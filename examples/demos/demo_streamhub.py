#!/usr/bin/env python3
"""
NeuralBridge Demo: Launch streamHub and navigate main page
"""

import json
import subprocess
import sys
import time
from mcp_client import MCPClient


def find_streamhub_package():
    """Find streamHub package name"""
    print("🔍 Searching for streamHub app...")

    result = subprocess.run([
        "/home/rdondeti/Android/Sdk/platform-tools/adb",
        "shell", "pm", "list", "packages"
    ], capture_output=True, text=True)

    packages = result.stdout.strip().split('\n')

    # Search for streamhub variations
    candidates = []
    for pkg in packages:
        pkg = pkg.replace('package:', '')
        if 'stream' in pkg.lower() and 'hub' in pkg.lower():
            candidates.append(pkg)
        elif 'streamhub' in pkg.lower():
            candidates.append(pkg)

    if candidates:
        print(f"   ✅ Found streamHub package: {candidates[0]}")
        return candidates[0]
    else:
        print("   ⚠️  streamHub not found. Searching for 'stream' apps:")
        for pkg in packages:
            pkg = pkg.replace('package:', '')
            if 'stream' in pkg.lower():
                print(f"      • {pkg}")
        return None


def launch_app(package_name):
    """Launch app by package name"""
    print(f"\n🚀 Launching {package_name}...")

    # Method 1: Launch main activity
    result = subprocess.run([
        "/home/rdondeti/Android/Sdk/platform-tools/adb",
        "shell", "monkey", "-p", package_name, "-c",
        "android.intent.category.LAUNCHER", "1"
    ], capture_output=True, text=True)

    time.sleep(3)
    print("   ✅ App launched\n")


def explore_screen(client: MCPClient, screen_name: str):
    """Explore current screen and return UI elements"""
    print(f"📱 {screen_name}")
    print("   " + "=" * 66)

    ui_tree = client.get_ui_tree()
    elements = ui_tree.get("elements", [])
    foreground_app = ui_tree.get("foreground_app", "unknown")
    latency = ui_tree.get("latency_ms", 0)

    print(f"   Current app: {foreground_app}")
    print(f"   Elements: {len(elements)} | Latency: {latency}ms\n")

    # Categorize elements
    buttons = []
    text_labels = []
    clickable_items = []
    navigation_items = []

    # Keywords that suggest navigation/clickable items
    nav_keywords = ['home', 'favorite', 'live', 'movie', 'tv', 'radio', 'settings',
                    'search', 'menu', 'back', 'next', 'play', 'pause']

    for elem in elements:
        text = elem.get("text", "")
        if not text:
            continue

        clickable = elem.get("clickable", False)
        enabled = elem.get("enabled", False)
        semantic_type = elem.get("semantic_type", "")
        text_lower = text.lower()

        # Check if it looks like a navigation item
        is_nav_item = any(keyword in text_lower for keyword in nav_keywords)

        if clickable:
            if "button" in semantic_type.lower():
                buttons.append(elem)
            else:
                clickable_items.append(elem)
        elif is_nav_item and enabled:
            # Likely a navigation item even if not marked clickable
            navigation_items.append(elem)
        else:
            text_labels.append(elem)

    # Display UI structure
    if buttons:
        print("   🔘 BUTTONS:")
        for btn in buttons[:10]:
            print(f"      • {btn.get('text')}")
        if len(buttons) > 10:
            print(f"      ... and {len(buttons) - 10} more")
        print()

    if clickable_items:
        print("   👆 CLICKABLE ITEMS:")
        for item in clickable_items[:15]:
            print(f"      • {item.get('text')}")
        if len(clickable_items) > 15:
            print(f"      ... and {len(clickable_items) - 15} more")
        print()

    if navigation_items:
        print("   🧭 NAVIGATION ITEMS (likely clickable):")
        for item in navigation_items[:15]:
            bounds = item.get('bounds', {})
            print(f"      • {item.get('text')} @ ({bounds.get('left', 0)}, {bounds.get('top', 0)})")
        if len(navigation_items) > 15:
            print(f"      ... and {len(navigation_items) - 15} more")
        print()

    if text_labels:
        print("   📝 OTHER TEXT:")
        for label in text_labels[:8]:
            text = label.get('text')
            if len(text) > 50:
                text = text[:47] + "..."
            print(f"      • {text}")
        if len(text_labels) > 8:
            print(f"      ... and {len(text_labels) - 8} more")
        print()

    print("   " + "=" * 66)
    print()

    return {
        'elements': elements,
        'buttons': buttons,
        'clickable_items': clickable_items,
        'navigation_items': navigation_items,
        'text_labels': text_labels
    }


