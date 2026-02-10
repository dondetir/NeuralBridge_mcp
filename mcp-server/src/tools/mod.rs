/*!
 * Tools Module
 *
 * Exports all MCP tools for Android automation.
 * Tools are organized into categories:
 * - observe: UI inspection, screenshots, element finding
 * - act: Gestures, input, global actions
 * - manage: App lifecycle, device settings
 * - wait: Synchronization primitives
 */

pub mod observe;
pub mod act;
pub mod manage;
pub mod wait;
