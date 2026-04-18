import asyncio
import importlib
import json
import logging
import re
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path
from typing import Any, Optional

import requests
import websockets

try:
    from winsdk.windows.media.control import (
        GlobalSystemMediaTransportControlsSessionManager as MediaManager,
    )
except Exception:  # pragma: no cover - runtime environment dependent
    MediaManager = None


HOST = "localhost"
PORT = 8765
POLL_INTERVAL_SECONDS = 0.1  # 10Hz
LAST_LINE_DEFAULT_DURATION_MS = 3000
LYRIC_FETCH_TIMEOUT_SECONDS = 8
LOCAL_LRC_DIR = "local_lrc"

LRC_TIMESTAMP_PATTERN = re.compile(r"\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]")
VERSION_SUFFIX_PATTERN = re.compile(r"(\s*[-_ ]?\d+(\.\d+)?\s*$)")
PAREN_CONTENT_PATTERN = re.compile(r"[（(].*?[)）]")


@dataclass
class LyricLine:
    time_seconds: float
    text: str
    duration_ms: int


@dataclass
class MediaSnapshot:
    title: str
    artist: str
    position_ms: int
    is_playing: bool

    @property
    def track_id(self) -> str:
        return f"{self.title}::{self.artist}"


class LyricTimeline:
    def __init__(self, raw_lines: list[dict[str, Any]]):
        sorted_lines = sorted(raw_lines, key=lambda item: item["time"])
        self.lines: list[LyricLine] = []
        for index, line in enumerate(sorted_lines):
            if index + 1 < len(sorted_lines):
                duration_ms = int((sorted_lines[index + 1]["time"] - line["time"]) * 1000)
                duration_ms = max(duration_ms, 300)
            else:
                duration_ms = LAST_LINE_DEFAULT_DURATION_MS
            self.lines.append(
                LyricLine(
                    time_seconds=float(line["time"]),
                    text=str(line["text"]),
                    duration_ms=duration_ms,
                )
            )

        self.pointer = 0
        self.last_position_ms = 0

    def reset(self) -> None:
        self.pointer = 0
        self.last_position_ms = 0

    def sync_for_seek(self, position_ms: int) -> None:
        position_seconds = position_ms / 1000.0
        pointer = 0
        while pointer < len(self.lines) and self.lines[pointer].time_seconds <= position_seconds:
            pointer += 1
        self.pointer = pointer
        self.last_position_ms = position_ms

    def collect_due(self, position_ms: int) -> list[LyricLine]:
        if position_ms + 600 < self.last_position_ms:
            logging.info("Detected playback seek backward. Re-sync lyric pointer.")
            self.sync_for_seek(position_ms)
            return []

        due: list[LyricLine] = []
        position_seconds = position_ms / 1000.0
        while self.pointer < len(self.lines):
            current = self.lines[self.pointer]
            if position_seconds >= current.time_seconds:
                due.append(current)
                self.pointer += 1
            else:
                break

        self.last_position_ms = position_ms
        return due


