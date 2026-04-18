import contextlib
import asyncio
import json
from datetime import datetime

import websockets


WS_URI = "ws://localhost:8765"


class TuiState:
    def __init__(self) -> None:
        self.connection_status = "DISCONNECTED"
        self.total_messages = 0
        self.lyric_messages = 0
        self.ignored_messages = 0
        self.last_lyric_text = "-"
        self.last_duration_ms = 0
        self.last_is_translated = False
        self.last_update_time = "-"
        self.last_error = "-"

    def render(self) -> str:
        return (
            "\x1b[2J\x1b[H"
            "LyricSync Test Client (TUI)\n"
            "Press Ctrl+C to quit.\n"
            "----------------------------------------\n"
            f"WebSocket URI   : {WS_URI}\n"
            f"Connection      : {self.connection_status}\n"
            f"Total Messages  : {self.total_messages}\n"
            f"Lyric Messages  : {self.lyric_messages}\n"
            f"Ignored Messages: {self.ignored_messages}\n"
            "----------------------------------------\n"
            f"Last Update Time: {self.last_update_time}\n"
            f"Last Lyric      : {self.last_lyric_text}\n"
            f"Duration (ms)   : {self.last_duration_ms}\n"
            f"Is Translated   : {self.last_is_translated}\n"
            f"Last Error      : {self.last_error}\n"
        )


async def tui_loop(state: TuiState) -> None:
    while True:
        print(state.render(), end="", flush=True)
        await asyncio.sleep(0.2)


async def receive_loop(websocket, state: TuiState) -> None:
    async for raw in websocket:
        state.total_messages += 1
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            state.ignored_messages += 1
            continue

        if payload.get("type") != "LYRIC_UPDATE":
            state.ignored_messages += 1
            continue

        data = payload.get("data", {})
        state.lyric_messages += 1
        state.last_lyric_text = str(data.get("text", ""))
        state.last_duration_ms = int(data.get("duration", 0))
        state.last_is_translated = bool(data.get("is_translated", False))
        state.last_update_time = datetime.now().strftime("%H:%M:%S")


async def run_client() -> None:
    state = TuiState()
    tui_task = asyncio.create_task(tui_loop(state))
    try:
        while True:
            try:
                state.connection_status = "CONNECTING"
                async with websockets.connect(WS_URI, ping_interval=20) as websocket:
                    state.connection_status = "CONNECTED"
                    state.last_error = "-"
                    await receive_loop(websocket, state)
            except Exception as exc:
                state.last_error = f"{type(exc).__name__}: {exc}"
                state.connection_status = "RECONNECTING (retry in 1s)"
                await asyncio.sleep(1.0)
    finally:
        tui_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await tui_task


if __name__ == "__main__":
    try:
        asyncio.run(run_client())
    except KeyboardInterrupt:
        print("Client stopped.")
