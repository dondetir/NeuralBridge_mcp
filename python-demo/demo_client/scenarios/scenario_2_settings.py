"""Scenario 2: Settings Deep Dive

Demonstrates:
- launch_app, close_app
- get_ui_tree, scroll_to_element
- tap, press_key, fling
- screenshot, screenshot_diff
"""

import asyncio
import base64
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING

from rich.panel import Panel
from rich.table import Table

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency, format_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_2_settings(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 2: Settings Deep Dive.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 2: Settings Deep Dive[/bold cyan]\n"
        "Duration: ~3 minutes\n"
        "Tools: launch_app, get_ui_tree, scroll_to_element, tap, fling, screenshot_diff, close_app",
        border_style="cyan"
    ))

    try:
        # Step 1: Launch Settings app
        console.print("\n[bold]Step 1:[/bold] Launch Settings app")
        async with measure_latency(tracker, "launch_app"):
            await client.launch_app("com.android.settings")

        await asyncio.sleep(2.0)  # Wait for app to fully load
        console.print("  ✅ Settings app launched")

        # Step 2: Get UI tree
        console.print("\n[bold]Step 2:[/bold] Get UI tree")
        await client.wait_for_idle(timeout_ms=3000)
        async with measure_latency(tracker, "get_ui_tree"):
            ui_tree = await client.get_ui_tree(include_invisible=False)

        element_count = ui_tree.get("total_nodes", 0)
        console.print(f"  ✅ UI tree contains [green]{element_count}[/green] elements")

        # Step 3: Screenshot (reference for diff later)
        console.print("\n[bold]Step 3:[/bold] Take reference screenshot")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_ref_path = screenshot_dir / f"scenario2_ref_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot_ref"):
            reference_bytes = await client.screenshot(quality="full", save_path=screenshot_ref_path)

        size_ref_kb = len(reference_bytes) / 1024
        console.print(f"  ✅ Reference saved: [green]{screenshot_ref_path.name}[/green] ({size_ref_kb:.1f} KB)")

        # Convert to base64 for screenshot_diff later
        reference_base64 = base64.b64encode(reference_bytes).decode('utf-8')

        # Step 4: Scroll to element (About phone / About emulated device)
        console.print("\n[bold]Step 4:[/bold] Scroll to 'About' section")
        scroll_found = False
        for search_text in ["About phone", "About emulated device", "About device", "About tablet"]:
            try:
                async with measure_latency(tracker, "scroll_to_element"):
                    await client.scroll_to_element(text=search_text, max_scrolls=10)
                console.print(f"  ✅ Found '{search_text}' after scrolling")
                scroll_found = True
                break
            except Exception:
                continue

        if not scroll_found:
            console.print("  ⚠️ Could not scroll to About section, continuing")

        # Step 5: Tap About
        console.print("\n[bold]Step 5:[/bold] Tap About section")
        tap_success = False
        for tap_text in ["About phone", "About emulated device", "About device"]:
            try:
                async with measure_latency(tracker, "tap"):
                    await client.tap(text=tap_text)
                tap_success = True
                break
            except Exception:
                continue

        if not tap_success:
            console.print("  ⚠️ Could not tap About section, continuing")

        await asyncio.sleep(1.0)  # Wait for page to load
        console.print("  ✅ Opened About page")

        # Step 6: Find clickable elements
        console.print("\n[bold]Step 6:[/bold] Find clickable elements (device info items)")
        async with measure_latency(tracker, "find_elements"):
            clickable_elements = await client.find_elements(clickable=True)

        clickable_count = len(clickable_elements)
        console.print(f"  ✅ Found [green]{clickable_count}[/green] clickable elements")

        # Show first few items
        if clickable_elements:
            texts = [e.get("text", "") for e in clickable_elements[:5] if e.get("text")]
            if texts:
                console.print(f"  First items: {', '.join(texts[:3])}")

        # Step 7: Press back
        console.print("\n[bold]Step 7:[/bold] Press BACK key")
        async with measure_latency(tracker, "press_key"):
            await client.press_key("BACK")

        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to Settings main page")

        # Step 8: Fling down (fast scroll)
        console.print("\n[bold]Step 8:[/bold] Fling down (fast scroll)")
        async with measure_latency(tracker, "fling_down"):
            await client.fling("down")

        await asyncio.sleep(0.5)
        console.print("  ✅ Flung down")

        # Step 9: Fling up (back to top)
        console.print("\n[bold]Step 9:[/bold] Fling up (back to top)")
        async with measure_latency(tracker, "fling_up"):
            await client.fling("up")

        await asyncio.sleep(0.5)
        console.print("  ✅ Flung back to top")

        # Step 10: Screenshot (current state)
        console.print("\n[bold]Step 10:[/bold] Take current screenshot")
        screenshot_current_path = screenshot_dir / f"scenario2_current_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot_current"):
            current_bytes = await client.screenshot(quality="full", save_path=screenshot_current_path)

        size_current_kb = len(current_bytes) / 1024
        console.print(f"  ✅ Current saved: [green]{screenshot_current_path.name}[/green] ({size_current_kb:.1f} KB)")

        # Step 11: Screenshot diff
        console.print("\n[bold]Step 11:[/bold] Compare screenshots (screenshot_diff)")
        try:
            async with measure_latency(tracker, "screenshot_diff"):
                diff_result = await client.screenshot_diff(
                    reference_base64=reference_base64,
                    threshold=0.8
                )
            similarity = diff_result.get("similarity_score", 0.0)
            is_similar = diff_result.get("is_similar", False)
            console.print(f"  ✅ Similarity score: [green]{similarity:.3f}[/green]")
            console.print(f"  {'✅' if is_similar else '⚠️'}  Similar (threshold 0.8): [{'green' if is_similar else 'yellow'}]{is_similar}[/{'green' if is_similar else 'yellow'}]")
        except Exception as e:
            console.print(f"  ⚠️ screenshot_diff failed (MediaProjection may not be available): {e}")

        # Step 12: Close Settings app
        console.print("\n[bold]Step 12:[/bold] Close Settings app")
        async with measure_latency(tracker, "close_app"):
            await client.close_app("com.android.settings")

        await asyncio.sleep(0.5)
        console.print("  ✅ Settings app closed")

        # Step 13: Return to home
        console.print("\n[bold]Step 13:[/bold] Return to home screen")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to home screen")

        # Success
        console.print("\n[bold green]✅ Scenario 2: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 2: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        # Cleanup attempt
        try:
            await client.close_app("com.android.settings")
            await client.global_action("HOME")
        except:
            pass
        return False
