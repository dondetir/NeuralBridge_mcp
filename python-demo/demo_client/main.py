"""NeuralBridge Python MCP Demo Client - Main Entry Point

Interactive CLI for running demo scenarios showcasing Phase 1+2 features.
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
from .scenarios.scenario_1_basics import run_scenario_1_basics
from .scenarios.scenario_2_forms import run_scenario_2_forms
from .scenarios.scenario_3_gestures import run_scenario_3_gestures
from .scenarios.scenario_4_events import run_scenario_4_events
from .scenarios.scenario_5_clipboard import run_scenario_5_clipboard
from .scenarios.scenario_6_app_lifecycle import run_scenario_6_app_lifecycle
from .scenarios.scenario_7_stress_test import run_scenario_7_stress_test
from .utils.logger import console, setup_logger
from .utils.performance import LatencyTracker

# Scenario registry
SCENARIOS = {
    1: ("UI Inspection & Navigation", "~2 min", run_scenario_1_basics),
    2: ("Form Automation", "~3 min", run_scenario_2_forms),
    3: ("Advanced Gestures", "~2 min", run_scenario_3_gestures),
    4: ("Event Streaming", "~2 min", run_scenario_4_events),
    5: ("Clipboard Operations", "~1 min", run_scenario_5_clipboard),
    6: ("App Lifecycle Management", "~2 min", run_scenario_6_app_lifecycle),
    7: ("Performance Stress Test", "~1 min", run_scenario_7_stress_test),
}


def print_banner():
    """Print welcome banner."""
    banner = """
╔═══════════════════════════════════════════════════════════╗
║                                                           ║
║        NeuralBridge Python MCP Demo Client               ║
║        Phase 1+2 Feature Showcase                        ║
║                                                           ║
║        24 MCP Tools | <100ms Latency | AI-Native         ║
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
    table.add_column("Scenario", style="green", width=35)
    table.add_column("Duration", style="yellow", width=10)

    for num, (name, duration, _) in SCENARIOS.items():
        table.add_row(str(num), name, duration)

    table.add_row("8", "[bold]Run All Scenarios[/bold]", "~13 min")
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
    """Run a single scenario.

    Args:
        scenario_num: Scenario number (1-7)
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        True if scenario passed, False otherwise
    """
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
    """Run all scenarios sequentially.

    Args:
        client: AndroidClient instance
        tracker: LatencyTracker for performance measurement
        screenshot_dir: Directory to save screenshots

    Returns:
        Dictionary with scenario results
    """
    console.print("\n[bold cyan]Running All Scenarios (1-7)[/bold cyan]\n")

    start_time = datetime.now()
    results = {}

    for scenario_num in range(1, 8):
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

    # Results table
    summary_table = Table(title="Scenario Results", show_header=True, header_style="bold magenta")
    summary_table.add_column("Scenario", style="green", width=35)
    summary_table.add_column("Result", style="cyan", width=10)
    summary_table.add_column("Duration", style="yellow", width=15)

    passed_count = 0
    for num, (name, _, _) in SCENARIOS.items():
        result = results.get(num, {})
        passed = result.get("passed", False)
        duration = result.get("duration", 0)

        status = "✅ PASS" if passed else "❌ FAIL"
        duration_str = f"{duration:.1f}s"

        summary_table.add_row(f"{num}. {name}", status, duration_str)

        if passed:
            passed_count += 1

    console.print(summary_table)

    # Overall stats
    console.print(f"\n[bold]Overall Statistics:[/bold]")
    console.print(f"  Scenarios Run: [cyan]{len(results)}/7[/cyan]")
    console.print(f"  Success Rate: [{'green' if passed_count == 7 else 'yellow'}]{passed_count}/{len(results)} ({passed_count/len(results)*100:.0f}%)[/{'green' if passed_count == 7 else 'yellow'}]")
    console.print(f"  Total Time: [cyan]{total_duration/60:.1f}m {total_duration%60:.0f}s[/cyan]")

    # Screenshots
    screenshot_files = list(screenshot_dir.glob("*.jpg"))
    console.print(f"  Screenshots Saved: [cyan]{len(screenshot_files)}[/cyan]")
    console.print(f"  Screenshot Directory: [cyan]{screenshot_dir}[/cyan]")

    # Performance summary
    console.print("\n")
    tracker.print_summary("Overall Performance Summary")

    return results


