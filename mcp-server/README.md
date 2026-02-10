# NeuralBridge MCP Server

Rust-based MCP server providing AI-native Android automation tools. Connects to Android companion app via binary protobuf protocol for <100ms latency operations.

## Quick Start

### Prerequisites

- Rust 1.75+ (`rustc --version`)
- Android SDK Platform-Tools (for ADB)
- Android device or emulator with companion app installed

### Build

```bash
cargo build --release
```

### Run

```bash
# Auto-discover first available device
cargo run -- --auto-discover

# Connect to specific device
cargo run -- --device emulator-5554

# Verify setup
cargo run -- --check
```

## Architecture

The MCP server exposes ~50 tools organized into categories:

- **OBSERVE** (13 tools): `get_ui_tree`, `screenshot`, `find_elements`, etc.
- **ACT** (14 tools): `tap`, `swipe`, `input_text`, `press_key`, etc.
- **MANAGE** (13 tools): `launch_app`, `close_app`, `install_app`, etc.
- **WAIT** (4 tools): `wait_for_element`, `wait_for_gone`, `wait_for_idle`

All tools communicate with the Android companion app using binary protobuf over TCP (port 38472).

## Configuration

### ADB Setup

The MCP server requires ADB for:
- Device discovery
- Port forwarding setup
- Privileged operations (app management, permissions, clipboard on Android 10+)

**ADB Location:**
- Automatically detected from PATH or `$ANDROID_HOME/platform-tools/adb`
- Common paths checked: `/usr/local/bin/adb`, `/usr/bin/adb`

### Port Forwarding

Automatically set up when connecting to a device:
```bash
adb -s <device-id> forward tcp:38472 tcp:38472
```

Manual setup if needed:
```bash
adb forward tcp:38472 tcp:38472
```

## Project Structure

```
mcp-server/
├── src/
│   ├── main.rs              # Entry point, MCP server initialization
│   ├── tools/               # MCP tool implementations
│   │   ├── observe.rs       # UI inspection, screenshots, finding
│   │   ├── act.rs           # Gestures, input, global actions
│   │   ├── manage.rs        # App lifecycle, device settings
│   │   └── wait.rs          # Synchronization primitives
│   ├── protocol/            # Binary protocol
│   │   ├── codec.rs         # Protobuf encoding/decoding
│   │   └── connection.rs    # TCP connection management
│   ├── device/              # Device management
│   │   ├── manager.rs       # Device discovery
│   │   ├── adb.rs           # ADB command execution
│   │   └── pool.rs          # Connection pooling
│   └── semantic/            # Element matching
│       ├── resolver.rs      # Intelligent element resolution
│       └── selector.rs      # Selector parsing
├── proto/
│   └── neuralbridge.proto   # Protocol schema (shared with companion app)
├── build.rs                 # Protobuf code generation
└── Cargo.toml               # Dependencies and build config
```

## Development

### Generate Protobuf Code

Automatically generated on build via `build.rs`:
```bash
cargo build
```

Output location: `src/protocol/generated/`

### Update Protocol Schema

1. Edit `proto/neuralbridge.proto`
2. Rebuild: `cargo build`
3. Update companion app: `cd ../companion-app && ./gradlew generateProto`

### Run Tests

```bash
# All tests
cargo test

# Specific module
cargo test protocol::codec

# Integration tests
cargo test --test integration_tests
```

### Logging

Set log level via `RUST_LOG` environment variable:
```bash
# Debug output
RUST_LOG=debug cargo run -- --auto-discover

# Trace for protocol debugging
RUST_LOG=trace cargo run
```

## Tool Examples

### Get UI Tree
```json
{
  "tool": "android_get_ui_tree",
  "arguments": {
    "include_invisible": false,
    "max_depth": 0
  }
}
```

### Tap Element
```json
{
  "tool": "android_tap",
  "arguments": {
    "text": "Login"
  }
}
```

### Take Screenshot
```json
{
  "tool": "android_screenshot",
  "arguments": {
    "quality": "full"
  }
}
```

## Troubleshooting

### Connection Issues

**Error: "Failed to connect to companion app"**
- Verify companion app is installed and AccessibilityService is enabled
- Check port forwarding: `adb forward --list`
- Ensure device is connected: `adb devices`

**Error: "Device not found"**
- Run `adb devices` to list connected devices
- Use `--device <id>` to specify device explicitly

### Protocol Errors

**Error: "Invalid magic bytes"**
- Protocol version mismatch between server and companion app
- Rebuild both components with matching protobuf schema

### Performance Issues

**UI tree retrieval >100ms:**
- Complex apps with 1000+ nodes may exceed latency target
- Use `max_depth` parameter to limit tree depth
- Consider caching UI tree and using incremental updates

## See Also

- [Companion App README](../companion-app/README.md) - Android app setup
- [Protocol Specification](../docs/prd.md) - Complete technical documentation
- [MCP SDK Documentation](https://github.com/modelcontextprotocol/rust-sdk) - rmcp library