def navigate_streamhub(client: MCPClient, package_name: str):
    """Navigate through streamHub app"""
    print("=" * 70)
    print("🎬 NeuralBridge Demo: streamHub Navigation")
    print("=" * 70)
    print()

    # Launch app
    launch_app(package_name)

    # Step 1: Explore main screen
    screen_data = explore_screen(client, "Main Screen")

    # Step 2: Look for interesting elements to interact with
    clickable = screen_data['clickable_items']
    buttons = screen_data['buttons']
    navigation = screen_data['navigation_items']

    # Prioritize actual clickable elements, but also try navigation items
    all_interactive = clickable + buttons + navigation

    if not all_interactive:
        print("   ℹ️  No interactive elements found on main screen")
        print("   📋 Showing all available elements for debugging:")
        for elem in screen_data['elements'][:20]:
            text = elem.get('text', '')
            if text:
                clickable_flag = elem.get('clickable', False)
                enabled_flag = elem.get('enabled', False)
                bounds = elem.get('bounds', {})
                print(f"      • '{text}' - clickable:{clickable_flag}, enabled:{enabled_flag}")
                print(f"        bounds: ({bounds.get('left', 0)}, {bounds.get('top', 0)}, {bounds.get('right', 0)}, {bounds.get('bottom', 0)})")
        return

    # Step 3: Try to interact with first few elements
    print("🎯 Interactive Navigation Demo")
    print("=" * 70)
    print()
    print(f"   Found {len(all_interactive)} potentially interactive elements")
    print(f"   Will try tapping on first 3...\n")

    for i, elem in enumerate(all_interactive[:3], 1):
        text = elem.get('text', 'Unknown')
        print(f"📍 Action {i}: Tapping on '{text}'")

        x, y = client.get_element_center(elem)
        print(f"   Coordinates: ({x}, {y})")

        success = client.tap(x, y)
        print(f"   ✅ Tap executed: {success}\n")

        time.sleep(2)

        # Get new UI tree to see what changed
        new_ui = client.get_ui_tree()
        new_app = new_ui.get('foreground_app', '')

        if new_app != package_name:
            print(f"   📱 Navigated to: {new_app}")
        else:
            print(f"   📱 Still in {package_name}")

        # Show what's on the new screen
        new_elements = new_ui.get('elements', [])
        new_clickable = [e for e in new_elements if e.get('clickable') and e.get('text')]

        if new_clickable:
            print(f"   New screen has {len(new_clickable)} clickable items:")
            for item in new_clickable[:5]:
                print(f"      • {item.get('text')}")
            print()

        # Go back
        if i < len(all_interactive[:3]):
            print("   ⬅️  Going back...")
            subprocess.run([
                "/home/rdondeti/Android/Sdk/platform-tools/adb",
                "shell", "input", "keyevent", "KEYCODE_BACK"
            ], capture_output=True)
            time.sleep(1)
            print()

    # Step 4: Try scrolling
    print("📜 Scroll Demo")
    print("=" * 70)
    print()

    print("   Scrolling down to reveal more content...")
    success = client.swipe(640, 1500, 640, 500, 400)
    print(f"   ✅ Swipe executed: {success}\n")
    time.sleep(1)

    # Get UI after scroll
    scrolled_ui = client.get_ui_tree()
    scrolled_elements = scrolled_ui.get('elements', [])
    scrolled_clickable = [e for e in scrolled_elements if e.get('clickable') and e.get('text')]

    print(f"   After scroll: {len(scrolled_clickable)} clickable items visible")
    if scrolled_clickable:
        print("   New items revealed:")
        for item in scrolled_clickable[:5]:
            print(f"      • {item.get('text')}")
    print()

    # Step 5: Take screenshot
    print("📸 Screenshot Demo")
    print("=" * 70)
    print()

    print("   Capturing screenshot...")
    try:
        screenshot_result = client.call_tool("android_screenshot", {
            "quality": "full"
        })

        if "content" in screenshot_result and len(screenshot_result["content"]) > 0:
            content_text = screenshot_result["content"][0].get("text", "{}")
            screenshot_data = json.loads(content_text)

            if screenshot_data.get("success"):
                image_data = screenshot_data.get("image_data", "")
                if image_data:
                    print(f"   ✅ Screenshot captured ({len(image_data)} bytes)")
                    print(f"   Resolution: {screenshot_data.get('width')}x{screenshot_data.get('height')}")
                else:
                    print("   ⚠️  Screenshot empty (MediaProjection consent may be required)")
            else:
                print(f"   ⚠️  Screenshot failed: {screenshot_data.get('error_message', 'unknown')}")
        else:
            print("   ⚠️  No screenshot data returned")
    except Exception as e:
        print(f"   ⚠️  Screenshot error: {e}")

    print()


def main():
    """Main entry point"""
    print("\n" + "=" * 70)
    print("🎬 NeuralBridge - streamHub Demo")
    print("=" * 70)
    print()

    # Find streamHub
    package_name = find_streamhub_package()

    if not package_name:
        print("\n❌ streamHub app not found on device")
        print("   Please install streamHub or specify a different app")
        return

    # Start MCP client
    server_path = "/home/rdondeti/Code/Android/neuralBridge/mcp-server/target/release/neuralbridge-mcp"
    device_id = "emulator-5554"

    client = MCPClient(server_path, device_id)

    try:
        print("\n🚀 Starting MCP server...")
        client.start()
        print()

        # Navigate streamHub
        navigate_streamhub(client, package_name)

        print("=" * 70)
        print("✅ Demo complete!")
        print("=" * 70)
        print()
        print("Summary:")
        print("  • Launched streamHub app")
        print("  • Explored main screen UI")
        print("  • Interacted with clickable elements")
        print("  • Demonstrated scrolling")
        print("  • Attempted screenshot capture")
        print()

    except KeyboardInterrupt:
        print("\n\n⚠️  Interrupted by user")
    except Exception as e:
        print(f"\n❌ Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        client.stop()


if __name__ == "__main__":
    main()
