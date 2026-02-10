# NeuralBridge: Complete Technical Architecture
## AI-Native Android Automation Platform

**Version:** 2.0 | **Date:** February 9, 2026 | **Status:** Architecture Finalized

---

## 📋 TL;DR — Read This First

**What:** AI-native Android automation platform enabling AI agents to control Android devices with <100ms latency for in-process operations.

**Why:** Existing tools (Appium, UIAutomator) are 10-20x slower due to ADB overhead, process spawning, and IPC delays.

**How:** Three-tier architecture with persistent in-process connection via AccessibilityService.

### ✅ What Works (95% of Apps)
- **Fast path (<100ms):** Tap, swipe, scroll, input text, read UI tree, capture screenshots
- **Works on:** Native Android apps, e-commerce, social media, productivity apps, settings screens
- **Requirements:** Android 7.0+ (API 24), no root needed

### ⚠️ What's Slower (200-500ms via ADB)
- Install/uninstall apps, clear app data, force-stop apps, grant permissions
- Toggle WiFi/airplane mode, modify system settings
- **Reason:** These operations require shell/system privileges, must route through ADB from MCP server

### ❌ What Doesn't Work
- Games (OpenGL/Unity/Unreal), canvas-rendered UIs
- Banking apps with FLAG_SECURE, biometric authentication flows
- Background clipboard monitoring (Android 10+ restriction)
- CI/CD headless screenshots on Android 14+ without user consent
- Google Play distribution (AccessibilityService policy violation)

### 🏗️ Key Architecture Decisions
1. **AccessibilityService** for in-process UI access (<10ms latency, no IPC)
2. **Rust MCP server** for device management, protobuf handling, ADB routing
3. **Kotlin companion app** with TCP server, gesture engine, screenshot pipeline
4. **Protobuf over TCP** for type-safe binary protocol
5. **Dual command path:** Fast (TCP) for gestures/UI, Slow (ADB) for privileged ops

### 📦 Technology Stack
- **MCP Server:** Rust + `rmcp` + `tokio` + `prost` (protobuf)
- **Companion App:** Kotlin + Coroutines + AccessibilityService + MediaProjection
- **Screenshot JNI:** C++ + libjpeg-turbo (NEON-accelerated)
- **Protocol:** Protobuf 3 over TCP via ADB port-forward

### 🚨 Critical Constraints
1. **Latency budget:** <100ms for in-process ops only; ADB ops add 200-500ms
2. **Android 14+ MediaProjection:** Requires user consent per session (cannot automate in CI/CD)
3. **Android 15+ sideloading:** Users must enable "Allow restricted settings"
4. **Clipboard access:** Restricted since Android 10; requires focus or ADB workaround
5. **Distribution:** Sideload only (APK/ADB); Google Play will reject (policy violation)

### 📅 Timeline
- **Phase 1 (6 weeks):** Core MVP — 15 tools, basic gestures, single device
- **Phase 2 (3 weeks):** Full gesture suite, notifications, text selection
- **Phase 3 (3 weeks):** Semantic resolver, event streaming, UI caching
- **Phase 4 (4 weeks):** Multi-device, WebView tools, CI/CD, visual diff

### 📖 First-Time Readers
**Start here:** Sections 1, 2.1, 5, 6, 7, 8
**Skip on first read:** Sections 2.2.2 (full tool listing), 2.3.2 (protobuf schema), 2.4 (error codes)
**Reference only:** Sections with implementation pseudocode (marked `[IMPLEMENTATION DETAIL]` below)

---

## 1. Architecture Overview

NeuralBridge is a three-tier system. Every design decision flows from one constraint: **an AI agent must be able to do everything a human finger and eye can do, in under 100ms, with zero human intervention.**

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  TIER 1: AI AGENT LAYER                                                         │
│                                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌────────────────────┐     │
│  │ Claude Code │  │ Cursor IDE  │  │ Copilot CLI │  │ Custom MCP Client  │     │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └─────────┬──────────┘     │
│         │                │                │                    │                 │
│         └────────────────┴────────────────┴────────────────────┘                 │
│                                    │                                             │
│                          MCP Protocol (stdio / SSE)                              │
│                                    │                                             │
├────────────────────────────────────┼─────────────────────────────────────────────┤
│  TIER 2: MCP SERVER (Host Machine — Rust)                                       │
│                                    │                                             │
│  ┌─────────────────────────────────┴──────────────────────────────────────┐      │
│  │                         MCP TOOL REGISTRY                              │      │
│  │                                                                        │      │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────────┐  │      │
│  │  │ OBSERVE TOOLS    │  │ ACT TOOLS        │  │ MANAGE TOOLS        │  │      │
│  │  │                  │  │                  │  │                     │  │      │
│  │  │ • get_ui_tree    │  │ • tap            │  │ • launch_app        │  │      │
│  │  │ • screenshot     │  │ • long_press     │  │ • close_app         │  │      │
│  │  │ • get_element    │  │ • double_tap     │  │ • install_app       │  │      │
│  │  │ • find_elements  │  │ • swipe          │  │ • clear_app_data    │  │      │
│  │  │ • get_text       │  │ • fling          │  │ • set_orientation   │  │      │
│  │  │ • get_toast      │  │ • pinch          │  │ • set_wifi          │  │      │
│  │  │ • get_notifs     │  │ • drag           │  │ • set_location      │  │      │
│  │  │ • get_clipboard  │  │ • input_text     │  │ • set_clipboard     │  │      │
│  │  │ • is_keyboard_up │  │ • select_text    │  │ • open_notifications│  │      │
│  │  │ • get_foreground │  │ • press_key      │  │ • open_quick_settings│ │      │
│  │  │ • get_device_info│  │ • scroll_to_elem │  │ • grant_permission  │  │      │
│  │  │ • assert_element │  │ • multi_gesture  │  │ • open_url          │  │      │
│  │  │ • compare_screens│  │ • hide_keyboard  │  │ • execute_shell     │  │      │
│  │  └──────────────────┘  └──────────────────┘  └─────────────────────┘  │      │
│  │                                                                        │      │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────────┐  │      │
│  │  │ WAIT TOOLS       │  │ WEBVIEW TOOLS    │  │ TEST TOOLS          │  │      │
│  │  │                  │  │                  │  │                     │  │      │
│  │  │ • wait_for_elem  │  │ • get_webview_dom│  │ • start_recording   │  │      │
│  │  │ • wait_for_gone  │  │ • exec_js        │  │ • stop_recording    │  │      │
│  │  │ • wait_for_text  │  │ • get_webview_url│  │ • capture_logcat    │  │      │
│  │  │ • wait_for_idle  │  │                  │  │ • screenshot_diff   │  │      │
│  │  │ • wait_for_event │  │                  │  │ • get_perf_metrics  │  │      │
│  │  └──────────────────┘  └──────────────────┘  └─────────────────────┘  │      │
│  └────────────────────────────────────────────────────────────────────────┘      │
│                                                                                 │
│  ┌─────────────────────┐  ┌───────────────────────┐  ┌─────────────────────┐    │
│  │ SEMANTIC ENGINE     │  │ DEVICE MANAGER         │  │ SESSION MANAGER     │    │
│  │                     │  │                         │  │                     │    │
│  │ • AI description    │  │ • Device discovery      │  │ • Connection pool   │    │
│  │   generator         │  │ • Port allocation       │  │ • State recovery    │    │
│  │ • Element resolver  │  │ • Health monitoring     │  │ • Event buffering   │    │
│  │ • Selector parser   │  │ • ADB tunnel setup      │  │ • Error taxonomy    │    │
│  │ • Coord normalizer  │  │ • Capability detection  │  │ • Audit logging     │    │
│  └──────────┬──────────┘  └───────────┬─────────────┘  └──────────┬──────────┘    │
│             │                         │                           │              │
│             └─────────────────────────┴───────────────────────────┘              │
│                                       │                                          │
│                          Binary Protocol (Protobuf over TCP)                     │
│                          via ADB port-forward (no USB driver needed)             │
│                                       │                                          │
├───────────────────────────────────────┼──────────────────────────────────────────┤
│  TIER 3: COMPANION APP (Android Device — Kotlin + C++ via JNI)                  │
│                                       │                                          │
│  ┌────────────────────────────────────┴───────────────────────────────────┐      │
│  │                     TCP SERVER (Kotlin Coroutines)                      │      │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────┐   │      │
│  │  │ Protocol Decoder│  │ Command Router  │  │ Response Encoder     │   │      │
│  │  │ (Protobuf)      │  │                 │  │ (Protobuf)           │   │      │
│  │  └─────────────────┘  └─────────────────┘  └──────────────────────┘   │      │
│  └────────────────────────────────────────────────────────────────────────┘      │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐     │
│  │                     ACCESSIBILITY SERVICE                               │     │
│  │                                                                         │     │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────┐    │     │
│  │  │ Event Listener  │  │ UI Tree Walker  │  │ State Cache          │    │     │
│  │  │                 │  │                 │  │ (Ring Buffer)        │    │     │
│  │  │ • Window change │  │ • Node visitor  │  │                      │    │     │
│  │  │ • Content change│  │ • Semantic map  │  │ • Last 3 UI states   │    │     │
│  │  │ • Scroll events │  │ • Stable ID gen │  │ • Delta computation  │    │     │
│  │  │ • Focus changes │  │ • Depth tracker │  │ • Element index      │    │     │
│  │  │ • Notification  │  │                 │  │                      │    │     │
│  │  └─────────────────┘  └─────────────────┘  └──────────────────────┘    │     │
│  └─────────────────────────────────────────────────────────────────────────┘     │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐     │
│  │                     ACTION EXECUTORS                                     │     │
│  │                                                                         │     │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────┐    │     │
│  │  │ Gesture Engine  │  │ Input Engine    │  │ System Engine        │    │     │
│  │  │                 │  │                 │  │                      │    │     │
│  │  │ • dispatchGest  │  │ • IME control   │  │ • performGlobalAction│    │     │
│  │  │   (single tap)  │  │ • Text inject   │  │ • Intent launcher    │    │     │
│  │  │   (long press)  │  │ • Clipboard mgr │  │ • Package manager    │    │     │
│  │  │   (double tap)  │  │ • Text selection│  │ • Settings modifier  │    │     │
│  │  │   (swipe/fling) │  │ • Cursor control│  │ • Notification reader│    │     │
│  │  │   (pinch zoom)  │  │ • Key events    │  │ • Permission handler │    │     │
│  │  │   (drag & drop) │  │                 │  │ • Shell executor     │    │     │
│  │  │   (multi-touch) │  │                 │  │                      │    │     │
│  │  └─────────────────┘  └─────────────────┘  └──────────────────────┘    │     │
│  └─────────────────────────────────────────────────────────────────────────┘     │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐     │
│  │                     SCREENSHOT PIPELINE (C++ via JNI)                    │     │
│  │                                                                         │     │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────┐    │     │
│  │  │ MediaProjection │  │ Hardware JPEG   │  │ Delta Encoder        │    │     │
│  │  │ + ImageReader   │  │ Encoder (libjpeg│  │ (block comparison)   │    │     │
│  │  │                 │  │ -turbo via JNI) │  │                      │    │     │
│  │  └─────────────────┘  └─────────────────┘  └──────────────────────┘    │     │
│  └─────────────────────────────────────────────────────────────────────────┘     │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐     │
│  │                     FOREGROUND SERVICE + KEEP-ALIVE                      │     │
│  │  • Persistent notification (required for foreground service)             │     │
│  │  • Battery optimization exemption request on setup                      │     │
│  │  • Watchdog thread: restarts TCP server if killed                       │     │
│  │  • Partial wake lock during active sessions only                        │     │
│  └─────────────────────────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Component Deep Dives

