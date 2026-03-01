<p align="center">
  <img src="companion-app/design/wave-bridge.svg" alt="NeuralBridge" width="120" />
</p>

<h1 align="center">NeuralBridge</h1>

<p align="center">
  <strong>AI-native Android automation via the Model Context Protocol</strong>
</p>

<p align="center">
  Give any AI agent — Claude, GPT, Gemini, or your own — full control over an Android device.<br/>
  Observe the UI. Tap. Swipe. Type. Manage apps. Capture screenshots.<br/>
  All with <strong>sub-100ms latency</strong>. No root required.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="Apache 2.0 License" /></a>
  <a href="CHANGELOG.md"><img src="https://img.shields.io/badge/Version-0.3.0-success" alt="v0.3.0" /></a>
  <a href="#mcp-tools-43"><img src="https://img.shields.io/badge/MCP%20Tools-43-brightgreen" alt="43 Tools" /></a>
  <a href="#performance"><img src="https://img.shields.io/badge/Avg%20Latency-6.4ms-brightgreen" alt="6.4ms" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 7+" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Rust-stable-orange?logo=rust&logoColor=white" alt="Rust" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" /></a>
</p>

---

## Why NeuralBridge?

Existing Android automation (Appium, UIAutomator2, ADB scripting) was built for test engineers, not AI agents. They suffer from:

- **High latency** — 200ms-2000ms per action due to IPC, process spawning, and USB overhead
- **Fragile selectors** — break across app versions, locales, and screen densities
- **No MCP support** — AI agents can't natively connect without custom glue code
- **Root or special builds** — many tools need debug builds, instrumentation, or root

NeuralBridge solves all of this:

| | NeuralBridge | UIAutomator2 | ADB Shell |
|---|:---:|:---:|:---:|
| Tap latency | **~2ms** | 200-500ms | 500-2000ms |
| Text input | **~1.4ms** | 200-500ms | 500-2000ms |
| UI tree read | **18-33ms** | 200-500ms | 300-1000ms |
| Screenshot | **~60ms** | 200-500ms | 200-300ms |
| MCP native | **Yes** | No | No |
| Root required | **No** | No | Partial |
| Works on any app | **Yes** | Limited | Limited |

**Reliability:** 100/100 consecutive requests with zero errors in testing.

---

## How It Works

NeuralBridge is a three-tier system. Your AI agent never talks to the phone directly — it speaks MCP, and NeuralBridge handles everything else.

```
                          YOUR MACHINE                                    ANDROID DEVICE
  ┌──────────────────┐                  ┌──────────────────┐              ┌──────────────────────────────┐
  │                  │                  │                  │              │                              │
  │    AI  Agent     │     MCP          │   NeuralBridge   │  Protobuf   │    NeuralBridge Companion    │
  │                  │   (stdio)        │   MCP Server     │   (TCP)     │           App                │
  │  Claude Code     │ ◄─────────────►  │                  │◄──────────► │                              │
  │  Cursor IDE      │  Tool calls &    │  ┌────────────┐  │  port       │  ┌────────────────────────┐  │
  │  Custom Agent    │  responses       │  │ Tool       │  │  38472      │  │ AccessibilityService   │  │
  │                  │                  │  │ Registry   │  │  via ADB    │  │                        │  │
  │  "tap the        │                  │  ├────────────┤  │  forward    │  │  • UI tree walking     │  │
  │   login button"  │                  │  │ Semantic   │  │             │  │  • Gesture injection   │  │
  │                  │                  │  │ Engine     │  │             │  │  • Event callbacks     │  │
  │  "screenshot     │                  │  ├────────────┤  │             │  │  • Global actions      │  │
  │   the screen"    │                  │  │ Device     │  │             │  ├────────────────────────┤  │
  │                  │                  │  │ Manager    │  │             │  │ Screenshot Pipeline    │  │
  │  "type hello"    │                  │  └────────────┘  │             │  │  MediaProjection →     │  │
  │                  │                  │      Rust         │             │  │  libjpeg-turbo (JNI)   │  │
  └──────────────────┘                  └──────────────────┘              │  ├────────────────────────┤  │
                                                                         │  │ TCP Server + Protobuf  │  │
                                                                         │  └────────────────────────┘  │
                                                                         │         Kotlin + C++         │
                                                                         └──────────────────────────────┘
```

