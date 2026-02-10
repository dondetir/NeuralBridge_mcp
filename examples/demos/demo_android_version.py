#!/usr/bin/env python3
"""
Simple demo: Get Android version information from device
"""

import json
import subprocess
import sys
import time
from mcp_client import MCPClient


def get_android_version_info(client: MCPClient):
    """Get Android version from the device"""
    print("=" * 70)
    print("🤖 NeuralBridge Demo: Get Android Version")
    print("=" * 70)
    print()

    # Step 1: Open Settings to "About" page directly
    print("📱 Step 1: Opening About Device page...")
    subprocess.run([
        "/home/rdondeti/Android/Sdk/platform-tools/adb",
        "shell", "am", "start", "-a",
        "android.settings.DEVICE_INFO_SETTINGS"
    ], capture_output=True)
    time.sleep(2)
    print("   ✅ About page opened\n")

    # Step 2: Get UI tree
    print("🔍 Step 2: Scanning UI for version information...")
    ui_tree = client.get_ui_tree()
    elements = ui_tree.get("elements", [])
    print(f"   ✅ Found {len(elements)} UI elements")
    print(f"   ⏱️  Latency: {ui_tree.get('latency_ms', 0)}ms\n")

    # Step 3: Extract device information
    print("📊 Step 3: Extracting device information...\n")

    device_info = {}

    # Look through all elements for info
    for i, element in enumerate(elements):
        text = element.get("text", "")
        if not text:
            continue

        text_lower = text.lower()

        # Look for labels and their values
        if text_lower == "model":
            # Get next text element
            for j in range(i+1, min(i+3, len(elements))):
                next_text = elements[j].get("text", "")
                if next_text and next_text.lower() != "model":
                    device_info["Model"] = next_text
                    break

        elif "android version" in text_lower:
            device_info["Android Version Label"] = text
            # Try to find the actual version number nearby
            for j in range(i+1, min(i+5, len(elements))):
                next_text = elements[j].get("text", "")
                if next_text and any(c.isdigit() for c in next_text):
                    if len(next_text) < 20:  # Reasonable version length
                        device_info["Android Version"] = next_text
                        break

        elif text_lower.startswith("api") or "api level" in text_lower:
            device_info["API Level"] = text

        elif text_lower == "build number" or text_lower == "build":
            for j in range(i+1, min(i+3, len(elements))):
                next_text = elements[j].get("text", "")
                if next_text and next_text.lower() not in ["build number", "build"]:
                    device_info["Build Number"] = next_text
                    break

        elif "imei" in text_lower:
            for j in range(i+1, min(i+3, len(elements))):
                next_text = elements[j].get("text", "")
                if next_text and next_text.isdigit():
                    device_info["IMEI"] = next_text
                    break

        # Also capture any text that looks like it might be version info
        elif any(char.isdigit() for char in text):
            # If it's a short text with numbers, might be version
            if 2 <= len(text) <= 30:
                if text not in device_info.values():
                    # Check if previous element was a label
                    if i > 0:
                        prev_text = elements[i-1].get("text", "")
                        if prev_text and prev_text.endswith(":"):
                            device_info[prev_text.rstrip(":")] = text

    # Step 4: Display results
    print("   " + "=" * 66)
    print("   📱 DEVICE INFORMATION")
    print("   " + "=" * 66)

    if device_info:
        for key, value in device_info.items():
            print(f"   {key:25} : {value}")
    else:
        print("   ⚠️  Could not extract device information")
        print("\n   Available text elements:")
        for elem in elements[:30]:
            text = elem.get("text", "")
            if text:
                print(f"      • {text}")

    print("   " + "=" * 66)
    print()

    # Step 5: Bonus - Tap on Android version to see Easter egg
    print("🎮 Step 4: Bonus - Tapping Android version for Easter egg...")

    android_version_elem = None
    for elem in elements:
        text = elem.get("text", "")
        if "android version" in text.lower() and elem.get("clickable"):
            android_version_elem = elem
            break

    if android_version_elem:
        x, y = client.get_element_center(android_version_elem)
        print(f"   Tapping at ({x}, {y})...")

        # Tap multiple times quickly (Easter egg usually requires multiple taps)
        for i in range(7):
            client.tap(x, y)
            time.sleep(0.1)

        print("   ✅ Tapped 7 times - watch for Android Easter egg! 🎨")
        time.sleep(2)
    else:
        print("   ⚠️  Android version not clickable")

    print("\n" + "=" * 70)
    print("✅ Demo complete!")
    print("=" * 70)


def main():
    """Main entry point"""
    print("\n" + "=" * 70)
    print("🤖 NeuralBridge - Android Version Demo")
    print("=" * 70)
    print()

    server_path = "/home/rdondeti/Code/Android/neuralBridge/mcp-server/target/release/neuralbridge-mcp"
    device_id = "emulator-5554"

    client = MCPClient(server_path, device_id)

    try:
        client.start()
        get_android_version_info(client)

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