### 2.1 Companion App (Android Side)

The companion app is the heart of NeuralBridge. It runs on the Android device and provides all the eyes and hands that the AI agent needs.

#### 2.1.1 Accessibility Service — The Eyes

**What it does:** The AccessibilityService is a privileged Android service that can observe the entire UI of any app on the device, receive real-time events when anything changes, and perform actions (gestures, clicks, global actions) on behalf of the user.

**Why Accessibility Service and not UIAutomator/ADB:**

| Approach | Latency | Capabilities | Root Required | Limitations |
|----------|---------|-------------|---------------|-------------|
| AccessibilityService | <10ms (in-process) | Full UI tree, gestures, global actions, notifications | No | Requires user to enable in Settings |
| UIAutomator2 | 200-500ms (IPC) | UI tree, actions | No | Separate process, no event streaming |
| ADB shell commands | 500-2000ms (USB/TCP) | Screenshots, input, dumpsys | No | External process, polling only |
| Instrumentation (Espresso) | <10ms | Full access | No | Only works on app under test |

The AccessibilityService is the **only** approach that gives us:
- In-process speed (no IPC overhead)
- Real-time event callbacks (no polling)
- Cross-app visibility (see any app, not just one)
- Gesture injection via `dispatchGesture()` (since Android 7.0 / API 24)
- Global actions via `performGlobalAction()` (back, home, notifications, quick settings, recents)
- No root required

**Configuration (accessibility_service_config.xml):**
```xml
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds
        |flagIncludeNotImportantViews
        |flagRequestEnhancedWebAccessibility
        |flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canRequestFilterKeyEvents="true"
    android:notificationTimeout="50"
    android:settingsActivity=".SettingsActivity"
    android:description="@string/service_description" />
```

Key flags explained:
- `flagReportViewIds`: Returns resource IDs for elements (critical for stable identification)
- `flagIncludeNotImportantViews`: Returns ALL views, not just "important" ones (gets decorative elements too)
- `flagRequestEnhancedWebAccessibility`: Exposes WebView DOM via accessibility tree
- `flagRetrieveInteractiveWindows`: Access to multiple windows (dialogs, popups, split screen)
- `canPerformGestures`: Enables `dispatchGesture()` for touch/swipe/pinch injection
- `canRequestFilterKeyEvents`: Lets us intercept hardware key events

**UI Tree Walker — Semantic Extraction Algorithm:**

`[IMPLEMENTATION DETAIL - REFERENCE ONLY]`

```
FUNCTION walkTree(rootNode):
    queue = [rootNode]
    semanticTree = {}
    elementIndex = {}  // text/description → element map

    WHILE queue is not empty:
        node = queue.dequeue()

        semanticElement = {
            id: generateStableId(node),
            type: mapToSemanticType(node.className),
            text: node.text ?? "",
            contentDescription: node.contentDescription ?? "",
            hint: node.hintText ?? "",
            resourceId: node.viewIdResourceName ?? "",
            bounds: node.boundsInScreen,
            center: computeCenter(node.boundsInScreen),
            depth: computeDepth(node),

            // State
            isClickable: node.isClickable,
            isLongClickable: node.isLongClickable,
            isScrollable: node.isScrollable,
            isCheckable: node.isCheckable,
            isChecked: node.isChecked,
            isFocusable: node.isFocusable,
            isFocused: node.isFocused,
            isEditable: node.isEditable,
            isEnabled: node.isEnabled,
            isSelected: node.isSelected,
            isPassword: node.isPassword,
            isVisibleToUser: node.isVisibleToUser,

            // Hierarchy
            childCount: node.childCount,
            children: [],

            // AI-optimized
            aiDescription: buildAiDescription(node),
            actions: extractAvailableActions(node)
        }

        // Index for semantic lookup
        IF semanticElement.text:
            elementIndex[semanticElement.text] = semanticElement
        IF semanticElement.contentDescription:
            elementIndex[semanticElement.contentDescription] = semanticElement

        FOR each child in node.children:
            queue.enqueue(child)
            semanticElement.children.add(walkTree(child))

        RETURN semanticElement
```

**Semantic Type Mapping:**

| Android Class | Semantic Type | AI Description Hint |
|---------------|--------------|---------------------|
| `android.widget.Button` | `Button` | "clickable button" |
| `android.widget.EditText` | `TextInput` | "text input field" |
| `android.widget.TextView` | `Text` | "text label" |
| `android.widget.ImageView` | `Image` | "image" |
| `android.widget.ImageButton` | `IconButton` | "icon button" |
| `android.widget.CheckBox` | `Checkbox` | "checkbox" |
| `android.widget.Switch` | `Toggle` | "toggle switch" |
| `android.widget.RadioButton` | `RadioButton` | "radio option" |
| `android.widget.Spinner` | `Dropdown` | "dropdown selector" |
| `android.widget.SeekBar` | `Slider` | "slider control" |
| `android.widget.ProgressBar` | `ProgressBar` | "progress indicator" |
| `android.widget.ListView` | `List` | "scrollable list" |
| `android.widget.RecyclerView` | `List` | "scrollable list" |
| `android.widget.ScrollView` | `ScrollContainer` | "scroll area" |
| `android.widget.ViewPager` | `PageView` | "swipeable pages" |
| `android.widget.TabLayout` | `TabBar` | "tab navigation" |
| `android.widget.Toolbar` | `Toolbar` | "toolbar" |
| `android.webkit.WebView` | `WebView` | "web content" |
| `android.widget.FrameLayout` | `Container` | (omit from description) |
| `android.widget.LinearLayout` | `Container` | (omit from description) |

