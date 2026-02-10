# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

NeuralBridge is an AI-native Android automation platform enabling AI agents to control Android devices with <100ms latency. The architecture consists of three tiers:

1. **AI Agent Layer** - AI agents (Claude Code, Cursor, etc.) connect via MCP protocol
2. **MCP Server** - Rust server on host machine translating MCP tool calls to binary protocol commands
3. **Companion App** - Android app using AccessibilityService for in-process UI observation and gesture injection

**Core Constraint**: Every design decision flows from one requirement: an AI agent must do everything a human finger and eye can do, in under 100ms, with zero human intervention.

## Repository Structure

```
neuralbridge/
├── mcp-server/          # Rust MCP server (host machine)
│   ├── src/
│   │   ├── main.rs      # MCP server entry point
│   │   ├── tools/       # MCP tool implementations
│   │   ├── protocol/    # Protobuf definitions and codec
│   │   ├── device/      # Device manager, connection pool
│   │   └── semantic/    # Element resolver, selector parser
│   ├── Cargo.toml
│   └── proto/           # Protobuf schema files
├── companion-app/       # Kotlin + C++ Android app
│   ├── app/src/main/
│   │   ├── kotlin/
│   │   │   ├── service/         # AccessibilityService implementation
│   │   │   ├── network/         # TCP server, protobuf codec
│   │   │   ├── gesture/         # Gesture engine (dispatchGesture wrappers)
│   │   │   ├── input/           # Text input, clipboard, key events
│   │   │   ├── screenshot/      # MediaProjection wrapper
│   │   │   └── uitree/          # UI tree walker, semantic extraction
│   │   ├── cpp/                 # JNI screenshot encoder
│   │   │   └── jpeg_encoder.cpp # libjpeg-turbo integration
│   │   └── proto/               # Generated protobuf code (from mcp-server/proto)
│   ├── build.gradle.kts
│   └── AndroidManifest.xml
└── docs/
    └── prd.md           # Complete technical architecture specification
```

## Technology Stack

### MCP Server (Rust)
- **rmcp** - Official MCP SDK with `#[tool]` macros
- **tokio** - Async runtime for TCP connection management
- **prost** - Protobuf code generation and serialization
- **ADB integration** - Device discovery and port forwarding

### Companion App (Kotlin + C++)
- **AccessibilityService** - In-process UI tree access and gesture injection (API 24+)
- **Kotlin Coroutines** - TCP server and async command handling
- **Protobuf Kotlin** - Binary protocol serialization
- **MediaProjection + ImageReader** - Non-root screenshot capture
- **libjpeg-turbo (JNI)** - Hardware-accelerated JPEG encoding

## Development Commands

### MCP Server (Rust)
```bash
# Build
cd mcp-server
cargo build --release

# Run with auto device discovery
cargo run -- --auto-discover

# Run for specific device
cargo run -- --device emulator-5554

# Verify setup and connection
cargo run -- --check

# Generate protobuf code (when .proto files change)
cargo build  # Runs build.rs which invokes prost-build

# Run tests
cargo test

# Run specific test suite
cargo test --test integration_tests
```

