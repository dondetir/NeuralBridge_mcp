/*!
 * NeuralBridge MCP Server Library
 *
 * Exposes protocol types and modules for integration testing.
 */

pub mod protocol;

// Re-export commonly used types for convenience
pub use protocol::pb;
pub use protocol::connection;
pub use protocol::codec;