**Stable ID Generation:**

Elements get stable IDs that survive UI redraws using a hash of:
```
stableId = hash(resourceId + className + text + boundsRounded + parentStableId)
```

Where `boundsRounded` rounds coordinates to nearest 10dp to tolerate minor layout shifts. If the element has a `resourceId` (e.g., `com.app:id/login_button`), that alone becomes the primary key since it's stable across sessions.

#### 2.1.2 Gesture Engine — The Hands

The Gesture Engine uses Android's `AccessibilityService.dispatchGesture()` API to inject touch events. This API supports **multi-stroke gestures** — meaning we can simulate pinch-to-zoom, two-finger swipe, and any other multi-touch interaction.

**How dispatchGesture works internally:**

1. You build a `GestureDescription` with one or more `StrokeDescription` objects
2. Each stroke is a `Path` with a start time and duration
3. Android's `MotionEventInjector` converts these into `MotionEvent` sequences
4. Events are injected at the input pipeline level — apps see them as real touches

**Complete Gesture Implementations:**

`[IMPLEMENTATION DETAIL - REFERENCE ONLY]`

```
TAP(x, y):
    path = new Path()
    path.moveTo(x, y)
    stroke = StrokeDescription(path, startTime=0, duration=50ms)
    dispatchGesture(GestureDescription([stroke]))

LONG_PRESS(x, y, durationMs=1000):
    path = new Path()
    path.moveTo(x, y)
    // Stay in same position — duration makes it a long press
    stroke = StrokeDescription(path, startTime=0, duration=durationMs)
    dispatchGesture(GestureDescription([stroke]))

DOUBLE_TAP(x, y):
    // First tap
    path1 = new Path(); path1.moveTo(x, y)
    stroke1 = StrokeDescription(path1, startTime=0, duration=50ms)
    // Second tap after 100ms gap
    path2 = new Path(); path2.moveTo(x, y)
    stroke2 = StrokeDescription(path2, startTime=150ms, duration=50ms)
    dispatchGesture(GestureDescription([stroke1, stroke2]))

SWIPE(x1, y1, x2, y2, durationMs=300):
    path = new Path()
    path.moveTo(x1, y1)
    path.lineTo(x2, y2)
    stroke = StrokeDescription(path, startTime=0, duration=durationMs)
    dispatchGesture(GestureDescription([stroke]))

FLING(x1, y1, x2, y2):
    // Same as swipe but with very short duration = high velocity
    SWIPE(x1, y1, x2, y2, durationMs=50)

PINCH(centerX, centerY, startDistance, endDistance, durationMs=500):
    // Two simultaneous strokes moving in opposite directions
    // Finger 1: moves from top to center (or center to top)
    path1 = new Path()
    path1.moveTo(centerX, centerY - startDistance/2)
    path1.lineTo(centerX, centerY - endDistance/2)
    stroke1 = StrokeDescription(path1, startTime=0, duration=durationMs)

    // Finger 2: moves from bottom to center (or center to bottom)
    path2 = new Path()
    path2.moveTo(centerX, centerY + startDistance/2)
    path2.lineTo(centerX, centerY + endDistance/2)
    stroke2 = StrokeDescription(path2, startTime=0, duration=durationMs)

    dispatchGesture(GestureDescription([stroke1, stroke2]))

DRAG(fromX, fromY, toX, toY, durationMs=1000):
    // Long press then move = drag
    path = new Path()
    path.moveTo(fromX, fromY)
    // Hold position briefly (creates long-press trigger)
    path.lineTo(fromX, fromY)  // tiny pause via duration
    path.lineTo(toX, toY)
    stroke = StrokeDescription(path, startTime=0, duration=durationMs)
    dispatchGesture(GestureDescription([stroke]))

MULTI_GESTURE(touchPoints[]):
    // Arbitrary multi-finger gesture
    strokes = []
    FOR each point in touchPoints:
        path = new Path()
        path.moveTo(point.startX, point.startY)
        FOR each waypoint in point.waypoints:
            path.lineTo(waypoint.x, waypoint.y)
        strokes.add(StrokeDescription(path, point.startTime, point.duration))
    dispatchGesture(GestureDescription(strokes))
```

**GestureDescription Limits (per Android docs):**
- Max strokes per gesture: 10 (sufficient for any realistic interaction)
- Max duration: 60 seconds
- Strokes can overlap in time (enabling simultaneous multi-touch)

#### 2.1.3 Input Engine — Text & Clipboard

**Text Input** uses `AccessibilityNodeInfo.performAction()`:
```
INPUT_TEXT(element, text):
    // Focus the element
    node = findNode(element)
    node.performAction(ACTION_FOCUS)

    // Clear existing text
    arguments = Bundle()
    arguments.putInt(ACTION_ARGUMENT_SELECTION_START, 0)
    arguments.putInt(ACTION_ARGUMENT_SELECTION_END, node.text.length)
    node.performAction(ACTION_SET_SELECTION, arguments)
    node.performAction(ACTION_CUT)

    // Set new text
    arguments = Bundle()
    arguments.putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    node.performAction(ACTION_SET_TEXT, arguments)
```

**Text Selection:**
```
SELECT_TEXT(element, start, end):
    node = findNode(element)
    arguments = Bundle()
    arguments.putInt(ACTION_ARGUMENT_SELECTION_START, start)
    arguments.putInt(ACTION_ARGUMENT_SELECTION_END, end)
    node.performAction(ACTION_SET_SELECTION, arguments)
```

**Clipboard** uses `ClipboardManager`:
```
GET_CLIPBOARD():
    clipboardManager = getSystemService(CLIPBOARD_SERVICE)
    return clipboardManager.primaryClip?.getItemAt(0)?.text

SET_CLIPBOARD(text):
    clipboardManager.setPrimaryClip(ClipData.newPlainText("NeuralBridge", text))
```

**Key Events** for special keys:
```
PRESS_KEY(keyCode):
    // Use dispatchGesture for simple keys, or:
    performGlobalAction(keyCode)  // for BACK, HOME, RECENTS

    // For arbitrary key codes (Enter, Tab, Delete, arrows):
    // Inject via Instrumentation or shell command fallback
    Runtime.exec("input keyevent $keyCode")
```

#### 2.1.4 System Engine — Device Control

**Global Actions (AccessibilityService.performGlobalAction):**

| Action Constant | What It Does | MCP Tool |
|----------------|-------------|----------|
| `GLOBAL_ACTION_BACK` | Press Back button | `press_back` |
| `GLOBAL_ACTION_HOME` | Press Home button | `press_home` |
| `GLOBAL_ACTION_RECENTS` | Open recent apps | `open_recents` |
| `GLOBAL_ACTION_NOTIFICATIONS` | Pull down notification shade | `open_notifications` |
| `GLOBAL_ACTION_QUICK_SETTINGS` | Open quick settings panel | `open_quick_settings` |
| `GLOBAL_ACTION_POWER_DIALOG` | Show power menu | `show_power_menu` |
| `GLOBAL_ACTION_LOCK_SCREEN` | Lock the device | `lock_screen` |
| `GLOBAL_ACTION_TAKE_SCREENSHOT` | System screenshot | `system_screenshot` |
| `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` | Close notification shade | `close_notifications` |

**Notification Reading:**
```
READ_NOTIFICATIONS():
    // AccessibilityService receives TYPE_NOTIFICATION_STATE_CHANGED events
    // Additionally, use NotificationListenerService (requires separate permission)
    // to get full notification content including:
    //   - title, text, subtext, bigText
    //   - package name (which app sent it)
    //   - actions (button labels and intents)
    //   - posted timestamp
```

