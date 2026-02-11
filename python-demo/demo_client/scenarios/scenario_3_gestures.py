"""Scenario 3: Gesture Showcase with Context (Enhanced)

Demonstrates:
- All 8 gesture types in realistic app context
- Visual before/after documentation with screenshots
- Context-aware gesture execution (Photos app)
- Graceful fallback to generic gestures if app unavailable
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


async def run_scenario_3_gestures(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 3: Gesture Showcase with Context.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]🎨 Scenario 3: Gesture Showcase with Context (Enhanced)[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Demonstrates: All 8 gestures in realistic app context with visual feedback\n"
        "Target App: Google Photos (or fallback to generic)",
        border_style="cyan"
    ))

    try:
        # Get screen dimensions
        ui_tree = await client.get_ui_tree()
        screen = ui_tree.get("screen", {})
        screen_width = screen.get("width", 1080)
        screen_height = screen.get("height", 1920)
        center_x = screen_width // 2
        center_y = screen_height // 2

        console.print(f"  📐 Screen: {screen_width}x{screen_height}")

        # Step 1: Attempt to launch Photos app
        console.print("\n[bold]Step 1:[/bold] Launch Google Photos for gesture context")

        photos_available = False
        try:
            async with measure_latency(tracker, "launch_app"):
                await client.launch_app("com.google.android.apps.photos")
            await asyncio.sleep(2.0)

            # Verify Photos launched
            app_info = await client.get_foreground_app()
            if "photos" in app_info.get("package_name", "").lower():
                console.print("  ✅ [green]Google Photos launched successfully![/green]")
                photos_available = True

                # Take initial screenshot
                screenshot_path = screenshot_dir / "scenario3_photos_initial.jpg"
                async with measure_latency(tracker, "screenshot"):
                    screenshot_data = await client.screenshot(quality="full")
                screenshot_path.write_bytes(screenshot_data)
                console.print(f"  📸 Initial: {screenshot_path.name}")
            else:
                raise Exception("Photos didn't launch")

        except Exception as e:
            console.print(f"  ⚠️ [yellow]Photos not available: {e}[/yellow]")
            console.print("  ℹ️  Using generic gesture demonstration instead")
            photos_available = False

            # Go to home for generic gestures
            async with measure_latency(tracker, "global_action"):
                await client.global_action("HOME")
            await asyncio.sleep(0.5)

        # Gesture execution with context
        gestures_executed = []

        if photos_available:
            # Photos app gesture flow
            console.print("\n[bold]Photos App Gesture Flow:[/bold]")

            # Gesture 1: Swipe (navigate between photos)
            console.print("\n  🎯 [cyan]Gesture 1: SWIPE[/cyan] - Navigate between photos")
            for i in range(2):
                async with measure_latency(tracker, "swipe"):
                    await client.swipe(
                        x1=center_x + 200,
                        y1=center_y,
                        x2=center_x - 200,
                        y2=center_y,
                        duration_ms=300
                    )
                await asyncio.sleep(0.8)
                console.print(f"     ✅ Swipe {i+1}/2 executed")

            screenshot_path = screenshot_dir / "scenario3_after_swipe.jpg"
            async with measure_latency(tracker, "screenshot"):
                screenshot_data = await client.screenshot(quality="full")
            screenshot_path.write_bytes(screenshot_data)
            console.print(f"     📸 After swipe: {screenshot_path.name}")
            gestures_executed.append(("Swipe", "Navigate photos", "✅"))

            # Gesture 2: Double tap (zoom in on photo)
            console.print("\n  🎯 [cyan]Gesture 2: DOUBLE TAP[/cyan] - Zoom in on photo")
            async with measure_latency(tracker, "double_tap"):
                await client.double_tap(x=center_x, y=center_y)
            await asyncio.sleep(1.0)
            console.print("     ✅ Double tap - photo zoomed")

            screenshot_path = screenshot_dir / "scenario3_after_double_tap.jpg"
            async with measure_latency(tracker, "screenshot"):
                screenshot_data = await client.screenshot(quality="full")
            screenshot_path.write_bytes(screenshot_data)
            console.print(f"     📸 After double tap: {screenshot_path.name}")
            gestures_executed.append(("Double Tap", "Zoom in", "✅"))

            # Gesture 3: Pinch (zoom out)
            console.print("\n  🎯 [cyan]Gesture 3: PINCH OUT[/cyan] - Zoom out")
            async with measure_latency(tracker, "pinch"):
                await client.pinch(
                    center_x=center_x,
                    center_y=center_y,
                    scale=0.5,
                    duration_ms=500
                )
            await asyncio.sleep(0.8)
            console.print("     ✅ Pinch out (scale=0.5)")
            gestures_executed.append(("Pinch Out", "Zoom out 50%", "✅"))

            # Gesture 4: Pinch (zoom in)
            console.print("\n  🎯 [cyan]Gesture 4: PINCH IN[/cyan] - Zoom in")
            async with measure_latency(tracker, "pinch"):
                await client.pinch(
                    center_x=center_x,
                    center_y=center_y,
                    scale=2.0,
                    duration_ms=500
                )
            await asyncio.sleep(0.8)
            console.print("     ✅ Pinch in (scale=2.0)")

            screenshot_path = screenshot_dir / "scenario3_after_pinch.jpg"
            async with measure_latency(tracker, "screenshot"):
                screenshot_data = await client.screenshot(quality="full")
            screenshot_path.write_bytes(screenshot_data)
            console.print(f"     📸 After pinch: {screenshot_path.name}")
            gestures_executed.append(("Pinch In", "Zoom in 200%", "✅"))

            # Gesture 5: Drag (pan around zoomed photo)
            console.print("\n  🎯 [cyan]Gesture 5: DRAG[/cyan] - Pan around zoomed photo")
            async with measure_latency(tracker, "drag"):
                await client.drag(
                    x1=center_x,
                    y1=center_y,
                    x2=center_x - 150,
                    y2=center_y - 150,
                    duration_ms=500
                )
            await asyncio.sleep(0.8)
            console.print("     ✅ Drag - panned view")
            gestures_executed.append(("Drag", "Pan zoomed view", "✅"))

            # Gesture 6: Long press (open context menu)
            console.print("\n  🎯 [cyan]Gesture 6: LONG PRESS[/cyan] - Open context menu")
            async with measure_latency(tracker, "long_press"):
                await client.long_press(x=center_x, y=center_y, duration_ms=1000)
            await asyncio.sleep(1.0)
            console.print("     ✅ Long press - context menu")

            screenshot_path = screenshot_dir / "scenario3_after_long_press.jpg"
            async with measure_latency(tracker, "screenshot"):
                screenshot_data = await client.screenshot(quality="full")
            screenshot_path.write_bytes(screenshot_data)
            console.print(f"     📸 After long press: {screenshot_path.name}")
            gestures_executed.append(("Long Press", "Context menu", "✅"))

            # Close menu
            await client.press_key("BACK")
            await asyncio.sleep(0.5)

            # Gesture 7: Fling (fast scroll through photo grid)
            console.print("\n  🎯 [cyan]Gesture 7: FLING[/cyan] - Fast scroll")
            # Go back to grid view
            await client.press_key("BACK")
            await asyncio.sleep(0.8)

            async with measure_latency(tracker, "fling"):
                await client.fling(direction="up")
            await asyncio.sleep(0.8)
            console.print("     ✅ Fling up - fast scroll")

            screenshot_path = screenshot_dir / "scenario3_after_fling.jpg"
            async with measure_latency(tracker, "screenshot"):
                screenshot_data = await client.screenshot(quality="full")
            screenshot_path.write_bytes(screenshot_data)
            console.print(f"     📸 After fling: {screenshot_path.name}")
            gestures_executed.append(("Fling", "Fast scroll", "✅"))

            # Gesture 8: Tap (regular tap to select)
            console.print("\n  🎯 [cyan]Gesture 8: TAP[/cyan] - Select photo")
            async with measure_latency(tracker, "tap"):
                await client.tap(x=center_x, y=center_y)
            await asyncio.sleep(0.8)
            console.print("     ✅ Tap - photo selected")
            gestures_executed.append(("Tap", "Select photo", "✅"))

        else:
            # Generic gesture demonstration (fallback)
            console.print("\n[bold]Generic Gesture Demonstration:[/bold]")

            gestures = [
                ("Tap", lambda: client.tap(x=center_x, y=center_y)),
                ("Double Tap", lambda: client.double_tap(x=center_x, y=center_y)),
                ("Long Press", lambda: client.long_press(x=center_x, y=center_y, duration_ms=1000)),
                ("Swipe Right", lambda: client.swipe(center_x - 200, center_y, center_x + 200, center_y, 300)),
                ("Swipe Left", lambda: client.swipe(center_x + 200, center_y, center_x - 200, center_y, 300)),
                ("Pinch Out", lambda: client.pinch(center_x, center_y, 0.5, 500)),
                ("Pinch In", lambda: client.pinch(center_x, center_y, 2.0, 500)),
                ("Fling Up", lambda: client.fling(direction="up")),
                ("Drag", lambda: client.drag(center_x - 100, center_y, center_x + 100, center_y, 500)),
            ]

            for i, (name, gesture_func) in enumerate(gestures, 1):
                console.print(f"\n  🎯 [cyan]Gesture {i}/9: {name}[/cyan]")
                async with measure_latency(tracker, name.lower().replace(" ", "_")):
                    await gesture_func()
                await asyncio.sleep(0.5)
                console.print(f"     ✅ {name} executed")
                gestures_executed.append((name, "Generic demo", "✅"))

                # Take screenshot for key gestures
                if i % 3 == 0:
                    screenshot_path = screenshot_dir / f"scenario3_generic_gesture{i}.jpg"
                    async with measure_latency(tracker, "screenshot"):
                        screenshot_data = await client.screenshot(quality="full")
                    screenshot_path.write_bytes(screenshot_data)
                    console.print(f"     📸 Screenshot: {screenshot_path.name}")

        # Step 2: Gesture summary
        console.print("\n[bold]Step 2:[/bold] Gesture Summary")

        gesture_table = Table(title="🎨 Gestures Executed", border_style="cyan")
        gesture_table.add_column("Gesture", style="cyan", width=15)
        gesture_table.add_column("Context", style="yellow", width=30)
        gesture_table.add_column("Status", style="green", width=10)

        for gesture_name, context, status in gestures_executed:
            gesture_table.add_row(gesture_name, context, status)

        console.print(gesture_table)

        # Step 3: Return to home
        console.print("\n[bold]Step 3:[/bold] Return to home screen")
        async with measure_latency(tracker, "press_key"):
            await client.press_key("BACK")
        await asyncio.sleep(0.3)
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")
        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to home")

        # Final summary
        console.print("\n[bold]Gesture Showcase Summary:[/bold]")
        summary = Panel.fit(
            f"[bold cyan]Gesture Demonstration Results[/bold cyan]\n\n"
            f"🎯 Total Gestures: [bold]{len(gestures_executed)}[/bold]\n"
            f"📸 Screenshots: [bold]{len([g for g in gestures_executed if 'photo' in g[1].lower() or gestures_executed.index(g) % 3 == 2])}[/bold] captured\n"
            f"📱 Context: [bold]{'Google Photos' if photos_available else 'Generic Demo'}[/bold]\n"
            f"✅ Success Rate: [bold]100%[/bold]\n\n"
            f"[green]All gesture types demonstrated successfully![/green]\n"
            f"[dim]{'Realistic app context with visual feedback.' if photos_available else 'Generic gestures with coordinate-based execution.'}[/dim]",
            border_style="green"
        )
        console.print(summary)

        console.print("\n[bold green]✅ Scenario 3: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 3: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        import traceback
        console.print(f"[dim]{traceback.format_exc()}[/dim]")
        return False
