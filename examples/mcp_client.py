#!/usr/bin/env python3
"""
NeuralBridge MCP Product Demo — Narrated walkthrough of all capabilities.

A product-manager-style demo that explains each action before performing it,
showcasing NeuralBridge's full suite of Android automation tools.

Usage:
    # Emulator (with adb forward tcp:7474 tcp:7474):
    uv run --with mcp mcp_client.py

    # Physical device on WiFi:
    uv run --with mcp mcp_client.py 192.168.1.100

    # Custom port:
    python mcp_client.py 192.168.1.100 --port 7474
"""

import argparse
import asyncio
import sys

from mcp.client.streamable_http import streamablehttp_client
from mcp import ClientSession

# ── Timing ────────────────────────────────────────────────────────────────────
BEAT = 1.5          # pause between narration and action
SCENE_PAUSE = 2.5   # pause between scenes


def _preview(result) -> str:
    if not result or not result.content:
        return "(empty)"
    item = result.content[0]
    if hasattr(item, "text"):
        t = item.text
        return t[:200] + "..." if len(t) > 200 else t
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

    print()
    print("=" * 60)
    print("  NeuralBridge MCP — Product Demo")
    print("=" * 60)
    print()
    print(f"  Connecting to {url} ...")
    print()

    async with streamablehttp_client(url) as (read, write, _):
        async with ClientSession(read, write) as session:
            init = await session.initialize()
            info = init.serverInfo
            tools = (await session.list_tools()).tools

            print(f"  Server  : {info.name} v{info.version}")
            print(f"  Protocol: {init.protocolVersion}")
            print(f"  Tools   : {len(tools)} available")
            print()

            passed = failed = 0

            async def narrate(text: str):
                """Print narration text with a pause for readability."""
                print(f"\n    >> {text}")
                await asyncio.sleep(BEAT)

            async def call(name: str, args=None, label: str | None = None):
                nonlocal passed, failed
                display = label or name
                try:
                    res = await session.call_tool(name, args or {})
                    ok = not getattr(res, "isError", False)
                    passed += ok
                    failed += not ok
                    marker = "OK" if ok else "!!"
                    print(f"       [{marker}] {display}: {_preview(res)}")
                    return res
                except Exception as e:
                    failed += 1
                    print(f"       [!!] {display}: {e}")
                    return None

            async def scene_break():
                await asyncio.sleep(SCENE_PAUSE)

            # ==============================================================
            # ACT 1: Device Discovery
            # ==============================================================
            print("-" * 60)
            print("  ACT 1: Device Discovery & Tool Catalog")
            print("-" * 60)

            await narrate(
                "First, let's see what device we're connected to."
            )
            await call("android_get_device_info")

            await narrate(
                "NeuralBridge can list all connected devices — useful "
                "in multi-device setups."
            )
            await call("android_list_devices")
            await call("android_select_device", {"device_id": "local"})

            await narrate(
                "Let's explore the tool catalog. We can search tools by "
                "keyword and get detailed descriptions."
            )
            await call("android_search_tools", {"query": "screenshot"})
            await call(
                "android_describe_tools",
                {"tools": ["android_screenshot", "android_tap"]},
            )

            await narrate(
                "We can also list installed apps on the device."
            )
            await call("android_list_apps", {"filter": "third_party"})

            await narrate(
                "Event streaming lets AI agents react to UI changes, "
                "notifications, and crashes in real time."
            )
            await call("android_enable_events", {"enable": True, "event_types": []})
            await call(
                "android_enable_events",
                {"enable": False},
                "android_enable_events (disable)",
            )

            await scene_break()

            # ==============================================================
            # ACT 2: Screen Reading & Visual Intelligence
            # ==============================================================
            print("\n" + "-" * 60)
            print("  ACT 2: Screen Reading & Visual Intelligence")
            print("-" * 60)

            await narrate(
                "Let's open the Settings app and see how NeuralBridge "
                "reads the screen."
            )
            await call("android_launch_app", {"package_name": "com.android.settings"})
            await call("android_wait_for_idle")

            await narrate(
                "get_screen_context gives an AI agent a full snapshot — "
                "foreground app, UI tree, and thumbnail — in one call."
            )
            await call("android_get_screen_context")

            await narrate(
                "We can also get the full interactive UI tree, or find "
                "specific elements by text or resource ID."
            )
            await call("android_get_ui_tree", {"filter": "interactive"})
            await call(
                "android_find_elements", {"text": "Network", "find_all": True}
            )

            await narrate(
                "The accessibility audit checks for missing labels, "
                "small touch targets, and non-focusable interactive elements."
            )
            await call("android_accessibility_audit")

            await narrate(
                "Let's take a reference screenshot — we'll compare it "
                "to a different screen later for visual regression testing."
            )
            ref = await call(
                "android_screenshot",
                {"quality": "thumbnail"},
                "android_screenshot (reference)",
            )
            ref_b64 = _image_b64(ref)

            await narrate("And check what notifications are active.")
            await call("android_get_notifications", {"active_only": True})

            await scene_break()

            # ==============================================================
            # ACT 3: Touch & Gesture Automation
            # ==============================================================
            print("\n" + "-" * 60)
            print("  ACT 3: Touch & Gesture Automation")
            print("-" * 60)

            await narrate(
                "Now let's navigate the UI. I'll tap on the first "
                "settings item to drill into it."
            )
            # Tap first interactive item below the search bar
            await call("android_tap", {"x": 540, "y": 450})
            await call("android_wait_for_idle")

            await narrate(
                "Let's compare this screen to our reference — the diff "
                "score tells us how different they are."
            )
            if ref_b64:
                await call(
                    "android_screenshot_diff",
                    {"reference_base64": ref_b64, "threshold": 0.5},
                )

            await narrate("I'll press Back to return to the main Settings list.")
            await call("android_press_key", {"key": "back"})
            await call("android_wait_for_idle")

            await narrate(
                "Swipe gestures are fully supported — here's a scroll "
                "down through the settings list."
            )
            await call(
                "android_swipe",
                {"start_x": 540, "start_y": 1400, "end_x": 540, "end_y": 700},
            )

            await narrate(
                "scroll_to_element automatically scrolls until it finds "
                "the target — no manual coordinate math needed."
            )
            await call(
                "android_scroll_to_element",
                {"text": "About", "direction": "down"},
            )

            await narrate(
                "Long press opens context menus — useful for testing "
                "secondary actions."
            )
            await call("android_long_press", {"text": "About"})
            await call("android_press_key", {"key": "back"})

            await narrate("Heading back to the home screen via global action.")
            await call("android_global_action", {"action": "home"})
            await call("android_wait_for_idle")

            await narrate(
                "Drag gestures work too — here I'm dragging across the "
                "home screen."
            )
            await call(
                "android_drag",
                {
                    "from_x": 540,
                    "from_y": 1800,
                    "to_x": 200,
                    "to_y": 1800,
                    "duration_ms": 600,
                },
            )

            await narrate("Let's check if any toast messages appeared.")
            await call("android_get_recent_toasts", {"since_ms": 5000})

            await scene_break()

            # ==============================================================
            # ACT 4: Text Input & Keyboard Control
            # ==============================================================
            print("\n" + "-" * 60)
            print("  ACT 4: Text Input & Keyboard Control")
            print("-" * 60)

            await narrate(
                "This is where it gets interesting. Let's open Settings "
                "search and demonstrate full keyboard control."
            )
            await call("android_launch_app", {"package_name": "com.android.settings"})
            await call("android_wait_for_idle")
            await call("android_tap", {"text": "Search"})
            await asyncio.sleep(1)

            # Use resource_id for reliable targeting across text changes
            search_field = "open_search_view_edit_text"

            await narrate(
                "I'll type 'bluetooth' into the search field using "
                "input_text — this works on any editable field."
            )
            await call(
                "android_input_text",
                {"text": "bluetooth", "resource_id": search_field},
            )
            await asyncio.sleep(0.5)

            await narrate(
                "Now watch the new keyboard controls. I'll press Delete "
                "three times to remove the last three characters."
            )
            for i in range(3):
                await call(
                    "android_press_key",
                    {"key": "delete"},
                    f"android_press_key (delete #{i + 1})",
                )

            await narrate(
                "The field should now read 'blueto'. Let's verify."
            )
            await call(
                "android_find_elements",
                {"resource_id": search_field},
                "verify text after delete",
            )

            await narrate(
                "I'll append 'oth' back using the append flag — this "
                "adds text without replacing."
            )
            await call(
                "android_input_text",
                {"text": "oth", "resource_id": search_field, "append": True},
            )

            await narrate(
                "Now let's press Space to add a space, then type more."
            )
            await call("android_press_key", {"key": "space"})
            await call(
                "android_input_text",
                {"text": "settings", "resource_id": search_field, "append": True},
            )

            await narrate(
                "Select all, copy to clipboard — standard editing operations."
            )
            await call("android_press_key", {"key": "select_all"})
            await call("android_press_key", {"key": "copy"})

            await narrate(
                "I'll clear the field, then paste what we copied back."
            )
            await call(
                "android_input_text",
                {"text": "", "resource_id": search_field},
                "android_input_text (clear field)",
            )
            await call("android_press_key", {"key": "paste"})

            await narrate(
                "Finally, press Enter to submit the search."
            )
            await call("android_press_key", {"key": "enter"})
            await asyncio.sleep(0.5)

            await narrate(
                "And Escape to back out — this maps to the system Back action."
            )
            await call("android_press_key", {"key": "escape"})

            await scene_break()

            # ==============================================================
            # ACT 5: Web & Multi-App Workflows
            # ==============================================================
            print("\n" + "-" * 60)
            print("  ACT 5: Web & Multi-App Workflows")
            print("-" * 60)

            await narrate(
                "NeuralBridge can open URLs directly — let's load "
                "example.com in the browser."
            )
            await call("android_open_url", {"url": "https://example.com"})
            await call(
                "android_wait_for_element",
                {"text": "Example Domain", "timeout_ms": 15000},
            )

            await narrate(
                "Set clipboard content from the AI side — useful for "
                "sharing data between tools."
            )
            await call("android_set_clipboard", {"text": "NeuralBridge demo"})

            await narrate(
                "Pinch to zoom in — great for verifying responsive "
                "layouts and small UI details."
            )
            await call(
                "android_pinch",
                {"center_x": 540, "center_y": 800, "scale": 1.5},
            )

            await narrate(
                "Double-tap to reset zoom, then swipe to scroll."
            )
            await call("android_double_tap", {"x": 540, "y": 800})
            await call(
                "android_swipe",
                {"start_x": 540, "start_y": 1200, "end_x": 540, "end_y": 600},
            )

            await narrate(
                "Now let's navigate to a different URL and verify the "
                "old content disappears — wait_for_gone is perfect for this."
            )
            await call(
                "android_open_url",
                {"url": "https://google.com"},
                "android_open_url (google.com)",
            )
            await call(
                "android_wait_for_gone",
                {"text": "Example Domain", "timeout_ms": 10000},
            )

            await narrate("Closing the browser to clean up.")
            await call("android_close_app", {"package_name": "com.android.chrome"})

            await narrate("And heading back to the home screen.")
            await call("android_global_action", {"action": "home"})

            # ==============================================================
            # Summary
            # ==============================================================
            total = passed + failed
            print()
            print("=" * 60)
            print(f"  Demo Complete: {passed}/{total} actions succeeded")
            if failed:
                print(f"  ({failed} failed — check output above)")
            print("=" * 60)
            print()


def main():
    parser = argparse.ArgumentParser(
        description="NeuralBridge MCP Product Demo — narrated walkthrough"
    )
    parser.add_argument(
        "host",
        nargs="?",
        default="localhost",
        help="Device IP or 'localhost' for emulator (default: localhost)",
    )
    parser.add_argument("--port", type=int, default=7474)
    args = parser.parse_args()
    asyncio.run(run(args.host, args.port))


if __name__ == "__main__":
    main()
