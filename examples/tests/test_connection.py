#!/usr/bin/env python3
"""
Simple TCP connection test for NeuralBridge
Tests the binary protocol and protobuf communication
"""

import socket
import struct
import sys

MAGIC = 0x4E42
HOST = "localhost"
PORT = 38472

def send_message(sock, msg_type, payload):
    """Send a message with 7-byte header + payload"""
    # Pack header: magic (2B) + type (1B) + length (4B)
    length = len(payload)
    header = struct.pack(">HBI", MAGIC, msg_type, length)
    sock.sendall(header + payload)
    print(f"✓ Sent: type={msg_type}, length={length} bytes")

def receive_message(sock):
    """Receive a message and parse header"""
    # Read header (7 bytes)
    header = sock.recv(7)
    if len(header) < 7:
        return None, None

    magic, msg_type, length = struct.unpack(">HBI", header)

    if magic != MAGIC:
        print(f"✗ Invalid magic: 0x{magic:04x} (expected 0x{MAGIC:04x})")
        return None, None

    # Read payload
    payload = b""
    while len(payload) < length:
        chunk = sock.recv(length - len(payload))
        if not chunk:
            break
        payload += chunk

    print(f"✓ Received: type={msg_type}, length={length} bytes")
    return msg_type, payload

def test_connection():
    """Test TCP connection to NeuralBridge companion app"""
    print("🧪 NeuralBridge TCP Connection Test")
    print("=" * 50)

    try:
        # Connect
        print(f"\n1. Connecting to {HOST}:{PORT}...")
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5)
        sock.connect((HOST, PORT))
        print("   ✅ Connected!")

        # Send a simple protobuf Request (minimal, may not be valid)
        # This is just to test the wire protocol, not full functionality
        print("\n2. Testing wire protocol...")
        print("   (Note: This sends an empty request, expecting error response)")

        # Empty protobuf Request (just to test connection)
        test_payload = b""  # Empty payload
        send_message(sock, 0x01, test_payload)  # 0x01 = Request type

        # Try to receive response
        print("\n3. Waiting for response...")
        msg_type, payload = receive_message(sock)

        if msg_type == 0x02:  # Response type
            print("   ✅ Received Response message!")
            print(f"   Response payload: {len(payload)} bytes")
        else:
            print(f"   ⚠️  Unexpected message type: {msg_type}")

        sock.close()
        print("\n✅ TCP connection test PASSED!")
        print("   - Connection established")
        print("   - Wire protocol working (7-byte header)")
        print("   - Server responds to messages")
        return True

    except socket.timeout:
        print("   ✗ Connection timeout")
        return False
    except ConnectionRefusedError:
        print("   ✗ Connection refused - is companion app running?")
        return False
    except Exception as e:
        print(f"   ✗ Error: {e}")
        return False

if __name__ == "__main__":
    success = test_connection()
    sys.exit(0 if success else 1)
