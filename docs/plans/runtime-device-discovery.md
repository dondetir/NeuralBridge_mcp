# Runtime Device Discovery & Selection — Architecture Plan

## Problem Statement

Currently, device selection happens at **startup** via CLI args (`--device <id>` or `--auto-discover`). If no device is specified, the server exits. This creates friction:
- AI agents can't discover available devices at runtime
- Switching devices requires restarting the MCP server
- Multi-device workflows are impossible
- The `--device` flag requires the user to know the device ID upfront

## Design Goals

1. **Zero-config startup** — Server starts without requiring a device argument
2. **Runtime discovery** — AI agents can list available devices via MCP tool
3. **Runtime selection** — AI agents can switch devices via MCP tool
4. **Smart auto-select** — If exactly one ready device exists, auto-connect
5. **Backward compatibility** — `--device` and `--auto-discover` still work
6. **Graceful degradation** — Tools return clear errors when no device is selected

## Architecture Decision: Single Active Device (Not Pool)

**Decision:** Keep the single-device `AppState` model. Don't activate `ConnectionPool`.

**Rationale:**
- Port 38472 is **fixed** on the companion app — only one device can be port-forwarded at a time
- Multi-device would require dynamic port allocation (38472, 38473, ...) + companion app changes
- The existing `ConnectionPool` in `device/pool.rs` assumes same-port connections
- Single-device covers 95% of use cases; multi-device is a Phase 5+ feature
- Simpler state management = fewer race conditions

## Detailed Changes

### 1. New MCP Tool: `android_list_devices`

**Purpose:** Discover all connected Android devices with readiness information.

**File:** `main.rs` (add alongside other tools in the `#[tool_router] impl` block)

**Params struct:**
```rust
#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct ListDevicesParams {
    /// Force refresh device list (default: true)
    pub refresh: Option<bool>,
}
```

**Tool implementation:**
```rust
#[tool(
    name = "android_list_devices",
    description = "List all connected Android devices with their status. Returns device IDs, models, and companion app readiness. Use this to discover available devices before calling android_select_device. Does not require a device to be selected first."
)]
async fn android_list_devices(
    &self,
    Parameters(params): Parameters<ListDevicesParams>,
) -> Result<CallToolResult, McpError>
```

**Logic:**
1. Call `device_manager.discover_devices()` (always refreshes ADB cache)
2. For each device, call `device_manager.check_permissions(device_id)` **in parallel** via `tokio::join!` or `futures::join_all`
3. Enrich each device with ADB `getprop` for model/Android version (already parsed from `adb devices -l`)
4. Mark currently selected device (if any)

**Response JSON:**
```json
{
  "devices": [
    {
      "device_id": "344656504e303098",
      "model": "SM-G965U",
      "android_version": "10",
      "state": "device",
      "companion_installed": true,
      "accessibility_enabled": true,
      "notification_listener": true,
      "is_ready": true,
      "is_selected": true
    },
    {
      "device_id": "emulator-5554",
      "model": "sdk_phone_x86_64",
      "android_version": "14",
      "state": "device",
      "companion_installed": true,
      "accessibility_enabled": false,
      "notification_listener": false,
      "is_ready": false,
      "is_selected": false
    }
  ],
  "total_count": 2,
  "selected_device": "344656504e303098"
}
```

**Key design choice:** This tool does NOT require a device to be selected. It works even when `device_id` is `None`. It only uses ADB (slow path), never the companion TCP connection.

### 2. New MCP Tool: `android_select_device`

**Purpose:** Select a device to use for all subsequent commands.

**File:** `main.rs`

**Params struct:**
```rust
#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct SelectDeviceParams {
    /// Device ID to select (from android_list_devices output)
    pub device_id: String,
    /// Auto-enable missing permissions (default: false)
    pub auto_enable_permissions: Option<bool>,
}
```

**Tool implementation:**
```rust
#[tool(
    name = "android_select_device",
    description = "Select an Android device for all subsequent commands. Use android_list_devices first to see available devices. Establishes connection to the companion app on the selected device."
)]
async fn android_select_device(
    &self,
    Parameters(params): Parameters<SelectDeviceParams>,
) -> Result<CallToolResult, McpError>
```

