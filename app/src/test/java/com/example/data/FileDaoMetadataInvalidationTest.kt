package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileDaoMetadataInvalidationTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: FileDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.fileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun replacementFileInvalidatesContentDerivedMetadata() = runBlocking {
        val original = FileItemEntity(
            id = 1,
            name = "photo.jpg",
            path = "/storage/photo.jpg",
            category = FileCategory.IMAGES.name,
            sizeBytes = 100,
            dateModifiedMs = 1_000,
            md5Hash = "old-hash",
            ocrText = "old OCR",
            visualSimilarityHash = "old-visual",
            semanticEmbeddingVersion = 3,
            semanticIndexed = true,
            semanticEmbeddingString = "1.0,2.0",
            videoFingerprintVersion = 2,
            videoSampleHashes = "old-video",
            videoDurationMs = 1000,
            videoWidth = 100,
            videoHeight = 100,
            videoAudioSignature = "old-audio",
            videoChunkHash = "old-chunk",
            documentCandidateFingerprint = "old-document"
        )
        dao.insertFileDirect(original)

        dao.upsertFilesPreservingMetadata(
            listOf(
                original.copy(
                    id = 0,
                    sizeBytes = 200,
                    dateModifiedMs = 2_000,
                    md5Hash = "",
                    ocrText = "",
                    visualSimilarityHash = "",
                    semanticEmbeddingVersion = 0,
                    semanticIndexed = false,
                    semanticEmbeddingString = "",
                    videoFingerprintVersion = 0,
                    videoSampleHashes = "",
                    videoDurationMs = 0,
                    videoWidth = 0,
                    videoHeight = 0,
                    videoAudioSignature = "",
                    videoChunkHash = "",
                    documentCandidateFingerprint = ""
                )
            )
        )

        val updated = dao.getFileByPath(original.path)!!
        assertEquals(200, updated.sizeBytes)
        assertEquals(2_000, updated.dateModifiedMs)
        assertEquals("", updated.md5Hash)
        assertEquals("", updated.ocrText)
        assertEquals("", updated.visualSimilarityHash)
        assertEquals(0, updated.semanticEmbeddingVersion)
        assertFalse(updated.semanticIndexed)
        assertEquals("", updated.semanticEmbeddingString)
        assertEquals(0, updated.videoFingerprintVersion)
        assertEquals("", updated.videoSampleHashes)
        assertEquals(0L, updated.videoDurationMs)
        assertEquals(0, updated.videoWidth)
        assertEquals(0, updated.videoHeight)
        assertEquals("", updated.videoAudioSignature)
        assertEquals("", updated.videoChunkHash)
        assertEquals("", updated.documentCandidateFingerprint)
    }

    @Test
    fun unchangedFilePreservesDerivedMetadata() = runBlocking {
        val original = FileItemEntity(
            name = "document.pdf",
            path = "/storage/document.pdf",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 100,
            dateModifiedMs = 1_000,
            md5Hash = "hash",
            ocrText = "OCR",
            semanticEmbeddingVersion = 4,
            semanticIndexed = true,
            semanticEmbeddingString = "1.0,2.0",
            documentCandidateFingerprint = "candidate"
        )
        dao.insertFileDirect(original)

        dao.upsertFilesPreservingMetadata(
            listOf(original.copy(id = 0, md5Hash = "", ocrText = "", semanticIndexed = false, semanticEmbeddingVersion = 0, semanticEmbeddingString = "", documentCandidateFingerprint = ""))
        )

        val updated = dao.getFileByPath(original.path)!!
        assertEquals("hash", updated.md5Hash)
        assertEquals("OCR", updated.ocrText)
        assertEquals(4, updated.semanticEmbeddingVersion)
        assertTrue(updated.semanticIndexed)
        assertEquals("1.0,2.0", updated.semanticEmbeddingString)
        assertEquals("candidate", updated.documentCandidateFingerprint)
    }
}
