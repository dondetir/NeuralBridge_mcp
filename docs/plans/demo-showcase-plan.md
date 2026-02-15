# NeuralBridge Demo Showcase Plan

## Goal
Redesign the Python MCP demo client to showcase **every single MCP tool (36 tools)** using default apps on the Samsung Galaxy S9+ (Android 10). Each scenario tells a compelling story while exercising specific tools.

## Target Device
- **Samsung Galaxy S9+** (SM-G965U), Android 10, SDK 29
- Physical device via USB (transport_id: 13)

## Available Default Apps (Verified)

| App | Package Name |
|-----|-------------|
| Settings | `com.android.settings` |
| Samsung Contacts | `com.samsung.android.contacts` |
| Samsung Dialer | `com.samsung.android.dialer` |
| Samsung Messages | `com.samsung.android.messaging` |
| Samsung Calendar | `com.samsung.android.calendar` |
| Samsung Camera | `com.sec.android.app.camera` |
| Samsung Gallery | `com.sec.android.gallery3d` |
| Samsung Clock | `com.sec.android.app.clockpackage` |
| Chrome | `com.android.chrome` |
| Files | `com.android.documentsui` |
| Samsung Internet | `com.sec.android.app.sbrowser` |
| Google Photos | `com.google.android.apps.photos` |
| YouTube | `com.google.android.youtube` |

## Tool Coverage Matrix

### Currently Demoed (22 tools) → Need to add 14 more

**Missing tools to incorporate:**
1. `android_wait_for_element`
2. `android_wait_for_gone`
3. `android_scroll_to_element`
4. `android_get_device_info`
5. `android_get_screen_context`
6. `android_clear_app_data`
7. `android_capture_logcat`
8. `android_screenshot_diff`
9. `android_get_recent_toasts`
10. `android_pull_to_refresh`
11. `android_dismiss_keyboard`
12. `android_accessibility_audit`
13. `android_list_devices`
14. `android_select_device`

---

## Scenario Redesign (12 Scenarios)

### Scenario 1: "Device Discovery & Inspection" (~1 min)
**Story:** First contact — discover device, inspect capabilities, take baseline screenshot.

**Tools exercised (5):**
- `android_list_devices` — discover connected devices
- `android_select_device` — connect to the S9+
- `android_get_device_info` — show manufacturer, model, Android version, screen size
- `android_get_screen_context` — combined snapshot (app + UI tree + screenshot)
- `android_screenshot` — full quality baseline capture

**App:** Home screen / whatever is currently open

---

### Scenario 2: "Settings Deep Dive" (~3 min)
**Story:** Navigate Android Settings — scroll through menus, search, toggle switches, verify changes.

**Tools exercised (8):**
- `android_launch_app` — open Settings
- `android_get_ui_tree` — inspect settings hierarchy
- `android_scroll_to_element` — find "About phone" buried at bottom
- `android_tap` — tap into "About phone"
- `android_find_elements` — find all text elements showing device info
- `android_fling` — fast-scroll back to top
- `android_press_key` — press Back to return
- `android_screenshot_diff` — compare settings screen before/after navigation

**App:** `com.android.settings`

---

### Scenario 3: "Contact Creation Workflow" (~3 min)
**Story:** Create a new contact end-to-end — open Contacts, fill form, save, verify, clean up.

**Tools exercised (9):**
- `android_launch_app` — open Contacts
- `android_wait_for_element` — wait for contact list to load
- `android_tap` — tap FAB "Add contact"
- `android_input_text` — fill name, phone, email fields
- `android_dismiss_keyboard` — hide keyboard after typing
- `android_screenshot` — capture filled form
- `android_press_key` — press Back/save
- `android_wait_for_idle` — wait for save animation to complete
- `android_global_action` — press Home when done

**App:** `com.samsung.android.contacts`

---

### Scenario 4: "Gallery Gesture Playground" (~3 min)
**Story:** Open Gallery, browse photos with every gesture type — swipe, pinch, double-tap, long-press, drag.

