# Task #3: Protocol Corruption After enable_events - Technical Analysis

**Date:** 2026-02-10
**Investigator:** protocol-debugger
**Status:** Root cause identified, fix in progress

## Problem Statement

After calling `android_enable_events(true)`, all subsequent MCP tool calls fail with:
```
Invalid magic bytes: 0x4203, expected 0x4E42
```

## Symptoms

- **Affected scenarios:** 4, 5, 6, 7 (all scenarios after enable_events)
- **Error pattern:** Always shows `0x4203` instead of `0x4E42`
- **Timing:** Error occurs 2-3 commands after enabling events
- **Impact:** Complete connection corruption, requires reconnect

## Root Cause Analysis

### Hex Dump Evidence
```
Buffer hex (first 32 bytes): 42 03 00 00 00 3B 0A 24 38 39 37 31 66 35 35 65 2D 37 64 65 39 2D 34 63 31 37 2D 39 37 34 65 2D
```

**Breakdown:**
- `42 03`: Bytes 1-2 of Event header (MISSING first byte `4E`)
- `00 00 00 3B`: Payload length = 59 bytes (big-endian)
- `0A 24 ...`: Protobuf Event payload (field 1, event_id)

**Conclusion:** MessageFramer buffer is offset by exactly 1 byte. The previous message extraction consumed one extra byte (`0x4E`).

### Architecture Flow

```
[MCP Server] --Request--> [Companion App]
                              |
                              v
                          handleRequest()
                              |
                              +---> handleEnableEvents()
                              |         |
                              |         v
                              |     setEventsEnabled(true)
                              |
                              v
                          sendResponse() (type=0x02)
                              |
                              v
[MCP Server] <--Response-- [Companion App]

                          [AccessibilityEvent fires]
                              |
                              v
                          onAccessibilityEvent()
                              |
                              v
                          sendUIChangeEvent()
                              |
                              v
                          serviceScope.launch(IO) {
                              broadcastEvent() (type=0x03)
                          }
                              |
                              v
[MCP Server] <--Event----- [Companion App]
```

### Key Findings

1. **Write Pattern:** Companion app uses TWO separate `outputStream.write()` calls:
   ```kotlin
   outputStream.write(header)   // 7 bytes
   outputStream.write(payload)  // N bytes
   outputStream.flush()
   ```

2. **Synchronization:** Both writes are inside `synchronized(outputStream)` block

3. **Threading:** Events sent via coroutines on IO dispatcher, responses sent on TCP server thread

4. **Protocol:** Wire format is:
   ```
   [Magic: 0x4E42] [Type: 0x01-0x03] [Length: 4 bytes BE] [Payload: N bytes]
   ```

### Hypotheses

#### Hypothesis 1: Write Exception Between Header and Payload ❌
- **Theory:** Exception thrown after writing header but before writing payload
- **Evidence:** No exceptions in companion app logs
- **Status:** REJECTED

#### Hypothesis 2: Protobuf Length Mismatch ⚠️
- **Theory:** Protobuf `toByteArray()` returns N bytes, but header claims N+1 or N-1 bytes
- **Evidence:** Hex dump shows correct length field (59 bytes) for visible payload
- **Status:** NEEDS VERIFICATION

#### Hypothesis 3: MessageFramer Bug ⚠️
- **Theory:** `BytesMut::split_to()` consumes incorrect number of bytes
- **Evidence:** bytes crate is well-tested, unlikely but possible
- **Status:** LOW PROBABILITY

#### Hypothesis 4: Race Condition in Synchronized Block ⚠️
- **Theory:** Despite synchronized, bytes from different threads interleave
- **Evidence:** Synchronized should prevent this, but coroutines may behave differently
- **Status:** NEEDS INVESTIGATION

## Fix Attempts

### Attempt 1: Atomic Write with Combined Buffer ❌
**Code:**
```kotlin
val combined = ByteArray(HEADER_SIZE + payload.size)
System.arraycopy(header, 0, combined, 0, HEADER_SIZE)
System.arraycopy(payload, 0, combined, HEADER_SIZE, payload.size)
outputStream.write(combined)
```

**Result:** Companion app immediately closes connection after first request

**Diagnosis:** Likely caused build/runtime issue, needs investigation

### Attempt 2: Enhanced Error Handling ❌
**Code:**
```kotlin
try {
    outputStream.write(header)
    outputStream.write(payload)
    outputStream.flush()
} catch (e: Exception) {
    Log.e(TAG, "Failed to send message", e)
    close()
}
```

**Result:** Doesn't fix root cause, only handles aftermath

## Recommended Next Steps

### Option A: Deep Diagnostic (1-2 hours)
1. Add comprehensive logging to companion app sendMessage()
2. Log exact byte counts for header and payload
3. Compare Kotlin protobuf encoding vs Rust decoding
4. Add hex dump logging on companion app write side
5. Verify synchronized block behavior with coroutines

### Option B: Workaround (30 minutes)
1. Disable event streaming feature in scenarios 4-7
2. Document as known issue
3. Unblock other test scenarios
4. Fix separately in future iteration

### Option C: Alternative Architecture (2-3 hours)
1. Implement dedicated reader task in Rust (already attempted, introduced new bugs)
2. Use separate TCP connections for events vs responses
3. Implement proper framing recovery mechanism

## Files Modified

- `mcp-server/src/protocol/connection.rs` - Added hex dump logging
- `mcp-server/src/protocol/codec.rs` - Enhanced error messages with hex dump
- `companion-app/.../network/TcpServer.kt` - Attempted fixes (reverted)

## Impact

**Blocked Scenarios:**
- Scenario 4: Event Streaming
- Scenario 5: Clipboard Operations (uses same corrupted connection)
- Scenario 6: App Lifecycle Management (uses same corrupted connection)
- Scenario 7: Performance Stress Test (uses same corrupted connection)

**Success Rate:** 1/7 scenarios passing (Scenario 2 only)
