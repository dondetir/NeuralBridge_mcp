"""MCP Client Layer - Connection to NeuralBridge Rust MCP Server.

This module provides a wrapper around the official MCP Python SDK to communicate
with the NeuralBridge MCP server (Rust) via stdio transport.
"""

import asyncio
import json
import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.types import Tool

logger = logging.getLogger(__name__)


class MCPError(Exception):
    """Base exception for MCP-related errors."""
    pass


class MCPConnectionError(MCPError):
    """Error connecting to MCP server."""
    pass


class MCPToolError(MCPError):
    """Error calling MCP tool."""
    pass


class NeuralBridgeMCPClient:
    """Client for communicating with NeuralBridge MCP server.

    Uses the official MCP Python SDK to spawn and communicate with the
    Rust MCP server binary via stdio transport.

    Example:
        ```python
        client = NeuralBridgeMCPClient(
            mcp_server_path="./mcp-server/target/release/neuralbridge-mcp",
            device_id="emulator-5554"  # or set ANDROID_DEVICE_ID env var
        )
        await client.connect()
        result = await client.call_tool("android_tap", {"coordinates": {"x": 100, "y": 200}})
        await client.close()
        ```
    """

    def __init__(
        self,
        mcp_server_path: str,
        device_id: str = None,
        auto_discover: bool = False,
        no_consolidate: bool = False
    ):
        """Initialize MCP client.

        Args:
            mcp_server_path: Path to neuralbridge-mcp binary
            device_id: Android device ID (e.g., "emulator-5554"). Defaults to
                ANDROID_DEVICE_ID env var, then auto-discovery.
            auto_discover: Use auto-discovery instead of specific device
        """
        self.server_path = Path(mcp_server_path).resolve()
        self.device_id = device_id or os.environ.get("ANDROID_DEVICE_ID")
        self.auto_discover = auto_discover or self.device_id is None
        self.no_consolidate = no_consolidate

        # MCP session components
        self._session: Optional[ClientSession] = None
        self._read_stream = None
        self._write_stream = None
        self._stdio_context = None
        self._session_context = None

        # Connection state
        self._connected = False
        self._tools_cache: List[Tool] = []

        logger.debug(
            f"Initialized MCP client: server={self.server_path}, "
            f"device={self.device_id}, auto_discover={self.auto_discover}"
        )

    async def connect(self) -> None:
        """Connect to MCP server and initialize session.

        Raises:
            MCPConnectionError: If connection fails
        """
        if self._connected:
            logger.warning("Already connected to MCP server")
            return

        try:
            # Verify binary exists
            if not self.server_path.exists():
                raise MCPConnectionError(
                    f"MCP server binary not found: {self.server_path}\n"
                    f"Build it with: cd mcp-server && cargo build --release"
                )

            # Build server command
            args = []
            if self.auto_discover:
                args.append("--auto-discover")
            else:
                args.extend(["--device", self.device_id])
            if self.no_consolidate:
                args.append("--no-consolidate")

            logger.info(f"Starting MCP server: {self.server_path} {' '.join(args)}")

            # Create server parameters with Android SDK environment
            import os
            from pathlib import Path
            env = os.environ.copy()
            # Set ANDROID_HOME for ADB discovery
            if "ANDROID_HOME" not in env:
                # Try common Android SDK locations
                home = Path.home()
                candidates = [
                    home / "Android" / "Sdk",
                    home / "Library" / "Android" / "sdk",
                    Path("/opt/android-sdk")
                ]
                android_sdk_path = None
                for p in candidates:
                    if p.exists():
                        android_sdk_path = str(p)
                        break

                if android_sdk_path:
                    env["ANDROID_HOME"] = android_sdk_path
                    # Add platform-tools to PATH
                    if "PATH" in env:
                        env["PATH"] = f"{android_sdk_path}/platform-tools:{env['PATH']}"
                    else:
                        env["PATH"] = f"{android_sdk_path}/platform-tools"

            server_params = StdioServerParameters(
                command=str(self.server_path),
                args=args,
                env=env
            )

            # Connect via stdio
            self._stdio_context = stdio_client(server_params)
            self._read_stream, self._write_stream = await self._stdio_context.__aenter__()

            # Create session
            self._session_context = ClientSession(self._read_stream, self._write_stream)
            self._session = await self._session_context.__aenter__()

            # Initialize session
            await self._session.initialize()

            self._connected = True
            logger.info("✅ Connected to MCP server successfully")

            # Cache available tools
            await self._refresh_tools()

        except Exception as e:
            logger.error(f"Failed to connect to MCP server: {e}")
            await self.close()
            raise MCPConnectionError(f"Connection failed: {e}") from e

    async def _refresh_tools(self) -> None:
        """Refresh the cache of available tools."""
        if not self._session:
            return

        try:
            tools_list = await self._session.list_tools()
            self._tools_cache = tools_list.tools
            logger.debug(f"Cached {len(self._tools_cache)} tools from MCP server")
        except Exception as e:
            logger.warning(f"Failed to refresh tools cache: {e}")

    async def list_tools(self) -> List[Tool]:
        """List all available MCP tools.

        Returns:
            List of Tool objects

        Raises:
            MCPConnectionError: If not connected
        """
        if not self._connected or not self._session:
            raise MCPConnectionError("Not connected to MCP server")

        if not self._tools_cache:
            await self._refresh_tools()

        return self._tools_cache

    async def call_tool(
        self,
        tool_name: str,
        arguments: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        """Call an MCP tool and return the result.

        Args:
            tool_name: Name of the MCP tool (e.g., "android_tap")
            arguments: Tool arguments as dictionary

        Returns:
            Tool result as dictionary

        Raises:
            MCPConnectionError: If not connected
            MCPToolError: If tool call fails
        """
        if not self._connected or not self._session:
            raise MCPConnectionError("Not connected to MCP server")

        if arguments is None:
            arguments = {}

        logger.debug(f"Calling tool: {tool_name}({json.dumps(arguments, indent=2)})")

        try:
            # Call tool via MCP session
            result = await self._session.call_tool(tool_name, arguments)

            # Check for errors
            if result.isError:
                error_msg = "Unknown error"
                if result.content:
                    # Extract error message from content
                    if hasattr(result.content[0], 'text'):
                        error_msg = result.content[0].text
                    else:
                        error_msg = str(result.content[0])

                logger.error(f"Tool call failed: {tool_name} - {error_msg}")
                raise MCPToolError(f"{tool_name} failed: {error_msg}")

            # Parse result
            if not result.content:
                return {}

            # Extract text content and parse as JSON
            content_text = result.content[0].text if hasattr(result.content[0], 'text') else str(result.content[0])

            try:
                return json.loads(content_text)
            except json.JSONDecodeError:
                # If not JSON, return as raw text
                return {"text": content_text}

        except MCPToolError:
            raise
        except Exception as e:
            logger.error(f"Unexpected error calling tool {tool_name}: {e}")
            raise MCPToolError(f"{tool_name} failed: {e}") from e

    async def close(self) -> None:
        """Close MCP connection and cleanup resources."""
        if not self._connected:
            return

        logger.info("Closing MCP connection...")

        try:
            # Close session
            if self._session_context:
                await self._session_context.__aexit__(None, None, None)
                self._session = None
                self._session_context = None

            # Close stdio streams
            if self._stdio_context:
                await self._stdio_context.__aexit__(None, None, None)
                self._read_stream = None
                self._write_stream = None
                self._stdio_context = None

            self._connected = False
            logger.info("✅ MCP connection closed")

        except Exception as e:
            logger.error(f"Error during close: {e}")

    @property
    def is_connected(self) -> bool:
        """Check if client is connected to MCP server."""
        return self._connected

    async def __aenter__(self):
        """Async context manager entry."""
        await self.connect()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        """Async context manager exit."""
        await self.close()
