/*!
 * NeuralBridge MCP Server
 *
 * Entry point for the AI-native Android automation MCP server.
 * Provides MCP tools for Android device control via AccessibilityService.
 */

use anyhow::{Context, Result};
use base64::Engine;
use rmcp::{
    ErrorData as McpError,
    ServerHandler,
    ServiceExt,
    handler::server::{tool::ToolRouter, wrapper::Parameters},
    model::*,
    tool, tool_handler, tool_router,
    transport::io::stdio,
};
use rmcp::model::ErrorCode;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::sync::RwLock;
use tracing::{info, warn, error, debug};
use uuid::Uuid;

mod device;
mod protocol;
mod semantic;
mod tools;

use device::manager::DeviceManager;
use protocol::connection::DeviceConnection;
use protocol::pb::{self, Request, request::Command};

// ============================================================================
// Application State
// ============================================================================

pub struct AppState {
    device_manager: Arc<DeviceManager>,
    connection: Arc<RwLock<Option<DeviceConnection>>>,
    device_id: Arc<RwLock<Option<String>>>,
    event_buffer: Arc<RwLock<VecDeque<pb::Event>>>,
    auto_enable_permissions: AtomicBool,
    permissions_checked: AtomicBool,
}

// Helper to convert anyhow errors to MCP errors with detailed classification
fn to_mcp_error(e: anyhow::Error) -> McpError {
    let error_msg = e.to_string().to_lowercase();

    // Classify connection errors
    if error_msg.contains("connection refused")
        || error_msg.contains("connection timeout")
        || error_msg.contains("connection reset")
        || error_msg.contains("no route to host")
    {
        let msg = format!(
            "Failed to connect to companion app: {}\n\
            \n\
            Troubleshooting checklist:\n\
            1. Check companion app is installed and running\n\
            2. Verify AccessibilityService is enabled in Settings\n\
            3. Run: adb forward tcp:38472 tcp:38472\n\
            4. Check logcat: adb logcat -s NeuralBridge:V",
            e
        );
        return McpError::new(ErrorCode::INTERNAL_ERROR, msg, None);
    }

    // Classify permission errors
    if error_msg.contains("permission denied") || error_msg.contains("unauthorized") {
        let msg = format!(
            "Permission denied: {}\n\
            \n\
            Troubleshooting checklist:\n\
            1. Accept authorization prompt on device screen\n\
            2. Check USB debugging is enabled\n\
            3. Run: adb devices (device should show 'device', not 'unauthorized')\n\
            4. Try: adb kill-server && adb start-server",
            e
        );
        return McpError::new(ErrorCode::INTERNAL_ERROR, msg, None);
    }

    // Classify device state errors
    if error_msg.contains("device offline") || error_msg.contains("device not responding") {
        let msg = format!(
            "Device is offline or not responding: {}\n\
            \n\
            Troubleshooting checklist:\n\
            1. Check device is powered on and unlocked\n\
            2. Check USB cable connection\n\
            3. Run: adb devices\n\
            4. Try: adb reconnect",
            e
        );
        return McpError::new(ErrorCode::INTERNAL_ERROR, msg, None);
    }

    // Classify ADB errors
    if error_msg.contains("adb") || error_msg.contains("device not found") {
        let msg = format!(
            "ADB operation failed: {}\n\
            \n\
            Troubleshooting checklist:\n\
            1. Run: adb devices\n\
            2. Check device is connected and authorized\n\
            3. Verify ADB is in PATH\n\
            4. Try: adb kill-server && adb start-server",
            e
        );
        return McpError::new(ErrorCode::INTERNAL_ERROR, msg, None);
    }

    // Classify "no device selected" errors
    if error_msg.contains("no device selected") {
        let msg = format!(
            "{}\n\
            \n\
            Please specify a device:\n\
            - Use --device <id> flag\n\
            - Or use --auto-discover to auto-select first device",
            e
        );
        return McpError::new(ErrorCode::INVALID_PARAMS, msg, None);
    }

    // Classify port forwarding errors
    if error_msg.contains("port forwarding") {
        let msg = format!(
            "Port forwarding setup failed: {}\n\
            \n\
            Troubleshooting checklist:\n\
            1. Check device is online: adb devices\n\
            2. Manually test: adb forward tcp:38472 tcp:38472\n\
            3. Check for port conflicts: netstat -an | grep 38472\n\
            4. Try: adb forward --remove-all",
            e
        );
        return McpError::new(ErrorCode::INTERNAL_ERROR, msg, None);
    }

    // Default: generic error
    McpError::new(ErrorCode::INTERNAL_ERROR, e.to_string(), None)
}


// Helper to validate selector has at least one non-empty field or boolean filter
#[allow(clippy::too_many_arguments)]
fn validate_selector(
    text: Option<&String>,
    resource_id: Option<&String>,
    content_desc: Option<&String>,
    class_name: Option<&String>,
    clickable: Option<bool>,
    scrollable: Option<bool>,
    focusable: Option<bool>,
    long_clickable: Option<bool>,
    checkable: Option<bool>,
    checked: Option<bool>,
) -> Result<(), McpError> {
    let has_text = text.map(|s| !s.is_empty()).unwrap_or(false);
    let has_resource_id = resource_id.map(|s| !s.is_empty()).unwrap_or(false);
    let has_content_desc = content_desc.map(|s| !s.is_empty()).unwrap_or(false);
    let has_class_name = class_name.map(|s| !s.is_empty()).unwrap_or(false);

    // Check if any boolean filter is explicitly set
    let has_boolean_filter = clickable.is_some()
        || scrollable.is_some()
        || focusable.is_some()
        || long_clickable.is_some()
        || checkable.is_some()
        || checked.is_some();

    if !has_text && !has_resource_id && !has_content_desc && !has_class_name && !has_boolean_filter {
        return Err(McpError::new(
            ErrorCode::INVALID_PARAMS,
            "Selector must have at least one non-empty field (text, resource_id, content_desc, class_name) or boolean filter (clickable, scrollable, focusable, long_clickable, checkable, checked)".to_string(),
            None,
        ));
    }

    Ok(())
}

// Retry helper for transient failures
async fn retry_on_transient(
    conn: &DeviceConnection,
    request: Request,
    max_retries: u32,
) -> Result<pb::Response> {
    let mut last_error = None;

    for attempt in 0..=max_retries {
        if attempt > 0 {
            debug!("Retry attempt {} after 200ms delay", attempt);
            tokio::time::sleep(std::time::Duration::from_millis(200)).await;
        }

        // Clone request for retry (request_id stays the same for correlation)
        let req = Request {
            request_id: request.request_id.clone(),
            command: request.command.clone(),
        };

        match conn.send_request(req).await {
            Ok(response) => {
                // Check if error is retryable
                if response.success {
                    return Ok(response);
                }

                let is_retryable = response.error_code == pb::ErrorCode::ElementNotFound as i32;

                if is_retryable && attempt < max_retries {
                    debug!("Retryable error (code={}): {}", response.error_code, response.error_message);
                    last_error = Some(anyhow::anyhow!("{}", response.error_message));
                    continue;
                }

                // Non-retryable error or final attempt - return response
                return Ok(response);
            }
            Err(e) => {
                // Connection errors are retryable
                if (e.to_string().contains("timeout") || e.to_string().contains("connection"))
                    && attempt < max_retries
                {
                    debug!("Connection error, will retry: {}", e);
                    last_error = Some(e);
                    continue;
                }
                // Non-retryable error or final attempt
                return Err(e);
            }
        }
    }

    // This should never be reached, but just in case
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("Max retries exceeded")))
}

impl AppState {
    pub fn new(device_manager: DeviceManager) -> Self {
        Self {
            device_manager: Arc::new(device_manager),
            connection: Arc::new(RwLock::new(None)),
            device_id: Arc::new(RwLock::new(None)),
            event_buffer: Arc::new(RwLock::new(VecDeque::with_capacity(100))),
            auto_enable_permissions: AtomicBool::new(false),
            permissions_checked: AtomicBool::new(false),
        }
    }

    /// Check if companion app is ready with required permissions
    ///
    /// Returns error if permissions are missing. If auto_enable is true,
    /// attempts to enable missing permissions automatically.
    /// Caches successful permission checks to avoid redundant ADB calls.
    async fn check_companion_ready(&self, auto_enable: bool) -> Result<()> {
        // Check cache first - if permissions were already verified, skip check
        if self.permissions_checked.load(Ordering::SeqCst) {
            debug!("Permissions already verified (cached), skipping check");
            return Ok(());
        }

        let device_id = self.device_id.read().await;
        let device_id_str = device_id.as_ref()
            .context("No device selected. Call android_list_devices to see available devices, then android_select_device to connect.")?;

        debug!("Checking companion app permissions on device: {}", device_id_str);

        // Check current permissions
        let status = self.device_manager.check_permissions(device_id_str).await
            .context("Failed to check companion app permissions")?;

        // If not ready and auto-enable is requested, try to enable
        if !status.is_ready() && auto_enable {
            info!("Auto-enabling missing permissions...");

            if !status.accessibility_enabled {
                self.device_manager.enable_accessibility_service(device_id_str).await
                    .context("Failed to enable AccessibilityService")?;
            }

            if !status.notification_listener_enabled {
                self.device_manager.enable_notification_listener(device_id_str).await
                    .context("Failed to enable NotificationListenerService")?;
            }

            // Re-check after enabling
            let new_status = self.device_manager.check_permissions(device_id_str).await
                .context("Failed to re-check permissions after auto-enable")?;

            if !new_status.is_ready() {
                if let Some(msg) = new_status.missing_permissions_message() {
                    anyhow::bail!("{}", msg);
                }
            }
        } else if !status.is_ready() {
            // Not ready and auto-enable not requested
            if let Some(msg) = status.missing_permissions_message() {
                anyhow::bail!(
                    "{}\n\nUse --enable-permissions flag to auto-enable missing permissions",
                    msg
                );
            }
        }

        // Cache successful permission check
        self.permissions_checked.store(true, Ordering::SeqCst);

        Ok(())
    }

    /// Clear the cached connection (called on send failures to force reconnect)
    pub async fn clear_connection(&self) {
        let mut conn_write = self.connection.write().await;
        if conn_write.is_some() {
            info!("Clearing cached connection (will reconnect on next use)");
            *conn_write = None;
        }
    }

    pub async fn get_connection(&self) -> Result<DeviceConnection> {
        // Check if we have an existing connection
        let conn = self.connection.read().await;
        if let Some(existing_conn) = conn.as_ref() {
            // Verify connection is still alive
            if existing_conn.is_alive().await {
                return Ok(existing_conn.clone());
            }
            info!("Existing connection is dead, reconnecting...");
        }
        drop(conn);

        // Clear the dead connection
        self.clear_connection().await;

        // Get device ID
        let device_id = self.device_id.read().await;
        let device_id_str = device_id.as_ref()
            .context("No device selected. Call android_list_devices to see available devices, then android_select_device to connect.")?;

        info!("Establishing new connection to device: {}", device_id_str);

        // Pre-flight check: verify companion app permissions
        let auto_enable = self.auto_enable_permissions.load(Ordering::SeqCst);
        self.check_companion_ready(auto_enable).await
            .context("Companion app not ready")?;

        // Set up ADB port forwarding
        self.device_manager.setup_port_forwarding(device_id_str).await
            .context("Failed to set up ADB port forwarding")?;

        // Establish TCP connection (with automatic retry logic)
        let new_conn = DeviceConnection::connect().await
            .context("Failed to connect to companion app")?;

        // Take the event receiver and spawn background task to process events
        if let Some(mut event_rx) = new_conn.take_event_receiver().await {
            let event_buffer = self.event_buffer.clone();
            tokio::spawn(async move {
                debug!("Event reader task started");
                while let Some(event) = event_rx.recv().await {
                    debug!("Received event: type={:?}, id={}", event.event_type, event.event_id);

                    // Add to circular buffer (remove oldest if full)
                    let mut buffer = event_buffer.write().await;
                    if buffer.len() >= 100 {
                        buffer.pop_front();
                    }
                    buffer.push_back(event);
                }
                debug!("Event reader task terminated");
            });
        }

        // Cache the connection
        let mut conn_write = self.connection.write().await;
        *conn_write = Some(new_conn.clone());

        info!("Connection established successfully");
        Ok(new_conn)
    }

    /// Send a request with automatic connection recovery.
    /// If the send fails due to a dead connection, clears the cached connection
    /// and retries once with a fresh connection.
    pub async fn send_with_recovery(&self, request: Request) -> Result<pb::Response> {
        // Try with existing connection
        let conn = self.get_connection().await?;
        match conn.send_request(request.clone()).await {
            Ok(response) => Ok(response),
            Err(e) => {
                let error_msg = e.to_string().to_lowercase();
                if error_msg.contains("send")
                    || error_msg.contains("write")
                    || error_msg.contains("broken pipe")
                    || error_msg.contains("connection reset")
                    || error_msg.contains("connection closed")
                {
                    warn!("Send failed ({}), clearing connection and retrying...", e);
                    self.clear_connection().await;
                    // Retry with fresh connection
                    let new_conn = self.get_connection().await?;
                    new_conn.send_request(request).await
                } else {
                    Err(e)
                }
            }
        }
    }

    pub fn device_manager(&self) -> &Arc<DeviceManager> {
        &self.device_manager
    }
}

