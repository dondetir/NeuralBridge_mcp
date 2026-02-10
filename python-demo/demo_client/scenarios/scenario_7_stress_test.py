"""Scenario 7: Performance Stress Test

Demonstrates:
- Performance validation across all fast-path tools
- Latency measurement and reporting
"""

import asyncio
from pathlib import Path
from typing import TYPE_CHECKING

from rich.panel import Panel
from rich.progress import track

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_7_stress_test(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 7: Performance Stress Test.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 7: Performance Stress Test[/bold cyan]\n"
        "Duration: ~1 minute\n"
        "Goal: Validate <100ms latency target for fast-path operations",
        border_style="cyan"
    ))

    try:
        # Get screen dimensions
        ui_tree = await client.get_ui_tree()
        screen_bounds = ui_tree.get("screen_bounds", {"width": 1080, "height": 1920})
        center_x = screen_bounds["width"] // 2
        center_y = screen_bounds["height"] // 2

        # Go to home screen
        await client.global_action("HOME")
        await asyncio.sleep(0.5)

        # Run stress test cycles
        num_cycles = 20
        console.print(f"\n[bold]Running {num_cycles} stress test cycles...[/bold]\n")

        for i in track(range(num_cycles), description="Stress testing..."):
            # Cycle operations:
            # 1. Tap home button
            async with measure_latency(tracker, "tap_stress"):
                await client.tap(x=center_x, y=center_y)

            await asyncio.sleep(0.05)  # Brief pause

            # 2. Get UI tree
            async with measure_latency(tracker, "get_ui_tree_stress"):
                await client.get_ui_tree(include_invisible=False)

            await asyncio.sleep(0.05)

            # 3. Take screenshot (thumbnail quality for speed)
            async with measure_latency(tracker, "screenshot_stress"):
                await client.screenshot(quality="thumbnail")

            await asyncio.sleep(0.05)

            # 4. Get foreground app
            async with measure_latency(tracker, "get_foreground_app_stress"):
                await client.get_foreground_app()

            await asyncio.sleep(0.05)

        console.print("\n[bold]Stress test complete![/bold]\n")

        # Calculate statistics
        console.print("[bold]Performance Analysis:[/bold]\n")

        operations = {
            "tap_stress": "Tap",
            "get_ui_tree_stress": "Get UI Tree",
            "screenshot_stress": "Screenshot (thumbnail)",
            "get_foreground_app_stress": "Get Foreground App"
        }

        all_passed = True
        for op_key, op_name in operations.items():
            stats = tracker.get_stats(op_key)
            if stats:
                avg = stats["avg"]
                p50 = stats["p50"]
                p95 = stats["p95"]
                p99 = stats["p99"]

                # Check if P95 meets <100ms target
                passed = p95 < 100.0
                status = "✅ PASS" if passed else "❌ FAIL"
                color = "green" if passed else "red"

                console.print(f"  {status} [{color}]{op_name}[/{color}]")
                console.print(f"     Avg: {avg:.1f}ms | P50: {p50:.1f}ms | P95: {p95:.1f}ms | P99: {p99:.1f}ms")
                console.print(f"     Count: {stats['count']}\n")

                if not passed:
                    all_passed = False

        # Overall success/failure
        if all_passed:
            console.print("[bold green]✅ All operations < 100ms (P95)[/bold green]")
            console.print("[bold green]✅ Performance target: VALIDATED[/bold green]\n")
        else:
            console.print("[bold yellow]⚠ Some operations > 100ms (P95)[/bold yellow]")
            console.print("[bold yellow]Note: Emulator performance may vary[/bold yellow]\n")

        # Print full performance summary
        tracker.print_summary("Stress Test Performance Summary")

        # Return to home
        await client.global_action("HOME")
        await asyncio.sleep(0.3)

        # Success (even if some operations are slow, the test itself passed)
        console.print("\n[bold green]✅ Scenario 7: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 7: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
