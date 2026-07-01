import json
import time
import base64
import os

bridge = None # Injected by ScriptExecutionService
scripts_dir = "." # Injected by ScriptExecutionService
files_dir = "." # Injected by ScriptExecutionService (screenshots, etc.)
current_session = 0
last_stopped_session = -1


class ScriptStopped(KeyboardInterrupt):
    """[Control & Lifecycle] Exception raised on stop — a KeyboardInterrupt subclass, so a broad ``except Exception`` cannot swallow it.

    Subclasses KeyboardInterrupt — and therefore BaseException, NOT Exception — on
    purpose: a script's broad ``except Exception`` CANNOT swallow the stop signal, so
    stop is always honoured even in resilient try/except loops. Scripts that need
    cleanup on stop should use a ``finally:`` block, or catch
    ``(KeyboardInterrupt, automator.ScriptStopped)`` and then re-raise.
    """
    pass


def check_stop():
    """[Control & Lifecycle] Raise ScriptStopped if the user stopped the run. Call periodically in pure-Python loops."""
    # If the current session has been stopped, raise an un-swallowable stop signal.
    if current_session <= last_stopped_session:
        raise ScriptStopped("Script stopped by user")

def get_root():
    """[Screen Inspection] Full accessibility tree of the active window as a dict (or None if unavailable)."""
    check_stop()
    res = bridge.getRoot()
    return json.loads(res) if res else None

def click(x, y):
    """[Gestures & Input] Tap at screen coordinates (pixels). Returns True on success."""
    check_stop()
    return bridge.click(float(x), float(y))

def swipe(x1, y1, x2, y2, duration_ms=300):
    """[Gestures & Input] Swipe/drag from one point to another over the given duration."""
    check_stop()
    return bridge.swipe(float(x1), float(y1), float(x2), float(y2), int(duration_ms))

def press_back():
    """[Navigation & Apps] Back button (global action)."""
    check_stop()
    return bridge.pressBack()

def press_home():
    """[Navigation & Apps] Home button (global action)."""
    check_stop()
    return bridge.pressHome()

def press_recent():
    """[Navigation & Apps] Recents/overview button (global action)."""
    check_stop()
    return bridge.pressRecent()

def launch_app(package_name):
    """[Navigation & Apps] Launch an app by package name. Returns True if a launch intent was found."""
    check_stop()
    return bridge.launchApp(package_name)

def input_text(text):
    """[Gestures & Input] Replace the text in the currently focused input field."""
    check_stop()
    return bridge.inputText(text, True)

def is_secure_window():
    """[Screen Inspection] True if the foreground window is FLAG_SECURE (tree is unavailable — use coordinates)."""
    check_stop()
    return bridge.isSecureWindow()

def get_last_toast():
    """[Toasts] Text of the most recently captured toast (or None)."""
    check_stop()
    return bridge.getLastToast()

def clear_last_toast():
    """[Toasts] Forget the last captured toast."""
    check_stop()
    bridge.clearLastToast()

def get_last_toast_package():
    """[Toasts] Package name that posted the last captured toast."""
    check_stop()
    return bridge.getLastToastPackage()

def set_toast_filter(package_name=None):
    """[Toasts] Only capture toasts from this package; None captures from all apps."""
    # Only capture toasts from the given package; pass None to capture from all apps
    check_stop()
    bridge.setToastPackageFilter(package_name)

def wait_for_toast(timeout_ms=5000, package=None):
    """[Toasts] Poll until a toast appears; returns its text or None on timeout."""
    check_stop()
    start = time.time()
    while (time.time() - start) * 1000 < timeout_ms:
        check_stop()
        toast = get_last_toast()
        if toast:
            if package and get_last_toast_package() != package:
                time.sleep(0.2)
                continue
            return toast
        time.sleep(0.2)
    return None

def clear_logs():
    """[Control & Lifecycle] Clear the dashboard console log buffer."""
    check_stop()
    bridge.clearLogs()

def get_screen_size():
    """[Screen Inspection] Returns [width, height] in pixels."""
    check_stop()
    res = bridge.getScreenSize()
    return [int(x) for x in res.split(",")]

def dump_tree():
    """[Screen Inspection] Accessibility tree as a dict rooted at "root"."""
    check_stop()
    return json.loads(bridge.dumpTree())

def get_interactable_elements():
    """[Screen Inspection] Flattened list of clickable/focusable/input nodes, with deep text resolved."""
    check_stop()
    return json.loads(bridge.getInteractableNodes())

def take_screenshot():
    """[Screenshots & Recording] Capture the screen; returns a base64 JPEG string (or None)."""
    check_stop()
    return bridge.takeScreenshot()

def save_screenshot(filename="screenshot.jpg"):
    """[Screenshots & Recording] Capture and save into Far_Auto/files; returns the saved path or False."""
    check_stop()
    data = take_screenshot()
    if not data:
        return False
    try:
        # Saved into Far_Auto/files alongside screen recordings
        path = os.path.join(files_dir, os.path.basename(filename))
        with open(path, "wb") as f:
            f.write(base64.b64decode(data))
        return path
    except Exception as e:
        print(f"Error saving screenshot: {e}")
        return False