// ============================================================================
// Tool Parameter Structs (each derives JsonSchema for auto schema generation)
// ============================================================================

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct GetUiTreeParams {
    /// Include elements not currently visible (default: false)
    pub include_invisible: Option<bool>,
    /// Maximum tree depth (0 = unlimited)
    pub max_depth: Option<i32>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct ScreenshotParams {
    /// Image quality: "full" (80%) or "thumbnail" (40%)
    pub quality: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct FindElementsParams {
    /// Text content to match
    pub text: Option<String>,
    /// Content description to match
    pub content_desc: Option<String>,
    /// Resource ID to match (suffix match)
    pub resource_id: Option<String>,
    /// Class name to match
    pub class_name: Option<String>,
    /// Filter by clickable property
    pub clickable: Option<bool>,
    /// Filter by scrollable property
    pub scrollable: Option<bool>,
    /// Filter by focusable property
    pub focusable: Option<bool>,
    /// Filter by long_clickable property
    pub long_clickable: Option<bool>,
    /// Filter by checkable property
    pub checkable: Option<bool>,
    /// Filter by checked property
    pub checked: Option<bool>,
    /// Return all matches (default: false)
    pub find_all: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct GetNotificationsParams {
    /// Return only active notifications (default: true)
    pub active_only: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct EnableEventsParams {
    /// Enable or disable event streaming
    pub enable: bool,
    /// Event types to enable (empty = all events)
    pub event_types: Option<Vec<String>>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct SetClipboardParams {
    /// Text to set in clipboard
    pub text: String,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct TapParams {
    /// X coordinate (pixels)
    pub x: Option<i32>,
    /// Y coordinate (pixels)
    pub y: Option<i32>,
    /// Find element by text
    pub text: Option<String>,
    /// Find element by resource ID
    pub resource_id: Option<String>,
    /// Find element by content description
    pub content_desc: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct LongPressParams {
    /// X coordinate
    pub x: Option<i32>,
    /// Y coordinate
    pub y: Option<i32>,
    /// Press duration in milliseconds (default: 1000)
    pub duration_ms: Option<i32>,
    /// Find element by text
    pub text: Option<String>,
    /// Find element by resource ID
    pub resource_id: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct SwipeParams {
    /// Start X coordinate
    pub start_x: i32,
    /// Start Y coordinate
    pub start_y: i32,
    /// End X coordinate
    pub end_x: i32,
    /// End Y coordinate
    pub end_y: i32,
    /// Duration in ms (default: 300, <200 = fling)
    pub duration_ms: Option<i32>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct DoubleTapParams {
    /// X coordinate (pixels)
    pub x: Option<i32>,
    /// Y coordinate (pixels)
    pub y: Option<i32>,
    /// Find element by text
    pub text: Option<String>,
    /// Find element by resource ID
    pub resource_id: Option<String>,
    /// Find element by content description
    pub content_desc: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct PinchParams {
    /// Center X coordinate
    pub center_x: i32,
    /// Center Y coordinate
    pub center_y: i32,
    /// Scale factor (>1.0 = zoom in, <1.0 = zoom out)
    pub scale: f32,
    /// Duration in milliseconds (default: 300)
    pub duration_ms: Option<i32>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct DragParams {
    /// Start X coordinate
    pub from_x: i32,
    /// Start Y coordinate
    pub from_y: i32,
    /// End X coordinate
    pub to_x: i32,
    /// End Y coordinate
    pub to_y: i32,
    /// Duration in milliseconds (default: 500)
    pub duration_ms: Option<i32>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct FlingParams {
    /// Direction: "up", "down", "left", "right"
    pub direction: String,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct InputTextParams {
    /// Text to input
    pub text: String,
    /// Find input field by current text/hint
    pub element_text: Option<String>,
    /// Find input field by resource ID
    pub resource_id: Option<String>,
    /// Append to existing text (default: false)
    pub append: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct PressKeyParams {
    /// Key name: "back", "home", "enter", "delete", "tab", "space", etc.
    pub key: String,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct GlobalActionParams {
    /// Action: "back", "home", "recents", "notifications", "quick_settings"
    pub action: String,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct LaunchAppParams {
    /// App package name (e.g., "com.android.chrome")
    pub package_name: String,
    /// Specific activity to launch
    pub activity: Option<String>,
    /// Clear existing task stack
    pub clear_task: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct CloseAppParams {
    /// App package name
    pub package_name: String,
    /// Force-stop via ADB (default: false)
    pub force: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct OpenUrlParams {
    /// URL or deep link to open
    pub url: String,
    /// Specific browser package
    pub browser_package: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct WaitForElementParams {
    /// Element text to wait for
    pub text: Option<String>,
    /// Element resource ID
    pub resource_id: Option<String>,
    /// Element content description
    pub content_desc: Option<String>,
    /// Timeout in milliseconds (default: 5000)
    pub timeout_ms: Option<i32>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct WaitForIdleParams {
    /// Timeout in milliseconds (default: 5000)
    pub timeout_ms: Option<i32>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct ClearAppDataParams {
    /// App package name to clear data for
    pub package_name: String,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct WaitForGoneParams {
    /// Element text to wait to disappear
    pub text: Option<String>,
    /// Element resource ID
    pub resource_id: Option<String>,
    /// Element content description
    pub content_desc: Option<String>,
    /// Timeout in milliseconds (default: 5000)
    pub timeout_ms: Option<i32>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct ScrollToElementParams {
    /// Element text to scroll to
    pub text: Option<String>,
    /// Element resource ID
    pub resource_id: Option<String>,
    /// Element content description
    pub content_desc: Option<String>,
    /// Scroll direction: "up", "down", "left", "right" (default: "up" scrolls content down)
    pub direction: Option<String>,
    /// Maximum number of scrolls to attempt (default: 20)
    pub max_scrolls: Option<i32>,
    /// Total timeout in milliseconds (default: 30000)
    pub timeout_ms: Option<i32>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct GetScreenContextParams {
    /// Include all elements (true) or only interactive/text elements (false, default)
    pub include_all_elements: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct CaptureLogcatParams {
    /// Filter by package name
    pub package: Option<String>,
    /// Log level: "V", "D", "I", "W", "E", "F" (default: "W")
    pub level: Option<String>,
    /// Number of lines to return (default: 100)
    pub lines: Option<i32>,
    /// Return only crash reports (FATAL EXCEPTION blocks)
    pub crash_only: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct ScreenshotDiffParams {
    /// Reference screenshot as base64-encoded JPEG
    pub reference_base64: String,
    /// Similarity threshold (0.0-1.0, default: 0.95)
    pub threshold: Option<f64>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct GetRecentToastsParams {
    /// Only return toasts from the last N milliseconds (default: 5000)
    pub since_ms: Option<i64>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct ListDevicesParams {
    /// Force refresh device list (default: true)
    pub refresh: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct SelectDeviceParams {
    /// Device ID to select (from android_list_devices output)
    pub device_id: String,
    /// Auto-enable missing permissions (default: false)
    pub auto_enable_permissions: Option<bool>,
}

// ============================================================================
// NeuralBridge MCP Server
// ============================================================================

#[derive(Clone)]
pub struct NeuralBridgeServer {
    #[allow(dead_code)]
    state: Arc<AppState>,
    tool_router: ToolRouter<Self>,
}

#[tool_handler]
impl ServerHandler for NeuralBridgeServer {
    fn get_info(&self) -> ServerInfo {
        ServerInfo {
            instructions: Some(
                "NeuralBridge: AI-native Android automation. \
                 Provides tools for UI observation, gesture control, \
                 app management, and device interaction."
                    .into(),
            ),
            capabilities: ServerCapabilities::builder().enable_tools().build(),
            ..Default::default()
        }
    }
}

#[tool_router]
impl NeuralBridgeServer {
    pub fn new(state: Arc<AppState>) -> Self {
        Self {
            state,
            tool_router: Self::tool_router(),
        }
    }

    // ========================================================================
    // OBSERVE tools
    // ========================================================================

    #[tool(
        name = "android_get_ui_tree",
        description = "Get the UI tree of the current screen. Returns all visible UI elements with IDs, text, bounds, and semantic types. Use for understanding screen structure, finding interactive elements, or debugging selectors. Prefer resource_id for stable element identification."
    )]
    async fn android_get_ui_tree(
        &self,
        Parameters(params): Parameters<GetUiTreeParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_get_ui_tree");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::GetUiTree(pb::GetUiTreeRequest {
                include_invisible: params.include_invisible.unwrap_or(false),
                include_webview: false,
                max_depth: params.max_depth.unwrap_or(0),
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to get UI tree: {}",
                response.error_message
            ))]));
        }

        // Extract UI tree result
        let ui_tree = match response.result {
            Some(pb::response::Result::UiTree(tree)) => tree,
            _ => return Ok(CallToolResult::error(vec![Content::text(
                "Invalid response: expected UI tree".to_string()
            )])),
        };

        // Convert to JSON
        let result = serde_json::json!({
            "success": true,
            "elements": ui_tree.elements.iter().map(|e| {
                serde_json::json!({
                    "element_id": e.element_id,
                    "resource_id": e.resource_id,
                    "class_name": e.class_name,
                    "text": e.text,
                    "content_description": e.content_description,
                    "bounds": {
                        "left": e.bounds.as_ref().map(|b| b.left).unwrap_or(0),
                        "top": e.bounds.as_ref().map(|b| b.top).unwrap_or(0),
                        "right": e.bounds.as_ref().map(|b| b.right).unwrap_or(0),
                        "bottom": e.bounds.as_ref().map(|b| b.bottom).unwrap_or(0),
                    },
                    "visible": e.visible,
                    "enabled": e.enabled,
                    "clickable": e.clickable,
                    "semantic_type": e.semantic_type,
                })
            }).collect::<Vec<_>>(),
            "foreground_app": ui_tree.foreground_app,
            "total_nodes": ui_tree.total_nodes,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_screenshot",
        description = "Capture a screenshot. Returns base64-encoded JPEG. Quality: 'full' (~50KB) or 'thumbnail' (~20KB). Typical latency: ~60ms (MediaProjection) or ~200ms (ADB fallback). Note: On headless emulators or without user consent, MediaProjection will fail and automatically fall back to ADB screencap (slower but headless-compatible)."
    )]
    async fn android_screenshot(
        &self,
        Parameters(params): Parameters<ScreenshotParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_screenshot");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Parse quality parameter
        let quality = match params.quality.as_deref() {
            Some("thumbnail") => pb::ScreenshotQuality::Thumbnail,
            _ => pb::ScreenshotQuality::Full,
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::Screenshot(pb::ScreenshotRequest {
                quality: quality as i32,
                use_adb_fallback: false,
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success - if MediaProjection fails, fall back to ADB
        if !response.success {
            // Check if this is a MediaProjection unavailable error
            if response.error_message.contains("MediaProjection") {
                info!("MediaProjection unavailable, falling back to ADB screencap");

                // Get device ID
                let device_id = self.state.device_id.read().await;
                let device_id_str = device_id.as_ref()
                    .ok_or_else(|| to_mcp_error(anyhow::anyhow!("No device selected")))?;

                // Execute ADB screencap
                let adb = self.state.device_manager().adb();
                let screenshot_data = adb.screenshot(device_id_str).await
                    .map_err(|e| to_mcp_error(anyhow::anyhow!("ADB screencap failed: {}", e)))?;

                // Encode as base64
                let base64_image = base64::Engine::encode(
                    &base64::engine::general_purpose::STANDARD,
                    &screenshot_data
                );

                // Return result (PNG format from ADB)
                let result = serde_json::json!({
                    "success": true,
                    "image_data": base64_image,
                    "width": 0,  // Unknown from ADB
                    "height": 0, // Unknown from ADB
                    "format": "png",
                    "method": "adb_fallback",
                });

                return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
            }

            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to capture screenshot: {}",
                response.error_message
            ))]));
        }

        // Extract screenshot result
        let screenshot = match response.result {
            Some(pb::response::Result::ScreenshotResult(screenshot)) => screenshot,
            _ => return Ok(CallToolResult::error(vec![Content::text(
                "Invalid response: expected screenshot result".to_string()
            )])),
        };

        // Encode image data as base64
        let base64_image = base64::Engine::encode(
            &base64::engine::general_purpose::STANDARD,
            &screenshot.image_data
        );

        // Return result with base64-encoded image
        let result = serde_json::json!({
            "success": true,
            "image_data": base64_image,
            "width": screenshot.width,
            "height": screenshot.height,
            "format": match screenshot.format {
                1 => "jpeg",
                2 => "png",
                _ => "unknown",
            },
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_find_elements",
        description = "Find UI elements by text, resource ID, content description, or class name. Prefer resource_id for stable identification (e.g., 'com.app:id/login_button'). Set find_all=true to get all matches. Returns bounds for coordinate-based actions."
    )]
    async fn android_find_elements(
        &self,
        Parameters(params): Parameters<FindElementsParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_find_elements");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Validate selector has at least one non-empty field or boolean filter
        validate_selector(
            params.text.as_ref(),
            params.resource_id.as_ref(),
            params.content_desc.as_ref(),
            params.class_name.as_ref(),
            params.clickable,
            params.scrollable,
            params.focusable,
            params.long_clickable,
            params.checkable,
            params.checked,
        )?;

        // Build selector from params
        let selector = pb::Selector {
            text: params.text.unwrap_or_default(),
            content_desc: params.content_desc.unwrap_or_default(),
            resource_id: params.resource_id.unwrap_or_default(),
            class_name: params.class_name.unwrap_or_default(),
            element_id: String::new(),
            exact_match: false,
            visible_only: true,
            enabled_only: false,
            clickable: params.clickable,
            scrollable: params.scrollable,
            focusable: params.focusable,
            long_clickable: params.long_clickable,
            checkable: params.checkable,
            checked: params.checked,
            index: 0,
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::FindElements(pb::FindElementsRequest {
                selector: Some(selector),
                find_all: params.find_all.unwrap_or(false),
                visible_only: true,
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            // Provide actionable error messages based on error code
            let error_msg = if response.error_code == pb::ErrorCode::ElementNotFound as i32 {
                format!(
                    "Failed to find elements: {}\nSuggestion: Try android_screenshot and android_get_ui_tree to see available elements.",
                    response.error_message
                )
            } else if response.error_code == pb::ErrorCode::ElementAmbiguous as i32 {
                format!(
                    "Failed to find elements: {}\nSuggestion: Multiple elements match. Use more specific selector (resource_id preferred) or set find_all=true to see all matches.",
                    response.error_message
                )
            } else {
                format!("Failed to find elements: {}", response.error_message)
            };
            return Ok(CallToolResult::error(vec![Content::text(error_msg)]));
        }

        // Extract element list result
        let element_list = match response.result {
            Some(pb::response::Result::ElementList(list)) => list,
            _ => return Ok(CallToolResult::error(vec![Content::text(
                "Invalid response: expected element list".to_string()
            )])),
        };

        // Convert to JSON
        let result = serde_json::json!({
            "success": true,
            "elements": element_list.elements.iter().map(|e| {
                serde_json::json!({
                    "element_id": e.element_id,
                    "resource_id": e.resource_id,
                    "class_name": e.class_name,
                    "text": e.text,
                    "content_description": e.content_description,
                    "bounds": {
                        "left": e.bounds.as_ref().map(|b| b.left).unwrap_or(0),
                        "top": e.bounds.as_ref().map(|b| b.top).unwrap_or(0),
                        "right": e.bounds.as_ref().map(|b| b.right).unwrap_or(0),
                        "bottom": e.bounds.as_ref().map(|b| b.bottom).unwrap_or(0),
                    },
                    "visible": e.visible,
                    "enabled": e.enabled,
                    "clickable": e.clickable,
                })
            }).collect::<Vec<_>>(),
            "total_matches": element_list.total_matches,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_get_foreground_app",
        description = "Get the package name and activity of the currently visible app."
    )]
    async fn android_get_foreground_app(&self) -> Result<CallToolResult, McpError> {
        info!("Tool: android_get_foreground_app");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::GetForegroundApp(pb::GetForegroundAppRequest {})),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to get foreground app: {}",
                response.error_message
            ))]));
        }

        // Extract app info result
        let app_info = match response.result {
            Some(pb::response::Result::AppInfo(info)) => info,
            _ => return Ok(CallToolResult::error(vec![Content::text(
                "Invalid response: expected app info".to_string()
            )])),
        };

        // Convert to JSON
        let result = serde_json::json!({
            "success": true,
            "package_name": app_info.package_name,
            "activity_name": app_info.activity_name,
            "is_launcher": app_info.is_launcher,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_get_device_info",
        description = "Get device information including manufacturer, model, Android version, SDK level, and screen dimensions."
    )]
    async fn android_get_device_info(&self) -> Result<CallToolResult, McpError> {
        info!("Tool: android_get_device_info");

        // Get device ID
        let device_id = self.state.device_id.read().await;
        let device_id_str = device_id.as_ref()
            .ok_or_else(|| to_mcp_error(anyhow::anyhow!("No device selected")))?;

        // Get device info via ADB
        let adb = self.state.device_manager().adb();
        let device_info = adb.get_device_info(device_id_str).await
            .map_err(|e| to_mcp_error(anyhow::anyhow!("Failed to get device info: {}", e)))?;

        // Return the device info JSON
        Ok(CallToolResult::success(vec![Content::text(device_info.to_string())]))
    }

    #[tool(
        name = "android_get_screen_context",
        description = "Get a comprehensive snapshot of the current screen for AI analysis. Returns foreground app info, simplified UI tree (interactive elements and text), and a thumbnail screenshot in a single efficient call."
    )]
    async fn android_get_screen_context(
        &self,
        Parameters(params): Parameters<GetScreenContextParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_get_screen_context");

        let include_all = params.include_all_elements.unwrap_or(false);

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Step 1: Get foreground app info
        let app_request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::GetForegroundApp(pb::GetForegroundAppRequest {})),
        };

        let app_response = conn.send_request(app_request).await
            .map_err(to_mcp_error)?;

        let app_info = match app_response.result {
            Some(pb::response::Result::AppInfo(info)) => info,
            _ => {
                return Ok(CallToolResult::error(vec![Content::text(
                    "Failed to get foreground app info".to_string()
                )]));
            }
        };

        // Step 2: Get UI tree (visible elements only)
        let tree_request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::GetUiTree(pb::GetUiTreeRequest {
                include_invisible: false,
                include_webview: false,
                max_depth: 0,
            })),
        };

        let tree_response = conn.send_request(tree_request).await
            .map_err(to_mcp_error)?;

        let ui_tree = match tree_response.result {
            Some(pb::response::Result::UiTree(tree)) => tree,
            _ => {
                return Ok(CallToolResult::error(vec![Content::text(
                    "Failed to get UI tree".to_string()
                )]));
            }
        };

        // Step 3: Get thumbnail screenshot
        let screenshot_request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::Screenshot(pb::ScreenshotRequest {
                quality: pb::ScreenshotQuality::Thumbnail as i32,
                use_adb_fallback: false,
            })),
        };

        let screenshot_response = conn.send_request(screenshot_request).await
            .map_err(to_mcp_error)?;

        let screenshot_result = match screenshot_response.result {
            Some(pb::response::Result::ScreenshotResult(result)) => result,
            _ => {
                return Ok(CallToolResult::error(vec![Content::text(
                    "Failed to get screenshot".to_string()
                )]));
            }
        };

        // Step 4: Filter elements to interactive/text elements (unless include_all is true)
        let filtered_elements: Vec<_> = ui_tree.elements.iter()
            .filter(|e| {
                if include_all {
                    true
                } else {
                    // Include interactive elements or text-bearing elements
                    e.clickable || e.focusable || e.checkable || e.scrollable || !e.text.is_empty()
                }
            })
            .map(|e| {
                // Calculate center coordinates
                let bounds = e.bounds.as_ref();
                let center_x = bounds.map(|b| (b.left + b.right) / 2).unwrap_or(0);
                let center_y = bounds.map(|b| (b.top + b.bottom) / 2).unwrap_or(0);

                serde_json::json!({
                    "element_id": e.element_id,
                    "resource_id": e.resource_id,
                    "class_name": e.class_name,
                    "text": e.text,
                    "content_description": e.content_description,
                    "bounds": bounds.map(|b| serde_json::json!({
                        "left": b.left,
                        "top": b.top,
                        "right": b.right,
                        "bottom": b.bottom,
                    })),
                    "center": {
                        "x": center_x,
                        "y": center_y,
                    },
                    "clickable": e.clickable,
                    "focusable": e.focusable,
                    "checkable": e.checkable,
                    "scrollable": e.scrollable,
                    "enabled": e.enabled,
                    "visible": e.visible,
                })
            })
            .collect();

        // Step 5: Build comprehensive response
        let result = serde_json::json!({
            "success": true,
            "app_info": {
                "package_name": app_info.package_name,
                "activity_name": app_info.activity_name,
                "is_launcher": app_info.is_launcher,
            },
            "ui_tree": {
                "total_elements": ui_tree.total_nodes,
                "filtered_elements": filtered_elements.len(),
                "elements": filtered_elements,
            },
            "screenshot": {
                "width": screenshot_result.width,
                "height": screenshot_result.height,
                "format": "jpeg",
                "data": base64::engine::general_purpose::STANDARD.encode(&screenshot_result.image_data),
                "size_bytes": screenshot_result.image_data.len(),
            },
            "capture_timestamp": ui_tree.capture_timestamp,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    // ========================================================================
    // ACT tools
    // ========================================================================

    #[tool(
        name = "android_tap",
        description = "Tap at (x,y) coordinates or on an element matching text/resource_id/content_desc. Typical latency: ~64ms. Network overhead: <15ms."
    )]
    async fn android_tap(
        &self,
        Parameters(params): Parameters<TapParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_tap");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Check if using coordinates (no retry) or selector (with retry)
        let use_selector = params.x.is_none() || params.y.is_none();

        // Build target (coordinates or selector)
        let target = if let (Some(x), Some(y)) = (params.x, params.y) {
            Some(pb::tap_request::Target::Coordinates(pb::Point { x, y }))
        } else {
            // Validate selector has at least one non-empty field
            validate_selector(
                params.text.as_ref(),
                params.resource_id.as_ref(),
                params.content_desc.as_ref(),
                None, // class_name not exposed in tap params
                None, None, None, None, None, None, // no boolean filters in tap
            )?;

            // Build selector from params
            Some(pb::tap_request::Target::Selector(pb::Selector {
                text: params.text.unwrap_or_default(),
                content_desc: params.content_desc.unwrap_or_default(),
                resource_id: params.resource_id.unwrap_or_default(),
                class_name: String::new(),
                element_id: String::new(),
                exact_match: false,
                visible_only: true,
                enabled_only: true,
                clickable: None,
                scrollable: None,
                focusable: None,
                long_clickable: None,
                checkable: None,
                checked: None,
                index: 0,
            }))
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::Tap(pb::TapRequest { target })),
        };

        // Send and await response (with retry for selector-based taps only)
        let response = if use_selector {
            retry_on_transient(&conn, request, 1).await
        } else {
            conn.send_request(request).await
        }.map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            // Provide actionable error messages based on error code
            let error_msg = if response.error_code == pb::ErrorCode::ElementNotFound as i32 {
                format!(
                    "Failed to tap: {}\nSuggestion: Element not found. Try android_find_elements to verify the selector, or use android_get_ui_tree to see all available elements.",
                    response.error_message
                )
            } else if response.error_code == pb::ErrorCode::ElementNotVisible as i32 {
                format!(
                    "Failed to tap: {}\nSuggestion: Element is not visible. Try android_swipe to scroll the element into view first.",
                    response.error_message
                )
            } else if response.error_code == pb::ErrorCode::ElementNotEnabled as i32 {
                format!(
                    "Failed to tap: {}\nSuggestion: Element is disabled. Check if a loading state or condition needs to be satisfied first.",
                    response.error_message
                )
            } else {
                format!("Failed to tap: {}", response.error_message)
            };
            return Ok(CallToolResult::error(vec![Content::text(error_msg)]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_long_press",
        description = "Long press at coordinates or element. Default duration: 1000ms. Use for triggering context menus, selecting text, or activating long-press actions."
    )]
    async fn android_long_press(
        &self,
        Parameters(params): Parameters<LongPressParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_long_press");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build target (coordinates or selector)
        let target = if let (Some(x), Some(y)) = (params.x, params.y) {
            Some(pb::long_press_request::Target::Coordinates(pb::Point { x, y }))
        } else {
            // Validate selector has at least one non-empty field
            validate_selector(
                params.text.as_ref(),
                params.resource_id.as_ref(),
                None, // content_desc not in params
                None, // class_name not in params
                None, None, None, None, None, None, // no boolean filters in long_press
            )?;

            // Build selector from params
            Some(pb::long_press_request::Target::Selector(pb::Selector {
                text: params.text.unwrap_or_default(),
                content_desc: String::new(),
                resource_id: params.resource_id.unwrap_or_default(),
                class_name: String::new(),
                element_id: String::new(),
                exact_match: false,
                visible_only: true,
                enabled_only: true,
                clickable: None,
                scrollable: None,
                focusable: None,
                long_clickable: None,
                checkable: None,
                checked: None,
                index: 0,
            }))
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::LongPress(pb::LongPressRequest {
                target,
                duration_ms: params.duration_ms.unwrap_or(1000),
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to long press: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_swipe",
        description = "Swipe from (start_x,start_y) to (end_x,end_y). Default duration: 300ms. Duration <200ms creates a fast fling gesture. Use for scrolling, swiping between pages, or dismissing items."
    )]
    async fn android_swipe(
        &self,
        Parameters(params): Parameters<SwipeParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_swipe");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::Swipe(pb::SwipeRequest {
                start: Some(pb::Point {
                    x: params.start_x,
                    y: params.start_y,
                }),
                end: Some(pb::Point {
                    x: params.end_x,
                    y: params.end_y,
                }),
                duration_ms: params.duration_ms.unwrap_or(300),
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to swipe: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_double_tap",
        description = "Double tap at (x,y) coordinates or on an element matching text/resource_id/content_desc. Typical latency: ~203ms (includes 100ms gap between taps). Network overhead: <15ms."
    )]
    async fn android_double_tap(
        &self,
        Parameters(params): Parameters<DoubleTapParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_double_tap");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build target (coordinates or selector)
        let target = if let (Some(x), Some(y)) = (params.x, params.y) {
            Some(pb::double_tap_request::Target::Coordinates(pb::Point { x, y }))
        } else {
            // Validate selector has at least one non-empty field
            validate_selector(
                params.text.as_ref(),
                params.resource_id.as_ref(),
                params.content_desc.as_ref(),
                None,
                None, None, None, None, None, None, // no boolean filters in double_tap
            )?;

            // Build selector from params
            Some(pb::double_tap_request::Target::Selector(pb::Selector {
                text: params.text.unwrap_or_default(),
                content_desc: params.content_desc.unwrap_or_default(),
                resource_id: params.resource_id.unwrap_or_default(),
                class_name: String::new(),
                element_id: String::new(),
                exact_match: false,
                visible_only: true,
                enabled_only: true,
                clickable: None,
                scrollable: None,
                focusable: None,
                long_clickable: None,
                checkable: None,
                checked: None,
                index: 0,
            }))
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::DoubleTap(pb::DoubleTapRequest { target })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to double tap: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_pinch",
        description = "Pinch zoom gesture. Scale >1.0 = zoom in, <1.0 = zoom out. Typical latency: ~305ms (includes gesture duration). Example: scale=2.0 zooms in 2x, scale=0.5 zooms out 50%."
    )]
    async fn android_pinch(
        &self,
        Parameters(params): Parameters<PinchParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_pinch(scale={})", params.scale);

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::Pinch(pb::PinchRequest {
                center: Some(pb::Point {
                    x: params.center_x,
                    y: params.center_y,
                }),
                scale: params.scale,
                duration_ms: params.duration_ms.unwrap_or(300),
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to pinch: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_drag",
        description = "Drag from (from_x,from_y) to (to_x,to_y). Default duration: 500ms. Typical latency: ~508ms (includes specified duration). Use for dragging list items, sliders, or drag-and-drop operations."
    )]
    async fn android_drag(
        &self,
        Parameters(params): Parameters<DragParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_drag");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::Drag(pb::DragRequest {
                from: Some(pb::Point {
                    x: params.from_x,
                    y: params.from_y,
                }),
                to: Some(pb::Point {
                    x: params.to_x,
                    y: params.to_y,
                }),
                duration_ms: params.duration_ms.unwrap_or(500),
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to drag: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_fling",
        description = "Fling in a direction: up, down, left, right. Typical latency: ~153-158ms. Fast gesture for scrolling lists, pages, or continuous content. Directions: 'up' (scroll down content), 'down' (scroll up content), 'left' (next page), 'right' (previous page)."
    )]
    async fn android_fling(
        &self,
        Parameters(params): Parameters<FlingParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_fling({})", params.direction);

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Map direction string to enum
        let direction = match params.direction.to_lowercase().as_str() {
            "up" => pb::Direction::Up as i32,
            "down" => pb::Direction::Down as i32,
            "left" => pb::Direction::Left as i32,
            "right" => pb::Direction::Right as i32,
            _ => {
                return Ok(CallToolResult::error(vec![Content::text(format!(
                    "Invalid direction: '{}'. Supported: up, down, left, right",
                    params.direction
                ))]));
            }
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::Fling(pb::FlingRequest { direction })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to fling: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_input_text",
        description = "Type text into an input field. Find field by element_text or resource_id. Omit selector to type into currently focused field. Set append=true to add to existing text (default: replace). Uses clipboard + paste for fast input."
    )]
    async fn android_input_text(
        &self,
        Parameters(params): Parameters<InputTextParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_input_text ({} chars)", params.text.len());

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build target selector (if provided)
        let target = if params.element_text.is_some() || params.resource_id.is_some() {
            // Validate selector has at least one non-empty field
            validate_selector(
                params.element_text.as_ref(),
                params.resource_id.as_ref(),
                None,
                None,
                None, None, None, None, None, None, // no boolean filters in input_text
            )?;

            Some(pb::input_text_request::Target::Selector(pb::Selector {
                text: params.element_text.unwrap_or_default(),
                content_desc: String::new(),
                resource_id: params.resource_id.unwrap_or_default(),
                class_name: String::new(),
                element_id: String::new(),
                exact_match: false,
                visible_only: true,
                enabled_only: true,
                clickable: None,
                scrollable: None,
                focusable: None,
                long_clickable: None,
                checkable: None,
                checked: None,
                index: 0,
            }))
        } else {
            None
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::InputText(pb::InputTextRequest {
                target,
                text: params.text,
                append: params.append.unwrap_or(false),
            })),
        };

        // Send and await response (with retry on transient failures)
        let response = retry_on_transient(&conn, request, 1).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to input text: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_press_key",
        description = "Press a key: 'back', 'home', 'enter', 'delete', 'tab', 'volume_up', etc."
    )]
    async fn android_press_key(
        &self,
        Parameters(params): Parameters<PressKeyParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_press_key({})", params.key);

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Map key string to KeyCode enum
        let key_code = match params.key.to_lowercase().as_str() {
            "back" => pb::KeyCode::Back as i32,
            "home" => pb::KeyCode::Home as i32,
            "menu" => pb::KeyCode::Menu as i32,
            "enter" | "return" => pb::KeyCode::Enter as i32,
            "delete" | "del" | "backspace" => pb::KeyCode::Delete as i32,
            "tab" => pb::KeyCode::Tab as i32,
            "space" => pb::KeyCode::Space as i32,
            "volume_up" | "volumeup" => pb::KeyCode::VolumeUp as i32,
            "volume_down" | "volumedown" => pb::KeyCode::VolumeDown as i32,
            "power" => pb::KeyCode::Power as i32,
            _ => {
                return Ok(CallToolResult::error(vec![Content::text(format!(
                    "Unknown key: '{}'. Supported keys: back, home, menu, enter, delete, tab, space, volume_up, volume_down, power",
                    params.key
                ))]));
            }
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::PressKey(pb::PressKeyRequest {
                key_code,
                with_meta: false,
                with_ctrl: false,
                with_alt: false,
                with_shift: false,
            })),
        };

        // Send and await response (with retry on transient failures)
        let response = retry_on_transient(&conn, request, 1).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to press key: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "key": params.key,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_global_action",
        description = "System action: 'back', 'home', 'recents', 'notifications', 'quick_settings'."
    )]
    async fn android_global_action(
        &self,
        Parameters(params): Parameters<GlobalActionParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_global_action({})", params.action);

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Map action string to GlobalAction enum
        let action = match params.action.to_lowercase().as_str() {
            "back" => pb::GlobalAction::GlobalBack as i32,
            "home" => pb::GlobalAction::GlobalHome as i32,
            "recents" | "recent" | "recent_apps" => pb::GlobalAction::GlobalRecents as i32,
            "notifications" | "notification" => pb::GlobalAction::GlobalNotifications as i32,
            "quick_settings" | "quicksettings" => pb::GlobalAction::GlobalQuickSettings as i32,
            "lock_screen" | "lockscreen" | "lock" => pb::GlobalAction::GlobalLockScreen as i32,
            "screenshot" | "take_screenshot" => pb::GlobalAction::GlobalTakeScreenshot as i32,
            _ => {
                return Ok(CallToolResult::error(vec![Content::text(format!(
                    "Unknown action: '{}'. Supported actions: back, home, recents, notifications, quick_settings, lock_screen, screenshot",
                    params.action
                ))]));
            }
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::GlobalAction(pb::GlobalActionRequest { action })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to perform global action: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "action": params.action,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    // ========================================================================
    // MANAGE tools
    // ========================================================================

    #[tool(
        name = "android_launch_app",
        description = "Launch an app by package name (e.g., 'com.android.chrome'). Optionally specify activity for direct launch. Set clear_task=true to clear existing task stack (fresh start). Fast path via companion app."
    )]
    async fn android_launch_app(
        &self,
        Parameters(params): Parameters<LaunchAppParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_launch_app({})", params.package_name);

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build target (package name or activity)
        let target = if let Some(activity) = params.activity {
            Some(pb::launch_app_request::Target::Activity(activity))
        } else {
            Some(pb::launch_app_request::Target::PackageName(params.package_name.clone()))
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::LaunchApp(pb::LaunchAppRequest {
                target,
                clear_task: params.clear_task.unwrap_or(false),
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            // Provide actionable error messages based on error code
            let error_msg = if response.error_code == pb::ErrorCode::AppNotInstalled as i32 {
                format!(
                    "Failed to launch app: {}\nSuggestion: App is not installed. Verify the package name (e.g., 'com.android.chrome') or install the app first.",
                    response.error_message
                )
            } else if response.error_code == pb::ErrorCode::ActivityNotFound as i32 {
                format!(
                    "Failed to launch app: {}\nSuggestion: Activity not found. Try launching by package_name only (without activity parameter) to use the default launcher activity.",
                    response.error_message
                )
            } else {
                format!("Failed to launch app: {}", response.error_message)
            };
            return Ok(CallToolResult::error(vec![Content::text(error_msg)]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "package_name": params.package_name,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_close_app",
        description = "Close an app. Default: graceful close via companion app (fast path ~100ms). Set force=true for ADB force-stop (slow path ~200-500ms, kills all processes immediately). Use force=true for stuck or crashed apps."
    )]
    async fn android_close_app(
        &self,
        Parameters(params): Parameters<CloseAppParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_close_app({})", params.package_name);

        // Force-stop always requires ADB (SLOW PATH)
        let force = params.force.unwrap_or(false);

        if force {
            // Get device ID
            let device_id = self.state.device_id.read().await;
            let device_id_str = device_id.as_ref()
                .ok_or_else(|| to_mcp_error(anyhow::anyhow!("No device selected")))?;

            // Execute ADB force-stop
            let adb = self.state.device_manager().adb();
            adb.force_stop(device_id_str, &params.package_name).await
                .map_err(|e| to_mcp_error(anyhow::anyhow!("Failed to force-stop app: {}", e)))?;

            // Return success result
            let result = serde_json::json!({
                "success": true,
                "package_name": params.package_name,
                "method": "adb_force_stop",
            });

            Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
        } else {
            // Try graceful close via companion app (FAST PATH)
            let conn = self.state.get_connection().await
                .map_err(to_mcp_error)?;

            let request = Request {
                request_id: Uuid::new_v4().to_string(),
                command: Some(Command::CloseApp(pb::CloseAppRequest {
                    package_name: params.package_name.clone(),
                    force: false,
                })),
            };

            let response = conn.send_request(request).await
                .map_err(to_mcp_error)?;

            if !response.success {
                return Ok(CallToolResult::error(vec![Content::text(format!(
                    "Failed to close app: {}",
                    response.error_message
                ))]));
            }

            let result = serde_json::json!({
                "success": true,
                "package_name": params.package_name,
                "method": "graceful",
                "latency_ms": response.latency_ms,
            });

            Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
        }
    }

    #[tool(
        name = "android_clear_app_data",
        description = "Clear all app data (cache, databases, shared preferences) for a package. Equivalent to Settings > Apps > Clear Data. Uses ADB shell command."
    )]
    async fn android_clear_app_data(
        &self,
        Parameters(params): Parameters<ClearAppDataParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_clear_app_data({})", params.package_name);

        // Get device ID
        let device_id = self.state.device_id.read().await;
        let device_id_str = device_id.as_ref()
            .ok_or_else(|| to_mcp_error(anyhow::anyhow!("No device selected")))?;

        // Execute ADB clear_app_data (SLOW PATH)
        let adb = self.state.device_manager().adb();
        adb.clear_app_data(device_id_str, &params.package_name).await
            .map_err(|e| to_mcp_error(anyhow::anyhow!("Failed to clear app data: {}", e)))?;

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "package_name": params.package_name,
            "message": "App data cleared successfully",
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_open_url",
        description = "Open a URL or deep link in the default browser."
    )]
    async fn android_open_url(
        &self,
        Parameters(params): Parameters<OpenUrlParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_open_url({})", params.url);

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::OpenUrl(pb::OpenUrlRequest {
                url: params.url.clone(),
                browser_package: params.browser_package.unwrap_or_default(),
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to open URL: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "url": params.url,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    // ========================================================================
    // WAIT tools
    // ========================================================================

    #[tool(
        name = "android_wait_for_element",
        description = "Wait for a UI element to appear. Default timeout: 5000ms. Use instead of fixed delays when waiting for loading, navigation, or UI updates. Returns found=false on timeout (not an error)."
    )]
    async fn android_wait_for_element(
        &self,
        Parameters(params): Parameters<WaitForElementParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_wait_for_element");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build selector from params
        let selector = pb::Selector {
            text: params.text.unwrap_or_default(),
            content_desc: params.content_desc.unwrap_or_default(),
            resource_id: params.resource_id.unwrap_or_default(),
            class_name: String::new(),
            element_id: String::new(),
            exact_match: false,
            visible_only: true,
            enabled_only: false,
            clickable: None,
            scrollable: None,
            focusable: None,
            long_clickable: None,
            checkable: None,
            checked: None,
            index: 0,
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::WaitForElement(pb::WaitForElementRequest {
                selector: Some(selector),
                timeout_ms: params.timeout_ms.unwrap_or(5000),
                poll_interval_ms: 100,
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            // Timeout is not an error, just return found=false
            if response.error_code == pb::ErrorCode::Timeout as i32 {
                let result = serde_json::json!({
                    "success": true,
                    "found": false,
                    "elapsed_ms": response.latency_ms,
                    "reason": "timeout",
                    "suggestion": "Element did not appear within timeout. Try android_screenshot and android_get_ui_tree to verify current UI state, or check if app is loading/stuck."
                });
                return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
            }

            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to wait for element: {}\nSuggestion: Check device responsiveness with android_get_foreground_app.",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "found": true,
            "elapsed_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_wait_for_gone",
        description = "Wait until a UI element disappears from the screen. Useful for waiting for loading dialogs, splash screens, or progress indicators to dismiss. Returns found=false when element is gone (success), found=true if still present after timeout."
    )]
    async fn android_wait_for_gone(
        &self,
        Parameters(params): Parameters<WaitForGoneParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_wait_for_gone");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build selector from params
        let selector = pb::Selector {
            text: params.text.unwrap_or_default(),
            content_desc: params.content_desc.unwrap_or_default(),
            resource_id: params.resource_id.unwrap_or_default(),
            class_name: String::new(),
            element_id: String::new(),
            exact_match: false,
            visible_only: true,
            enabled_only: false,
            clickable: None,
            scrollable: None,
            focusable: None,
            long_clickable: None,
            checkable: None,
            checked: None,
            index: 0,
        };

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::WaitForGone(pb::WaitForGoneRequest {
                selector: Some(selector),
                timeout_ms: params.timeout_ms.unwrap_or(5000),
                poll_interval_ms: 100,
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            // Timeout means element is still visible
            if response.error_code == pb::ErrorCode::Timeout as i32 {
                let result = serde_json::json!({
                    "success": true,
                    "found": true,
                    "elapsed_ms": response.latency_ms,
                    "reason": "timeout",
                    "message": "Element is still visible after timeout",
                });
                return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
            }

            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to wait for element to disappear: {}",
                response.error_message
            ))]));
        }

        // Return success result (element is gone)
        let result = serde_json::json!({
            "success": true,
            "found": false,
            "elapsed_ms": response.latency_ms,
            "message": "Element disappeared successfully",
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_wait_for_idle",
        description = "Wait until the UI stabilizes (no changes for 300ms)."
    )]
    async fn android_wait_for_idle(
        &self,
        Parameters(params): Parameters<WaitForIdleParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_wait_for_idle");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::WaitForIdle(pb::WaitForIdleRequest {
                timeout_ms: params.timeout_ms.unwrap_or(5000),
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            // Timeout is not an error, just return idle=false
            if response.error_code == pb::ErrorCode::Timeout as i32 {
                let result = serde_json::json!({
                    "success": true,
                    "idle": false,
                    "elapsed_ms": response.latency_ms,
                    "reason": "timeout"
                });
                return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
            }

            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to wait for idle: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "idle": true,
            "elapsed_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_scroll_to_element",
        description = "Scroll to find an element that may be off-screen. Automatically scrolls through lists, recycler views, and scroll containers until the target element is found or the end of content is reached."
    )]
    async fn android_scroll_to_element(
        &self,
        Parameters(params): Parameters<ScrollToElementParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_scroll_to_element");

        let start_time = std::time::Instant::now();
        let timeout_ms = params.timeout_ms.unwrap_or(30000);
        let max_scrolls = params.max_scrolls.unwrap_or(20);
        let direction = params.direction.unwrap_or_else(|| "up".to_string());

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build selector from params
        let selector = pb::Selector {
            text: params.text.clone().unwrap_or_default(),
            content_desc: params.content_desc.clone().unwrap_or_default(),
            resource_id: params.resource_id.clone().unwrap_or_default(),
            class_name: String::new(),
            element_id: String::new(),
            exact_match: false,
            visible_only: true,
            enabled_only: false,
            clickable: None,
            scrollable: None,
            focusable: None,
            long_clickable: None,
            checkable: None,
            checked: None,
            index: 0,
        };

        // Step 1: Check if element is already visible
        let find_request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::FindElements(pb::FindElementsRequest {
                selector: Some(selector.clone()),
                find_all: false,
                visible_only: true,
            })),
        };

        let response = conn.send_request(find_request).await
            .map_err(to_mcp_error)?;

        if response.success {
            if let Some(pb::response::Result::ElementList(element_list)) = response.result {
                if !element_list.elements.is_empty() {
                    let element = &element_list.elements[0];
                    let result = serde_json::json!({
                        "success": true,
                        "found": true,
                        "scrolls": 0,
                        "elapsed_ms": start_time.elapsed().as_millis() as i64,
                        "element": {
                            "element_id": element.element_id,
                            "bounds": element.bounds.as_ref().map(|b| serde_json::json!({
                                "left": b.left,
                                "top": b.top,
                                "right": b.right,
                                "bottom": b.bottom,
                            })),
                        },
                    });
                    return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
                }
            }
        }

        // Step 2: Get UI tree to identify scrollable elements (for future optimization)
        // For now, we'll just start scrolling

        // Convert direction string to Direction enum
        let fling_direction = match direction.to_lowercase().as_str() {
            "up" => pb::Direction::Up,
            "down" => pb::Direction::Down,
            "left" => pb::Direction::Left,
            "right" => pb::Direction::Right,
            _ => pb::Direction::Up, // default
        };

        // Step 3: Scroll loop
        let mut previous_hash: Option<u64> = None;
        let mut scroll_count = 0;

        for _i in 0..max_scrolls {
            // Check timeout
            if start_time.elapsed().as_millis() as i64 > timeout_ms as i64 {
                let result = serde_json::json!({
                    "success": false,
                    "found": false,
                    "scrolls": scroll_count,
                    "elapsed_ms": start_time.elapsed().as_millis() as i64,
                    "reason": "timeout",
                    "message": "Element not found within timeout",
                });
                return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
            }

            // Step 3a: Fling
            let fling_request = Request {
                request_id: Uuid::new_v4().to_string(),
                command: Some(Command::Fling(pb::FlingRequest {
                    direction: fling_direction as i32,
                })),
            };

            conn.send_request(fling_request).await
                .map_err(to_mcp_error)?;

            scroll_count += 1;

            // Step 3b: Wait for idle
            let idle_request = Request {
                request_id: Uuid::new_v4().to_string(),
                command: Some(Command::WaitForIdle(pb::WaitForIdleRequest {
                    timeout_ms: 300,
                })),
            };

            conn.send_request(idle_request).await
                .map_err(to_mcp_error)?;

            // Step 3c: Find element again
            let find_request = Request {
                request_id: Uuid::new_v4().to_string(),
                command: Some(Command::FindElements(pb::FindElementsRequest {
                    selector: Some(selector.clone()),
                    find_all: false,
                    visible_only: true,
                })),
            };

            let response = conn.send_request(find_request).await
                .map_err(to_mcp_error)?;

            if response.success {
                if let Some(pb::response::Result::ElementList(element_list)) = response.result {
                    if !element_list.elements.is_empty() {
                        let element = &element_list.elements[0];
                        let result = serde_json::json!({
                            "success": true,
                            "found": true,
                            "scrolls": scroll_count,
                            "elapsed_ms": start_time.elapsed().as_millis() as i64,
                            "element": {
                                "element_id": element.element_id,
                                "bounds": element.bounds.as_ref().map(|b| serde_json::json!({
                                    "left": b.left,
                                    "top": b.top,
                                    "right": b.right,
                                    "bottom": b.bottom,
                                })),
                            },
                        });
                        return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
                    }
                }
            }

            // Step 3d: Get UI tree and compute hash
            let tree_request = Request {
                request_id: Uuid::new_v4().to_string(),
                command: Some(Command::GetUiTree(pb::GetUiTreeRequest {
                    include_invisible: false,
                    include_webview: false,
                    max_depth: 0,
                })),
            };

            let response = conn.send_request(tree_request).await
                .map_err(to_mcp_error)?;

            if response.success {
                if let Some(pb::response::Result::UiTree(ui_tree)) = response.result {
                    // Compute hash of visible element IDs
                    use std::collections::hash_map::DefaultHasher;
                    use std::hash::{Hash, Hasher};

                    let mut hasher = DefaultHasher::new();
                    for element in &ui_tree.elements {
                        if element.visible {
                            element.element_id.hash(&mut hasher);
                        }
                    }
                    let current_hash = hasher.finish();

                    // Step 3e: Check if we've reached the end
                    if let Some(prev_hash) = previous_hash {
                        if current_hash == prev_hash {
                            // End of scroll reached
                            let result = serde_json::json!({
                                "success": false,
                                "found": false,
                                "scrolls": scroll_count,
                                "elapsed_ms": start_time.elapsed().as_millis() as i64,
                                "reason": "end_of_scroll",
                                "message": "Reached end of scrollable content without finding element",
                            });
                            return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
                        }
                    }

                    previous_hash = Some(current_hash);
                }
            }
        }

        // Max scrolls reached
        let result = serde_json::json!({
            "success": false,
            "found": false,
            "scrolls": scroll_count,
            "elapsed_ms": start_time.elapsed().as_millis() as i64,
            "reason": "max_scrolls",
            "message": format!("Element not found after {} scrolls", max_scrolls),
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    // ========================================================================
    // EVENT tools
    // ========================================================================

    #[tool(
        name = "android_enable_events",
        description = "Enable or disable event streaming from the device (UI changes, notifications, toasts, crashes). Event types: 'ui_change', 'notification_posted', 'toast_shown', 'app_crash'. Leave event_types empty for all events. Events are buffered (max 100, circular buffer)."
    )]
    async fn android_enable_events(
        &self,
        Parameters(params): Parameters<EnableEventsParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_enable_events(enable={})", params.enable);

        // Convert event type strings to i32 enum values
        let event_types = params.event_types.unwrap_or_default().iter().filter_map(|s| {
            match s.to_lowercase().as_str() {
                "ui_change" => Some(1),
                "notification_posted" => Some(2),
                "toast_shown" => Some(3),
                "app_crash" => Some(4),
                _ => None,
            }
        }).collect::<Vec<i32>>();

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::EnableEvents(pb::EnableEventsRequest {
                enable: params.enable,
                event_types,
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to enable events: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "enabled": params.enable,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_get_notifications",
        description = "Get current notifications. Returns title, text, package, timestamp, and clearable status. Set active_only=false to include dismissed notifications. Requires NotificationListenerService permission (granted in device Settings → Notifications → Notification access)."
    )]
    async fn android_get_notifications(
        &self,
        Parameters(params): Parameters<GetNotificationsParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_get_notifications");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::GetNotifications(pb::GetNotificationsRequest {
                active_only: params.active_only.unwrap_or(true),
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to get notifications: {}",
                response.error_message
            ))]));
        }

        // Extract notification list result
        let notification_list = match response.result {
            Some(pb::response::Result::NotificationList(list)) => list,
            _ => return Ok(CallToolResult::error(vec![Content::text(
                "Invalid response: expected notification list".to_string()
            )])),
        };

        // Convert to JSON
        let result = serde_json::json!({
            "success": true,
            "notifications": notification_list.notifications.iter().map(|n| {
                serde_json::json!({
                    "package_name": n.package_name,
                    "title": n.title,
                    "text": n.text,
                    "post_time": n.post_time,
                    "ongoing": n.ongoing,
                    "clearable": n.clearable,
                })
            }).collect::<Vec<_>>(),
            "total_count": notification_list.notifications.len(),
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    // ========================================================================
    // CLIPBOARD tools
    // ========================================================================

    #[tool(
        name = "android_get_clipboard",
        description = "Get clipboard content via ADB (slow path ~200-500ms). Works on Android 10+ where background clipboard access is restricted. Requires ADB connection. Use after android_set_clipboard to verify, or to read user-copied content."
    )]
    async fn android_get_clipboard(&self) -> Result<CallToolResult, McpError> {
        info!("Tool: android_get_clipboard");

        // Get device ID
        let device_id = self.state.device_id.read().await;
        let device_id_str = device_id.as_ref()
            .ok_or_else(|| to_mcp_error(anyhow::anyhow!("No device selected")))?;

        // Execute ADB command to get clipboard
        let adb = self.state.device_manager().adb();
        let clipboard_text = adb.get_clipboard(device_id_str).await
            .map_err(|e| to_mcp_error(anyhow::anyhow!("Failed to get clipboard: {}", e)))?;

        // Return result
        let result = serde_json::json!({
            "success": true,
            "text": clipboard_text,
            "has_content": !clipboard_text.is_empty(),
            "method": "adb",
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_set_clipboard",
        description = "Set clipboard content. Fast path via companion app (~2ms). Use for sharing text between apps, or before android_input_text as a workaround for special characters."
    )]
    async fn android_set_clipboard(
        &self,
        Parameters(params): Parameters<SetClipboardParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_set_clipboard ({} chars)", params.text.len());

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Build request
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::SetClipboard(pb::SetClipboardRequest {
                text: params.text.clone(),
            })),
        };

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        // Check success
        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to set clipboard: {}",
                response.error_message
            ))]));
        }

        // Return success result
        let result = serde_json::json!({
            "success": true,
            "text_length": params.text.len(),
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    // ========================================================================
    // TEST & DEBUG tools (Sprint 3)
    // ========================================================================

    #[tool(
        name = "android_capture_logcat",
        description = "Capture logcat output for debugging. Filter by package, log level, or crash reports. Returns recent log lines from the device. Slow path via ADB (~200-500ms)."
    )]
    async fn android_capture_logcat(
        &self,
        Parameters(params): Parameters<CaptureLogcatParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_capture_logcat");

        // Get device ID
        let device_id = self.state.device_id.read().await;
        let device_id_str = device_id.as_ref()
            .ok_or_else(|| to_mcp_error(anyhow::anyhow!("No device selected")))?;

        // Execute ADB command to capture logcat
        let adb = self.state.device_manager().adb();
        let level = params.level.as_deref().unwrap_or("W");
        let lines = params.lines.unwrap_or(100);
        let crash_only = params.crash_only.unwrap_or(false);

        let log_output = adb.capture_logcat(
            device_id_str,
            params.package.as_deref(),
            level,
            lines,
            crash_only,
        ).await
        .map_err(|e| to_mcp_error(anyhow::anyhow!("Failed to capture logcat: {}", e)))?;

        // Return result
        let result = serde_json::json!({
            "success": true,
            "log": log_output,
            "lines_returned": log_output.lines().count(),
            "package": params.package,
            "level": level,
            "crash_only": crash_only,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_screenshot_diff",
        description = "Compare a reference screenshot with the current screen. Returns a similarity score (0.0-1.0) and whether the screens match within the threshold. Use for visual regression testing and detecting UI changes."
    )]
    async fn android_screenshot_diff(
        &self,
        Parameters(params): Parameters<ScreenshotDiffParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_screenshot_diff");

        // Take a new screenshot
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::Screenshot(pb::ScreenshotRequest {
                quality: pb::ScreenshotQuality::Full as i32,
                use_adb_fallback: false,
            })),
        };

        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to take screenshot: {}",
                response.error_message
            ))]));
        }

        // Extract screenshot result
        let screenshot_result = match response.result {
            Some(pb::response::Result::ScreenshotResult(result)) => result,
            _ => return Ok(CallToolResult::error(vec![Content::text(
                "Invalid response: expected screenshot result".to_string()
            )])),
        };

        // Decode both images
        let reference_bytes = base64::engine::general_purpose::STANDARD
            .decode(&params.reference_base64)
            .map_err(|e| to_mcp_error(anyhow::anyhow!("Failed to decode reference image: {}", e)))?;

        let current_bytes = &screenshot_result.image_data;

        let reference_img = image::load_from_memory(&reference_bytes)
            .map_err(|e| to_mcp_error(anyhow::anyhow!("Failed to load reference image: {}", e)))?
            .to_rgba8();

        let current_img = image::load_from_memory(current_bytes)
            .map_err(|e| to_mcp_error(anyhow::anyhow!("Failed to load current screenshot: {}", e)))?
            .to_rgba8();

        // Check dimensions match
        if reference_img.dimensions() != current_img.dimensions() {
            let result = serde_json::json!({
                "success": true,
                "match": false,
                "similarity": 0.0,
                "reference_dimensions": {
                    "width": reference_img.width(),
                    "height": reference_img.height(),
                },
                "current_dimensions": {
                    "width": current_img.width(),
                    "height": current_img.height(),
                },
                "reason": "Dimensions mismatch",
            });
            return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
        }

        // Compare pixels
        let total_pixels = (reference_img.width() * reference_img.height()) as usize;
        let mut matching_pixels = 0;

        for (ref_pixel, cur_pixel) in reference_img.pixels().zip(current_img.pixels()) {
            // Check if pixels match within tolerance (±10 per channel)
            let r_diff = (ref_pixel[0] as i32 - cur_pixel[0] as i32).abs();
            let g_diff = (ref_pixel[1] as i32 - cur_pixel[1] as i32).abs();
            let b_diff = (ref_pixel[2] as i32 - cur_pixel[2] as i32).abs();

            if r_diff <= 10 && g_diff <= 10 && b_diff <= 10 {
                matching_pixels += 1;
            }
        }

        let similarity = matching_pixels as f64 / total_pixels as f64;
        let threshold = params.threshold.unwrap_or(0.95);
        let is_match = similarity >= threshold;

        let result = serde_json::json!({
            "success": true,
            "match": is_match,
            "similarity": similarity,
            "threshold": threshold,
            "width": reference_img.width(),
            "height": reference_img.height(),
            "total_pixels": total_pixels,
            "matching_pixels": matching_pixels,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_get_recent_toasts",
        description = "Get recently shown toast messages. Requires event streaming to be enabled first via android_enable_events. Returns toasts from the event buffer."
    )]
    async fn android_get_recent_toasts(
        &self,
        Parameters(params): Parameters<GetRecentToastsParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_get_recent_toasts");

        let since_ms = params.since_ms.unwrap_or(5000);
        let current_time = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64;
        let cutoff_time = current_time - since_ms;

        // Read from event buffer
        let event_buffer = self.state.event_buffer.read().await;
        let toasts: Vec<_> = event_buffer
            .iter()
            .filter(|event| {
                event.event_type == pb::EventType::ToastShown as i32
                    && event.timestamp >= cutoff_time
            })
            .filter_map(|event| {
                match &event.data {
                    Some(pb::event::Data::Toast(toast)) => Some(serde_json::json!({
                        "text": toast.text,
                        "timestamp": event.timestamp,
                    })),
                    _ => None,
                }
            })
            .collect();

        let result = serde_json::json!({
            "success": true,
            "toasts": toasts,
            "total_count": toasts.len(),
            "since_ms": since_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_pull_to_refresh",
        description = "Perform a pull-to-refresh gesture. Swipes down from the top portion of the screen. Useful for refreshing content in apps that support pull-to-refresh."
    )]
    async fn android_pull_to_refresh(&self) -> Result<CallToolResult, McpError> {
        info!("Tool: android_pull_to_refresh");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Perform swipe down from (540, 400) to (540, 1400) over 500ms
        let swipe_request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::Swipe(pb::SwipeRequest {
                start: Some(pb::Point { x: 540, y: 400 }),
                end: Some(pb::Point { x: 540, y: 1400 }),
                duration_ms: 500,
            })),
        };

        let response = conn.send_request(swipe_request).await
            .map_err(to_mcp_error)?;

        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to perform pull-to-refresh: {}",
                response.error_message
            ))]));
        }

        // Wait for UI to settle
        let wait_request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::WaitForIdle(pb::WaitForIdleRequest {
                timeout_ms: 2000,
            })),
        };

        conn.send_request(wait_request).await
            .map_err(to_mcp_error)?;

        let result = serde_json::json!({
            "success": true,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_dismiss_keyboard",
        description = "Dismiss the on-screen keyboard if it is currently visible. Uses the system back action, which is the most reliable method across Android versions."
    )]
    async fn android_dismiss_keyboard(&self) -> Result<CallToolResult, McpError> {
        info!("Tool: android_dismiss_keyboard");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Send global back action
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::GlobalAction(pb::GlobalActionRequest {
                action: pb::GlobalAction::GlobalBack as i32,
            })),
        };

        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to dismiss keyboard: {}",
                response.error_message
            ))]));
        }

        let result = serde_json::json!({
            "success": true,
            "latency_ms": response.latency_ms,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_accessibility_audit",
        description = "Audit the current screen for accessibility issues. Checks for missing content descriptions, small touch targets (<48dp), and non-focusable interactive elements. Returns a list of violations with element information."
    )]
    async fn android_accessibility_audit(&self) -> Result<CallToolResult, McpError> {
        info!("Tool: android_accessibility_audit");

        // Get connection
        let conn = self.state.get_connection().await
            .map_err(to_mcp_error)?;

        // Get UI tree
        let request = Request {
            request_id: Uuid::new_v4().to_string(),
            command: Some(Command::GetUiTree(pb::GetUiTreeRequest {
                include_invisible: false,
                include_webview: false,
                max_depth: 0,
            })),
        };

        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

        if !response.success {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to get UI tree: {}",
                response.error_message
            ))]));
        }

        // Extract UI tree
        let ui_tree = match response.result {
            Some(pb::response::Result::UiTree(tree)) => tree,
            _ => return Ok(CallToolResult::error(vec![Content::text(
                "Invalid response: expected UI tree".to_string()
            )])),
        };

        // Audit elements for accessibility issues
        let mut violations = Vec::new();

        for element in &ui_tree.elements {
            // Check if element is clickable
            if !element.clickable {
                continue;
            }

            // Get bounds (handle Option<Bounds>)
            let bounds = match &element.bounds {
                Some(b) => b,
                None => continue, // Skip elements without bounds
            };

            // Issue 1: Missing label
            if element.text.is_empty() && element.content_description.is_empty() {
                violations.push(serde_json::json!({
                    "issue": "Missing label",
                    "severity": "warning",
                    "element_id": element.element_id,
                    "resource_id": element.resource_id,
                    "class_name": element.class_name,
                    "bounds": format!("[{},{},{},{}]",
                        bounds.left, bounds.top,
                        bounds.right, bounds.bottom),
                    "recommendation": "Add text or content description for screen readers",
                }));
            }

            // Issue 2: Small touch target (< 96 pixels ≈ 48dp on mdpi)
            let width = bounds.right - bounds.left;
            let height = bounds.bottom - bounds.top;

            if width < 96 || height < 96 {
                violations.push(serde_json::json!({
                    "issue": "Small touch target",
                    "severity": "error",
                    "element_id": element.element_id,
                    "resource_id": element.resource_id,
                    "text": element.text,
                    "size": format!("{}x{}", width, height),
                    "bounds": format!("[{},{},{},{}]",
                        bounds.left, bounds.top,
                        bounds.right, bounds.bottom),
                    "recommendation": "Increase touch target to at least 48dp (96px on mdpi)",
                }));
            }

            // Issue 3: Not keyboard accessible
            if !element.focusable {
                violations.push(serde_json::json!({
                    "issue": "Not keyboard accessible",
                    "severity": "warning",
                    "element_id": element.element_id,
                    "resource_id": element.resource_id,
                    "text": element.text,
                    "bounds": format!("[{},{},{},{}]",
                        bounds.left, bounds.top,
                        bounds.right, bounds.bottom),
                    "recommendation": "Make element focusable for keyboard navigation",
                }));
            }
        }

        let result = serde_json::json!({
            "success": true,
            "total_elements": ui_tree.elements.len(),
            "interactive_elements": ui_tree.elements.iter().filter(|e| e.clickable).count(),
            "violations": violations,
            "violation_count": violations.len(),
            "score": if violations.is_empty() { "Pass" } else { "Fail" },
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    // ========================================================================
    // DEVICE DISCOVERY & SELECTION
    // ========================================================================

    #[tool(
        name = "android_list_devices",
        description = "List all connected Android devices with their status. Returns device IDs, models, and companion app readiness. Use this to discover available devices before calling android_select_device. Does not require a device to be selected first."
    )]
    async fn android_list_devices(
        &self,
        Parameters(_params): Parameters<ListDevicesParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_list_devices");

        // Discover devices via ADB (always refreshes)
        let device_ids = self.state.device_manager()
            .discover_devices()
            .await
            .map_err(to_mcp_error)?;

        if device_ids.is_empty() {
            let result = serde_json::json!({
                "devices": [],
                "total_count": 0,
                "selected_device": null,
            });
            return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
        }

        // Get currently selected device
        let selected_device = self.state.device_id.read().await.clone();

        // For each device, check permissions in parallel
        let mut device_futures = Vec::new();
        for device_id in &device_ids {
            let device_id_clone = device_id.clone();
            let device_manager = self.state.device_manager().clone();

            device_futures.push(async move {
                // Get device info
                let device_info = device_manager.get_device_info(&device_id_clone).await.ok().flatten();

                // Check permissions
                let permission_status = device_manager.check_permissions(&device_id_clone).await.ok();

                (device_id_clone, device_info, permission_status)
            });
        }

        // Execute all futures in parallel
        let results = futures::future::join_all(device_futures).await;

        // Build device list JSON
        let devices: Vec<serde_json::Value> = results.into_iter().map(|(device_id, info, perms)| {
            let model = info.as_ref().and_then(|i| i.model.clone()).unwrap_or_else(|| "Unknown".to_string());
            let android_version = info.as_ref().and_then(|i| i.android_version.clone()).unwrap_or_else(|| "Unknown".to_string());
            let state = info.as_ref().map(|i| i.state.clone()).unwrap_or_else(|| "unknown".to_string());

            let companion_installed = perms.as_ref().map(|p| p.companion_installed).unwrap_or(false);
            let accessibility_enabled = perms.as_ref().map(|p| p.accessibility_enabled).unwrap_or(false);
            let notification_listener = perms.as_ref().map(|p| p.notification_listener_enabled).unwrap_or(false);
            let is_ready = perms.as_ref().map(|p| p.is_ready()).unwrap_or(false);
            let is_selected = selected_device.as_ref() == Some(&device_id);

            serde_json::json!({
                "device_id": device_id,
                "model": model,
                "android_version": android_version,
                "state": state,
                "companion_installed": companion_installed,
                "accessibility_enabled": accessibility_enabled,
                "notification_listener": notification_listener,
                "is_ready": is_ready,
                "is_selected": is_selected,
            })
        }).collect();

        let result = serde_json::json!({
            "devices": devices,
            "total_count": device_ids.len(),
            "selected_device": selected_device,
        });

        Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
    }

    #[tool(
        name = "android_select_device",
        description = "Select an Android device for all subsequent commands. Use android_list_devices first to see available devices. Establishes connection to the companion app on the selected device."
    )]
    async fn android_select_device(
        &self,
        Parameters(params): Parameters<SelectDeviceParams>,
    ) -> Result<CallToolResult, McpError> {
        info!("Tool: android_select_device (device_id: {})", params.device_id);

        // Validate device_id format (alphanumeric + `.:-_`)
        if !params.device_id.chars().all(|c| c.is_alphanumeric() || c == '.' || c == ':' || c == '-' || c == '_') {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Invalid device_id format: '{}'. Must contain only alphanumeric characters and '.:-_'",
                params.device_id
            ))]));
        }

        // Verify device exists in ADB
        let discovered_devices = self.state.device_manager()
            .discover_devices()
            .await
            .map_err(to_mcp_error)?;

        if !discovered_devices.contains(&params.device_id) {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Device '{}' not found. Run android_list_devices to see available devices.",
                params.device_id
            ))]));
        }

        // Disconnect current device if one is selected
        {
            let current_device = self.state.device_id.read().await;
            if current_device.is_some() {
                info!("Disconnecting current device...");
                drop(current_device); // Release read lock

                // Clear connection
                self.state.clear_connection().await;

                // Remove port forwarding
                if let Err(e) = self.state.device_manager().remove_port_forwarding(&params.device_id).await {
                    warn!("Failed to remove port forwarding (non-fatal): {}", e);
                }

                // Reset permissions checked flag
                self.state.permissions_checked.store(false, std::sync::atomic::Ordering::SeqCst);
            }
        }

        // Set new device
        {
            let mut device_id_write = self.state.device_id.write().await;
            *device_id_write = Some(params.device_id.clone());
        }

        // Set auto_enable_permissions flag if requested
        let auto_enable = params.auto_enable_permissions.unwrap_or(false);
        self.state.auto_enable_permissions.store(auto_enable, std::sync::atomic::Ordering::SeqCst);

        // Eagerly connect to the device
        info!("Establishing connection to device: {}", params.device_id);

        // Check permissions (auto-enable if requested)
        if let Err(e) = self.state.check_companion_ready(auto_enable).await {
            // Get permission status for error message
            let permission_status = self.state.device_manager()
                .check_permissions(&params.device_id)
                .await
                .map_err(to_mcp_error)?;

            let result = serde_json::json!({
                "success": false,
                "device_id": params.device_id,
                "companion_status": "not_ready",
                "permissions": {
                    "companion_installed": permission_status.companion_installed,
                    "accessibility_enabled": permission_status.accessibility_enabled,
                    "notification_listener": permission_status.notification_listener_enabled,
                },
                "error": format!("Device selected but companion app not ready: {}", e),
            });

            return Ok(CallToolResult::success(vec![Content::text(result.to_string())]));
        }

        // Setup port forwarding
        if let Err(e) = self.state.device_manager().setup_port_forwarding(&params.device_id).await {
            return Ok(CallToolResult::error(vec![Content::text(format!(
                "Failed to setup port forwarding: {}",
                e
            ))]));
        }

        // Establish TCP connection
        match self.state.get_connection().await {
            Ok(_conn) => {
                // Get device info for response
                let device_info = self.state.device_manager()
                    .get_device_info(&params.device_id)
                    .await
                    .map_err(to_mcp_error)?;

                let permission_status = self.state.device_manager()
                    .check_permissions(&params.device_id)
                    .await
                    .map_err(to_mcp_error)?;

                let model = device_info
                    .as_ref()
                    .and_then(|i| i.model.clone())
                    .unwrap_or_else(|| "Unknown".to_string());

                let result = serde_json::json!({
                    "success": true,
                    "device_id": params.device_id,
                    "model": model,
                    "companion_status": "connected",
                    "permissions": {
                        "companion_installed": permission_status.companion_installed,
                        "accessibility_enabled": permission_status.accessibility_enabled,
                        "notification_listener": permission_status.notification_listener_enabled,
                    }
                });

                Ok(CallToolResult::success(vec![Content::text(result.to_string())]))
            }
            Err(e) => {
                Ok(CallToolResult::error(vec![Content::text(format!(
                    "Device selected but failed to connect: {}. Companion app may not be running.",
                    e
                ))]))
            }
        }
    }
}