**App Lifecycle Management (via shell commands executed from companion):**
```
LAUNCH_APP(packageName):
    Intent intent = packageManager.getLaunchIntentForPackage(packageName)
    startActivity(intent)

CLOSE_APP(packageName):
    Runtime.exec("am force-stop $packageName")

CLEAR_APP_DATA(packageName):
    Runtime.exec("pm clear $packageName")

GET_FOREGROUND_APP():
    // From accessibility events — track TYPE_WINDOW_STATE_CHANGED
    return lastForegroundPackage

IS_APP_RUNNING(packageName):
    activityManager.getRunningAppProcesses()
        .any { it.processName == packageName }

INSTALL_APP(apkPath):
    Runtime.exec("pm install -r $apkPath")

GET_INSTALLED_APPS():
    packageManager.getInstalledApplications(0)
        .filter { !isSystemApp(it) }
        .map { it.packageName }
```

**Device State Control (via shell commands / Settings API):**
```
SET_ORIENTATION(orientation):
    // Disable auto-rotate then set
    Settings.System.putInt(contentResolver, ACCELEROMETER_ROTATION, 0)
    Settings.System.putInt(contentResolver, USER_ROTATION, orientation)

SET_WIFI(enabled):
    Runtime.exec("svc wifi ${if(enabled) "enable" else "disable"}")

SET_AIRPLANE_MODE(enabled):
    Runtime.exec("cmd connectivity airplane-mode ${if(enabled) "enable" else "disable"}")

SET_LOCATION(lat, lng):
    // Emulator only — uses telnet or adb emu commands
    Runtime.exec("adb emu geo fix $lng $lat")

SET_VOLUME(level):
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
```

#### 2.1.5 Screenshot Pipeline

**Approach: MediaProjection + ImageReader (no root required)**

The companion app requests `MediaProjection` permission on setup (one-time user approval). This creates a `VirtualDisplay` that mirrors the screen content into an `ImageReader` surface.

```
SCREENSHOT PIPELINE:

1. MediaProjection → VirtualDisplay → ImageReader (Surface)
   └── ImageReader.OnImageAvailableListener fires on every VSYNC

2. On capture request:
   └── acquireLatestImage() → Image (hardware buffer)
       └── Image.getPlanes()[0] → ByteBuffer (RGBA pixels)

3. Encoding (C++ via JNI for speed):
   └── libjpeg-turbo: RGBA → JPEG (hardware-accelerated on ARM via NEON)
       └── Quality 80: ~50KB for 1080p (good enough for AI vision)
       └── Quality 40: ~20KB (thumbnail mode for fast iteration)

4. Optional delta encoding:
   └── Compare current frame block-by-block with previous
       └── Only encode changed 64x64 blocks
       └── 80-95% bandwidth reduction for static UIs

5. Send via TCP:
   └── Length-prefixed: [4 bytes length][image bytes]
```

**Performance Targets:**
- Full frame capture: <30ms (MediaProjection → buffer)
- JPEG encode (1080p): <20ms (libjpeg-turbo JNI)
- TCP transfer (local): <10ms (5MB at 500MB/s loopback)
- **Total: <60ms** (vs 300-500ms for ADB screencap)

**Alternative for emulators:** Use `adb exec-out screencap -p` as fallback if MediaProjection is unavailable (no user present to approve). This is slower (~200ms) but fully automated.

#### 2.1.6 Keep-Alive Strategy

Android aggressively kills background services. Our strategy:

1. **Foreground Service** with persistent notification (cannot be killed by system)
2. **Battery optimization exemption** — prompt user during setup to exclude NeuralBridge
3. **Watchdog coroutine** — monitors TCP server health, restarts if needed
4. **Partial wake lock** — only held during active automation sessions
5. **AccessibilityService itself** is system-managed and highly resilient — Android rarely kills accessibility services as they're considered critical

---

### 2.2 MCP Server (Host Machine)

The MCP Server runs on the developer's machine. It translates MCP tool calls from AI agents into binary protocol commands sent to the companion app.

#### 2.2.1 Why Rust

| Factor | Rust | Go | TypeScript |
|--------|------|-----|-----------|
| Binary size | ~5MB | ~10MB | ~50MB+ (Node) |
| Startup time | <10ms | <20ms | ~500ms |
| Memory for 10 devices | ~30MB | ~50MB | ~200MB |
| Async TCP performance | tokio (excellent) | goroutines (excellent) | libuv (good) |
| MCP SDK | `rmcp` (official) | `go-sdk` (official, Google) | `@modelcontextprotocol/sdk` |
| Protobuf support | `prost` (native) | `protoc-gen-go` (native) | `protobufjs` |
| Error handling | Compile-time (Result<T>) | Runtime (error return) | Runtime (exceptions) |

**Decision: Rust** — smallest binary, fastest startup, compile-time safety for a long-running server handling multiple concurrent device connections. The `rmcp` crate provides official MCP support with `#[tool]` macros.

#### 2.2.2 MCP Tool Definitions

`[REFERENCE ONLY - 50+ TOOL DEFINITIONS]`

Every tool has a rich description optimized for LLM context. Here's the complete tool registry:

**OBSERVE Tools (read device state):**

```
android_get_ui_tree
  Description: "Get the complete UI hierarchy of the current screen as a semantic tree.
    Returns elements with type, text, bounds, state (clickable, enabled, checked, etc).
    Use this to understand what's on screen before taking actions."
  Parameters: { maxDepth?: number, filter?: "interactive" | "all" }
  Returns: SemanticUITree (JSON)

android_screenshot
  Description: "Capture the current screen as a JPEG image.
    Use when you need to see visual layout, colors, images, or verify visual state."
  Parameters: { quality?: "full" | "half" | "thumbnail", format?: "jpeg" | "png" }
  Returns: base64 image

android_find_elements
  Description: "Find UI elements matching a selector.
    Supports finding by: text, contentDescription, resourceId, type, or any combination."
  Parameters: { text?: string, description?: string, resourceId?: string,
                type?: string, clickable?: boolean, scrollable?: boolean }
  Returns: Element[] with bounds and state

android_get_element_info
  Description: "Get detailed info about a specific element by its stable ID."
  Parameters: { elementId: string }
  Returns: Full element details including available actions

android_get_notifications
  Description: "Read all current notifications. Returns title, text, app, and available actions."
  Parameters: {}
  Returns: Notification[]

android_get_clipboard
  Description: "Read current clipboard text content."
  Parameters: {}
  Returns: { text: string }

android_is_keyboard_visible
  Description: "Check if the soft keyboard is currently showing."
  Parameters: {}
  Returns: { visible: boolean, height: number }

android_get_foreground_app
  Description: "Get the package name and activity of the currently visible app."
  Parameters: {}
  Returns: { packageName: string, activityName: string }

android_get_device_info
  Description: "Get device details: screen size, density, Android version, orientation."
  Parameters: {}
  Returns: DeviceInfo

android_get_toast
  Description: "Get the most recent toast message shown (if any, within last 3 seconds)."
  Parameters: {}
  Returns: { text: string, timestamp: number } | null
```

**ACT Tools (interact with device):**

```
android_tap
  Description: "Tap on an element. Provide EITHER coordinates OR a selector.
    Prefer selectors (text/description) over coordinates for reliability."
  Parameters: { x?: number, y?: number, text?: string,
                description?: string, resourceId?: string, elementId?: string }
  Returns: { success: boolean }

android_long_press
  Description: "Long press on an element. Triggers context menus, drag mode, etc."
  Parameters: { x?: number, y?: number, text?: string, durationMs?: number }
  Returns: { success: boolean }

android_double_tap
  Description: "Double tap on an element. Used for zoom, text selection, etc."
  Parameters: { x?: number, y?: number, text?: string }
  Returns: { success: boolean }

android_swipe
  Description: "Swipe from one point to another.
    For scrolling: swipe up to scroll down, swipe down to scroll up."
  Parameters: { startX: number, startY: number, endX: number, endY: number,
                durationMs?: number }
  Returns: { success: boolean }

android_scroll_to_element
  Description: "Scroll within a container until the target element is visible.
    Automatically determines scroll direction and container."
  Parameters: { text?: string, description?: string, resourceId?: string,
                maxScrolls?: number, container?: string }
  Returns: { found: boolean, element?: Element }

android_fling
  Description: "Fast fling gesture (like a quick scroll with momentum)."
  Parameters: { direction: "up" | "down" | "left" | "right", speed?: "slow" | "medium" | "fast" }
  Returns: { success: boolean }

android_pinch
  Description: "Pinch gesture for zoom in/out. Scale > 1 = zoom in, < 1 = zoom out."
  Parameters: { centerX: number, centerY: number, scale: number }
  Returns: { success: boolean }

android_drag
  Description: "Drag an element from one position to another (long press then move)."
  Parameters: { fromX: number, fromY: number, toX: number, toY: number }
  Returns: { success: boolean }

android_input_text
  Description: "Type text into the currently focused input field, or into a field matching the selector."
  Parameters: { text: string, field?: string, append?: boolean }
  Returns: { success: boolean }

android_select_text
  Description: "Select text within an editable field."
  Parameters: { elementId?: string, start: number, end: number }
  Returns: { success: boolean }

android_set_clipboard
  Description: "Set clipboard content."
  Parameters: { text: string }
  Returns: { success: boolean }

android_press_key
  Description: "Press a hardware or soft key."
  Parameters: { key: "back" | "home" | "recents" | "enter" | "tab" | "delete"
                    | "volume_up" | "volume_down" | "power" | number }
  Returns: { success: boolean }

android_hide_keyboard
  Description: "Dismiss the soft keyboard if visible."
  Parameters: {}
  Returns: { success: boolean }

android_multi_gesture
  Description: "Execute a custom multi-touch gesture with multiple simultaneous touch points."
  Parameters: { touches: TouchPoint[] }
  Returns: { success: boolean }
```

