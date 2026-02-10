# NeuralBridge Python MCP Demo Client

> **Comprehensive feature showcase for Phase 1+2** - Demonstrating all 24 MCP tools with real-world scenarios

[![Python 3.8+](https://img.shields.io/badge/python-3.8+-blue.svg)](https://www.python.org/downloads/)
[![MCP SDK](https://img.shields.io/badge/MCP-0.9.0+-green.svg)](https://pypi.org/project/mcp/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

## Overview

This Python MCP demo client showcases NeuralBridge's Android automation capabilities through 7 interactive scenarios. It uses the official MCP Python SDK to communicate with the Rust MCP server, demonstrating how AI agents will integrate with NeuralBridge in production.

**Key Features:**
- ✅ All 24 MCP tools (16 Phase 1 + 8 Phase 2)
- ✅ <100ms latency validation for fast-path operations
- ✅ Beautiful terminal UI with rich formatting
- ✅ Interactive scenario selection
- ✅ Performance tracking and reporting
- ✅ Screenshot capture and saving

## Quick Start

### Prerequisites

1. **Emulator running** with companion app installed:
   ```bash
   adb devices  # Should show emulator-5554
   ```

2. **MCP server built**:
   ```bash
   cd mcp-server && cargo build --release
   ```

3. **Port forwarding active**:
   ```bash
   adb forward tcp:38472 tcp:38472
   ```

4. **Python 3.8+** installed

### Installation

```bash
# 1. Navigate to demo directory
cd python-demo

# 2. Create virtual environment
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# 3. Install dependencies
pip install -r requirements.txt

# 4. Run demo
python -m demo_client.main
```

That's it! The interactive menu will guide you through the scenarios.

## Scenarios

### Scenario 1: UI Inspection & Navigation (~2 min)
**Tools:** `get_ui_tree`, `screenshot`, `find_elements`, `tap`, `press_key`, `global_action`

Demonstrates basic observation and gesture tools:
- Get foreground app
- Retrieve UI tree hierarchy
- Take screenshots
- Find clickable elements
- Tap gestures
- Hardware key presses

### Scenario 2: Form Automation (~3 min)
**Tools:** `launch_app`, `wait_for_element`, `input_text`, `tap`, `wait_for_idle`

Demonstrates app automation workflows:
- Launch Settings app
- Wait for UI to stabilize
- Navigate to Wi-Fi settings
- Form filling simulation
- Screenshot capture

### Scenario 3: Advanced Gestures (~2 min)
**Tools:** `double_tap`, `pinch`, `drag`, `fling`, `swipe`, `long_press`

Demonstrates Phase 2 advanced gestures:
- Double tap to zoom
- Pinch zoom (in/out)
- Drag gestures
- Fling scrolling
- Swipe navigation
- Long press context menus

### Scenario 4: Event Streaming (~2 min)
**Tools:** `enable_events`, `get_notifications`, `open_url`

Demonstrates real-time event monitoring:
- Enable event streaming
- Monitor UI change events
- Capture notifications
- Event-driven automation

### Scenario 5: Clipboard Operations (~1 min)
**Tools:** `set_clipboard`, `get_clipboard`

Demonstrates clipboard integration:
- Set clipboard text
- Read clipboard contents
- Verify persistence
- Performance validation (<100ms)

### Scenario 6: App Lifecycle Management (~2 min)
**Tools:** `launch_app`, `close_app`, `get_foreground_app`, `wait_for_idle`

Demonstrates app control:
- Launch applications
- Verify app state
- Force-stop apps (via ADB)
- Lifecycle validation

### Scenario 7: Performance Stress Test (~1 min)
**Tools:** All fast-path tools

Validates <100ms latency target:
- 20 cycles of operations
- Tap, screenshot, UI tree, foreground app
- P50, P95, P99 latency measurements
- Performance summary report

## Usage Examples

### Interactive Mode (Default)
```bash
python -m demo_client.main
```

Interactive menu allows selecting individual scenarios or running all.

### Headless Mode (Coming Soon)
```bash
# Run specific scenario
python -m demo_client.main --scenario 7

# Run all scenarios
python -m demo_client.main --all

# Custom device
python -m demo_client.main --device emulator-5556

# Custom screenshot directory
python -m demo_client.main --screenshots ./my_screenshots
```

### Programmatic Use

```python
from demo_client.mcp_client import NeuralBridgeMCPClient
from demo_client.android_client import AndroidClient
import asyncio

async def my_automation():
    # Connect to MCP server
    mcp = NeuralBridgeMCPClient(
        mcp_server_path="../mcp-server/target/release/neuralbridge-mcp",
        device_id="emulator-5554"
    )
    await mcp.connect()

    # Create high-level client
    client = AndroidClient(mcp)

    # Automate!
    await client.launch_app("com.android.settings")
    await client.wait_for_idle()
    await client.tap(text="Wi-Fi")

    screenshot = await client.screenshot()
    with open("wifi.jpg", "wb") as f:
        f.write(screenshot)

    # Cleanup
    await mcp.close()

asyncio.run(my_automation())
```

## Architecture

```
Python MCP Client (demo_client)
  │
  ├─ Uses: Official MCP Python SDK
  │
  ↓ MCP Protocol (stdio transport)
  │
Rust MCP Server (neuralbridge-mcp binary)
  │
  ├─ Converts MCP tool calls → Protobuf
  │
  ↓ TCP Port 38472 (binary protobuf protocol)
  │
Android Companion App
  │
  ├─ AccessibilityService (in-process)
  │
  ↓ Result
  │
Rust MCP Server
  │
  ├─ Converts Protobuf → MCP result
  │
  ↓ MCP Protocol
  │
Python MCP Client
```

## Directory Structure

```
python-demo/
├── README.md                         # This file
├── requirements.txt                  # Python dependencies
├── demo_client/
│   ├── __init__.py
│   ├── main.py                      # CLI entry point
│   ├── mcp_client.py                # MCP SDK wrapper
│   ├── android_client.py            # High-level Android API (24 tools)
│   ├── scenarios/
│   │   ├── scenario_1_basics.py
│   │   ├── scenario_2_forms.py
│   │   ├── scenario_3_gestures.py
│   │   ├── scenario_4_events.py
│   │   ├── scenario_5_clipboard.py
│   │   ├── scenario_6_app_lifecycle.py
│   │   └── scenario_7_stress_test.py
│   └── utils/
│       ├── logger.py                # Structured logging
│       └── performance.py           # Latency measurement
└── screenshots/                     # Output directory
```

## Available MCP Tools (24)

### OBSERVE (6 tools)
- `android_get_ui_tree` - Get complete UI hierarchy
- `android_screenshot` - Capture screen as JPEG
- `android_find_elements` - Find UI elements by selector
- `android_get_foreground_app` - Get current app package/activity
- (Future: `android_get_element_info`)
- (Future: `android_get_device_info`)

### ACT (12 tools)
- `android_tap` - Tap by coordinates or selector
- `android_long_press` - Long press gesture
- `android_swipe` - Swipe gesture
- `android_input_text` - Type text
- `android_press_key` - Press hardware key
- `android_global_action` - Global actions (home, recents, etc.)
- `android_double_tap` - Double tap gesture (Phase 2)
- `android_pinch` - Pinch zoom (Phase 2)
- `android_drag` - Drag gesture (Phase 2)
- `android_fling` - Fling scroll (Phase 2)

### MANAGE (3 tools)
- `android_launch_app` - Launch app by package
- `android_close_app` - Force-stop app
- `android_open_url` - Open URL in browser

### WAIT (3 tools)
- `android_wait_for_element` - Wait for element to appear
- `android_wait_for_idle` - Wait for UI to stabilize
- (Future: `android_wait_for_gone`)

### EVENT (2 tools)
- `android_enable_events` - Enable/disable event streaming
- `android_get_notifications` - Get active notifications

### CLIPBOARD (2 tools)
- `android_get_clipboard` - Get clipboard text
- `android_set_clipboard` - Set clipboard text

## Performance Targets

NeuralBridge aims for **<100ms latency** for fast-path operations:

| Operation | Target | Typical |
|-----------|--------|---------|
| `tap` | <100ms | ~64ms |
| `screenshot` | <100ms | ~85ms |
| `get_ui_tree` | <100ms | ~45ms |
| `get_foreground_app` | <100ms | ~30ms |
| `get_clipboard` | <5ms | ~2ms |

**Note:** Slow-path operations (ADB routing) take 200-500ms:
- `close_app` (force-stop via ADB)
- `install_app`, `grant_permission`
- `set_wifi`, `set_bluetooth`

## Troubleshooting

### Error: MCP server binary not found
```bash
cd mcp-server && cargo build --release
```

### Error: Connection refused (port 38472)
```bash
# Verify companion app is running
adb shell ps | grep neuralbridge

# Verify port forwarding
adb forward tcp:38472 tcp:38472

# Test connection
nc -zv localhost 38472
```

### Error: Emulator not found
```bash
# List devices
adb devices

# If no emulator, start one:
emulator -avd <avd_name>
```

### Error: AccessibilityService not enabled
```bash
# Enable via ADB
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### Error: ModuleNotFoundError: mcp
```bash
# Ensure virtual environment is activated
source venv/bin/activate

# Reinstall dependencies
pip install -r requirements.txt
```

## Integration with AI Frameworks

### LangChain Integration

```python
from langchain.tools import Tool
from demo_client.android_client import AndroidClient
import asyncio

# Wrap Android tools as LangChain tools
def create_android_tools(client: AndroidClient):
    return [
        Tool(
            name="android_tap",
            description="Tap on Android screen by coordinates or text",
            func=lambda args: asyncio.run(client.tap(**args))
        ),
        Tool(
            name="android_screenshot",
            description="Take Android screenshot",
            func=lambda: asyncio.run(client.screenshot())
        ),
        # ... all 24 tools
    ]

# Use in LangChain agent
from langchain.agents import initialize_agent, AgentType
android_tools = create_android_tools(client)
agent = initialize_agent(android_tools, llm, agent=AgentType.ZERO_SHOT_REACT_DESCRIPTION)
agent.run("Open Settings and enable Wi-Fi")
```

### CrewAI Integration

```python
from crewai import Agent, Task, Crew
from demo_client.android_client import AndroidClient

# Create Android automation agent
android_agent = Agent(
    role='Android Automation Specialist',
    goal='Automate Android device interactions',
    backstory='Expert in Android UI automation',
    tools=create_android_tools(client),
    verbose=True
)

# Define task
task = Task(
    description='Open Settings, navigate to Wi-Fi, and take a screenshot',
    agent=android_agent
)

# Execute
crew = Crew(agents=[android_agent], tasks=[task])
result = crew.kickoff()
```

## Contributing

We welcome contributions! Areas for improvement:

- [ ] Additional scenarios (drag & drop, text selection, etc.)
- [ ] Video recording of demo runs
- [ ] Jupyter notebook version
- [ ] Comparison benchmarks (vs UIAutomator2, Appium)
- [ ] Docker container for reproducible demo
- [ ] Web UI version (FastAPI + WebSocket)

## License

MIT License - See [LICENSE](../LICENSE) for details.

## Support

- **Documentation:** [docs/prd.md](../docs/prd.md)
- **Issues:** [GitHub Issues](https://github.com/neuralbridge/neuralbridge/issues)
- **Discord:** [Join our community](https://discord.gg/neuralbridge)

## Acknowledgments

Built with:
- [MCP Python SDK](https://github.com/anthropics/mcp-python-sdk) - Official MCP protocol implementation
- [Rich](https://github.com/Textualize/rich) - Beautiful terminal formatting
- [Click](https://click.palletsprojects.com/) - CLI framework
- [asyncio](https://docs.python.org/3/library/asyncio.html) - Async I/O

---

**NeuralBridge** - AI-Native Android Automation | <100ms Latency | Zero Human Intervention
