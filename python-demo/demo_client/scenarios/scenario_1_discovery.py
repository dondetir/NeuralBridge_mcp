"""Scenario 1: Device Discovery & Inspection

Demonstrates:
- get_device_info, get_screen_context
- screenshot (quality comparison)
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


async def run_scenario_1_discovery(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 1: Device Discovery & Inspection.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 1: Device Discovery & Inspection[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: get_device_info, get_screen_context, screenshot",
        border_style="cyan"
    ))

    try:
        # Step 1: Get device info
        console.print("\n[bold]Step 1:[/bold] Get device information")
        async with measure_latency(tracker, "get_device_info"):
            device_info = await client.get_device_info()

        # Display device info table
        table = Table(title="Device Specifications", show_header=True, header_style="bold magenta")
        table.add_column("Property", style="cyan", no_wrap=True)
        table.add_column("Value", style="green")

        table.add_row("Manufacturer", device_info.get("manufacturer", "unknown"))
        table.add_row("Model", device_info.get("model", "unknown"))
        table.add_row("Android Version", device_info.get("android_version", "unknown"))
        table.add_row("API Level", str(device_info.get("api_level", "unknown")))
        table.add_row("Screen Resolution", f"{device_info.get('screen_width', 0)}x{device_info.get('screen_height', 0)}")
        table.add_row("Screen Density", str(device_info.get('screen_density', 'unknown')))

        console.print(table)

        # Step 2: Get screen context (summary only)
        console.print("\n[bold]Step 2:[/bold] Get screen context (summary)")
        try:
            async with measure_latency(tracker, "get_screen_context"):
                context_summary = await client.get_screen_context(include_all_elements=False)
            console.print(f"  ✅ Current app: [green]{context_summary.get('foreground_app', {}).get('package_name', 'unknown')}[/green]")
            console.print(f"  ✅ Element count (summary): [green]{len(context_summary.get('elements', []))}[/green]")
        except Exception as e:
            console.print(f"  ⚠️ get_screen_context failed (MediaProjection may not be available): {e}")
            context_summary = {}

        # Step 3: Screenshot (full quality)
        console.print("\n[bold]Step 3:[/bold] Take screenshot (full quality)")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_full_path = screenshot_dir / f"scenario1_full_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot_full"):
            screenshot_full_bytes = await client.screenshot(quality="full", save_path=screenshot_full_path)

        size_full_kb = len(screenshot_full_bytes) / 1024
        console.print(f"  ✅ Full quality screenshot: [green]{screenshot_full_path.name}[/green] ({size_full_kb:.1f} KB)")

        # Step 4: Get screen context (full)
        console.print("\n[bold]Step 4:[/bold] Get screen context (all elements)")
        try:
            async with measure_latency(tracker, "get_screen_context_full"):
                context_full = await client.get_screen_context(include_all_elements=True)
            full_element_count = len(context_full.get('elements', []))
            console.print(f"  ✅ Element count (full): [green]{full_element_count}[/green]")
            summary_count = len(context_summary.get('elements', []))
            console.print(f"  [yellow]Summary vs Full: {summary_count} vs {full_element_count} elements[/yellow]")
        except Exception as e:
            console.print(f"  ⚠️ get_screen_context (full) failed: {e}")

        # Step 5: Screenshot (thumbnail)
        console.print("\n[bold]Step 5:[/bold] Take screenshot (thumbnail quality)")
        screenshot_thumb_path = screenshot_dir / f"scenario1_thumb_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot_thumb"):
            screenshot_thumb_bytes = await client.screenshot(quality="thumbnail", save_path=screenshot_thumb_path)

        size_thumb_kb = len(screenshot_thumb_bytes) / 1024
        console.print(f"  ✅ Thumbnail screenshot: [green]{screenshot_thumb_path.name}[/green] ({size_thumb_kb:.1f} KB)")

        # Show quality comparison
        compression_ratio = size_thumb_kb / size_full_kb * 100 if size_full_kb > 0 else 0
        console.print(f"  [yellow]Quality comparison: thumbnail is {compression_ratio:.1f}% of full size[/yellow]")

        # Success
        console.print("\n[bold green]✅ Scenario 1: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 1: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
