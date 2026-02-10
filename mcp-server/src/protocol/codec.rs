/*!
 * Protocol Codec
 *
 * Encodes and decodes protobuf messages with custom wire format:
 *
 * ```text
 * ┌─────────────┬────────────┬──────────────┬────────────────┐
 * │ Magic (2B)  │ Type (1B)  │ Length (4B)  │ Payload (N B)  │
 * │   0x4E42    │ 0x01-0x03  │  big-endian  │   Protobuf     │
 * └─────────────┴────────────┴──────────────┴────────────────┘
 * ```
 *
 * Message types:
 * - 0x01: Request (MCP server → Companion app)
 * - 0x02: Response (Companion app → MCP server)
 * - 0x03: Event (Companion app → MCP server, unsolicited)
 */

use anyhow::{Result, Context, bail};
use bytes::BytesMut;
use prost::Message;
use tracing::{debug, trace};

/// Magic bytes: "NB" (NeuralBridge)
const MAGIC: u16 = 0x4E42;

/// Message type constants
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MessageType {
    Request = 0x01,
    Response = 0x02,
    Event = 0x03,
}

impl MessageType {
    /// Parse message type from byte
    pub fn from_u8(value: u8) -> Result<Self> {
        match value {
            0x01 => Ok(MessageType::Request),
            0x02 => Ok(MessageType::Response),
            0x03 => Ok(MessageType::Event),
            _ => bail!("Invalid message type: 0x{:02X}", value),
        }
    }
}

/// Wire format header (7 bytes)
#[derive(Debug, Clone)]
pub struct MessageHeader {
    pub message_type: MessageType,
    pub payload_length: u32,
}

impl MessageHeader {
    /// Header size in bytes
    pub const SIZE: usize = 7; // 2 (magic) + 1 (type) + 4 (length)

    /// Maximum payload size (16MB)
    pub const MAX_PAYLOAD_SIZE: u32 = 16 * 1024 * 1024;

    /// Create new header
    pub fn new(message_type: MessageType, payload_length: u32) -> Self {
        Self {
            message_type,
            payload_length,
        }
    }

    /// Encode header to bytes
    pub fn encode(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        buf[0..2].copy_from_slice(&MAGIC.to_be_bytes());
        buf[2] = self.message_type as u8;
        buf[3..7].copy_from_slice(&self.payload_length.to_be_bytes());
        buf
    }

    /// Decode header from bytes
    pub fn decode(buf: &[u8]) -> Result<Self> {
        if buf.len() < Self::SIZE {
            bail!("Buffer too short for header: {} bytes", buf.len());
        }

        // Verify magic
        let magic = u16::from_be_bytes([buf[0], buf[1]]);
        if magic != MAGIC {
            // Log hex dump of buffer for debugging
            let hex_dump: String = buf.iter().take(std::cmp::min(32, buf.len()))
                .map(|b| format!("{:02X}", b))
                .collect::<Vec<String>>()
                .join(" ");
            bail!("Invalid magic bytes: 0x{:04X}, expected 0x{:04X}. Buffer hex (first 32 bytes): {}",
                magic, MAGIC, hex_dump);
        }

        // Parse message type
        let message_type = MessageType::from_u8(buf[2])?;

        // Parse payload length
        let payload_length = u32::from_be_bytes([buf[3], buf[4], buf[5], buf[6]]);

        // Validate payload length
        if payload_length > Self::MAX_PAYLOAD_SIZE {
            bail!("Payload length {} exceeds maximum {}", payload_length, Self::MAX_PAYLOAD_SIZE);
        }

        Ok(Self {
            message_type,
            payload_length,
        })
    }
}

/// Encode a protobuf message to wire format
pub fn encode_message<M: Message>(message_type: MessageType, message: &M) -> Result<Vec<u8>> {
    // Encode protobuf message
    let mut payload = Vec::new();
    message.encode(&mut payload)
        .context("Failed to encode protobuf message")?;

    let payload_len = payload.len() as u32;
    trace!("Encoding message: type={:?}, payload_len={}", message_type, payload_len);

    // Create header
    let header = MessageHeader::new(message_type, payload_len);
    let header_bytes = header.encode();

    // Combine header + payload
    let mut result = Vec::with_capacity(MessageHeader::SIZE + payload.len());
    result.extend_from_slice(&header_bytes);
    result.extend_from_slice(&payload);

    debug!("Encoded message: total_size={} bytes", result.len());
    Ok(result)
}

