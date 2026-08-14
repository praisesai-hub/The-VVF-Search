package com.example.ai

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import kotlin.math.sqrt

class LightweightEmbeddingEngineTest {

    private lateinit var tempDirectory: java.nio.file.Path

    @Before
    fun setUp() {
        tempDirectory = Files.createTempDirectory("lightweight-embedding-test")
    }

    @After
    fun tearDown() {
        Files.walk(tempDirectory).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun generateTextEmbedding_returnsNormalizedDeterministic128DimensionVector() {
        val first = LightweightEmbeddingEngine.generateTextEmbedding("Family vacation photos 2026")
        val second = LightweightEmbeddingEngine.generateTextEmbedding("Family vacation photos 2026")

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(128, first!!.size)
        assertArrayEquals(first, second, 0.0f)
        assertEquals(1.0f, euclideanNorm(first), 0.0001f)
    }

    @Test
    fun generateTextEmbedding_rejectsBlankAndNonAlphanumericInputs() {
        assertNull(LightweightEmbeddingEngine.generateTextEmbedding(""))
        assertNull(LightweightEmbeddingEngine.generateTextEmbedding("   "))
        assertNull(LightweightEmbeddingEngine.generateTextEmbedding("--- !!! ..."))
    }

    @Test
    fun generateImageEmbedding_failsClosedForMissingFile() {
        val missing = tempDirectory.resolve("missing.png").toFile()

        assertFalse(missing.exists())
        assertNull(LightweightEmbeddingEngine.generateImageEmbedding(missing))
    }

    @Test
    fun generateImageEmbedding_usesOnDeviceFilenameFallbackForReadableNonImage() {
        val file = tempDirectory.resolve("meeting-notes.txt").toFile().apply {
            writeText("on-device-only content")
        }

        val embedding = LightweightEmbeddingEngine.generateImageEmbedding(file)

        assertNotNull(embedding)
        assertEquals(128, embedding!!.size)
        assertEquals(1.0f, euclideanNorm(embedding), 0.0001f)
    }

    private fun euclideanNorm(vector: FloatArray): Float =
        sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
}
