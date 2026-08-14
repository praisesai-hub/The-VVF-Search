package com.example.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class TFLiteSemanticEmbeddingProviderJvmTest {

    @Test
    fun modelUnavailable_failsClosedWithoutProducingEmbeddings() = runBlocking {
        val provider = TFLiteSemanticEmbeddingProvider(File("/missing/model.tflite"))

        assertFalse(provider.isModelLoaded())
        assertNull(provider.generateTextEmbedding("sensitive search query"))
        assertNull(provider.generateImageEmbedding(File("/missing/photo.jpg")))
        assertNull(provider.generateTextEmbedding(""))
        provider.close()
    }
}