async def interactive_mode(
    mcp_server_path: str,
    device_id: str,
    screenshot_dir: Path,
    log_level: str
):
    """Run interactive demo mode.

    Args:
        mcp_server_path: Path to MCP server binary
        device_id: Android device ID
        screenshot_dir: Directory to save screenshots
        log_level: Logging level
    """
    # Setup logger
    logger = setup_logger("neuralbridge", log_level, enable_rich=True)

    # Print banner
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
        console.print("  1. Verify emulator is running: adb devices")
        console.print("  2. Verify companion app is installed")
        console.print("  3. Verify port forwarding: adb forward tcp:38472 tcp:38472")
        console.print(f"  4. Verify MCP server binary exists: {mcp_server_path}")
        return

    console.print("[bold green]✅ Connected to MCP server![/bold green]")

    # Create Android client and tracker
    client = AndroidClient(mcp_client)
    tracker = LatencyTracker()

    try:
        # List available tools
        tools = await mcp_client.list_tools()
        console.print(f"[bold]Available MCP Tools:[/bold] [cyan]{len(tools)}[/cyan]")

        # Interactive loop
        while True:
            print_scenario_menu()

            try:
                choice = IntPrompt.ask(
                    "[bold cyan]Select scenario[/bold cyan]",
                    choices=[str(i) for i in range(9)],
                    default=0
                )
            except KeyboardInterrupt:
                console.print("\n[yellow]Interrupted by user[/yellow]")
                break

            if choice == 0:
                console.print("\n[bold cyan]Exiting demo. Thank you![/bold cyan]")
                break
            elif choice == 8:
                # Run all scenarios
                await run_all_scenarios(client, tracker, screenshot_dir)
                break  # Exit after running all
            elif choice in SCENARIOS:
                # Run single scenario
                await run_scenario(choice, client, tracker, screenshot_dir)

                # Ask if user wants to continue
                console.print("\n")
                continue_prompt = input("Press Enter to continue, or Ctrl+C to exit... ")
            else:
                console.print(f"[red]Invalid choice: {choice}[/red]")

    except KeyboardInterrupt:
        console.print("\n[yellow]Interrupted by user[/yellow]")
    finally:
        # Cleanup
        console.print("\n[bold]Closing MCP connection...[/bold]")
        await mcp_client.close()
        console.print("[bold green]✅ Connection closed. Goodbye![/bold green]\n")


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
    help="Run specific scenario (1-7)"
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

    Interactive demo showcasing Phase 1+2 features via MCP protocol.
    """
    # Resolve paths
    mcp_server_path = Path(server).resolve()
    screenshot_dir = Path(screenshots).resolve()
    screenshot_dir.mkdir(parents=True, exist_ok=True)

    # Verify MCP server binary exists
    if not mcp_server_path.exists():
        console.print(f"[bold red]Error:[/bold red] MCP server binary not found: {mcp_server_path}")
        console.print("\n[yellow]Build it with:[/yellow]")
        console.print("  cd mcp-server && cargo build --release")
        sys.exit(1)

    # Run in appropriate mode
    if scenario is not None or run_all:
        # Headless mode (not yet fully implemented - use interactive for now)
        console.print("[yellow]Headless mode not yet implemented. Using interactive mode.[/yellow]")
        asyncio.run(interactive_mode(str(mcp_server_path), device, screenshot_dir, log_level))
    else:
        # Interactive mode
        asyncio.run(interactive_mode(str(mcp_server_path), device, screenshot_dir, log_level))


if __name__ == "__main__":
    main()
