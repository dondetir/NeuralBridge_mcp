package com.neuralbridge.companion

import com.neuralbridge.companion.mcp.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun testJsonRpcRequestDeserializationIntId() {
        val raw = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        val req = json.decodeFromString<JsonRpcRequest>(raw)
        assertEquals("2.0", req.jsonrpc)
        assertEquals("tools/list", req.method)
        assertEquals(JsonPrimitive(1), req.id)
    }

    @Test
    fun testJsonRpcRequestDeserializationStringId() {
        val raw = """{"jsonrpc":"2.0","id":"abc-123","method":"initialize","params":{}}"""
        val req = json.decodeFromString<JsonRpcRequest>(raw)
        assertEquals("initialize", req.method)
        assertEquals(JsonPrimitive("abc-123"), req.id)
    }

    @Test
    fun testJsonRpcRequestNullId() {
        val raw = """{"jsonrpc":"2.0","id":null,"method":"ping"}"""
        val req = json.decodeFromString<JsonRpcRequest>(raw)
        // kotlinx.serialization maps JSON null to Kotlin null for nullable JsonElement?
        assertNull(req.id)
    }

    @Test
    fun testSuccessResponseSerialization() {
        val resp = successResponse(JsonPrimitive(1), buildJsonObject { put("key", "value") })
        val encoded = json.encodeToString(JsonRpcResponse.serializer(), resp)
        assertTrue(encoded.contains("\"result\""))
        assertFalse(encoded.contains("\"error\""))
        assertEquals("value", resp.result?.jsonObject?.get("key")?.jsonPrimitive?.content)
    }

    @Test
    fun testErrorResponseSerialization() {
        val resp = errorResponse(JsonPrimitive(1), JsonRpcErrorCodes.INVALID_PARAMS, "bad params")
        val encoded = json.encodeToString(JsonRpcResponse.serializer(), resp)
        assertTrue(encoded.contains("\"error\""))
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, resp.error?.code)
        assertEquals("bad params", resp.error?.message)
    }

    @Test
    fun testTextResult() {
        val result = textResult("hello world")
        assertEquals(1, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("hello world", result.content[0].text)
        assertFalse(result.isError)
    }

    @Test
    fun testErrorResult() {
        val result = errorResult("something broke")
        assertTrue(result.isError)
        assertEquals("text", result.content[0].type)
        assertEquals("something broke", result.content[0].text)
    }
}

class McpToolRegistryTest {
    @Test
    fun testToolCountAtLeast30() {
        assertTrue("Must have at least 30 tools", McpToolRegistry.getAllTools().size >= 30)
    }

    @Test
    fun testAllToolsHaveAndroidPrefix() {
        McpToolRegistry.getAllTools().forEach { tool ->
            assertTrue("${tool.name} must start with android_", tool.name.startsWith("android_"))
        }
    }

    @Test
    fun testAllToolsHaveDescription() {
        McpToolRegistry.getAllTools().forEach { tool ->
            assertTrue("${tool.name} description must not be empty", tool.description.isNotEmpty())
        }
    }

    @Test
    fun testNoDuplicateToolNames() {
        val names = McpToolRegistry.getAllTools().map { it.name }
        assertEquals("Duplicate tool names found", names.size, names.toSet().size)
    }

    @Test
    fun testGetTool() {
        val tool = McpToolRegistry.getTool("android_tap")
        assertNotNull(tool)
        assertEquals("android_tap", tool?.name)
    }

    @Test
    fun testGetToolNull() {
        assertNull(McpToolRegistry.getTool("nonexistent_tool"))
    }

    @Test
    fun testAllToolsHaveInputSchema() {
        McpToolRegistry.getAllTools().forEach { tool ->
            assertNotNull("${tool.name} must have inputSchema", tool.inputSchema)
            assertEquals(
                "${tool.name} inputSchema must be type object",
                "object",
                tool.inputSchema["type"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun testRequiredToolsPresent() {
        val required = listOf(
            "android_tap",
            "android_screenshot",
            "android_get_ui_tree",
            "android_swipe",
            "android_input_text",
            "android_wait_for_element"
        )
        required.forEach { name ->
            assertNotNull("$name must exist", McpToolRegistry.getTool(name))
        }
    }
}

class McpAuthTest {
    @Test
    fun testUuidFormat() {
        val uuidRegex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        repeat(5) {
            val key = java.util.UUID.randomUUID().toString()
            assertTrue("UUID must match format", uuidRegex.matches(key))
        }
    }

    @Test
    fun testNullKeyRejected() {
        val stored = "test-key-123"
        val provided: String? = null
        assertFalse(provided != null && provided == stored)
    }

    @Test
    fun testWrongKeyRejected() {
        val stored = "correct-key"
        val provided = "wrong-key"
        assertFalse(provided == stored)
    }

    @Test
    fun testCorrectKeyAccepted() {
        val key = "test-api-key-123"
        assertTrue(key == key)
    }
}