**MANAGE Tools (control device and apps):**

```
android_launch_app
  Description: "Launch an app by package name."
  Parameters: { packageName: string }
  Returns: { success: boolean }

android_close_app
  Description: "Force stop an app."
  Parameters: { packageName: string }
  Returns: { success: boolean }

android_clear_app_data
  Description: "Clear all data for an app (like a fresh install). WARNING: destroys user data."
  Parameters: { packageName: string }
  Returns: { success: boolean }

android_install_app
  Description: "Install an APK file on the device."
  Parameters: { apkPath: string }
  Returns: { success: boolean }

android_get_installed_apps
  Description: "List all user-installed apps."
  Parameters: { includeSystem?: boolean }
  Returns: App[]

android_open_url
  Description: "Open a URL or deep link in the default handler."
  Parameters: { url: string }
  Returns: { success: boolean }

android_open_notifications
  Description: "Pull down the notification shade."
  Parameters: {}
  Returns: { success: boolean }

android_close_notifications
  Description: "Dismiss the notification shade."
  Parameters: {}
  Returns: { success: boolean }

android_open_quick_settings
  Description: "Open the quick settings panel."
  Parameters: {}
  Returns: { success: boolean }

android_grant_permission
  Description: "Grant a runtime permission to an app."
  Parameters: { packageName: string, permission: string }
  Returns: { success: boolean }

android_set_orientation
  Description: "Set screen orientation."
  Parameters: { orientation: "portrait" | "landscape" | "auto" }
  Returns: { success: boolean }

android_set_wifi
  Description: "Enable or disable WiFi."
  Parameters: { enabled: boolean }
  Returns: { success: boolean }

android_set_location
  Description: "Mock GPS location (emulator only)."
  Parameters: { latitude: number, longitude: number }
  Returns: { success: boolean }

android_execute_shell
  Description: "Execute an ADB shell command on the device. Use with caution."
  Parameters: { command: string }
  Returns: { output: string, exitCode: number }
```

**WAIT Tools (synchronization):**

```
android_wait_for_element
  Description: "Wait until an element matching the selector appears on screen."
  Parameters: { text?: string, description?: string, resourceId?: string,
                timeoutMs?: number }
  Returns: { found: boolean, element?: Element }

android_wait_for_gone
  Description: "Wait until an element disappears from screen (e.g., loading spinner)."
  Parameters: { text?: string, description?: string, timeoutMs?: number }
  Returns: { gone: boolean }

android_wait_for_text
  Description: "Wait until specific text appears anywhere on screen."
  Parameters: { text: string, timeoutMs?: number }
  Returns: { found: boolean }

android_wait_for_idle
  Description: "Wait until all animations complete and UI is stable."
  Parameters: { timeoutMs?: number }
  Returns: { idle: boolean }
```

**WEBVIEW Tools (hybrid app support):**

```
android_get_webview_url
  Description: "Get the URL loaded in the visible WebView."
  Parameters: {}
  Returns: { url: string }

android_get_webview_dom
  Description: "Extract a simplified DOM from the WebView's accessibility tree."
  Parameters: {}
  Returns: WebViewDOM

android_execute_js
  Description: "Execute JavaScript in the WebView context."
  Parameters: { script: string }
  Returns: { result: string }
```

**TEST Tools (assertions and recording):**

```
android_assert_element
  Description: "Assert that an element exists and matches expected state."
  Parameters: { text?: string, exists?: boolean, enabled?: boolean,
                checked?: boolean, visible?: boolean }
  Returns: { passed: boolean, actual: Element | null }

android_screenshot_diff
  Description: "Compare current screen to a baseline screenshot."
  Parameters: { baselineBase64: string, threshold?: number }
  Returns: { matchPercent: number, diffRegions: Region[] }

android_start_recording
  Description: "Start screen recording."
  Parameters: { maxDurationSec?: number }
  Returns: { recordingId: string }

android_stop_recording
  Description: "Stop screen recording and save."
  Parameters: { recordingId: string }
  Returns: { filePath: string }

android_get_logcat
  Description: "Get recent logcat entries, optionally filtered."
  Parameters: { tag?: string, level?: string, lines?: number, packageName?: string }
  Returns: { entries: LogEntry[] }
```

#### 2.2.3 Semantic Element Resolver

When an AI agent calls `android_tap(text="Login")`, the MCP server needs to resolve "Login" to coordinates. The Semantic Engine handles this:

```
RESOLVE(selector) → Element:

    1. Exact text match: element.text == selector.text
    2. Partial text match: element.text.contains(selector.text)
    3. Content description match: element.contentDescription == selector.description
    4. Resource ID match: element.resourceId.endsWith(selector.resourceId)
    5. Combined match: all provided selectors must match
    6. Fuzzy match (fallback): Levenshtein distance < 3

    If multiple matches:
        - Prefer visible elements (isVisibleToUser = true)
        - Prefer interactive elements (isClickable = true)
        - Prefer elements closer to center of screen (more likely primary)
        - If still ambiguous: return error with all candidates for agent to choose
```

#### 2.2.4 Coordinate Normalization

Different devices have different screen sizes and densities. The MCP server normalizes:

```
NORMALIZE(rawX, rawY, deviceInfo) → (normalizedX, normalizedY):
    // Convert to density-independent pixels
    dpX = rawX / deviceInfo.density
    dpY = rawY / deviceInfo.density

DENORMALIZE(dpX, dpY, deviceInfo) → (rawX, rawY):
    rawX = dpX * deviceInfo.density
    rawY = dpY * deviceInfo.density
```

AI agents work in raw pixels (matching screenshot coordinates). The normalization happens only when porting actions across devices.

---

### 2.3 Binary Protocol

#### 2.3.1 Why Protobuf Over MessagePack

After research, **Protobuf** is the better choice for NeuralBridge:

| Factor | Protobuf | MessagePack |
|--------|----------|-------------|
| Schema validation | Compile-time (`.proto` files) | None (schema-less) |
| Kotlin support | `protobuf-kotlin` (official, excellent) | `msgpack-kotlin` (community) |
| Rust support | `prost` (excellent) | `rmp` (good) |
| Ser/deser speed | Faster (schema allows optimization) | Slightly slower |
| Message size | Slightly smaller (field numbers vs string keys) | Slightly larger |
| Evolution | Add fields without breaking | Manual compatibility |
| Code generation | Automatic data classes both sides | Manual struct definition |

The critical advantage: `.proto` files generate **identical data structures** in both Kotlin (companion) and Rust (MCP server), eliminating entire classes of serialization bugs.

#### 2.3.2 Protocol Definition

`[IMPLEMENTATION DETAIL - COMPLETE PROTOBUF SCHEMA]`

