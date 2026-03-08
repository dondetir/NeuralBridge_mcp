<p align="center">
  <img src="android/design/wave-bridge.svg" alt="NeuralBridge" width="120" />
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
  <a href="CHANGELOG.md"><img src="https://img.shields.io/badge/Version-0.4.0-success" alt="v0.4.0" /></a>
  <a href="#mcp-tools-32"><img src="https://img.shields.io/badge/MCP%20Tools-32-brightgreen" alt="32 Tools" /></a>
  <a href="#performance"><img src="https://img.shields.io/badge/Avg%20Latency-6.4ms-brightgreen" alt="6.4ms" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 7+" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" /></a>
</p>

<p align="center">
  <img src="docs/screenshots/status-tab.png" alt="Status — MCP server connected" width="240" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/setup-tab.png" alt="Setup — permission status" width="240" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/logs-tab.png" alt="Logs — real-time tool calls" width="240" />
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

NeuralBridge is a two-tier system. Your AI agent speaks MCP over HTTP directly to the companion app — no middleware required.

```
              YOUR MACHINE                                       ANDROID DEVICE
  ┌──────────────────┐                              ┌──────────────────────────────────┐
  │                  │                              │                                  │
  │    AI  Agent     │   MCP over HTTP (port 7474)  │    NeuralBridge Companion App    │
  │                  │ ◄──────────────────────────► │                                  │
  │  Claude Code     │  Tool calls &                │  ┌──────────────────────────┐   │
  │  Cursor IDE      │  responses                   │  │ MCP HTTP Server          │   │
  │  Custom Agent    │                              │  │ (Ktor CIO, port 7474)    │   │
  │                  │                              │  ├──────────────────────────┤   │
  │  "tap the        │                              │  │ Tool Registry (32 tools) │   │
  │   login button"  │                              │  ├──────────────────────────┤   │
  │                  │                              │  │ AccessibilityService     │   │
  │  "screenshot     │                              │  │  • UI tree walking       │   │
  │   the screen"    │                              │  │  • Gesture injection     │   │
  │                  │                              │  │  • Event callbacks       │   │
  │  "type hello"    │                              │  ├──────────────────────────┤   │
  │                  │                              │  │ Screenshot Pipeline      │   │
  └──────────────────┘                              │  │  MediaProjection →       │   │
                                                    │  │  libjpeg-turbo (JNI)    │   │
                                                    │  └──────────────────────────┘   │
                                                    │          Kotlin + C++            │
                                                    └──────────────────────────────────┘
```

### Data Flow: What happens when you say "tap Login"

```
  Step 1                Step 2               Step 3
  Agent sends           Companion App        Response
  MCP tool call         resolves & executes  flows back

  ┌─────────┐          ┌──────────────┐      ┌─────────┐
  │  Agent   │  ──►    │  Companion   │ ──►  │  Agent  │
  │          │         │     App      │      │         │
  │ tap(text │  HTTP   │              │ HTTP │ "tapped │
  │ ="Login")│  <5ms   │ "Login" →   │ <1ms │  at     │
  │          │         │ (540, 820)  │      │(540,820)│
  └─────────┘          │ dispatchGesture     └─────────┘
                       │ at (540,820) │
                       └──────────────┘
                              │
                              ▼  <50ms
                       ┌──────────────┐
                       │  Android OS  │
                       │  processes   │
                       │  the tap     │
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
  │  • tap, swipe, pinch│        │  • close_app (force) │
  │  • input_text       │        │  • list_apps         │
  │  • get_ui_tree      │        │  • set_clipboard     │
  │  • find_elements    │        │                      │
  │  • screenshot       │        │                      │
  │  • press_key        │        │                      │
  │  • global actions   │        │  Requires ADB conn.  │
  │                     │        │                      │
  │  95% of operations  │        │  5% of operations    │
  └─────────────────────┘        └─────────────────────┘
```

---

## Getting Started

NeuralBridge is built from source. You'll clone the repo, build the Android companion app, then connect your AI agent.

### Prerequisites

| Requirement | Version | What it's for |
|---|---|---|
| **Android SDK** | API 24+ | ADB, build tools |
| **Java JDK** | 17 | Building the companion app |
| **Android device or emulator** | Android 7.0+ | Running the companion app |

<details>
<summary><strong>Installing prerequisites</strong></summary>

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
git clone https://github.com/dondetir/NeuralBridge_mcp.git
cd NeuralBridge_mcp

# Build the Android app
cd android
./gradlew assembleDebug
cd ..
```

After building, you'll have:
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### Step 2: Install the Companion App

Connect your Android device via USB (or start an emulator) and install:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
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

Connect over the network — the app shows its IP on the main screen. No port forwarding needed. Just make sure your machine and the Android device are reachable on the same network (WiFi or wired).

The companion app starts the embedded MCP HTTP server automatically when the NeuralBridge toggle is enabled. No separate server process to run.

### Step 5: Configure Your AI Agent

Add NeuralBridge to your AI agent's MCP configuration. No authentication is required.

**Claude Code** (CLI):
```bash
claude mcp add neuralbridge http://<device-ip>:7474/mcp --transport http
```

Or manually in `.claude/mcp.json`:
```json
{
  "mcpServers": {
    "neuralbridge": {
      "type": "http",
      "url": "http://<device-ip>:7474/mcp"
    }
  }
}
```

**Claude Desktop** (`claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "neuralbridge": {
      "type": "http",
      "url": "http://<device-ip>:7474/mcp"
    }
  }
}
```

**Any MCP-compatible agent** — NeuralBridge uses HTTP transport on port 7474. Point your agent's MCP client at `http://<device-ip>:7474/mcp`. No authentication headers are needed.

