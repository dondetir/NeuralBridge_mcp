# NeuralBridge Android App

Android app providing AI-native automation capabilities via AccessibilityService. Enables sub-100ms UI control for AI agents through a binary protobuf protocol (TCP, port 38472) and an embedded HTTP MCP server (Ktor CIO, port 7474). The HTTP MCP server requires no authentication — any MCP-compatible agent on the same network can connect directly.

## Quick Start

### Prerequisites

- Android 7.0+ (API 24+) device or emulator
- ADB installed and accessible
- Companion APK (build from source or use release APK)

### Installation

```bash
# Build APK
./gradlew assembleDebug

# Install on device
./gradlew installDebug

# Or manually
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Setup

#### 1. Enable AccessibilityService

**Via Settings (Manual):**
```
Settings → Accessibility → NeuralBridge → Enable
```

**Via ADB (Automated):**
```bash
# Enable accessibility service
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService

adb shell settings put secure accessibility_enabled 1

# Grant restricted settings permission (Android 15+)
# Must be done manually in Settings → Apps → NeuralBridge → Advanced
```

#### 2. Enable Notification Access (Optional)

For full notification content access:
```
Settings → Notifications → Notification access → NeuralBridge → Enable
```

#### 3. Grant MediaProjection (Screenshot) Permission

On first screenshot request, a system dialog will appear asking for permission to capture screen content. This is required for screenshot functionality.

**Android 14+ Note:** MediaProjection consent is single-use and resets after:
- App process is killed
- Device restart
- App update/reinstall

If permission is not available, the system automatically falls back to ADB screencap (slower but headless).

### Verify Installation

```bash
# Check AccessibilityService status
adb shell settings get secure enabled_accessibility_services

# View app logs
adb logcat -s NeuralBridge:V

# Test MCP HTTP connection (use the IP shown in the app)
curl http://<device-ip>:7474/health
```

### Connection

**Primary — Network (no setup needed):**
The app displays its IP address on the main screen. Connect from any machine reachable on the same network (WiFi or wired):
```
http://<device-ip>:7474/mcp
```

No API key or authentication headers are required for either method.

## Architecture

### Core Components

1. **NeuralBridgeAccessibilityService** (`service/`)
   - Main automation service
   - UI tree walking and element inspection
   - Event streaming to MCP server

2. **TcpServer** (`network/`)
   - Binary protobuf protocol server
   - Listens on port 38472
   - Routes commands to appropriate handlers

3. **McpHttpServer** (`mcp/`)
   - HTTP MCP server (Ktor CIO, port 7474)
   - JSON-RPC protocol for AI agent tool calls
   - Direct MCP integration without external middleware
   - No authentication required

4. **GestureEngine** (`gesture/`)
   - Gesture execution via `dispatchGesture()`
   - Supports tap, swipe, pinch, drag, multi-touch
   - <50ms execution time for most gestures

5. **UiTreeWalker** (`uitree/`)
   - Semantic UI tree extraction
   - Stable element ID generation (hash-based)
   - AI-optimized descriptions

6. **ScreenshotPipeline** (`screenshot/`)
   - MediaProjection → VirtualDisplay → ImageReader
   - JNI/libjpeg-turbo JPEG encoding (<20ms)
   - ADB screencap fallback

7. **InputEngine** (`input/`)
   - Text input via accessibility actions
   - Clipboard operations
   - Text selection

8. **NotificationListener** (`notification/`)
   - Full notification content access
   - Notification events to MCP server

### Protocol

Binary protobuf over TCP with 7-byte header:
```
┌─────────────┬────────────┬──────────────┬────────────────┐
│ Magic (2B)  │ Type (1B)  │ Length (4B)  │ Payload (N B)  │
│   0x4E42    │ 0x01-0x03  │  big-endian  │   Protobuf     │
└─────────────┴────────────┴──────────────┴────────────────┘
```

Message types:
- `0x01` - Request (MCP server → Companion app)
- `0x02` - Response (Companion app → MCP server)
- `0x03` - Event (Companion app → MCP server, pushed)

## Project Structure

```
android/
├── app/src/main/
│   ├── kotlin/com/neuralbridge/companion/
│   │   ├── service/              # AccessibilityService
│   │   ├── network/              # TCP server, protobuf codec
│   │   ├── gesture/              # Gesture engine
│   │   ├── uitree/               # UI tree walking
│   │   ├── screenshot/           # Screenshot capture
│   │   ├── input/                # Text input, clipboard
│   │   ├── notification/         # Notification listener
│   │   └── MainActivity.kt       # Setup UI
│   ├── cpp/                      # JNI
│   │   ├── jpeg_encoder.cpp      # libjpeg-turbo JPEG encoding
│   │   └── CMakeLists.txt        # NDK build config
│   ├── proto/                    # Protobuf schema (copied from project root proto/)
│   └── res/                      # Resources
│       ├── values/strings.xml
│       └── xml/accessibility_service_config.xml
├── build.gradle.kts              # App module config
└── settings.gradle.kts           # Project settings
```

## Development

### Build

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install and run
./gradlew installDebug
adb shell am start -n com.neuralbridge.companion/.MainActivity
```

