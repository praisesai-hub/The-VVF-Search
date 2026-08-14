package com.example.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SmartManagerRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var fakeDao: TestFileDao
    private lateinit var fakeOcr: TestOcrEngine
    private lateinit var repository: SmartManagerRepository

    private class TestFileDao : FileDao {
        val unhashedFiles = mutableListOf<FileItemEntity>()
        val plugins = mutableListOf<PluginEntity>()
        val updatedFiles = mutableListOf<FileItemEntity>()
        val activeFiles = mutableListOf<FileItemEntity>()
        val duplicateFiles = mutableListOf<FileItemEntity>()
        var onUpdate: (() -> Unit)? = null

        override suspend fun getUnhashedFiles(): List<FileItemEntity> = unhashedFiles.toList()
        override fun getAllPlugins(): Flow<List<PluginEntity>> = flowOf(plugins.toList())
        override suspend fun updateFiles(files: List<FileItemEntity>) {
            updatedFiles += files
            onUpdate?.invoke()
        }

        override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? = null
        override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>) = updateFiles(files)
        override suspend fun getFileById(id: Long): FileItemEntity? =
            (activeFiles + unhashedFiles).firstOrNull { it.id == id }
        override suspend fun getFileByName(name: String): FileItemEntity? =
            (activeFiles + unhashedFiles).firstOrNull { it.name == name }
        override fun getOcrScannedFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getAllActiveFiles(): Flow<List<FileItemEntity>> = flowOf(activeFiles.toList())
        override fun getRecentFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getCategoryStats(): Flow<List<CategoryStat>> = flowOf(emptyList())
        override suspend fun getFilteredFilesPaged(category: String?, query: String, limit: Int, offset: Int): List<FileItemEntity> = emptyList()
        override fun getFilesByCategory(category: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getRecycleBinFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getVaultFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getDuplicateFilesByHash(): Flow<List<FileItemEntity>> = flowOf(duplicateFiles.toList())
        override suspend fun insertFile(file: FileItemEntity): Long = file.id
        override suspend fun insertFiles(files: List<FileItemEntity>) = Unit
        override suspend fun updateFile(file: FileItemEntity) = Unit
        override suspend fun getFileByPath(path: String): FileItemEntity? = null
        override suspend fun insertFileDirect(file: FileItemEntity): Long = file.id
        override suspend fun getAllOrdinaryFilesDirect(): List<FileItemEntity> = emptyList()
        override suspend fun deleteFilesByIds(ids: List<Long>) = Unit
        override suspend fun deleteFileById(id: Long) = Unit
        override suspend fun emptyRecycleBin() = Unit
        override suspend fun getVaultFileByName(name: String): FileItemEntity? = null
        override fun getAllVaultItems(): Flow<List<VaultItemEntity>> = flowOf(emptyList())
        override suspend fun insertVaultItem(item: VaultItemEntity): Long = item.id
        override suspend fun deleteVaultItemById(id: Long) = Unit
        override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = flowOf(emptyList())
        override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long = item.id
        override suspend fun deleteCloudSyncItem(id: Long) = Unit
        override suspend fun setPluginEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun insertPlugins(plugins: List<PluginEntity>) = Unit
    }

    private class TestOcrEngine : OcrEngine {
        var text: String = ""

        override suspend fun extractRealOcrText(filePath: String): String = text

        override suspend fun extractOcrBlocks(filePath: String): List<OcrTextBlock> = emptyList()
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        fakeDao = TestFileDao()
        fakeOcr = TestOcrEngine()
        repository = SmartManagerRepository(context, fakeDao, fakeOcr)
    }

    @Test
    fun documentStats_countsOnlyIndexedEligibleDocuments(): Unit {
        runBlocking {
            fakeDao.activeFiles += listOf(
                document(1L, "indexed.pdf", md5Hash = "hash"),
                document(2L, "pending.pdf"),
                document(3L, "vault.pdf", md5Hash = "vault", isVault = true),
                document(4L, "recycled.pdf", md5Hash = "recycled", isRecycleBin = true),
                image(5L, "photo.jpg")
            )

            val stats = repository.documentStats.first()

            assertEquals(1, stats.first)
            assertEquals(1, stats.second)
            assertEquals(0.5f, stats.third)
        }
    }

    @Test
    fun exactDuplicates_groupsRepeatedNonBlankHashesOnly(): Unit {
        runBlocking {
            val first = document(10L, "one.txt", md5Hash = "same")
            val second = first.copy(id = 11L, name = "two.txt")
            fakeDao.duplicateFiles += listOf(
                first,
                second,
                document(12L, "blank-one.txt"),
                document(13L, "blank-two.txt")
            )

            val groups = repository.exactDuplicates.first()

            assertEquals(1, groups.size)
            assertEquals(100, groups.single().similarityScore)
            assertEquals(listOf(10L, 11L), groups.single().files.map { it.id })
            assertTrue(groups.single().title.contains("SHA-256 Hash Match"))
        }
    }

    @Test
    fun withRetry_returnsAfterTransientFailures(): Unit {
        runBlocking {
            var attempts = 0
            val result = repository.withRetry(maxAttempts = 3, initialDelayMs = 0, factor = 2.0) {
                attempts++
                check(attempts >= 3) { "transient" }
                "persisted"
            }

            assertEquals("persisted", result)
            assertEquals(3, attempts)
        }
    }

    @Test
    fun withRetry_rethrowsFinalFailureAfterAttemptBudget(): Unit {
        runBlocking {
            var attempts = 0
            try {
                repository.withRetry(maxAttempts = 2, initialDelayMs = 0) {
                    attempts++
                    error("permanent")
                }
                throw AssertionError("withRetry should rethrow the final failure")
            } catch (exception: IllegalStateException) {
                assertEquals("permanent", exception.message)
                assertEquals(2, attempts)
            }
        }
    }

    @Test
    fun incrementalScan_persistsRealHashesOcrAndSemanticIndex(): Unit {
        val file = File.createTempFile("vvf_scan_", ".txt")
        try {
            file.writeText("on-device production scan fixture")
            fakeOcr.text = "AUTHENTIC OCR CONTENT"
            val pending = FileItemEntity(
                id = 20L,
                name = "fixture.txt",
                path = file.absolutePath,
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = file.length(),
                ocrText = ""
            )
            fakeDao.unhashedFiles += pending
            fakeDao.plugins += PluginEntity(
                "ocr_engine",
                "Test OCR",
                "OCR",
                "Extract text",
                isEnabled = true,
                isCore = true
            )
            val updated = CountDownLatch(1)
            fakeDao.onUpdate = { updated.countDown() }

            repository.startIncrementalDuplicateScan()

            assertTrue(updated.await(5, TimeUnit.SECONDS))
            val result = fakeDao.updatedFiles.single { it.id == 20L }
            assertEquals("AUTHENTIC OCR CONTENT", result.ocrText)
            assertTrue(result.md5Hash.isNotBlank())
            assertTrue(result.visualSimilarityHash.isNotBlank())
            assertTrue(result.semanticIndexed)
            assertTrue(result.semanticEmbeddingString.isNotBlank())
            assertEquals(1.0f, repository.scanProgress.value)
            assertFalse(repository.isScanning.value)
        } finally {
            file.delete()
        }
    }

    @Test
    fun repository_lookup_delegatesToDao(): Unit {
        runBlocking {
            val expected = image(30L, "lookup.jpg")
            fakeDao.activeFiles += expected

            assertEquals(expected, repository.getFileById(30L))
            assertEquals(expected, repository.getFileByName("lookup.jpg"))
            assertNotNull(repository.activeFiles.first())
        }
    }

    private fun document(
        id: Long,
        name: String,
        md5Hash: String = "",
        isVault: Boolean = false,
        isRecycleBin: Boolean = false
    ): FileItemEntity {
        return FileItemEntity(
            id = id,
            name = name,
            path = "/docs/$name",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 1L,
            md5Hash = md5Hash,
            isVault = isVault,
            isRecycleBin = isRecycleBin
        )
    }

    private fun image(id: Long, name: String): FileItemEntity {
        return FileItemEntity(
            id = id,
            name = name,
            path = "/images/$name",
            category = FileCategory.IMAGES.name,
            sizeBytes = 1L
        )
    }
}