```protobuf
syntax = "proto3";
package neuralbridge;

// === Request Envelope ===
message Request {
    uint32 request_id = 1;  // For correlating async responses
    oneof command {
        GetUIRequest get_ui = 10;
        ScreenshotRequest screenshot = 11;
        TapRequest tap = 12;
        LongPressRequest long_press = 13;
        DoubleTapRequest double_tap = 14;
        SwipeRequest swipe = 15;
        FlingRequest fling = 16;
        PinchRequest pinch = 17;
        DragRequest drag = 18;
        InputTextRequest input_text = 19;
        SelectTextRequest select_text = 20;
        PressKeyRequest press_key = 21;
        LaunchAppRequest launch_app = 22;
        CloseAppRequest close_app = 23;
        FindElementsRequest find_elements = 24;
        WaitForRequest wait_for = 25;
        GetClipboardRequest get_clipboard = 26;
        SetClipboardRequest set_clipboard = 27;
        GetNotificationsRequest get_notifications = 28;
        GlobalActionRequest global_action = 29;
        ShellCommandRequest shell_command = 30;
        ScrollToElementRequest scroll_to_element = 31;
        MultiGestureRequest multi_gesture = 32;
        SetOrientationRequest set_orientation = 33;
        GetDeviceInfoRequest get_device_info = 34;
        HeartbeatRequest heartbeat = 99;
    }
}

// === Response Envelope ===
message Response {
    uint32 request_id = 1;
    bool success = 2;
    string error_code = 3;     // Structured error code
    string error_message = 4;  // Human-readable error
    oneof result {
        UITree ui_tree = 10;
        ScreenshotResult screenshot = 11;
        ElementList elements = 12;
        ClipboardContent clipboard = 13;
        NotificationList notifications = 14;
        DeviceInfo device_info = 15;
        ShellResult shell_result = 16;
        bytes raw_data = 50;
    }
}

// === Event (pushed from device) ===
message Event {
    uint64 timestamp = 1;
    oneof event {
        UIChangedEvent ui_changed = 10;
        AppChangedEvent app_changed = 11;
        ToastEvent toast = 12;
        KeyboardEvent keyboard = 13;
        NotificationEvent notification = 14;
    }
}

// === UI Tree ===
message UITree {
    repeated UIElement elements = 1;
    uint64 timestamp = 2;
    string foreground_package = 3;
    string foreground_activity = 4;
}

message UIElement {
    string id = 1;
    string type = 2;            // Semantic type: Button, TextInput, etc.
    string text = 3;
    string content_description = 4;
    string hint = 5;
    string resource_id = 6;
    Bounds bounds = 7;
    Point center = 8;
    uint32 depth = 9;

    bool is_clickable = 20;
    bool is_long_clickable = 21;
    bool is_scrollable = 22;
    bool is_checkable = 23;
    bool is_checked = 24;
    bool is_focusable = 25;
    bool is_focused = 26;
    bool is_editable = 27;
    bool is_enabled = 28;
    bool is_selected = 29;
    bool is_password = 30;
    bool is_visible = 31;

    string ai_description = 40;
    repeated string available_actions = 41;
    repeated UIElement children = 50;
}

message Bounds {
    int32 left = 1;
    int32 top = 2;
    int32 right = 3;
    int32 bottom = 4;
}

message Point {
    int32 x = 1;
    int32 y = 2;
}

// === Command Messages (selected examples) ===

message TapRequest {
    oneof target {
        Point coordinates = 1;
        ElementSelector selector = 2;
    }
}

message SwipeRequest {
    Point start = 1;
    Point end = 2;
    uint32 duration_ms = 3;
}

message PinchRequest {
    Point center = 1;
    float scale = 2;  // >1 = zoom in, <1 = zoom out
    uint32 duration_ms = 3;
}

message DragRequest {
    Point from = 1;
    Point to = 2;
    uint32 duration_ms = 3;
}

message MultiGestureRequest {
    repeated TouchPath touches = 1;
    message TouchPath {
        repeated Point waypoints = 1;
        uint32 start_time_ms = 2;
        uint32 duration_ms = 3;
    }
}

message ElementSelector {
    optional string text = 1;
    optional string content_description = 2;
    optional string resource_id = 3;
    optional string type = 4;
    optional string element_id = 5;
    optional bool clickable = 6;
    optional int32 index = 7;  // nth match
}

message ScrollToElementRequest {
    ElementSelector target = 1;
    optional ElementSelector container = 2;
    uint32 max_scrolls = 3;
    string direction = 4;  // "down", "up", "left", "right"
}
```

#### 2.3.3 Wire Format

```
FRAME FORMAT (both directions):

┌─────────────────┬──────────────┬─────────────────┬──────────────────────────┐
│ Magic (2 bytes) │ Type (1 byte)│ Length (4 bytes) │ Protobuf Payload (N bytes)│
│    0x4E42       │  0x01-0x03   │   big-endian    │  Request/Response/Event   │
└─────────────────┴──────────────┴─────────────────┴──────────────────────────┘

Magic bytes "NB" (0x4E42) allow quick validation.

Type field:
  0x01 = Request (MCP Server → Companion App)
  0x02 = Response (Companion App → MCP Server)
  0x03 = Event (Companion App → MCP Server, pushed)

Length excludes the 7-byte header.
Max message size: 16MB (sufficient for screenshots).
```

#### 2.3.4 Connection Setup

```
CONNECTION LIFECYCLE:

1. MCP Server starts → listens for MCP clients (stdio/SSE)
2. AI agent connects → MCP Server discovers devices via `adb devices`
3. For each device:
   a. adb forward tcp:${localPort} tcp:38472  (NeuralBridge port)
   b. TCP connect to localhost:${localPort}
   c. Handshake: send version, receive capabilities
   d. Connection established — ready for commands
4. Commands are async — multiple requests can be in-flight
5. Events stream continuously on the same connection
6. Heartbeat every 5 seconds — reconnect if missed 3x
```

---

### 2.4 Error Taxonomy

`[REFERENCE ONLY - COMPLETE ERROR CODE LISTING]`

Every error returned to the AI agent must be **actionable**. The agent needs to know what went wrong and what to try next.

```
ERROR CODES:

ELEMENT_NOT_FOUND       "No element matching selector {selector}. Try screenshot + get_ui_tree to see current state."
ELEMENT_NOT_VISIBLE     "Element exists but is off-screen. Try scroll_to_element first."
ELEMENT_NOT_INTERACTABLE "Element is disabled or not clickable. Check isEnabled state."
ELEMENT_AMBIGUOUS       "Multiple elements match selector. Candidates: [...]. Add more specificity."
TIMEOUT                 "Action did not complete within {timeout}ms. UI may be loading."
APP_NOT_INSTALLED       "Package {pkg} not found. Use get_installed_apps to check."
APP_CRASHED             "App {pkg} crashed during action. Logcat: {last_crash_line}"
DEVICE_DISCONNECTED     "Lost connection to device. Attempting reconnect..."
KEYBOARD_REQUIRED       "No input field is focused. Tap on a text field first."
PERMISSION_DENIED       "App requires permission {perm}. Use grant_permission tool."
DEVICE_LOCKED           "Device screen is locked. Cannot interact."
SERVICE_NOT_RUNNING     "NeuralBridge companion service is not active on device."
INVALID_COORDINATES     "Coordinates ({x},{y}) are outside screen bounds ({w}x{h})."
GESTURE_FAILED          "Gesture was cancelled by the system. Another touch may be in progress."
```

---

## 3. Data Flow: Complete Action-Observe Loop

Here's what happens when an AI agent asks "Tap the Login button":

```
STEP 1: AI Agent → MCP Server (MCP Protocol)
    Tool call: android_tap(text="Login")
    Time: <1ms (stdio pipe)

STEP 2: MCP Server resolves selector
    - Check cached UI tree for element with text="Login"
    - If found: get center coordinates (240, 850)
    - If not found: send GET_UI to device, search result
    Time: <1ms (cache hit) or <50ms (cache miss)

STEP 3: MCP Server → Companion App (Protobuf over TCP)
    Send: Request { tap: TapRequest { coordinates: Point(240, 850) } }
    Time: <5ms (localhost TCP + protobuf encode/decode)

STEP 4: Companion App executes gesture
    - Build GestureDescription with single tap path
    - dispatchGesture() → MotionEventInjector
    - Wait for GestureResultCallback.onCompleted()
    Time: <50ms (gesture dispatch + execution)

STEP 5: Companion App → MCP Server (event push)
    - AccessibilityService.onAccessibilityEvent(TYPE_VIEW_CLICKED)
    - New UI tree computed, delta sent as event
    Time: <30ms (tree walk + delta + encode + send)

STEP 6: MCP Server → AI Agent (MCP response)
    Return: { success: true }
    Time: <1ms

TOTAL: ~90ms end-to-end (vs 1000-2000ms with Appium/ADB)
```

