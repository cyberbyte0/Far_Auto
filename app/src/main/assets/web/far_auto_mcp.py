import asyncio
import json
import os
import httpx
from mcp.server.fastmcp import FastMCP

# --- Configuration ---
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

@mcp.tool()
async def get_screen_info():
    """Returns a list of all interactable UI elements on the Android screen."""
    return await rpc_call("get_screen_info")

@mcp.tool()
async def tap_element(x: float, y: float):
    """Taps on a specific (x,y) coordinate on the phone screen."""
    return await rpc_call("perform_action", {"action": "click", "x": x, "y": y})

@mcp.tool()
async def type_text(text: str, clear: bool = True):
    """Types text into the currently focused input field."""
    return await rpc_call("perform_action", {"action": "input", "text": text, "clear": clear})

@mcp.tool()
async def navigate(action: str):
    """Performs a system navigation action: 'back', 'home', or 'recents'."""
    return await rpc_call("perform_action", {"action": "key_press", "key": action})

@mcp.tool()
async def open_app(package_name: str):
    """Launches an Android app using its package name (e.g. 'com.android.settings')."""
    return await rpc_call("perform_action", {"action": "launch_app", "package": package_name})

@mcp.tool()
async def get_screenshot():
    """Captures a visual screenshot of the phone screen (Base64 JPEG)."""
    return await rpc_call("take_screenshot")

if __name__ == "__main__":
    mcp.run()
