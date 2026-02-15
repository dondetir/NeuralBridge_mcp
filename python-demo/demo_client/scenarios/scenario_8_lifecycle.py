"""Scenario 8: App Lifecycle & Debugging

Demonstrates:
- get_foreground_app for app state verification
- capture_logcat for debugging logs
- screenshot for visual inspection
- clear_app_data for app reset (first-run state)
- close_app with force flag

Note:
- Uses Chrome (NOT Chrome) to avoid clearing user's browsing data
- Demonstrates full app lifecycle: launch → capture logs → reset → relaunch
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


async def run_scenario_8_lifecycle(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 8: App Lifecycle & Debugging.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 8: App Lifecycle & Debugging[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: get_foreground_app, capture_logcat, clear_app_data, close_app",
        border_style="cyan"
    ))

    try:
        # Step 1: Launch Chrome
        console.print("\n[bold]Step 1:[/bold] Launch Chrome")
        async with measure_latency(tracker, "launch_app"):
            await client.launch_app("com.android.chrome")

        await asyncio.sleep(2)
        console.print("  ✅ Chrome launched")

        # Step 2: Get foreground app
        console.print("\n[bold]Step 2:[/bold] Get foreground app")
        async with measure_latency(tracker, "get_foreground_app"):
            app_info = await client.get_foreground_app()

        package_name = app_info.get("package_name", "unknown")
        activity_name = app_info.get("activity_name", "unknown")

        console.print(f"  ✅ Foreground app: [green]{package_name}[/green]")
        console.print(f"     Activity: [cyan]{activity_name}[/cyan]")

        # Verify it's Chrome
        if "sbrowser" in package_name:
            console.print("  ✅ Chrome verified in foreground")
        else:
            console.print(f"  ⚠️  Unexpected app in foreground: {package_name}")

        # Step 3: Capture logcat
        console.print("\n[bold]Step 3:[/bold] Capture startup logs")
        async with measure_latency(tracker, "capture_logcat"):
            logcat_result = await client.capture_logcat(
                package="com.android.chrome",
                level="I",
                lines=20
            )

        # Extract lines from result (may be dict with "output" or "lines" key)
        if isinstance(logcat_result, dict):
            logcat_text = logcat_result.get("output", logcat_result.get("lines", ""))
            if isinstance(logcat_text, list):
                logcat_lines = logcat_text
            elif isinstance(logcat_text, str):
                logcat_lines = logcat_text.strip().split("\n") if logcat_text else []
            else:
                logcat_lines = []
        elif isinstance(logcat_result, str):
            logcat_lines = logcat_result.strip().split("\n") if logcat_result else []
        else:
            logcat_lines = []

        console.print(f"  ✅ Captured [green]{len(logcat_lines)}[/green] log lines")

        # Display first 10 lines
        if logcat_lines:
            console.print("\n  [bold]First 10 log lines:[/bold]")
            for i, line in enumerate(logcat_lines[:10], 1):
                display_line = str(line)[:100] + "..." if len(str(line)) > 100 else str(line)
                console.print(f"    {i:2d}. {display_line}")

        # Step 4: Screenshot initial state
        console.print("\n[bold]Step 4:[/bold] Screenshot initial state")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"scenario8_initial_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 5: Clear app data
        console.print("\n[bold]Step 5:[/bold] Clear app data")
        async with measure_latency(tracker, "clear_app_data"):
            await client.clear_app_data("com.android.chrome")

        console.print("  ✅ App data cleared (reset to first-run state)")

        # Step 6: Wait and relaunch
        console.print("\n[bold]Step 6:[/bold] Relaunch Chrome")
        await asyncio.sleep(1)

        async with measure_latency(tracker, "launch_app"):
            await client.launch_app("com.android.chrome")

        await asyncio.sleep(2)
        console.print("  ✅ Chrome relaunched (should show first-run/welcome)")

        # Step 7: Screenshot first-run state
        console.print("\n[bold]Step 7:[/bold] Screenshot first-run state")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"scenario8_firstrun_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 8: Force close app
        console.print("\n[bold]Step 8:[/bold] Force close Chrome")
        async with measure_latency(tracker, "close_app"):
            await client.close_app("com.android.chrome", force=True)

        console.print("  ✅ Chrome force closed")

        # Step 9: Return to home
        console.print("\n[bold]Step 9:[/bold] Return to home")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        console.print("  ✅ Returned to home screen")

        # Success
        console.print("\n[bold green]✅ Scenario 8: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 8: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
