package com.example.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFingerprintTest {
    @Test
    fun serializedSamplesRetainsOnlyCompleteDHashValues() {
        val fingerprint = VideoFingerprint(
            sampleHashes = listOf("0".repeat(16), "short", "1".repeat(16)),
            durationMs = 1L,
            width = 1,
            height = 1,
            audioSignature = "audio",
            chunkHash = "chunk"
        )

        assertEquals("${"0".repeat(16)};${"1".repeat(16)}", fingerprint.serializedSamples())
    }
}
