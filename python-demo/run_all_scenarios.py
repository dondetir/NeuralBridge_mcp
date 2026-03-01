#!/usr/bin/env python3
"""Headless test runner for all demo scenarios"""

import asyncio
import os
import sys
from pathlib import Path
from datetime import datetime
import traceback

# Add demo_client to path
sys.path.insert(0, str(Path(__file__).parent))

from demo_client.mcp_client import NeuralBridgeMCPClient, MCPConnectionError
from demo_client.android_client import AndroidClient
from demo_client.utils.performance import LatencyTracker
from demo_client.scenarios.scenario_1_discovery import run_scenario_1_discovery
from demo_client.scenarios.scenario_2_settings import run_scenario_2_settings
from demo_client.scenarios.scenario_3_contacts import run_scenario_3_contacts
from demo_client.scenarios.scenario_4_gestures import run_scenario_4_gestures
from demo_client.scenarios.scenario_5_chrome import run_scenario_5_chrome
from demo_client.scenarios.scenario_6_multiapp import run_scenario_6_multiapp
from demo_client.scenarios.scenario_7_events import run_scenario_7_events
from demo_client.scenarios.scenario_8_lifecycle import run_scenario_8_lifecycle
from demo_client.scenarios.scenario_9_accessibility import run_scenario_9_accessibility
from demo_client.scenarios.scenario_10_recovery import run_scenario_10_recovery
from demo_client.scenarios.scenario_11_explorer import run_scenario_11_explorer
from demo_client.scenarios.scenario_12_stress_test import run_scenario_12_stress_test

SCENARIOS = {
    1:  ("Device Discovery & Inspection",  run_scenario_1_discovery),
    2:  ("Settings Navigation",            run_scenario_2_settings),
    3:  ("Contacts App",                   run_scenario_3_contacts),
    4:  ("Gestures",                       run_scenario_4_gestures),
    5:  ("Chrome Browser",                 run_scenario_5_chrome),
    6:  ("Multi-App Flow",                 run_scenario_6_multiapp),
    7:  ("Event Streaming",                run_scenario_7_events),
    8:  ("App Lifecycle",                  run_scenario_8_lifecycle),
    9:  ("Accessibility Audit",            run_scenario_9_accessibility),
    10: ("Error Recovery",                 run_scenario_10_recovery),
    11: ("UI Explorer",                    run_scenario_11_explorer),
    12: ("Stress Test",                    run_scenario_12_stress_test),
}


async def main():
    """Run all scenarios headless"""
    mcp_server_path = Path("../mcp-server/target/release/neuralbridge-mcp").resolve()
    device_id = os.environ.get("ANDROID_DEVICE_ID")  # None = auto-discover
    screenshot_dir = Path("../screenshots").resolve()
    screenshot_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 80)
    print("NeuralBridge Demo - Headless Test Runner")
    print("=" * 80)
    print(f"MCP Server: {mcp_server_path}")
    print(f"Device: {device_id}")
    print(f"Screenshot Dir: {screenshot_dir}")
    print()

    # Verify MCP server exists
    if not mcp_server_path.exists():
        print(f"❌ ERROR: MCP server not found: {mcp_server_path}")
        return 1

    # Connect
    print("Connecting to MCP server...")
    try:
        mcp_client = NeuralBridgeMCPClient(str(mcp_server_path), device_id, no_consolidate=True)
        await mcp_client.connect()
        print("✅ Connected!")
    except MCPConnectionError as e:
        print(f"❌ Connection failed: {e}")
        return 1

    client = AndroidClient(mcp_client)
    tracker = LatencyTracker()

    # Run all scenarios
    results = {}
    start_time = datetime.now()

    for num, (name, scenario_func) in SCENARIOS.items():
        print()
        print("=" * 80)
        print(f"SCENARIO {num}: {name}")
        print("=" * 80)

        scenario_start = datetime.now()

        try:
            passed = await scenario_func(client, tracker, screenshot_dir)
            results[num] = {
                "name": name,
                "passed": passed,
                "error": None,
                "duration": (datetime.now() - scenario_start).total_seconds()
            }

            if passed:
                print(f"\n✅ SCENARIO {num} PASSED")
            else:
                print(f"\n❌ SCENARIO {num} FAILED")

        except Exception as e:
            results[num] = {
                "name": name,
                "passed": False,
                "error": str(e),
                "traceback": traceback.format_exc(),
                "duration": (datetime.now() - scenario_start).total_seconds()
            }
            print(f"\n💥 SCENARIO {num} CRASHED: {e}")
            print(traceback.format_exc())

        # Brief pause between scenarios
        await asyncio.sleep(2)

    # Close connection
    print("\nClosing connection...")
    await mcp_client.close()

    # Print summary
    total_duration = (datetime.now() - start_time).total_seconds()

    print()
    print("=" * 80)
    print("SUMMARY REPORT")
    print("=" * 80)

    passed_count = sum(1 for r in results.values() if r["passed"])

    for num, result in results.items():
        status = "✅ PASS" if result["passed"] else "❌ FAIL"
        duration = result["duration"]
        print(f"{status} Scenario {num}: {result['name']} ({duration:.1f}s)")
        if result.get("error"):
            print(f"    Error: {result['error']}")

    print()
    print(f"Success Rate: {passed_count}/{len(results)} ({passed_count/len(results)*100:.0f}%)")
    print(f"Total Time: {total_duration:.1f}s")
    print()

    # Print performance stats
    tracker.print_summary("Overall Performance")

    # Write detailed results to file
    result_file = Path("test_results.txt")
    with open(result_file, "w") as f:
        f.write("=" * 80 + "\n")
        f.write("DETAILED TEST RESULTS\n")
        f.write("=" * 80 + "\n\n")

        for num, result in results.items():
            f.write(f"Scenario {num}: {result['name']}\n")
            f.write(f"Status: {'PASS' if result['passed'] else 'FAIL'}\n")
            f.write(f"Duration: {result['duration']:.1f}s\n")
            if result.get("error"):
                f.write(f"Error: {result['error']}\n")
                if result.get("traceback"):
                    f.write(f"Traceback:\n{result['traceback']}\n")
            f.write("\n" + "-" * 80 + "\n\n")

    print(f"Detailed results written to: {result_file}")

    return 0 if passed_count == len(results) else 1


if __name__ == "__main__":
    exit_code = asyncio.run(main())
    sys.exit(exit_code)
