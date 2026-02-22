# Changelog

All notable changes to NeuralBridge are documented here.

## [Unreleased]

### Planned
- Multi-device support
- WebView tools (`get_webview_dom`, `execute_js`, `get_webview_url`)
- Visual regression testing pipeline
- CI/CD headless emulator integration

---

## [0.3.0] — Semantic Resolution & Advanced Observation

### Added
- `android_get_screen_context` — single-call screen snapshot (UI tree + screenshot)
- `android_scroll_to_element` — auto-scroll until element is found
- `android_wait_for_gone` — wait for element to disappear
- `android_get_device_info` — device manufacturer, model, Android version, SDK, screen dimensions
- `android_capture_logcat` — filtered logcat capture for debugging
- `android_screenshot_diff` — visual regression comparison (similarity score 0.0–1.0)
- `android_get_recent_toasts` — capture recent toast messages
- `android_pull_to_refresh` — pull-to-refresh gesture macro
- `android_dismiss_keyboard` — dismiss on-screen keyboard
- `android_accessibility_audit` — audit screen for missing content descriptions, small touch targets, non-focusable interactive elements
- `android_enable_events` — stream UI change events, notifications, toasts, crashes
- `android_clear_app_data` — clear all app data (cache, databases, preferences)
- Token optimization: compact tabular UI tree, interactive-element filter, compact bounds format, tool consolidation

### Changed
- Auto-discover now prefers device with all permissions ready (AccessibilityService + NotificationListener)
- Event streaming fixed with atomic write and buffer realignment recovery

---

## [0.2.0] — Gestures, Events & Notifications

### Added
- `android_double_tap` — double tap at coordinates or by selector
- `android_pinch` — pinch zoom gesture (scale > 1.0 = zoom in)
- `android_drag` — drag with configurable duration
- `android_fling` — directional fling gesture (up/down/left/right)
- `android_get_notifications` — get notification list (title, text, package, timestamp)
- `android_enable_events` — event streaming infrastructure (UI changes, crashes, toasts)
- `android_wait_for_element` — wait until element appears with configurable timeout
- `android_wait_for_idle` — wait until UI stabilizes

### Changed
- Selector validation added to tap, long_press, find_elements, input_text
- ADB screenshot fallback when MediaProjection is unavailable

---

## [0.1.0] — Core MVP

### Added
- Binary TCP protocol (7-byte header + protobuf) between MCP server and companion app
- `android_get_ui_tree` — full UI hierarchy extraction with semantic typing
- `android_screenshot` — screen capture via MediaProjection + libjpeg-turbo (JPEG)
- `android_find_elements` — query elements by text, resource_id, content_desc, class_name
- `android_tap` — tap by coordinates or selector
- `android_long_press` — long press with configurable duration
- `android_swipe` — linear swipe gesture
- `android_input_text` — text input into focused fields
- `android_press_key` — key events (back, home, enter, delete, volume, etc.)
- `android_global_action` — system actions (back, home, recents, notifications, quick_settings)
- `android_launch_app` — launch app by package name
- `android_close_app` — close app (graceful or force-stop via ADB)
- `android_get_foreground_app` — get current foreground app package and activity
- `android_set_clipboard` / `android_get_clipboard` — clipboard read/write
- `android_open_url` — open URL in default browser
- ADB device discovery and port forwarding (port 38472)
- MCP server auto-discover with device readiness check

[Unreleased]: https://github.com/dondetir/neuralBridge/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/dondetir/neuralBridge/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/dondetir/neuralBridge/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/dondetir/neuralBridge/releases/tag/v0.1.0