### Verify It Works

Once configured, ask your AI agent:

> "Take a screenshot of the Android device"

If you see a screenshot, everything is connected and working.

---

## MCP Tools (32)

Every tool is callable by your AI agent through MCP. Tools accept **selectors** (text, resource ID, content description) so your agent never needs to hardcode pixel coordinates.

### Observe — See what's on screen

| Tool | Description | Typical Latency |
|---|---|---|
| `screenshot` | Capture screen as JPEG (full or thumbnail quality) | ~60ms |
| `get_ui_tree` | Full UI hierarchy with element IDs, text, bounds | 18-33ms |
| `find_elements` | Search for elements by text, ID, or content description | <10ms |
| `get_screen_context` | Screenshot + simplified UI tree in one call | ~70ms |
| `get_device_info` | Manufacturer, model, Android version, screen size | <5ms |
| `get_notifications` | Read notification titles, text, and actions | <10ms |
| `get_recent_toasts` | Capture toast messages shown on screen | <5ms |

### Act — Touch, type, and interact

| Tool | Description | Typical Latency |
|---|---|---|
| `tap` | Tap by selector or coordinates | ~2ms |
| `long_press` | Long press (default 1000ms hold) | ~1000ms |
| `double_tap` | Double tap gesture | ~150ms |
| `swipe` | Swipe between two points with duration control | ~2ms |
| `pinch` | Pinch zoom (scale >1.0 = in, <1.0 = out) | ~300ms |
| `drag` | Drag from point A to point B | ~500ms |
| `input_text` | Type text into a field | ~1.4ms |
| `press_key` | Press system keys (back, home, enter, etc.) | <5ms |
| `set_clipboard` | Set clipboard content | <10ms |

### Manage — Control apps and device

| Tool | Description | Typical Latency |
|---|---|---|
| `launch_app` | Launch app by package name | <100ms |
| `close_app` | Close app (graceful or force-stop via ADB) | ~200ms |
| `open_url` | Open URL in default browser | <100ms |
| `global_action` | System actions: back, home, recents, notifications | <10ms |
| `list_apps` | List installed apps (all or by filter) | ~200ms |

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

    Agent → HTTP → Companion App (in-process)
                          │
    Agent ← HTTP ◄────────┘

    No process spawning. No intermediate server.
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

All optimizations are enabled by default and configured within the companion app.

---

## Project Structure

```
neuralbridge/
├── android/                       # Android app (runs on device)
│   ├── app/src/main/
│   │   ├── kotlin/.../companion/
│   │   │   ├── mcp/              # Embedded MCP HTTP server (Ktor CIO, port 7474)
│   │   │   ├── service/          # AccessibilityService + command handler
│   │   │   ├── gesture/          # Gesture engine
│   │   │   ├── input/            # Text input, clipboard, key events
│   │   │   ├── screenshot/       # MediaProjection + libjpeg-turbo (JNI)
│   │   │   ├── network/          # TCP server + protobuf codec
│   │   │   ├── uitree/           # UI tree walker + semantic extraction
│   │   │   └── notification/     # NotificationListenerService
│   │   ├── cpp/                   # JNI (libjpeg-turbo JPEG encoding)
│   │   └── res/                   # Resources
│   ├── proto/                     # Protobuf schema (source of truth)
│   └── build.gradle.kts
├── examples/                      # Example MCP client
│   └── mcp_client.py
├── docs/                          # Documentation
├── CONTRIBUTING.md
├── SECURITY.md
└── LICENSE                        # Apache 2.0
```

---

## What Works and What Doesn't

### Works great (95% of use cases)

- **Native Android apps** — Settings, Calculator, Clock, Contacts, Files
- **Popular apps** — Chrome, YouTube, Gmail, Maps, social media, e-commerce
- **System UI** — Notifications, Quick Settings, Recent Apps, Launcher
- **Multi-step workflows** — Form filling, navigation, app switching
- **Accessibility testing** — Touch target audits, content description checks

### Does not work

| Limitation | Reason |
|---|---|
| Games (OpenGL/Unity/Unreal) | Canvas rendering — no accessibility tree |
| Banking apps with FLAG_SECURE | Screenshot blocked by the app |
| Biometric authentication | Cannot simulate fingerprint/face |
| CI/CD headless screenshots (Android 14+) | MediaProjection requires user consent |
| Google Play distribution | AccessibilityService policy restrictions |

See the [Android App README](android/README.md) for more details.

---

## Troubleshooting

<details>
<summary><strong>Cannot connect to MCP server (HTTP)</strong></summary>

```bash
# 1. Check that both devices are reachable on the same network
#    The app shows its IP on the main screen

# 2. Verify the server is running (use the IP shown in the app)
curl http://<device-ip>:7474/health

```

Also check that the NeuralBridge toggle is enabled in the app — the MCP server only runs when the toggle is on.
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

## Development

### Building from source

```bash
cd android
./gradlew assembleDebug       # Build debug APK
./gradlew test                # Run unit tests
./gradlew connectedAndroidTest  # Run instrumented tests (requires device)
```

### Updating the protobuf schema

The schema at `android/proto/neuralbridge.proto` is the source of truth. When you change it, regenerate Kotlin code:

```bash
cd android && ./gradlew generateProto
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
| [Android App](android/README.md) | Android app setup and permissions |
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

[Apache 2.0](LICENSE) — Copyright 2026 dondetir

If you use or build upon NeuralBridge, you must include the [NOTICE](NOTICE) file
in your distribution. We'd love a mention too:

> "Powered by [NeuralBridge](https://github.com/dondetir/NeuralBridge_mcp)"