// ============================================================================
// Main
// ============================================================================

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"))
        )
        .init();

    info!("NeuralBridge MCP Server v{}", env!("CARGO_PKG_VERSION"));

    let args: Vec<String> = std::env::args().collect();
    let mut device_id: Option<String> = None;
    let mut auto_discover = false;
    let mut check_mode = false;
    let mut enable_permissions = false;

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--device" => {
                i += 1;
                if i < args.len() {
                    device_id = Some(args[i].clone());
                } else {
                    error!("--device requires a device ID argument");
                    std::process::exit(1);
                }
            }
            "--auto-discover" => auto_discover = true,
            "--check" => check_mode = true,
            "--enable-permissions" => enable_permissions = true,
            "--help" | "-h" => {
                print_usage();
                return Ok(());
            }
            _ => warn!("Unknown argument: {}", args[i]),
        }
        i += 1;
    }

    let device_manager = DeviceManager::new().await?;

    if auto_discover && device_id.is_none() {
        info!("Auto-discovering Android devices...");
        match device_manager.discover_devices().await {
            Ok(devices) if devices.is_empty() => {
                error!("No Android devices found.");
                std::process::exit(1);
            }
            Ok(devices) => {
                // Prefer device where companion app is fully ready
                let mut selected = devices[0].clone();
                for d in &devices {
                    match device_manager.check_permissions(d).await {
                        Ok(status) if status.is_ready() => {
                            info!("Found fully ready device: {}", d);
                            selected = d.clone();
                            break;
                        }
                        Ok(status) => {
                            if status.companion_installed {
                                info!("Device {} has companion but missing permissions", d);
                            }
                        }
                        Err(e) => {
                            warn!("Failed to check device {}: {}", d, e);
                        }
                    }
                }
                info!("Auto-selected device: {}", selected);
                device_id = Some(selected);
            }
            Err(e) => {
                error!("Failed to discover devices: {}", e);
                std::process::exit(1);
            }
        }
    }

    if check_mode {
        return run_check_mode(&device_manager).await;
    }

    // Always discover at startup for initial state (if no device was explicitly specified)
    if device_id.is_none() {
        let discovered = device_manager.discover_devices().await.unwrap_or_default();

        if !discovered.is_empty() {
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
    }

    let app_state = Arc::new(AppState::new(device_manager));

    if let Some(ref selected) = device_id {
        *app_state.device_id.write().await = Some(selected.clone());
        app_state.auto_enable_permissions.store(enable_permissions, Ordering::SeqCst);
        info!("Starting MCP server for device: {}", selected);
        if enable_permissions {
            info!("Auto-enable permissions: ENABLED");
        }
    } else {
        info!("Starting MCP server (no device pre-selected — use android_list_devices + android_select_device)");
    }

    let server = NeuralBridgeServer::new(app_state);
    let service = server.serve(stdio()).await
        .map_err(|e| anyhow::anyhow!("Failed to start MCP server: {}", e))?;

    info!("MCP server ready. Listening on stdio...");
    service.waiting().await
        .map_err(|e| anyhow::anyhow!("MCP server error: {}", e))?;

    Ok(())
}

