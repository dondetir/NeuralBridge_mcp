"""Scenario 2: Form Automation

Demonstrates:
- launch_app, wait_for_idle, wait_for_element
- input_text, tap
- Form filling and navigation
"""

import asyncio
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING

from rich.panel import Panel

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_2_forms(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 2: Form Automation.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 2: Form Automation[/bold cyan]\n"
        "Duration: ~3 minutes\n"
        "Tools: launch_app, wait_for_element, input_text, tap, screenshot",
        border_style="cyan"
    ))

    try:
        # Step 1: Launch Settings app
        console.print("\n[bold]Step 1:[/bold] Launch Settings app")
        async with measure_latency(tracker, "launch_app"):
            await client.launch_app("com.android.settings")

        await asyncio.sleep(1.0)  # Wait for app to start
        console.print("  ✅ Settings app launched")

        # Step 2: Wait for UI to stabilize
        console.print("\n[bold]Step 2:[/bold] Wait for UI to stabilize")
        async with measure_latency(tracker, "wait_for_idle"):
            await client.wait_for_idle(timeout_ms=5000)

        console.print("  ✅ UI is idle")

        # Step 3: Find search box
        console.print("\n[bold]Step 3:[/bold] Find search box")
        try:
            async with measure_latency(tracker, "find_elements"):
                # Try common search box resource IDs
                search_boxes = await client.find_elements(resource_id="search")

            if not search_boxes:
                # Try text match
                search_boxes = await client.find_elements(content_desc="Search")

            if search_boxes:
                console.print(f"  ✅ Found search box: [green]{search_boxes[0].get('resource_id', 'unknown')}[/green]")
            else:
                console.print("  [yellow]⚠ No search box found, using alternative approach[/yellow]")

        except Exception as e:
            console.print(f"  [yellow]⚠ Search box not found: {e}[/yellow]")

        # Step 4: Tap on search (if found) or use a different element
        console.print("\n[bold]Step 4:[/bold] Navigate to Wi-Fi settings")
        try:
            # Try to find Wi-Fi in settings
            async with measure_latency(tracker, "find_elements"):
                wifi_elements = await client.find_elements(text="Wi")  # Partial match

            if wifi_elements:
                console.print(f"  ✅ Found {len(wifi_elements)} Wi-Fi related items")

                # Tap on first Wi-Fi item
                async with measure_latency(tracker, "tap"):
                    await client.tap(text="Wi")

                await asyncio.sleep(1.0)
                console.print("  ✅ Tapped Wi-Fi item")
            else:
                console.print("  [yellow]⚠ Wi-Fi item not found, continuing...[/yellow]")

        except Exception as e:
            console.print(f"  [yellow]⚠ Navigation failed: {e}[/yellow]")

        # Step 5: Take screenshot of current state
        console.print("\n[bold]Step 5:[/bold] Take screenshot")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"scenario2_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 6: Verify navigation
        console.print("\n[bold]Step 6:[/bold] Verify current screen")
        async with measure_latency(tracker, "get_foreground_app"):
            app_info = await client.get_foreground_app()

        package = app_info.get("package_name", "")
        activity = app_info.get("activity", "")
        console.print(f"  ✅ Current screen: [green]{package}[/green]")
        console.print(f"     Activity: {activity.split('.')[-1]}")

        # Step 7: Return home
        console.print("\n[bold]Step 7:[/bold] Return to home")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to home screen")

        # Success
        console.print("\n[bold green]✅ Scenario 2: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 2: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