### Data Flow: What happens when you say "tap Login"

```
  Step 1                Step 2                Step 3               Step 4
  Agent sends           MCP Server            Companion App        Response
  MCP tool call         resolves selector     executes gesture     flows back

  ┌─────────┐          ┌─────────────┐       ┌──────────────┐     ┌─────────┐
  │  Agent   │  ──►    │  Semantic    │  ──►  │   Gesture    │ ──► │  Agent   │
  │          │         │  Engine     │        │   Engine     │     │          │
  │ tap(text │ stdio   │             │  TCP   │              │ TCP │ "tapped  │
  │ ="Login")│ <1ms    │ "Login" →   │  <5ms  │ dispatchGesture  │ <1ms │  at      │
  │          │         │ (540, 820)  │        │ at (540,820) │     │ (540,820)│
  └─────────┘          └─────────────┘        └──────────────┘     └─────────┘
                                                    │
                                                    ▼  <50ms
                                              ┌──────────────┐
                                              │  Android OS   │
                                              │  processes    │
                                              │  the tap      │
                                              └──────────────┘

  Total end-to-end: ~60ms (vs ~1500ms with Appium)
```

### The Two Command Paths

Not all operations are equal. NeuralBridge intelligently routes commands through the fastest available path:

```
  ┌─────────────────────────────────────────────────────────────────────┐
  │                         MCP TOOL CALL                              │
  └───────────────────────────┬─────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
  ┌─────────────────────┐        ┌─────────────────────┐
  │   FAST PATH (<10ms) │        │  SLOW PATH (200ms+) │
  │   AccessibilityService       │  ADB Shell           │
  │                     │        │                      │
  │  • tap, swipe, pinch│        │  • install_app       │
  │  • input_text       │        │  • clear_app_data    │
  │  • get_ui_tree      │        │  • grant_permission  │
  │  • find_elements    │        │  • close_app (force) │
  │  • screenshot       │        │  • get_clipboard     │
  │  • press_key        │        │  • set_wifi          │
  │  • global actions   │        │                      │
  │                     │        │  Requires ADB conn.  │
  │  95% of operations  │        │  5% of operations    │
  └─────────────────────┘        └─────────────────────┘
```

### Wire Protocol

Every message between the MCP Server and the Companion App uses a compact binary format:

```
  ┌─────────────────┬──────────────┬────────────────┬─────────────────────┐
  │   Magic (2B)    │  Type (1B)   │  Length (4B)   │   Payload (N B)     │
  │    0x4E42       │  0x01-0x03   │  big-endian    │   Protobuf          │
  └─────────────────┴──────────────┴────────────────┴─────────────────────┘

  Type values:
    0x01 = Request    (Server → Device)    "tap at (540, 820)"
    0x02 = Response   (Device → Server)    "tap completed in 2ms"
    0x03 = Event      (Device → Server)    "UI changed — new screen detected"

  Header: 7 bytes  │  Max payload: 16 MB  │  Port: 38472 (fixed)
```

---

## Getting Started

NeuralBridge is built from source. You'll clone the repo, build the Rust MCP server and the Android companion app, then connect them.

### Prerequisites

| Requirement | Version | What it's for |
|---|---|---|
| **Rust toolchain** | stable | Building the MCP server |
| **Android SDK** | API 24+ | ADB, build tools |
| **Java JDK** | 17 | Building the companion app |
| **protoc** | 3.x | Protobuf code generation |
| **Android device or emulator** | Android 7.0+ | Running the companion app |

<details>
<summary><strong>Installing prerequisites</strong></summary>

