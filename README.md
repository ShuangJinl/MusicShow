# MusicShow (LyricSync)

**LyricSync** is a **Fabric client-only** mod for **Minecraft 1.21.11** that reads **live playback** from music apps on your PC (for example **NetEase Cloud Music** on Windows), fetches **time-synced lyrics (LRC)**, and renders each line as **floating text in the world** in front of you—fade in/out, optional particles, and a small dynamic light tied to the text.

The in-game client talks to a small **local WebSocket service** (“sidecar”) that runs outside Minecraft. This repository contains **both** the Fabric mod and that service.

| Component | Role |
|-----------|------|
| `lyricsync-fabric-client` | Minecraft mod: WebSocket client, world-space `TextDisplay` rendering, settings UI |
| `lyricsync-python-middleware` | Windows sidecar: SMTC playback → lyric timeline → `ws://localhost:8765` |

---

## Features

### Playback & lyrics pipeline

- **Windows System Media Transport Controls (SMTC)** at ~10 Hz for the active session (title, artist, position)—works with many players that integrate with Windows media controls (including NetEase Cloud Music when exposed to SMTC).
- **Synced lyrics**: resolves LRC using **NetEase (`pyncm`) first**, then **LRCLIB** as fallback, keyed by current title + artist; caches per track; handles backward seeks by re-aligning the lyric pointer.
- **WebSocket broadcast** to the game on `ws://localhost:8765` with JSON messages (`LYRIC_UPDATE`: text, duration, translation flag).
- If SMTC is unavailable, the sidecar can fall back to an **internal simulated timeline** for testing rendering.

### In-world presentation

- **Floating text** using Minecraft **text display** entities, placed in view with raycast-friendly sampling and configurable distance / vertical offset.
- **Fade in / fade out** and a short cross-fade when the next line arrives early.
- **Ambient particles** while a line is visible, plus a denser burst while fading out (type and count are configurable).
- **Per-line light**: a small **Light** block display follows the text and scales with opacity; removed when the line is fully gone.
- **“Glow ink” style** (optional): subtle **opacity pulse** + italic styling while the line is fully visible; can be turned off in settings.

### Controls & configuration

- **`O`** opens the **settings screen** (also **`/lyricsync`** client command).
- Settings are saved to **`config/lyricsync-client.json`** (under your Minecraft instance config folder).

Notable options include:

| Option | Meaning |
|--------|---------|
| **Follow Facing** | When enabled, the text **keeps rotating** to face you every tick (same rotation gate as below). |
| **Fixed Mode: Rotate To Face Player** | When enabled, the text **keeps rotating** to face you every tick. When **both** this and **Follow Facing** are off, the text **only** snaps to face you **once** when the line spawns, then keeps that orientation. (If either option is on, billboarding stays on.) |
| **Glow Ink Flash Style** | **On**: flashy pulse + italic; **Off**: steady appearance. |
| **Random Color / Fixed Color**, **Bold**, **Text Shadow**, **Transparent Background** | Text styling. |
| **Min / Max Distance**, **Text Y Offset** | Placement in front of the camera. |
| **Particle Count / Y Offset / Particle Type** | Ambient and exit particles (`END_ROD`, `ENCHANT`, `PORTAL`, etc.). |

### Auto-start sidecar (optional)

On game start, if nothing is listening on **127.0.0.1:8765**, the mod tries to launch:

1. **`lyricsync-sidecar.exe`** from the game directory, `mods/`, or `lyricsync-sidecar/` (see mod code for exact search paths), **or**
2. **`python server.py`** inside **`lyricsync-python-middleware`** next to the game directory (or a path overridden by JVM properties).

Advanced JVM properties (launcher “JVM arguments”):

- **`-Dlyricsync.sidecar.command=...`** — full command split by spaces to start the sidecar.
- **`-Dlyricsync.sidecar.dir=...`** — directory used for the default `python server.py` fallback.

