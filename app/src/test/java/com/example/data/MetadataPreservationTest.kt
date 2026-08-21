package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataPreservationTest {

    @Test
    fun `test upsert preserves existing enriched metadata and state`() {
        val merged = mergePreservingMetadata(existingEnrichedFile(), freshScannedFile())

        assertEquals(42L, merged.id)
        assertEquals("/storage/emulated/0/Vault/document.pdf", merged.originalPath)
        assertEquals("a1b2c3d4e5", merged.md5Hash)
        assertEquals("Confidential Financial Report 2026", merged.ocrText)
        assertEquals("finance,work,2026", merged.tags)
        assertTrue(merged.isVault)
        assertEquals("phash123456", merged.visualSimilarityHash)
        assertEquals("candidate123456", merged.documentCandidateFingerprint)
        assertEquals(1, merged.semanticEmbeddingVersion)
        assertTrue(merged.semanticIndexed)
        assertEquals("0.1,0.2,0.3,0.4", merged.semanticEmbeddingString)
        assertEquals(1000050L, merged.dateModifiedMs)
    }

    private fun existingEnrichedFile() = FileItemEntity(
            id = 42L,
            name = "document.pdf",
            path = "/storage/emulated/0/Documents/document.pdf",
            originalPath = "/storage/emulated/0/Vault/document.pdf",
            category = "DOCUMENTS",
            sizeBytes = 2048L,
            dateModifiedMs = 1000000L,
            md5Hash = "a1b2c3d4e5",
            ocrText = "Confidential Financial Report 2026",
            tags = "finance,work,2026",
            isVault = true,
            isRecycleBin = false,
            deletedTimestampMs = 0L,
            visualSimilarityHash = "phash123456",
            documentCandidateFingerprint = "candidate123456",
            semanticEmbeddingVersion = 1,
            semanticIndexed = true,
            semanticEmbeddingString = "0.1,0.2,0.3,0.4"
        )

    private fun freshScannedFile() = FileItemEntity(
            id = 0L,
            name = "document.pdf",
            path = "/storage/emulated/0/Documents/document.pdf",
            category = "DOCUMENTS",
            sizeBytes = 2048L,
            dateModifiedMs = 1000050L
        )

    private fun mergePreservingMetadata(existing: FileItemEntity, freshScanned: FileItemEntity) = existing.copy(
            name = freshScanned.name,
            category = freshScanned.category,
            sizeBytes = freshScanned.sizeBytes,
            dateModifiedMs = freshScanned.dateModifiedMs,
            md5Hash = if (freshScanned.md5Hash.isNotBlank()) freshScanned.md5Hash else existing.md5Hash,
            ocrText = if (freshScanned.ocrText.isNotBlank()) freshScanned.ocrText else existing.ocrText,
            tags = if (freshScanned.tags.isNotBlank()) freshScanned.tags else existing.tags,
            originalPath = if (freshScanned.originalPath.isNotBlank()) freshScanned.originalPath else existing.originalPath,
            visualSimilarityHash = if (freshScanned.visualSimilarityHash.isNotBlank()) freshScanned.visualSimilarityHash else existing.visualSimilarityHash,
            documentCandidateFingerprint = if (freshScanned.documentCandidateFingerprint.isNotBlank()) freshScanned.documentCandidateFingerprint else existing.documentCandidateFingerprint,
            semanticEmbeddingVersion = if (freshScanned.semanticEmbeddingVersion > 0) freshScanned.semanticEmbeddingVersion else existing.semanticEmbeddingVersion,
            semanticIndexed = freshScanned.semanticIndexed || existing.semanticIndexed,
            semanticEmbeddingString = if (freshScanned.semanticEmbeddingString.isNotBlank()) freshScanned.semanticEmbeddingString else existing.semanticEmbeddingString,
            isVault = existing.isVault,
            isRecycleBin = existing.isRecycleBin,
            deletedTimestampMs = existing.deletedTimestampMs
        )
}
