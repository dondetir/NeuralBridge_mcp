# NeuralBridge MCP Server

Rust-based MCP server providing AI-native Android automation tools. Connects to an Android companion app via binary protobuf protocol for <100ms latency operations.

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
./target/release/neuralbridge-mcp --auto-discover

# Connect to specific device
./target/release/neuralbridge-mcp --device emulator-5554

# Verify setup
./target/release/neuralbridge-mcp --check
```

---

## Connecting AI Agents

### Claude Code

Place `.mcp.json` in your project root (or `~/.claude/mcp.json` for global use):

```json
{
  "mcpServers": {
    "neuralbridge": {
      "command": "/path/to/neuralbridge-mcp",
      "args": ["--auto-discover"],
      "env": {
        "ANDROID_HOME": "/path/to/Android/Sdk",
        "ADB_PATH": "/path/to/Android/Sdk/platform-tools/adb"
      }
    }
  }
}
```

Claude Code will automatically detect `.mcp.json` in the project directory and make all Android tools available in the session. You can also add it via the CLI:

```bash
claude mcp add neuralbridge /path/to/neuralbridge-mcp --auto-discover
```

**Usage in Claude Code:**

Just describe what you want in natural language — Claude will call the MCP tools automatically:

```
"Take a screenshot of the current screen"
"Tap the Login button"
"Find all text fields on screen and fill them in"
"Launch com.example.myapp and navigate to Settings"
```

---

### GitHub Copilot (VS Code)

Add to `.vscode/mcp.json` in your workspace (VS Code 1.99+ with Copilot Chat):

```json
{
  "servers": {
    "neuralbridge": {
      "command": "/path/to/neuralbridge-mcp",
      "args": ["--auto-discover"],
      "env": {
        "ANDROID_HOME": "/path/to/Android/Sdk",
        "ADB_PATH": "/path/to/Android/Sdk/platform-tools/adb"
      }
    }
  }
}
```

Or add to VS Code `settings.json` for global availability:

```json
{
  "mcp": {
    "servers": {
      "neuralbridge": {
        "command": "/path/to/neuralbridge-mcp",
        "args": ["--auto-discover"],
        "env": {
          "ANDROID_HOME": "/path/to/Android/Sdk",
          "ADB_PATH": "/path/to/Android/Sdk/platform-tools/adb"
        }
      }
    }
  }
}
```

**Usage in Copilot Chat:**

Open Copilot Chat (`Ctrl+Alt+I`), switch to **Agent mode**, and the Android tools will be available:

```
@workspace Take a screenshot of the connected Android device
@workspace Tap on the "Sign In" button on screen
@workspace Get the UI tree and find all clickable elements
```

---

## Tool Reference

43 tools across 6 categories (4 redundant tools hidden by default — see [Token Optimization](#token-optimization)):

| Category | Count | Key Tools |
|----------|-------|-----------|
| **observe** | 9 | `get_ui_tree`, `screenshot`, `find_elements`, `get_screen_context`, `get_clipboard` |
| **act** | 12 | `tap`, `long_press`, `swipe`, `double_tap`, `pinch`, `drag`, `input_text`, `press_key` |
| **manage** | 10 | `launch_app`, `close_app`, `install_app`, `clear_app_data`, `grant_permission`, `revoke_permission` |
| **device** | 2 | `list_devices`, `select_device` |
| **wait** | 4 | `wait_for_element`, `wait_for_gone`, `wait_for_idle`, `scroll_to_element` |
| **test** | 4 | `capture_logcat`, `screenshot_diff`, `accessibility_audit`, `enable_events` |
| **meta** | 2 | `search_tools`, `describe_tools` |

Search tools at runtime with the `android_search_tools` tool (e.g., `query: "screenshot"`, `category: "observe"`).

---

## Tool Examples

### Get UI Tree

```json
{
  "tool": "android_get_ui_tree",
  "arguments": {
    "filter": "interactive"
  }
}
```

`filter` options: `"interactive"` (default — clickable/focusable/scrollable elements only), `"all"`, `"text"` (elements with visible text).

### Tap an Element

```json
{
  "tool": "android_tap",
  "arguments": {
    "text": "Login"
  }
}
```

Selectors (use the most stable available): `resource_id` > `text` > `content_desc` > `x`+`y` coordinates.

### Take a Screenshot

```json
{
  "tool": "android_screenshot",
  "arguments": {
    "quality": "thumbnail",
    "max_width": 720
  }
}
```

`quality`: `"full"` (~50KB) or `"thumbnail"` (~20KB). `max_width` caps resolution for token efficiency.

### Input Text

```json
{
  "tool": "android_input_text",
  "arguments": {
    "text": "user@example.com",
    "resource_id": "com.example.app:id/email_field"
  }
}
```

### Wait for Element

```json
{
  "tool": "android_wait_for_element",
  "arguments": {
    "text": "Welcome",
    "timeout_ms": 5000
  }
}
```

### Get Screen Context (all-in-one)

```json
{
  "tool": "android_get_screen_context",
  "arguments": {}
}
```

Returns foreground app info + interactive UI tree + thumbnail screenshot in a single call — the most token-efficient way to understand the current state.

### Capture Logcat

```json
{
  "tool": "android_capture_logcat",
  "arguments": {
    "package": "com.example.app",
    "level": "E",
    "lines": 50
  }
}
```

Output is automatically compressed: timestamps stripped, duplicate lines deduplicated, truncated to 8,000 chars (most recent kept).

---

## Token Optimization

All optimizations are ON by default. Disable specific ones via CLI flags:

| Flag | Effect |
|------|--------|
| `--no-compact-tree` | UI tree returns verbose JSON instead of compact tabular format |
| `--no-filter-elements` | `get_ui_tree` shows all nodes instead of interactive-only |
| `--no-compact-bounds` | Bounds use `{"left":l}` object instead of `[l,t,r,b]` array |
| `--no-consolidate` | Expose 4 redundant tools (fling, pull_to_refresh, dismiss_keyboard, get_foreground_app) |

Always-on: omit empty fields, strip `"success": true`, logcat compression, meta-tools.

See the [main README](../README.md#token-optimization) for savings estimates.

---

## Configuration

### ADB Setup

ADB is required for device discovery and privileged operations (app management, permissions, clipboard on Android 10+).

**Detection order:**
1. `ADB_PATH` environment variable
2. `$ANDROID_HOME/platform-tools/adb`
3. System `PATH`

### Port Forwarding

Set up automatically on connection. Manual setup:

```bash
adb forward tcp:38472 tcp:38472
```

### Environment Variables

| Variable | Description |
|----------|-------------|
| `ANDROID_HOME` | Android SDK root (e.g., `~/Android/Sdk`) |
| `ADB_PATH` | Full path to ADB binary (overrides ANDROID_HOME) |
| `RUST_LOG` | Log level: `error`, `warn`, `info` (default), `debug`, `trace` |

---

## Development

### Project Structure

```
mcp-server/
├── src/
│   ├── main.rs          # Entry point, all tool implementations (~4000 lines)
│   ├── lib.rs           # Library entry point (for unit tests)
│   ├── tools/
│   │   └── manage.rs    # ADB management helpers
│   ├── protocol/
│   │   ├── codec.rs     # 7-byte header + protobuf framing
│   │   └── connection.rs # TCP connection with retry/backoff
│   ├── device/
│   │   ├── manager.rs   # Device discovery and port forwarding
│   │   └── adb.rs       # ADB command execution
│   └── semantic/        # Element selector resolution (6 match strategies)
├── proto/
│   └── neuralbridge.proto  # Shared protocol schema
├── build.rs             # Protobuf code generation
└── Cargo.toml
```

### Update Protocol Schema

1. Edit `proto/neuralbridge.proto`
2. Rebuild Rust: `cargo build` (auto-runs `prost-build`)
3. Rebuild Kotlin: `cd ../companion-app && ./gradlew generateProto`

### Run Tests

```bash
# Unit tests only (no device required) — 94 tests
cargo test --lib && cargo test --bin neuralbridge-mcp

