/*!
 * Event Streaming Debug Test
 * Simple test to diagnose event streaming issues
 */

use anyhow::Result;
use std::time::Duration;
use tokio::net::TcpStream;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use uuid::Uuid;

use neuralbridge_mcp::protocol::pb::{
    Request, Response, Event, request::Command,
    EnableEventsRequest, TapRequest, tap_request, Point, EventType,
};
use neuralbridge_mcp::protocol::codec::{MessageType, encode_message, MessageFramer};
use prost::Message;

#[tokio::test]
async fn debug_event_streaming() -> Result<()> {
    println!("\n=== Event Streaming Debug Test ===\n");

    // Connect
    println!("1. Connecting to companion app...");
    let mut stream = TcpStream::connect(("localhost", 38472)).await?;
    stream.set_nodelay(true)?;
    let mut framer = MessageFramer::new();
    println!("   ✓ Connected\n");

    // Enable events
    println!("2. Enabling events...");
    let enable_req = Request {
        request_id: Uuid::new_v4().to_string(),
        command: Some(Command::EnableEvents(EnableEventsRequest {
            enable: true,
            event_types: vec![EventType::UiChange as i32],
        })),
    };
    let msg = encode_message(MessageType::Request, &enable_req)?;
    stream.write_all(&msg).await?;

    // Read response
    let response = read_response(&mut stream, &mut framer).await?;
    println!("   ✓ Events enabled: success={}\n", response.success);

    // Wait a bit
    println!("3. Waiting 1 second for event system to initialize...");
    tokio::time::sleep(Duration::from_secs(1)).await;
    println!("   ✓ Done\n");

    // Drain any pending events
    println!("4. Draining pending events...");
    let mut drained = 0;
    loop {
        match read_event_timeout(&mut stream, &mut framer, Duration::from_millis(200)).await {
            Ok(Some(evt)) => {
                println!("   - Drained event: type={}, id={}", evt.event_type, evt.event_id);
                drained += 1;
            }
            _ => break,
        }
    }
    println!("   ✓ Drained {} events\n", drained);

    // Trigger UI change
    println!("5. Triggering UI change (press HOME button)...");
    let home_req = Request {
        request_id: Uuid::new_v4().to_string(),
        command: Some(Command::GlobalAction(neuralbridge_mcp::protocol::pb::GlobalActionRequest {
            action: neuralbridge_mcp::protocol::pb::GlobalAction::GlobalHome as i32,
        })),
    };
    let msg = encode_message(MessageType::Request, &home_req)?;
    stream.write_all(&msg).await?;

    // Read home response
    let home_resp = read_response(&mut stream, &mut framer).await?;
    println!("   ✓ Home action completed: success={}\n", home_resp.success);

    // Try to read events (check what's in the buffer)
    println!("6. Reading events from stream (up to 5 seconds)...");
    let start = std::time::Instant::now();
    let mut event_count = 0;

    loop {
        if start.elapsed() > Duration::from_secs(5) {
            break;
        }

        match read_message_timeout(&mut stream, &mut framer, Duration::from_millis(500)).await {
            Ok(Some((header, payload))) => {
                match header.message_type {
                    MessageType::Event => {
                        let event = Event::decode(&payload[..])?;
                        println!("   ✓ Received EVENT: type={} ({}), id={}, timestamp={}",
                                 event.event_type,
                                 event_type_name(event.event_type),
                                 event.event_id,
                                 event.timestamp);
                        event_count += 1;
                    }
                    MessageType::Response => {
                        let response = Response::decode(&payload[..])?;
                        println!("   - Received RESPONSE: request_id={}, success={}",
                                 response.request_id, response.success);
                    }
                    _ => {
                        println!("   - Received unknown message type: {:?}", header.message_type);
                    }
                }
            }
            Ok(None) => {
                println!("   - Timeout (500ms), no message");
            }
            Err(e) => {
                println!("   - Error reading: {}", e);
                break;
            }
        }
    }

    println!("\n7. Summary:");
    println!("   Total events received: {}", event_count);

    if event_count > 0 {
        println!("   ✅ EVENT STREAMING WORKS!\n");
        Ok(())
    } else {
        println!("   ❌ NO EVENTS RECEIVED\n");
        Err(anyhow::anyhow!("No events received"))
    }
}

async fn read_response(stream: &mut TcpStream, framer: &mut MessageFramer) -> Result<Response> {
    loop {
        if let Some((header, payload)) = framer.try_extract_message()? {
            if header.message_type == MessageType::Response {
                return Ok(Response::decode(&payload[..])?);
            }
        }

        let mut buf = vec![0u8; 4096];
        let n = stream.read(&mut buf).await?;
        if n == 0 {
            anyhow::bail!("Connection closed");
        }
        framer.add_data(&buf[..n]);
    }
}

async fn read_event_timeout(
    stream: &mut TcpStream,
    framer: &mut MessageFramer,
    timeout: Duration
) -> Result<Option<Event>> {
    match tokio::time::timeout(timeout, read_event(stream, framer)).await {
        Ok(Ok(evt)) => Ok(Some(evt)),
        Ok(Err(e)) => Err(e),
        Err(_) => Ok(None),
    }
}

async fn read_event(stream: &mut TcpStream, framer: &mut MessageFramer) -> Result<Event> {
    loop {
        if let Some((header, payload)) = framer.try_extract_message()? {
            if header.message_type == MessageType::Event {
                return Ok(Event::decode(&payload[..])?);
            }
        }

        let mut buf = vec![0u8; 4096];
        let n = stream.read(&mut buf).await?;
        if n == 0 {
            anyhow::bail!("Connection closed");
        }
        framer.add_data(&buf[..n]);
    }
}

async fn read_message_timeout(
    stream: &mut TcpStream,
    framer: &mut MessageFramer,
    timeout: Duration
) -> Result<Option<(neuralbridge_mcp::protocol::codec::MessageHeader, Vec<u8>)>> {
    match tokio::time::timeout(timeout, read_message(stream, framer)).await {
        Ok(Ok(msg)) => Ok(Some(msg)),
        Ok(Err(e)) => Err(e),
        Err(_) => Ok(None),
    }
}

async fn read_message(
    stream: &mut TcpStream,
    framer: &mut MessageFramer
) -> Result<(neuralbridge_mcp::protocol::codec::MessageHeader, Vec<u8>)> {
    loop {
        if let Some(msg) = framer.try_extract_message()? {
            return Ok(msg);
        }

        let mut buf = vec![0u8; 4096];
        let n = stream.read(&mut buf).await?;
        if n == 0 {
            anyhow::bail!("Connection closed");
        }
        framer.add_data(&buf[..n]);
    }
}

fn event_type_name(event_type: i32) -> &'static str {
    match event_type {
        0 => "UNSPECIFIED",
        1 => "UI_CHANGE",
        2 => "NOTIFICATION_POSTED",
        3 => "TOAST_SHOWN",
        4 => "APP_CRASH",
        _ => "UNKNOWN",
    }
}
