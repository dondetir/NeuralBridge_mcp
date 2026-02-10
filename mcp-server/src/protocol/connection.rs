/*!
 * Device Connection
 *
 * Manages TCP connection to the Android companion app.
 * Handles connection establishment, message sending/receiving, and reconnection logic.
 */

use anyhow::{Result, Context, bail};
use prost::Message;
use tokio::net::TcpStream;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::Mutex;
use std::sync::Arc;
use std::time::Duration;
use tracing::{info, debug, warn};

use super::codec::{MessageType, MessageFramer, encode_message};
use super::pb::{Request, Response, Event};

/// TCP port where companion app listens
pub const COMPANION_PORT: u16 = 38472;

/// Connection timeout
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// Read timeout
const READ_TIMEOUT: Duration = Duration::from_secs(10);

/// Device connection handle
#[derive(Clone)]
pub struct DeviceConnection {
    inner: Arc<Mutex<ConnectionInner>>,
}

struct ConnectionInner {
    stream: TcpStream,
    framer: MessageFramer,
    pending_events: Vec<Event>,  // Buffer for events received while waiting for response
}

impl DeviceConnection {
    /// Establish connection to companion app
    ///
    /// Requires ADB port forwarding to be set up first:
    /// `adb forward tcp:38472 tcp:38472`
    pub async fn connect() -> Result<Self> {
        info!("Connecting to companion app on localhost:{}", COMPANION_PORT);

        let stream = tokio::time::timeout(
            CONNECT_TIMEOUT,
            TcpStream::connect(("localhost", COMPANION_PORT))
        )
        .await
        .context("Connection timeout")?
        .context("Failed to connect to companion app")?;

        // Set TCP_NODELAY for low latency
        stream.set_nodelay(true)
            .context("Failed to set TCP_NODELAY")?;

        info!("Connected to companion app");

        Ok(Self {
            inner: Arc::new(Mutex::new(ConnectionInner {
                stream,
                framer: MessageFramer::new(),
                pending_events: Vec::new(),
            })),
        })
    }

    /// Take the event receiver (can only be called once)
    /// NOTE: In this simplified implementation, events are discarded
    /// A full implementation would use a background task with channels
    pub async fn take_event_receiver(&self) -> Option<tokio::sync::mpsc::UnboundedReceiver<Event>> {
        // Return None - events are handled inline for now
        None
    }

    /// Send a request and wait for response
    pub async fn send_request(&self, request: Request) -> Result<Response> {
        let request_id = request.request_id.clone();
        debug!("Sending request: id={}", request_id);

        // Encode request
        let message_bytes = encode_message(MessageType::Request, &request)?;

        // Send to companion app
        let mut inner = self.inner.lock().await;
        inner.stream.write_all(&message_bytes).await
            .context("Failed to send request")?;
        inner.stream.flush().await
            .context("Failed to flush request")?;

        debug!("Request sent, waiting for response...");

        // Read response with timeout
        let response = tokio::time::timeout(
            READ_TIMEOUT,
            Self::read_response_inner(&mut inner)
        )
        .await
        .context("Response timeout")??;

        // Validate request ID matches
        if response.request_id != request_id {
            warn!("Response request_id mismatch: expected {}, got {}",
                request_id, response.request_id);
        }

        debug!("Received response: success={}", response.success);
        Ok(response)
    }

    /// Internal method to read response (requires lock held)
    async fn read_response_inner(inner: &mut ConnectionInner) -> Result<Response> {
        loop {
            // Try to extract message from buffer
            match inner.framer.try_extract_message() {
                Ok(Some((header, payload))) => {
                    // Handle different message types
                    match header.message_type {
                        MessageType::Response => {
                            // Decode response
                            let response = Response::decode(&payload[..])
                                .context("Failed to decode response")?;
                            return Ok(response);
                        }
                        MessageType::Event => {
                            // Decode and buffer event (to be discarded or processed later)
                            match Event::decode(&payload[..]) {
                                Ok(event) => {
                                    debug!("Received event (buffered): type={:?}, id={}", event.event_type, event.event_id);
                                    inner.pending_events.push(event);
                                    // Keep pending_events bounded
                                    if inner.pending_events.len() > 100 {
                                        inner.pending_events.remove(0);
                                    }
                                }
                                Err(e) => {
                                    warn!("Failed to decode event: {}", e);
                                }
                            }
                            // Continue reading for response
                            continue;
                        }
                        MessageType::Request => {
                            warn!("Received unexpected Request message from companion app");
                            continue;
                        }
                    }
                }
                Ok(None) => {
                    // Need more data - fall through to read
                }
                Err(e) => {
                    // Framing error - this is fatal
                    return Err(e);
                }
            }

            // Need more data
            let mut buf = vec![0u8; 4096];
            let n = inner.stream.read(&mut buf).await
                .context("Failed to read from connection")?;

            if n == 0 {
                bail!("Connection closed by companion app");
            }

            // Log first 32 bytes as hex for debugging
            if n > 0 {
                let hex_preview: String = buf.iter().take(std::cmp::min(32, n))
                    .map(|b| format!("{:02X}", b))
                    .collect::<Vec<String>>()
                    .join(" ");
                debug!("Read {} bytes from connection. First {} bytes: {}",
                    n, std::cmp::min(32, n), hex_preview);
            }

            inner.framer.add_data(&buf[..n]);
        }
    }

    /// Close the connection
    pub async fn close(&self) -> Result<()> {
        let mut inner = self.inner.lock().await;
        inner.stream.shutdown().await
            .context("Failed to close connection")?;
        info!("Connection closed");
        Ok(())
    }

    /// Check if connection is still alive
    pub async fn is_alive(&self) -> bool {
        let inner = self.inner.lock().await;

        // Use peek with a 1-byte buffer to check connection status
        let mut peek_buf = [0u8; 1];
        match inner.stream.try_read(&mut peek_buf) {
            Ok(_) => true,
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => true,
            Err(_) => {
                debug!("Connection health check failed");
                false
            }
        }
    }
}

/// Connection pool for managing multiple device connections
#[allow(dead_code)]
pub struct ConnectionPool {
    // TODO Week 3: Implement connection pooling
}

impl ConnectionPool {
    #[allow(dead_code)]
    pub fn new() -> Self {
        Self {}
    }

    #[allow(dead_code)]
    pub async fn get_connection(&self, _device_id: &str) -> Result<DeviceConnection> {
        bail!("Connection pooling not yet implemented")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_connection_creation() {
        if std::env::var("CI").is_ok() {
            return;
        }

        let result = DeviceConnection::connect().await;
        if result.is_err() {
            eprintln!("Connection test skipped (companion app not running)");
        }
    }
}