**Rust:**
```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

**protoc (Protocol Buffers compiler):**
```bash
# Ubuntu/Debian
sudo apt install -y protobuf-compiler

# macOS
brew install protobuf

# Arch
sudo pacman -S protobuf
```

**Android SDK + JDK:**
Install [Android Studio](https://developer.android.com/studio) which includes the SDK and JDK, or install them separately:
```bash
# Ubuntu/Debian (JDK only)
sudo apt install -y openjdk-17-jdk

# Set ANDROID_HOME
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

</details>

### Step 1: Clone and Build

```bash
# Clone the repository
git clone https://github.com/dondetir/neuralBridge.git
cd neuralbridge

# Build the MCP server (Rust)
cd mcp-server
cargo build --release
cd ..

# Build the companion app (Android)
cd companion-app
./gradlew assembleDebug
cd ..
```

After building, you'll have:
- MCP server binary: `mcp-server/target/release/neuralbridge-mcp` (4.4 MB)
- Companion APK: `companion-app/app/build/outputs/apk/debug/app-debug.apk` (7.5 MB)

### Step 2: Install the Companion App

Connect your Android device via USB (or start an emulator) and install:

```bash
adb install -r companion-app/app/build/outputs/apk/debug/app-debug.apk
```

<details>
<summary><strong>Android 15+ sideloading note</strong></summary>

Android 15 and later require additional steps for apps using AccessibilityService:

1. Install the APK via `adb install` or file manager
2. Open **Settings > Apps > Special app access > Install unknown apps**
3. Find the source app and enable "Allow from this source"
4. Go to **Settings > Apps > NeuralBridge > Advanced**
5. Enable **"Allow restricted settings"**

</details>

### Step 3: Enable Permissions

The companion app needs two permissions enabled from device Settings:

**AccessibilityService** (required — enables all UI automation):
```bash
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

**Notification Listener** (optional — enables notification reading):
> Open **Settings > Notifications > Notification access** and enable **NeuralBridge**

**Screenshot consent** (one-time):
> On first screenshot request, Android shows a system dialog — tap **"Start now"** or **"Allow"**

### Step 4: Connect

```bash
# Set up port forwarding (bridges device TCP port to your machine)
adb forward tcp:38472 tcp:38472

