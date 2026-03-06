# Architecture Plan: Android-Native MCP HTTP Server

## Summary

Embed an MCP HTTP server (Ktor) directly in the Android companion app, replacing the two-process architecture. Claude Code connects over WiFi via HTTP/SSE transport — zero local installation.

## Key Architectural Decisions

### 1. Ktor Server Engine: CIO (not Netty)
- **CIO** (Coroutine IO) is Ktor's pure-Kotlin engine — no JNI, no native libraries
- ~800KB vs Netty's ~3.5MB, critical for APK size on an already 7.5MB app
- Works seamlessly with existing coroutine scoping (serviceScope)

### 2. JSON: kotlinx.serialization (not Gson)
- Already idiomatic Kotlin, compile-time codegen (no reflection)
- Ktor has first-class kotlinx.serialization support via ContentNegotiation plugin
- Minimal runtime overhead vs Gson's reflection-based approach

### 3. MCP Protocol: Streamable HTTP (2025-03-26 spec)
- Single endpoint: `POST /mcp` for all JSON-RPC 2.0 messages
- Stateless request/response — no SSE session required for tools
- Optional SSE for server-initiated events (notifications, UI changes)
- Claude Code config: `{"type": "http", "url": "http://device-ip:7474/mcp"}`

### 4. Authentication: API Key via header
- UUID generated on first launch, stored in SharedPreferences (`nb_api_key`)
- Validated via `NeuralBridge-API-Key` HTTP header on every request
- Displayed in app UI for one-time copy to Claude Code config

### 5. Binding: 0.0.0.0:7474 (all interfaces)
- WiFi interface for Claude Code connection
- Loopback for local testing
- Existing TcpServer stays on 127.0.0.1:38472 (unchanged)

### 6. Tool Mapping: Direct call to engines (bypass CommandHandler protobuf)
- New McpToolHandler receives JSON params → calls GestureEngine/UiTreeWalker/etc. directly
- Returns JSON responses (no protobuf intermediary)
- Gesture callbacks wrapped in `suspendCancellableCoroutine`
- CommandHandler.kt left untouched — TCP/protobuf path still works

## File Plan

### NEW FILES (6)

| File | Purpose | Lines Est. |
|------|---------|------------|
| `mcp/McpHttpServer.kt` | Ktor server lifecycle (start/stop/routes) | ~180 |
| `mcp/McpProtocol.kt` | JSON-RPC 2.0 data classes + MCP message types | ~120 |
| `mcp/McpToolRegistry.kt` | Tool definitions (name, description, inputSchema) | ~350 |
| `mcp/McpToolHandler.kt` | Tool execution (params → engine calls → JSON result) | ~600 |
| `mcp/McpAuthManager.kt` | API key generation/storage/validation | ~50 |
| `mcp/McpNetworkUtils.kt` | WiFi IP detection, mDNS (optional) | ~60 |

All in package: `com.neuralbridge.companion.mcp`

### MODIFIED FILES (3)

| File | Change | Impact |
|------|--------|--------|
| `build.gradle.kts` | Add Ktor CIO + kotlinx.serialization dependencies | Build config |
| `NeuralBridgeAccessibilityService.kt` | Add `mcpHttpServer` field, start/stop alongside TCP | Service lifecycle |
| `MainActivity.kt` | Show HTTP URL + API key in Setup tab; new connection card for HTTP | UI display |

## Task Decomposition

### Task 1: Build Config + Dependencies (SEQUENTIAL — blocks all others)
- Add kotlinx.serialization plugin to build.gradle.kts
- Add Ktor CIO server + content-negotiation dependencies
- Verify `./gradlew assembleDebug` succeeds

### Task 2: MCP Protocol Layer (PARALLEL with Task 3)
- `McpProtocol.kt`: JSON-RPC 2.0 request/response data classes
  - `JsonRpcRequest(jsonrpc, id, method, params)`
  - `JsonRpcResponse(jsonrpc, id, result?, error?)`
  - `McpToolDefinition(name, description, inputSchema)`
  - `McpToolCallResult(content: List<ContentBlock>, isError: Boolean)`
  - `ContentBlock(type, text?, data?, mimeType?)`
- `McpAuthManager.kt`: UUID generation + SharedPreferences storage

### Task 3: Tool Registry (PARALLEL with Task 2)
- `McpToolRegistry.kt`: All 35 tool definitions with JSON Schema
  - Each tool: name, description, inputSchema (JSON object)
  - Categories: observe, act, manage, wait, test, meta
  - 7 ADB-only tools marked with `requiresAdb = true`

### Task 4: Tool Handler (SEQUENTIAL — depends on Tasks 2+3)
- `McpToolHandler.kt`: Routes tool calls to engine methods
  - Constructor receives: AccessibilityService, GestureEngine, UiTreeWalker, InputEngine, ScreenshotPipeline
  - `suspend fun handleToolCall(name: String, arguments: JsonObject): McpToolCallResult`
  - Gesture callback → suspend wrapper pattern
  - Token-optimized responses (compact tree, omit empty fields)
  - ADB-only tools return clear error: "This tool requires the ADB bridge..."

