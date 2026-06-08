<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Scripting-Python_3-3776AB?style=for-the-badge&logo=python&logoColor=white" alt="Python">
  <img src="https://img.shields.io/badge/Min_SDK-31_(Android_12)-34A853?style=for-the-badge" alt="Min SDK">
</p>

<h1 align="center">Far_Auto</h1>
<p align="center">
  <b>Self-contained, background-ready Android automation powered by Python — no root, no ADB, no PC.</b>
</p>

<p align="center">
  Write Python scripts that see, tap, swipe, and type on any Android app.<br>
  Manage everything from a modern, real-time web console.
</p>

---

## ✨ Features

- 🐍 **Full Python Runtime** — Integrated CPython via Chaquopy.
- 🔓 **Zero Root / Zero ADB** — Uses the official Android `AccessibilityService` API.
- 📱 **Modern UI** — Sleek Dark Mode (Material 3) with animated rolling menus and card layouts.
- 🏃 **Background Persistence** — High-priority foreground service with **WakeLocks** ensures scripts continue running when you switch apps or turn off the screen.
- 🌐 **Modern Web Console** — Professional developer interface with the **Ace Code Editor**, syntax highlighting, and instant terminal feedback.
- ⚡ **Performance Optimized** — Kotlin-side UI filtering and **In-Memory** logging for zero-lag interaction.
- 🤖 **Live Agent Mode (MCP)** — Connect external AI models (Claude, Cursor, Cline) to control your device using the Model Context Protocol.
- 🛡️ **Security First** — Path-traversal protection and cryptographically secure auth tokens.
- 📦 **Backup & Import** — One-tap "Export All" to ZIP and easy script importing from device storage.
- 🗒️ **Run Logs** — Every script run (web or app) is saved to a timestamped log file (`script_name_YYYY-MM-DD_HH-mm-ss.txt`); the latest 50 runs are kept.
- 🎥 **Screen Recording** — Record the screen to MP4 from scripts after a one-time consent (MediaProjection).

---

## 🏗️ Architecture

Far_Auto runs as a **single process** designed for high reliability:

```
┌──────────────────────────────────────────────────────┐
│  Android Process (com.fareed.auto)                   │
│                                                      │
│  ┌──────────────┐    ┌─────────────────────────────┐ │
│  │ MainActivity │    │ ScriptExecutionService      │ │
│  │  Modern UI    │    │  (Foreground Service)       │ │
│  │  Script List  │    │  - Python Engine (Chaquopy) │ │
│  │  Import/Export│    │  - Web Dashboard Server     │ │
│  └──────┬───────┘    │  - CPU WakeLock             │ │
│         │            │  - In-Memory Log Buffer     │ │
│         ▼            └────────────┬────────────────┘ │
│  ┌───────────────────────────────────────────────┐   │
│  │            AutomatorBridge                    │   │
│  │   Safe Threading · Deep Text Crawling         │   │
│  │   Kotlin-side Node Filtering (Fast)           │   │
│  └──────────────────┬────────────────────────────┘   │
│                     │                                │
│                     ▼                                │
│  ┌───────────────────────────────────────────────┐   │
│  │      FarAutoAccessibilityService              │   │
│  │   UI Tree Inspection · Gesture Injection      │   │
│  │   Double-Tap Volume Down Kill Switch          │   │
│  └───────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start

### 1. Enable Engine
Grant the **Accessibility Service** permission to allow the app to interact with other apps. On Android 13+, you may need to "Allow Restricted Settings" in the app info screen first.

### 2. Connect via Web
Open the URL shown on the main screen (e.g., `http://192.168.1.5:8080`) in your laptop browser. Enter the 12-character token to unlock the **Console**.

### 3. Run the Explorer
Launch the bundled `ui_explorer.py`. It provides a real-time interactive terminal where you can navigate your phone using shortcuts:
- `b`: Back | `h`: Home | `rec`: Recents
- `up`/`dw`: Scroll | `lt`/`rt`: Swipe
- `ss`: Screenshot | `close`: Close current app from Recents
- `cls`: Clear terminal | `exit`: Quit the explorer
- `[number]`: Click a UI element or input text

---

## 🐍 Python API (automator module)

All functions return `None`/`False` on failure and never raise exceptions into your script.

### 👆 Gestures & Input

| Function | Description |
|---|---|
| `automator.click(x, y)` | Precise coordinate tap |
| `automator.click_element(el)` | Clicks the center of an element object (from `find_elements` etc.) |
| `automator.swipe(x1, y1, x2, y2, ms)` | Gesture injection |
| `automator.input_text("msg")` | Type into focused field (clears first) |

