package com.example.data

import com.example.ai.SemanticEmbeddingProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticIndexingPolicyTest {
    private val provider = object : SemanticEmbeddingProvider {
        override val embeddingVersion: Int = 3
        override fun isModelLoaded(): Boolean = true
        override suspend fun generateImageEmbedding(file: File): FloatArray? = null
        override suspend fun generateTextEmbedding(text: String): FloatArray? = floatArrayOf(1f, 0f)
    }

    @Test
    fun needsReindex_requiresReplacementForLegacyFallbackVector() = runBlocking {
        val legacyItem = document(semanticIndexed = true, version = 2, vector = "0.1,0.2")

        assertTrue(SemanticIndexingPolicy.needsReindex(legacyItem, provider))
    }

    @Test
    fun needsReindex_keepsCompatibleNonEmptyEmbedding() = runBlocking {
        val currentItem = document(semanticIndexed = true, version = 3, vector = "0.1,0.2")

        assertFalse(SemanticIndexingPolicy.needsReindex(currentItem, provider))
    }

    @Test
    fun needsReindex_requiresReplacementForEmptyOrUnindexedEmbedding() = runBlocking {
        assertTrue(SemanticIndexingPolicy.needsReindex(document(true, 3, ""), provider))
        assertTrue(SemanticIndexingPolicy.needsReindex(document(false, 3, "0.1,0.2"), provider))
    }

    private fun document(semanticIndexed: Boolean, version: Int, vector: String) = FileItemEntity(
        id = 7L,
        name = "बिजली का बिल.pdf",
        path = "content://documents/electricity-bill",
        category = FileCategory.DOCUMENTS.name,
        sizeBytes = 10L,
        semanticIndexed = semanticIndexed,
        semanticEmbeddingVersion = version,
        semanticEmbeddingString = vector
    )
}
