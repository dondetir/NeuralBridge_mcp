"""Scenario 5: Chrome Web Automation & Clipboard

Demonstrates:
- open_url, pull_to_refresh, wait_for_element, wait_for_gone
- set_clipboard, get_clipboard, input_text, dismiss_keyboard
- scroll_to_element, screenshot, close_app
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


async def run_scenario_5_chrome(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 5: Chrome Web Automation & Clipboard.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 5: Chrome Web Automation & Clipboard[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: open_url, wait_for_element, pull_to_refresh, clipboard, input_text",
        border_style="cyan"
    ))

    try:
        # Step 1: Open URL in Chrome
        console.print("\n[bold]Step 1:[/bold] Open URL in Chrome")
        async with measure_latency(tracker, "open_url"):
            await client.open_url("https://httpbin.org/forms/post")

        await asyncio.sleep(2)
        console.print("  ✅ URL opened")

        # Step 2: Wait for page to load (with fallback)
        console.print("\n[bold]Step 2:[/bold] Wait for page element")
        try:
            async with measure_latency(tracker, "wait_for_element"):
                await client.wait_for_element(text="Customer name", timeout_ms=8000)
            console.print("  ✅ Page loaded (form found)")
        except Exception as e:
            console.print(f"  ⚠️  Form not found, opening fallback URL: {e}")
            async with measure_latency(tracker, "open_url"):
                await client.open_url("chrome://version")
            await asyncio.sleep(2)
            console.print("  ✅ Fallback URL opened")

        # Step 3: Pull to refresh
        console.print("\n[bold]Step 3:[/bold] Pull to refresh")
        async with measure_latency(tracker, "pull_to_refresh"):
            await client.pull_to_refresh()

        await asyncio.sleep(1)
        console.print("  ✅ Pull to refresh executed")

        # Step 4: Wait for loading to finish
        console.print("\n[bold]Step 4:[/bold] Wait for loading to finish")
        try:
            async with measure_latency(tracker, "wait_for_gone"):
                await client.wait_for_gone(text="Loading", timeout_ms=5000)
            console.print("  ✅ Loading finished")
        except Exception:
            console.print("  ℹ️  No loading indicator found (page already loaded)")

        # Step 5: Screenshot loaded page
        console.print("\n[bold]Step 5:[/bold] Screenshot loaded page")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"scenario5_loaded_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 6: Set clipboard
        console.print("\n[bold]Step 6:[/bold] Set clipboard")
        test_text = "NeuralBridge clipboard test"
        async with measure_latency(tracker, "set_clipboard"):
            await client.set_clipboard(test_text)

        console.print(f"  ✅ Clipboard set: [green]{test_text}[/green]")

        # Step 7: Get clipboard and verify
        console.print("\n[bold]Step 7:[/bold] Get clipboard and verify")
        async with measure_latency(tracker, "get_clipboard"):
            clipboard_text = await client.get_clipboard()

        if clipboard_text == test_text:
            console.print(f"  ✅ Clipboard verified: [green]{clipboard_text}[/green]")
        else:
            console.print(f"  ⚠️  Clipboard mismatch: got '{clipboard_text}'")

        # Step 8: Try to find and tap input field, then input text
        console.print("\n[bold]Step 8:[/bold] Try to input text in form field")
        try:
            async with measure_latency(tracker, "find_elements"):
                input_fields = await client.find_elements(class_name="EditText")

            if input_fields:
                console.print(f"  Found {len(input_fields)} input fields")
                # Tap first input field
                first_field = input_fields[0]
                x = first_field["bounds"]["center_x"]
                y = first_field["bounds"]["center_y"]

                async with measure_latency(tracker, "tap"):
                    await client.tap(x=x, y=y)

                await asyncio.sleep(0.5)

                # Input text
                async with measure_latency(tracker, "input_text"):
                    await client.input_text("Test input")

                console.print("  ✅ Text input successful")
            else:
                console.print("  ℹ️  No input fields found")
        except Exception as e:
            console.print(f"  ⚠️  Input failed: {e}")

        # Step 9: Dismiss keyboard
        console.print("\n[bold]Step 9:[/bold] Dismiss keyboard")
        async with measure_latency(tracker, "dismiss_keyboard"):
            await client.dismiss_keyboard()

        console.print("  ✅ Keyboard dismissed")

        # Step 10: Try to scroll to Submit button
        console.print("\n[bold]Step 10:[/bold] Scroll to Submit button")
        try:
            async with measure_latency(tracker, "scroll_to_element"):
                await client.scroll_to_element(text="Submit", direction="up", max_scrolls=5)
            console.print("  ✅ Submit button found")
        except Exception as e:
            console.print(f"  ℹ️  Submit button not found: {e}")

        # Step 11: Final screenshot
        console.print("\n[bold]Step 11:[/bold] Final screenshot")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"scenario5_final_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 12: Clean up
        console.print("\n[bold]Step 12:[/bold] Clean up")
        async with measure_latency(tracker, "close_app"):
            await client.close_app("com.android.chrome")

        await asyncio.sleep(0.5)

        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        console.print("  ✅ Chrome closed, returned to home")

        # Success
        console.print("\n[bold green]✅ Scenario 5: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 5: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