# Start the MCP server (auto-discovers connected devices)
./mcp-server/target/release/neuralbridge-mcp --auto-discover
```

### Step 5: Configure Your AI Agent

Add NeuralBridge to your AI agent's MCP configuration.

**Claude Code** (`.claude/mcp.json`):
```json
{
  "mcpServers": {
    "neuralbridge": {
      "command": "/absolute/path/to/neuralbridge-mcp",
      "args": ["--auto-discover"],
      "env": {
        "ANDROID_HOME": "/path/to/Android/Sdk"
      }
    }
  }
}
```

**Claude Desktop** (`claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "neuralbridge": {
      "command": "/absolute/path/to/neuralbridge-mcp",
      "args": ["--auto-discover"],
      "env": {
        "ANDROID_HOME": "/path/to/Android/Sdk"
      }
    }
  }
}
```

**Any MCP-compatible agent** — NeuralBridge uses stdio transport, the universal MCP standard. Point your agent's MCP client at the `neuralbridge-mcp` binary.

### Verify It Works

Once configured, ask your AI agent:

> "Take a screenshot of the Android device"

If you see a screenshot, everything is connected and working.

---

## MCP Tools (43)

Every tool is callable by your AI agent through MCP. Tools accept **selectors** (text, resource ID, content description) so your agent never needs to hardcode pixel coordinates.

### Observe — See what's on screen

| Tool | Description | Typical Latency |
|---|---|---|
| `screenshot` | Capture screen as JPEG (full or thumbnail quality) | ~60ms |
| `get_ui_tree` | Full UI hierarchy with element IDs, text, bounds | 18-33ms |
| `find_elements` | Search for elements by text, ID, or content description | <10ms |
| `get_screen_context` | Screenshot + simplified UI tree in one call | ~70ms |
| `get_foreground_app` | Current app package name and activity | <5ms |
| `get_device_info` | Manufacturer, model, Android version, screen size | <5ms |
| `get_notifications` | Read notification titles, text, and actions | <10ms |
| `get_clipboard` | Read clipboard content (via ADB on Android 10+) | ~200ms |
| `get_recent_toasts` | Capture toast messages shown on screen | <5ms |

### Act — Touch, type, and interact

| Tool | Description | Typical Latency |
|---|---|---|
| `tap` | Tap by selector or coordinates | ~2ms |
| `long_press` | Long press (default 1000ms hold) | ~1000ms |
| `double_tap` | Double tap gesture | ~150ms |
| `swipe` | Swipe between two points with duration control | ~2ms |
| `fling` | Fast fling in a direction (up/down/left/right) | ~2ms |
| `pinch` | Pinch zoom (scale >1.0 = in, <1.0 = out) | ~300ms |
| `drag` | Drag from point A to point B | ~500ms |
| `input_text` | Type text into a field | ~1.4ms |
| `press_key` | Press system keys (back, home, enter, etc.) | <5ms |
| `pull_to_refresh` | Pull-to-refresh gesture | ~300ms |
| `dismiss_keyboard` | Hide the on-screen keyboard | <10ms |
| `set_clipboard` | Set clipboard content | <10ms |

### Manage — Control apps and device

| Tool | Description | Typical Latency |
|---|---|---|
| `launch_app` | Launch app by package name | <100ms |
| `close_app` | Close app (graceful or force-stop via ADB) | ~200ms |
| `clear_app_data` | Wipe all app data (cache, databases, prefs) | ~200ms |
| `open_url` | Open URL in default browser | <100ms |
| `global_action` | System actions: back, home, recents, notifications | <10ms |
| `list_apps` | List installed apps (all or by filter) | ~200ms |
| `install_app` | Install APK from path via ADB | ~2s |
| `uninstall_app` | Uninstall app by package name via ADB | ~500ms |
| `grant_permission` | Grant a runtime permission via ADB | ~200ms |
| `revoke_permission` | Revoke a runtime permission via ADB | ~200ms |

### Wait — Synchronize with the UI

| Tool | Description | Typical Latency |
|---|---|---|
| `wait_for_element` | Wait until element appears (with timeout) | up to 5s |
| `wait_for_gone` | Wait until element disappears | up to 5s |
| `wait_for_idle` | Wait until UI stabilizes (no changes for 300ms) | up to 5s |
| `scroll_to_element` | Scroll through lists until element is found | up to 30s |

### Test — Validate and debug

| Tool | Description | Typical Latency |
|---|---|---|
| `screenshot_diff` | Compare screenshots for visual regression | ~100ms |
| `capture_logcat` | Capture device logs (filter by package/level/crashes) | ~200ms |
| `accessibility_audit` | Audit screen for a11y issues (touch targets, labels) | <50ms |
| `enable_events` | Toggle real-time event streaming (UI changes, toasts) | <5ms |

### Device — Multi-device management

| Tool | Description |
|---|---|
| `list_devices` | List all connected Android devices with status |
| `select_device` | Switch active device for all subsequent commands |

### Meta — Discover and explore tools

| Tool | Description | Typical Latency |
|---|---|---|
| `search_tools` | Search available tools by name, description, or category | <5ms |
| `describe_tools` | Get detailed description of one or all tools | <5ms |

### Selector System

Most tools accept selectors instead of raw coordinates. The Semantic Engine resolves selectors through a priority chain:

```
  Agent says: tap(text="Login")

  Semantic Engine tries (in order):
    1. Exact text match        →  "Login" == "Login"         ✓ Found!
    2. Partial text match      →  "Login" in "Login Now"
    3. Content description     →  contentDesc == "Login"
    4. Resource ID (suffix)    →  id ends with "login_button"
    5. Combined (AND logic)    →  text="Login" AND class="Button"
    6. Fuzzy match             →  Levenshtein("Login", "Logn") < 3

  Multiple matches? Prefer: visible → interactive → center-positioned
