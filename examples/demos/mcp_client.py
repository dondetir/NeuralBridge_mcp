#!/usr/bin/env python3
"""
NeuralBridge MCP Client
A test client that connects to the MCP server and performs Android automation tasks
"""

import json
import subprocess
import sys
import time
from typing import Dict, Any, Optional

class MCPClient:
    def __init__(self, server_path: str, device_id: str):
        """Initialize MCP client with server path and device ID"""
        self.server_path = server_path
        self.device_id = device_id
        self.process = None
        self.request_id = 0

    def start(self):
        """Start the MCP server process"""
        print(f"🚀 Starting MCP server for device: {self.device_id}")

        env = {
            'PATH': '/home/rdondeti/Android/Sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
        }

        cmd = [
            self.server_path,
            '--device', self.device_id
        ]

        self.process = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
            env=env
        )

        # Initialize MCP connection
        self._initialize()
        print("✅ MCP server connected\n")

    def _initialize(self):
        """Perform MCP initialization handshake"""
        init_request = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {
                    "name": "neuralbridge-test-client",
                    "version": "0.1.0"
                }
            }
        }

        response = self._send_request(init_request)

        # Send initialized notification
        initialized = {
            "jsonrpc": "2.0",
            "method": "notifications/initialized"
        }
        self._send_notification(initialized)

    def _next_id(self) -> int:
        """Get next request ID"""
        self.request_id += 1
        return self.request_id

    def _send_request(self, request: Dict[str, Any]) -> Dict[str, Any]:
        """Send a JSON-RPC request and wait for response"""
        request_json = json.dumps(request)
        self.process.stdin.write(request_json + '\n')
        self.process.stdin.flush()

        # Read response
        response_line = self.process.stdout.readline()
        if not response_line:
            raise Exception("Server closed connection")

        return json.loads(response_line)

    def _send_notification(self, notification: Dict[str, Any]):
        """Send a JSON-RPC notification (no response expected)"""
        notification_json = json.dumps(notification)
        self.process.stdin.write(notification_json + '\n')
        self.process.stdin.flush()

    def call_tool(self, tool_name: str, arguments: Dict[str, Any]) -> Dict[str, Any]:
        """Call an MCP tool"""
        request = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "tools/call",
            "params": {
                "name": tool_name,
                "arguments": arguments
            }
        }

        response = self._send_request(request)

        if "error" in response:
            raise Exception(f"Tool call failed: {response['error']}")

        return response.get("result", {})

    def get_ui_tree(self, include_invisible: bool = False, max_depth: int = 0) -> Dict[str, Any]:
        """Get the UI tree of the current screen"""
        result = self.call_tool("android_get_ui_tree", {
            "include_invisible": include_invisible,
            "max_depth": max_depth
        })

        # Parse the content (MCP returns content array)
        if "content" in result and len(result["content"]) > 0:
            content_text = result["content"][0].get("text", "{}")
            return json.loads(content_text)

        return {}

    def tap(self, x: int, y: int) -> bool:
        """Tap at coordinates"""
        result = self.call_tool("android_tap", {
            "x": x,
            "y": y
        })

        if "content" in result and len(result["content"]) > 0:
            content_text = result["content"][0].get("text", "{}")
            response = json.loads(content_text)
            return response.get("success", False)

        return False

    def swipe(self, start_x: int, start_y: int, end_x: int, end_y: int, duration_ms: int = 300) -> bool:
        """Swipe from start to end"""
        result = self.call_tool("android_swipe", {
            "start_x": start_x,
            "start_y": start_y,
            "end_x": end_x,
            "end_y": end_y,
            "duration_ms": duration_ms
        })

        if "content" in result and len(result["content"]) > 0:
            content_text = result["content"][0].get("text", "{}")
            response = json.loads(content_text)
            return response.get("success", False)

        return False

    def find_element_by_text(self, ui_tree: Dict[str, Any], text: str, partial: bool = True) -> Optional[Dict[str, Any]]:
        """Find an element by text content"""
        elements = ui_tree.get("elements", [])

        for element in elements:
            element_text = element.get("text", "")
            if not element_text:
                continue

            if partial:
                if text.lower() in element_text.lower():
                    return element
            else:
                if text.lower() == element_text.lower():
                    return element

        return None

    def get_element_center(self, element: Dict[str, Any]) -> tuple:
        """Get the center coordinates of an element"""
        bounds = element.get("bounds", {})
        left = bounds.get("left", 0)
        top = bounds.get("top", 0)
        right = bounds.get("right", 0)
        bottom = bounds.get("bottom", 0)

        center_x = (left + right) // 2
        center_y = (top + bottom) // 2

        return (center_x, center_y)

    def stop(self):
        """Stop the MCP server"""
        if self.process:
            self.process.terminate()
            self.process.wait(timeout=5)
            print("\n✅ MCP server stopped")


