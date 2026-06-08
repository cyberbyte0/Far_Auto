import json
import time
import base64
import os

bridge = None # Injected by ScriptExecutionService
scripts_dir = "." # Injected by ScriptExecutionService
current_session = 0
last_stopped_session = -1

def check_stop():
    # If the current session has been stopped, raise InterruptedError
    if current_session <= last_stopped_session:
        raise InterruptedError("Script stopped by user")

def get_root():
    check_stop()
    res = bridge.getRoot()
    return json.loads(res) if res else None

def click(x, y):
    check_stop()
    return bridge.click(float(x), float(y))

def swipe(x1, y1, x2, y2, duration_ms=300):
    check_stop()
    return bridge.swipe(float(x1), float(y1), float(x2), float(y2), int(duration_ms))

def press_back():
    check_stop()
    return bridge.pressBack()

def press_home():
    check_stop()
    return bridge.pressHome()

def press_recent():
    check_stop()
    return bridge.pressRecent()

def launch_app(package_name):
    check_stop()
    return bridge.launchApp(package_name)

def input_text(text):
    check_stop()
    return bridge.inputText(text, True)

def is_secure_window():
    check_stop()
    return bridge.isSecureWindow()

def get_last_toast():
    check_stop()
    return bridge.getLastToast()

def clear_last_toast():
    check_stop()
    bridge.clearLastToast()

def get_last_toast_package():
    check_stop()
    return bridge.getLastToastPackage()

def set_toast_filter(package_name=None):
    # Only capture toasts from the given package; pass None to capture from all apps
    check_stop()
    bridge.setToastPackageFilter(package_name)

def wait_for_toast(timeout_ms=5000, package=None):
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
    check_stop()
    bridge.clearLogs()

def get_screen_size():
    check_stop()
    res = bridge.getScreenSize()
    return [int(x) for x in res.split(",")]

def dump_tree():
    check_stop()
    return json.loads(bridge.dumpTree())

def get_interactable_elements():
    check_stop()
    return json.loads(bridge.getInteractableNodes())

def take_screenshot():
    check_stop()
    return bridge.takeScreenshot()

def save_screenshot(filename="screenshot.jpg"):
    check_stop()
    data = take_screenshot()
    if not data:
        return False
    try:
        # Save to the app's script directory for easy access
        path = os.path.join(scripts_dir, filename)
        with open(path, "wb") as f:
            f.write(base64.b64decode(data))
        return path
    except Exception as e:
        print(f"Error saving screenshot: {e}")
        return False

def is_screen_record_ready():
    # True once the user has granted screen-capture consent via the
    # "Enable Screen Recording" button in Settings.
    check_stop()
    return bridge.isScreenRecordReady()

def is_recording():
    # True while a screen recording is actively being written.
    check_stop()
    return bridge.isScreenRecording()

def start_screen_record(filename=None):
    # Records the screen to an .mp4 in the 'FAR_auto recordings' folder.
    # Requires consent first (see is_screen_record_ready). Returns True on start.
    check_stop()
    if filename is None:
        filename = f"rec_{int(time.time())}.mp4"
    return bridge.startScreenRecord(filename)

def stop_screen_record():
    # Stops the active recording and returns the saved file path (or None).
    check_stop()
    return bridge.stopScreenRecord()

def find_elements(resource_id=None, text=None):
    check_stop()
    res = bridge.findNodes(resource_id, text)
    return json.loads(res)

def wait_for_element(resource_id=None, text=None, timeout_ms=5000):
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
    if not element or 'bounds' not in element:
        return False
    b = [int(x) for x in element['bounds'].split(',')]
    x = (b[0] + b[2]) / 2
    y = (b[1] + b[3]) / 2
    return click(x, y)

def force_stop_app(package_name):
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
    check_stop()
    press_recent()
    time.sleep(1)
    w, h = get_screen_size()
    # Swipe up to close the current app in recents
    return swipe(w // 2, h // 2, w // 2, h // 4, duration_ms=200)