### 🔍 Screen Inspection

| Function | Description |
|---|---|
| `automator.get_root()` | Returns the root node of the active window as JSON |
| `automator.dump_tree()` | Returns full UI hierarchy as JSON |
| `automator.find_elements(id, text)` | Find elements matching resource ID or text |
| `automator.wait_for_element(id, text, ms)` | Wait until element appears |
| `automator.get_interactable_elements()` | Returns optimized JSON of all visible buttons/inputs |
| `automator.get_screen_size()` | Returns `[width, height]` |
| `automator.is_secure_window()` | Returns `True` if screen content is hidden (FLAG_SECURE) |

### 📸 Screenshots

| Function | Description |
|---|---|
| `automator.take_screenshot()` | Returns Base64-encoded JPEG of current screen |
| `automator.save_screenshot(name)` | Saves screenshot to the scripts folder, returns the file path |

### 🎥 Screen Recording

Requires a **one-time consent**: open **Settings → Enable Screen Recording**, allow the microphone prompt (needed for internal-audio capture), and approve the screen-capture prompt. Consent lasts for the app session; recordings (H264 video + AAC internal audio, muxed to MP4) are saved to the `FAR_auto recordings` folder. If the audio permission is denied — or an app marks its audio non-capturable — recording falls back to video-only.

| Function | Description |
|---|---|
| `automator.is_screen_record_ready()` | `True` once screen-capture consent has been granted |
| `automator.start_screen_record(filename=None)` | Starts recording the screen to an MP4 (auto-named if omitted). Returns `True` on start |
| `automator.stop_screen_record()` | Stops the recording and returns the saved file path |

### 📱 App & System Navigation

| Function | Description |
|---|---|
| `automator.launch_app("pkg")` | Launch app by package name |
| `automator.force_stop_app("pkg")` | Force stop an app via system settings |
| `automator.close_app_from_recents()` | Swipes away the current app in Recents view |
| `automator.press_back()` / `_home()` / `_recent()` | System navigation |

### 🔔 Toast Capture

| Function | Description |
|---|---|
| `automator.get_last_toast()` | Returns text of the last captured toast (status-bar notifications are ignored) |
| `automator.get_last_toast_package()` | Returns the package name that posted the last captured toast |
| `automator.wait_for_toast(ms, package=None)` | Blocking wait for the next toast, optionally only from the given package |
| `automator.set_toast_filter("pkg")` | Only capture toasts from this package; `set_toast_filter(None)` captures from all apps |
| `automator.clear_last_toast()` | Clears the stored toast text and package |

### 🛠️ Utilities

| Function | Description |
|---|---|
| `automator.clear_logs()` | Instantly wipes the in-memory terminal |

---

## 🌐 Web Console

The new web interface is a full-featured IDE and Terminal:
- **Ace Editor**: Full syntax highlighting and autocompletion for Python.
- **Integrated Workflow**: "Save & Run" directly from the editor tab.
- **Smart Console**: Automatic bottom-scrolling with manual scroll override.
- **Management**: Rename, Reset, or Delete scripts with ease.

---

## 🤖 Live Agent Mode (MCP)

Far_Auto now supports the **Model Context Protocol (MCP)**, allowing AI agents (like Claude Desktop, Cursor, or Cline) to interact with your phone in real-time.

### Setup
1. Open the dashboard on your computer.
2. Tap the **MCP** text in the app header on your phone.
3. Follow the instructions in the dashboard modal to:
   - Install dependencies (`pip install mcp httpx`).
   - Save the bridge script (`far_auto_mcp.py`).
   - Add the config to Claude, Cursor, or Cline (the IP and Token are auto-filled!).

### Exposed Tools
The MCP server exposes the following tools to the AI:
- `get_screen_info`: Returns a list of interactable UI elements.
- `tap_element`: Taps on specific coordinates.
- `type_text`: Types text into focused fields.
- `navigate`: Performs back, home, or recents actions.
- `open_app`: Launches apps by package name.
- `get_screenshot`: Captures a visual screenshot.

---

## 📄 License & Safety
Provided for educational and personal automation purposes. 
- **Privacy**: The live terminal is RAM-only. Each script run is additionally saved to a timestamped log file in the `FAR_auto logs` folder (auto-pruned to the latest 50 runs).
- **Control**: Stop any script instantly via the notification button or hardware volume keys.

<p align="center">
  <b>Built for Android Power Users.</b>
</p>
