# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK
./gradlew test                   # Unit tests
./gradlew connectedAndroidTest   # Instrumented tests
./gradlew clean build            # Clean rebuild
```

There is currently **no test source set** (`app/src/test/` and `app/src/androidTest/` do not exist), so the test tasks compile and pass with zero tests. Add tests under those directories when introducing them. All source lives in the single `app/src/main` set.

## Architecture

Far_Auto is a **single-process** Android app (package `com.fareed.auto`) that lets users write Python scripts to automate the UI of other Android apps — no root, no ADB. It embeds a Python runtime via **Chaquopy** and uses the **AccessibilityService API** for UI tree inspection and gesture injection.

### Component Map

| Component | Role |
|---|---|
| `MainActivity.kt` | Script list UI, storage management, permission flow |
| `ScriptExecutionService.kt` | Foreground service hosting the Chaquopy Python engine, WakeLock, log buffer, dashboard startup |
| `FarAutoAccessibilityService.kt` | Main-thread AccessibilityService — UI tree access, gesture dispatch, volume-key kill switch |
| `AutomatorBridge.kt` | Thread-safe Kotlin↔Python bridge (see Threading below) |
| `DashboardServer.kt` | NanoHTTPD web server — token auth, JSON-RPC for MCP, script execution endpoints |
| `EditorActivity.kt` | In-app Python editor (Sora Editor) with syntax checking |
| `SettingsActivity.kt` | Dashboard port, token, MCP toggle — persisted to SharedPreferences |
| `McpActivity.kt` | MCP setup for Claude/Cursor/Cline AI agent integration |
| `KillSwitchReceiver.kt` | BroadcastReceiver for `com.fareed.auto.ACTION_KILL_SCRIPT` |
| `app/src/main/python/automator.py` | Python API exposed to user scripts |
| `app/src/main/python/logger_util.py` | Redirects Python stdout/stderr to Kotlin logger |
| `app/src/main/assets/web/dashboard.html` | Web IDE with Ace Editor and live terminal |
| `app/src/main/assets/web/far_auto_mcp.py` | MCP bridge script for AI agent tools |

`com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt` is a **deprecated no-op legacy identity** — the live accessibility service is `FarAutoAccessibilityService`. Do not wire new logic into the SelectToSpeak class.

### Data Flow

```
Python Script (Chaquopy, background thread)
    ↓  calls automator.click() / find_elements() / etc.
AutomatorBridge (posts Runnable via Handler)
    ↓  CompletableFuture.get(5s timeout)
FarAutoAccessibilityService (main thread)
    ↓  getRootInActiveWindow() / dispatchGesture()
Android UI tree / gesture injection
```

## Non-Negotiable Rules

These patterns must never be changed (see `RULES.md` and `ARCHITECTURE.md` for full rationale):

**Threading — every `AutomatorBridge` method must follow this exact pattern:**
```kotlin
fun someCall(): ResultType {
    val future = CompletableFuture<ResultType>()
    Handler(Looper.getMainLooper()).post {
        future.complete(accessibilityService?.someAccessibilityApi())
    }
    return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { null }
}
```
`getRootInActiveWindow()` and `dispatchGesture()` return `null` or deadlock off the main thread. The `CompletableFuture` + `Handler` bridge is the only safe mechanism.

**Kill flag must be `AtomicBoolean`** — accessed concurrently from the Python thread and main thread.

**Foreground service** must declare `android:foregroundServiceType="specialUse"` and call `startForeground()` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+. Omitting this crashes on Android 14.

**`launch_app()` must use the foreground service context** (`ScriptExecutionService`) to call `startActivity()`. Background activity launches are silently blocked on Android 12+.

**Dashboard security (all non-negotiable):**
- Token auth on every route — HTTP 401 on mismatch, no exceptions. Token is a 256-bit `SecureRandom` value, URL-safe Base64, regenerated each server start. Accept it via the `X-Automator-Token` header or `token` query param.
- CORS allowed origin is `http://<device-ip>:<port>` only.
- `/run` is rate-limited to 10 requests/minute per IP.
- Server binds to `127.0.0.1` by default. LAN binding (`0.0.0.0`) requires explicit user opt-in **and** a persistent warning banner.

**Storage must use `getFilesDir()`** (scripts → `getFilesDir()/scripts/`, logs → `getFilesDir()/logs/`). Never write to `/sdcard/` automatically. `getExternalFilesDir()` only on explicit user-triggered exports.

**Never call `Settings.Secure.putString()`** to enable the accessibility service — requires root.

## Python API Contract

All `automator` functions return `None`/`False` on failure and never raise exceptions into user scripts. Exceptions are logged and swallowed at the Chaquopy boundary. Scripts check `automator.is_killed()` to support graceful termination.

Public API (`app/src/main/python/automator.py`): `click`, `swipe`, `input_text`, `press_back`/`press_home`/`press_recent`, `launch_app`, `get_root`/`dump_tree`, `find_elements`/`wait_for_element`/`get_interactable_elements`, `get_screen_size`, `is_secure_window`, toast helpers (`get_last_toast`/`get_last_toast_package`/`clear_last_toast`/`wait_for_toast`/`set_toast_filter` — toasts only, status-bar notifications are ignored), and `clear_logs`. Each is a thin wrapper over an `AutomatorBridge` method following the threading pattern above.

## Tech Stack

- **Kotlin 1.9.22**, minSdk 31 (Android 12), targetSdk 36 (Android 16)
- **Chaquopy 15.0.1** — embedded Python runtime
- **NanoHTTPD 2.3.1** — web dashboard server
- **Sora Editor 0.21.0** — in-app code editor
- **Gradle 8.10.2 / AGP 8.2.2**

## Code Conventions

- Kotlin only (no Java); `camelCase` for Kotlin, `snake_case` for Python API and Android resources
- 4-space indentation, 120-char line limit, trailing commas required in Kotlin
- Broadcast action: `com.fareed.auto.ACTION_KILL_SCRIPT`
- Notification channel ID: `script_execution_channel`

## FLAG_SECURE Windows

When a target app uses `FLAG_SECURE` (banking, DRM), `getRootInActiveWindow()` returns `null` — tree queries return empty/`None`. Gesture injection may still work on AOSP but can be blocked by OEM layers (Knox, MIUI). Use `automator.is_secure_window()` to detect and implement coordinate-based fallbacks.

## Reference Docs

`RULES.md` is the canonical source for non-negotiable constraints; `ARCHITECTURE.md` covers the full design. `GEMINI.md`, `FULL_DOCS.md`, `Far_Auto_Documentation.md`, and `TASKS.md` are supplementary/historical — defer to `RULES.md` and the code when they conflict.
