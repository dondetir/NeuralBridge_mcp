package com.neuralbridge.companion.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// JSON-RPC 2.0 error codes
object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class McpToolCallResult(
    val content: List<McpContentBlock>,
    val isError: Boolean = false
)

@Serializable
data class McpContentBlock(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null
)

// Helper functions
fun successResponse(id: JsonElement?, result: JsonObject): JsonRpcResponse =
    JsonRpcResponse(id = id, result = result)

fun errorResponse(id: JsonElement?, code: Int, message: String): JsonRpcResponse =
    JsonRpcResponse(id = id, error = JsonRpcError(code = code, message = message))

fun textResult(text: String): McpToolCallResult =
    McpToolCallResult(content = listOf(McpContentBlock(type = "text", text = text)))

fun errorResult(message: String): McpToolCallResult =
    McpToolCallResult(content = listOf(McpContentBlock(type = "text", text = message)), isError = true)

fun imageResult(base64Data: String, mimeType: String = "image/jpeg", metadata: String? = null): McpToolCallResult {
    val blocks = mutableListOf(McpContentBlock(type = "image", data = base64Data, mimeType = mimeType))
    if (metadata != null) blocks.add(McpContentBlock(type = "text", text = metadata))
    return McpToolCallResult(content = blocks)
}