---

## 4. Setup & Deployment

### First-Time Setup (< 5 minutes)

```
SETUP FLOW:

1. Developer installs MCP Server
   $ cargo install neuralbridge-mcp
   # OR: download binary from releases

2. Developer installs Companion App on device
   $ adb install neuralbridge-companion.apk

3. Enable Accessibility Service
   - App opens Settings → Accessibility automatically
   - User taps to enable NeuralBridge service
   - One-time MediaProjection permission grant (for screenshots)

4. Configure AI agent
   # claude_desktop_config.json
   {
     "mcpServers": {
       "android": {
         "command": "neuralbridge-mcp",
         "args": ["--auto-discover"]
       }
     }
   }

5. Verify
   $ neuralbridge-mcp --check
   ✓ Device found: Pixel 8 (emulator-5554)
   ✓ Companion app v1.0.0 running
   ✓ Accessibility service active
   ✓ Screenshot permission granted
   ✓ TCP connection established (port 38472)
   ✓ Round-trip latency: 8ms
   Ready.
```

### CI/CD Headless Setup

```bash
# Docker with Android emulator
FROM neuralbridge/ci:latest

# Start emulator
emulator -avd test_device -no-window -no-audio &
adb wait-for-device

# Install and enable companion (automated via adb)
adb install neuralbridge-companion.apk
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1

# Run MCP server
neuralbridge-mcp --device emulator-5554 --headless
```

---

## 5. Technology Decisions Summary

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| MCP Server | Rust + `rmcp` + `tokio` | Performance, safety, small binary, official SDK |
| Companion App | Kotlin + Coroutines | Native Android, modern async, accessibility API access |
| Screenshot JNI | C++ + libjpeg-turbo | Hardware-accelerated encoding, 3-5x faster than Java |
| Binary Protocol | Protobuf 3 | Schema validation, code generation, smallest wire format |
| Transport | TCP over ADB port-forward | No USB driver hassle, works with wireless ADB too |
| UI Capture | AccessibilityService | In-process speed, event-driven, no root, cross-app |
| Gesture Injection | dispatchGesture() | Multi-touch support, system-level injection, API 24+ |
| Screenshot Capture | MediaProjection + ImageReader | No root, all windows including system UI |

---

## 6. What Existing Solutions Get Wrong (And How We Fix It)

| Existing Tool | Their Approach | Our Approach | Speedup |
|--------------|---------------|-------------|---------|
| **Appium** | WebDriver → HTTP → UIAutomator2 → IPC | MCP → TCP → AccessibilityService (in-process) | 10-20x |
| **mobile-mcp** | ADB shell commands, polling | Binary protocol, event-driven | 5-10x |
| **Android-MCP** | Python + UIAutomator2 + ADB | Rust + Kotlin + Protobuf | 5-15x |
| **scrcpy** | H.264 video stream (designed for humans) | Targeted JPEG capture (designed for AI) | 2-3x for single frames |

The fundamental problem with every existing solution: they all route through **ADB** for actions, which means every command is a separate process spawn + USB/TCP round trip. NeuralBridge keeps a **persistent in-process connection** to a service that can already see and touch everything.

---

## 7. Risk Mitigation

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| Android restricts AccessibilityService API | Medium | Critical | Monitor Android developer previews, maintain ADB fallback path |
| MediaProjection requires user interaction | Known | Medium | Support ADB screencap fallback for CI/CD; pursue Device Admin API for enterprise |
| dispatchGesture max 10 strokes | Low | Low | 10 strokes covers any realistic gesture; chain gestures for edge cases |
| Battery drain from foreground service | Medium | Medium | Partial wake lock only during sessions; aggressive idle detection |
| Android 16+ may add new restrictions | Medium | Medium | Active participation in Android beta program; maintain backward compatibility |
| Protobuf version conflicts | Low | Low | Bundle specific protobuf version; no dynamic linking |

---

## 7.5 Platform Restrictions and Workarounds

### Overview

While NeuralBridge provides comprehensive Android automation capabilities, certain platform restrictions require architectural workarounds. This section documents known limitations and their solutions.

### 1. Shell Command Execution

**Restriction:** `Runtime.exec()` from the companion app does not have elevated privileges.

**Impact:**
Privileged shell commands fail with "Permission denied" when executed from within the companion app:
- `pm install` / `pm uninstall` (package management)
- `pm clear` (clear app data)
- `am force-stop` (force-stop apps)
- `svc wifi enable/disable` (network control)
- `pm grant` (permission management)

**Why it fails:**
```kotlin
// This does NOT work from companion app:
Runtime.getRuntime().exec("pm clear com.example.app")
// Result: java.io.IOException: Cannot run program "pm": error=13, Permission denied
```

Android's security model restricts shell command privileges based on the calling app's UID. The companion app runs as a regular user app and cannot execute privileged operations.

**Workaround:**
Route these commands through the MCP server's ADB connection:
```rust
// In MCP server:
async fn clear_app_data(package: &str, device: &Device) -> Result<()> {
  device.adb_shell(&format!("pm clear {}", package)).await
}
```

**Performance impact:** ADB commands add 200-500ms latency vs in-process operations (<100ms).

---

### 2. Clipboard Access (Android 10+)

**Restriction:** Background clipboard access blocked since Android 10.

**Official Android documentation:**
> "Unless your app is the default IME or has focus, it cannot access clipboard on Android 10 (API level 29) and higher."

**Impact:**
The companion app cannot read clipboard content unless it has input focus:
```kotlin
val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
val clip = clipboardManager.primaryClip // Returns null if app has no focus
```

**Use cases affected:**
- Reading copied text from other apps
- Clipboard-based automation triggers
- Copy-paste verification in testing

**Workaround:**
Use ADB shell command from MCP server:
```bash
adb shell cmd clipboard get-text
```

```rust
// In MCP server:
async fn get_clipboard(device: &Device) -> Result<String> {
  let output = device.adb_shell("cmd clipboard get-text").await?;
  Ok(output.trim().to_string())
}
```

**Performance impact:** 200-300ms (ADB round-trip) vs <10ms (in-process).

---

### 3. MediaProjection Consent (Android 14+)

**Restriction:** MediaProjection permission model changed to single-use consent.

**Timeline:**
- **Android 13 and earlier:** Permission persists across app restarts
- **Android 14+:** Permission valid only for current session, resets on:
  - App process killed
  - Device restart
  - App update/reinstall

**Official Android documentation:**
> "Starting in Android 14, users must give consent each time an app wants to start a screen capture session."

**Impact:**
- Cannot automate screenshots in CI/CD on Android 14+ without user interaction
- First screenshot in each session requires user approval dialog
- Headless emulator testing requires fallback mechanism

**Workaround:**
Implement fallback to ADB screencap:

```kotlin
class ScreenshotProvider {
  fun capture(): Bitmap {
    return when {
      mediaProjectionAvailable() -> {
        captureViaMediaProjection() // 40-80ms, preferred
      }
      adbConnected() -> {
        captureViaAdb() // 150-300ms, CI/CD fallback
      }
      else -> {
        throw ScreenshotUnavailableException()
      }
    }
  }

  private fun captureViaAdb(): Bitmap {
    Runtime.exec("screencap -p /sdcard/temp_screenshot.png").waitFor()
    val file = File("/sdcard/temp_screenshot.png")
    return BitmapFactory.decodeFile(file.absolutePath)
  }
}
```

**Performance impact:**
- MediaProjection: 40-80ms
- ADB screencap: 150-300ms (3-5x slower)

**CI/CD strategy:**
```bash
# Dockerfile for CI environment
if [ "$API_LEVEL" -ge 34 ]; then
  export NEURALBRIDGE_SCREENSHOT_MODE=adb
fi
```

---

### 4. Distribution Channels

**Restriction:** Google Play Store distribution not viable for AccessibilityService apps.

**Google Play policy (Accessibility Services section):**
> "AccessibilityService APIs are only permitted for apps that help users with disabilities use Android devices. Apps that use these APIs without a clear use case for improving accessibility may be removed from Google Play."

**Why NeuralBridge is affected:**
- Broad accessibility permissions (all events, window content, gestures)
- Primary use case is automation, not disability assistance
- Would violate policy even with clear documentation

**Distribution method:**
**Sideloading only** via direct APK installation:
```bash
adb install neuralbridge-companion.apk
```