Sidecar stdout/stderr append to **`lyricsync-sidecar.log`** in the sidecar working directory when the mod spawns the process.

---

## Requirements

| | |
|--|--|
| **Minecraft** | **1.21.11** (see `fabric.mod.json` in the mod) |
| **Loader** | **Fabric** + **Fabric API** |
| **Java** | **21+** |
| **Lyrics service** | **Windows 10/11** recommended for SMTC-based capture; **Python 3.10+** if you run `server.py` instead of the packaged EXE |

---

## Quick start

### 1. Sidecar (pick one)

**Option A — Python (dev / flexible)**

```powershell
cd lyricsync-python-middleware
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python server.py
```

**Option B — Standalone EXE (players)**

Build with `lyricsync-python-middleware/build_sidecar.ps1`, then place **`lyricsync-sidecar.exe`** in the game root or `mods/` as described in [lyricsync-python-middleware/README.md](lyricsync-python-middleware/README.md).

Keep **`ws://localhost:8765`** reachable while playing.

### 2. Mod

1. Build the Fabric project (see **Building** below) or use a prebuilt jar from releases (if you publish one).
2. Put **`lyricsync-*.jar`** in your `.minecraft/mods/` folder together with **Fabric API**.
3. Launch the game, start the sidecar, play music with SMTC-capable software, and join a world—lyrics should appear in front of you.

Open settings with **`O`** or **`/lyricsync`**.

---

## Building the mod

From `lyricsync-fabric-client`:

```powershell
.\gradlew.bat build
```

Remapped mod jar (typical install artifact):

- `lyricsync-fabric-client/build/libs/lyricsync-<version>.jar`

The **`-sources.jar`** is for development only; do not put it in `mods/`.

---

## Protocol (for integrations)

The sidecar pushes messages like:

```json
{
  "type": "LYRIC_UPDATE",
  "data": {
    "text": "Example lyric line",
    "duration": 3500,
    "is_translated": false
  }
}
```

More detail: [lyricsync-python-middleware/README.md](lyricsync-python-middleware/README.md).

---

## Troubleshooting

| Symptom | Things to check |
|---------|------------------|
| No lyrics in game | Sidecar running? Firewall blocking localhost? Check game log for WebSocket errors. |
| Sidecar not auto-starting | Place `lyricsync-sidecar.exe` or clone this repo so `lyricsync-python-middleware/server.py` exists relative to the game directory; or set `-Dlyricsync.sidecar.command=...`. |
| No LRC for a song | That track may have no synced lyrics in the online sources; sidecar logs the miss. |
| GitHub `git push` fails on port 443 | Network/proxy issue on your PC; try system VPN/proxy or configure Git HTTP proxy for `https://github.com`. |

---

## Repository layout

```
musicshow/
├── lyricsync-fabric-client/     # Fabric mod (Java)
├── lyricsync-python-middleware/ # WebSocket + SMTC + lyric fetch (Python)
└── README.md                    # This file
```

---

## License

The Minecraft mod metadata declares **MIT** (see `lyricsync-fabric-client/src/main/resources/fabric.mod.json`). If you add a separate license for the Python service, document it in `lyricsync-python-middleware/` and update this section.

---

## 中文简介

**LyricSync** 是一款面向 **Minecraft 1.21.11** 的 **Fabric 纯客户端**模组：在 **Windows** 上通过 **SMTC** 读取本机正在播放的音乐（例如网易云音乐等支持系统媒体控制的播放器），在线拉取 **带时间轴的歌词（LRC）**，经本地 **WebSocket（`ws://localhost:8765`）** 推送给游戏，并在世界中以 **悬浮文字** 展示，支持淡入淡出、粒子、可选“荧光墨囊”风格闪烁，以及随文字生命周期变化的 **小光源**。

仓库包含 **Java 模组** 与 **Python 中间件**；详细行为与消息格式见 [lyricsync-python-middleware/README.md](lyricsync-python-middleware/README.md)。
