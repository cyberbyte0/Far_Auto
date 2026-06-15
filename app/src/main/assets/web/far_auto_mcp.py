import os
import httpx
from mcp.server.fastmcp import FastMCP, Image

# --- Configuration (set via env vars or the app's "Send to Web Console" button) ---
DEVICE_IP = os.getenv("DEVICE_IP", "10.0.2.16")
AUTH_TOKEN = os.getenv("AUTH_TOKEN", "YOUR_TOKEN")
PORT = os.getenv("DEVICE_PORT", "8080")

mcp = FastMCP("FarAuto")
BASE_URL = f"http://{DEVICE_IP}:{PORT}/api/rpc"

async def rpc_call(method, params=None):
    headers = {"x-automator-token": AUTH_TOKEN, "Content-Type": "application/json"}
    payload = {"method": method, "params": params or {}}
    async with httpx.AsyncClient(timeout=15.0) as client:
        try:
            response = await client.post(BASE_URL, json=payload, headers=headers)
            return response.json()
        except Exception as e:
            return {"error": str(e)}

# ── Screen inspection ──────────────────────────────────────────────────────────

@mcp.tool()
async def get_screen_size():
    """Returns the screen width and height in pixels. Use this before calculating tap/swipe coordinates."""
    return await rpc_call("get_screen_size")

@mcp.tool()
async def get_screen_info():
    """Returns all interactable UI elements (buttons, inputs, etc.) visible on screen with their coordinates."""
    return await rpc_call("get_screen_info")

@mcp.tool()
async def dump_ui_tree():
    """Returns the full accessibility UI tree of the current screen. Use when get_screen_info misses elements."""
    return await rpc_call("dump_ui_tree")

@mcp.tool()
async def find_element(text: str = "", resource_id: str = ""):
    """Finds elements by visible text or resource ID (e.g. 'com.app:id/btn_login'). Returns matching elements with coordinates."""
    return await rpc_call("find_element", {"text": text, "resource_id": resource_id})

@mcp.tool()
async def get_screenshot():
    """Captures a screenshot of the phone screen and returns it as a viewable image."""
    result = await rpc_call("take_screenshot")
    if result.get("success") and result.get("image"):
        return Image(data=result["image"], format="jpeg")
    return {"error": "screenshot failed"}

@mcp.tool()
async def is_secure_window():
    """Returns whether the current screen has FLAG_SECURE set (e.g. banking apps). When true, screenshots and UI tree will be empty."""
    return await rpc_call("is_secure_window")

# ── Gestures ──────────────────────────────────────────────────────────────────

@mcp.tool()
async def tap_element(x: float, y: float):
    """Taps on a specific (x,y) coordinate on the phone screen."""
    return await rpc_call("perform_action", {"action": "click", "x": x, "y": y})

@mcp.tool()
async def tap_and_wait(x: float, y: float):
    """Taps a coordinate and waits ~800ms for the UI to settle. Use after tapping buttons that trigger navigation or loading."""
    return await rpc_call("click_and_wait", {"x": x, "y": y})

@mcp.tool()
async def long_press(x: float, y: float):
    """Long-presses a coordinate for ~800ms. Use to open context menus or trigger long-press actions."""
    return await rpc_call("perform_action", {"action": "long_press", "x": x, "y": y})

@mcp.tool()
async def swipe_screen(x1: float, y1: float, x2: float, y2: float, duration_ms: int = 300):
    """Swipes from (x1,y1) to (x2,y2). Use for scrolling lists, swiping pages, or drag gestures. duration_ms controls speed."""
    return await rpc_call("perform_action", {"action": "swipe", "x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration": duration_ms})

@mcp.tool()
async def type_text(text: str, clear: bool = True):
    """Types text into the currently focused input field. Set clear=True to replace existing text."""
    return await rpc_call("perform_action", {"action": "input", "text": text, "clear": clear})

# ── Navigation ────────────────────────────────────────────────────────────────

@mcp.tool()
async def navigate(action: str):
    """Performs a system navigation action. action must be one of: 'back', 'home', 'recents'."""
    return await rpc_call("perform_action", {"action": "key_press", "key": action})

@mcp.tool()
async def open_app(package_name: str):
    """Launches an Android app using its package name (e.g. 'com.android.settings')."""
    return await rpc_call("perform_action", {"action": "launch_app", "package": package_name})

@mcp.tool()
async def open_app_settings(package_name: str):
    """Opens the system Settings page for a specific app (permissions, storage, notifications, etc.)."""
    return await rpc_call("open_app_settings", {"package": package_name})

@mcp.tool()
async def close_app_from_recents():
    """Dismisses the current foreground app by opening recents and swiping it away. Does not require a package name."""
    return await rpc_call("close_app_from_recents")

@mcp.tool()
async def force_stop_app(package_name: str):
    """Force-stops an app via Android Settings > App Info > Force Stop. More reliable than close_app_from_recents but navigates through Settings UI."""
    return await rpc_call("force_stop_app", {"package": package_name})

# ── Feedback ─────────────────────────────────────────────────────────────────

@mcp.tool()
async def get_last_toast():
    """Returns the last toast notification shown on screen (e.g. 'Saved', 'Login failed'). Useful for confirming operation results."""
    return await rpc_call("get_last_toast")

if __name__ == "__main__":
    mcp.run()