**Tools exercised (8):**
- `android_launch_app` — open Gallery
- `android_tap` — open first photo
- `android_swipe` — swipe to next/previous photo
- `android_double_tap` — toggle zoom on photo
- `android_pinch` — pinch to zoom in/out
- `android_long_press` — trigger selection mode in grid
- `android_drag` — (if available) drag to reorder
- `android_fling` — fast scroll through photo grid

**App:** `com.sec.android.gallery3d`

---

### Scenario 5: "Chrome Web Automation" (~3 min)
**Story:** Open Chrome, navigate to a URL, interact with web content, use clipboard, pull to refresh.

**Tools exercised (8):**
- `android_open_url` — open a test URL (httpbin.org/forms/post)
- `android_wait_for_element` — wait for page load
- `android_pull_to_refresh` — refresh the page
- `android_input_text` — fill form fields on the page
- `android_set_clipboard` — copy text to clipboard
- `android_get_clipboard` — verify clipboard content
- `android_scroll_to_element` — scroll to submit button
- `android_dismiss_keyboard` — dismiss after typing in URL bar

**App:** `com.android.chrome`

---

### Scenario 6: "Clock & Notifications" (~2 min)
**Story:** Set a timer in Clock app, wait for notification, capture it.

**Tools exercised (6):**
- `android_launch_app` — open Clock
- `android_tap` — select Timer tab, set time, start
- `android_enable_events` — start event streaming (toasts, notifications)
- `android_get_notifications` — capture timer notification
- `android_get_recent_toasts` — capture any toast messages
- `android_close_app` — close Clock when done

**App:** `com.sec.android.app.clockpackage`

---

### Scenario 7: "App Lifecycle & Data Management" (~2 min)
**Story:** Demonstrate full app lifecycle — launch, inspect, clear data, force close, verify.

**Tools exercised (6):**
- `android_launch_app` — launch Chrome
- `android_get_foreground_app` — verify Chrome is in foreground
- `android_clear_app_data` — wipe Chrome's cache/data
- `android_launch_app` — relaunch Chrome (should show first-run)
- `android_capture_logcat` — capture Chrome's startup logs
- `android_close_app` — force-stop Chrome

**App:** `com.android.chrome` (or Samsung Internet to avoid clearing important data)

---

### Scenario 8: "Accessibility Audit" (~2 min)
**Story:** Run accessibility checks on multiple apps, report violations.

**Tools exercised (5):**
- `android_launch_app` — open Settings
- `android_accessibility_audit` — audit Settings screen
- `android_launch_app` — open Calculator
- `android_accessibility_audit` — audit Calculator screen
- `android_screenshot` — capture annotated results

**App:** `com.android.settings`, Calculator (if available)

---

### Scenario 9: "Smart Wait Patterns" (~2 min)
**Story:** Demonstrate intelligent waiting — wait for elements to appear/disappear during navigation.

**Tools exercised (6):**
- `android_launch_app` — open Settings
- `android_tap` — tap on a settings category
- `android_wait_for_element` — wait for specific element to appear
- `android_wait_for_gone` — wait for loading spinner to disappear
- `android_wait_for_idle` — wait for UI to stabilize
- `android_screenshot_diff` — verify screen changed

**App:** `com.android.settings`

---

### Scenario 10: "Performance Stress Test" (~2 min)
**Story:** Rapid-fire 100 operations, measure P50/P95/P99 latencies, prove <100ms claim.

**Tools exercised (5 in rapid succession):**
- `android_tap` — 25 rapid taps
- `android_get_ui_tree` — 25 rapid tree reads
- `android_screenshot` — 25 rapid captures
- `android_get_foreground_app` — 25 rapid queries
- Performance metrics table with P50/P95/P99

**App:** Home screen

---

### Scenario 11: "AI Explorer" (~3 min)
**Story:** Autonomous app exploration — AI navigates an unfamiliar app, mapping screens.

