package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.example.domain.retry.RetryOperation
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
        val recycleBinFiles = mutableListOf<FileItemEntity>()
        val cloudSyncItems = mutableListOf<CloudSyncItemEntity>()
        val deletedFileIds = mutableListOf<Long>()
        val deletedCloudSyncIds = mutableListOf<Long>()
        val updatedSingleFiles = mutableListOf<FileItemEntity>()
        var onUpdate: (() -> Unit)? = null

        override suspend fun getUnhashedFiles(): List<FileItemEntity> = unhashedFiles.toList()
        override fun getAllPlugins(): Flow<List<PluginEntity>> = flow { emit(plugins.toList()) }
        override suspend fun updateFiles(files: List<FileItemEntity>) {
            updatedFiles += files
            onUpdate?.invoke()
        }

        override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? = null
        override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>) = updateFiles(files)
        override suspend fun getFileById(id: Long): FileItemEntity? =
            (activeFiles + unhashedFiles + recycleBinFiles).firstOrNull { it.id == id }
        override suspend fun getFileByName(name: String): FileItemEntity? =
            (activeFiles + unhashedFiles).firstOrNull { it.name == name }
        override fun getOcrScannedFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getAllActiveFiles(): Flow<List<FileItemEntity>> = flow { emit(activeFiles.toList()) }
        override fun getRecentFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getCategoryStats(): Flow<List<CategoryStat>> = flowOf(emptyList())
        override suspend fun getFilteredFilesPaged(category: String?, query: String, limit: Int, offset: Int): List<FileItemEntity> = emptyList()
        override fun getFilesByCategory(category: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getRecycleBinFiles(): Flow<List<FileItemEntity>> = flow { emit(recycleBinFiles.toList()) }
        override fun getVaultFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getDuplicateFilesByHash(): Flow<List<FileItemEntity>> = flow { emit(duplicateFiles.toList()) }
        override suspend fun insertFile(file: FileItemEntity): Long = file.id
        override suspend fun insertFiles(files: List<FileItemEntity>) = Unit
        override suspend fun updateFile(file: FileItemEntity) {
            updatedSingleFiles += file
        }
        override suspend fun getFileByPath(path: String): FileItemEntity? = null
        override suspend fun insertFileDirect(file: FileItemEntity): Long = file.id
        override suspend fun getAllOrdinaryFilesDirect(): List<FileItemEntity> = emptyList()
        override suspend fun deleteFilesByIds(ids: List<Long>) = Unit
        override suspend fun deleteFileById(id: Long) {
            deletedFileIds += id
        }
        override suspend fun emptyRecycleBin() {
            recycleBinFiles.clear()
        }
        override suspend fun getVaultFileByName(name: String): FileItemEntity? = null
        override fun getAllVaultItems(): Flow<List<VaultItemEntity>> = flowOf(emptyList())
        override suspend fun insertVaultItem(item: VaultItemEntity): Long = item.id
        override suspend fun deleteVaultItemById(id: Long) = Unit
        override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = flow { emit(cloudSyncItems.toList()) }
        override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long {
            cloudSyncItems.removeAll { it.id == item.id }
            cloudSyncItems += item
            return item.id
        }
        override suspend fun deleteCloudSyncItem(id: Long) {
            deletedCloudSyncIds += id
            cloudSyncItems.removeAll { it.id == id }
        }
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
            val result = repository.withRetry(RetryOperation.DATABASE_WRITE, maxAttempts = 3, initialDelayMs = 0, factor = 2.0) {
                attempts++
                if (attempts < 3) throw SQLiteDatabaseLockedException("database locked")
                "persisted"
            }

            assertEquals("persisted", result)
            assertEquals(3, attempts)
        }
    }

    @Test
    fun withRetry_doesNotRetryPermanentFailure(): Unit {
        runBlocking {
            var attempts = 0
            try {
                repository.withRetry(RetryOperation.DATABASE_WRITE, maxAttempts = 2, initialDelayMs = 0) {
                    attempts++
                    throw IllegalStateException("permanent")
                }
                throw AssertionError("withRetry should rethrow the final failure")
            } catch (exception: IllegalStateException) {
                assertEquals("permanent", exception.message)
                assertEquals(1, attempts)

            }
        }
    }

    @Test
    fun incrementalScan_persistsRealHashesOcrAndIndexesWithOnDeviceFallback(): Unit {
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
            assertTrue(result.documentCandidateFingerprint.isNotBlank())
            assertTrue(result.semanticIndexed)
            assertTrue(result.semanticEmbeddingString.isNotBlank())
            assertTrue(repository.isSemanticSearchAvailable)
            runBlocking {
                withTimeout(5_000L) {
                    while (repository.isScanning.value) delay(10L)
                }
            }
            assertEquals(1.0f, repository.scanProgress.value)
            assertFalse(repository.isScanning.value)
        } finally {
            file.delete()
        }
    }

    @Test
    fun recycleBinOperations_ignoreMissingAndAlreadyTerminalRows(): Unit = runBlocking {
        val missing = document(301L, "missing.pdf")
        repository.moveToRecycleBin(missing)
        repository.restoreFromRecycleBin(missing)
        repository.deletePermanently(missing)
        assertTrue(fakeDao.updatedSingleFiles.isEmpty())
        assertTrue(fakeDao.deletedFileIds.isEmpty())

        val alreadyRecycled = document(302L, "already-recycled.pdf", isRecycleBin = true)
        fakeDao.activeFiles += alreadyRecycled
        repository.moveToRecycleBin(alreadyRecycled)
        assertTrue(fakeDao.updatedSingleFiles.isEmpty())

        val ordinary = document(303L, "ordinary.pdf")
        fakeDao.activeFiles += ordinary
        repository.restoreFromRecycleBin(ordinary)
        assertTrue(fakeDao.updatedSingleFiles.isEmpty())
    }

    @Test
    fun workSchedulingAndMemoryTrim_useRealWorkManagerIntegration(): Unit {
        repository.trimMemory()
        repository.enqueueDuplicateCleanupWork()
        repository.enqueueCloudSyncWork()
        repository.enqueueCacheCleanupWork()
        repository.enqueueBackgroundIndexWork()
    }

    @Test
    fun recycleBinOperations_preservePhysicalDataAndDaoIntegrity(): Unit = runBlocking {
        val source = File.createTempFile("vvf_repo_move_", ".txt", context.cacheDir)
        source.writeText("repository move payload")
        try {
            val ordinary = document(30L, source.name).copy(path = source.absolutePath, sizeBytes = source.length())
            fakeDao.activeFiles += ordinary

            repository.moveToRecycleBin(ordinary)

            assertFalse(source.exists())
            val recycled = fakeDao.updatedSingleFiles.single()
            assertTrue(recycled.isRecycleBin)
            assertEquals(source.absolutePath, recycled.originalPath)
            assertTrue(File(recycled.path).exists())

            fakeDao.updatedSingleFiles.clear()
            fakeDao.activeFiles.clear()
            fakeDao.activeFiles += recycled
            repository.restoreFromRecycleBin(recycled)

            assertTrue(source.exists())
            val restored = fakeDao.updatedSingleFiles.single()
            assertFalse(restored.isRecycleBin)
            assertEquals(source.absolutePath, restored.path)
            assertTrue(restored.originalPath.isBlank())
        } finally {
            source.delete()
            fakeDao.updatedSingleFiles.map { it.path }.distinct().forEach { File(it).delete() }
        }
    }

    @Test
    fun deletePermanently_removesPhysicalFileAndDaoRecord(): Unit = runBlocking {
        val source = File.createTempFile("vvf_repo_delete_", ".txt", context.cacheDir)
        try {
            val ordinary = document(31L, source.name).copy(path = source.absolutePath, sizeBytes = source.length())
            fakeDao.activeFiles += ordinary

            repository.deletePermanently(ordinary)

            assertFalse(source.exists())
            assertEquals(listOf(31L), fakeDao.deletedFileIds)
        } finally {
            source.delete()
        }
    }

    @Test
    fun emptyRecycleBin_deletesPhysicalTrashBeforeDaoRows(): Unit = runBlocking {
        val trash = File.createTempFile("vvf_repo_trash_", ".txt", context.cacheDir)
        try {
            trash.writeText("trash payload")
            fakeDao.recycleBinFiles += document(32L, trash.name, isRecycleBin = true).copy(
                path = trash.absolutePath,
                sizeBytes = trash.length()
            )

            repository.emptyRecycleBin()

            assertFalse(trash.exists())
            assertTrue(fakeDao.recycleBinFiles.isEmpty())
        } finally {
            trash.delete()
        }
    }

    @Test
    fun cloudSyncGuards_rejectDisabledDuplicateMissingAndTerminalItems(): Unit = runBlocking {
        val pending = CloudSyncItemEntity(
            id = 40L,
            provider = "GOOGLE_DRIVE",
            fileName = "report.pdf",
            filePath = "/docs/report.pdf",
            fileSize = 10L,
            status = "PENDING",
            lastSyncedMs = 0L,
            isCore = false
        )
        fakeDao.cloudSyncItems += pending
        fakeDao.plugins += PluginEntity("gdrive_sync", "Google Drive", "CLOUD", "Sync", true, false)

        assertFalse(repository.enqueueCloudSyncItem("GOOGLE_DRIVE", "report.pdf", 10L, "/docs/report.pdf"))
        assertFalse(repository.retryCloudSyncItem(999L))
        assertFalse(repository.cancelCloudSyncItem(999L))

        fakeDao.plugins.clear()
        fakeDao.plugins += PluginEntity("gdrive_sync", "Google Drive", "CLOUD", "Sync", false, false)
        assertFalse(repository.enqueueCloudSyncItem("GOOGLE_DRIVE", "new.pdf", 5L, "/docs/new.pdf"))
        assertFalse(repository.retryCloudSyncItem(40L))

        fakeDao.plugins.clear()
        fakeDao.plugins += PluginEntity("gdrive_sync", "Google Drive", "CLOUD", "Sync", true, false)
        assertFalse(repository.retryCloudSyncItem(40L))
        fakeDao.cloudSyncItems += pending.copy(id = 41L, status = "SYNCED")
        assertFalse(repository.retryCloudSyncItem(41L))
        assertFalse(repository.cancelCloudSyncItem(41L))
        assertTrue(fakeDao.deletedCloudSyncIds.isEmpty())
    }

    @Test
    fun cloudSyncSuccesses_coverProviderMappingEnqueueRetryAndCancelWhenExplicitlyAuthorized(): Unit = runBlocking {
        val consentedRepository = SmartManagerRepository(
            context = context,
            dao = fakeDao,
            ocrEngine = fakeOcr,
            cloudTransferAllowed = { true },
        )
        fakeDao.plugins += PluginEntity("onedrive_sync", "OneDrive", "CLOUD", "Sync", true, false)
        fakeDao.plugins += PluginEntity("dropbox_sync", "Dropbox", "CLOUD", "Sync", true, false)

        assertTrue(consentedRepository.enqueueCloudSyncItem("ONEDRIVE", "one.txt", 12L))
        assertTrue(consentedRepository.enqueueCloudSyncItem("DROPBOX", "two.txt", 24L))
        assertFalse(consentedRepository.enqueueCloudSyncItem("UNKNOWN_PROVIDER", "three.txt", 36L))

        val queued = fakeDao.cloudSyncItems.single()
        assertEquals("DROPBOX", queued.provider)
        assertEquals("QUEUED", queued.status)
        assertTrue(consentedRepository.retryCloudSyncItem(queued.id))
        assertEquals("QUEUED", fakeDao.cloudSyncItems.single().status)
        assertTrue(consentedRepository.cancelCloudSyncItem(queued.id))
        assertEquals(listOf(queued.id), fakeDao.deletedCloudSyncIds)
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
