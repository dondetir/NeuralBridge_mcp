/*!
 * Wait Tools - Implementation helpers
 *
 * Contains the business logic for wait/synchronization tools.
 * Tool entry points are defined as methods on NeuralBridgeServer in main.rs.
 *
 * This module will hold:
 * - Polling loop implementation for wait_for_element
 * - Idle detection logic
 * - Timeout management
 */

use anyhow::Result;

/// Default timeout for wait operations (5 seconds)
#[allow(dead_code)]
pub const DEFAULT_TIMEOUT_MS: i32 = 5000;

/// Default poll interval (500ms)
#[allow(dead_code)]
pub const DEFAULT_POLL_INTERVAL_MS: i32 = 500;

/// Idle threshold - UI considered stable after no events for this duration
#[allow(dead_code)]
pub const IDLE_THRESHOLD_MS: u64 = 300;

/// Validate wait parameters
#[allow(dead_code)]
pub fn validate_wait_params(timeout_ms: i32, poll_interval_ms: i32) -> Result<()> {
    if timeout_ms <= 0 {
        anyhow::bail!("Timeout must be positive, got: {}ms", timeout_ms);
    }
    if timeout_ms > 60000 {
        anyhow::bail!("Timeout too large: {}ms (max 60000ms)", timeout_ms);
    }
    if poll_interval_ms <= 0 {
        anyhow::bail!("Poll interval must be positive, got: {}ms", poll_interval_ms);
    }
    if poll_interval_ms > timeout_ms {
        anyhow::bail!("Poll interval ({}ms) cannot exceed timeout ({}ms)", poll_interval_ms, timeout_ms);
    }
    Ok(())
}
