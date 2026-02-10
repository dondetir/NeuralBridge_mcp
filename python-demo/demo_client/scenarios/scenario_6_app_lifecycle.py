"""Scenario 6: App Lifecycle Management

Demonstrates:
- launch_app, close_app, get_foreground_app
- App lifecycle control
"""

import asyncio
from pathlib import Path
from typing import TYPE_CHECKING

from rich.panel import Panel

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_6_app_lifecycle(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 6: App Lifecycle Management.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 6: App Lifecycle Management[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: launch_app, close_app, get_foreground_app, wait_for_idle",
        border_style="cyan"
    ))

    try:
        # Test with Settings app (always available)
        test_package = "com.android.settings"

        # Step 1: Get current foreground app
        console.print("\n[bold]Step 1:[/bold] Get current foreground app")
        async with measure_latency(tracker, "get_foreground_app"):
            initial_app = await client.get_foreground_app()

        initial_package = initial_app.get("package_name", "unknown")
        console.print(f"  ✅ Initial app: [cyan]{initial_package}[/cyan]")

        # Step 2: Launch test app
        console.print(f"\n[bold]Step 2:[/bold] Launch {test_package}")
        async with measure_latency(tracker, "launch_app"):
            await client.launch_app(test_package)

        await asyncio.sleep(1.5)  # Wait for app to start
        console.print(f"  ✅ App launched")

        # Step 3: Wait for app to load
        console.print("\n[bold]Step 3:[/bold] Wait for app to load")
        async with measure_latency(tracker, "wait_for_idle"):
            await client.wait_for_idle(timeout_ms=5000)

        console.print("  ✅ App loaded (UI idle)")

        # Step 4: Verify app is foreground
        console.print("\n[bold]Step 4:[/bold] Verify app is foreground")
        async with measure_latency(tracker, "get_foreground_app"):
            current_app = await client.get_foreground_app()

        current_package = current_app.get("package_name", "")
        current_activity = current_app.get("activity", "")

        if current_package == test_package:
            console.print(f"  ✅ Foreground app verified: [green]{current_package}[/green]")
            console.print(f"     Activity: {current_activity.split('.')[-1]}")
        else:
            console.print(f"  ⚠ Unexpected foreground app: [yellow]{current_package}[/yellow]")

        # Step 5: Close app (force-stop via ADB)
        console.print(f"\n[bold]Step 5:[/bold] Close {test_package} (force-stop)")
        async with measure_latency(tracker, "close_app"):
            await client.close_app(test_package, force=True)

        console.print("  ✅ App closed (force-stop via ADB)")

        # Step 6: Wait for close to complete
        console.print("\n[bold]Step 6:[/bold] Wait for close to complete")
        await asyncio.sleep(1.0)
        console.print("  ✅ Wait complete")

        # Step 7: Verify app is closed
        console.print("\n[bold]Step 7:[/bold] Verify app is closed")
        async with measure_latency(tracker, "get_foreground_app"):
            final_app = await client.get_foreground_app()

        final_package = final_app.get("package_name", "")

        if final_package != test_package:
            console.print(f"  ✅ App closed verified: [green]{final_package}[/green] (not {test_package})")
        else:
            console.print(f"  ⚠ App still foreground: [yellow]{final_package}[/yellow]")

        # Step 8: Lifecycle summary
        console.print("\n[bold]Step 8:[/bold] Lifecycle summary")
        console.print(f"  📊 Full lifecycle completed:")
        console.print(f"     Initial: {initial_package}")
        console.print(f"     Launched: {test_package}")
        console.print(f"     Closed: {test_package}")
        console.print(f"     Final: {final_package}")

        # Return to home
        await client.global_action("HOME")
        await asyncio.sleep(0.3)

        # Success
        console.print("\n[bold green]✅ Scenario 6: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 6: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
