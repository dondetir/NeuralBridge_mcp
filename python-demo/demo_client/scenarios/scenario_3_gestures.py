"""Scenario 3: Advanced Gestures (Phase 2)

Demonstrates:
- double_tap, pinch, drag, fling
- Advanced gesture capabilities
"""

import asyncio
from pathlib import Path
from typing import TYPE_CHECKING

from rich.panel import Panel

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_3_gestures(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 3: Advanced Gestures.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 3: Advanced Gestures[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: double_tap, pinch, drag, fling, swipe, long_press",
        border_style="cyan"
    ))

    try:
        # Step 1: Go to home screen
        console.print("\n[bold]Step 1:[/bold] Navigate to home screen")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        await asyncio.sleep(0.5)
        console.print("  ✅ On home screen")

        # Get screen dimensions from UI tree
        ui_tree = await client.get_ui_tree()
        screen_bounds = ui_tree.get("screen_bounds", {"width": 1080, "height": 1920})
        center_x = screen_bounds["width"] // 2
        center_y = screen_bounds["height"] // 2

        console.print(f"  Screen: {screen_bounds['width']}x{screen_bounds['height']}")

        # Step 2: Double tap gesture
        console.print("\n[bold]Step 2:[/bold] Double tap at center")
        async with measure_latency(tracker, "double_tap"):
            await client.double_tap(x=center_x, y=center_y)

        await asyncio.sleep(0.5)
        console.print("  ✅ Double tap executed")

        # Step 3: Pinch out (zoom out)
        console.print("\n[bold]Step 3:[/bold] Pinch gesture (zoom out)")
        async with measure_latency(tracker, "pinch"):
            await client.pinch(
                center_x=center_x,
                center_y=center_y,
                scale=0.5,  # Zoom out
                duration_ms=500
            )

        await asyncio.sleep(0.5)
        console.print("  ✅ Pinch out (scale 0.5)")

        # Step 4: Pinch in (zoom in)
        console.print("\n[bold]Step 4:[/bold] Pinch gesture (zoom in)")
        async with measure_latency(tracker, "pinch"):
            await client.pinch(
                center_x=center_x,
                center_y=center_y,
                scale=2.0,  # Zoom in
                duration_ms=500
            )

        await asyncio.sleep(0.5)
        console.print("  ✅ Pinch in (scale 2.0)")

        # Step 5: Drag gesture
        console.print("\n[bold]Step 5:[/bold] Drag gesture (left to right)")
        async with measure_latency(tracker, "drag"):
            await client.drag(
                x1=center_x - 200,
                y1=center_y,
                x2=center_x + 200,
                y2=center_y,
                duration_ms=500
            )

        await asyncio.sleep(0.5)
        console.print("  ✅ Drag executed")

        # Step 6: Fling up (scroll)
        console.print("\n[bold]Step 6:[/bold] Fling up")
        async with measure_latency(tracker, "fling"):
            await client.fling(direction="UP")

        await asyncio.sleep(0.5)
        console.print("  ✅ Fling up executed")

        # Step 7: Swipe down
        console.print("\n[bold]Step 7:[/bold] Swipe down")
        async with measure_latency(tracker, "swipe"):
            await client.swipe(
                x1=center_x,
                y1=center_y - 300,
                x2=center_x,
                y2=center_y + 300,
                duration_ms=300
            )

        await asyncio.sleep(0.5)
        console.print("  ✅ Swipe down executed")

        # Step 8: Long press at center
        console.print("\n[bold]Step 8:[/bold] Long press at center")
        async with measure_latency(tracker, "long_press"):
            await client.long_press(x=center_x, y=center_y, duration_ms=1000)

        await asyncio.sleep(0.5)
        console.print("  ✅ Long press executed")

        # Return to home
        console.print("\n[bold]Step 9:[/bold] Return to home")
        await client.press_key("BACK")
        await asyncio.sleep(0.3)
        await client.global_action("HOME")
        await asyncio.sleep(0.3)
        console.print("  ✅ Returned to home")

        # Success
        console.print("\n[bold green]✅ Scenario 3: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 3: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
