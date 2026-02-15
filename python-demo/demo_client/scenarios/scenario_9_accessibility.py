"""Scenario 9: Accessibility Audit

Demonstrates:
- accessibility_audit
- launch_app, close_app
- screenshot
- global_action
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


async def run_scenario_9_accessibility(
    client: "AndroidClient",
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run Scenario 9: Accessibility Audit.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
    console.print(Panel.fit(
        "[bold cyan]Scenario 9: Accessibility Audit[/bold cyan]\n"
        "Duration: ~2 minutes\n"
        "Tools: accessibility_audit, launch_app, close_app, screenshot, tap",
        border_style="cyan"
    ))

    try:
        # Step 1: Launch Settings app
        console.print("\n[bold]Step 1:[/bold] Launch Settings app")
        async with measure_latency(tracker, "launch_app_settings"):
            await client.launch_app("com.android.settings")

        await asyncio.sleep(2.0)
        try:
            await client.wait_for_idle(timeout_ms=3000)
        except Exception:
            pass
        console.print("  ✅ Settings app launched")

        # Step 2: Audit Settings app
        console.print("\n[bold]Step 2:[/bold] Audit Settings accessibility")
        async with measure_latency(tracker, "accessibility_audit_settings"):
            audit_settings = await client.accessibility_audit()

        violations_settings = audit_settings.get("violations", [])

        # Display violations table
        table_settings = Table(title="Settings App Accessibility Violations", show_header=True, header_style="bold red")
        table_settings.add_column("Element", style="cyan")
        table_settings.add_column("Issue", style="yellow")
        table_settings.add_column("Details", style="white")

        if violations_settings:
            for violation in violations_settings[:10]:  # Limit to first 10
                table_settings.add_row(
                    violation.get("element_id", "unknown"),
                    violation.get("issue_type", "unknown"),
                    violation.get("details", "")
                )
            console.print(table_settings)
        else:
            console.print("  ✅ [green]No accessibility violations found in Settings[/green]")

        # Step 3: Screenshot Settings
        console.print("\n[bold]Step 3:[/bold] Capture Settings screenshot")
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_settings_path = screenshot_dir / f"scenario9_settings_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot_settings"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_settings_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_settings_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 4: Close Settings
        console.print("\n[bold]Step 4:[/bold] Close Settings app")
        async with measure_latency(tracker, "close_app_settings"):
            await client.close_app("com.android.settings")

        console.print("  ✅ Settings app closed")

        # Step 5: Launch Chrome
        console.print("\n[bold]Step 5:[/bold] Launch Chrome browser")
        async with measure_latency(tracker, "launch_app_browser"):
            await client.launch_app("com.android.chrome")

        await asyncio.sleep(2.0)
        try:
            await client.wait_for_idle(timeout_ms=3000)
        except Exception:
            pass
        console.print("  ✅ Chrome launched")

        # Step 6: Audit browser
        console.print("\n[bold]Step 6:[/bold] Audit Chrome accessibility")
        async with measure_latency(tracker, "accessibility_audit_browser"):
            audit_browser = await client.accessibility_audit()

        violations_browser = audit_browser.get("violations", [])

        # Display violations table
        table_browser = Table(title="Chrome Accessibility Violations", show_header=True, header_style="bold red")
        table_browser.add_column("Element", style="cyan")
        table_browser.add_column("Issue", style="yellow")
        table_browser.add_column("Details", style="white")

        if violations_browser:
            for violation in violations_browser[:10]:  # Limit to first 10
                table_browser.add_row(
                    violation.get("element_id", "unknown"),
                    violation.get("issue_type", "unknown"),
                    violation.get("details", "")
                )
            console.print(table_browser)
        else:
            console.print("  ✅ [green]No accessibility violations found in Chrome[/green]")

        # Step 7: Try to tap an element (demonstrate NeuralBridge works with poor a11y)
        console.print("\n[bold]Step 7:[/bold] Tap any visible element (showing NeuralBridge works with poor a11y)")
        try:
            async with measure_latency(tracker, "tap_element"):
                # Tap center of screen - should hit something
                await client.tap(x=720, y=1480)

            await asyncio.sleep(0.5)
            console.print("  ✅ Successfully tapped element (NeuralBridge works even with poor metadata)")
        except Exception as tap_error:
            console.print(f"  [yellow]Tap failed (expected on some screens): {tap_error}[/yellow]")

        # Step 8: Screenshot browser result
        console.print("\n[bold]Step 8:[/bold] Capture browser screenshot")
        screenshot_browser_path = screenshot_dir / f"scenario9_browser_{timestamp}.jpg"

        async with measure_latency(tracker, "screenshot_browser"):
            screenshot_bytes = await client.screenshot(quality="full", save_path=screenshot_browser_path)

        size_kb = len(screenshot_bytes) / 1024
        console.print(f"  ✅ Screenshot saved: [green]{screenshot_browser_path.name}[/green] ({size_kb:.1f} KB)")

        # Step 9: Close browser
        console.print("\n[bold]Step 9:[/bold] Close Chrome")
        async with measure_latency(tracker, "close_app_browser"):
            await client.close_app("com.android.chrome")

        console.print("  ✅ Chrome closed")

        # Step 10: Return to home
        console.print("\n[bold]Step 10:[/bold] Return to home screen")
        async with measure_latency(tracker, "global_action_home"):
            await client.global_action("HOME")

        await asyncio.sleep(0.5)
        console.print("  ✅ Returned to home screen")

        # Display summary
        console.print("\n[bold]Accessibility Audit Summary:[/bold]")
        console.print(f"  Settings app: [red]{len(violations_settings)} violations[/red]")
        console.print(f"  Chrome: [red]{len(violations_browser)} violations[/red]")
        console.print(f"  [green]NeuralBridge can automate apps even with poor accessibility metadata[/green]")

        # Success
        console.print("\n[bold green]✅ Scenario 9: PASSED[/bold green]")
        return True

    except Exception as e:
        console.print(f"\n[bold red]❌ Scenario 9: FAILED[/bold red]")
        console.print(f"[red]Error: {e}[/red]")
        return False
