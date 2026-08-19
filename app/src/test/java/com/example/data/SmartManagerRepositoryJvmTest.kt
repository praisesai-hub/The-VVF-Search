package com.example.data

import android.content.Context
import com.example.ai.SemanticEmbeddingProvider
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartManagerRepositoryJvmTest {
    private lateinit var context: Context
    private lateinit var dao: FileDao

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dao = mockk(relaxed = true)
    }

    @Test
    fun searchFiles_blankQueryFiltersTheAuthoritativeActiveFileFlow() = runBlocking {
        every { dao.getAllActiveFiles() } returns flowOf(
            listOf(
                file(id = 1L, category = FileCategory.DOCUMENTS.name),
                file(id = 2L, category = FileCategory.IMAGES.name)
            )
        )
        val repository = repository()

        val results = repository.searchFiles(query = "", category = FileCategory.DOCUMENTS.name).first()

        assertEquals(listOf(1L), results.map { it.id })
    }

    @Test
    fun searchFiles_usesProvidedFtsIndexForCompiledNonBlankQuery() = runBlocking {
        val index = mockk<SearchIndexDao>()
        every { index.observeFilesByFts(any(), FileCategory.DOCUMENTS.name, 250) } returns
            flowOf(listOf(file(id = 3L, category = FileCategory.DOCUMENTS.name)))
        val repository = repository(searchIndexDao = index)

        val results = repository.searchFiles(query = "बिजली बिल", category = FileCategory.DOCUMENTS.name).first()

        assertEquals(listOf(3L), results.map { it.id })
        verify(exactly = 1) {
            index.observeFilesByFts(any(), FileCategory.DOCUMENTS.name, 250)
        }
    }

    @Test
    fun semanticSearch_returnsNoResultsWhenTheProviderCannotEmbedTheQuery() = runBlocking {
        val provider = NullQueryEmbeddingProvider()
        val repository = repository(semanticEmbeddingProvider = provider)

        val results = repository.searchSemanticFiles("बिजली का बिल").first()

        assertEquals(emptyList<FileItemEntity>(), results)
    }

    @Test
    fun documentStats_excludesVaultAndRecycleRowsAndReportsIndexedRatio() = runBlocking {
        every { dao.getAllActiveFiles() } returns flowOf(
            listOf(
                file(id = 4L, category = FileCategory.DOCUMENTS.name, hash = "sha256"),
                file(id = 5L, category = FileCategory.DOCUMENTS.name),
                file(id = 6L, category = FileCategory.DOCUMENTS.name, vault = true),
                file(id = 7L, category = FileCategory.DOCUMENTS.name, recycle = true),
                file(id = 8L, category = FileCategory.IMAGES.name)
            )
        )
        val repository = repository()

        val stats = repository.documentStats.first()

        assertEquals(1, stats.first)
        assertEquals(1, stats.second)
        assertEquals(0.5f, stats.third)
    }

    @Test
    fun enqueueCloudSyncItem_deniesTransfersBeforeAnyProviderOrQueueAccess() = runBlocking {
        val repository = repository(cloudTransferAllowed = { false })
        clearMocks(dao, answers = false)

        val queued = repository.enqueueCloudSyncItem(
            provider = "GOOGLE_DRIVE",
            fileName = "private.pdf",
            size = 1024L,
            filePath = "content://documents/private.pdf"
        )

        assertFalse(queued)
        verify(exactly = 0) { dao.getCloudSyncItems() }
        coVerify(exactly = 0) { dao.insertCloudSyncItem(any()) }
    }

    private fun repository(
        semanticEmbeddingProvider: SemanticEmbeddingProvider? = null,
        searchIndexDao: SearchIndexDao? = null,
        cloudTransferAllowed: (Context) -> Boolean = { false }
    ) = SmartManagerRepository(
        context = context,
        dao = dao,
        semanticEmbeddingProvider = semanticEmbeddingProvider,
        searchIndexDao = searchIndexDao,
        cloudTransferAllowed = cloudTransferAllowed
    )

    private fun file(
        id: Long,
        category: String,
        hash: String = "",
        vault: Boolean = false,
        recycle: Boolean = false
    ) = FileItemEntity(
        id = id,
        name = "file-$id",
        path = File(context.cacheDir, "file-$id").absolutePath,
        category = category,
        sizeBytes = 1L,
        md5Hash = hash,
        isVault = vault,
        isRecycleBin = recycle
    )

    private class NullQueryEmbeddingProvider : SemanticEmbeddingProvider {
        override val embeddingVersion: Int = 3
        override fun isModelLoaded(): Boolean = true
        override suspend fun generateImageEmbedding(file: File): FloatArray? = null
        override suspend fun generateTextEmbedding(text: String): FloatArray? = null
        override suspend fun generateQueryEmbedding(query: String): FloatArray? = null
    }
}
