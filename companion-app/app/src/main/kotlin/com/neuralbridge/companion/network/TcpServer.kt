package com.neuralbridge.companion.network

import android.util.Log
import com.neuralbridge.companion.service.NeuralBridgeAccessibilityService
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TCP Server for MCP Protocol Communication
 *
 * Listens on port 38472 for connections from the MCP server.
 * Handles binary protobuf protocol with 7-byte header:
 *
 * ┌─────────────┬────────────┬──────────────┬────────────────┐
 * │ Magic (2B)  │ Type (1B)  │ Length (4B)  │ Payload (N B)  │
 * │   0x4E42    │ 0x01-0x03  │  big-endian  │   Protobuf     │
 * └─────────────┴────────────┴──────────────┴────────────────┘
 */
class TcpServer(
    private val port: Int,
    private val accessibilityService: NeuralBridgeAccessibilityService,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TcpServer"
        private const val MAGIC = 0x4E42.toShort() // "NB"
        private const val HEADER_SIZE = 7
        private const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024 // 16MB

        // Message types
        private const val TYPE_REQUEST: Byte = 0x01
        private const val TYPE_RESPONSE: Byte = 0x02
        private const val TYPE_EVENT: Byte = 0x03
    }

    private var serverSocket: ServerSocket? = null
    private val activeConnections = CopyOnWriteArrayList<ClientConnection>()

    /**
     * Start the TCP server
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting TCP server on port $port")

        try {
            // Create unbound socket, set reuseAddress BEFORE binding
            // Bind to loopback only (127.0.0.1) — prevents LAN-reachable exposure.
            // ADB port-forwarding (tcp:38472 tcp:38472) is the intended transport.
            val loopback = InetAddress.getLoopbackAddress()
            serverSocket = ServerSocket().apply {
                reuseAddress = true  // Must be set before bind()
                soTimeout = 0 // No timeout for accept()
                bind(java.net.InetSocketAddress(loopback, port), 50)
            }

            Log.i(TAG, "TCP server listening on port $port")

            // Accept connections in loop
            while (isActive) {
                try {
                    Log.d(TAG, "Waiting for client connection...")
                    val clientSocket = serverSocket?.accept() ?: break
                    Log.i(TAG, "Client connected: ${clientSocket.inetAddress}")
                    handleClient(clientSocket)
                } catch (e: SocketTimeoutException) {
                    // Timeout is ok, continue loop
                } catch (e: IOException) {
                    // Only log if this is unexpected (not a clean shutdown)
                    if (isActive && serverSocket != null) {
                        Log.e(TAG, "Error accepting connection", e)
                    } else {
                        Log.d(TAG, "Accept interrupted (server stopping)")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start TCP server", e)
            throw e
        }
    }

    /**
     * Handle client connection
     */
    private fun handleClient(socket: Socket) {
        // Create execution engines
        val gestureEngine = com.neuralbridge.companion.gesture.GestureEngine(accessibilityService)
        val uiTreeWalker = com.neuralbridge.companion.uitree.UiTreeWalker(accessibilityService)
        val inputEngine = com.neuralbridge.companion.input.InputEngine(accessibilityService)

        // Create command handler
        val commandHandler = com.neuralbridge.companion.service.CommandHandler(
            accessibilityService = accessibilityService,
            gestureEngine = gestureEngine,
            uiTreeWalker = uiTreeWalker,
            inputEngine = inputEngine
        )

        val connection = ClientConnection(socket, accessibilityService, commandHandler)
        activeConnections.add(connection)
        Log.i(TAG, "Connection added to activeConnections, total: ${activeConnections.size}")

        scope.launch(Dispatchers.IO) {
            try {
                connection.handleMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Client connection error", e)
            } finally {
                activeConnections.remove(connection)
                Log.i(TAG, "Connection removed from activeConnections, remaining: ${activeConnections.size}")
                try {
                    socket.close()
                } catch (e: IOException) {
                    // Ignore close errors
                }
                Log.i(TAG, "Client disconnected: ${socket.inetAddress}")
            }
        }
    }

    /**
     * Stop the TCP server
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Stopping TCP server")

        // Close all client connections
        activeConnections.forEach { connection ->
            try {
                connection.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client connection", e)
            }
        }
        activeConnections.clear()

        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null

        Log.i(TAG, "TCP server stopped")
    }

    /**
     * Get number of active connections
     */
    fun getActiveConnectionCount(): Int = activeConnections.size

    /**
     * Broadcast event to all connected clients
     */
    fun broadcastEvent(eventPayload: ByteArray) {
        Log.d(TAG, "Broadcasting event to ${activeConnections.size} connections (${eventPayload.size} bytes)")
        activeConnections.forEach { connection ->
            try {
                Log.d(TAG, "Calling sendEvent() on connection")
                connection.sendEvent(eventPayload)
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting event to connection", e)
            }
        }
    }
}

/**
 * Client connection handler
 */
