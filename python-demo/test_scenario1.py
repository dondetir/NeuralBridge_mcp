#!/usr/bin/env python3
"""Test scenario 1 only"""

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from demo_client.mcp_client import NeuralBridgeMCPClient
from demo_client.android_client import AndroidClient
from demo_client.utils.performance import LatencyTracker
from demo_client.scenarios.scenario_1_basics import run_scenario_1_basics

async def main():
    mcp_server_path = Path("../mcp-server/target/release/neuralbridge-mcp").resolve()
    device_id = "emulator-5554"
    screenshot_dir = Path("../screenshots").resolve()
    screenshot_dir.mkdir(parents=True, exist_ok=True)

    print(f"Testing Scenario 1 with screenshot fix")
    print(f"Screenshot Dir: {screenshot_dir}")

    mcp_client = NeuralBridgeMCPClient(str(mcp_server_path), device_id)
    await mcp_client.connect()
    print("✅ Connected!")

    client = AndroidClient(mcp_client)
    tracker = LatencyTracker()

    try:
        passed = await run_scenario_1_basics(client, tracker, screenshot_dir)
        print(f"\nScenario 1: {'PASSED' if passed else 'FAILED'}")

        # Check screenshot file size
        screenshots = list(screenshot_dir.glob("scenario1_*.jpg"))
        if screenshots:
            for ss in screenshots:
                size_kb = ss.stat().st_size / 1024
                print(f"Screenshot: {ss.name} ({size_kb:.1f} KB)")
                if size_kb > 100:
                    print("✅ Screenshot file size is good!")
                else:
                    print("❌ Screenshot file is too small (likely empty)")
        else:
            print("❌ No screenshot files found")

    finally:
        await mcp_client.close()

    return 0

if __name__ == "__main__":
    asyncio.run(main())