### Task 5: HTTP Server + Routing (SEQUENTIAL — depends on Task 4)
- `McpHttpServer.kt`: Ktor CIO server
  - `POST /mcp` route handles: initialize, tools/list, tools/call
  - Auth middleware: validates `NeuralBridge-API-Key` header
  - CORS headers for browser-based clients
  - Graceful shutdown via server.stop()
- `McpNetworkUtils.kt`: WiFi IP helper

### Task 6: Service Integration (SEQUENTIAL — depends on Task 5)
- Modify `NeuralBridgeAccessibilityService.kt`:
  - Add `private lateinit var mcpHttpServer: McpHttpServer`
  - Start in `enable()` alongside TCP server
  - Stop in `disable()` and `onDestroy()`
  - Add `getMcpHttpConnectionCount()` method

### Task 7: UI Integration (SEQUENTIAL — depends on Task 6)
- Modify `MainActivity.kt`:
  - Show HTTP URL (`http://192.168.x.x:7474/mcp`) in Status tab
  - Show API key with copy button in Setup tab
  - Add MCP HTTP status to 3-up grid (alongside Accessibility, TCP, Screenshot)
  - Copy button for Claude Code MCP config JSON

### Task 8: Testing (PARALLEL — can start after Task 4)
- Unit tests for McpProtocol (JSON-RPC serialization)
- Unit tests for McpToolRegistry (tool count, schema validation)
- Unit tests for McpAuthManager (generate, store, validate)
- Integration test pattern: mock AccessibilityService + verify tool responses

## Data Flow

```
Claude Code
  │
  │ POST /mcp (JSON-RPC 2.0)
  │ Header: NeuralBridge-API-Key: <uuid>
  ▼
McpHttpServer (Ktor CIO, port 7474)
  │
  │ Parses JSON-RPC → routes by method
  ▼
┌─────────────────────────────────────┐
│ method: "initialize"                │ → return ServerInfo + capabilities
│ method: "tools/list"                │ → McpToolRegistry.getTools()
│ method: "tools/call"                │ → McpToolHandler.handleToolCall()
└─────────────────────────────────────┘
  │
  │ McpToolHandler dispatches to:
  ▼
┌────────────────────────────────────────┐
│ GestureEngine    (tap, swipe, pinch)   │ ← suspendCancellableCoroutine
│ UiTreeWalker     (get_ui_tree, find)   │ ← suspend
│ InputEngine      (input_text)          │ ← synchronous
│ ScreenshotPipeline (screenshot)        │ ← suspend
│ NotificationListener (notifications)   │ ← direct access
│ PackageManager   (list_apps)           │ ← Android API
│ Build class      (device_info)         │ ← Android API
│ Intent           (launch, open_url)    │ ← Android API
└────────────────────────────────────────┘
```

## MCP JSON-RPC Examples

### Initialize
```json
// Request
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"Claude Code","version":"1.0"}}}

// Response
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"neuralbridge-android","version":"0.4.0"}}}
```

### tools/list
```json
// Request
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}

// Response
{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"android_tap","description":"Tap at (x,y) or on element by text/resource_id/content_desc.","inputSchema":{"type":"object","properties":{"x":{"type":"integer","description":"X coordinate"},"y":{"type":"integer","description":"Y coordinate"},"text":{"type":"string","description":"Element text"},"resource_id":{"type":"string","description":"Resource ID"},"content_desc":{"type":"string","description":"Content description"}}}}]}}
```

### tools/call
```json
// Request
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"android_tap","arguments":{"text":"Login"}}}

// Response (success)
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"latency_ms\":45}"}]}}

// Response (error)
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Element not found: 'Login'"}],"isError":true}}
```

## Security Considerations

1. **API key auth** — every request validated before processing
2. **No file system access** — MCP tools only interact with Android UI/apps
3. **Rate limiting** — optional, Ktor plugin available
4. **HTTPS consideration** — deferred (self-signed cert adds UX friction; local network threat model is acceptable for v1)

## Dependency Versions

```kotlin
// Ktor (CIO engine — pure Kotlin, no native deps)
val ktor_version = "2.3.12"
implementation("io.ktor:ktor-server-cio:$ktor_version")
implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")

// kotlinx.serialization
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
```

## Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Android kills HTTP server | Medium | Already runs as foreground service; battery optimization exemption in Setup |
| WiFi disconnects | Medium | Server rebinds on reconnect; client retries |
| Port 7474 conflict | Low | Configurable port in SharedPreferences |
| APK size increase | Low | CIO engine adds ~800KB (vs current 7.5MB) |
| Ktor startup latency | Low | CIO starts in <200ms; server starts with service |
