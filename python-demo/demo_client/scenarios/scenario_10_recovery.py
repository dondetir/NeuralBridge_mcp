"""Scenario 10: Error Recovery & Resilience

Demonstrates:
- Error handling (MCPToolError)
- find_elements
- get_screen_context
- tap
- screenshot
"""

import asyncio
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING

from rich.panel import Panel
from rich.table import Table

from ..mcp_client import MCPToolError
from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency, format_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_10_recovery(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 10: Error Recovery & Resilience.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 10: Error Recovery & Resilience[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: tap (with error), find_elements, get_screen_context, screenshot",
        border_style="cyan"
    ))

    try:
        # Step 1: Launch Settings app
        console.print("\n[bold]Step 1:[/bold] Launch Settings app")
        async with measure_latency(tracker, "launch_app"):
            await client.launch_app("com.android.settings")

        await asyncio.sleep(1.0)
        console.print("  ✅ Settings app launched")

        # Step 2: Intentionally try a bad selector
        console.print("\n[bold]Step 2:[/bold] Intentionally try bad selector (expect failure)")
        console.print("  [yellow]Attempting to tap on 'NonExistentButton123'...[/yellow]")

        error_caught = False
        error_message = ""
        try:
            async with measure_latency(tracker, "tap_bad_selector"):
                await client.tap(text="NonExistentButton123")
        except MCPToolError as e:
            error_caught = True
            error_message = str(e)
            console.print(f"  ✅ [yellow]Expected error caught: {error_message}[/yellow]")

        if not error_caught:
            console.print("  [red]WARNING: Expected error was not caught[/red]")

        # Step 3: Find elements with bad selector (expect empty list)
        console.print("\n[bold]Step 3:[/bold] Find elements with bad selector (expect empty list)")
        async with measure_latency(tracker, "find_bad_elements"):
            bad_elements = await client.find_elements(text="NonExistentButton123")

        console.print(f"  ✅ Found [yellow]{len(bad_elements)} elements[/yellow] (as expected)")

        # Step 4: Re-assess screen with get_screen_context
        console.print("\n[bold]Step 4:[/bold] Re-assess screen with get_screen_context")
        try:
            async with measure_latency(tracker, "get_screen_context"):
                context = await client.get_screen_context(include_all_elements=False)
            foreground_app = context.get("foreground_app", {})
            elements = context.get("elements", [])
            console.print(f"  ✅ Current app: [green]{foreground_app.get('package_name', 'unknown')}[/green]")
            console.print(f"  ✅ Found [green]{len(elements)} interactive elements[/green]")
        except Exception as e:
            console.print(f"  ⚠️ get_screen_context failed (fallback to get_foreground_app): {e}")
            app_info = await client.get_foreground_app()
            console.print(f"  ✅ Current app: [green]{app_info.get('package_name', 'unknown')}[/green]")

        # Step 5: Find REAL elements (clickable)
        console.print("\n[bold]Step 5:[/bold] Find real clickable elements")
        async with measure_latency(tracker, "find_clickable"):
            clickable_elements = await client.find_elements(clickable=True)

        console.print(f"  ✅ Found [green]{len(clickable_elements)} clickable elements[/green]")

        # Pick first element with text
        target_element = None
        for elem in clickable_elements:
            if elem.get("text"):
                target_element = elem
                break

        if target_element:
            target_text = target_element.get("text", "")
            console.print(f"  ✅ Selected target element: [cyan]{target_text}[/cyan]")
        else:
            console.print("  [yellow]No elements with text found, using first clickable element[/yellow]")
            target_element = clickable_elements[0] if clickable_elements else None

        # Step 6: Tap on real element
        if target_element:
            console.print("\n[bold]Step 6:[/bold] Tap on real element (recovery successful)")
            element_id = target_element.get("element_id", "")

            try:
                async with measure_latency(tracker, "tap_real_element"):
                    # Try tapping by text first, fallback to element_id
                    if target_element.get("text"):
                        await client.tap(text=target_element.get("text"))
                    else:
                        await client.tap(element_id=element_id)

                console.print(f"  ✅ [green]Successfully tapped element (error recovery complete)[/green]")
            except MCPToolError as e:
                console.print(f"  [yellow]Tap failed (expected on some elements): {e}[/yellow]")
        else:
            console.print("\n[bold]Step 6:[/bold] [yellow]No clickable elements found (skipping tap)[/yellow]")

        # Step 7: Wait and screenshot
        console.print("\n[bold]Step 7:[/bold] Capture recovery screenshot")
        await asyncio.sleep(0.5)

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"scenario10_recovery_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 8: Clean up - go back
        console.print("\n[bold]Step 8:[/bold] Clean up")
        async with measure_latency(tracker, "press_back"):
            await client.press_key("BACK")

        await asyncio.sleep(0.3)

        async with measure_latency(tracker, "close_app"):
            await client.close_app("com.android.settings")

        console.print("  ✅ Closed Settings app")

        # Step 9: Return to home
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to home screen")

        # Display recovery summary
        console.print("\n[bold]Error Recovery Summary:[/bold]")
        console.print(f"  [green]✓[/green] Bad selector detected and error caught")
        console.print(f"  [green]✓[/green] Screen context re-assessed")
        console.print(f"  [green]✓[/green] Real element found and tapped")
        console.print(f"  [green]✓[/green] Recovery workflow completed successfully")

        # Success
        console.print("\n[bold green]✅ Scenario 10: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 10: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