**Logic:**
1. Validate `device_id` format (alphanumeric + `.:-_` only — reuse existing validation pattern from `enable_accessibility_service`)
2. Verify device exists in ADB (`adb devices` check, or use cached list)
3. **Disconnect current device** if one is selected:
   - Close existing TCP connection (`clear_connection()`)
   - Remove old port forwarding (`remove_port_forwarding()`)
   - Reset `permissions_checked` flag to `false`
4. **Set new device:**
   - Update `AppState.device_id`
   - If `auto_enable_permissions`, set the flag
5. **Eagerly connect** (don't wait for first tool call):
   - Check permissions (auto-enable if requested)
   - Setup port forwarding for new device
   - Establish TCP connection
   - Start event reader task
6. Return connection status

**Response JSON:**
```json
{
  "success": true,
  "device_id": "344656504e303098",
  "model": "SM-G965U",
  "companion_status": "connected",
  "permissions": {
    "companion_installed": true,
    "accessibility_enabled": true,
    "notification_listener": true
  }
}
```

**Error cases:**
- Device not found: `"Device '123' not found. Run android_list_devices to see available devices."`
- Device not ready: `"Device selected but companion app not ready: <missing permissions>"`
- Connection failed: `"Device selected but failed to connect: <error>. Companion app may not be running."`

### 3. Startup Behavior Change

**File:** `main.rs`, `async fn main()`

**Current flow:**
```
Parse CLI → discover (if --auto-discover) → REQUIRE device_id → start MCP server
```

**New flow:**
```
Parse CLI → if --device: pre-select → if --auto-discover: smart-select → start MCP server (device_id may be None)
```

**Specific changes:**

1. **Remove the exit on no device** (lines 3260-3264):
   ```rust
   // REMOVE THIS:
   if device_id.is_none() {
       error!("No device specified...");
       std::process::exit(1);
   }
   ```

2. **Always auto-discover on startup** (but don't fail if none found):
   ```rust
   // Always discover at startup for initial state
   let discovered = device_manager.discover_devices().await.unwrap_or_default();

   // Auto-select logic (only if no --device was specified):
   if device_id.is_none() && !discovered.is_empty() {
       // If exactly one device and it's ready, auto-select it
       if discovered.len() == 1 {
           let d = &discovered[0];
           if let Ok(status) = device_manager.check_permissions(d).await {
               if status.is_ready() {
                   info!("Auto-selected only available device: {}", d);
                   device_id = Some(d.clone());
               }
           }
       }
       // If multiple devices, log and let agent choose
       if device_id.is_none() && discovered.len() > 1 {
           info!("{} devices found. Use android_list_devices + android_select_device to choose.", discovered.len());
       }
   }
   ```

3. **Server starts even without device_id:**
   ```rust
   let app_state = Arc::new(AppState::new(device_manager));
   if let Some(ref selected) = device_id {
       *app_state.device_id.write().await = Some(selected.clone());
       app_state.auto_enable_permissions.store(enable_permissions, Ordering::SeqCst);
       info!("Starting MCP server for device: {}", selected);
   } else {
       info!("Starting MCP server (no device pre-selected — use android_list_devices + android_select_device)");
   }
   ```

4. **Keep `--device` and `--auto-discover` working** — they just pre-set the device_id before MCP server starts. Backward compatible.

### 4. Error Message Update for Unselected Device

**File:** `main.rs`, `get_connection()` and `check_companion_ready()`

Update the error message when no device is selected (lines 273, 347):

```rust
// OLD:
.context("No device selected. Use --device or --auto-discover")?;

// NEW:
.context("No device selected. Call android_list_devices to see available devices, then android_select_device to connect.")?;
```

This guides the AI agent to use the new MCP tools instead of CLI args.

### 5. Update `android_get_device_info`

Currently `android_get_device_info` fails when no device is selected. This is fine — it should still require a selected device since it queries device-specific info. No changes needed.

### 6. Update `print_usage()`

Add a note about runtime discovery:

```
Options:
  --device <id>           Pre-select a specific device at startup
  --auto-discover         Auto-detect and select first ready device
  --enable-permissions    Auto-enable AccessibilityService and NotificationListener
  --check                 Run setup verification and show device status
  --help, -h              Show this help message

Note: All options are optional. Without --device or --auto-discover,
the server starts without a device. AI agents can then use
android_list_devices and android_select_device tools at runtime.
```

## File Changes Summary

| File | Change | Lines (est.) |
|------|--------|-------------|
| `main.rs` | Add `ListDevicesParams` struct | ~5 |
| `main.rs` | Add `SelectDeviceParams` struct | ~6 |
| `main.rs` | Add `android_list_devices` tool | ~70 |
| `main.rs` | Add `android_select_device` tool | ~80 |
| `main.rs` | Update `main()` startup logic | ~30 (modify existing) |
| `main.rs` | Update error messages (2 places) | ~4 |
| `main.rs` | Update `print_usage()` | ~5 |
| **Total** | | **~200 lines** |

No changes to `device/manager.rs`, `device/adb.rs`, `device/pool.rs`, or `protocol/connection.rs`. All existing infrastructure is sufficient.

## Edge Cases & Failure Modes

| Scenario | Behavior |
|----------|----------|
| No devices connected | `android_list_devices` returns empty list. All other tools return "No device selected" error. |
| Device disconnected mid-session | Existing `send_with_recovery` handles this. Connection clears, next call retries. If device is gone, error guides agent to re-discover. |
| `android_select_device` with invalid ID | Returns error: "Device not found". |
| `android_select_device` with unready device | Selects device, reports missing permissions in response. Tools that need companion will fail with clear permission errors. |
| Multiple rapid `android_select_device` calls | RwLock serializes access. Previous connection is properly closed before new one opens. |
| `--device` + `android_select_device` | Works fine. CLI pre-selects, then agent can override at runtime. |
| Port forwarding conflict | `setup_port_forwarding` overwrites existing forward (ADB behavior). No issue. |

## Testing Plan

1. **Unit tests:** Add test for `ListDevicesParams` and `SelectDeviceParams` serialization
2. **Integration test:** Start server without `--device`, call `android_list_devices`, verify response format
3. **Integration test:** Call `android_select_device` with real device, verify connection works
4. **Integration test:** Switch between devices (if 2 available), verify old connection closed
5. **Backward compat:** Verify `--device emulator-5554` still works as before
6. **Backward compat:** Verify `--auto-discover` still works as before
7. **Error path:** Call any tool without selecting device, verify error message mentions `android_list_devices`

## Sequence Diagram

```
AI Agent                    MCP Server                    ADB                    Device
   |                           |                           |                       |
   |--- start server --------->|                           |                       |
   |   (no --device flag)      |--- discover_devices() --->|                       |
   |                           |<-- [dev1, dev2] ----------|                       |
   |                           | (1 ready? auto-select)    |                       |
   |                           | (>1? wait for agent)      |                       |
   |                           |                           |                       |
   |--- android_list_devices ->|                           |                       |
   |                           |--- adb devices -l ------->|                       |
   |                           |--- check_permissions(d1)->|                       |
   |                           |--- check_permissions(d2)->|                       |
   |<-- [{d1: ready}, {d2}] --|                           |                       |
   |                           |                           |                       |
   |--- android_select_device->|                           |                       |
   |    (device_id: "d1")      |--- forward tcp:38472 ---->|                       |
   |                           |--- TCP connect ---------->|---------------------> |
   |                           |<-- connected -------------|<--------------------- |
   |<-- {success, connected} --|                           |                       |
   |                           |                           |                       |
   |--- android_tap ---------->|--- protobuf request ----->|---------------------> |
   |                           |                           |                       |
```

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Race condition on device switch | Low | Medium | RwLock on connection + device_id serializes access |
| Port forwarding not cleaned up | Low | Low | ADB `forward` is idempotent; overwrites existing |
| Agent forgets to select device | Medium | Low | Clear error message guides to correct tool |
| Startup slower with discovery | Low | Low | Discovery is ~200ms; only runs once at startup |