### Generate Protobuf Code

```bash
# Copies proto from project root and generates Kotlin code
./gradlew generateProto
```

Output: `app/build/generated/source/proto/`

### Run Tests

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires device)
./gradlew connectedAndroidTest
```

### Logging

View app logs:
```bash
# All logs
adb logcat -s NeuralBridge:V

# Specific components
adb logcat -s TcpServer:V GestureEngine:V UiTreeWalker:V
```

## Android 15+ Sideloading

When installing on Android 15+:

1. Install APK via `adb install` or file manager
2. Open **Settings → Apps → Special app access → Install unknown apps**
3. Find source app (Files, Chrome, etc.) and enable "Allow from this source"
4. After first launch: **Settings → Apps → NeuralBridge → Advanced**
5. Enable **"Allow restricted settings"** (required for full AccessibilityService permissions)

## Permissions

### Required Permissions
- `INTERNET` - TCP server communication
- `FOREGROUND_SERVICE` - Keep service alive
- `BIND_ACCESSIBILITY_SERVICE` - System permission for AccessibilityService

### Optional Permissions
- `BIND_NOTIFICATION_LISTENER_SERVICE` - Full notification content
- `WRITE_SETTINGS` - Device orientation control (future)

### Runtime Permissions
- MediaProjection - Screenshot capture (user consent dialog)

## Performance

### Latency Targets
- **Tap gesture:** <30ms
- **UI tree (< 500 nodes):** <50ms
- **Screenshot (1080p JPEG):** <60ms
- **Text input (< 50 chars):** <100ms

### Actual Performance (Measured)
- AccessibilityService operations: 5-20ms (in-process)
- TCP localhost round-trip: <5ms
- Gesture dispatch: 10-30ms
- UI tree walk (typical app): 20-80ms

## Troubleshooting

### AccessibilityService Issues

**Service not starting:**
- Check if enabled: `adb shell settings get secure enabled_accessibility_services`
- View logs: `adb logcat -s NeuralBridge:V`
- Force restart: Disable and re-enable in Settings

**Permission denied:**
- Android 15+: Must enable "Allow restricted settings" in app info
- System apps may block automation

### Screenshot Issues

**MediaProjection unavailable:**
- User must grant permission via dialog
- Permission resets on Android 14+ after app restart
- Fallback to ADB screencap automatically

**Black screenshots:**
- Secure content (banking apps, DRM) blocks screen capture
- FLAG_SECURE prevents screenshots by design

### Performance Issues

**High latency (>200ms):**
- Check device performance
- Complex UI trees (1000+ nodes) take longer
- ADB operations add 200-500ms overhead

## See Also

- [AccessibilityService Guide](https://developer.android.com/guide/topics/ui/accessibility/service) - Android docs
