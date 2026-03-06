package com.neuralbridge.companion.mcp

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    // =========================================================================
    // JsonRpcRequest deserialization
    // =========================================================================

    @Test
    fun `deserialize request with integer id`() {
        val raw = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        val req = json.decodeFromString<JsonRpcRequest>(raw)
        assertEquals("2.0", req.jsonrpc)
        assertEquals(JsonPrimitive(1), req.id)
        assertEquals("initialize", req.method)
    }

    @Test
    fun `deserialize request with string id`() {
        val raw = """{"jsonrpc":"2.0","id":"req-abc","method":"tools/list"}"""
        val req = json.decodeFromString<JsonRpcRequest>(raw)
        assertEquals("req-abc", req.id?.jsonPrimitive?.content)
        assertEquals("tools/list", req.method)
    }

    @Test
    fun `deserialize request with null id (notification)`() {
        val raw = """{"jsonrpc":"2.0","id":null,"method":"notifications/initialized"}"""
        val req = json.decodeFromString<JsonRpcRequest>(raw)
        // kotlinx.serialization deserializes JSON null for a nullable field as Kotlin null
        assertTrue("id should be null or JsonNull for JSON null value",
            req.id == null || req.id is JsonNull)
    }

    @Test
    fun `deserialize request with nested params`() {
        val raw = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"android_tap","arguments":{"x":100,"y":200}}}"""
        val req = json.decodeFromString<JsonRpcRequest>(raw)
        assertNotNull("params missing", req.params)
        val params = req.params!!.jsonObject
        assertEquals("android_tap", params["name"]?.jsonPrimitive?.content)
        assertNotNull("arguments missing", params["arguments"])
        val args = params["arguments"]!!.jsonObject
        assertEquals(100, args["x"]?.jsonPrimitive?.int)
    }

    // =========================================================================
    // JsonRpcResponse serialization
    // =========================================================================

    @Test
    fun `serialize success response`() {
        val result = buildJsonObject { put("protocolVersion", "2024-11-05") }
        val response = successResponse(JsonPrimitive(1), result)
        val serialized = json.encodeToString(JsonRpcResponse.serializer(), response)
        assertTrue(serialized.contains("\"result\""))
        assertFalse(serialized.contains("\"error\""))
        assertTrue(serialized.contains("2024-11-05"))
    }

    @Test
    fun `serialize error response`() {
        val response = errorResponse(JsonPrimitive(2), JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: foo")
        val serialized = json.encodeToString(JsonRpcResponse.serializer(), response)
        assertTrue(serialized.contains("\"error\""))
        assertTrue(serialized.contains("-32601"))
        assertTrue(serialized.contains("Method not found"))
    }

    @Test
    fun `serialize response with null id`() {
        val response = errorResponse(null, JsonRpcErrorCodes.PARSE_ERROR, "Bad JSON")
        val serialized = json.encodeToString(JsonRpcResponse.serializer(), response)
        // null id should be omitted (encodeDefaults = false)
        assertFalse("null id should be omitted from JSON", serialized.contains("\"id\""))
    }

    // =========================================================================
    // Helper functions
    // =========================================================================

    @Test
    fun `textResult creates text content block`() {
        val result = textResult("hello world")
        assertEquals(1, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("hello world", result.content[0].text)
        assertFalse(result.isError)
    }

    @Test
    fun `errorResult sets isError true`() {
        val result = errorResult("something went wrong")
        assertTrue(result.isError)
        assertEquals("text", result.content[0].type)
        assertEquals("something went wrong", result.content[0].text)
    }

    @Test
    fun `imageResult creates image and optional text blocks`() {
        val result = imageResult("base64data==", "image/jpeg", "metadata")
        assertEquals(2, result.content.size)
        assertEquals("image", result.content[0].type)
        assertEquals("base64data==", result.content[0].data)
        assertEquals("image/jpeg", result.content[0].mimeType)
        assertEquals("text", result.content[1].type)
        assertEquals("metadata", result.content[1].text)
    }

    @Test
    fun `imageResult without metadata has only one block`() {
        val result = imageResult("data==")
        assertEquals(1, result.content.size)
        assertEquals("image", result.content[0].type)
    }

    // =========================================================================
    // Error codes
    // =========================================================================

    @Test
    fun `error codes have correct values`() {
        assertEquals(-32700, JsonRpcErrorCodes.PARSE_ERROR)
        assertEquals(-32600, JsonRpcErrorCodes.INVALID_REQUEST)
        assertEquals(-32601, JsonRpcErrorCodes.METHOD_NOT_FOUND)
        assertEquals(-32602, JsonRpcErrorCodes.INVALID_PARAMS)
        assertEquals(-32603, JsonRpcErrorCodes.INTERNAL_ERROR)
    }
}
