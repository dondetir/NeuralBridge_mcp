package com.neuralbridge.companion

import neuralbridge.Neuralbridge
import org.junit.Test

/**
 * Test to verify protobuf classes are generated and can be imported.
 */
class ProtobufImportTest {
    @Test
    fun testProtobufImport() {
        // Verify we can access generated protobuf classes
        val request = Neuralbridge.Request.newBuilder()
            .setRequestId("test-123")
            .build()

        assert(request.requestId == "test-123")
    }

    @Test
    fun testProtobufEnums() {
        // Verify enums are accessible
        val quality = Neuralbridge.ScreenshotQuality.FULL
        val format = Neuralbridge.ImageFormat.JPEG

        assert(quality != null)
        assert(format != null)
    }
}