```

---

## Performance

### Benchmarks

All measurements taken on a Pixel-class device over ADB-forwarded TCP.

```
  Operation          NeuralBridge    UIAutomator2    ADB Shell
  ─────────────────────────────────────────────────────────────
  Tap                    ~2ms          200-500ms     500-2000ms
  Swipe                  ~2ms          200-500ms     500-2000ms
  Input text            ~1.4ms         200-500ms     500-2000ms
  Get UI tree          18-33ms         200-500ms     300-1000ms
  Screenshot            ~60ms          200-500ms      200-300ms
  Find elements          <10ms         200-500ms     300-1000ms
  ─────────────────────────────────────────────────────────────
  Average               6.4ms            ~350ms        ~800ms
```

### Why So Fast?

```
  Traditional tools (Appium, UIAutomator2):

    Agent → HTTP → Appium Server → ADB → Device → spawn process → UIAutomator
                                                                        │
    Agent ← HTTP ← Appium Server ← ADB ← Device ← result ◄────────────┘

    Each hop adds latency. Process spawning alone costs 100-200ms.


  NeuralBridge:

    Agent → stdio → MCP Server → TCP → Companion App (in-process)
                                                │
    Agent ← stdio ← MCP Server ← TCP ◄─────────┘

    No process spawning. No IPC. No HTTP.
    AccessibilityService runs IN the same process as the Android UI framework.
    It's like the difference between a phone call and a note on your own desk.
```

### Screenshot Pipeline

```
  ┌─────────────────┐     ┌───────────────────┐     ┌─────────────────┐     ┌──────────┐
  │ MediaProjection  │────►│ ImageReader        │────►│ libjpeg-turbo   │────►│ TCP Send │
  │ (Android API)    │     │ (hardware buffer)  │     │ (C++ via JNI)   │     │          │
  └─────────────────┘     └───────────────────┘     └─────────────────┘     └──────────┘
       <30ms                   zero-copy                 <20ms                  <10ms

  Total: ~60ms for a full 1080p JPEG screenshot
  Full quality (80): ~50KB  │  Thumbnail (40): ~20KB
