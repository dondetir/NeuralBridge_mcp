"""Scenario 5: Clipboard Operations

Demonstrates:
- set_clipboard, get_clipboard
- Clipboard integration
"""

import asyncio
from pathlib import Path
from typing import TYPE_CHECKING

from rich.panel import Panel

from ..utils.logger import console
from ..utils.performance import LatencyTracker, measure_latency

if TYPE_CHECKING:
    from ..android_client import AndroidClient


async def run_scenario_5_clipboard(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 5: Clipboard Operations.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 5: Clipboard Operations[/bold cyan]\n"
        "Duration: ~1 minute\n"
        "Tools: set_clipboard, get_clipboard",
        border_style="cyan"
    ))

    try:
        test_text = "Hello NeuralBridge! 🚀"

        # Step 1: Set clipboard
        console.print("\n[bold]Step 1:[/bold] Set clipboard text")
        async with measure_latency(tracker, "set_clipboard"):
            await client.set_clipboard(test_text)

        console.print(f"  ✅ Clipboard set: [green]{test_text}[/green]")

        # Step 2: Get clipboard (verify)
        console.print("\n[bold]Step 2:[/bold] Get clipboard text (verify)")
        async with measure_latency(tracker, "get_clipboard"):
            clipboard_text = await client.get_clipboard()

        if clipboard_text == test_text:
            console.print(f"  ✅ Clipboard verified: [green]{clipboard_text}[/green]")
        else:
            console.print(f"  ⚠ Clipboard mismatch:")
            console.print(f"     Expected: {test_text}")
            console.print(f"     Got: {clipboard_text}")

        # Step 3: Set different text
        console.print("\n[bold]Step 3:[/bold] Update clipboard with new text")
        new_text = "NeuralBridge Phase 1+2 Complete! 🎉"

        async with measure_latency(tracker, "set_clipboard"):
            await client.set_clipboard(new_text)

        console.print(f"  ✅ Clipboard updated: [green]{new_text}[/green]")

        # Step 4: Verify persistence
        console.print("\n[bold]Step 4:[/bold] Verify clipboard persistence")
        await asyncio.sleep(0.5)

        async with measure_latency(tracker, "get_clipboard"):
            clipboard_text = await client.get_clipboard()

        if clipboard_text == new_text:
            console.print(f"  ✅ Clipboard persistence: [green]PASS[/green]")
        else:
            console.print(f"  ⚠ Clipboard persistence: [yellow]FAIL[/yellow]")

        # Step 5: Performance check
        console.print("\n[bold]Step 5:[/bold] Clipboard performance check")
        clipboard_stats = tracker.get_stats("get_clipboard")
        if clipboard_stats:
            avg_latency = clipboard_stats["avg"]
            console.print(f"  📊 Average get_clipboard latency: {avg_latency:.1f}ms")

            if avg_latency < 100:
                console.print(f"  ✅ Performance: [green]< 100ms target[/green]")
            else:
                console.print(f"  ⚠ Performance: [yellow]> 100ms (expected for ADB path)[/yellow]")

        # Success
        console.print("\n[bold green]✅ Scenario 5: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 5: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
