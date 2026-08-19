package com.example.data

import com.example.ai.SemanticEmbeddingProvider

/** Prevents incompatible vector spaces from being compared after model upgrades. */
object SemanticIndexingPolicy {
    fun needsReindex(item: FileItemEntity, provider: SemanticEmbeddingProvider): Boolean =
        !item.semanticIndexed ||
            item.semanticEmbeddingVersion != provider.embeddingVersion ||
            item.semanticEmbeddingString.isBlank()
}
