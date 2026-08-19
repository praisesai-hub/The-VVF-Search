package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticAnnIndexTest {
    @Test
    fun bucketsFor_createsStableFourBandEntries() {
        val vector = FloatArray(128) { index -> if (index % 2 == 0) 0.5f else -0.5f }
        val serialized = vector.joinToString(",")

        val buckets = SemanticAnnIndex.bucketsFor(42L, 3, serialized)

        assertEquals(4, buckets.size)
        assertTrue(buckets.all { it.fileId == 42L && it.embeddingVersion == 3 })
    }

    @Test
    fun probeKeys_includeEachExactBucketBeforeOneBitNeighbors() {
        val vector = FloatArray(128) { index -> if (index % 3 == 0) 0.3f else -0.3f }
        val exact = SemanticAnnIndex.bucketsFor(7L, 3, vector.joinToString(",")).map { it.bucketKey }
        val probes = SemanticAnnIndex.probeKeys(vector)

        assertTrue(exact.all { it in probes })
        assertTrue(probes.size > exact.size)
    }
}
