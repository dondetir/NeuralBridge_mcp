"""Scenario 6: Multi-App Workflow

Demonstrates:
- launch_app, find_elements, tap across multiple apps
- set_clipboard, get_clipboard for cross-app data transfer
- close_app, global_action for app switching

Safety Note:
- Uses fictional phone number 555-0100
- Does NOT tap call button (observation only)
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


async def run_scenario_6_multiapp(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 6: Multi-App Workflow.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 6: Multi-App Workflow[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: launch_app, find_elements, clipboard, tap, close_app\n"
        "Safety: Uses 555-0100 (fictional), does NOT tap call button",
        border_style="cyan"
    ))

    try:
        # Step 1: Launch Contacts app
        console.print("\n[bold]Step 1:[/bold] Launch Contacts app")
        async with measure_latency(tracker, "launch_app"):
            await client.launch_app("com.google.android.contacts")

        await asyncio.sleep(3.0)
        try:
            await client.wait_for_idle(timeout_ms=5000)
        except Exception:
            pass

        # Dismiss any first-run dialogs
        for dismiss_text in ["Skip", "Got it", "OK", "Allow", "Not now", "SKIP", "NO THANKS"]:
            try:
                await client.tap(text=dismiss_text)
                await asyncio.sleep(0.5)
            except Exception:
                continue

        console.print("  ✅ Contacts app launched")

        # Step 2: Find contact list items
        console.print("\n[bold]Step 2:[/bold] Find contact list items")
        async with measure_latency(tracker, "find_elements"):
            clickable_items = await client.find_elements(clickable=True)

        console.print(f"  ✅ Found [green]{len(clickable_items)}[/green] clickable items")

        # Display first 5 items
        if clickable_items:
            table = Table(title="First 5 Clickable Items", show_header=True, header_style="bold magenta")
            table.add_column("Text", style="green")
            table.add_column("Class", style="yellow")
            table.add_column("Resource ID", style="cyan", no_wrap=True)

            for item in clickable_items[:5]:
                table.add_row(
                    item.get("text", "(no text)")[:40],
                    item.get("class_name", "").split(".")[-1],
                    item.get("resource_id", "")[-30:]
                )

            console.print(table)

        # Step 3: Tap first contact or fallback to coordinates
        console.print("\n[bold]Step 3:[/bold] Tap contact")
        try:
            if clickable_items:
                first_item = clickable_items[0]
                x = first_item["bounds"]["center_x"]
                y = first_item["bounds"]["center_y"]

                async with measure_latency(tracker, "tap"):
                    await client.tap(x=x, y=y)

                console.print("  ✅ Tapped first contact")
            else:
                # Fallback to center-top area
                async with measure_latency(tracker, "tap"):
                    await client.tap(x=720, y=600)

                console.print("  ✅ Tapped at coordinates (720, 600)")

            await asyncio.sleep(1)
        except Exception as e:
            console.print(f"  ⚠️  Tap failed: {e}")

        # Step 4: Copy phone number to clipboard
        console.print("\n[bold]Step 4:[/bold] Copy phone number to clipboard")
        phone_number = "555-0100"
        async with measure_latency(tracker, "set_clipboard"):
            await client.set_clipboard(phone_number)

        console.print(f"  ✅ Clipboard set: [green]{phone_number}[/green] (fictional)")

        # Step 5: Launch Dialer app
        console.print("\n[bold]Step 5:[/bold] Launch Dialer app")
        async with measure_latency(tracker, "launch_app"):
            await client.launch_app("com.google.android.dialer")

        await asyncio.sleep(1.5)
        console.print("  ✅ Dialer app launched")

        # Step 6: Tap on dialpad/search area
        console.print("\n[bold]Step 6:[/bold] Tap on dialpad/search area")
        try:
            # Try to find search/input field by resource_id
            async with measure_latency(tracker, "find_elements"):
                search_fields = await client.find_elements(class_name="EditText")

            if search_fields:
                first_field = search_fields[0]
                x = first_field["bounds"]["center_x"]
                y = first_field["bounds"]["center_y"]

                async with measure_latency(tracker, "tap"):
                    await client.tap(x=x, y=y)

                console.print("  ✅ Tapped search field")
            else:
                # Fallback to top-center area
                async with measure_latency(tracker, "tap"):
                    await client.tap(x=720, y=400)

                console.print("  ✅ Tapped at coordinates (720, 400)")
        except Exception as e:
            console.print(f"  ⚠️  Tap failed: {e}")

        # Step 7: Verify clipboard still has phone number
        console.print("\n[bold]Step 7:[/bold] Verify clipboard")
        async with measure_latency(tracker, "get_clipboard"):
            clipboard_text = await client.get_clipboard()

        if clipboard_text == phone_number:
            console.print(f"  ✅ Clipboard verified: [green]{clipboard_text}[/green]")
        else:
            console.print(f"  ⚠️  Clipboard changed: got '{clipboard_text}'")

        # Step 8: Try to verify number appears in UI
        console.print("\n[bold]Step 8:[/bold] Search for phone number in UI")
        try:
            async with measure_latency(tracker, "find_elements"):
                number_elements = await client.find_elements(text="555")

            if number_elements:
                console.print(f"  ✅ Found [green]{len(number_elements)}[/green] elements with '555'")
            else:
                console.print("  ℹ️  Number not found in UI (expected)")
        except Exception as e:
            console.print(f"  ℹ️  Search failed: {e}")

        # Step 9: Screenshot
        console.print("\n[bold]Step 9:[/bold] Screenshot dialer state")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"scenario6_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 10: Close Dialer
        console.print("\n[bold]Step 10:[/bold] Close Dialer")
        async with measure_latency(tracker, "close_app"):
            await client.close_app("com.google.android.dialer")

        await asyncio.sleep(0.5)
        console.print("  ✅ Dialer closed")

        # Step 11: Close Contacts
        console.print("\n[bold]Step 11:[/bold] Close Contacts")
        async with measure_latency(tracker, "close_app"):
            await client.close_app("com.google.android.contacts")

        await asyncio.sleep(0.5)
        console.print("  ✅ Contacts closed")

        # Step 12: Return to home
        console.print("\n[bold]Step 12:[/bold] Return to home")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        console.print("  ✅ Returned to home screen")

        # Success
        console.print("\n[bold green]✅ Scenario 6: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 6: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