async fn run_check_mode(device_manager: &DeviceManager) -> Result<()> {
    info!("Running NeuralBridge setup check...");
    eprintln!();

    // Check 1: ADB installation
    eprintln!("1. Checking ADB installation...");
    match device_manager.check_adb_installed().await {
        Ok(true) => eprintln!("   ✓ ADB found"),
        Ok(false) => {
            eprintln!("   ✗ ADB not found in PATH");
            eprintln!("     Install Android SDK platform-tools");
        }
        Err(e) => eprintln!("   ✗ Failed to check ADB: {}", e),
    }
    eprintln!();

    // Check 2: Device discovery
    eprintln!("2. Discovering Android devices...");
    let devices = match device_manager.discover_devices().await {
        Ok(devices) if devices.is_empty() => {
            eprintln!("   ✗ No devices found");
            eprintln!("     Connect a device or start an emulator");
            return Ok(());
        }
        Ok(devices) => {
            eprintln!("   ✓ Found {} device(s)", devices.len());
            devices
        }
        Err(e) => {
            eprintln!("   ✗ Failed to list devices: {}", e);
            return Ok(());
        }
    };
    eprintln!();

    // Check 3: Permissions per device
    for device_id in &devices {
        eprintln!("3. Checking device: {}", device_id);

        match device_manager.check_permissions(device_id).await {
            Ok(status) => {
                eprintln!("   Companion app installed:       {}",
                    if status.companion_installed { "✓" } else { "✗" });
                eprintln!("   AccessibilityService enabled:  {}",
                    if status.accessibility_enabled { "✓" } else { "✗" });
                eprintln!("   NotificationListener enabled:  {}",
                    if status.notification_listener_enabled { "✓" } else { "✗" });

                if status.is_ready() {
                    eprintln!("   Status: ✓ READY");
                } else {
                    eprintln!("   Status: ✗ NOT READY");
                    if let Some(msg) = status.missing_permissions_message() {
                        eprintln!("   {}", msg);
                    }
                }

                // Check 4: Test connection to companion app
                if status.is_ready() {
                    eprintln!();
                    eprintln!("4. Testing connection to companion app...");

                    // Set up port forwarding
                    match device_manager.setup_port_forwarding(device_id).await {
                        Ok(_) => eprintln!("   ✓ Port forwarding setup successful"),
                        Err(e) => {
                            eprintln!("   ✗ Port forwarding failed: {}", e);
                            continue;
                        }
                    }

                    // Try to connect
                    match DeviceConnection::connect().await {
                        Ok(conn) => {
                            eprintln!("   ✓ Connected to companion app");
                            // Test connection is alive
                            if conn.is_alive().await {
                                eprintln!("   ✓ Connection is alive");
                            } else {
                                eprintln!("   ✗ Connection is not responding");
                            }
                        }
                        Err(e) => {
                            eprintln!("   ✗ Connection failed: {}", e);
                            eprintln!("     Make sure companion app is running");
                            eprintln!("     Check logcat: adb logcat -s NeuralBridge:V");
                        }
                    }
                }
            }
            Err(e) => {
                eprintln!("   ✗ Failed to check permissions: {}", e);
            }
        }
        eprintln!();
    }

    eprintln!("Setup check complete");
    Ok(())
}

