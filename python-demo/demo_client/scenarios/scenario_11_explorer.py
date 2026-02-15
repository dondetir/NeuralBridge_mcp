"""Scenario 11: AI Explorer (Autonomous App Exploration)

Demonstrates:
- Autonomous navigation
- get_screen_context
- find_elements
- screenshot
- wait_for_idle
- get_ui_tree
"""

import asyncio
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING, List, Dict, Any

from rich.panel import Panel
from rich.table import Table

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency, format_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_11_explorer(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 11: AI Explorer (Autonomous App Exploration).

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 11: AI Explorer (Autonomous Navigation)[/bold cyan]\n"
        "Duration: ~3 minutes\n"
        "Tools: get_screen_context, find_elements, screenshot, tap, wait_for_idle, get_ui_tree",
        border_style="cyan"
    ))

    try:
        # Step 1: Launch Settings app
        console.print("\n[bold]Step 1:[/bold] Launch Settings app for exploration")
        async with measure_latency(tracker, "launch_app"):
            await client.launch_app("com.android.settings")

        await asyncio.sleep(1.0)
        console.print("  ✅ Settings app launched")

        # Initialize exploration tracking
        screens_visited: List[Dict[str, Any]] = []
        max_depth = 3
        timestamp_base = datetime.now().strftime("%Y%m%d_%H%M%S")

        # Step 2: Exploration loop
        console.print(f"\n[bold]Step 2:[/bold] Begin autonomous exploration (max depth: {max_depth})")

        for screen_num in range(1, max_depth + 1):
            console.print(f"\n[bold cyan]--- Exploring Screen {screen_num} ---[/bold cyan]")

            # 2a: Get screen context
            console.print(f"  [bold]2a:[/bold] Understanding screen {screen_num}")
            try:
                async with measure_latency(tracker, f"get_screen_context_{screen_num}"):
                    context = await client.get_screen_context(include_all_elements=False)
                foreground_app = context.get("foreground_app", {})
                context_elements = context.get("elements", [])
                console.print(f"    Current: [green]{foreground_app.get('package_name', 'unknown')}[/green]")
                console.print(f"    Elements (summary): [green]{len(context_elements)}[/green]")
            except Exception as e:
                console.print(f"    ⚠️ get_screen_context failed, using get_foreground_app: {e}")
                app_info = await client.get_foreground_app()
                console.print(f"    Current: [green]{app_info.get('package_name', 'unknown')}[/green]")

            # 2b: Find interactive elements
            console.print(f"  [bold]2b:[/bold] Finding interactive elements")
            async with measure_latency(tracker, f"find_elements_{screen_num}"):
                clickable_elements = await client.find_elements(clickable=True)

            console.print(f"    Found [green]{len(clickable_elements)} interactive elements[/green]")

            # 2c: Screenshot
            console.print(f"  [bold]2c:[/bold] Documenting screen {screen_num}")
            screenshot_path = screenshot_dir / f"scenario11_screen{screen_num}_{timestamp_base}.jpg"

            async with measure_latency(tracker, f"screenshot_{screen_num}"):
                screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

            size_kb = len(screenshot_bytes) / 1024
            console.print(f"    Screenshot: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

            # 2d: Display elements found
            console.print(f"  [bold]2d:[/bold] Screen {screen_num}: Found {len(clickable_elements)} interactive elements")

            # 2e: Pick an element to tap (prefer elements with text, skip "Back")
            console.print(f"  [bold]2e:[/bold] Selecting element to explore")
            target_element = None
            target_text = ""

            for elem in clickable_elements:
                elem_text = elem.get("text", "").strip()
                if elem_text and "back" not in elem_text.lower():
                    target_element = elem
                    target_text = elem_text
                    break

            if not target_element and clickable_elements:
                # Fallback: use first clickable element
                target_element = clickable_elements[0]
                target_text = target_element.get("text", "") or target_element.get("content_desc", "") or "unnamed element"

            if target_element:
                console.print(f"    Selected: [cyan]{target_text}[/cyan]")

                # 2f: Tap chosen element
                console.print(f"  [bold]2f:[/bold] Tapping element")
                try:
                    async with measure_latency(tracker, f"tap_{screen_num}"):
                        if target_element.get("text"):
                            await client.tap(text=target_element.get("text"))
                        elif target_element.get("element_id"):
                            await client.tap(element_id=target_element.get("element_id"))
                        else:
                            # Fallback to coordinates
                            bounds = target_element.get("bounds", {})
                            x = bounds.get("centerX", 720)
                            y = bounds.get("centerY", 1480)
                            await client.tap(x=x, y=y)

                    await asyncio.sleep(1.0)
                    console.print("    ✅ Tap successful")
                except Exception as tap_error:
                    console.print(f"    [yellow]Tap failed: {tap_error}[/yellow]")
                    # Continue exploration anyway

                # 2g: Wait for UI to settle
                console.print(f"  [bold]2g:[/bold] Waiting for UI to settle")
                try:
                    async with measure_latency(tracker, f"wait_for_idle_{screen_num}"):
                        await client.wait_for_idle(timeout_ms=2000)
                    console.print("    ✅ UI settled")
                except Exception:
                    console.print("    [yellow]Idle timeout (UI may still be animating)[/yellow]")

                # 2h: Deep UI analysis
                console.print(f"  [bold]2h:[/bold] Deep UI tree analysis")
                async with measure_latency(tracker, f"get_ui_tree_{screen_num}"):
                    ui_tree = await client.get_ui_tree(include_invisible=False, max_depth=3)

                node_count = ui_tree.get("total_nodes", 0)
                console.print(f"    UI tree nodes: [green]{node_count}[/green]")

                # 2i: Record screen info
                screens_visited.append({
                    "screen_num": screen_num,
                    "elements_found": len(clickable_elements),
                    "element_tapped": target_text,
                    "ui_nodes": node_count
                })
            else:
                console.print("    [yellow]No tappable elements found (stopping exploration)[/yellow]")
                break

        # Step 3: Backtrack
        console.print(f"\n[bold]Step 3:[/bold] Backtracking (pressing BACK {len(screens_visited)}x)")
        for i in range(len(screens_visited)):
            async with measure_latency(tracker, f"press_back_{i+1}"):
                await client.press_key("BACK")
            await asyncio.sleep(0.3)

        console.print(f"  ✅ Backtracked {len(screens_visited)} screens")

        # Step 4: Display exploration summary
        console.print("\n[bold]Step 4:[/bold] Exploration Summary")

        summary_table = Table(title="Screens Explored", show_header=True, header_style="bold magenta")
        summary_table.add_column("Screen", style="cyan", justify="center")
        summary_table.add_column("Elements Found", style="green", justify="right")
        summary_table.add_column("Element Tapped", style="yellow")
        summary_table.add_column("UI Nodes", style="blue", justify="right")

        for screen in screens_visited:
            summary_table.add_row(
                str(screen["screen_num"]),
                str(screen["elements_found"]),
                screen["element_tapped"][:40],  # Truncate long text
                str(screen["ui_nodes"])
            )

        console.print(summary_table)

        # Step 5: Clean up
        console.print("\n[bold]Step 5:[/bold] Clean up")
        async with measure_latency(tracker, "close_app"):
            await client.close_app("com.android.settings")

        console.print("  ✅ Closed Settings app")

        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to home screen")

        # Display final summary
        console.print("\n[bold]AI Explorer Results:[/bold]")
        console.print(f"  Screens explored: [green]{len(screens_visited)}[/green]")
        total_elements = sum(s["elements_found"] for s in screens_visited)
        console.print(f"  Total elements discovered: [green]{total_elements}[/green]")
        console.print(f"  [cyan]NeuralBridge can autonomously explore and map Android apps[/cyan]")

        # Success
        console.print("\n[bold green]✅ Scenario 11: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 11: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