def demo_open_settings_and_find_version(client: MCPClient):
    """
    Demo: Open Settings, navigate to About phone, and display Android version
    """
    print("=" * 60)
    print("🤖 Demo: Open Settings → About phone → Android version")
    print("=" * 60)
    print()

    # Step 1: Open Settings app via ADB (since we don't have app launch implemented yet)
    print("📱 Step 1: Opening Settings app...")
    import subprocess
    subprocess.run([
        "/home/rdondeti/Android/Sdk/platform-tools/adb",
        "shell", "am", "start", "-n",
        "com.android.settings/.Settings"
    ], capture_output=True)
    time.sleep(2)
    print("   ✅ Settings opened\n")

    # Step 2: Get UI tree to find elements
    print("🔍 Step 2: Getting UI tree...")
    ui_tree = client.get_ui_tree()
    total_elements = ui_tree.get("total_nodes", 0)
    elements = ui_tree.get("elements", [])
    print(f"   ✅ Found {len(elements)} elements (total nodes: {total_elements})")
    print(f"   📱 Current app: {ui_tree.get('foreground_app', 'unknown')}")
    print(f"   ⏱️  Latency: {ui_tree.get('latency_ms', 0)}ms\n")

    # Step 3: Find "About phone" or "About emulated device"
    print("🔍 Step 3: Looking for 'About' option...")

    # First, show what's available
    print("   Available clickable options:")
    for elem in elements[:25]:
        if elem.get("clickable") and elem.get("text"):
            print(f"      • {elem.get('text')}")
    print()

    about_element = None

    # Try different variations with better matching
    def find_about_element(tree):
        """Find About element with better matching"""
        elems = tree.get("elements", [])

        # Try exact matches first
        for search_text in ["About phone", "About emulated device", "About tablet"]:
            for elem in elems:
                text = elem.get("text", "")
                if text.lower() == search_text.lower() and elem.get("clickable"):
                    return elem

        # Try "About" that starts with it
        for elem in elems:
            text = elem.get("text", "")
            if text.lower().startswith("about") and elem.get("clickable"):
                # Make sure it's not "About this page" or other variants
                if "feedback" not in text.lower() and "page" not in text.lower():
                    return elem

        return None

    about_element = find_about_element(ui_tree)

    if about_element:
        print(f"   ✅ Found: '{about_element.get('text', '')}'\n")
    else:
        print("   ⚠️  'About' option not visible, trying to scroll down...")

        # Scroll down to find it
        client.swipe(640, 1500, 640, 500, 300)
        time.sleep(1)

        # Get UI tree again
        ui_tree = client.get_ui_tree()
        about_element = find_about_element(ui_tree)

        if about_element:
            print(f"   ✅ Found after scroll: '{about_element.get('text', '')}'\n")
        else:
            print("   ❌ Still not found after scroll")

    if not about_element:
        print("   ❌ Could not find 'About' option. Listing all text elements:")
        for i, elem in enumerate(elements[:30]):
            text = elem.get("text", "")
            clickable = elem.get("clickable", False)
            if text:
                marker = "👆" if clickable else "  "
                print(f"      {marker} {text}")

        print("\n   ℹ️  Let's try searching in System section...")

        # Try to find and tap "System" option
        system_element = client.find_element_by_text(ui_tree, "System")
        if system_element:
            print(f"   ✅ Found 'System' option")
            x, y = client.get_element_center(system_element)
            client.tap(x, y)
            time.sleep(2)

            # Get new UI tree
            ui_tree = client.get_ui_tree()
            about_element = client.find_element_by_text(ui_tree, "About")
            if about_element:
                print(f"   ✅ Found 'About' in System menu\n")
        else:
            print("   ❌ Could not navigate to About screen")
            return

    # Step 4: Tap on "About" option
    print("👆 Step 4: Tapping on 'About' option...")
    x, y = client.get_element_center(about_element)
    print(f"   Coordinates: ({x}, {y})")
    success = client.tap(x, y)
    print(f"   ✅ Tap executed: {success}\n")
    time.sleep(2)

    # Step 5: Get UI tree of About screen
    print("🔍 Step 5: Getting About screen UI tree...")
    about_ui = client.get_ui_tree()
    about_elements = about_ui.get("elements", [])
    print(f"   ✅ Found {len(about_elements)} elements\n")

    # Step 6: Find Android version
    print("🔍 Step 6: Looking for Android version...")

    # Find all elements and look for version patterns
    version_info = {}

    for i, element in enumerate(about_elements):
        text = element.get("text", "")
        if not text:
            continue

        text_lower = text.lower()

        # Collect version-related information
        if "android version" in text_lower:
            version_info["android_version_label"] = text

            # Look at the next few elements for the actual version number
            for j in range(i+1, min(i+5, len(about_elements))):
                next_text = about_elements[j].get("text", "")
                if next_text and any(c.isdigit() for c in next_text):
                    version_info["android_version_value"] = next_text
                    break

        elif "android api" in text_lower or text_lower.startswith("api "):
            version_info["api_level"] = text

        elif "build" in text_lower and any(c.isdigit() for c in text):
            version_info["build_number"] = text

        elif text_lower == "model" or text_lower == "model number":
            # Get the model value
            for j in range(i+1, min(i+3, len(about_elements))):
                next_text = about_elements[j].get("text", "")
                if next_text and next_text != text:
                    version_info["model"] = next_text
                    break

    # Display found information
    print("\n   📊 Device Information:")
    print("   " + "=" * 50)

    if "model" in version_info:
        print(f"   📱 Model: {version_info['model']}")

    if "android_version_value" in version_info:
        print(f"   🤖 Android Version: {version_info['android_version_value']}")
    elif "android_version_label" in version_info:
        print(f"   🤖 {version_info['android_version_label']}")

    if "api_level" in version_info:
        print(f"   🔢 API Level: {version_info['api_level']}")

    if "build_number" in version_info:
        print(f"   🏗️  Build: {version_info['build_number']}")

    if not version_info:
        print("   ⚠️  Could not extract version info. Showing all text elements:")
        for elem in about_elements[:20]:
            text = elem.get("text", "")
            if text:
                print(f"      • {text}")

    print("\n" + "=" * 60)
    print("✅ Demo complete!")
    print("=" * 60)


def main():
    """Main entry point"""
    print("\n" + "=" * 60)
    print("🤖 NeuralBridge MCP Test Client")
    print("=" * 60)
    print()

    # Configuration
    server_path = "/home/rdondeti/Code/Android/neuralBridge/mcp-server/target/release/neuralbridge-mcp"
    device_id = "emulator-5554"

    # Create client
    client = MCPClient(server_path, device_id)

    try:
        # Start MCP server
        client.start()

        # Run demo
        demo_open_settings_and_find_version(client)

    except KeyboardInterrupt:
        print("\n\n⚠️  Interrupted by user")
    except Exception as e:
        print(f"\n❌ Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        # Clean up
        client.stop()


if __name__ == "__main__":
    main()