class LyricSyncServer:
    def __init__(self) -> None:
        self.clients: set[Any] = set()
        self.timeline: Optional[LyricTimeline] = None
        self.current_track_id: Optional[str] = None
        self.media_manager = None
        self.last_payload_json: Optional[str] = None
        self.lyric_cache: dict[str, Optional[list[dict[str, Any]]]] = {}

    async def init_media_manager(self) -> None:
        if MediaManager is None:
            logging.warning("winsdk is unavailable. Media polling will use fallback simulation.")
            return

        try:
            self.media_manager = await MediaManager.request_async()
        except Exception as exc:
            logging.warning("Failed to initialize SMTC manager: %s", exc)
            self.media_manager = None

    async def read_media_state(self) -> Optional[MediaSnapshot]:
        if self.media_manager is None:
            return self.simulated_media_state()

        try:
            session = self.media_manager.get_current_session()
            if session is None:
                return None

            info = await session.try_get_media_properties_async()
            timeline = session.get_timeline_properties()
            playback = session.get_playback_info()
            status = playback.playback_status
            is_playing = self._is_playing_status(status)

            position_ms = self._timedelta_to_ms(timeline.position)
            title = (info.title or "").strip()
            artist = (info.artist or "").strip()
            if not title:
                title = "Unknown Title"
            if not artist:
                artist = "Unknown Artist"

            return MediaSnapshot(
                title=title,
                artist=artist,
                position_ms=position_ms,
                is_playing=is_playing,
            )
        except Exception as exc:
            logging.warning("SMTC polling failed, fallback simulation enabled: %s", exc)
            return self.simulated_media_state()

    @staticmethod
    def _is_playing_status(status: Any) -> bool:
        # SMTC enum may appear as enum value, enum name, or plain int depending on environment.
        # 4 is PLAYING in GlobalSystemMediaTransportControlsSessionPlaybackStatus.
        try:
            if int(status) == 4:
                return True
        except Exception:
            pass

        text = str(status).lower()
        return "playing" in text

    def simulated_media_state(self) -> MediaSnapshot:
        loop_time = asyncio.get_running_loop().time()
        position_ms = int((loop_time % 22) * 1000)
        return MediaSnapshot(
            title="LyricSync Test Song",
            artist="Local Demo",
            position_ms=position_ms,
            is_playing=True,
        )

    @staticmethod
    def _timedelta_to_ms(value: timedelta) -> int:
        return int(value.total_seconds() * 1000)

    @staticmethod
    def _split_primary_artist(artist: str) -> str:
        separators = [" / ", ",", "&", "、"]
        result = artist
        for separator in separators:
            if separator in result:
                result = result.split(separator)[0].strip()
        return result.strip()

    @staticmethod
    def _normalize_title(title: str) -> str:
        cleaned = title.strip()
        cleaned = PAREN_CONTENT_PATTERN.sub("", cleaned).strip()
        cleaned = VERSION_SUFFIX_PATTERN.sub("", cleaned).strip()
        return cleaned

    @staticmethod
    def _parse_lrc_text(lrc_text: str) -> list[dict[str, Any]]:
        parsed: list[dict[str, Any]] = []
        for raw_line in lrc_text.splitlines():
            text = LRC_TIMESTAMP_PATTERN.sub("", raw_line).strip()
            if not text:
                continue
            matches = LRC_TIMESTAMP_PATTERN.findall(raw_line)
            if not matches:
                continue
            for minute, second, fraction in matches:
                minute_val = int(minute)
                second_val = int(second)
                fraction_val = int((fraction or "0").ljust(3, "0")[:3])
                total_seconds = minute_val * 60 + second_val + fraction_val / 1000.0
                parsed.append({"time": total_seconds, "text": text})
        parsed.sort(key=lambda item: item["time"])
        return parsed

    def _fetch_lrc_sync(self, title: str, artist: str) -> Optional[list[dict[str, Any]]]:
        # 0) Local override files (manual correction / offline fallback)
        local_lines = self._fetch_lrc_from_local_files(title, artist)
        if local_lines:
            return local_lines

        # 1) Prefer Netease (better hit rate for Chinese songs)
        netease = self._fetch_lrc_from_netease(title, artist)
        if netease:
            return netease

        # 2) Fallback to lrclib
        return self._fetch_lrc_from_lrclib(title, artist)

    def _fetch_lrc_from_local_files(self, title: str, artist: str) -> Optional[list[dict[str, Any]]]:
        base_dir = Path(__file__).resolve().parent / LOCAL_LRC_DIR
        if not base_dir.exists():
            return None

        normalized_title = self._normalize_title(title)
        primary_artist = self._split_primary_artist(artist)

        candidates = [
            base_dir / f"{normalized_title} - {primary_artist}.lrc",
            base_dir / f"{title} - {primary_artist}.lrc",
            base_dir / f"{normalized_title}.lrc",
            base_dir / f"{title}.lrc",
        ]

        for path in candidates:
            if not path.exists():
                continue
            try:
                text = path.read_text(encoding="utf-8")
                parsed = self._parse_lrc_text(text)
                if parsed:
                    logging.info("Loaded local LRC file: %s", path.name)
                    return parsed
            except Exception as exc:
                logging.warning("Failed to read local LRC file %s: %s", path, exc)
        return None

    def _fetch_lrc_from_lrclib(self, title: str, artist: str) -> Optional[list[dict[str, Any]]]:
        primary_artist = self._split_primary_artist(artist)
        normalized_title = self._normalize_title(title)
        headers = {"User-Agent": "LyricSync/0.1"}
        try:
            exact_resp = requests.get(
                "https://lrclib.net/api/get",
                params={"track_name": normalized_title or title, "artist_name": primary_artist},
                headers=headers,
                timeout=LYRIC_FETCH_TIMEOUT_SECONDS,
            )
            if exact_resp.ok:
                payload = exact_resp.json()
                synced = payload.get("syncedLyrics") or ""
                parsed = self._parse_lrc_text(synced)
                if parsed:
                    return parsed

            search_resp = requests.get(
                "https://lrclib.net/api/search",
                params={"track_name": normalized_title or title, "artist_name": primary_artist},
                headers=headers,
                timeout=LYRIC_FETCH_TIMEOUT_SECONDS,
            )
            if not search_resp.ok:
                # Last fallback: search by title only
                title_only_resp = requests.get(
                    "https://lrclib.net/api/search",
                    params={"track_name": normalized_title or title},
                    headers=headers,
                    timeout=LYRIC_FETCH_TIMEOUT_SECONDS,
                )
                if not title_only_resp.ok:
                    return None
                search_resp = title_only_resp

            results = search_resp.json()
            if not isinstance(results, list):
                return None

            for item in results:
                synced = item.get("syncedLyrics") or ""
                parsed = self._parse_lrc_text(synced)
                if parsed:
                    return parsed
            return None
        except Exception as exc:
            logging.warning("lrclib lyric fetch failed for %s / %s: %s", title, artist, exc)
            return None

    def _fetch_lrc_from_netease(self, title: str, artist: str) -> Optional[list[dict[str, Any]]]:
        try:
            pyncm_apis = importlib.import_module("pyncm.apis")
        except Exception as exc:
            logging.warning("pyncm is unavailable: %s", exc)
            return None

        primary_artist = self._split_primary_artist(artist)
        normalized_title = self._normalize_title(title)
        keywords = [f"{normalized_title} {primary_artist}".strip()]
        if normalized_title and normalized_title != title:
            keywords.append(f"{title} {primary_artist}".strip())

        for keyword in keywords:
            try:
                search_data = pyncm_apis.cloudsearch.GetSearchResult(
                    keyword=keyword, limit=8, offset=0
                )
                songs = (((search_data or {}).get("result") or {}).get("songs") or [])
                if not songs:
                    continue

                selected_song_id = None
                for song in songs:
                    song_name = (song.get("name") or "").strip().lower()
                    artists = song.get("ar") or []
                    artist_names = " / ".join((a.get("name") or "").strip() for a in artists).lower()
                    if normalized_title.lower() in song_name and primary_artist.lower() in artist_names:
                        selected_song_id = song.get("id")
                        break

                if selected_song_id is None:
                    selected_song_id = songs[0].get("id")

                if selected_song_id is None:
                    continue

                lyric_data = pyncm_apis.track.GetTrackLyrics(song_id=int(selected_song_id))
                lrc_text = ((lyric_data or {}).get("lrc") or {}).get("lyric") or ""
                parsed = self._parse_lrc_text(lrc_text)
                if parsed:
                    return parsed
            except Exception as exc:
                logging.warning("Netease lyric fetch failed for keyword '%s': %s", keyword, exc)
                continue
        return None

    async def get_lyrics_for_track(self, track_id: str, title: str, artist: str) -> Optional[list[dict[str, Any]]]:
        if track_id in self.lyric_cache:
            return self.lyric_cache[track_id]
        logging.info("Fetching lyrics online for track: %s", track_id)
        result = await asyncio.to_thread(self._fetch_lrc_sync, title, artist)
        self.lyric_cache[track_id] = result
        if result:
            logging.info("Fetched %d lyric lines for %s", len(result), track_id)
        else:
            logging.info("No synced lyric found for %s", track_id)
        return result

    async def ws_handler(self, websocket) -> None:
        self.clients.add(websocket)
        logging.info("Client connected (%d online).", len(self.clients))
        if self.last_payload_json is not None:
            try:
                await websocket.send(self.last_payload_json)
                logging.info("Sent latest lyric snapshot to new client.")
            except Exception:
                logging.warning("Failed to send lyric snapshot to new client.")
        try:
            await websocket.wait_closed()
        finally:
            self.clients.discard(websocket)
            logging.info("Client disconnected (%d online).", len(self.clients))

    async def broadcast_lyric(self, line: LyricLine) -> None:
        payload = {
            "type": "LYRIC_UPDATE",
            "data": {
                "text": line.text,
                "duration": line.duration_ms,
                "is_translated": False,
            },
        }
        message = json.dumps(payload, ensure_ascii=False)
        self.last_payload_json = message
        if not self.clients:
            logging.info("Cached lyric (no clients): %s", line.text)
            return

        closed_clients: list[Any] = []
        for client in self.clients:
            try:
                await client.send(message)
            except Exception:
                closed_clients.append(client)

        for client in closed_clients:
            self.clients.discard(client)

        logging.info("Broadcast: %s (duration=%dms)", line.text, line.duration_ms)

    async def tick_loop(self) -> None:
        await self.init_media_manager()
        while True:
            snapshot = await self.read_media_state()
            if snapshot is None:
                await asyncio.sleep(POLL_INTERVAL_SECONDS)
                continue

            if snapshot.track_id != self.current_track_id:
                self.current_track_id = snapshot.track_id
                logging.info("Track changed -> %s / %s", snapshot.title, snapshot.artist)
                raw_lrc = await self.get_lyrics_for_track(
                    track_id=snapshot.track_id,
                    title=snapshot.title,
                    artist=snapshot.artist,
                )
                if raw_lrc:
                    self.timeline = LyricTimeline(raw_lrc)
                    logging.info("Lyric timeline loaded for track: %s", snapshot.track_id)
                else:
                    self.timeline = None
                    logging.info("Lyric timeline unavailable for track: %s", snapshot.track_id)

            if snapshot.is_playing and self.timeline is not None:
                due_lines = self.timeline.collect_due(snapshot.position_ms)
                for line in due_lines:
                    await self.broadcast_lyric(line)

            await asyncio.sleep(POLL_INTERVAL_SECONDS)

    async def run(self) -> None:
        logging.info("Starting LyricSync middleware at ws://%s:%d", HOST, PORT)
        async with websockets.serve(self.ws_handler, HOST, PORT, ping_interval=20):
            await self.tick_loop()


async def main() -> None:
    server = LyricSyncServer()
    await server.run()


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
    )
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logging.info("LyricSync middleware stopped.")