private class ClientConnection(
    private val socket: Socket,
    private val accessibilityService: NeuralBridgeAccessibilityService,
    private val commandHandler: com.neuralbridge.companion.service.CommandHandler
) {
    companion object {
        private const val TAG = "ClientConnection"
        private const val MAGIC = 0x4E42.toShort()
        private const val HEADER_SIZE = 7
    }

    private val inputStream = socket.getInputStream()
    private val outputStream = socket.getOutputStream()

    /**
     * Main message handling loop
     */
    suspend fun handleMessages() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Handling messages from ${socket.inetAddress}")

        while (!socket.isClosed) {
            try {
                // Read message header
                val header = readHeader() ?: break

                // Read payload
                val payload = readPayload(header.payloadLength) ?: break

                // Process message
                processMessage(header, payload)

            } catch (e: IOException) {
                Log.d(TAG, "Connection closed or read error: ${e.message}")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
                // Continue to next message (error already logged)
            }
        }
    }

    /**
     * Read message header (7 bytes)
     */
    private fun readHeader(): MessageHeader? {
        val headerBytes = ByteArray(HEADER_SIZE)
        var offset = 0

        while (offset < HEADER_SIZE) {
            val read = inputStream.read(headerBytes, offset, HEADER_SIZE - offset)
            if (read == -1) return null
            offset += read
        }

        return parseHeader(headerBytes)
    }

    /**
     * Parse message header
     */
    private fun parseHeader(bytes: ByteArray): MessageHeader? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // Verify magic
        val magic = buffer.short
        if (magic != MAGIC) {
            Log.e(TAG, "Invalid magic: 0x${magic.toString(16)}")
            return null
        }

        // Parse type
        val type = buffer.get()

        // Parse length
        val length = buffer.int

        // Validate length
        if (length < 0 || length > 16 * 1024 * 1024) {
            Log.e(TAG, "Invalid payload length: $length")
            return null
        }

        return MessageHeader(type, length)
    }

    /**
     * Read payload
     */
    private fun readPayload(length: Int): ByteArray? {
        val payload = ByteArray(length)
        var offset = 0

        while (offset < length) {
            val read = inputStream.read(payload, offset, length - offset)
            if (read == -1) return null
            offset += read
        }

        return payload
    }

    /**
     * Process incoming message
     */
    private suspend fun processMessage(header: MessageHeader, payload: ByteArray) {
        when (header.type) {
            0x01.toByte() -> { // Request
                handleRequest(payload)
            }
            0x02.toByte() -> { // Response (unexpected from client)
                Log.w(TAG, "Received unexpected Response message from client")
            }
            0x03.toByte() -> { // Event (unexpected from client)
                Log.w(TAG, "Received unexpected Event message from client")
            }
            else -> {
                Log.w(TAG, "Unknown message type: ${header.type}")
            }
        }
    }

    /**
     * Handle request message
     */
    private suspend fun handleRequest(payload: ByteArray) {
        Log.d(TAG, "Received request: ${payload.size} bytes")

        try {
            // 1. Decode protobuf Request message
            val request = neuralbridge.Neuralbridge.Request.parseFrom(payload)
            Log.d(TAG, "Decoded request: id=${request.requestId}, command=${request.commandCase}")

            // 2. Route to CommandHandler
            val response = commandHandler.handleRequest(request)

            // 3. Serialize Response to bytes
            val responseBytes = response.toByteArray()

            // 4. Send response back with type 0x02
            sendMessage(0x02, responseBytes)

            Log.d(TAG, "Sent response: id=${response.requestId}, success=${response.success}, ${responseBytes.size} bytes")

        } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
            Log.e(TAG, "Failed to decode protobuf Request", e)
            // Send error response
            val errorResponse = neuralbridge.Neuralbridge.Response.newBuilder()
                .setRequestId("unknown")
                .setSuccess(false)
                .setErrorCode(neuralbridge.Neuralbridge.ErrorCode.ERROR_UNSPECIFIED)
                .setErrorMessage("Invalid protobuf message: ${e.message}")
                .build()
            sendMessage(0x02, errorResponse.toByteArray())

        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            // Send error response
            val errorResponse = neuralbridge.Neuralbridge.Response.newBuilder()
                .setRequestId("unknown")
                .setSuccess(false)
                .setErrorCode(neuralbridge.Neuralbridge.ErrorCode.ERROR_UNSPECIFIED)
                .setErrorMessage("Internal error: ${e.message}")
                .build()
            sendMessage(0x02, errorResponse.toByteArray())
        }
    }

    /**
     * Send message to client
     */
    private fun sendMessage(type: Byte, payload: ByteArray) {
        // Combine header + payload into a SINGLE byte array for atomic write
        val combined = ByteArray(HEADER_SIZE + payload.size)

        // Build header directly into combined array
        ByteBuffer.wrap(combined, 0, HEADER_SIZE).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(MAGIC)
            put(type)
            putInt(payload.size)
        }

        // Copy payload
        System.arraycopy(payload, 0, combined, HEADER_SIZE, payload.size)

        synchronized(outputStream) {
            outputStream.write(combined)
            outputStream.flush()
        }

        Log.d(TAG, "Sent message: type=0x${type.toString(16)}, payload=${payload.size} bytes, total=${combined.size} bytes")
    }

    /**
     * Send event to client (type=0x03)
     */
    fun sendEvent(eventPayload: ByteArray) {
        Log.d(TAG, "Sending event (type=0x03) to client: ${eventPayload.size} bytes")
        sendMessage(0x03, eventPayload)
    }

    /**
     * Close connection
     */
    fun close() {
        try {
            socket.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing socket", e)
        }
    }
}

/**
 * Message header structure
 */
private data class MessageHeader(
    val type: Byte,
    val payloadLength: Int
)
