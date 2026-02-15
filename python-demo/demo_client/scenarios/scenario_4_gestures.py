"""Scenario 4: Gallery Gesture Playground

Demonstrates:
- launch_app, close_app
- tap, swipe, double_tap, pinch
- long_press, fling, drag
- press_key, screenshot
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


async def run_scenario_4_gestures(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 4: Gallery Gesture Playground.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 4: Gallery Gesture Playground[/bold cyan]\n"
        "Duration: ~3 minutes\n"
        "Tools: tap, swipe, double_tap, pinch, long_press, fling, drag",
        border_style="cyan"
    ))

    try:
        # Get device info for coordinates (assume 1440x2960 for S9+, adjust if needed)
        console.print("\n[bold]Preparation:[/bold] Get device dimensions")
        async with measure_latency(tracker, "get_device_info"):
            device_info = await client.get_device_info()

        screen_width = device_info.get("screen_width", 1080)
        screen_height = device_info.get("screen_height", 1920)
        center_x = screen_width // 2
        center_y = screen_height // 2

        console.print(f"  ✅ Screen: {screen_width}x{screen_height}, Center: ({center_x}, {center_y})")

        # Step 1: Launch Gallery app
        console.print("\n[bold]Step 1:[/bold] Launch Gallery app")
        async with measure_latency(tracker, "launch_app"):
            # Try Samsung Gallery first, fallback to AOSP/Google Photos
            try:
                await client.launch_app("com.sec.android.gallery3d")
                app_launched = "Samsung Gallery"
            except:
                try:
                    await client.launch_app("com.google.android.apps.photos")
                    app_launched = "Google Photos"
                except:
                    await client.launch_app("com.android.gallery3d")
                    app_launched = "AOSP Gallery"

        await asyncio.sleep(1.5)  # Wait for app to load
        console.print(f"  ✅ {app_launched} launched")

        # Step 2: Tap first photo (use coordinates - grid layout varies)
        console.print("\n[bold]Step 2:[/bold] Tap first photo (open full view)")
        first_photo_x = screen_width // 4  # Assume photo is in upper-left quadrant
        first_photo_y = screen_height // 4

        async with measure_latency(tracker, "tap"):
            await client.tap(x=first_photo_x, y=first_photo_y)

        await asyncio.sleep(1.0)  # Wait for photo to open
        console.print(f"  ✅ Tapped at ({first_photo_x}, {first_photo_y})")

        # Step 3: Swipe to next photo
        console.print("\n[bold]Step 3:[/bold] Swipe left (next photo)")
        async with measure_latency(tracker, "swipe"):
            await client.swipe(
                int(screen_width * 0.8),
                center_y,
                int(screen_width * 0.2),
                center_y,
                duration_ms=300
            )

        await asyncio.sleep(0.5)
        console.print("  ✅ Swiped to next photo")

        # Step 4: Double tap (zoom toggle)
        console.print("\n[bold]Step 4:[/bold] Double tap (zoom toggle)")
        async with measure_latency(tracker, "double_tap"):
            await client.double_tap(x=center_x, y=center_y)

        await asyncio.sleep(0.5)
        console.print("  ✅ Double tapped (zoom toggled)")

        # Step 5: Pinch zoom in
        console.print("\n[bold]Step 5:[/bold] Pinch zoom in (scale 2.0)")
        async with measure_latency(tracker, "pinch_in"):
            await client.pinch(center_x=center_x, center_y=center_y, scale=2.0)

        await asyncio.sleep(0.5)
        console.print("  ✅ Pinched to zoom in")

        # Step 6: Pinch zoom out
        console.print("\n[bold]Step 6:[/bold] Pinch zoom out (scale 0.5)")
        async with measure_latency(tracker, "pinch_out"):
            await client.pinch(center_x=center_x, center_y=center_y, scale=0.5)

        await asyncio.sleep(0.5)
        console.print("  ✅ Pinched to zoom out")

        # Step 7: Press back (return to grid)
        console.print("\n[bold]Step 7:[/bold] Press BACK (return to grid)")
        async with measure_latency(tracker, "press_key"):
            await client.press_key("BACK")

        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to photo grid")

        # Step 8: Long press (trigger selection mode)
        console.print("\n[bold]Step 8:[/bold] Long press (selection mode)")
        async with measure_latency(tracker, "long_press"):
            await client.long_press(x=first_photo_x, y=first_photo_y)

        await asyncio.sleep(0.5)
        console.print("  ✅ Long pressed (selection mode activated)")

        # Step 9: Press back (exit selection)
        console.print("\n[bold]Step 9:[/bold] Press BACK (exit selection)")
        async with measure_latency(tracker, "press_key"):
            await client.press_key("BACK")

        await asyncio.sleep(0.5)
        console.print("  ✅ Exited selection mode")

        # Step 10: Fling up (fast scroll grid)
        console.print("\n[bold]Step 10:[/bold] Fling up (fast scroll gallery)")
        async with measure_latency(tracker, "fling"):
            await client.fling("up")

        await asyncio.sleep(0.5)
        console.print("  ✅ Flung up (fast scroll)")

        # Step 11: Close Gallery app
        console.print("\n[bold]Step 11:[/bold] Close Gallery app")
        async with measure_latency(tracker, "close_app"):
            try:
                await client.close_app("com.sec.android.gallery3d")
            except:
                try:
                    await client.close_app("com.google.android.apps.photos")
                except:
                    await client.close_app("com.android.gallery3d")

        await asyncio.sleep(0.5)
        console.print("  ✅ Gallery app closed")

        # Step 12: Return to home
        console.print("\n[bold]Step 12:[/bold] Return to home screen")
        async with measure_latency(tracker, "global_action"):
            await client.global_action("HOME")

        await asyncio.sleep(1.0)
        console.print("  ✅ Returned to home screen")

        # Step 13: Drag demo (move home screen icon)
        console.print("\n[bold]Step 13:[/bold] Drag demo (move home screen icon)")
        # Pick a position likely to have an icon (upper-left quadrant)
        icon_x = screen_width // 4
        icon_y = screen_height // 3
        target_x = int(screen_width * 0.6)
        target_y = int(screen_height * 0.4)

        async with measure_latency(tracker, "drag"):
            await client.drag(
                icon_x,
                icon_y,
                target_x,
                target_y,
                duration_ms=500
            )

        await asyncio.sleep(0.5)
        console.print(f"  ✅ Dragged from ({icon_x}, {icon_y}) to ({target_x}, {target_y})")

        # Step 14: Screenshot (capture result)
        console.print("\n[bold]Step 14:[/bold] Take screenshot (final state)")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = screenshot_dir / f"scenario4_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_path.name}[/green] ({size_kb:.1f} KB)")

        # Success
        console.print("\n[bold green]✅ Scenario 4: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 4: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        # Cleanup attempt
        try:
            await client.press_key("BACK")
            await asyncio.sleep(0.5)
            await client.global_action("HOME")
        except:
            pass
        return False
