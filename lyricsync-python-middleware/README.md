# LyricSync Python Middleware (Phase 1)

This service polls Windows SMTC playback progress and broadcasts lyric lines over WebSocket.
It fetches synced LRC lyrics online based on current `title + artist`, parses timestamps, and then performs timeline matching.
The fetch strategy is: NetEase (`pyncm`) first, then `lrclib` fallback.

## Environment

- OS: Windows 10/11
- Python: 3.10+

## Install

```powershell
cd e:\game\mc\mods\musicshow\lyricsync-python-middleware
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

## Run

```powershell
cd e:\game\mc\mods\musicshow\lyricsync-python-middleware
python server.py
```

WebSocket endpoint:

- `ws://localhost:8765`

## Build Sidecar EXE (Recommended for distribution)

Build a standalone sidecar executable (no Python required on player machine):

```powershell
cd e:\game\mc\mods\musicshow\lyricsync-python-middleware
.\build_sidecar.ps1
```

Output:

- `dist/lyricsync-sidecar.exe`

For release, put `lyricsync-sidecar.exe` in one of these locations:

- Minecraft game root
- `mods/` directory
- `lyricsync-sidecar/` directory under game root

## Broadcast Payload

```json
{
  "type": "LYRIC_UPDATE",
  "data": {
    "text": "穿梭时间的画面的钟",
    "duration": 3500,
    "is_translated": false
  }
}
```

## Behavior Details

- Polling frequency is 10Hz (`0.1s` interval).
- Reads active media session from SMTC (`winsdk`).
- If `winsdk` is unavailable or SMTC read fails, it falls back to an internal simulated timeline so you can test rendering.
- On track change (`title + artist`), the server fetches synced lyrics online (NetEase first, `lrclib` fallback), then caches results by track id.
- If no synced LRC is found for that track, it logs and does not broadcast lyrics for that song.
- On backward seek detection, lyric pointer is re-synced to current playback position.

## Quick Test

1. Start `server.py`.
2. Connect a WebSocket client to `ws://localhost:8765`.
3. Play any media in Windows (or rely on fallback simulation).
4. Observe `LYRIC_UPDATE` messages at line timestamps.

## Minimal Python Test Client (TUI)

This repo includes `test_client.py` for quick local verification.

Run server in one terminal:

```powershell
cd e:\game\mc\mods\musicshow\lyricsync-python-middleware
python server.py
```

Run test client in another terminal:

```powershell
cd e:\game\mc\mods\musicshow\lyricsync-python-middleware
python test_client.py
```

The client refreshes a simple terminal TUI in real time, showing:
- connection status
- latest lyric line
- message counters (`total`, `lyric`, `ignored`)
