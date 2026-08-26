package com.example.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

class ContentIdentityInstrumentedTest {
    private lateinit var dao: FileDao
    private lateinit var fixture: FileItemEntity

    @Before
    fun setUp() {
        dao = AppDatabase.getDatabase(InstrumentationRegistry.getInstrumentation().targetContext).fileDao()
        fixture = FileItemEntity(
            name = "content-identity-${System.nanoTime()}.txt",
            path = "/data/content-identity-${System.nanoTime()}.txt",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 10L,
            dateModifiedMs = 100L,
            md5Hash = "old-hash",
            ocrText = "old-ocr",
            semanticIndexed = true,
            semanticEmbeddingString = "1,2,3",
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            dao.getFileByPath(fixture.path)?.let { dao.deleteFileById(it.id) }
        }
    }

    @Test
    fun changedSourceMetadata_advancesContentIdentityAndInvalidatesDerivedState() = runBlocking {
        dao.insertFile(fixture)
        val before = dao.getFileByPath(fixture.path)!!
        dao.insertFile(fixture.copy(sizeBytes = 20L, dateModifiedMs = 200L, md5Hash = "", ocrText = "", semanticIndexed = false, semanticEmbeddingString = ""))
        val after = dao.getFileByPath(fixture.path)!!

        assertEquals(before.contentIdentityVersion + 1L, after.contentIdentityVersion)
        assertEquals("", after.md5Hash)
        assertEquals("", after.ocrText)
        assertEquals(false, after.semanticIndexed)
        assertEquals("", after.semanticEmbeddingString)
    }
}