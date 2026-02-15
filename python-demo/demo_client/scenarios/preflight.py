"""Preflight: Pre-flight checks

Demonstrates:
- get_foreground_app, get_device_info
- screenshot (MediaProjection consent trigger)
- global_action
"""

import asyncio
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING

from rich.panel import Panel
from rich.table import Table

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency, format_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_preflight(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Preflight: Pre-flight checks.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if preflight passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Preflight: Pre-flight Checks[/bold cyan]\n"
        "Duration: ~30 seconds\n"
        "Tools: get_foreground_app, get_device_info, screenshot, global_action",
        border_style="cyan"
    ))

    try:
        # Step 1: Verify device is alive
        console.print("\n[bold]Step 1:[/bold] Verify device connection")
        async with measure_latency(tracker, "get_foreground_app"):
            app_info = await client.get_foreground_app()

        console.print(f"  ✅ Device alive - Current app: [green]{app_info.get('package_name', 'unknown')}[/green]")

        # Step 2: Get device info
        console.print("\n[bold]Step 2:[/bold] Get device info")
        async with measure_latency(tracker, "get_device_info"):
            device_info = await client.get_device_info()

        # Display device info table
        table = Table(title="Device Information", show_header=True, header_style="bold magenta")
        table.add_column("Property", style="cyan", no_wrap=True)
        table.add_column("Value", style="green")

        table.add_row("Manufacturer", device_info.get("manufacturer", "unknown"))
        table.add_row("Model", device_info.get("model", "unknown"))
        table.add_row("Android Version", device_info.get("android_version", "unknown"))
        table.add_row("API Level", str(device_info.get("api_level", "unknown")))
        table.add_row("Screen Resolution", f"{device_info.get('screen_width', 0)}x{device_info.get('screen_height', 0)}")
        table.add_row("Screen Density", str(device_info.get('screen_density', 'unknown')))

        console.print(table)

        # Step 3: Trigger MediaProjection consent and take screenshot
        console.print("\n[bold]Step 3:[/bold] Take screenshot (MediaProjection consent may appear)")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"preflight_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")
        console.print("  [yellow]Note: If MediaProjection dialog appeared, permission is now granted[/yellow]")

        # Step 4: Clean state - return to home
        console.print("\n[bold]Step 4:[/bold] Return to home screen (clean state)")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        await asyncio.sleep(0.5)  # Wait for animation
        console.print("  ✅ Returned to home screen")

        # Success
        console.print("\n[bold green]✅ Preflight: PASSED[/bold green]")
        console.print("[green]Device is ready for demo scenarios[/green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Preflight: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
