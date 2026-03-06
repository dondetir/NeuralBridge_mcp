#!/usr/bin/env python3
"""
NeuralBridge MCP Test Client — Tests all 32 tools across 4 phases.

Usage:
    uv run --with mcp mcp_client.py 192.168.86.71
    python mcp_client.py 192.168.86.71 --port 7474
"""

import argparse
import asyncio

from mcp.client.streamable_http import streamablehttp_client
from mcp import ClientSession


def _preview(result) -> str:
    if not result or not result.content:
        return "(empty)"
    item = result.content[0]
    if hasattr(item, "text"):
        t = item.text
        return t[:150] + "..." if len(t) > 150 else t
    if hasattr(item, "data"):
        return f"<image {len(item.data)} b64 chars>"
    return str(item)


def _image_b64(result) -> str | None:
    if not result or not result.content:
        return None
    for item in result.content:
        if hasattr(item, "data"):
            return item.data
    return None


async def run(host: str, port: int):
    url = f"http://{host}:{port}/mcp"
    print(f"Connecting to {url} ...\n")

    async with streamablehttp_client(url) as (read, write, _):
        async with ClientSession(read, write) as session:
            init = await session.initialize()
            info = init.serverInfo
            print(f"Server : {info.name} v{info.version}  protocol {init.protocolVersion}")
            tools = (await session.list_tools()).tools
            print(f"Tools  : {len(tools)}\n")

            passed = failed = 0

            async def call(name, args=None, label=None):
                nonlocal passed, failed
                display = label or name
                try:
                    res = await session.call_tool(name, args or {})
                    ok = not getattr(res, "isError", False)
                    status = "PASS" if ok else "FAIL"
                    passed += ok
                    failed += not ok
                    print(f"  [{status}] {display}: {_preview(res)}")
                    return res
                except Exception as e:
                    failed += 1
                    print(f"  [FAIL] {display}: {e}")
                    return None

            # ── Phase 1: Meta tools (no app needed) ─────────────────────────
            print("─── Phase 1: Meta ──────────────────────────────────────────")
            await call("android_get_device_info")
            await call("android_list_devices")
            await call("android_select_device", {"device_id": "local"})
            await call("android_list_apps", {"filter": "system"})
            await call("android_search_tools", {"query": "screenshot"})
            await call("android_describe_tools", {"tools": ["android_screenshot", "android_tap"]})
            await call("android_enable_events", {"enable": True, "event_types": []})
            await call("android_enable_events", {"enable": False}, "android_enable_events (disable)")

            # ── Phase 2: Settings app ────────────────────────────────────────
            print("\n─── Phase 2: Settings ──────────────────────────────────────")
            await call("android_launch_app", {"package_name": "com.android.settings"})
            await call("android_wait_for_idle")
            await call("android_get_screen_context")
            await call("android_get_ui_tree", {"filter": "interactive"})
            await call("android_find_elements", {"text": "Connections", "find_all": True})
            await call("android_accessibility_audit")
            await call("android_get_notifications", {"active_only": True})

            # Reference screenshot (used later for diff)
            ref = await call("android_screenshot", {"quality": "thumbnail"}, "android_screenshot (reference)")
            ref_b64 = _image_b64(ref)

            # Navigate into Connections
            await call("android_tap", {"text": "Connections"})
            await call("android_wait_for_element", {"text": "Wi-Fi", "timeout_ms": 5000})

            # Diff: Connections screen vs Settings root — should be low similarity
            if ref_b64:
                await call("android_screenshot_diff",
                           {"reference_base64": ref_b64, "threshold": 0.5},
                           "android_screenshot_diff")

            await call("android_press_key", {"key": "back"})
            await call("android_wait_for_idle")
            await call("android_swipe",
                       {"start_x": 540, "start_y": 1400, "end_x": 540, "end_y": 700})
            await call("android_scroll_to_element",
                       {"text": "About phone", "direction": "down"})
            await call("android_long_press", {"text": "About phone"})
            await call("android_press_key", {"key": "back"})   # dismiss any context menu
            await call("android_global_action", {"action": "home"})
            await call("android_wait_for_idle")

            # ── Phase 3: Home screen ─────────────────────────────────────────
            print("\n─── Phase 3: Home screen ───────────────────────────────────")
            await call("android_drag",
                       {"from_x": 540, "from_y": 1800, "to_x": 200, "to_y": 1800,
                        "duration_ms": 600})
            await call("android_get_recent_toasts", {"since_ms": 5000})

            # ── Phase 4: Chrome ──────────────────────────────────────────────
            print("\n─── Phase 4: Chrome ────────────────────────────────────────")
            await call("android_open_url", {"url": "https://example.com"})
            await call("android_wait_for_element",
                       {"text": "Example Domain", "timeout_ms": 15000})
            await call("android_set_clipboard", {"text": "NeuralBridge test"})
            await call("android_swipe",
                       {"start_x": 540, "start_y": 1200, "end_x": 540, "end_y": 600})
            await call("android_double_tap", {"x": 540, "y": 800})
            await call("android_pinch",
                       {"center_x": 540, "center_y": 800, "scale": 1.5})

            # Tap address bar → input text
            await call("android_tap", {"x": 540, "y": 95}, "android_tap (address bar)")
            await call("android_input_text", {"text": "example.com"})

            # Navigate away → wait for old content to disappear
            await call("android_open_url", {"url": "https://google.com"},
                       "android_open_url (google.com)")
            await call("android_wait_for_gone",
                       {"text": "Example Domain", "timeout_ms": 10000})

            await call("android_close_app", {"package_name": "com.android.chrome"})

            # ── Summary ──────────────────────────────────────────────────────
            total = passed + failed
            print(f"\n{'═' * 52}")
            print(f"  {passed}/{total} PASSED   {failed} FAILED")
            print(f"{'═' * 52}")


def main():
    parser = argparse.ArgumentParser(description="NeuralBridge MCP — full 32-tool test")
    parser.add_argument("host", help="Device WiFi IP (e.g. 192.168.86.71)")
    parser.add_argument("--port", type=int, default=7474)
    args = parser.parse_args()
    asyncio.run(run(args.host, args.port))


if __name__ == "__main__":
    main()
