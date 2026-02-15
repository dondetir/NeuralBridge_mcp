"""Scenario 12: Performance Stress Test

Demonstrates:
- High-frequency tool calls
- Performance measurement
- get_foreground_app
- screenshot
- get_ui_tree
- tap
"""

import asyncio
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING, List

from rich.panel import Panel
from rich.table import Table

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency, format_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_12_stress_test(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 12: Performance Stress Test.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 12: Performance Stress Test[/bold cyan]\n"
        "Duration: ~1 minute\n"
        "Tools: 100 rapid-fire operations (get_foreground_app, screenshot, get_ui_tree, tap)",
        border_style="cyan"
    ))

    try:
        # Step 1: Return to home and prepare
        console.print("\n[bold]Step 1:[/bold] Prepare for stress test")
        async with measure_latency(tracker, "global_action_home"):
            await client.global_action("HOME")

        await asyncio.sleep(1.0)
        console.print("  ✅ Home screen ready")

        # Create separate tracker for stress test stats
        stress_tracker = LatencyTracker()

        console.print("\n[bold cyan]Running 100 rapid-fire operations...[/bold cyan]")
        start_time = datetime.now()

        # Phase 1: 25x get_foreground_app
        console.print("\n[bold]Phase 1:[/bold] 25x get_foreground_app")
        phase1_latencies: List[float] = []
        for i in range(25):
            op_start = datetime.now()
            async with measure_latency(stress_tracker, "get_foreground_app"):
                await client.get_foreground_app()
            op_end = datetime.now()
            latency_ms = (op_end - op_start).total_seconds() * 1000
            phase1_latencies.append(latency_ms)

            # Also record to passed-in tracker
            async with measure_latency(tracker, f"stress_get_foreground_app_{i+1}"):
                pass  # Already measured above

        console.print(f"  ✅ Completed 25 get_foreground_app calls")

        # Phase 2: 25x screenshot (thumbnail)
        console.print("\n[bold]Phase 2:[/bold] 25x screenshot (thumbnail quality)")
        phase2_latencies: List[float] = []
        for i in range(25):
            op_start = datetime.now()
            async with measure_latency(stress_tracker, "screenshot_thumb"):
                await client.screenshot(quality="thumbnail")
            op_end = datetime.now()
            latency_ms = (op_end - op_start).total_seconds() * 1000
            phase2_latencies.append(latency_ms)

            # Also record to passed-in tracker
            async with measure_latency(tracker, f"stress_screenshot_{i+1}"):
                pass

        console.print(f"  ✅ Completed 25 screenshot calls")

        # Phase 3: 25x get_ui_tree
        console.print("\n[bold]Phase 3:[/bold] 25x get_ui_tree")
        phase3_latencies: List[float] = []
        for i in range(25):
            op_start = datetime.now()
            async with measure_latency(stress_tracker, "get_ui_tree"):
                await client.get_ui_tree(include_invisible=False, max_depth=3)
            op_end = datetime.now()
            latency_ms = (op_end - op_start).total_seconds() * 1000
            phase3_latencies.append(latency_ms)

            # Also record to passed-in tracker
            async with measure_latency(tracker, f"stress_get_ui_tree_{i+1}"):
                pass

        console.print(f"  ✅ Completed 25 get_ui_tree calls")

        # Phase 4: 25x tap (rapid taps on center of screen)
        console.print("\n[bold]Phase 4:[/bold] 25x tap (rapid-fire on center)")
        phase4_latencies: List[float] = []
        for i in range(25):
            op_start = datetime.now()
            async with measure_latency(stress_tracker, "tap"):
                await client.tap(x=720, y=1480)
            op_end = datetime.now()
            latency_ms = (op_end - op_start).total_seconds() * 1000
            phase4_latencies.append(latency_ms)

            # Also record to passed-in tracker
            async with measure_latency(tracker, f"stress_tap_{i+1}"):
                pass

            # Brief pause to avoid overwhelming the system
            await asyncio.sleep(0.05)

        console.print(f"  ✅ Completed 25 tap calls")

        # Calculate statistics
        end_time = datetime.now()
        total_duration_s = (end_time - start_time).total_seconds()

        def calc_stats(latencies: List[float]) -> dict:
            """Calculate P50, P95, P99, and average."""
            if not latencies:
                return {"avg": 0, "p50": 0, "p95": 0, "p99": 0}

            sorted_lat = sorted(latencies)
            n = len(sorted_lat)

            return {
                "avg": sum(sorted_lat) / n,
                "p50": sorted_lat[int(n * 0.50)],
                "p95": sorted_lat[int(n * 0.95)],
                "p99": sorted_lat[int(n * 0.99)]
            }

        phase1_stats = calc_stats(phase1_latencies)
        phase2_stats = calc_stats(phase2_latencies)
        phase3_stats = calc_stats(phase3_latencies)
        phase4_stats = calc_stats(phase4_latencies)

        # Display results table
        console.print("\n[bold]Stress Test Results:[/bold]")

        results_table = Table(title="Performance Statistics", show_header=True, header_style="bold magenta")
        results_table.add_column("Phase", style="cyan")
        results_table.add_column("Ops", style="white", justify="right")
        results_table.add_column("Avg (ms)", style="green", justify="right")
        results_table.add_column("P50 (ms)", style="yellow", justify="right")
        results_table.add_column("P95 (ms)", style="blue", justify="right")
        results_table.add_column("P99 (ms)", style="red", justify="right")

        results_table.add_row(
            "get_foreground_app",
            "25",
            f"{phase1_stats['avg']:.1f}",
            f"{phase1_stats['p50']:.1f}",
            f"{phase1_stats['p95']:.1f}",
            f"{phase1_stats['p99']:.1f}"
        )
        results_table.add_row(
            "screenshot (thumb)",
            "25",
            f"{phase2_stats['avg']:.1f}",
            f"{phase2_stats['p50']:.1f}",
            f"{phase2_stats['p95']:.1f}",
            f"{phase2_stats['p99']:.1f}"
        )
        results_table.add_row(
            "get_ui_tree",
            "25",
            f"{phase3_stats['avg']:.1f}",
            f"{phase3_stats['p50']:.1f}",
            f"{phase3_stats['p95']:.1f}",
            f"{phase3_stats['p99']:.1f}"
        )
        results_table.add_row(
            "tap",
            "25",
            f"{phase4_stats['avg']:.1f}",
            f"{phase4_stats['p50']:.1f}",
            f"{phase4_stats['p95']:.1f}",
            f"{phase4_stats['p99']:.1f}"
        )

        console.print(results_table)

        # Display total summary
        console.print(f"\n[bold]Total:[/bold] 100 operations completed in [green]{total_duration_s:.2f}s[/green]")
        console.print(f"  Throughput: [green]{100 / total_duration_s:.1f} ops/sec[/green]")

        # Count operations under 100ms
        all_latencies = phase1_latencies + phase2_latencies + phase3_latencies + phase4_latencies
        under_100ms = sum(1 for lat in all_latencies if lat < 100)
        pct_under_100ms = (under_100ms / len(all_latencies)) * 100

        console.print(f"  Operations < 100ms: [green]{under_100ms}/100 ({pct_under_100ms:.0f}%)[/green]")

        # Highlight performance achievement
        if pct_under_100ms >= 80:
            console.print(f"  [bold green]🎯 Excellent: {pct_under_100ms:.0f}% of operations under 100ms![/bold green]")
        elif pct_under_100ms >= 50:
            console.print(f"  [bold yellow]⚠️ Good: {pct_under_100ms:.0f}% of operations under 100ms[/bold yellow]")
        else:
            console.print(f"  [bold red]⚠️ Needs improvement: Only {pct_under_100ms:.0f}% under 100ms[/bold red]")

        # Return to home
        console.print("\n[bold]Clean up:[/bold] Return to home screen")
        async with measure_latency(tracker, "global_action_home_final"):
            await client.global_action("HOME")

        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to home screen")

        # Success
        console.print("\n[bold green]✅ Scenario 12: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 12: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
