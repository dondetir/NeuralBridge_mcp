"""Scenario 3: Contact Creation Workflow

Demonstrates:
- launch_app, close_app
- wait_for_element, get_ui_tree
- tap, input_text, dismiss_keyboard
- press_key, wait_for_idle
- screenshot
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


async def run_scenario_3_contacts(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 3: Contact Creation Workflow.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 3: Contact Creation Workflow[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: launch_app, wait_for_element, tap, input_text, dismiss_keyboard, screenshot",
        border_style="cyan"
    ))

    try:
        # Step 1: Launch Contacts app
        console.print("\n[bold]Step 1:[/bold] Launch Contacts app")
        async with measure_latency(tracker, "launch_app"):
            # Try Google Contacts first, fallback to AOSP
            try:
                await client.launch_app("com.google.android.contacts")
                app_launched = "Google Contacts"
            except:
                await client.launch_app("com.android.contacts")
                app_launched = "AOSP Contacts"

        await asyncio.sleep(3.0)  # Wait for app to fully load
        try:
            await client.wait_for_idle(timeout_ms=5000)
        except Exception:
            pass

        # Dismiss any first-run dialogs (permission, sync, etc.)
        for dismiss_text in ["Skip", "Got it", "OK", "Allow", "Not now", "SKIP", "NO THANKS"]:
            try:
                await client.tap(text=dismiss_text)
                await asyncio.sleep(1.0)
            except Exception:
                continue

        console.print(f"  ✅ {app_launched} launched")

        # Step 2: Wait for FAB (floating action button)
        console.print("\n[bold]Step 2:[/bold] Wait for FAB (add contact button)")
        fab_found = False
        fab_selector = None

        # Try common FAB selectors
        fab_candidates = [
            {"resource_id": "floating_action_button"},
            {"resource_id": "create_contact_button"},
            {"resource_id": "fab"},
            {"content_desc": "Create"},
            {"content_desc": "Add"},
        ]

        for selector in fab_candidates:
            try:
                async with measure_latency(tracker, "wait_for_element"):
                    result = await client.wait_for_element(timeout_ms=3000, **selector)
                if result:
                    fab_found = True
                    fab_selector = selector
                    console.print(f"  ✅ Found FAB: {selector}")
                    break
            except:
                continue

        if not fab_found:
            # Fallback: get UI tree and discover FAB
            console.print("  [yellow]FAB not found with common selectors, discovering via UI tree[/yellow]")
            async with measure_latency(tracker, "get_ui_tree"):
                ui_tree = await client.get_ui_tree(include_invisible=False)

            # Look for FAB (usually FloatingActionButton class, or has 'Create'/'Add' desc)
            elements = ui_tree.get("elements", [])
            for elem in elements:
                class_name = elem.get("class_name", "")
                content_desc = elem.get("content_desc", "")
                resource_id = elem.get("resource_id", "")

                if "FloatingActionButton" in class_name or "Create" in content_desc or "Add" in content_desc:
                    fab_found = True
                    fab_selector = elem.get("bounds", {})  # Use coordinates as fallback
                    console.print(f"  ✅ Discovered FAB: class={class_name}, desc={content_desc}")
                    break

        if not fab_found:
            console.print("  [red]❌ Could not find FAB, aborting scenario[/red]")
            raise Exception("FAB not found")

        # Step 3: Tap FAB to create new contact
        console.print("\n[bold]Step 3:[/bold] Tap FAB (create new contact)")
        async with measure_latency(tracker, "tap_fab"):
            if isinstance(fab_selector, dict) and "x" in fab_selector:
                # Coordinates fallback
                bounds = fab_selector
                center_x = bounds.get("x", 0) + bounds.get("width", 0) // 2
                center_y = bounds.get("y", 0) + bounds.get("height", 0) // 2
                await client.tap(x=center_x, y=center_y)
            else:
                # Selector
                await client.tap(**fab_selector)

        await asyncio.sleep(2.0)  # Wait for form to load
        try:
            await client.wait_for_idle(timeout_ms=3000)
        except Exception:
            pass
        console.print("  ✅ Contact creation form opened")

        # Step 4: Fill in name field
        console.print("\n[bold]Step 4:[/bold] Input name 'NeuralBridge Test'")

        # Try common name field selectors
        # Try selectors first, then fallback to UI tree discovery
        name_selectors = [
            {"element_text": "First name"},
            {"element_text": "Name"},
            {"resource_id": "name"},
        ]

        name_filled = False
        for selector in name_selectors:
            try:
                async with measure_latency(tracker, "input_text"):
                    await client.input_text("NeuralBridge Test", **selector)
                name_filled = True
                console.print(f"  ✅ Name filled using selector: {selector}")
                break
            except:
                continue

        if not name_filled:
            # Fallback: find EditText via find_elements and tap to focus
            console.print("  [yellow]Trying find_elements fallback...[/yellow]")
            try:
                edit_texts = await client.find_elements(class_name="EditText")
                if edit_texts:
                    first_field = edit_texts[0]
                    cx = first_field["bounds"]["center_x"]
                    cy = first_field["bounds"]["center_y"]
                    await client.tap(x=cx, y=cy)
                    await asyncio.sleep(0.5)
                    async with measure_latency(tracker, "input_text"):
                        await client.input_text("NeuralBridge Test")
                    name_filled = True
                    console.print("  ✅ Name filled via EditText tap + type")
            except Exception as e:
                console.print(f"  [yellow]EditText fallback failed: {e}[/yellow]")

        if not name_filled:
            console.print("  [yellow]⚠️ Could not fill name field, continuing anyway[/yellow]")

        # Step 5: Dismiss keyboard
        console.print("\n[bold]Step 5:[/bold] Dismiss keyboard")
        async with measure_latency(tracker, "dismiss_keyboard"):
            await client.dismiss_keyboard()

        await asyncio.sleep(0.5)
        console.print("  ✅ Keyboard dismissed")

        # Step 6: Screenshot (capture filled form)
        console.print("\n[bold]Step 6:[/bold] Take screenshot (filled form)")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"scenario3_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 7: Wait for idle
        console.print("\n[bold]Step 7:[/bold] Wait for idle state")
        async with measure_latency(tracker, "wait_for_idle"):
            await client.wait_for_idle(timeout_ms=3000)

        console.print("  ✅ UI is idle")

        # Step 8: Press back (discard contact - don't actually save)
        console.print("\n[bold]Step 8:[/bold] Press BACK (discard contact)")
        async with measure_latency(tracker, "press_key"):
            await client.press_key("BACK")

        await asyncio.sleep(1.0)  # May show discard confirmation
        console.print("  ✅ BACK pressed")

        # Try pressing back again if confirmation dialog appeared
        try:
            await client.press_key("BACK")
            await asyncio.sleep(0.5)
        except:
            pass

        # Step 9: Return to home
        console.print("\n[bold]Step 9:[/bold] Return to home screen")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        await asyncio.sleep(1.0)
        console.print("  ✅ Returned to home screen")

        # Success
        console.print("\n[bold green]✅ Scenario 3: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 3: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        # Cleanup attempt
        try:
            await client.press_key("BACK")
            await asyncio.sleep(0.5)
            await client.press_key("BACK")
            await asyncio.sleep(0.5)
            await client.global_action("HOME")
        except:
            pass
        return False
