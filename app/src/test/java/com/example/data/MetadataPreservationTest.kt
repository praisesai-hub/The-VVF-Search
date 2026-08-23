package com.example.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MetadataPreservationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun changedContent_invalidatesDerivedMetadataButPreservesUserMetadata() = runBlocking {
        val dao = database.fileDao()
        val existing = FileItemEntity(
            id = 42L,
            name = "document.pdf",
            path = "/storage/emulated/0/Documents/document.pdf",
            category = "DOCUMENTS",
            sizeBytes = 2048L,
            dateModifiedMs = 1_000_000L,
            md5Hash = "old-sha256",
            ocrText = "old OCR",
            tags = "finance,work",
            visualSimilarityHash = "old-dhash",
            semanticEmbeddingVersion = 3,
            semanticIndexed = true,
            semanticEmbeddingString = "0.1,0.2",
            videoFingerprintVersion = 2,
            videoSampleHashes = "old-sample",
            videoDurationMs = 100L,
            videoWidth = 640,
            videoHeight = 480,
            videoAudioSignature = "old-audio",
            videoChunkHash = "old-chunk",
            documentCandidateFingerprint = "old-document-candidate",
        )
        dao.insertFileDirect(existing)

        dao.upsertFilesPreservingMetadata(
            listOf(
                existing.copy(
                    id = 0L,
                    sizeBytes = 4096L,
                    dateModifiedMs = 1_000_100L,
                    md5Hash = "",
                    ocrText = "",
                    tags = "",
                    visualSimilarityHash = "",
                    semanticEmbeddingVersion = 0,
                    semanticIndexed = false,
                    semanticEmbeddingString = "",
                    videoFingerprintVersion = 0,
                    videoSampleHashes = "",
                    videoDurationMs = 0L,
                    videoWidth = 0,
                    videoHeight = 0,
                    videoAudioSignature = "",
                    videoChunkHash = "",
                    documentCandidateFingerprint = "",
                ),
            ),
        )

        val updated = dao.getFileByPath(existing.path)
        assertEquals(4096L, updated?.sizeBytes)
        assertEquals("", updated?.md5Hash)
        assertEquals("", updated?.ocrText)
        assertEquals("finance,work", updated?.tags)
        assertEquals("", updated?.visualSimilarityHash)
        assertEquals(0, updated?.semanticEmbeddingVersion)
        assertFalse(updated?.semanticIndexed == true)
        assertEquals("", updated?.semanticEmbeddingString)
        assertEquals(0, updated?.videoFingerprintVersion)
        assertEquals("", updated?.videoSampleHashes)
        assertEquals(0L, updated?.videoDurationMs)
        assertEquals(0, updated?.videoWidth)
        assertEquals(0, updated?.videoHeight)
        assertEquals("", updated?.videoAudioSignature)
        assertEquals("", updated?.videoChunkHash)
        assertEquals("", updated?.documentCandidateFingerprint)
    }

    @Test
    fun unchangedContent_preservesDerivedMetadata() = runBlocking {
        val dao = database.fileDao()
        val existing = FileItemEntity(
            id = 43L,
            name = "document.pdf",
            path = "/storage/emulated/0/Documents/unchanged.pdf",
            category = "DOCUMENTS",
            sizeBytes = 2048L,
            dateModifiedMs = 1_000_000L,
            md5Hash = "sha256",
            ocrText = "OCR",
            tags = "finance",
            visualSimilarityHash = "dhash",
            semanticEmbeddingVersion = 2,
            semanticIndexed = true,
            semanticEmbeddingString = "0.3,0.4",
            documentCandidateFingerprint = "candidate",
        )
        dao.insertFileDirect(existing)

        dao.upsertFilesPreservingMetadata(
            listOf(existing.copy(id = 0L, md5Hash = "", ocrText = "", tags = "")),
        )

        val updated = dao.getFileByPath(existing.path)
        assertEquals("sha256", updated?.md5Hash)
        assertEquals("OCR", updated?.ocrText)
        assertEquals("finance", updated?.tags)
        assertEquals("dhash", updated?.visualSimilarityHash)
        assertEquals(2, updated?.semanticEmbeddingVersion)
        assertTrue(updated?.semanticIndexed == true)
        assertEquals("0.3,0.4", updated?.semanticEmbeddingString)
        assertEquals("candidate", updated?.documentCandidateFingerprint)
    }
}