**Android 15+ additional restriction:**
After installation, users must manually enable "Allow restricted settings":
1. Install APK via `adb install` or file manager
2. Launch app and attempt to enable AccessibilityService
3. System shows: "Restricted setting - This app may not be able to use this setting"
4. Navigate to: **Settings → Apps → NeuralBridge → Advanced**
5. Enable: **"Allow restricted settings"**
6. Return to Accessibility settings and enable service

**No workaround:** This is a security-by-design restriction to prevent malware abuse.

---

### 5. Notification Content Access

**Restriction:** AccessibilityService provides limited notification information.

**What AccessibilityService provides:**
- Event type: `TYPE_NOTIFICATION_STATE_CHANGED`
- Basic detection that a notification was posted/removed
- No content details (title, text, actions)

**What's missing:**
- Notification title and body text
- Action button labels and intents
- Reply input fields
- Icons and images
- Originating package name (sometimes)

**Workaround:**
Implement separate `NotificationListenerService`:

```kotlin
class NeuralBridgeNotificationListener : NotificationListenerService() {
  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val notification = sbn.notification
    val extras = notification.extras

    val title = extras.getString(Notification.EXTRA_TITLE)
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)
    val actions = notification.actions?.map { it.title.toString() } ?: emptyList()

    // Send to MCP server via TCP
    sendNotificationEvent(NotificationEvent(title, text, actions, sbn.packageName))
  }
}
```

**AndroidManifest.xml:**
```xml
<service
  android:name=".NeuralBridgeNotificationListener"
  android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
  <intent-filter>
    <action android:name="android.service.notification.NotificationListenerService" />
  </intent-filter>
</service>
```

**Requires separate permission grant:**
- Navigate to: **Settings → Notifications → Notification access**
- Enable **NeuralBridge** in the list

**Impact:** Two separate service implementations and two permission grants required for full functionality.

---

### Architecture Decision: Dual Command Path

NeuralBridge uses **two distinct command routing paths** based on privilege requirements.

#### Fast Path (<100ms): Companion App via AccessibilityService

**Routed operations:**
- **Gestures:** tap, long_press, swipe, pinch, drag, multi-gesture, fling
- **UI Observation:** get_ui_tree, find_elements, get_element_info, wait_for_element
- **Screenshots:** MediaProjection → ImageReader → JPEG (when available)
- **Input:** input_text, select_text via AccessibilityNodeInfo.performAction()
- **Global Actions:** back, home, recents, notifications, quick_settings
- **App Launch:** startActivity with package manager launch intent

**Why it's fast:**
- In-process execution (no IPC overhead)
- Direct AccessibilityService API access
- Hardware-accelerated operations (gesture injection, JPEG encoding)

**Latency profile:**
- Gestures: 20-50ms
- UI tree (simple): 30-80ms
- Screenshots (warm): 40-80ms

---

#### Slow Path (200-500ms): MCP Server via ADB Shell

**Routed operations:**
- **App Management:**
  - `install_app` → `adb install <apk>`
  - `clear_app_data` → `adb shell pm clear <package>`
  - `close_app` (force-stop) → `adb shell am force-stop <package>`
- **Permissions:** `grant_permission` → `adb shell pm grant <package> <permission>`
- **Device Settings:**
  - `set_wifi` → `adb shell svc wifi enable/disable`
  - `set_bluetooth` → `adb shell svc bluetooth enable/disable`
  - `set_airplane_mode` → `adb shell cmd connectivity airplane-mode enable/disable`
- **Clipboard (Android 10+):** `get_clipboard` → `adb shell cmd clipboard get-text`
- **Screenshot Fallback:** `adb exec-out screencap -p` (when MediaProjection unavailable)

**Why it's slow:**
- ADB protocol overhead (USB/TCP round-trip)
- Shell command parsing and execution on device
- Process spawn for each command

**Latency profile:**
- Simple commands (pm grant): 200-400ms
- App install: 500-1000ms (includes APK transfer)
- Screenshot via ADB: 150-300ms

---

#### Command Routing Logic

```rust
// In MCP server tool router:
fn route_command(cmd: &Command, device: &Device) -> CommandPath {
  match cmd {
    // Fast path: direct to companion app
    Command::Tap(_) | Command::Swipe(_) | Command::GetUI(_) => {
      CommandPath::Companion(device.tcp_connection())
    }

    // Slow path: through ADB
    Command::InstallApp(_) | Command::ClearAppData(_) | Command::GrantPermission(_) => {
      CommandPath::Adb(device.adb_connection())
    }

    // Conditional: try fast, fall back to slow
    Command::Screenshot(_) => {
      if device.media_projection_available() {
        CommandPath::Companion(device.tcp_connection())
      } else {
        CommandPath::Adb(device.adb_connection())
      }
    }

    Command::GetClipboard(_) => {
      if device.android_version() >= 10 {
        CommandPath::Adb(device.adb_connection()) // Restriction
      } else {
        CommandPath::Companion(device.tcp_connection()) // Legacy support
      }
    }
  }
}
```

---

#### Performance Implications

**Achievable <100ms latency for:**
- All gesture operations
- UI tree reading (simple screens)
- Screenshot capture (with warm MediaProjection)
- Text input and selection
- Element finding (with cached UI tree)

**NOT achievable <100ms for:**
- App installation/uninstallation (500-1000ms)
- App data clearing (300-500ms)
- Permission granting (200-400ms)
- Network state changes (200-500ms)
- Clipboard reading on Android 10+ (200-300ms)
- Complex UI trees with 1000+ nodes (100-500ms)
- First screenshot after cold start (150-300ms)

**Design principle:**
> Privileged operations are intentionally routed through the MCP server's ADB connection rather than attempting them from the companion app, where they would fail silently or require dangerous root access. This architectural decision trades latency (200-500ms overhead) for reliability and security.

---

### Summary Table

| Operation Type | Path | Latency | Limitation | Workaround |
|---------------|------|---------|------------|------------|
| Gestures | Fast (Companion) | 20-80ms | None | N/A |
| UI Tree | Fast (Companion) | 30-100ms | Complex trees slower | Cache aggressively |
| Screenshots | Fast (Companion) | 40-80ms | Android 14+ consent | ADB fallback (150-300ms) |
| App Install | Slow (ADB) | 500-1000ms | Requires privileges | No alternative |
| Clear App Data | Slow (ADB) | 300-500ms | Requires privileges | No alternative |
| Grant Permission | Slow (ADB) | 200-400ms | Requires privileges | No alternative |
| Network Control | Slow (ADB) | 200-500ms | Requires privileges | No alternative |
| Clipboard (Android 10+) | Slow (ADB) | 200-300ms | Background restriction | No alternative |

For a complete list of impossible user flows and app compatibility information, see [`docs/limitations.md`](limitations.md).

---

## 8. Development Phases

### Phase 1: Core (Weeks 1-6) — MVP
- Companion app with AccessibilityService + TCP server
- Basic MCP server with 15 core tools (tap, swipe, input, screenshot, get_ui, launch_app, press_key, find_elements, wait_for, get_foreground, get_clipboard, set_clipboard, open_url, press_back/home)
- Protobuf protocol for all messages
- Single device support
- ADB port-forward connection

### Phase 2: Full Gesture Suite (Weeks 7-9)
- All gesture types (long_press, double_tap, pinch, drag, fling, multi_gesture)
- scroll_to_element with automatic container detection
- Text selection and cursor control
- Keyboard show/hide control
- Notification reading and interaction

### Phase 3: Intelligence (Weeks 10-12)
- Semantic element resolver with fuzzy matching
- Smart wait system (wait_for_idle, wait_for_gone, wait_for_text)
- Event streaming from device to MCP server
- UI state caching and delta computation
- Screenshot delta encoding

### Phase 4: Enterprise (Weeks 13-16)
- Multi-device support with connection pooling
- WebView DOM extraction and JS execution
- CI/CD Docker image
- Visual diff and assertion tools
- Screen recording
- Logcat capture
- Shell command execution
- Device state control (orientation, wifi, location)

---

*This architecture gives AI agents the complete set of human-equivalent capabilities: see the screen (UI tree + screenshots), touch anything (all gesture types), type text (input + clipboard + selection), handle interruptions (notifications, dialogs, permissions), manage apps (launch, close, install, clear data), control device state (orientation, connectivity), and verify results (assertions, visual diff, wait conditions) — all at sub-100ms latency through a single persistent connection.*