### Companion App (Android)
```bash
cd companion-app

# Build APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Or combine: build + install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Generate protobuf Kotlin code (when .proto files change)
./gradlew generateProto

# Run unit tests
./gradlew test

# Run instrumented tests (requires device)
./gradlew connectedAndroidTest

# View logs from companion app
adb logcat -s NeuralBridge:V

# Check AccessibilityService status
adb shell settings get secure enabled_accessibility_services

# Enable AccessibilityService (automated setup for testing)
adb shell settings put secure enabled_accessibility_services com.neuralbridge.companion/.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

## Setup Requirements

### Android 15+ Sideloading
When installing the companion APK on Android 15+:
1. Install the APK via `adb install` or file manager
2. Open device **Settings → Apps → Special app access → Install unknown apps**
3. Find the source app (e.g., Files, Chrome) and enable "Allow from this source"
4. After first launch, go to **Settings → Apps → NeuralBridge → Advanced**
5. Enable **"Allow restricted settings"** (required for full AccessibilityService permissions)

### MediaProjection Consent (Required for Screenshots)
- On first screenshot request, Android shows a system dialog requesting permission
- User must tap "Start now" or "Allow" (one-time setup)
- **Android 14+ limitation:** Permission is single-use and resets after:
  - App process is killed
  - Device restart
  - App update/reinstall
- If MediaProjection is unavailable, system automatically falls back to ADB screencap (slower but headless)

### NotificationListenerService Permission
For full notification content (title, text, actions, icons):
1. Open **Settings → Notifications → Notification access**
2. Enable **NeuralBridge** in the list
3. Grant the permission by tapping "Allow"
4. This is separate from AccessibilityService permission

### ADB Connection for Privileged Operations
Keep ADB connected (USB or wireless) for operations requiring shell access:
- App installation and uninstallation
- Clearing app data
- Force-stopping apps
- Network service control (WiFi, Bluetooth)
- Granting runtime permissions
- Clipboard access on Android 10+

ADB wireless setup:
```bash
# One-time pairing (Android 11+)
adb pair <device-ip>:5555
# Connect
adb connect <device-ip>:5555
```

## Architecture Deep Dive

### Why AccessibilityService (Not UIAutomator/ADB)

AccessibilityService is the ONLY approach that provides:
- **In-process speed** - <10ms latency (no IPC overhead)
- **Event-driven updates** - Real-time callbacks on UI changes (no polling)
- **Cross-app visibility** - Observe any app, not just one under test
- **Gesture injection** - `dispatchGesture()` for multi-touch (API 24+)
- **Global actions** - Back, Home, Recents, Notifications via `performGlobalAction()`
- **No root required**

Comparison latencies:
- AccessibilityService: <10ms (in-process)
- UIAutomator2: 200-500ms (IPC)
- ADB shell: 500-2000ms (USB/TCP + process spawn)

### Binary Protocol (Protobuf)

All communication between MCP Server and Companion App uses Protobuf over TCP:

**Wire Format:**
```
┌─────────────┬────────────┬──────────────┬────────────────┐
│ Magic (2B)  │ Type (1B)  │ Length (4B)  │ Payload (N B)  │
│   0x4E42    │ 0x01-0x03  │  big-endian  │   Protobuf     │
└─────────────┴────────────┴──────────────┴────────────────┘

Type field:
  0x01 = Request (MCP Server → Companion App)
  0x02 = Response (Companion App → MCP Server)
  0x03 = Event (Companion App → MCP Server, pushed)

Total header: 7 bytes
```

**Schema Location:** `mcp-server/proto/neuralbridge.proto`

Key messages:
- `Request` - Envelope for all commands (get_ui, tap, swipe, etc.)
- `Response` - Envelope for results (ui_tree, screenshot, elements, etc.)
- `Event` - Device-initiated events (ui_changed, toast, notification)

**Critical:** When updating `.proto` files, regenerate code in BOTH repositories:
```bash
# Rust (automatic via build.rs)
cd mcp-server && cargo build