```

---

## Token Optimization

When AI agents read UI trees, every token costs money and context window space. NeuralBridge compresses tool responses automatically:

| Optimization | Token Savings | What it does |
|---|---|---|
| Compact UI tree | 50-60% | Tabular format instead of verbose JSON |
| Interactive-only filter | ~80% node reduction | Only returns tappable/typeable elements |
| Compact bounds | 15-20% | `[l,t,r,b]` instead of `{"left":0,"top":0,...}` |
| Omit empty fields | 10-15% | Strips null/empty values from responses |
| Tool consolidation | Fewer tools exposed | Merges simple tools into parent tools |

A typical 200-node screen (50 interactive elements) goes from **~3,000 tokens to ~800 tokens** — a 73% reduction.

All optimizations are enabled by default. Override with CLI flags if needed:
```
--no-compact-tree      Disable compact tabular UI tree
--no-filter-elements   Return all elements, not just interactive
--no-compact-bounds    Use verbose bounds format
--no-consolidate       Expose all tools individually
```

---

## Project Structure

```
neuralbridge/
│
├── mcp-server/                     # Rust MCP server (runs on your machine)
│   ├── src/
│   │   ├── main.rs                 # Entry point, CLI args, device discovery
│   │   ├── lib.rs                  # Library root, tool router
│   │   ├── tools/                  # MCP tool implementations
│   │   │   ├── observe.rs          #   screenshot, get_ui_tree, find_elements...
│   │   │   ├── act.rs              #   tap, swipe, input_text, pinch...
│   │   │   ├── manage.rs           #   launch_app, close_app, clear_data...
│   │   │   └── wait.rs             #   wait_for_element, scroll_to_element...
│   │   ├── protocol/               # Binary protocol layer
│   │   │   ├── codec.rs            #   7-byte header encode/decode
│   │   │   └── connection.rs       #   TCP connection + event channel
│   │   ├── device/                 # Device management
│   │   │   ├── adb.rs              #   ADB discovery + port forwarding
│   │   │   ├── manager.rs          #   Multi-device state machine
│   │   │   └── pool.rs             #   Connection pooling
│   │   └── semantic/               # Intelligent element resolution
│   │       ├── resolver.rs         #   6-strategy selector resolution
│   │       └── selector.rs         #   Selector parsing and validation
│   ├── proto/
│   │   └── neuralbridge.proto      # Shared protobuf schema (source of truth)
│   └── Cargo.toml
│
├── companion-app/                  # Android companion app (runs on device)
│   ├── app/src/main/
│   │   ├── kotlin/.../companion/
│   │   │   ├── service/            # AccessibilityService + command handler
│   │   │   ├── gesture/            # Gesture engine (dispatchGesture wrappers)
│   │   │   ├── input/              # Text input, clipboard, key events
│   │   │   ├── screenshot/         # MediaProjection + consent activity
│   │   │   ├── network/            # TCP server + protobuf codec
│   │   │   ├── uitree/             # UI tree walker + semantic extraction
│   │   │   ├── notification/       # NotificationListenerService
│   │   │   └── animation/          # Wave Bridge design system animations
│   │   ├── cpp/
│   │   │   └── jpeg_encoder.cpp    # JNI libjpeg-turbo (NEON-accelerated)
│   │   └── res/                    # Wave Bridge theme resources
│   ├── design/                     # Design system documentation
│   └── build.gradle.kts
│
├── python-demo/                    # Python MCP demo client (12 scenarios)
├── docs/                           # Documentation
├── scripts/                        # Setup helper scripts
├── CONTRIBUTING.md                 # Contribution guidelines
├── SECURITY.md                     # Security policy
├── NOTICE                          # Attribution requirements (Apache 2.0)
└── LICENSE                         # Apache 2.0 License
```

---

## What Works and What Doesn't

### Works great (95% of use cases)

- **Native Android apps** — Settings, Calculator, Clock, Contacts, Files
- **Popular apps** — Chrome, YouTube, Gmail, Maps, social media, e-commerce
- **System UI** — Notifications, Quick Settings, Recent Apps, Launcher
- **Multi-step workflows** — Form filling, navigation, app switching
- **Accessibility testing** — Touch target audits, content description checks

### Works with slower ADB path

- App installation and uninstallation
- Clearing app data
- Granting/revoking permissions
- Toggling WiFi, Bluetooth, Airplane mode
- Clipboard access on Android 10+

### Does not work

| Limitation | Reason |
|---|---|
| Games (OpenGL/Unity/Unreal) | Canvas rendering — no accessibility tree |
| Banking apps with FLAG_SECURE | Screenshot blocked by the app |
| Biometric authentication | Cannot simulate fingerprint/face |
| CI/CD headless screenshots (Android 14+) | MediaProjection requires user consent |
| Google Play distribution | AccessibilityService policy restrictions |

See the [Companion App README](companion-app/README.md) for more details.

---

## Troubleshooting

<details>
<summary><strong>"Device not found" when starting MCP server</strong></summary>

```bash
# Check device is connected
adb devices

# If using wireless ADB (Android 11+)
adb pair <device-ip>:5555
adb connect <device-ip>:5555

# Verify port forwarding
adb forward tcp:38472 tcp:38472
```
</details>

<details>
<summary><strong>AccessibilityService not running</strong></summary>

```bash
# Check current status
adb shell settings get secure enabled_accessibility_services

# Re-enable
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

On Android 15+, you may also need to enable **"Allow restricted settings"** for NeuralBridge in Settings > Apps.
</details>

