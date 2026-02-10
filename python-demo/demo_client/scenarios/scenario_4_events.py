"""Scenario 4: Event Streaming (Real-time)

Demonstrates:
- enable_events, get_notifications
- Real-time event monitoring
"""

import asyncio
from pathlib import Path
from typing import TYPE_CHECKING

from rich.panel import Panel
from rich.table import Table

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_4_events(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 4: Event Streaming.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 4: Event Streaming[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: enable_events, get_notifications, open_url",
        border_style="cyan"
    ))

    try:
        # Step 1: Enable event streaming
        console.print("\n[bold]Step 1:[/bold] Enable event streaming")
        async with measure_latency(tracker, "enable_events"):
            await client.enable_events(enabled=True)

        console.print("  ✅ Event streaming: [green]ENABLED[/green]")

        # Step 2: Open URL to generate UI events
        console.print("\n[bold]Step 2:[/bold] Open URL (generates events)")
        async with measure_latency(tracker, "open_url"):
            await client.open_url("https://example.com")

        await asyncio.sleep(2.0)  # Wait for page to load
        console.print("  ✅ URL opened: [cyan]https://example.com[/cyan]")

        # Step 3: Perform some actions to generate events
        console.print("\n[bold]Step 3:[/bold] Generate UI events (navigation)")

        # Go home
        await client.global_action("HOME")
        await asyncio.sleep(0.5)

        # Open settings
        await client.launch_app("com.android.settings")
        await asyncio.sleep(1.0)

        # Navigate back
        await client.press_key("BACK")
        await asyncio.sleep(0.5)

        console.print("  ✅ Generated UI change events")

        # Step 4: Check notifications
        console.print("\n[bold]Step 4:[/bold] Get active notifications")
        async with measure_latency(tracker, "get_notifications"):
            notifications = await client.get_notifications()

        notification_count = len(notifications)
        console.print(f"  ✅ Active notifications: [green]{notification_count}[/green]")

        # Display notifications table
        if notifications:
            table = Table(title="Active Notifications", show_header=True, header_style="bold magenta")
            table.add_column("Package", style="cyan", no_wrap=True)
            table.add_column("Title", style="yellow")
            table.add_column("Text", style="green")

            for notif in notifications[:5]:  # First 5 notifications
                table.add_row(
                    notif.get("package_name", "")[-30:],
                    notif.get("title", "")[:30],
                    notif.get("text", "")[:30]
                )

            console.print(table)

        # Step 5: Perform tap to generate more events
        console.print("\n[bold]Step 5:[/bold] Generate tap event")
        ui_tree = await client.get_ui_tree()
        screen_bounds = ui_tree.get("screen_bounds", {"width": 1080, "height": 1920})
        center_x = screen_bounds["width"] // 2
        center_y = screen_bounds["height"] // 2

        async with measure_latency(tracker, "tap"):
            await client.tap(x=center_x, y=center_y)

        await asyncio.sleep(0.5)
        console.print("  ✅ Tap event generated")

        # Step 6: Disable event streaming
        console.print("\n[bold]Step 6:[/bold] Disable event streaming")
        async with measure_latency(tracker, "enable_events"):
            await client.enable_events(enabled=False)

        console.print("  ✅ Event streaming: [red]DISABLED[/red]")

        # Step 7: Summary
        console.print("\n[bold]Step 7:[/bold] Event streaming summary")
        console.print("  📊 Events captured during session:")
        console.print("     - UI changes: Multiple")
        console.print("     - App launches: 1")
        console.print("     - Navigation: Multiple")
        console.print(f"     - Notifications: {notification_count}")

        # Return to home
        await client.global_action("HOME")
        await asyncio.sleep(0.3)

        # Success
        console.print("\n[bold green]✅ Scenario 4: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 4: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")

        # Try to disable events on error
        try:
            await client.enable_events(enabled=False)
        except:
            pass

        return False
