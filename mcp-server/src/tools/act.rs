/*!
 * Action Tools - Implementation helpers
 *
 * Contains the business logic for action tools (tap, swipe, input, keys).
 * Tool entry points are defined as methods on NeuralBridgeServer in main.rs.
 *
 * This module will hold:
 * - Protobuf request building for gesture commands
 * - Coordinate validation and screen bounds checking
 * - Key name to Android KeyCode mapping
 * - Global action name mapping
 */

use anyhow::Result;

/// Map key name string to Android KeyCode value
/// TODO Week 3: Complete mapping
#[allow(dead_code)]
pub fn map_key_name(key: &str) -> Result<i32> {
    match key.to_lowercase().as_str() {
        "back" => Ok(4),        // KEYCODE_BACK
        "home" => Ok(3),        // KEYCODE_HOME
        "enter" | "return" => Ok(66),  // KEYCODE_ENTER
        "delete" | "backspace" => Ok(67), // KEYCODE_DEL
        "tab" => Ok(61),        // KEYCODE_TAB
        "space" => Ok(62),      // KEYCODE_SPACE
        "volume_up" => Ok(24),  // KEYCODE_VOLUME_UP
        "volume_down" => Ok(25), // KEYCODE_VOLUME_DOWN
        "escape" | "esc" => Ok(111), // KEYCODE_ESCAPE
        _ => anyhow::bail!("Unknown key: {}", key),
    }
}

/// Map global action name to AccessibilityService constant
/// TODO Week 3: Use in global action implementation
#[allow(dead_code)]
pub fn map_global_action(action: &str) -> Result<i32> {
    match action.to_lowercase().as_str() {
        "back" => Ok(1),            // GLOBAL_ACTION_BACK
        "home" => Ok(2),            // GLOBAL_ACTION_HOME
        "recents" => Ok(3),         // GLOBAL_ACTION_RECENTS
        "notifications" => Ok(4),   // GLOBAL_ACTION_NOTIFICATIONS
        "quick_settings" => Ok(5),  // GLOBAL_ACTION_QUICK_SETTINGS
        "power_dialog" => Ok(6),    // GLOBAL_ACTION_POWER_DIALOG
        "lock_screen" => Ok(8),     // GLOBAL_ACTION_LOCK_SCREEN (API 28+)
        "screenshot" => Ok(9),      // GLOBAL_ACTION_TAKE_SCREENSHOT (API 28+)
        _ => anyhow::bail!("Unknown global action: {}", action),
    }
}

/// Validate coordinates are within screen bounds
#[allow(dead_code)]
pub fn validate_coordinates(x: i32, y: i32, screen_width: i32, screen_height: i32) -> Result<()> {
    if x < 0 || x >= screen_width || y < 0 || y >= screen_height {
        anyhow::bail!(
            "Coordinates ({}, {}) out of bounds (screen: {}x{})",
            x, y, screen_width, screen_height
        );
    }
    Ok(())
}