/// Decode a protobuf message from wire format
#[allow(dead_code)]
pub fn decode_message<M: Message + Default>(buf: &[u8]) -> Result<(MessageHeader, M)> {
    // Decode header
    let header = MessageHeader::decode(buf)?;
    trace!("Decoded header: type={:?}, payload_len={}", header.message_type, header.payload_length);

    // Extract payload
    let payload_start = MessageHeader::SIZE;
    let payload_end = payload_start + header.payload_length as usize;

    if buf.len() < payload_end {
        bail!("Buffer too short for payload: {} bytes available, {} expected",
            buf.len() - payload_start, header.payload_length);
    }

    let payload = &buf[payload_start..payload_end];

    // Decode protobuf message
    let message = M::decode(payload)
        .context("Failed to decode protobuf message")?;

    debug!("Decoded message successfully");
    Ok((header, message))
}

/// Message framing for streaming protocols
pub struct MessageFramer {
    buffer: BytesMut,
}

impl MessageFramer {
    /// Create new framer
    pub fn new() -> Self {
        Self {
            buffer: BytesMut::with_capacity(4096),
        }
    }

    /// Add data to the framer's buffer
    pub fn add_data(&mut self, data: &[u8]) {
        self.buffer.extend_from_slice(data);
    }

    /// Try to extract a complete message from the buffer
    ///
    /// Returns Some((header, payload_bytes)) if a complete message is available,
    /// or None if more data is needed.
    pub fn try_extract_message(&mut self) -> Result<Option<(MessageHeader, Vec<u8>)>> {
        // Need at least header
        if self.buffer.len() < MessageHeader::SIZE {
            return Ok(None);
        }

        // Decode header
        let header = MessageHeader::decode(&self.buffer[..])?;

        // Check if full message is available
        let total_size = MessageHeader::SIZE + header.payload_length as usize;
        if self.buffer.len() < total_size {
            trace!("Incomplete message: have {} bytes, need {}", self.buffer.len(), total_size);
            return Ok(None);
        }

        // Extract complete message
        let message_bytes = self.buffer.split_to(total_size);
        let payload = message_bytes[MessageHeader::SIZE..].to_vec();

        debug!("Extracted complete message: type={:?}, payload_len={}",
            header.message_type, header.payload_length);

        Ok(Some((header, payload)))
    }

    /// Get the number of bytes currently buffered
    #[allow(dead_code)]
    pub fn buffered_len(&self) -> usize {
        self.buffer.len()
    }
}

impl Default for MessageFramer {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_header_encode_decode() {
        let header = MessageHeader::new(MessageType::Request, 1234);
        let encoded = header.encode();
        let decoded = MessageHeader::decode(&encoded).unwrap();

        assert_eq!(decoded.message_type, MessageType::Request);
        assert_eq!(decoded.payload_length, 1234);
    }

    #[test]
    fn test_invalid_magic() {
        let mut buf = [0u8; 7];
        buf[0..2].copy_from_slice(&[0xFF, 0xFF]); // Wrong magic

        let result = MessageHeader::decode(&buf);
        assert!(result.is_err());
    }

    #[test]
    fn test_message_framer() {
        let mut framer = MessageFramer::new();

        // Create a test message
        let header = MessageHeader::new(MessageType::Response, 4);
        let encoded_header = header.encode();
        let payload = b"test";

        // Add data in chunks
        framer.add_data(&encoded_header[..3]);
        assert!(framer.try_extract_message().unwrap().is_none());

        framer.add_data(&encoded_header[3..]);
        framer.add_data(payload);

        // Should now have complete message
        let (extracted_header, extracted_payload) = framer.try_extract_message().unwrap().unwrap();
        assert_eq!(extracted_header.message_type, MessageType::Response);
        assert_eq!(extracted_payload, payload);
    }
}
