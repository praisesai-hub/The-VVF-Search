package com.example.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SemanticEmbeddingProviderTest {

    private val provider = FallbackSemanticEmbeddingProvider()

    @Test
    fun `test float array to string and back`() {
        val original = floatArrayOf(0.1f, 0.5f, -0.2f)
        val str = provider.floatArrayToString(original)
        val reconstructed = provider.stringToFloatArray(str)
        
        assertTrue(reconstructed != null)
        assertArrayEquals(original, reconstructed!!, 0.0001f)
    }

    @Test
    fun `test cosine similarity`() {
        val v1 = floatArrayOf(1.0f, 0.0f)
        val v2 = floatArrayOf(1.0f, 0.0f)
        val v3 = floatArrayOf(0.0f, 1.0f)
        val v4 = floatArrayOf(-1.0f, 0.0f)

        val simSame = provider.calculateCosineSimilarity(v1, v2)
        assertEquals(1.0f, simSame, 0.001f)

        val simOrthogonal = provider.calculateCosineSimilarity(v1, v3)
        assertEquals(0.0f, simOrthogonal, 0.001f)

        val simOpposite = provider.calculateCosineSimilarity(v1, v4)
        assertEquals(-1.0f, simOpposite, 0.001f)
    }

    @Test
    fun `test fallback provider is not advertised as a production semantic model`() = runBlocking {
        assertFalse(provider.isModelLoaded())
        assertEquals(2, provider.embeddingVersion)
        val embedding = provider.generateTextEmbedding("local-only semantic search")
        assertNotNull(embedding)
        assertEquals(128, embedding!!.size)
    }

    @Test
    fun `test Latin-only fallback does not generate Devanagari embedding`() = runBlocking {
        assertNull(provider.generateTextEmbedding("बिजली का बिल"))
    }

    @Test
    fun `test model loading with missing model`() {
        val tfliteProvider = TFLiteSemanticEmbeddingProvider()
        val nonExistentFile = File("non_existent_model_file.tflite")
        val success = tfliteProvider.loadModelFromFile(nonExistentFile)
        assertFalse(success)
        assertFalse(tfliteProvider.isModelLoaded())
    }

    @Test
    fun `test model loading with invalid model file`() {
        val tempFile = File.createTempFile("invalid_model", ".tflite")
        tempFile.deleteOnExit()
        
        // Write corrupt/random bytes to the file
        FileOutputStream(tempFile).use { fos ->
            fos.write(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
        }

        val tfliteProvider = TFLiteSemanticEmbeddingProvider()
        val success = tfliteProvider.loadModelFromFile(tempFile)
        
        // Should handle corrupt file gracefully and return false without crashing the JVM
        assertFalse(success)
        assertFalse(tfliteProvider.isModelLoaded())
    }

    @Test
    fun `test load model from invalid byte buffer`() {
        val buffer = ByteBuffer.allocateDirect(16)
        buffer.order(ByteOrder.nativeOrder())
        buffer.put(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        buffer.rewind()

        val tfliteProvider = TFLiteSemanticEmbeddingProvider()
        val success = tfliteProvider.loadModelFromBuffer(buffer)
        
        // Interpreter creation should fail on bad flatbuffer metadata but handle it gracefully
        assertFalse(success)
        assertFalse(tfliteProvider.isModelLoaded())
    }

    @Test
    fun `test deterministic tokenizer and lightweight embedding generation`() {
        val text1 = "Quick brown fox"
        val text2 = "quick brown fox"
        val text3 = "Slower blue elephant"

        val emb1 = LightweightEmbeddingEngine.generateTextEmbedding(text1)
        val emb2 = LightweightEmbeddingEngine.generateTextEmbedding(text2)
        val emb3 = LightweightEmbeddingEngine.generateTextEmbedding(text3)

        // Embeddings must be non-null and have length of 128 dimensions
        assertTrue(emb1 != null)
        assertTrue(emb2 != null)
        assertTrue(emb3 != null)
        assertEquals(128, emb1!!.size)
        assertEquals(128, emb2!!.size)
        assertEquals(128, emb3!!.size)

        // Preprocessing is case-insensitive, so identical text with different case must produce identical embeddings
        assertArrayEquals(emb1, emb2, 0.00001f)

        // Different text must produce different embeddings
        val simSame = provider.calculateCosineSimilarity(emb1, emb2)
        val simDiff = provider.calculateCosineSimilarity(emb1, emb3)

        assertEquals(1.0f, simSame, 0.001f)
        assertTrue(simDiff < 0.9f)
    }

    @Test
    fun `test vector L2 normalization`() {
        val vector = floatArrayOf(3.0f, 4.0f)
        
        // Custom L2 normalization function
        var sumSquares = 0.0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares.toDouble()).toFloat()
        val normalized = FloatArray(vector.size)
        for (i in vector.indices) {
            normalized[i] = vector[i] / norm
        }

        // The norm of normalized vector must be 1.0f
        var normalizedSumSq = 0.0f
        for (v in normalized) {
            normalizedSumSq += v * v
        }
        val normalizedNorm = sqrt(normalizedSumSq.toDouble()).toFloat()
        assertEquals(1.0f, normalizedNorm, 0.0001f)
    }

    @Test
    fun `test similarity ranking logic`() {
        val query = floatArrayOf(1.0f, 0.0f)
        
        val doc1 = floatArrayOf(1.0f, 0.0f) // Identical: Cosine Sim = 1.0
        val doc2 = floatArrayOf(0.707f, 0.707f) // Cosine Sim ~ 0.707
        val doc3 = floatArrayOf(0.0f, 1.0f) // Orthogonal: Cosine Sim = 0.0

        val sim1 = provider.calculateCosineSimilarity(query, doc1)
        val sim2 = provider.calculateCosineSimilarity(query, doc2)
        val sim3 = provider.calculateCosineSimilarity(query, doc3)

        assertTrue(sim1 > sim2)
        assertTrue(sim2 > sim3)

        // Rank pairs
        val ranked = listOf(
            "doc3" to sim3,
            "doc1" to sim1,
            "doc2" to sim2
        ).sortedByDescending { it.second }

        assertEquals("doc1", ranked[0].first)
        assertEquals("doc2", ranked[1].first)
        assertEquals("doc3", ranked[2].first)
    }

    @Test
    fun `test fallback when model files are completely missing`() = runBlocking {
        // TFLiteSemanticEmbeddingProvider should report model as not loaded when initialized with defaults
        val tfliteProvider = TFLiteSemanticEmbeddingProvider()
        assertFalse(tfliteProvider.isModelLoaded())
        
        // Calls to generate embeddings must gracefully return null
        val imgEmbedding = tfliteProvider.generateTextEmbedding("test")
        assertNull(imgEmbedding)
    }
}
