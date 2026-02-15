"""NeuralBridge Python MCP Demo Client - Main Entry Point

Interactive CLI for running demo scenarios showcasing all 36 MCP tools.
"""

import asyncio
import logging
import sys
from datetime import datetime
from pathlib import Path
from typing import Optional

import click
from rich.console import Console
from rich.panel import Panel
from rich.prompt import IntPrompt
from rich.table import Table

from .android_client import AndroidClient
from .mcp_client import NeuralBridgeMCPClient, MCPConnectionError
from .scenarios.preflight import run_preflight
from .scenarios.scenario_1_discovery import run_scenario_1_discovery
from .scenarios.scenario_2_settings import run_scenario_2_settings
from .scenarios.scenario_3_contacts import run_scenario_3_contacts
from .scenarios.scenario_4_gestures import run_scenario_4_gestures
from .scenarios.scenario_5_chrome import run_scenario_5_chrome
from .scenarios.scenario_6_multiapp import run_scenario_6_multiapp
from .scenarios.scenario_7_events import run_scenario_7_events
from .scenarios.scenario_8_lifecycle import run_scenario_8_lifecycle
from .scenarios.scenario_9_accessibility import run_scenario_9_accessibility
from .scenarios.scenario_10_recovery import run_scenario_10_recovery
from .scenarios.scenario_11_explorer import run_scenario_11_explorer
from .scenarios.scenario_12_stress_test import run_scenario_12_stress_test
from .utils.logger import console, setup_logger
from .utils.performance import LatencyTracker

# Scenario registry
SCENARIOS = {
    1: ("Device Discovery & Inspection", "~1 min", run_scenario_1_discovery),
    2: ("Settings Deep Dive", "~3 min", run_scenario_2_settings),
    3: ("Contact Creation Workflow", "~3 min", run_scenario_3_contacts),
    4: ("Gallery Gesture Playground", "~3 min", run_scenario_4_gestures),
    5: ("Chrome Web Automation & Clipboard", "~3 min", run_scenario_5_chrome),
    6: ("Multi-App Workflow", "~3 min", run_scenario_6_multiapp),
    7: ("Clock, Events & Notifications", "~2 min", run_scenario_7_events),
    8: ("App Lifecycle & Debugging", "~2 min", run_scenario_8_lifecycle),
    9: ("Accessibility Audit", "~2 min", run_scenario_9_accessibility),
    10: ("Error Recovery & Resilience", "~2 min", run_scenario_10_recovery),
    11: ("AI Explorer", "~3 min", run_scenario_11_explorer),
    12: ("Performance Stress Test (Bonus)", "~2 min", run_scenario_12_stress_test),
}


def print_banner():
    """Print welcome banner."""
    banner = """
╔═══════════════════════════════════════════════════════════╗
║                                                           ║
║        NeuralBridge Python MCP Demo Client               ║
║        12 Scenarios | 36/36 MCP Tools | AI-Native        ║
║                                                           ║
║        Phase 1+2+3 Complete | <100ms Latency             ║
║                                                           ║
╚═══════════════════════════════════════════════════════════╝
    """
    console.print(banner, style="bold cyan")


def print_connection_info(device_id: str):
    """Print connection information."""
    info_panel = Panel(
        f"[bold]Device:[/bold] {device_id}\n"
        f"[bold]Transport:[/bold] MCP over stdio\n"
        f"[bold]Protocol:[/bold] Protobuf over TCP (port 38472)",
        title="Connection Info",
        border_style="green"
    )
    console.print(info_panel)


def print_scenario_menu():
    """Print interactive scenario selection menu."""
    table = Table(title="Available Scenarios", show_header=True, header_style="bold magenta")
    table.add_column("#", style="cyan", width=3)
    table.add_column("Scenario", style="green", width=45)
    table.add_column("Duration", style="yellow", width=10)

    for num, (name, duration, _) in SCENARIOS.items():
        table.add_row(str(num), name, duration)

    table.add_row("13", "[bold]Run All Scenarios[/bold]", "~29 min")
    table.add_row("0", "Exit", "")

    console.print("\n")
    console.print(table)
    console.print("\n")


