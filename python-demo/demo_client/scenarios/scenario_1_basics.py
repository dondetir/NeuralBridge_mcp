"""Scenario 1: UI Inspection & Navigation (Basics)

Demonstrates:
- get_ui_tree, screenshot, find_elements
- tap, press_key, global_action
- Basic observation and gesture tools
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


async def run_scenario_1_basics(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 1: UI Inspection & Navigation.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 1: UI Inspection & Navigation[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: get_ui_tree, screenshot, find_elements, tap, press_key, global_action",
        border_style="cyan"
    ))

    try:
        # Step 1: Get foreground app
        console.print("\n[bold]Step 1:[/bold] Get foreground app")
        async with measure_latency(tracker, "get_foreground_app"):
            app_info = await client.get_foreground_app()

        console.print(f"  ✅ Current app: [green]{app_info.get('package_name', 'unknown')}[/green]")

        # Step 2: Get UI tree (first 10 elements)
        console.print("\n[bold]Step 2:[/bold] Get UI tree")
        async with measure_latency(tracker, "get_ui_tree"):
            ui_tree = await client.get_ui_tree(include_invisible=False)

        total_nodes = ui_tree.get("total_nodes", 0)
        elements = ui_tree.get("elements", [])[:10]  # First 10 elements

        console.print(f"  ✅ Total elements: [green]{total_nodes}[/green]")

        # Display elements table
        if elements:
            table = Table(title="First 10 UI Elements", show_header=True, header_style="bold magenta")
            table.add_column("Resource ID", style="cyan", no_wrap=True)
            table.add_column("Class", style="yellow")
            table.add_column("Text", style="green")
            table.add_column("Bounds", style="blue")

            for elem in elements:
                table.add_row(
                    elem.get("resource_id", "")[-30:],  # Last 30 chars
                    elem.get("class_name", "").split(".")[-1],  # Short class name
                    elem.get("text", "")[:30],  # First 30 chars
                    f"{elem.get('bounds', {}).get('width', 0)}x{elem.get('bounds', {}).get('height', 0)}"
                )

            console.print(table)

        # Step 3: Take screenshot
        console.print("\n[bold]Step 3:[/bold] Take screenshot")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"scenario1_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 4: Find all buttons
        console.print("\n[bold]Step 4:[/bold] Find all buttons")
        async with measure_latency(tracker, "find_elements"):
            buttons = await client.find_elements(clickable=True)

        button_count = len(buttons)
        console.print(f"  ✅ Found [green]{button_count}[/green] clickable elements")

        # Show first 5 button texts
        if buttons:
            button_texts = [b.get("text", "(no text)") for b in buttons[:5] if b.get("text")]
            if button_texts:
                console.print(f"  First button texts: {', '.join(button_texts[:5])}")

        # Step 5: Tap home button (use global action instead of coordinates)
        console.print("\n[bold]Step 5:[/bold] Press HOME (global action)")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        await asyncio.sleep(0.5)  # Wait for animation
        console.print("  ✅ HOME action executed")

        # Step 6: Press BACK key
        console.print("\n[bold]Step 6:[/bold] Press BACK key")
        async with measure_latency(tracker, "press_key"):
            await client.press_key("BACK")

        await asyncio.sleep(0.3)
        console.print("  ✅ BACK key pressed")

        # Step 7: Return to home
        console.print("\n[bold]Step 7:[/bold] Return to launcher (global HOME)")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to home screen")

        # Success
        console.print("\n[bold green]✅ Scenario 1: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 1: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