**Tools exercised (varies):**
- `android_launch_app` — open target app
- `android_get_screen_context` — understand current screen
- `android_find_elements` — find interactive elements
- `android_tap` — navigate deeper
- `android_press_key` — backtrack
- `android_screenshot` — document each screen
- `android_get_ui_tree` — deep structural analysis

**App:** `com.android.settings` (rich navigation hierarchy)

---

### Scenario 12: "Dialer Speed Dial" (~2 min)
**Story:** Open dialer, type a number, demonstrate key presses, verify display.

**Tools exercised (5):**
- `android_launch_app` — open Dialer
- `android_tap` — tap dialpad numbers
- `android_find_elements` — read the dialed number display
- `android_press_key` — press delete to correct
- `android_global_action` — press back/home to exit

**App:** `com.samsung.android.dialer`

---

## Complete Tool Coverage Verification

| # | Tool | Scenario(s) |
|---|------|-------------|
| 1 | `android_get_ui_tree` | 2, 10, 11 |
| 2 | `android_screenshot` | 1, 3, 4, 8, 10, 11 |
| 3 | `android_find_elements` | 2, 11, 12 |
| 4 | `android_get_foreground_app` | 7, 10 |
| 5 | `android_get_device_info` | 1 |
| 6 | `android_get_screen_context` | 1, 11 |
| 7 | `android_tap` | 2, 3, 4, 6, 10, 11, 12 |
| 8 | `android_long_press` | 4 |
| 9 | `android_swipe` | 4 |
| 10 | `android_double_tap` | 4 |
| 11 | `android_pinch` | 4 |
| 12 | `android_drag` | 4 |
| 13 | `android_fling` | 2, 4 |
| 14 | `android_input_text` | 3, 5 |
| 15 | `android_press_key` | 2, 3, 11, 12 |
| 16 | `android_global_action` | 3, 12 |
| 17 | `android_pull_to_refresh` | 5 |
| 18 | `android_dismiss_keyboard` | 3, 5 |
| 19 | `android_launch_app` | 2, 3, 4, 6, 7, 8, 9, 11, 12 |
| 20 | `android_close_app` | 6, 7 |
| 21 | `android_clear_app_data` | 7 |
| 22 | `android_open_url` | 5 |
| 23 | `android_wait_for_element` | 3, 5, 9 |
| 24 | `android_wait_for_gone` | 9 |
| 25 | `android_wait_for_idle` | 3, 9 |
| 26 | `android_scroll_to_element` | 2, 5 |
| 27 | `android_enable_events` | 6 |
| 28 | `android_get_notifications` | 6 |
| 29 | `android_get_recent_toasts` | 6 |
| 30 | `android_get_clipboard` | 5 |
| 31 | `android_set_clipboard` | 5 |
| 32 | `android_capture_logcat` | 7 |
| 33 | `android_screenshot_diff` | 2, 9 |
| 34 | `android_accessibility_audit` | 8 |
| 35 | `android_list_devices` | 1 |
| 36 | `android_select_device` | 1 |

**Coverage: 36/36 (100%)**

---

## Implementation Notes

### Fallback Strategy
Each scenario should detect available apps and adapt:
- Samsung Camera → Android Camera → skip camera steps
- Samsung Gallery → Google Photos → skip gallery steps
- Chrome → Samsung Internet → AOSP Browser

### Safety
- **Scenario 7 (clear data):** Use Samsung Internet (`com.sec.android.app.sbrowser`) instead of Chrome to avoid clearing user's important Chrome data. Ask for confirmation.
- **Scenario 3 (create contact):** Clean up by deleting the test contact at end.
- **Scenario 6 (timer):** Use short timer (10s) to avoid waiting.

### Performance Tracking
Every scenario measures and reports latency for each tool call. Final summary shows aggregate P50/P95/P99 across all scenarios.

### Demo Flow
Run scenarios in order 1→12. Each is self-contained and can also run independently. Scenario 1 should always run first (device setup). Rich terminal UI with progress bars, timing, and pass/fail status.

### Estimated Total Runtime
~26 minutes for all 12 scenarios (interactive, with pauses for user observation).
