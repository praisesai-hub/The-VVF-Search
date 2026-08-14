package com.example.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticEmbeddingProviderContractTest {

    private val provider = FallbackSemanticEmbeddingProvider()

    @Test
    fun calculateCosineSimilarity_handlesIdenticalOrthogonalAndInvalidVectors() {
        assertEquals(1.0f, provider.calculateCosineSimilarity(floatArrayOf(3f, 4f), floatArrayOf(3f, 4f)), 0.0001f)
        assertEquals(0.0f, provider.calculateCosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 0.0001f)
        assertEquals(0.0f, provider.calculateCosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 0f)), 0.0001f)
        assertEquals(0.0f, provider.calculateCosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 0.0001f)
    }

    @Test
    fun vectorSerialization_roundTripsAndRejectsMalformedValues() {
        val original = floatArrayOf(0.125f, -1.25f, 2.5f)
        val serialized = provider.floatArrayToString(original)

        assertArrayEquals(original, provider.stringToFloatArray(serialized), 0.0f)
        assertNull(provider.stringToFloatArray(""))
        assertNull(provider.stringToFloatArray("1.0,not-a-number"))
    }
}
