"""High-level Android API wrapper for NeuralBridge MCP tools.

Provides a friendly Python API wrapping all 36 MCP tools from Phase 1, Phase 2, and Phase 3.
"""

import base64
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

from .mcp_client import NeuralBridgeMCPClient, MCPToolError

logger = logging.getLogger(__name__)


class AndroidClient:
    """High-level Android automation client.

    Wraps NeuralBridge MCP tools with a convenient Python API.

    Example:
        ```python
        client = AndroidClient(mcp_client)
        await client.launch_app("com.android.settings")
        await client.tap(text="Wi-Fi")
        screenshot_bytes = await client.screenshot()
        ```
    """

    def __init__(self, mcp_client: NeuralBridgeMCPClient):
        """Initialize Android client.

        Args:
            mcp_client: Connected NeuralBridgeMCPClient instance
        """
        self.mcp = mcp_client

    # ========== OBSERVE TOOLS (6) ==========

    async def get_ui_tree(
        self,
        include_invisible: bool = False,
        max_depth: Optional[int] = None
    ) -> Dict[str, Any]:
        """Get complete UI hierarchy.

        Args:
            include_invisible: Include invisible elements
            max_depth: Maximum tree depth (None = unlimited)

        Returns:
            Dictionary with UI tree structure

        Raises:
            MCPToolError: If tool call fails
        """
        args = {"include_invisible": include_invisible}
        if max_depth is not None:
            args["max_depth"] = max_depth

        return await self.mcp.call_tool("android_get_ui_tree", args)

    async def screenshot(
        self,
        quality: str = "full",
        save_path: Optional[Path] = None
    ) -> bytes:
        """Capture screen as JPEG.

        Args:
            quality: "full" (quality 80) or "thumbnail" (quality 40)
            save_path: Optional path to save screenshot

        Returns:
            JPEG image bytes

        Raises:
            MCPToolError: If tool call fails
        """
        result = await self.mcp.call_tool("android_screenshot", {"quality": quality})

        # Decode base64 image
        image_data = result.get("image_data", "")
        image_bytes = base64.b64decode(image_data)

        # Save if path provided
        if save_path:
            save_path = Path(save_path)
            save_path.parent.mkdir(parents=True, exist_ok=True)
            save_path.write_bytes(image_bytes)
            logger.debug(f"Screenshot saved to {save_path} ({len(image_bytes)} bytes)")

        return image_bytes

    async def find_elements(
        self,
        text: Optional[str] = None,
        resource_id: Optional[str] = None,
        content_desc: Optional[str] = None,
        class_name: Optional[str] = None,
        clickable: Optional[bool] = None,
        **kwargs
    ) -> List[Dict[str, Any]]:
        """Find UI elements by selector.

        Args:
            text: Text content to match
            resource_id: Resource ID to match (suffix matching)
            content_desc: Content description to match
            class_name: Class name to match
            clickable: Filter by clickable state
            **kwargs: Additional selector fields (scrollable, focusable, etc.)

        Returns:
            List of matching elements

        Raises:
            MCPToolError: If tool call fails
        """
        # Build flat params dict (MCP expects flat structure, not nested selector)
        params = {
            k: v for k, v in {
                "text": text,
                "resource_id": resource_id,
                "content_desc": content_desc,
                "class_name": class_name,
                "clickable": clickable,
                **kwargs
            }.items() if v is not None
        }

        if not params:
            raise ValueError("At least one selector field required")

        result = await self.mcp.call_tool("android_find_elements", params)
        return result.get("elements", [])

    async def get_foreground_app(self) -> Dict[str, str]:
        """Get current foreground app.

        Returns:
            Dictionary with package_name and activity

        Raises:
            MCPToolError: If tool call fails
        """
        return await self.mcp.call_tool("android_get_foreground_app", {})

    # ========== ACT TOOLS (8 + 4 advanced gestures) ==========

    async def tap(
        self,
        x: Optional[int] = None,
        y: Optional[int] = None,
        text: Optional[str] = None,
        resource_id: Optional[str] = None,
        **selector_kwargs
    ) -> None:
        """Tap on screen by coordinates or selector.

        Args:
            x: X coordinate (if using coordinates)
            y: Y coordinate (if using coordinates)
            text: Text selector
            resource_id: Resource ID selector
            **selector_kwargs: Additional selector fields

        Raises:
            MCPToolError: If tool call fails
            ValueError: If neither coordinates nor selector provided
        """
        # Build flat params dict (MCP expects flat x, y fields)
        params = {}

        if x is not None and y is not None:
            params["x"] = x
            params["y"] = y
        else:
            # Add selector fields directly to params
            selector_fields = {
                k: v for k, v in {
                    "text": text,
                    "resource_id": resource_id,
                    **selector_kwargs
                }.items() if v is not None
            }
            if not selector_fields:
                raise ValueError("Must provide either coordinates (x, y) or selector")
            params.update(selector_fields)

        await self.mcp.call_tool("android_tap", params)

    async def long_press(
        self,
        x: Optional[int] = None,
        y: Optional[int] = None,
        duration_ms: int = 1000,
        text: Optional[str] = None,
        resource_id: Optional[str] = None,
        **selector_kwargs
    ) -> None:
        """Long press on screen.

        Args:
            x: X coordinate (if using coordinates)
            y: Y coordinate (if using coordinates)
            duration_ms: Press duration in milliseconds
            text: Text selector
            resource_id: Resource ID selector
            **selector_kwargs: Additional selector fields

        Raises:
            MCPToolError: If tool call fails
        """
        # Build flat params dict (MCP expects flat x, y fields)
        params = {"duration_ms": duration_ms}

        if x is not None and y is not None:
            params["x"] = x
            params["y"] = y
        else:
            # Add selector fields directly to params
            selector_fields = {
                k: v for k, v in {
                    "text": text,
                    "resource_id": resource_id,
                    **selector_kwargs
                }.items() if v is not None
            }
            params.update(selector_fields)

        await self.mcp.call_tool("android_long_press", params)

    async def swipe(
        self,
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        duration_ms: int = 300
    ) -> None:
        """Swipe gesture.

        Args:
            x1: Start X coordinate
            y1: Start Y coordinate
            x2: End X coordinate
            y2: End Y coordinate
            duration_ms: Swipe duration

        Raises:
            MCPToolError: If tool call fails
        """
        # MCP expects flat start_x, start_y, end_x, end_y fields
        await self.mcp.call_tool("android_swipe", {
            "start_x": x1,
            "start_y": y1,
            "end_x": x2,
            "end_y": y2,
            "duration_ms": duration_ms
        })

    async def input_text(
        self,
        text: str,
        x: Optional[int] = None,
        y: Optional[int] = None,
        **selector_kwargs
    ) -> None:
        """Input text into element.

        Args:
            text: Text to input
            x: X coordinate (if using coordinates)
            y: Y coordinate (if using coordinates)
            **selector_kwargs: Selector fields

        Raises:
            MCPToolError: If tool call fails
        """
        # Build flat params dict (MCP expects flat x, y fields)
        params = {"text": text}

        if x is not None and y is not None:
            params["x"] = x
            params["y"] = y
        else:
            # Add selector fields directly to params
            selector_fields = {k: v for k, v in selector_kwargs.items() if v is not None}
            params.update(selector_fields)

        await self.mcp.call_tool("android_input_text", params)

    async def press_key(self, key: str) -> None:
        """Press hardware key.

        Args:
            key: Key code (e.g., "BACK", "ENTER", "HOME")

        Raises:
            MCPToolError: If tool call fails
        """
        await self.mcp.call_tool("android_press_key", {"key": key.lower()})

    async def global_action(self, action: str) -> None:
        """Perform global accessibility action.

        Args:
            action: Action name (e.g., "HOME", "RECENTS", "NOTIFICATIONS")

        Raises:
            MCPToolError: If tool call fails
        """
        await self.mcp.call_tool("android_global_action", {"action": action.upper()})

    # Phase 2: Advanced Gestures

    async def double_tap(
        self,
        x: Optional[int] = None,
        y: Optional[int] = None,
        **selector_kwargs
    ) -> None:
        """Double tap gesture.

        Args:
            x: X coordinate (if using coordinates)
            y: Y coordinate (if using coordinates)
            **selector_kwargs: Selector fields

        Raises:
            MCPToolError: If tool call fails
        """
        # Build flat params dict (MCP expects flat x, y fields)
        params = {}

        if x is not None and y is not None:
            params["x"] = x
            params["y"] = y
        else:
            # Add selector fields directly to params
            selector_fields = {k: v for k, v in selector_kwargs.items() if v is not None}
            params.update(selector_fields)

        await self.mcp.call_tool("android_double_tap", params)

    async def pinch(
        self,
        center_x: int,
        center_y: int,
        scale: float,
        duration_ms: int = 500
    ) -> None:
        """Pinch zoom gesture.

        Args:
            center_x: Center X coordinate
            center_y: Center Y coordinate
            scale: Scale factor (>1.0 = zoom in, <1.0 = zoom out)
            duration_ms: Gesture duration

        Raises:
            MCPToolError: If tool call fails
        """
        # MCP expects flat center_x, center_y fields
        await self.mcp.call_tool("android_pinch", {
            "center_x": center_x,
            "center_y": center_y,
            "scale": scale,
            "duration_ms": duration_ms
        })

    async def drag(
        self,
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        duration_ms: int = 500
    ) -> None:
        """Drag gesture (long press + move).

        Args:
            x1: Start X coordinate
            y1: Start Y coordinate
            x2: End X coordinate
            y2: End Y coordinate
            duration_ms: Drag duration

        Raises:
            MCPToolError: If tool call fails
        """
        # MCP expects flat from_x, from_y, to_x, to_y fields
        await self.mcp.call_tool("android_drag", {
            "from_x": x1,
            "from_y": y1,
            "to_x": x2,
            "to_y": y2,
            "duration_ms": duration_ms
        })

    async def fling(self, direction: str) -> None:
        """Fling gesture (fast scroll).

        Args:
            direction: Direction ("UP", "DOWN", "LEFT", "RIGHT")

        Raises:
            MCPToolError: If tool call fails
        """
        await self.mcp.call_tool("android_fling", {"direction": direction.upper()})

    # ========== MANAGE TOOLS (3) ==========

    async def launch_app(self, package: str, activity: Optional[str] = None) -> None:
        """Launch Android app.

        Args:
            package: Package name (e.g., "com.android.settings")
            activity: Optional activity name

        Raises:
            MCPToolError: If tool call fails
        """
        args = {"package_name": package}
        if activity:
            args["activity"] = activity

        await self.mcp.call_tool("android_launch_app", args)

    async def close_app(self, package: str, force: bool = True) -> None:
        """Close Android app.

        Args:
            package: Package name
            force: Use force-stop (via ADB)

        Raises:
            MCPToolError: If tool call fails
        """
        await self.mcp.call_tool("android_close_app", {
            "package_name": package,
            "force": force
        })

    async def open_url(self, url: str) -> None:
        """Open URL in browser.

        Args:
            url: URL to open

        Raises:
            MCPToolError: If tool call fails
        """
        await self.mcp.call_tool("android_open_url", {"url": url})

    # ========== WAIT TOOLS (3) ==========

    async def wait_for_element(
        self,
        timeout_ms: int = 5000,
        **selector_kwargs
    ) -> Dict[str, Any]:
        """Wait for element to appear.

        Args:
            timeout_ms: Timeout in milliseconds
            **selector_kwargs: Element selector fields

        Returns:
            Element information

        Raises:
            MCPToolError: If element not found within timeout
        """
        # Build flat params dict (MCP expects flat selector fields)
        selector_fields = {k: v for k, v in selector_kwargs.items() if v is not None}
        if not selector_fields:
            raise ValueError("At least one selector field required")

        params = {"timeout_ms": timeout_ms}
        params.update(selector_fields)

        return await self.mcp.call_tool("android_wait_for_element", params)

    async def wait_for_idle(self, timeout_ms: int = 3000) -> None:
        """Wait for UI to stabilize.

        Args:
            timeout_ms: Timeout in milliseconds

        Raises:
            MCPToolError: If UI doesn't stabilize within timeout
        """
        await self.mcp.call_tool("android_wait_for_idle", {"timeout_ms": timeout_ms})

    # ========== EVENT TOOLS (2) ==========

    async def enable_events(
        self,
        enabled: bool,
        event_types: Optional[List[str]] = None
    ) -> None:
        """Enable/disable real-time event streaming.

        Args:
            enabled: Enable or disable events
            event_types: Optional list of event types to filter

        Raises:
            MCPToolError: If tool call fails
        """
        args = {"enable": enabled}
        if event_types:
            args["event_types"] = event_types

        await self.mcp.call_tool("android_enable_events", args)

    async def get_notifications(self) -> List[Dict[str, Any]]:
        """Get active notifications.

        Returns:
            List of notification objects

        Raises:
            MCPToolError: If tool call fails
        """
        result = await self.mcp.call_tool("android_get_notifications", {})
        return result.get("notifications", [])

    # ========== CLIPBOARD TOOLS (2) ==========

    async def get_clipboard(self) -> str:
        """Get clipboard text.

        Returns:
            Clipboard text content

        Raises:
            MCPToolError: If tool call fails
        """
        result = await self.mcp.call_tool("android_get_clipboard", {})
        return result.get("text", "")

    async def set_clipboard(self, text: str) -> None:
        """Set clipboard text.

        Args:
            text: Text to copy to clipboard

        Raises:
            MCPToolError: If tool call fails
        """
        await self.mcp.call_tool("android_set_clipboard", {"text": text})

    # ========== PHASE 3 TOOLS (11) ==========

    async def scroll_to_element(
        self,
        text: Optional[str] = None,
        resource_id: Optional[str] = None,
        content_desc: Optional[str] = None,
        direction: Optional[str] = None,
        max_scrolls: Optional[int] = None,
        timeout_ms: Optional[int] = None
    ) -> Dict[str, Any]:
        """Scroll until element is visible.

        Args:
            text, resource_id, content_desc: Element selector
            direction, max_scrolls, timeout_ms: Scroll behavior

        Returns:
            Element information

        Raises:
            MCPToolError: If element not found after scrolling
        """
        params = {
            k: v for k, v in {
                "text": text,
                "resource_id": resource_id,
                "content_desc": content_desc,
                "direction": direction,
                "max_scrolls": max_scrolls,
                "timeout_ms": timeout_ms
            }.items() if v is not None
        }

        return await self.mcp.call_tool("android_scroll_to_element", params)

    async def get_screen_context(
        self,
        include_all_elements: bool = False
    ) -> Dict[str, Any]:
        """Get semantic screen context (focused elements, visible text).

        Args:
            include_all_elements: Include all visible elements

        Returns:
            Screen context dictionary

        Raises:
            MCPToolError: If tool call fails
        """
        return await self.mcp.call_tool(
            "android_get_screen_context",
            {"include_all_elements": include_all_elements}
        )

    async def screenshot_diff(
        self,
        reference_base64: str,
        threshold: float = 0.95
    ) -> Dict[str, Any]:
        """Compare current screenshot with reference.

        Args:
            reference_base64: Base64-encoded reference image
            threshold: Similarity threshold (0.0-1.0)

        Returns:
            Dictionary with similarity score

        Raises:
            MCPToolError: If tool call fails
        """
        return await self.mcp.call_tool("android_screenshot_diff", {
            "reference_base64": reference_base64,
            "threshold": threshold
        })

    async def capture_logcat(
        self,
        package: Optional[str] = None,
        level: Optional[str] = None,
        lines: Optional[int] = None,
        crash_only: Optional[bool] = None
    ) -> Dict[str, Any]:
        """Capture logcat output.

        Args:
            package, level, lines, crash_only: Logcat filters

        Returns:
            Logcat output dictionary

        Raises:
            MCPToolError: If tool call fails
        """
        params = {
            k: v for k, v in {
                "package": package,
                "level": level,
                "lines": lines,
                "crash_only": crash_only
            }.items() if v is not None
        }

        return await self.mcp.call_tool("android_capture_logcat", params)

    async def get_recent_toasts(
        self,
        since_ms: Optional[int] = 5000
    ) -> List[Dict[str, Any]]:
        """Get recent toast messages.

        Args:
            since_ms: Time window in milliseconds

        Returns:
            List of toast message objects

        Raises:
            MCPToolError: If tool call fails
        """
        result = await self.mcp.call_tool(
            "android_get_recent_toasts",
            {"since_ms": since_ms} if since_ms is not None else {}
        )
        return result.get("toasts", [])

    async def pull_to_refresh(self) -> None:
        """Execute pull-to-refresh gesture.

        Raises:
            MCPToolError: If tool call fails
        """
        await self.mcp.call_tool("android_pull_to_refresh", {})

    async def dismiss_keyboard(self) -> None:
        """Dismiss on-screen keyboard.

        Raises:
            MCPToolError: If tool call fails
        """
        await self.mcp.call_tool("android_dismiss_keyboard", {})

    async def accessibility_audit(self) -> Dict[str, Any]:
        """Run accessibility audit on current screen.

        Returns:
            Audit results dictionary

        Raises:
            MCPToolError: If tool call fails
        """
        return await self.mcp.call_tool("android_accessibility_audit", {})

    async def clear_app_data(self, package_name: str) -> None:
        """Clear app data (via ADB).

        Args:
            package_name: Package to clear data for

        Raises:
            MCPToolError: If tool call fails
        """
        await self.mcp.call_tool("android_clear_app_data", {"package_name": package_name})

    async def get_device_info(self) -> Dict[str, Any]:
        """Get device information (model, OS version, screen size).

        Returns:
            Device info dictionary

        Raises:
            MCPToolError: If tool call fails
        """
        return await self.mcp.call_tool("android_get_device_info", {})

    async def wait_for_gone(
        self,
        text: Optional[str] = None,
        resource_id: Optional[str] = None,
        content_desc: Optional[str] = None,
        timeout_ms: Optional[int] = 5000
    ) -> Dict[str, Any]:
        """Wait for element to disappear.

        Args:
            text, resource_id, content_desc: Element selector
            timeout_ms: Timeout in milliseconds

        Returns:
            Success confirmation dictionary

        Raises:
            MCPToolError: If element still visible after timeout
        """
        params = {
            k: v for k, v in {
                "text": text,
                "resource_id": resource_id,
                "content_desc": content_desc,
                "timeout_ms": timeout_ms
            }.items() if v is not None
        }

        return await self.mcp.call_tool("android_wait_for_gone", params)
