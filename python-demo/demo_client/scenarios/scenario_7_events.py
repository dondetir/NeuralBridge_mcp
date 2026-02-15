"""Scenario 7: Clock, Events & Notifications

Demonstrates:
- enable_events for toast and notification monitoring
- launch_app, tap for navigation
- get_notifications, get_recent_toasts for event capture
- close_app, global_action for cleanup

Note:
- Samsung Clock UI varies by device/version
- Uses liberal try/except for UI navigation
- Demonstrates event system even if timer setup fails
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


async def run_scenario_7_events(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 7: Clock, Events & Notifications.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 7: Clock, Events & Notifications[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: enable_events, get_notifications, get_recent_toasts, tap",
        border_style="cyan"
    ))

    try:
        # Step 1: Enable event streaming
        console.print("\n[bold]Step 1:[/bold] Enable event streaming")
        async with measure_latency(tracker, "enable_events"):
            await client.enable_events(True, ["toast_shown", "notification_posted"])

        console.print("  ✅ Event streaming enabled (toast, notification)")

        # Step 2: Launch Clock app
        console.print("\n[bold]Step 2:[/bold] Launch Clock app")
        async with measure_latency(tracker, "launch_app"):
            await client.launch_app("com.google.android.deskclock")

        await asyncio.sleep(1.5)
        console.print("  ✅ Clock app launched")

        # Step 3: Try to navigate to Timer tab
        console.print("\n[bold]Step 3:[/bold] Navigate to Timer tab")
        try:
            # Try text match first
            async with measure_latency(tracker, "tap"):
                await client.tap(text="Timer")

            await asyncio.sleep(0.5)
            console.print("  ✅ Navigated to Timer tab")
        except Exception as e:
            console.print(f"  ⚠️  Timer tab not found: {e}")

            # Try to find tabs via find_elements
            try:
                async with measure_latency(tracker, "find_elements"):
                    tabs = await client.find_elements(clickable=True)

                console.print(f"  ℹ️  Found {len(tabs)} clickable elements (tabs/buttons)")

                # Display first 5
                if tabs:
                    for i, tab in enumerate(tabs[:5]):
                        text = tab.get("text", "(no text)")
                        console.print(f"    {i+1}. {text}")
            except Exception:
                console.print("  ℹ️  Could not enumerate tabs")

        # Step 4: Try to set timer (5 seconds)
        console.print("\n[bold]Step 4:[/bold] Try to set 5-second timer")
        try:
            # Try to find number pad and tap digits
            async with measure_latency(tracker, "find_elements"):
                buttons = await client.find_elements(clickable=True)

            # Look for "5" button
            five_button = None
            for btn in buttons:
                if btn.get("text") == "5":
                    five_button = btn
                    break

            if five_button:
                x = five_button["bounds"]["center_x"]
                y = five_button["bounds"]["center_y"]

                async with measure_latency(tracker, "tap"):
                    await client.tap(x=x, y=y)

                console.print("  ✅ Tapped '5' button")
            else:
                console.print("  ℹ️  Number pad not found")
        except Exception as e:
            console.print(f"  ⚠️  Timer setup failed: {e}")

        # Step 5: Try to start timer
        console.print("\n[bold]Step 5:[/bold] Try to start timer")
        try:
            # Try text match
            async with measure_latency(tracker, "tap"):
                await client.tap(text="Start")

            console.print("  ✅ Timer started")
        except Exception:
            # Try content_desc
            try:
                async with measure_latency(tracker, "tap"):
                    await client.tap(content_desc="Start")

                console.print("  ✅ Timer started (via content_desc)")
            except Exception as e:
                console.print(f"  ⚠️  Start button not found: {e}")

        # Step 6: Wait for timer
        console.print("\n[bold]Step 6:[/bold] Wait for timer (7 seconds)")
        await asyncio.sleep(7)
        console.print("  ✅ Wait complete")

        # Step 7: Get notifications
        console.print("\n[bold]Step 7:[/bold] Get notifications")
        async with measure_latency(tracker, "get_notifications"):
            notifications = await client.get_notifications()

        console.print(f"  ✅ Retrieved [green]{len(notifications)}[/green] notifications")

        # Display notifications table
        if notifications:
            table = Table(title="Recent Notifications", show_header=True, header_style="bold magenta")
            table.add_column("Package", style="cyan", no_wrap=True)
            table.add_column("Title", style="green")
            table.add_column("Text", style="yellow")

            for notif in notifications[:5]:
                table.add_row(
                    notif.get("package_name", "")[-30:],
                    notif.get("title", "")[:30],
                    notif.get("text", "")[:30]
                )

            console.print(table)
        else:
            console.print("  ℹ️  No notifications found")

        # Step 8: Get recent toasts
        console.print("\n[bold]Step 8:[/bold] Get recent toasts")
        async with measure_latency(tracker, "get_recent_toasts"):
            toasts = await client.get_recent_toasts(since_ms=10000)

        console.print(f"  ✅ Retrieved [green]{len(toasts)}[/green] toasts")

        # Display toasts
        if toasts:
            table = Table(title="Recent Toasts (last 10s)", show_header=True, header_style="bold magenta")
            table.add_column("Text", style="green")
            table.add_column("Package", style="cyan", no_wrap=True)

            for toast in toasts[:5]:
                table.add_row(
                    toast.get("text", "")[:50],
                    toast.get("package_name", "")[-30:]
                )

            console.print(table)
        else:
            console.print("  ℹ️  No toasts found")

        # Step 9: Close Clock app
        console.print("\n[bold]Step 9:[/bold] Close Clock app")
        async with measure_latency(tracker, "close_app"):
            await client.close_app("com.google.android.deskclock")

        await asyncio.sleep(0.5)
        console.print("  ✅ Clock app closed")

        # Step 10: Disable event streaming
        console.print("\n[bold]Step 10:[/bold] Disable event streaming")
        async with measure_latency(tracker, "enable_events"):
            await client.enable_events(False)

        console.print("  ✅ Event streaming disabled")

        # Step 11: Return to home
        console.print("\n[bold]Step 11:[/bold] Return to home")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        console.print("  ✅ Returned to home screen")

        # Success
        console.print("\n[bold green]✅ Scenario 7: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 7: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
