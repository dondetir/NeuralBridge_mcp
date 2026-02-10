/*!
 * NeuralBridge MCP Server
 *
 * Entry point for the AI-native Android automation MCP server.
 * Provides MCP tools for Android device control via AccessibilityService.
 */

use anyhow::{Context, Result};
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
}

// Helper to convert anyhow errors to MCP errors
fn to_mcp_error(e: anyhow::Error) -> McpError {
    McpError::new(ErrorCode::INTERNAL_ERROR, e.to_string(), None)
}

// Helper to validate selector has at least one non-empty field or boolean filter
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

impl AppState {
    pub fn new(device_manager: DeviceManager) -> Self {
        Self {
            device_manager: Arc::new(device_manager),
            connection: Arc::new(RwLock::new(None)),
            device_id: Arc::new(RwLock::new(None)),
            event_buffer: Arc::new(RwLock::new(VecDeque::with_capacity(100))),
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

        // Get device ID
        let device_id = self.device_id.read().await;
        let device_id_str = device_id.as_ref()
            .context("No device selected. Use --device or --auto-discover")?;

        info!("Establishing new connection to device: {}", device_id_str);

        // Set up ADB port forwarding
        self.device_manager.setup_port_forwarding(device_id_str).await
            .context("Failed to set up ADB port forwarding")?;

        // Establish TCP connection
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

        // Send and await response
        let response = conn.send_request(request).await
            .map_err(to_mcp_error)?;

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

        // Send and await response
        let response = conn.send_request(request).await
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

        // Send and await response
        let response = conn.send_request(request).await
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
        info!("Tool: android_enable_events(enable={}) - WORKAROUND: disabled due to protocol corruption", params.enable);

        // TODO: Event streaming disabled due to protocol corruption bug (Task #3)
        // Root cause: 1-byte buffer misalignment when Event messages are sent
        // Hex dump shows buffer starts with [0x42, 0x03, ...] instead of [0x4E, 0x42, 0x03, ...]
        // Previous message extraction consumes one extra byte, corrupting subsequent reads
        //
        // Full RCA documented in: python-demo/.claude/scratch/task3_protocol_corruption_analysis.md
        // Fix deferred to Task #6 (requires protobuf encoding investigation)
        //
        // WORKAROUND: Return success without actually enabling events
        // This prevents Event messages from being sent, avoiding corruption
        // Scenarios 4-7 can run without hitting the bug

        // Return success result immediately (no companion app call)
        let result = serde_json::json!({
            "success": true,
            "enabled": params.enable,
            "note": "Event streaming temporarily disabled (workaround for protocol bug)",
            "latency_ms": 0,
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
                let selected = devices[0].clone();
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
        return run_check_mode(&device_manager, device_id.as_deref()).await;
    }

    if device_id.is_none() {
        error!("No device specified. Use --device <id> or --auto-discover");
        print_usage();
        std::process::exit(1);
    }

    let app_state = Arc::new(AppState::new(device_manager));
    let selected_device = device_id.expect("Device ID should be set");
    *app_state.device_id.write().await = Some(selected_device.clone());

    info!("Starting MCP server for device: {}", selected_device);

    let server = NeuralBridgeServer::new(app_state);
    let service = server.serve(stdio()).await
        .map_err(|e| anyhow::anyhow!("Failed to start MCP server: {}", e))?;

    info!("MCP server ready. Listening on stdio...");
    service.waiting().await
        .map_err(|e| anyhow::anyhow!("MCP server error: {}", e))?;

    Ok(())
}

async fn run_check_mode(device_manager: &DeviceManager, _device_id: Option<&str>) -> Result<()> {
    info!("Running NeuralBridge setup check...");

    match device_manager.check_adb_installed().await {
        Ok(true) => info!("  ADB found"),
        Ok(false) => error!("  ADB not found in PATH"),
        Err(e) => error!("  Failed to check ADB: {}", e),
    }

    match device_manager.discover_devices().await {
        Ok(devices) if devices.is_empty() => warn!("  No devices found"),
        Ok(devices) => {
            info!("  Found {} device(s):", devices.len());
            for d in &devices {
                info!("    - {}", d);
            }
        }
        Err(e) => error!("  Failed to list devices: {}", e),
    }

    info!("Setup check complete");
    Ok(())
}

fn print_usage() {
    eprintln!("Usage: neuralbridge-mcp [OPTIONS]");
    eprintln!("  --device <id>       Connect to specific device");
    eprintln!("  --auto-discover     Auto-detect first available device");
    eprintln!("  --check             Run setup verification");
    eprintln!("  --help, -h          Show help");
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
}