<details>
<summary><strong>Screenshots return fallback (ADB screencap)</strong></summary>

MediaProjection requires a one-time user consent. Open the NeuralBridge app on the device and trigger a screenshot — tap "Start now" on the system dialog. On Android 14+, this consent resets when the app process dies.
</details>

<details>
<summary><strong>Companion app crashes or stops responding</strong></summary>

```bash
# Check crash logs
adb logcat -s NeuralBridge:V

# Force restart
adb shell am force-stop com.neuralbridge.companion
adb shell am start -n com.neuralbridge.companion/.MainActivity

# Re-enable AccessibilityService after restart
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
```
</details>

<details>
<summary><strong>High latency on first screenshot</strong></summary>

The first screenshot after MediaProjection setup takes 150-300ms (warm-up). Subsequent screenshots run at ~60ms. This is a one-time cost per session.
</details>

---

## Environment Configuration

Copy `.env.example` to `.env` and set your paths:

```bash
cp .env.example .env
```

```env
# Android SDK location
ANDROID_HOME=/path/to/Android/Sdk

# ADB binary path (defaults to $ANDROID_HOME/platform-tools/adb)
ADB_PATH=/path/to/Android/Sdk/platform-tools/adb
```

### MCP Server CLI Options

```
neuralbridge-mcp [OPTIONS]

Options:
  --auto-discover         Automatically find and connect to a device
  --device <ID>           Connect to a specific device (e.g., emulator-5554)
  --check                 Verify setup and connection, then exit
  --enable-permissions    Auto-enable missing device permissions via ADB
  --no-compact-tree       Disable compact UI tree format
  --no-filter-elements    Return all UI elements, not just interactive ones
  --no-compact-bounds     Use verbose bounds format
  --no-consolidate        Expose all tools individually (disables merging)
```

---

## Development

### Building from source

```bash
# MCP Server (Rust)
cd mcp-server
cargo build --release         # Optimized build
cargo test                    # Run all tests
cargo clippy                  # Lint check
cargo fmt --check             # Format check

# Companion App (Android)
cd companion-app
./gradlew assembleDebug       # Build debug APK
./gradlew test                # Run unit tests
./gradlew connectedAndroidTest  # Run instrumented tests (requires device)
```

### Updating the protobuf schema

The schema at `mcp-server/proto/neuralbridge.proto` is the single source of truth. When you change it, regenerate code in both projects:

```bash
# Rust (automatic via build.rs)
cd mcp-server && cargo build

# Kotlin (copies proto and generates code)
cd companion-app && ./gradlew generateProto
```

### Viewing companion app logs

```bash
adb logcat -s NeuralBridge:V
```

---

## Roadmap

- [x] Core MVP — 16 tools, TCP protocol, basic gestures
- [x] Advanced gestures, selectors, event streaming, notifications
- [x] Semantic resolver, scroll-to-element, accessibility audit, screenshot diff
- [ ] Multi-device, WebView tools, CI/CD integration, visual regression

---

## Documentation

| Document | Description |
|---|---|
| [MCP Server](mcp-server/README.md) | Server setup, tool reference, configuration |
| [Companion App](companion-app/README.md) | Android app setup and permissions |
| [Contributing](CONTRIBUTING.md) | How to contribute, code style, PR process |
| [Security](SECURITY.md) | Security policy and vulnerability reporting |
| [Third-Party Licenses](THIRD-PARTY-LICENSES.md) | All dependency licenses |

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for full guidelines. High-priority areas:

- Additional MCP tools (WebView, accessibility actions)
- Performance optimizations
- Cross-platform support (iOS)
- Example scenarios and demos

---

## License

[Apache 2.0](LICENSE) — Copyright 2026 NeuralBridge Contributors

If you use or build upon NeuralBridge, you must include the [NOTICE](NOTICE) file
in your distribution. We'd love a mention too:

> "Powered by [NeuralBridge](https://github.com/dondetir/neuralBridge)"
