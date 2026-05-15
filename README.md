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
- 🛡️ **Security First** — Path-traversal protection and cryptographically secure auth tokens.
- 📦 **Backup & Import** — One-tap "Export All" to ZIP and easy script importing from device storage.

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
- `[number]`: Click a UI element or input text

---

## 🐍 Python API (automator module)

| Function | Description |
|---|---|
| `automator.click(x, y)` | Precise coordinate tap |
| `automator.swipe(x1, y1, x2, y2, ms)` | Gesture injection |
| `automator.input_text("msg")` | Type into focused field |
| `automator.get_interactable_elements()` | Returns optimized JSON of all visible buttons/inputs |
| `automator.press_back()` / `_home()` | System navigation |
| `automator.clear_logs()` | Instantly wipes the in-memory terminal |
| `automator.get_screen_size()` | Returns `[width, height]` |

---

## 🌐 Web Console

The new web interface is a full-featured IDE and Terminal:
- **Ace Editor**: Full syntax highlighting and autocompletion for Python.
- **Integrated Workflow**: "Save & Run" directly from the editor tab.
- **Smart Console**: Automatic bottom-scrolling with manual scroll override.
- **Management**: Rename, Reset, or Delete scripts with ease.

---

## 📄 License & Safety
Provided for educational and personal automation purposes. 
- **Privacy**: No logs are saved to disk; all script data is stored in RAM.
- **Control**: Stop any script instantly via the notification button or hardware volume keys.

<p align="center">
  <b>Built for Android Power Users.</b>
</p>