# Kotlin
cd companion-app && ./gradlew generateProto
```

### MCP Tool Architecture

The MCP Server exposes ~50 tools grouped into categories:

- **OBSERVE** (13 tools) - `get_ui_tree`, `screenshot`, `find_elements`, `get_notifications`, `get_clipboard`, etc.
- **ACT** (14 tools) - `tap`, `long_press`, `swipe`, `pinch`, `input_text`, `press_key`, etc.
- **MANAGE** (13 tools) - `launch_app`, `close_app`, `install_app`, `grant_permission`, `set_orientation`, etc.
- **WAIT** (4 tools) - `wait_for_element`, `wait_for_gone`, `wait_for_idle`, etc.
- **WEBVIEW** (3 tools) - `get_webview_dom`, `execute_js`, `get_webview_url`
- **TEST** (6 tools) - `assert_element`, `screenshot_diff`, `start_recording`, `capture_logcat`, etc.

**Selector Resolution:** When agents call tools with selectors (e.g., `tap(text="Login")`), the Semantic Engine resolves them to coordinates:
1. Exact text match
2. Partial text match
3. Content description match
4. Resource ID match (suffix)
5. Combined match (AND logic)
6. Fuzzy match (Levenshtein distance < 3)

If multiple matches exist, prefer: visible → interactive → center-positioned elements.

### Gesture Engine Implementation

All gestures use `AccessibilityService.dispatchGesture()`:
- **Tap** - 50ms single stroke
- **Long Press** - 1000ms+ single stroke at same position
- **Double Tap** - Two 50ms strokes with 100ms gap
- **Swipe** - Linear path with duration (faster = fling)
- **Pinch** - Two simultaneous strokes moving in opposite directions
- **Drag** - Long press then move
- **Multi-gesture** - Arbitrary multi-finger (max 10 simultaneous strokes)

**Performance target:** Gesture dispatch + execution < 50ms

### Screenshot Pipeline

1. **Capture:** MediaProjection → VirtualDisplay → ImageReader (surface) → Image (hardware buffer)
2. **Encode:** JNI call to C++ → libjpeg-turbo (NEON-accelerated) → JPEG bytes
3. **Send:** Length-prefixed via TCP socket

**Performance target:** Full cycle (1080p) < 60ms
- Capture: <30ms
- JPEG encode: <20ms
- TCP transfer (localhost): <10ms

**Quality modes:**
- `full` (quality 80): ~50KB
- `thumbnail` (quality 40): ~20KB

### Error Taxonomy

All errors returned to AI agents must be **actionable**. Error codes include:
- `ELEMENT_NOT_FOUND` - Suggests screenshot + get_ui_tree
- `ELEMENT_NOT_VISIBLE` - Suggests scroll_to_element
- `ELEMENT_AMBIGUOUS` - Returns all candidates for disambiguation
- `TIMEOUT` - Indicates UI loading
- `APP_CRASHED` - Includes logcat crash line
- `DEVICE_LOCKED` - Cannot proceed until unlock

## Development Phases

**Current Status:** Phase 0 (Architecture finalized, implementation starting)

1. **Phase 1 (Weeks 1-6):** Core MVP - 15 essential tools, single device, basic gestures
2. **Phase 2 (Weeks 7-9):** Full gesture suite, text selection, notifications
3. **Phase 3 (Weeks 10-12):** Semantic resolver, event streaming, UI caching
4. **Phase 4 (Weeks 13-16):** Multi-device, WebView tools, CI/CD, visual diff

## Critical Constraints

1. **Latency Budget:** <100ms end-to-end for any action-observe loop
   - Achievable for in-process operations (gestures, UI tree, screenshots via TCP)
   - ADB-routed operations add 200-500ms overhead (app management, permissions, device settings)
   - Complex UI trees (1000+ nodes) can exceed 100ms for tree walking
   - First screenshot after MediaProjection setup takes 150-300ms (warm-up)
2. **API Level:** Minimum Android 7.0 (API 24) for `dispatchGesture()`
3. **No Root:** All functionality must work without root access
4. **Stable Element IDs:** Use `hash(resourceId + className + text + boundsRounded + parentId)`
5. **Max Message Size:** 16MB (sufficient for screenshots)
6. **TCP Port:** Companion app listens on port 38472 (fixed)

## Critical Architectural Constraints

### Shell Commands Require ADB Routing
Shell commands executed via `Runtime.exec()` from the companion app do NOT have elevated privileges. The following operations **must** be routed through ADB from the MCP server:
- `pm install` / `pm clear` - App installation and data clearing
- `am force-stop` - Force stopping applications
- `svc wifi` / `svc bluetooth` - Network service control
- `pm grant` - Granting runtime permissions programmatically
- Any privileged shell command requiring system/root access

### Clipboard Access Restricted (Android 10+)
Since Android 10, background clipboard access is restricted. Apps can only read clipboard when:
- They have input focus (foreground and actively editing text)
- They are the default IME (keyboard)
- **Workaround:** Use ADB shell command `adb shell cmd clipboard get-text` from MCP server

### MediaProjection Requires User Consent
- Android 14+ changed MediaProjection to single-use consent (must re-approve after app restart)
- Cannot be automated in CI/CD without user interaction
- **Workaround:** Fallback to `AdbScreencapProvider` using `adb exec-out screencap -p` (slower, ~200ms vs ~60ms)

### Distribution Channel Limitations
- **Google Play NOT viable:** AccessibilityService apps with broad permissions face strict policy enforcement
- **Distribution method:** Sideloading only (direct APK installation)
- **Android 15+ sideloading:** Requires manual "Allow restricted settings" enablement in device settings

### NotificationListenerService for Full Notification Content
- AccessibilityService provides basic notification events (TYPE_NOTIFICATION_STATE_CHANGED)
- For full notification content (title, text, actions, icons), must implement separate `NotificationListenerService`
- Requires separate permission grant: `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`

## Dual Command Path Architecture

NeuralBridge uses two distinct command paths based on privilege requirements:

### Fast Path (<100ms): Companion App → AccessibilityService
Operations that work in-process through AccessibilityService:
- **Gestures:** tap, long_press, swipe, pinch, drag, multi-touch
- **UI Observation:** get_ui_tree, find_elements, get_element_info
- **Screenshots:** MediaProjection → ImageReader → JPEG encoding
- **Input:** input_text, select_text, press_key (via accessibility actions)
- **Global Actions:** back, home, recents, notifications, quick_settings
- **App Launch:** startActivity with launch intent

### Slow Path (200-500ms): MCP Server → ADB → Device
Operations requiring shell/system privileges (must route through ADB):
- **App Management:** install_app (pm install), clear_app_data (pm clear), close_app (am force-stop)
- **Permissions:** grant_permission (pm grant)
- **Device Settings:** set_wifi (svc wifi), set_bluetooth (svc bluetooth)
- **Clipboard (Android 10+):** get_clipboard (cmd clipboard)
- **Screenshot Fallback:** ADB screencap when MediaProjection unavailable

**Architecture Decision:** Privileged operations are intentionally routed through the MCP server's ADB connection rather than attempting them from the companion app, where they would fail silently or require dangerous root access.

## Common Patterns

### Adding a New MCP Tool

1. Define tool in `mcp-server/src/tools/`:
```rust
#[tool]
async fn android_new_action(param: String) -> Result<Response> {
    // Build protobuf request
    let request = Request {
        command: Some(Command::NewAction(NewActionRequest { param }))
    };
    // Send to device
    send_and_await(request).await
}
```

2. Add protobuf message to `mcp-server/proto/neuralbridge.proto`
3. Implement handler in companion app `service/CommandHandler.kt`
4. Regenerate protobuf code in both projects

### Adding a Gesture Type

1. Add request message to protobuf schema
2. Implement in `companion-app/.../gesture/GestureEngine.kt`:
```kotlin
fun executeNewGesture(params: Params) {
    val path = Path().apply { /* build path */ }
    val stroke = StrokeDescription(path, startTime, duration)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    dispatchGesture(gesture, callback, null)
}
```

3. Wire up in `CommandHandler.kt` router
4. Add MCP tool wrapper in `mcp-server/src/tools/gestures.rs`

## Testing Strategy

- **Unit tests:** Rust (cargo test), Kotlin (JUnit)
- **Integration tests:** Mock device responses in Rust, robolectric in Kotlin
- **E2E tests:** Real device/emulator with automated AccessibilityService enablement
- **CI/CD:** Docker with headless emulator (see Phase 4)

## Key Files Reference

- `docs/prd.md` - Complete technical specification (READ THIS FIRST)
- `mcp-server/src/main.rs` - MCP server entry point and device discovery
- `mcp-server/proto/neuralbridge.proto` - Binary protocol schema
- `companion-app/.../service/NeuralBridgeAccessibilityService.kt` - Main service
- `companion-app/.../network/TcpServer.kt` - Protobuf TCP server
- `companion-app/.../uitree/UiTreeWalker.kt` - Semantic extraction algorithm
- `companion-app/.../gesture/GestureEngine.kt` - All gesture implementations

## Notes

- **Performance is critical:** Every millisecond matters. Profile before optimizing, but be conscious of latency in all design decisions.
- **Protobuf schema is the contract:** Any change requires coordination between Rust and Kotlin codebases.
- **AccessibilityService must stay alive:** Use foreground service + battery optimization exemption.
- **Coordinate systems:** UI tree bounds are in raw pixels matching screenshot coordinates (no DP conversion needed for agents).
- **Resource IDs are stable:** When available, `resourceId` is the best selector (e.g., `com.app:id/login_button`).