# All tests including integration (device required)
cargo test
```

### Logging

```bash
RUST_LOG=debug ./target/release/neuralbridge-mcp --auto-discover
RUST_LOG=trace ./target/release/neuralbridge-mcp  # Protocol-level debugging
```

---

## Troubleshooting

### "Failed to connect to companion app"
- Verify AccessibilityService is enabled: `adb shell settings get secure enabled_accessibility_services`
- Check port forwarding: `adb forward --list`
- Restart companion app and re-enable the service

### "Device not found"
- Run `adb devices` — device must show as `device` (not `unauthorized` or `offline`)
- Use `--device <id>` to specify explicitly
- For wireless ADB: `adb connect <ip>:5555`

### "Invalid magic bytes"
- Protocol version mismatch — rebuild both MCP server and companion app

### Screenshot returns error
- MediaProjection requires one-time user consent dialog on first use
- Android 14+: permission resets on app restart — tap "Allow" again
- Server automatically falls back to ADB screencap if MediaProjection unavailable

### UI tree retrieval slow (>100ms)
- Use `filter: "interactive"` (default) to skip layout-only nodes
- Complex apps with 1000+ nodes may exceed the latency target by design

---

## See Also

- [Companion App README](../companion-app/README.md) — Android app setup and permissions
- [MCP SDK (rmcp)](https://github.com/modelcontextprotocol/rust-sdk) — Rust MCP library
- [MCP Specification](https://modelcontextprotocol.io) — Protocol documentation
