package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class SemanticEmbeddingProviderInstrumentedTest {

    private lateinit var context: Context
    private lateinit var tempDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDirectory = File(context.cacheDir, "embedding-instrumented-${System.nanoTime()}").apply {
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempDirectory.deleteRecursively()
    }

    @Test
    fun lightweightTextEmbedding_isDeterministicNormalizedAndDimensioned() {
        val first = LightweightEmbeddingEngine.generateTextEmbedding("Family vacation photos 2026")
        val second = LightweightEmbeddingEngine.generateTextEmbedding("Family vacation photos 2026")

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(128, first!!.size)
        assertArrayEquals(first, second, 0.0f)
        assertEquals(1.0f, euclideanNorm(first), 0.0001f)
    }

    @Test
    fun lightweightTextEmbedding_rejectsBlankAndPunctuationOnlyInput() {
        assertNull(LightweightEmbeddingEngine.generateTextEmbedding(""))
        assertNull(LightweightEmbeddingEngine.generateTextEmbedding("   "))
        assertNull(LightweightEmbeddingEngine.generateTextEmbedding("--- !!! ..."))
    }

    @Test
    fun lightweightImageEmbedding_handlesMissingFileAndNonImageFallback() {
        val missing = File(tempDirectory, "missing.png")
        val textFile = File(tempDirectory, "meeting-notes.txt").apply {
            writeText("on-device-only content")
        }

        assertNull(LightweightEmbeddingEngine.generateImageEmbedding(missing))
        val fallback = LightweightEmbeddingEngine.generateImageEmbedding(textFile)

        assertNotNull(fallback)
        assertEquals(128, fallback!!.size)
        assertEquals(1.0f, euclideanNorm(fallback), 0.0001f)
    }

    @Test
    fun lightweightImageEmbedding_extractsFeaturesFromValidPng() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(220, 40, 80))
        }
        val image = File(tempDirectory, "sample.png")
        FileOutputStream(image).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()

        val embedding = LightweightEmbeddingEngine.generateImageEmbedding(image)

        assertNotNull(embedding)
        assertEquals(128, embedding!!.size)
        assertEquals(1.0f, euclideanNorm(embedding), 0.0001f)
    }

    @Test
    fun semanticProviderDefaultSerializationAndCosineSimilarityAreSafe() {
        val provider = FallbackSemanticEmbeddingProvider()

        assertEquals(1.0f, provider.calculateCosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)))
        assertEquals(0.0f, provider.calculateCosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 0f)))
        assertEquals(0.0f, provider.calculateCosineSimilarity(floatArrayOf(0f), floatArrayOf(1f)))
        assertEquals("1.0,2.0,3.0", provider.floatArrayToString(floatArrayOf(1f, 2f, 3f)))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), provider.stringToFloatArray("1.0,2.0,3.0")!!, 0.0f)
        assertNull(provider.stringToFloatArray(""))
        assertNull(provider.stringToFloatArray("not-a-vector"))
    }

    @Test
    fun fallbackProvider_usesDeterministicOnDeviceEmbeddingsWithoutModelAssets() = runBlocking {
        val provider = FallbackSemanticEmbeddingProvider()

        assertTrue(provider.isModelLoaded())
        val textEmbedding = provider.generateTextEmbedding("sensitive query")
        assertNotNull(textEmbedding)
        assertEquals(128, textEmbedding!!.size)
        assertEquals(1.0f, euclideanNorm(textEmbedding), 0.0001f)
        assertNull(provider.generateImageEmbedding(File(tempDirectory, "photo.jpg")))
    }

    @Test
    fun tfliteProvider_rejectsEmptyVocabularyInvalidAssetAndOversizedInput() = runBlocking {
        val provider = TFLiteSemanticEmbeddingProvider(File(tempDirectory, "missing.tflite"))
        val oversized = File(tempDirectory, "oversized.bin")
        RandomAccessFile(oversized, "rw").use { file ->
            file.setLength(50L * 1024L * 1024L + 1L)
        }

        assertFalse(provider.loadModelFromAssets(context, "invalid_model.tflite", "empty_vocab.txt"))
        assertFalse(provider.loadModelFromAssets(context, "invalid_model.tflite", "invalid_vocab.txt"))
        assertNull(provider.generateImageEmbedding(oversized))
        provider.close()
    }

    @Test
    fun tfliteProvider_failsClosedForMissingInvalidAndUnavailableModelInputs() = runBlocking {
        val provider = TFLiteSemanticEmbeddingProvider(File(tempDirectory, "missing.tflite"))
        val invalidModel = File(tempDirectory, "invalid.tflite").apply {
            writeText("not a TensorFlow Lite model")
        }

        assertFalse(provider.isModelLoaded())
        assertNull(provider.generateTextEmbedding("sensitive query"))
        assertNull(provider.generateImageEmbedding(File(tempDirectory, "photo.jpg")))
        assertFalse(provider.loadModelFromFile(invalidModel))
        assertFalse(provider.loadModelFromAssets(context, "missing-model.tflite", "missing-vocab.txt"))
        assertFalse(provider.loadModelFromBuffer(ByteBuffer.allocateDirect(8)))
        provider.close()
    }

    private fun euclideanNorm(vector: FloatArray): Float =
        sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
}