def is_screen_record_ready():
    """[Screenshots & Recording] True once one-time screen-capture consent has been granted in Settings."""
    # True once the user has granted screen-capture consent via the
    # "Enable Screen Recording" button in Settings.
    check_stop()
    return bridge.isScreenRecordReady()

def is_recording():
    """[Screenshots & Recording] True while a screen recording is actively being written."""
    # True while a screen recording is actively being written.
    check_stop()
    return bridge.isScreenRecording()

def start_screen_record(filename=None):
    """[Screenshots & Recording] Start recording to an .mp4 in Far_Auto/files. Returns True on start."""
    # Records the screen to an .mp4 in the 'Far_Auto/files' folder.
    # Requires consent first (see is_screen_record_ready). Returns True on start.
    check_stop()
    if filename is None:
        filename = f"rec_{int(time.time())}.mp4"
    return bridge.startScreenRecord(os.path.basename(filename))

def stop_screen_record():
    """[Screenshots & Recording] Stop the active recording; returns the saved file path (or None)."""
    # Stops the active recording and returns the saved file path (or None).
    check_stop()
    return bridge.stopScreenRecord()

def find_elements(resource_id=None, text=None):
    """[Screen Inspection] List of nodes matching a view id and/or a text substring."""
    check_stop()
    res = bridge.findNodes(resource_id, text)
    return json.loads(res)

def wait_for_element(resource_id=None, text=None, timeout_ms=5000):
    """[Screen Inspection] Poll until a match appears; returns the first node or None on timeout."""
    check_stop()
    start = time.time()
    while (time.time() - start) * 1000 < timeout_ms:
        check_stop()
        found = find_elements(resource_id, text)
        if found:
            return found[0]
        time.sleep(0.5)
    return None

def click_element(element):
    """[Screen Inspection] Tap the center of an element returned by find_elements/wait_for_element."""
    if not element or 'bounds' not in element:
        return False
    b = [int(x) for x in element['bounds'].split(',')]
    x = (b[0] + b[2]) / 2
    y = (b[1] + b[3]) / 2
    return click(x, y)

def force_stop_app(package_name):
    """[Navigation & Apps] Open the app's system Settings page and tap Force Stop + confirm."""
    check_stop()
    if not bridge.openAppSettings(package_name):
        return False
    btn = wait_for_element(resource_id="com.android.settings:id/force_stop_button", timeout_ms=2000)
    if not btn:
        btn = wait_for_element(text="Force stop", timeout_ms=500)
    if not btn:
        btn = wait_for_element(text="FORCE STOP", timeout_ms=500)
    if btn:
        click_element(btn)
        ok_btn = wait_for_element(resource_id="android:id/button1", timeout_ms=1000)
        if ok_btn:
            click_element(ok_btn)
        return True
    return False

def close_app_from_recents():
    """[Navigation & Apps] Open recents and swipe the current app away."""
    check_stop()
    press_recent()
    time.sleep(1)
    w, h = get_screen_size()
    # Swipe up to close the current app in recents
    return swipe(w // 2, h // 2, w // 2, h // 4, duration_ms=200)


# Display order for the dashboard API Reference sections; unknown groups sort to the
# end (still shown). Adding a new API function needs NO change here — just give it a
# docstring starting with a "[Group]" tag and it appears automatically.
_GROUP_ORDER = [
    "Gestures & Input",
    "Navigation & Apps",
    "Screen Inspection",
    "Screenshots & Recording",
    "Toasts",
    "Control & Lifecycle",
]


def api_reference():
    """Introspect this module and return the public automator API as a JSON string.

    Single source of truth for the dashboard's API Reference: every public function
    or class defined here is picked up automatically via reflection. Start a
    docstring with a ``[Group]`` tag to place the entry in a section; the remainder
    of that first line becomes the one-line description shown in the UI.
    """
    import sys
    import inspect
    mod = sys.modules[__name__]
    groups = {}
    for name, obj in vars(mod).items():
        if name.startswith("_") or name == "api_reference":
            continue
        is_fn = inspect.isfunction(obj) and getattr(obj, "__module__", None) == __name__
        is_cls = inspect.isclass(obj) and getattr(obj, "__module__", None) == __name__
        if not (is_fn or is_cls):
            continue
        summary = (inspect.getdoc(obj) or "").split("\n", 1)[0].strip()
        group = "Other"
        if summary.startswith("[") and "]" in summary:
            group = summary[1:summary.index("]")].strip()
            summary = summary[summary.index("]") + 1:].strip()
        args = str(inspect.signature(obj))[1:-1] if is_fn else ""
        groups.setdefault(group, []).append(
            {"name": name, "args": args, "desc": summary, "cls": is_cls}
        )

    def order(g):
        return (_GROUP_ORDER.index(g), "") if g in _GROUP_ORDER else (len(_GROUP_ORDER), g)

    out = [{"group": g, "fns": groups[g]} for g in sorted(groups, key=order)]
    return json.dumps(out)
