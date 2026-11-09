package com.neuralbridge.companion.mcp

import kotlinx.serialization.json.*

object McpToolRegistry {

    fun getAllTools(): List<McpToolDefinition> = listOf(
        // ── OBSERVE ──────────────────────────────────────────────────────
        McpToolDefinition(
            name = "android_get_ui_tree",
            description = "Get the UI tree of the current screen. Returns all visible UI elements with IDs, text, bounds, and semantic types. Use for understanding screen structure, finding interactive elements, or debugging selectors. Prefer resource_id for stable element identification.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("filter") {
                        put("type", "string")
                        put("description", "Filter mode: \"all\" (every element), \"interactive\" (clickable/focusable/scrollable/text), \"text\" (text-bearing only). Default: \"interactive\"")
                    }
                    putJsonObject("include_invisible") {
                        put("type", "boolean")
                        put("description", "Include invisible elements")
                    }
                    putJsonObject("max_depth") {
                        put("type", "integer")
                        put("description", "Max tree depth (0 = unlimited)")
                    }
                }
            }
        ),
        McpToolDefinition(
            name = "android_screenshot",
            description = "Capture a screenshot. Returns MCP image content (vision tokens). Quality: 'full' or 'thumbnail'. Resolution: max_width (default 720px, 0 = full).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("quality") {
                        put("type", "string")
                        put("description", "Quality: \"full\" or \"thumbnail\"")
                    }
                    putJsonObject("max_width") {
                        put("type", "integer")
                        put("description", "Max width in pixels (default 720, 0 = full)")
                    }
                }
            }
        ),
        McpToolDefinition(
            name = "android_find_elements",
            description = "Find UI elements by text, resource_id, content_desc, or class_name. Prefer resource_id for stability. Set find_all=true for all matches. Returns bounds.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Element text") }
                    putJsonObject("resource_id") { put("type", "string"); put("description", "Resource ID (suffix match)") }
                    putJsonObject("content_desc") { put("type", "string"); put("description", "Content description") }
                    putJsonObject("class_name") { put("type", "string"); put("description", "Class name") }
                    putJsonObject("clickable") { put("type", "boolean"); put("description", "Filter clickable") }
                    putJsonObject("scrollable") { put("type", "boolean"); put("description", "Filter scrollable") }
                    putJsonObject("focusable") { put("type", "boolean"); put("description", "Filter focusable") }
                    putJsonObject("find_all") { put("type", "boolean"); put("description", "Return all matches") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_get_screen_context",
            description = "Get a comprehensive snapshot of the current screen for AI analysis. Returns foreground app info, simplified UI tree (interactive elements and text), and a thumbnail screenshot in a single efficient call.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("include_all_elements") { put("type", "boolean"); put("description", "Include all elements or only interactive/text") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_get_notifications",
            description = "Get notifications (title, text, package, timestamp, clearable).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("active_only") { put("type", "boolean"); put("description", "Only active notifications") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_screenshot_diff",
            description = "Compare reference screenshot with current screen. Returns similarity score (0.0-1.0) and match status. For visual regression testing.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("reference_base64") { put("type", "string"); put("description", "Reference screenshot (base64 JPEG)") }
                    putJsonObject("threshold") { put("type", "number"); put("description", "Similarity threshold (0.0-1.0, default 0.95)") }
                }
                putJsonArray("required") { add(JsonPrimitive("reference_base64")) }
            }
        ),
        McpToolDefinition(
            name = "android_accessibility_audit",
            description = "Audit screen for accessibility issues: missing content descriptions, small touch targets (<48dp), non-focusable interactive elements.",
            inputSchema = buildJsonObject { put("type", "object"); putJsonObject("properties") {} }
        ),
        McpToolDefinition(
            name = "android_get_recent_toasts",
            description = "Get recent toast messages.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("since_ms") { put("type", "integer"); put("description", "Recent toasts window in ms (default 5000)") }
                }
            }
        ),
        // ── ACT ──────────────────────────────────────────────────────────
        McpToolDefinition(
            name = "android_tap",
            description = "Tap at (x,y) or on an element identified by its visible label. NOTE: 'text' here is the label of the element to TAP (e.g. a button caption), NOT text to type — to enter text use android_input_text instead.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("x") { put("type", "integer"); put("description", "X coordinate") }
                    putJsonObject("y") { put("type", "integer"); put("description", "Y coordinate") }
                    putJsonObject("text") { put("type", "string"); put("description", "Visible label of the element to tap (not text to type)") }
                    putJsonObject("resource_id") { put("type", "string"); put("description", "Resource ID") }
                    putJsonObject("content_desc") { put("type", "string"); put("description", "Content description") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_long_press",
            description = "Long press at coordinates or element (default 1000ms). For context menus, text selection, or long-press actions.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("x") { put("type", "integer"); put("description", "X coordinate") }
                    putJsonObject("y") { put("type", "integer"); put("description", "Y coordinate") }
                    putJsonObject("text") { put("type", "string"); put("description", "Element text") }
                    putJsonObject("resource_id") { put("type", "string"); put("description", "Resource ID") }
                    putJsonObject("duration_ms") { put("type", "integer"); put("description", "Duration in ms (default 1000)") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_double_tap",
            description = "Double tap at (x,y) or on element by text/resource_id/content_desc.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("x") { put("type", "integer"); put("description", "X coordinate") }
                    putJsonObject("y") { put("type", "integer"); put("description", "Y coordinate") }
                    putJsonObject("text") { put("type", "string"); put("description", "Element text") }
                    putJsonObject("resource_id") { put("type", "string"); put("description", "Resource ID") }
                    putJsonObject("content_desc") { put("type", "string"); put("description", "Content description") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_swipe",
            description = "Swipe from (start_x,start_y) to (end_x,end_y). Default 300ms, <200ms = fling. For scrolling, page navigation, or dismissing.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("start_x") { put("type", "integer"); put("description", "Start X") }
                    putJsonObject("start_y") { put("type", "integer"); put("description", "Start Y") }
                    putJsonObject("end_x") { put("type", "integer"); put("description", "End X") }
                    putJsonObject("end_y") { put("type", "integer"); put("description", "End Y") }
                    putJsonObject("duration_ms") { put("type", "integer"); put("description", "Duration ms (default 300)") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("start_x")); add(JsonPrimitive("start_y"))
                    add(JsonPrimitive("end_x")); add(JsonPrimitive("end_y"))
                }
            }
        ),
        McpToolDefinition(
            name = "android_pinch",
            description = "Pinch zoom gesture. Scale >1.0 = zoom in, <1.0 = zoom out.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("center_x") { put("type", "integer"); put("description", "Center X") }
                    putJsonObject("center_y") { put("type", "integer"); put("description", "Center Y") }
                    putJsonObject("scale") { put("type", "number"); put("description", "Scale (>1.0 zoom in, <1.0 zoom out)") }
                    putJsonObject("duration_ms") { put("type", "integer"); put("description", "Duration ms (default 300)") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("center_x")); add(JsonPrimitive("center_y")); add(JsonPrimitive("scale"))
                }
            }
        ),
        McpToolDefinition(
            name = "android_drag",
            description = "Drag from (from_x,from_y) to (to_x,to_y). Default 500ms. For list items, sliders, or drag-and-drop.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("from_x") { put("type", "integer"); put("description", "Start X") }
                    putJsonObject("from_y") { put("type", "integer"); put("description", "Start Y") }
                    putJsonObject("to_x") { put("type", "integer"); put("description", "End X") }
                    putJsonObject("to_y") { put("type", "integer"); put("description", "End Y") }
                    putJsonObject("duration_ms") { put("type", "integer"); put("description", "Duration ms (default 500)") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("from_x")); add(JsonPrimitive("from_y"))
                    add(JsonPrimitive("to_x")); add(JsonPrimitive("to_y"))
                }
            }
        ),
        McpToolDefinition(
            name = "android_input_text",
            description = "Type text into an input field. If element_text/resource_id are omitted, types into the currently FOCUSED field (works for id-less/text-less inputs such as terminal or search fields). Set append=true to add to existing text.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Text to input") }
                    putJsonObject("element_text") { put("type", "string"); put("description", "Input field text/hint") }
                    putJsonObject("resource_id") { put("type", "string"); put("description", "Input field resource ID") }
                    putJsonObject("append") { put("type", "boolean"); put("description", "Append to existing text") }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            }
        ),
        McpToolDefinition(
            name = "android_press_key",
            description = "Press a key. Global (always work): back, home, recents, notifications, power. Requires focused input field: enter, delete/backspace, tab, escape, space, select_all, cut, copy, paste.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") { put("type", "string"); put("description", "Key name: back, home, recents, notifications, power, enter, delete, backspace, tab, escape, space, select_all, cut, copy, paste") }
                }
                putJsonArray("required") { add(JsonPrimitive("key")) }
            }
        ),
        McpToolDefinition(
            name = "android_global_action",
            description = "System action: back, home, recents, notifications, quick_settings.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") { put("type", "string"); put("description", "Action (back, home, recents, notifications, quick_settings)") }
                }
                putJsonArray("required") { add(JsonPrimitive("action")) }
            }
        ),

        // ── MANAGE ────────────────────────────────────────────────────────
        McpToolDefinition(
            name = "android_launch_app",
            description = "Launch app by package name. Optional: activity. WARNING: clear_task=true adds FLAG_ACTIVITY_CLEAR_TASK which WIPES the app's existing task stack — it resets the app to its launch screen and destroys in-app state (e.g. live terminal/SSH sessions). Leave clear_task unset/false to resume the app in its current state.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package_name") { put("type", "string"); put("description", "Package name (e.g. com.android.chrome)") }
                    putJsonObject("activity") { put("type", "string"); put("description", "Activity to launch") }
                    putJsonObject("clear_task") { put("type", "boolean"); put("description", "Clear task stack") }
                }
                putJsonArray("required") { add(JsonPrimitive("package_name")) }
            }
        ),
        McpToolDefinition(
            name = "android_close_app",
            description = "Close app by sending it to the background via HOME.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package_name") { put("type", "string"); put("description", "Package name") }
                    putJsonObject("force") { put("type", "boolean"); put("description", "Force-stop (not supported, app is sent to background)") }
                }
                putJsonArray("required") { add(JsonPrimitive("package_name")) }
            }
        ),
        McpToolDefinition(
            name = "android_open_url",
            description = "Open a URL or deep link in the default browser.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") { put("type", "string"); put("description", "URL or deep link") }
                    putJsonObject("browser_package") { put("type", "string"); put("description", "Browser package") }
                }
                putJsonArray("required") { add(JsonPrimitive("url")) }
            }
        ),
        McpToolDefinition(
            name = "android_set_clipboard",
            description = "Set clipboard content. For sharing text or before input_text for special characters.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Text to set in clipboard") }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            }
        ),
        McpToolDefinition(
            name = "android_list_apps",
            description = "List installed apps on the device. Filter by \"all\" (default), \"third_party\", or \"system\" apps.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("filter") { put("type", "string"); put("description", "Filter: \"all\", \"third_party\", or \"system\"") }
                }
            }
        ),

        // ── INTENT / IPC ──────────────────────────────────────────────────
        McpToolDefinition(
            name = "android_start_activity",
            description = "Start an Activity via an explicit or implicit Intent (runs at the app's own permission level; cannot reach non-exported components or those needing system/signature permissions). Supports deep links, sharing (ACTION_SEND), launching specific activities, opening settings screens, etc.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") { put("type", "string"); put("description", "Intent action, e.g. android.intent.action.VIEW / SEND / DIAL") }
                    putJsonObject("data") { put("type", "string"); put("description", "Data URI, e.g. https://..., tel:..., content://...") }
                    putJsonObject("mime_type") { put("type", "string"); put("description", "MIME type, e.g. text/plain") }
                    putJsonObject("package") { put("type", "string"); put("description", "Target package (implicit within package)") }
                    putJsonObject("component") { put("type", "string"); put("description", "Explicit component \"pkg/cls\" or \"pkg/.cls\"") }
                    putJsonObject("categories") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Intent categories") }
                    putJsonObject("extras") { put("type", "object"); put("description", "Extras: {key:{type:string|int|long|float|bool,value:...}} or bare primitives") }
                    putJsonObject("flags") { put("type", "array"); putJsonObject("items") { put("type", "integer") }; put("description", "Intent flag ints (OR'd)") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_send_broadcast",
            description = "Send a broadcast Intent (implicit or explicit) at the app's permission level. Optional 'permission' restricts receivers. Cannot send most protected/system broadcasts.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") { put("type", "string"); put("description", "Broadcast action") }
                    putJsonObject("data") { put("type", "string"); put("description", "Data URI") }
                    putJsonObject("package") { put("type", "string"); put("description", "Target package (explicit broadcast)") }
                    putJsonObject("component") { put("type", "string"); put("description", "Explicit receiver component \"pkg/cls\"") }
                    putJsonObject("extras") { put("type", "object"); put("description", "Typed extras (see start_activity)") }
                    putJsonObject("permission") { put("type", "string"); put("description", "Receiver permission to enforce") }
                }
                putJsonArray("required") { add(JsonPrimitive("action")) }
            }
        ),
        McpToolDefinition(
            name = "android_start_service",
            description = "Start a background Service via Intent (app permission level; target must be exported or owned by this app).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("component") { put("type", "string"); put("description", "Service component \"pkg/cls\"") }
                    putJsonObject("package") { put("type", "string"); put("description", "Target package") }
                    putJsonObject("action") { put("type", "string"); put("description", "Optional action") }
                    putJsonObject("extras") { put("type", "object"); put("description", "Typed extras") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_start_foreground_service",
            description = "Start a foreground Service via Intent (startForegroundService on API 26+). Same constraints as start_service.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("component") { put("type", "string"); put("description", "Service component \"pkg/cls\"") }
                    putJsonObject("package") { put("type", "string"); put("description", "Target package") }
                    putJsonObject("action") { put("type", "string"); put("description", "Optional action") }
                    putJsonObject("extras") { put("type", "object"); put("description", "Typed extras") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_query_content",
            description = "Query a ContentProvider via ContentResolver (e.g. content://media/..., contacts if permitted). Returns columns + rows. Only providers readable at the app's permission level work.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("uri") { put("type", "string"); put("description", "content:// URI to query") }
                    putJsonObject("projection") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Columns to return (optional)") }
                    putJsonObject("selection") { put("type", "string"); put("description", "WHERE clause (optional)") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max rows (default 50)") }
                }
                putJsonArray("required") { add(JsonPrimitive("uri")) }
            }
        ),

        // ── SHELL ──────────────────────────────────────────────────────────
        McpToolDefinition(
            name = "android_shell",
            description = "Run a shell command at the APP's UID (unprivileged sandbox). IMPORTANT: this is NOT 'adb shell' — am/pm/settings/input/dumpsys and reading other apps' data will be DENIED. For a real privileged shell use adb shell over the device connection. Returns stdout, stderr, exit_code.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("command") { put("type", "string"); put("description", "Shell command (run via /system/bin/sh -c)") }
                    putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Timeout in ms (default 10000)") }
                }
                putJsonArray("required") { add(JsonPrimitive("command")) }
            }
        ),
        // ── WAIT ──────────────────────────────────────────────────────────
        McpToolDefinition(
            name = "android_wait_for_element",
            description = "Wait for UI element to appear (default 5000ms). Use for loading, navigation, or UI updates. Returns found=false on timeout.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Element text") }
                    putJsonObject("resource_id") { put("type", "string"); put("description", "Resource ID") }
                    putJsonObject("content_desc") { put("type", "string"); put("description", "Content description") }
                    putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Timeout ms (default 5000)") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_wait_for_gone",
            description = "Wait for element to disappear. For loading dialogs, splash screens, progress indicators. Returns found=false when gone.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Element text") }
                    putJsonObject("resource_id") { put("type", "string"); put("description", "Resource ID") }
                    putJsonObject("content_desc") { put("type", "string"); put("description", "Content description") }
                    putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Timeout ms (default 5000)") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_wait_for_idle",
            description = "Wait until the UI stabilizes (no changes for 300ms).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Timeout ms (default 5000)") }
                }
            }
        ),
        McpToolDefinition(
            name = "android_scroll_to_element",
            description = "Scroll to find an element that may be off-screen. Automatically scrolls through lists and scroll containers until the target element is found or the end of content is reached.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Element text") }
                    putJsonObject("resource_id") { put("type", "string"); put("description", "Resource ID") }
                    putJsonObject("content_desc") { put("type", "string"); put("description", "Content description") }
                    putJsonObject("direction") { put("type", "string"); put("description", "Direction: up, down, left, right") }
                    putJsonObject("max_scrolls") { put("type", "integer"); put("description", "Max scrolls (default 20)") }
                    putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Timeout ms (default 30000)") }
                }
            }
        ),

        // ── DEVICE ────────────────────────────────────────────────────────
        McpToolDefinition(
            name = "android_list_devices",
            description = "List connected devices with status. Returns this device's info.",
            inputSchema = buildJsonObject { put("type", "object"); putJsonObject("properties") {} }
        ),
        McpToolDefinition(
            name = "android_select_device",
            description = "Select device for all commands. No-op for embedded server (always using current device).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("device_id") { put("type", "string"); put("description", "Device ID") }
                    putJsonObject("auto_enable_permissions") { put("type", "boolean"); put("description", "Auto-enable missing permissions") }
                }
                putJsonArray("required") { add(JsonPrimitive("device_id")) }
            }
        ),

        // ── META ──────────────────────────────────────────────────────────
        McpToolDefinition(
            name = "android_search_tools",
            description = "Search available tools by keyword or category.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") { put("type", "string"); put("description", "Keyword to search for") }
                    putJsonObject("category") { put("type", "string"); put("description", "Filter by category") }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            }
        ),
        McpToolDefinition(
            name = "android_describe_tools",
            description = "Get detailed descriptions for specific tools.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("tools") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Tool names to describe")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("tools")) }
            }
        ),

        // ── TEST / EVENTS ─────────────────────────────────────────────────
        McpToolDefinition(
            name = "android_enable_events",
            description = "Enable/disable event streaming (UI changes, notifications, toasts, crashes).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("enable") { put("type", "boolean"); put("description", "Enable/disable streaming") }
                    putJsonObject("event_types") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Event types (empty = all)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("enable")) }
            }
        ),
        McpToolDefinition(
            name = "android_get_device_info",
            description = "Get device information including manufacturer, model, Android version, SDK level, and screen dimensions.",
            inputSchema = buildJsonObject { put("type", "object"); putJsonObject("properties") {} }
        ),

        // ── WEB (in-process WebView / CDP driver) ─────────────────────────
        McpToolDefinition(
            name = "web_navigate",
            description = "Load a URL in the app-owned WebView and wait for the page to finish loading. Returns the final URL and title. This drives an in-process WebView (not the system browser); use web_* tools to inspect and interact with its DOM.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") { put("type", "string"); put("description", "URL to load (include scheme, e.g. https://)") }
                    putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Load timeout in ms (default 30000)") }
                }
                putJsonArray("required") { add(JsonPrimitive("url")) }
            }
        ),
        McpToolDefinition(
            name = "web_current_url",
            description = "Get the current URL and title of the app-owned WebView.",
            inputSchema = buildJsonObject { put("type", "object"); putJsonObject("properties") {} }
        ),
        McpToolDefinition(
            name = "web_eval_js",
            description = "Evaluate a JavaScript expression in the app-owned WebView and return its result (JSON-encoded by the WebView). Use for reading page state or driving custom logic.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("script") { put("type", "string"); put("description", "JavaScript expression to evaluate") }
                }
                putJsonArray("required") { add(JsonPrimitive("script")) }
            }
        ),
        McpToolDefinition(
            name = "web_query",
            description = "Query DOM elements by CSS selector. Returns tag, id, text, name/type/href attributes, bounding rect, and visibility. Set all=true to return every match (default: first match only).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("selector") { put("type", "string"); put("description", "CSS selector") }
                    putJsonObject("all") { put("type", "boolean"); put("description", "Return all matches (default false)") }
                }
                putJsonArray("required") { add(JsonPrimitive("selector")) }
            }
        ),
        McpToolDefinition(
            name = "web_click",
            description = "Click the first DOM element matching a CSS selector in the app-owned WebView. Scrolls the element into view first. Returns found=false if no element matched.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("selector") { put("type", "string"); put("description", "CSS selector") }
                }
                putJsonArray("required") { add(JsonPrimitive("selector")) }
            }
        ),
        McpToolDefinition(
            name = "web_type",
            description = "Set the value of an input/textarea matching a CSS selector and dispatch input/change events so JS frameworks observe the change.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("selector") { put("type", "string"); put("description", "CSS selector for the input/textarea") }
                    putJsonObject("text") { put("type", "string"); put("description", "Text to set as the field value") }
                }
                putJsonArray("required") { add(JsonPrimitive("selector")); add(JsonPrimitive("text")) }
            }
        ),
        McpToolDefinition(
            name = "web_get_dom",
            description = "Get the outerHTML of the document (or of a specific element if selector is provided). Output is truncated to max_chars to control token usage.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("selector") { put("type", "string"); put("description", "Optional CSS selector; omit for whole document") }
                    putJsonObject("max_chars") { put("type", "integer"); put("description", "Max characters to return (default 20000)") }
                }
            }
        ),
        McpToolDefinition(
            name = "web_get_text",
            description = "Get the innerText of the page body (or of a specific element if selector is provided). Output is truncated to max_chars.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("selector") { put("type", "string"); put("description", "Optional CSS selector; omit for document body") }
                    putJsonObject("max_chars") { put("type", "integer"); put("description", "Max characters to return (default 20000)") }
                }
            }
        ),
        McpToolDefinition(
            name = "web_wait_for",
            description = "Poll until a DOM element matching a CSS selector exists, or until timeout. Returns found=true/false. Use after web_navigate or web_click for dynamic content.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("selector") { put("type", "string"); put("description", "CSS selector to wait for") }
                    putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Timeout in ms (default 5000)") }
                }
                putJsonArray("required") { add(JsonPrimitive("selector")) }
            }
        ),
        McpToolDefinition(
            name = "web_screenshot",
            description = "Capture the current contents of the app-owned WebView as a JPEG image (vision tokens).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("quality") { put("type", "integer"); put("description", "JPEG quality 1-100 (default 80)") }
                }
            }
        ),
    )

    fun getTool(name: String): McpToolDefinition? = getAllTools().find { it.name == name }
}