fn print_usage() {
    eprintln!("Usage: neuralbridge-mcp [OPTIONS]");
    eprintln!();
    eprintln!("Options:");
    eprintln!("  --device <id>           Pre-select a specific device at startup");
    eprintln!("  --auto-discover         Auto-detect and select first ready device");
    eprintln!("  --enable-permissions    Auto-enable AccessibilityService and NotificationListener");
    eprintln!("  --check                 Run setup verification and show device status");
    eprintln!("  --help, -h              Show this help message");
    eprintln!();
    eprintln!("Note: All options are optional. Without --device or --auto-discover,");
    eprintln!("the server starts without a device. AI agents can then use");
    eprintln!("android_list_devices and android_select_device tools at runtime.");
    eprintln!();
    eprintln!("Examples:");
    eprintln!("  neuralbridge-mcp --auto-discover --enable-permissions");
    eprintln!("  neuralbridge-mcp --device emulator-5554");
    eprintln!("  neuralbridge-mcp --check");
    eprintln!("  neuralbridge-mcp    # Start without device, select at runtime");
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_key_code_mapping() {
        // Test that key string mapping covers all required keys
        let test_cases = vec![
            ("back", pb::KeyCode::Back as i32),
            ("home", pb::KeyCode::Home as i32),
            ("menu", pb::KeyCode::Menu as i32),
            ("enter", pb::KeyCode::Enter as i32),
            ("return", pb::KeyCode::Enter as i32),
            ("delete", pb::KeyCode::Delete as i32),
            ("del", pb::KeyCode::Delete as i32),
            ("backspace", pb::KeyCode::Delete as i32),
            ("tab", pb::KeyCode::Tab as i32),
            ("space", pb::KeyCode::Space as i32),
            ("volume_up", pb::KeyCode::VolumeUp as i32),
            ("volumeup", pb::KeyCode::VolumeUp as i32),
            ("volume_down", pb::KeyCode::VolumeDown as i32),
            ("volumedown", pb::KeyCode::VolumeDown as i32),
            ("power", pb::KeyCode::Power as i32),
        ];

        for (key_str, expected_code) in test_cases {
            let result = map_key_string_to_code(key_str);
            assert!(result.is_ok(), "Failed to map key: {}", key_str);
            assert_eq!(result.unwrap(), expected_code, "Incorrect mapping for key: {}", key_str);
        }
    }

    #[test]
    fn test_key_code_mapping_case_insensitive() {
        // Test case insensitivity
        assert_eq!(
            map_key_string_to_code("BACK").unwrap(),
            map_key_string_to_code("back").unwrap()
        );
        assert_eq!(
            map_key_string_to_code("Home").unwrap(),
            map_key_string_to_code("home").unwrap()
        );
    }

    #[test]
    fn test_key_code_mapping_invalid() {
        // Test invalid key strings
        assert!(map_key_string_to_code("invalid_key").is_err());
        assert!(map_key_string_to_code("").is_err());
        assert!(map_key_string_to_code("xyz").is_err());
    }

    #[test]
    fn test_global_action_mapping() {
        // Test that action string mapping covers all required actions
        let test_cases = vec![
            ("back", pb::GlobalAction::GlobalBack as i32),
            ("home", pb::GlobalAction::GlobalHome as i32),
            ("recents", pb::GlobalAction::GlobalRecents as i32),
            ("recent", pb::GlobalAction::GlobalRecents as i32),
            ("recent_apps", pb::GlobalAction::GlobalRecents as i32),
            ("notifications", pb::GlobalAction::GlobalNotifications as i32),
            ("notification", pb::GlobalAction::GlobalNotifications as i32),
            ("quick_settings", pb::GlobalAction::GlobalQuickSettings as i32),
            ("quicksettings", pb::GlobalAction::GlobalQuickSettings as i32),
            ("lock_screen", pb::GlobalAction::GlobalLockScreen as i32),
            ("lockscreen", pb::GlobalAction::GlobalLockScreen as i32),
            ("lock", pb::GlobalAction::GlobalLockScreen as i32),
            ("screenshot", pb::GlobalAction::GlobalTakeScreenshot as i32),
            ("take_screenshot", pb::GlobalAction::GlobalTakeScreenshot as i32),
        ];

        for (action_str, expected_code) in test_cases {
            let result = map_action_string_to_code(action_str);
            assert!(result.is_ok(), "Failed to map action: {}", action_str);
            assert_eq!(result.unwrap(), expected_code, "Incorrect mapping for action: {}", action_str);
        }
    }

    #[test]
    fn test_global_action_mapping_case_insensitive() {
        // Test case insensitivity
        assert_eq!(
            map_action_string_to_code("BACK").unwrap(),
            map_action_string_to_code("back").unwrap()
        );
        assert_eq!(
            map_action_string_to_code("Notifications").unwrap(),
            map_action_string_to_code("notifications").unwrap()
        );
    }

    #[test]
    fn test_global_action_mapping_invalid() {
        // Test invalid action strings
        assert!(map_action_string_to_code("invalid_action").is_err());
        assert!(map_action_string_to_code("").is_err());
        assert!(map_action_string_to_code("xyz").is_err());
    }

    // Helper function for testing key mapping
    fn map_key_string_to_code(key: &str) -> Result<i32> {
        match key.to_lowercase().as_str() {
            "back" => Ok(pb::KeyCode::Back as i32),
            "home" => Ok(pb::KeyCode::Home as i32),
            "menu" => Ok(pb::KeyCode::Menu as i32),
            "enter" | "return" => Ok(pb::KeyCode::Enter as i32),
            "delete" | "del" | "backspace" => Ok(pb::KeyCode::Delete as i32),
            "tab" => Ok(pb::KeyCode::Tab as i32),
            "space" => Ok(pb::KeyCode::Space as i32),
            "volume_up" | "volumeup" => Ok(pb::KeyCode::VolumeUp as i32),
            "volume_down" | "volumedown" => Ok(pb::KeyCode::VolumeDown as i32),
            "power" => Ok(pb::KeyCode::Power as i32),
            _ => Err(anyhow::anyhow!("Unknown key: {}", key)),
        }
    }

    // Helper function for testing action mapping
    fn map_action_string_to_code(action: &str) -> Result<i32> {
        match action.to_lowercase().as_str() {
            "back" => Ok(pb::GlobalAction::GlobalBack as i32),
            "home" => Ok(pb::GlobalAction::GlobalHome as i32),
            "recents" | "recent" | "recent_apps" => Ok(pb::GlobalAction::GlobalRecents as i32),
            "notifications" | "notification" => Ok(pb::GlobalAction::GlobalNotifications as i32),
            "quick_settings" | "quicksettings" => Ok(pb::GlobalAction::GlobalQuickSettings as i32),
            "lock_screen" | "lockscreen" | "lock" => Ok(pb::GlobalAction::GlobalLockScreen as i32),
            "screenshot" | "take_screenshot" => Ok(pb::GlobalAction::GlobalTakeScreenshot as i32),
            _ => Err(anyhow::anyhow!("Unknown action: {}", action)),
        }
    }

    #[test]
    fn test_validate_selector_with_text() {
        let text = Some(&"Login".to_string());
        let result = validate_selector(text, None, None, None, None, None, None, None, None, None);
        assert!(result.is_ok(), "Selector with text should be valid");
    }

    #[test]
    fn test_validate_selector_with_resource_id() {
        let resource_id = Some(&"com.app:id/button".to_string());
        let result = validate_selector(None, resource_id, None, None, None, None, None, None, None, None);
        assert!(result.is_ok(), "Selector with resource_id should be valid");
    }

    #[test]
    fn test_validate_selector_with_content_desc() {
        let content_desc = Some(&"Submit button".to_string());
        let result = validate_selector(None, None, content_desc, None, None, None, None, None, None, None);
        assert!(result.is_ok(), "Selector with content_desc should be valid");
    }

    #[test]
    fn test_validate_selector_with_class_name() {
        let class_name = Some(&"android.widget.Button".to_string());
        let result = validate_selector(None, None, None, class_name, None, None, None, None, None, None);
        assert!(result.is_ok(), "Selector with class_name should be valid");
    }

    #[test]
    fn test_validate_selector_all_empty() {
        let result = validate_selector(None, None, None, None, None, None, None, None, None, None);
        assert!(result.is_err(), "Selector with all empty fields should fail");
    }

    #[test]
    fn test_validate_selector_empty_strings() {
        let empty_text = Some(&String::new());
        let empty_resource_id = Some(&String::new());
        let result = validate_selector(empty_text, empty_resource_id, None, None, None, None, None, None, None, None);
        assert!(result.is_err(), "Selector with only empty strings should fail");
    }

    #[test]
    fn test_validate_selector_mixed() {
        let text = Some(&"Login".to_string());
        let empty_resource_id = Some(&String::new());
        let result = validate_selector(text, empty_resource_id, None, None, None, None, None, None, None, None);
        assert!(result.is_ok(), "Selector with at least one non-empty field should be valid");
    }

    #[test]
    fn test_validate_selector_with_clickable_only() {
        let result = validate_selector(None, None, None, None, Some(true), None, None, None, None, None);
        assert!(result.is_ok(), "Selector with only clickable filter should be valid");
    }

    #[test]
    fn test_validate_selector_with_scrollable_only() {
        let result = validate_selector(None, None, None, None, None, Some(false), None, None, None, None);
        assert!(result.is_ok(), "Selector with only scrollable filter should be valid");
    }

    #[test]
    fn test_validate_selector_with_multiple_boolean_filters() {
        let result = validate_selector(None, None, None, None, Some(true), Some(false), None, None, None, None);
        assert!(result.is_ok(), "Selector with multiple boolean filters should be valid");
    }

    #[test]
    fn test_validate_selector_with_text_and_boolean() {
        let text = Some(&"Login".to_string());
        let result = validate_selector(text, None, None, None, Some(true), None, None, None, None, None);
        assert!(result.is_ok(), "Selector with text and boolean filter should be valid");
    }

    #[tokio::test]
    async fn test_event_buffer_capacity() {
        use std::collections::VecDeque;

        // Simulate circular buffer behavior
        let mut buffer: VecDeque<pb::Event> = VecDeque::with_capacity(100);

        // Add events beyond capacity
        for i in 0..150 {
            if buffer.len() >= 100 {
                buffer.pop_front();
            }
            buffer.push_back(pb::Event {
                event_id: format!("event_{}", i),
                timestamp: i as i64,
                event_type: pb::EventType::UiChange as i32,
                data: None,
            });
        }

        // Should have exactly 100 events (oldest removed)
        assert_eq!(buffer.len(), 100);

        // First event should be event_50 (0-49 removed)
        assert_eq!(buffer.front().unwrap().event_id, "event_50");

        // Last event should be event_149
        assert_eq!(buffer.back().unwrap().event_id, "event_149");
    }

    #[test]
    fn test_event_type_mapping() {
        // Test that event type string mapping is correct
        let test_cases = vec![
            ("ui_change", pb::EventType::UiChange as i32),
            ("notification_posted", pb::EventType::NotificationPosted as i32),
            ("toast_shown", pb::EventType::ToastShown as i32),
            ("app_crash", pb::EventType::AppCrash as i32),
        ];

        for (type_str, expected_code) in test_cases {
            let mapped = match type_str {
                "ui_change" => Some(pb::EventType::UiChange as i32),
                "notification_posted" => Some(pb::EventType::NotificationPosted as i32),
                "toast_shown" => Some(pb::EventType::ToastShown as i32),
                "app_crash" => Some(pb::EventType::AppCrash as i32),
                _ => None,
            };

            assert_eq!(mapped, Some(expected_code), "Failed to map event type: {}", type_str);
        }
    }

    #[test]
    fn test_clipboard_params_validation() {
        // Test that SetClipboardParams accepts valid text
        let params = SetClipboardParams {
            text: "Hello, World!".to_string(),
        };
        assert_eq!(params.text, "Hello, World!");

        // Test empty text (should be allowed - clearing clipboard)
        let empty_params = SetClipboardParams {
            text: String::new(),
        };
        assert_eq!(empty_params.text, "");

        // Test multiline text
        let multiline_params = SetClipboardParams {
            text: "Line 1\nLine 2\nLine 3".to_string(),
        };
        assert!(multiline_params.text.contains('\n'));
    }

    #[test]
    fn test_clipboard_special_characters() {
        // Test special characters that might need escaping
        let special_chars = vec![
            "Hello \"World\"",
            "Path: C:\\Users\\test",
            "Email: test@example.com",
            "Unicode: 你好世界 🌍",
            "Newline:\nTab:\tSpace: ",
        ];

        for text in special_chars {
            let params = SetClipboardParams {
                text: text.to_string(),
            };
            assert_eq!(params.text, text);
        }
    }

    // ============================================================================
    // Error Classification Tests
    // ============================================================================

    /// Test connection refused error classification
    #[test]
    fn test_error_classification_connection_refused() {
        let error = anyhow::anyhow!("Connection refused by peer");
        let mcp_error = to_mcp_error(error);

        assert_eq!(mcp_error.code, ErrorCode::INTERNAL_ERROR);
        assert!(mcp_error.message.contains("Failed to connect to companion app"));
        assert!(mcp_error.message.contains("Troubleshooting checklist"));
        assert!(mcp_error.message.contains("adb forward tcp:38472"));
        assert!(mcp_error.message.contains("AccessibilityService"));
    }

    /// Test connection timeout error classification
    #[test]
    fn test_error_classification_connection_timeout() {
        let error = anyhow::anyhow!("Connection timeout after 5 seconds");
        let mcp_error = to_mcp_error(error);

        assert_eq!(mcp_error.code, ErrorCode::INTERNAL_ERROR);
        assert!(mcp_error.message.contains("Failed to connect to companion app"));
        assert!(mcp_error.message.contains("companion app is installed"));
        assert!(mcp_error.message.contains("adb logcat"));
    }

    /// Test ADB error classification
    #[test]
    fn test_error_classification_adb_error() {
        let error = anyhow::anyhow!("ADB command failed: device not found");
        let mcp_error = to_mcp_error(error);

        assert_eq!(mcp_error.code, ErrorCode::INTERNAL_ERROR);
        assert!(mcp_error.message.contains("ADB operation failed"));
        assert!(mcp_error.message.contains("adb devices"));
        assert!(mcp_error.message.contains("device is connected and authorized"));
        assert!(mcp_error.message.contains("adb kill-server"));
    }

    /// Test "device not found" error classification
    #[test]
    fn test_error_classification_device_not_found() {
        let error = anyhow::anyhow!("Device not found: emulator-5554");
        let mcp_error = to_mcp_error(error);

        assert_eq!(mcp_error.code, ErrorCode::INTERNAL_ERROR);
        assert!(mcp_error.message.contains("ADB operation failed"));
    }

    /// Test "no device selected" error classification
    #[test]
    fn test_error_classification_no_device_selected() {
        let error = anyhow::anyhow!("No device selected");
        let mcp_error = to_mcp_error(error);

        assert_eq!(mcp_error.code, ErrorCode::INVALID_PARAMS);
        assert!(mcp_error.message.contains("No device selected"));
        assert!(mcp_error.message.contains("--device <id>"));
        assert!(mcp_error.message.contains("--auto-discover"));
    }

    /// Test port forwarding error classification
    #[test]
    fn test_error_classification_port_forwarding() {
        let error = anyhow::anyhow!("Port forwarding failed for device emulator-5554");
        let mcp_error = to_mcp_error(error);

        assert_eq!(mcp_error.code, ErrorCode::INTERNAL_ERROR);
        assert!(mcp_error.message.contains("Port forwarding setup failed"));
        assert!(mcp_error.message.contains("adb forward tcp:38472"));
        assert!(mcp_error.message.contains("port conflicts"));
        assert!(mcp_error.message.contains("netstat"));
    }

    /// Test generic error classification (fallback)
    #[test]
    fn test_error_classification_generic() {
        let error = anyhow::anyhow!("Some random error occurred");
        let mcp_error = to_mcp_error(error);

        assert_eq!(mcp_error.code, ErrorCode::INTERNAL_ERROR);
        assert_eq!(mcp_error.message, "Some random error occurred");
        // Generic errors should just return the error message without modifications
        assert!(!mcp_error.message.contains("Troubleshooting"));
    }

    /// Test that error classification is case-insensitive
    #[test]
    fn test_error_classification_case_insensitive() {
        // Test with uppercase
        let error1 = anyhow::anyhow!("CONNECTION REFUSED");
        let mcp_error1 = to_mcp_error(error1);
        assert!(mcp_error1.message.contains("Failed to connect to companion app"));

        // Test with mixed case
        let error2 = anyhow::anyhow!("Connection Timeout");
        let mcp_error2 = to_mcp_error(error2);
        assert!(mcp_error2.message.contains("Failed to connect to companion app"));

        // Test with lowercase
        let error3 = anyhow::anyhow!("adb device not found");
        let mcp_error3 = to_mcp_error(error3);
        assert!(mcp_error3.message.contains("ADB operation failed"));
    }

    /// Test connection error variations
    #[test]
    fn test_error_classification_connection_variations() {
        let variations = vec![
            "connection refused",
            "Connection refused by peer",
            "connection timeout occurred",
            "timeout while connecting - connection timeout",
        ];

        for error_msg in variations {
            let error = anyhow::anyhow!("{}", error_msg);
            let mcp_error = to_mcp_error(error);
            assert!(
                mcp_error.message.contains("Failed to connect to companion app"),
                "Error '{}' should be classified as connection error",
                error_msg
            );
        }
    }

    /// Test ADB error variations
    #[test]
    fn test_error_classification_adb_variations() {
        let variations = vec![
            "adb: command not found",
            "ADB failed to start",
            "device not found in adb",
        ];

        for error_msg in variations {
            let error = anyhow::anyhow!("{}", error_msg);
            let mcp_error = to_mcp_error(error);
            assert!(
                mcp_error.message.contains("ADB operation failed"),
                "Error '{}' should be classified as ADB error",
                error_msg
            );
        }
    }

    /// Test that error codes are correctly assigned
    #[test]
    fn test_error_codes() {
        // Connection errors → INTERNAL_ERROR
        let conn_error = anyhow::anyhow!("connection refused");
        assert_eq!(to_mcp_error(conn_error).code, ErrorCode::INTERNAL_ERROR);

        // No device selected → INVALID_PARAMS
        let device_error = anyhow::anyhow!("no device selected");
        assert_eq!(to_mcp_error(device_error).code, ErrorCode::INVALID_PARAMS);

        // Generic errors → INTERNAL_ERROR
        let generic_error = anyhow::anyhow!("something went wrong");
        assert_eq!(to_mcp_error(generic_error).code, ErrorCode::INTERNAL_ERROR);
    }

    /// Test that troubleshooting checklists are complete
    #[test]
    fn test_troubleshooting_checklists() {
        // Connection error checklist
        let conn_error = anyhow::anyhow!("connection refused");
        let conn_msg = to_mcp_error(conn_error).message;
        assert!(conn_msg.contains("1."));
        assert!(conn_msg.contains("2."));
        assert!(conn_msg.contains("3."));
        assert!(conn_msg.contains("4."));

        // ADB error checklist
        let adb_error = anyhow::anyhow!("adb failed");
        let adb_msg = to_mcp_error(adb_error).message;
        assert!(adb_msg.contains("1."));
        assert!(adb_msg.contains("2."));
        assert!(adb_msg.contains("3."));
        assert!(adb_msg.contains("4."));

        // Port forwarding checklist
        let port_error = anyhow::anyhow!("port forwarding failed");
        let port_msg = to_mcp_error(port_error).message;
        assert!(port_msg.contains("1."));
        assert!(port_msg.contains("2."));
        assert!(port_msg.contains("3."));
        assert!(port_msg.contains("4."));
    }
}
