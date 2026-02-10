#!/usr/bin/env python3
"""Quick connection test for NeuralBridge MCP client."""

import asyncio
import sys
from pathlib import Path

# Add parent directory to path
sys.path.insert(0, str(Path(__file__).parent))

from demo_client.mcp_client import NeuralBridgeMCPClient, MCPConnectionError
from demo_client.android_client import AndroidClient
from demo_client.utils.logger import console


async def test_connection():
    """Test MCP connection and list available tools."""
    console.print("[bold cyan]Testing NeuralBridge MCP Connection...[/bold cyan]\n")

    # Connect to MCP server
    mcp_server_path = Path(__file__).parent.parent / "mcp-server/target/release/neuralbridge-mcp"
    device_id = "emulator-5554"

    console.print(f"MCP Server: {mcp_server_path}")
    console.print(f"Device: {device_id}\n")

    try:
        console.print("Connecting to MCP server...")
        mcp_client = NeuralBridgeMCPClient(str(mcp_server_path), device_id)
        await mcp_client.connect()

        console.print("[green]✅ Connection successful![/green]\n")

        # List available tools
        console.print("Listing available MCP tools...")
        tools = await mcp_client.list_tools()

        console.print(f"[green]✅ Found {len(tools)} tools:[/green]\n")

        for i, tool in enumerate(tools, 1):
            console.print(f"  {i:2d}. [cyan]{tool.name}[/cyan]")

        # Test a simple operation
        console.print("\n[bold]Testing basic operation (get_foreground_app)...[/bold]")
        client = AndroidClient(mcp_client)
        app_info = await client.get_foreground_app()

        console.print(f"[green]✅ Current app: {app_info.get('package_name', 'unknown')}[/green]\n")

        # Close connection
        await mcp_client.close()
        console.print("[green]✅ Connection closed successfully[/green]\n")

        console.print("[bold green]All tests passed! ✅[/bold green]")
        return 0

    except MCPConnectionError as e:
        console.print(f"[bold red]❌ Connection failed:[/bold red]")
        console.print(f"[red]{e}[/red]\n")

        console.print("[yellow]Troubleshooting:[/yellow]")
        console.print("  1. Verify emulator is running: adb devices")
        console.print("  2. Verify companion app is installed")
        console.print("  3. Verify port forwarding: adb forward tcp:38472 tcp:38472")
        console.print(f"  4. Verify MCP server binary exists: {mcp_server_path}")

        return 1

    except Exception as e:
        console.print(f"[bold red]❌ Unexpected error:[/bold red]")
        console.print(f"[red]{e}[/red]\n")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    exit_code = asyncio.run(test_connection())
    sys.exit(exit_code)