async def run_scenario(
    scenario_num: int,
    client: AndroidClient,
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> bool:
    """Run a single scenario."""
    if scenario_num not in SCENARIOS:
        console.print(f"[red]Invalid scenario number: {scenario_num}[/red]")
        return False

    name, duration, scenario_func = SCENARIOS[scenario_num]
    console.print(f"\n[bold cyan]Starting Scenario {scenario_num}: {name}[/bold cyan]")

    try:
        result = await scenario_func(client, tracker, screenshot_dir)
        return result
    except Exception as e:
        console.print(f"[bold red]Scenario {scenario_num} crashed: {e}[/bold red]")
        import traceback
        traceback.print_exc()
        return False


async def run_all_scenarios(
    client: AndroidClient,
    tracker: LatencyTracker,
    screenshot_dir: Path
) -> dict:
    """Run all scenarios sequentially."""
    console.print("\n[bold cyan]Running All Scenarios (1-12)[/bold cyan]\n")

    start_time = datetime.now()
    results = {}

    for scenario_num in range(1, 13):
        scenario_start = datetime.now()
        passed = await run_scenario(scenario_num, client, tracker, screenshot_dir)
        scenario_end = datetime.now()
        scenario_duration = (scenario_end - scenario_start).total_seconds()

        results[scenario_num] = {
            "passed": passed,
            "duration": scenario_duration
        }

        # Brief pause between scenarios
        await asyncio.sleep(1.0)

    end_time = datetime.now()
    total_duration = (end_time - start_time).total_seconds()

    # Print summary
    console.print("\n" + "=" * 60)
    console.print(Panel.fit(
        "[bold cyan]Demo Summary Report[/bold cyan]",
        border_style="cyan"
    ))

    summary_table = Table(title="Scenario Results", show_header=True, header_style="bold magenta")
    summary_table.add_column("Scenario", style="green", width=45)
    summary_table.add_column("Result", style="cyan", width=10)
    summary_table.add_column("Duration", style="yellow", width=15)

    passed_count = 0
    total_scenarios = len(SCENARIOS)
    for num, (name, _, _) in SCENARIOS.items():
        result = results.get(num, {})
        passed = result.get("passed", False)
        duration = result.get("duration", 0)

        status = "PASS" if passed else "FAIL"
        duration_str = f"{duration:.1f}s"

        summary_table.add_row(f"{num}. {name}", status, duration_str)

        if passed:
            passed_count += 1

    console.print(summary_table)

    console.print(f"\n[bold]Overall Statistics:[/bold]")
    console.print(f"  Scenarios Run: [cyan]{len(results)}/{total_scenarios}[/cyan]")
    pct = passed_count / max(len(results), 1) * 100
    color = "green" if passed_count == total_scenarios else "yellow"
    console.print(f"  Success Rate: [{color}]{passed_count}/{len(results)} ({pct:.0f}%)[/{color}]")
    console.print(f"  Total Time: [cyan]{total_duration/60:.1f}m {total_duration%60:.0f}s[/cyan]")

    screenshot_files = list(screenshot_dir.glob("*.jpg"))
    console.print(f"  Screenshots Saved: [cyan]{len(screenshot_files)}[/cyan]")
    console.print(f"  Screenshot Directory: [cyan]{screenshot_dir}[/cyan]")

    console.print("\n")
    tracker.print_summary("Overall Performance Summary")

    return results


async def interactive_mode(
    mcp_server_path: str,
    device_id: str,
    screenshot_dir: Path,
    log_level: str
):
    """Run interactive demo mode."""
    logger = setup_logger("neuralbridge", log_level, enable_rich=True)

    print_banner()
    print_connection_info(device_id)

    # Connect to MCP server
    console.print("\n[bold]Connecting to MCP server...[/bold]")
    try:
        mcp_client = NeuralBridgeMCPClient(mcp_server_path, device_id)
        await mcp_client.connect()
    except MCPConnectionError as e:
        console.print(f"[bold red]Failed to connect to MCP server:[/bold red]")
        console.print(f"[red]{e}[/red]")
        console.print("\n[yellow]Troubleshooting:[/yellow]")
        console.print("  1. Verify device is connected: adb devices")
        console.print("  2. Verify companion app is installed and running")
        console.print("  3. Verify port forwarding: adb forward tcp:38472 tcp:38472")
        console.print(f"  4. Verify MCP server binary exists: {mcp_server_path}")
        return

    console.print("[bold green]Connected to MCP server![/bold green]")

    client = AndroidClient(mcp_client)
    tracker = LatencyTracker()

    try:
        # List available tools
        tools = await mcp_client.list_tools()
        console.print(f"[bold]Available MCP Tools:[/bold] [cyan]{len(tools)}[/cyan]")

        # Run pre-flight checks
        console.print("\n[bold]Running pre-flight checks...[/bold]")
        preflight_ok = await run_preflight(client, tracker, screenshot_dir)
        if not preflight_ok:
            console.print("[bold yellow]Pre-flight checks had issues, but continuing...[/bold yellow]")
        else:
            console.print("[bold green]Pre-flight checks passed![/bold green]")

        # Interactive loop
        while True:
            print_scenario_menu()

            try:
                choice = IntPrompt.ask(
                    "[bold cyan]Select scenario[/bold cyan]",
                    choices=[str(i) for i in range(14)],
                    default=0
                )
            except KeyboardInterrupt:
                console.print("\n[yellow]Interrupted by user[/yellow]")
                break

            if choice == 0:
                console.print("\n[bold cyan]Exiting demo. Thank you![/bold cyan]")
                break
            elif choice == 13:
                await run_all_scenarios(client, tracker, screenshot_dir)
                break
            elif choice in SCENARIOS:
                await run_scenario(choice, client, tracker, screenshot_dir)
                console.print("\n")
                try:
                    input("Press Enter to continue, or Ctrl+C to exit... ")
                except KeyboardInterrupt:
                    break
            else:
                console.print(f"[red]Invalid choice: {choice}[/red]")

    except KeyboardInterrupt:
        console.print("\n[yellow]Interrupted by user[/yellow]")
    finally:
        console.print("\n[bold]Closing MCP connection...[/bold]")
        await mcp_client.close()
        console.print("[bold green]Connection closed. Goodbye![/bold green]\n")


@click.command()
@click.option(
    "--server",
    default="../mcp-server/target/release/neuralbridge-mcp",
    help="Path to MCP server binary",
    type=click.Path(exists=True)
)
@click.option(
    "--device",
    default="emulator-5554",
    help="Android device ID"
)
@click.option(
    "--scenario",
    type=int,
    help="Run specific scenario (1-12)"
)
@click.option(
    "--all",
    "run_all",
    is_flag=True,
    help="Run all scenarios"
)
@click.option(
    "--screenshots",
    default="../screenshots",
    help="Screenshot output directory",
    type=click.Path()
)
@click.option(
    "--log-level",
    default="INFO",
    type=click.Choice(["DEBUG", "INFO", "WARNING", "ERROR"], case_sensitive=False),
    help="Logging level"
)
def main(
    server: str,
    device: str,
    scenario: Optional[int],
    run_all: bool,
    screenshots: str,
    log_level: str
):
    """NeuralBridge Python MCP Demo Client.

    Interactive demo showcasing all 36 MCP tools via 12 scenarios.
    """
    mcp_server_path = Path(server).resolve()
    screenshot_dir = Path(screenshots).resolve()
    screenshot_dir.mkdir(parents=True, exist_ok=True)

    if not mcp_server_path.exists():
        console.print(f"[bold red]Error:[/bold red] MCP server binary not found: {mcp_server_path}")
        console.print("\n[yellow]Build it with:[/yellow]")
        console.print("  cd mcp-server && cargo build --release")
        sys.exit(1)

    asyncio.run(interactive_mode(str(mcp_server_path), device, screenshot_dir, log_level))


if __name__ == "__main__":
    main()
