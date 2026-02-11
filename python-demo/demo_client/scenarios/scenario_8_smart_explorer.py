"""Scenario 8: Smart App Explorer (AI Intelligence Tier)

Demonstrates:
- Autonomous app discovery and exploration
- Intelligent element selection and navigation
- Screen mapping and breadcrumb tracking
- Adaptive behavior (backtracking when stuck)
- AI-native reasoning about UI structure
"""

import asyncio
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING, List, Dict, Set, Tuple

from rich.panel import Panel
from rich.table import Table

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_8_smart_explorer(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 8: Smart App Explorer.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]🧭 Scenario 8: Smart App Explorer (AI Intelligence)[/bold cyan]\n"
        "Duration: ~3 minutes\n"
        "Demonstrates: Autonomous discovery, intelligent navigation, screen mapping\n"
        "Target App: Clock (com.google.android.deskclock)",
        border_style="cyan"
    ))

    try:
        # Track exploration state
        visited_screens: Set[str] = set()
        screen_history: List[Tuple[int, str, int, List[str]]] = []  # (num, screenshot, elements, key_elements)
        navigation_path: List[str] = ["Home"]
        screen_num = 0
        max_screens = 4  # Explore up to 4 screens

        # Step 1: Launch target app
        console.print("\n[bold]Step 1:[/bold] Launch Clock app for exploration")
        async with measure_latency(tracker, "launch_app"):
            await client.launch_app("com.google.android.deskclock")

        await asyncio.sleep(1.5)  # Wait for app to fully load
        console.print("  ✅ Clock app launched")

        # Step 2: Initial reconnaissance
        console.print("\n[bold]Step 2:[/bold] Initial reconnaissance - analyze app structure")

        # Take initial screenshot
        screenshot_path = screenshot_dir / f"scenario8_explorer_initial.jpg"
        async with measure_latency(tracker, "screenshot"):
            screenshot_data = await client.screenshot(quality="full")
        screenshot_path.write_bytes(screenshot_data)
        console.print(f"  📸 Screenshot saved: {screenshot_path.name}")

        # Get UI tree
        async with measure_latency(tracker, "get_ui_tree"):
            ui_tree = await client.get_ui_tree(include_invisible=False)

        total_elements = len(ui_tree.get("elements", []))
        screen_bounds = ui_tree.get("screen", {})
        console.print(f"  📊 Total elements: {total_elements}")
        console.print(f"  📐 Screen: {screen_bounds.get('width')}x{screen_bounds.get('height')}")

        # Generate screen signature (for loop detection)
        screen_sig = _generate_screen_signature(ui_tree)
        visited_screens.add(screen_sig)

        # Extract key elements
        key_elements = _extract_key_elements(ui_tree)
        screen_history.append((screen_num, screenshot_path.name, total_elements, key_elements))
        console.print(f"  🔍 Discovered {len(key_elements)} interesting elements")

        # Step 3: Discovery phase - explore app intelligently
        console.print("\n[bold]Step 3:[/bold] Discovery phase - intelligent exploration")

        for explore_iteration in range(max_screens - 1):  # Already counted initial screen
            # Find clickable, unvisited elements
            async with measure_latency(tracker, "find_elements"):
                clickable = await client.find_elements(clickable=True, find_all=True)

            if not clickable:
                console.print("  ⚠️ [yellow]No clickable elements found, exploration complete[/yellow]")
                break

            # Filter interesting elements (has text, not system UI, not already explored)
            interesting = _filter_interesting_elements(clickable, visited_screens)

            if not interesting:
                console.print("  ⚠️ [yellow]No new interesting elements, backtracking...[/yellow]")
                # Navigate back
                async with measure_latency(tracker, "press_key"):
                    await client.press_key("BACK")
                await asyncio.sleep(0.8)
                navigation_path.pop() if len(navigation_path) > 1 else None
                console.print(f"  ⬅️  Navigated BACK to: {navigation_path[-1]}")
                continue

            # Select most promising element
            selected = _select_best_element(interesting)
            element_desc = selected.get("text") or selected.get("content_desc") or selected.get("resource_id", "Unknown")
            element_desc = element_desc.split("/")[-1][:30]  # Shorten for display

            console.print(f"\n  🎯 [cyan]Exploring element:[/cyan] \"{element_desc}\"")
            console.print(f"     Reason: {_explain_selection(selected)}")

            # Tap the element
            try:
                if selected.get("bounds"):
                    bounds = selected["bounds"]
                    center_x = (bounds["left"] + bounds["right"]) // 2
                    center_y = (bounds["top"] + bounds["bottom"]) // 2
                    async with measure_latency(tracker, "tap"):
                        await client.tap(x=center_x, y=center_y)
                elif selected.get("text"):
                    async with measure_latency(tracker, "tap"):
                        await client.tap(text=selected["text"])
                else:
                    console.print("  ⚠️ [yellow]Cannot tap element, skipping[/yellow]")
                    continue

                await asyncio.sleep(0.8)  # Wait for navigation

                # Check if screen changed
                async with measure_latency(tracker, "get_ui_tree"):
                    new_tree = await client.get_ui_tree(include_invisible=False)

                new_sig = _generate_screen_signature(new_tree)

                if new_sig == screen_sig:
                    console.print("  ℹ️  Screen didn't change (dialog/toast), continuing...")
                    continue

                if new_sig in visited_screens:
                    console.print("  ↩️  Already visited this screen, backtracking...")
                    async with measure_latency(tracker, "press_key"):
                        await client.press_key("BACK")
                    await asyncio.sleep(0.8)
                    continue

                # New screen discovered!
                screen_num += 1
                visited_screens.add(new_sig)
                screen_sig = new_sig
                navigation_path.append(element_desc)

                # Capture new screen
                screenshot_path = screenshot_dir / f"scenario8_explorer_screen{screen_num}.jpg"
                async with measure_latency(tracker, "screenshot"):
                    screenshot_data = await client.screenshot(quality="full")
                screenshot_path.write_bytes(screenshot_data)

                new_elements = len(new_tree.get("elements", []))
                key_elements = _extract_key_elements(new_tree)
                screen_history.append((screen_num, screenshot_path.name, new_elements, key_elements))

                console.print(f"  ✅ [green]New screen discovered![/green] Elements: {new_elements}")
                console.print(f"  📍 Path: {' → '.join(navigation_path)}")
                console.print(f"  📸 Screenshot: {screenshot_path.name}")

            except Exception as e:
                console.print(f"  ⚠️ [yellow]Error tapping element: {e}[/yellow]")
                continue

        # Step 4: Build exploration map
        console.print("\n[bold]Step 4:[/bold] Exploration summary")

        # Create exploration map table
        map_table = Table(title="📍 Discovered Screens", border_style="cyan")
        map_table.add_column("Screen", style="cyan", width=8)
        map_table.add_column("Screenshot", style="white", width=35)
        map_table.add_column("Elements", style="yellow", width=10)
        map_table.add_column("Key Features", style="green", width=40)

        for screen_info in screen_history:
            num, screenshot, elem_count, keys = screen_info
            key_str = ", ".join(keys[:3])  # Show first 3 key elements
            if len(keys) > 3:
                key_str += f" (+{len(keys)-3} more)"
            map_table.add_row(
                f"Screen {num}",
                screenshot,
                str(elem_count),
                key_str
            )

        console.print(map_table)

        # Step 5: Final summary report
        console.print("\n[bold]Step 5:[/bold] Exploration Report")

        summary = Panel.fit(
            f"[bold cyan]Smart Explorer Results[/bold cyan]\n\n"
            f"📊 Screens Explored: [bold]{len(screen_history)}[/bold]\n"
            f"🔍 Total Elements Discovered: [bold]{sum(s[2] for s in screen_history)}[/bold]\n"
            f"🎯 Interactive Elements: [bold]{sum(len(s[3]) for s in screen_history)}[/bold]\n"
            f"📏 Navigation Depth: [bold]{len(navigation_path)}[/bold] levels\n"
            f"📍 Final Path: {' → '.join(navigation_path)}\n\n"
            f"[green]✅ Autonomous exploration complete![/green]",
            border_style="green"
        )
        console.print(summary)

        # Return to home
        console.print("\n[bold]Step 6:[/bold] Return to home screen")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")
        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to home")

        console.print("\n[bold green]✅ Scenario 8: PASSED[/bold green]")
        console.print("[dim]This scenario demonstrated AI-native autonomous discovery - the killer differentiator vs traditional automation.[/dim]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 8: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        import traceback
        console.print(f"[dim]{traceback.format_exc()}[/dim]")
        return False


def _generate_screen_signature(ui_tree: Dict) -> str:
    """Generate a unique signature for a screen to detect revisits."""
    elements = ui_tree.get("elements", [])
    # Use element count + first 5 resource IDs as signature
    sig_parts = [str(len(elements))]
    for elem in elements[:5]:
        if elem.get("resource_id"):
            sig_parts.append(elem["resource_id"])
    return "|".join(sig_parts)


def _extract_key_elements(ui_tree: Dict) -> List[str]:
    """Extract interesting/key UI elements from tree."""
    elements = ui_tree.get("elements", [])
    key_elements = []

    for elem in elements:
        if not elem.get("clickable"):
            continue

        # Prioritize elements with text
        text = elem.get("text") or elem.get("content_desc")
        if text and len(text) > 0 and len(text) < 50:  # Reasonable length
            # Skip generic system UI
            if text.lower() not in ["ok", "cancel", "close", ""]:
                key_elements.append(text)

    return key_elements[:10]  # Top 10


def _filter_interesting_elements(elements: List[Dict], visited_screens: Set[str]) -> List[Dict]:
    """Filter elements to find interesting ones for exploration."""
    interesting = []

    for elem in elements:
        # Must have text or content description
        text = elem.get("text") or elem.get("content_desc")
        if not text or len(text) == 0:
            continue

        # Skip system UI elements
        if text.lower() in ["ok", "cancel", "close", "back", "home"]:
            continue

        # Skip navigation bar elements
        resource_id = elem.get("resource_id", "")
        if "navigationBarBackground" in resource_id or "statusBarBackground" in resource_id:
            continue

        # Prefer buttons and tabs
        class_name = elem.get("class_name", "")
        if "Button" in class_name or "Tab" in class_name or "MenuItem" in class_name:
            interesting.append(elem)
        elif elem.get("clickable"):
            interesting.append(elem)

    return interesting


def _select_best_element(elements: List[Dict]) -> Dict:
    """Select the most promising element to explore."""
    # Scoring: prefer buttons with text, then tabs, then generic clickables
    scored = []

    for elem in elements:
        score = 0
        class_name = elem.get("class_name", "")
        text = elem.get("text") or elem.get("content_desc") or ""

        # Class name scoring
        if "Button" in class_name:
            score += 3
        elif "Tab" in class_name:
            score += 2
        elif "MenuItem" in class_name:
            score += 2

        # Text scoring
        if len(text) > 0:
            score += 1

        # Position scoring (prefer top elements for first exploration)
        if elem.get("bounds"):
            y_pos = elem["bounds"]["top"]
            if y_pos < 500:  # Top of screen
                score += 1

        scored.append((score, elem))

    # Sort by score descending
    scored.sort(key=lambda x: x[0], reverse=True)

    return scored[0][1] if scored else elements[0]


def _explain_selection(element: Dict) -> str:
    """Explain why this element was selected for exploration."""
    reasons = []

    class_name = element.get("class_name", "")
    if "Button" in class_name:
        reasons.append("is a button")
    elif "Tab" in class_name:
        reasons.append("is a tab")

    text = element.get("text") or element.get("content_desc")
    if text:
        reasons.append(f"has descriptive text")

    if element.get("bounds", {}).get("top", 1000) < 500:
        reasons.append("positioned at top")

    return ", ".join(reasons) if reasons else "interactive element"
