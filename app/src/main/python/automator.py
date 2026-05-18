import json
import time

bridge = None # Injected by ScriptExecutionService
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

def wait_for_toast(timeout_ms=5000):
    check_stop()
    start = time.time()
    while (time.time() - start) * 1000 < timeout_ms:
        check_stop()
        toast = get_last_toast()
        if toast:
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
