package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.example.domain.retry.RetryOperation
import android.database.sqlite.SQLiteDatabaseLockedException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrEngineTest {

    private lateinit var context: Context
    private lateinit var mlKitOcrEngine: MLKitOcrEngine
    private lateinit var fakeDao: FakeFileDao
    private lateinit var fakeOcrEngine: FakeOcrEngine
    private lateinit var repository: SmartManagerRepository

    class FakeFileDao : FileDao {
        var unhashedFiles = mutableListOf<FileItemEntity>()
        var plugins = mutableListOf<PluginEntity>()
        val updatedFiles = mutableListOf<FileItemEntity>()
        var onUpdateCallback: (() -> Unit)? = null
        val activeFiles = mutableListOf<FileItemEntity>()
        val duplicateFiles = mutableListOf<FileItemEntity>()
        val cloudItems = mutableListOf<CloudSyncItemEntity>()
        val insertedCloudItems = mutableListOf<CloudSyncItemEntity>()
        val deletedCloudItemIds = mutableListOf<Long>()

        override suspend fun getUnhashedFiles(): List<FileItemEntity> {
            return unhashedFiles
        }

        override fun getAllPlugins(): Flow<List<PluginEntity>> {
            return flowOf(plugins)
        }

        override suspend fun updateFiles(files: List<FileItemEntity>) {
            updatedFiles.addAll(files)
            onUpdateCallback?.invoke()
        }

        override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? = null

        override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>) {
            updateFiles(files)
        }

        override suspend fun getFileById(id: Long): FileItemEntity? =
            activeFiles.firstOrNull { it.id == id } ?: unhashedFiles.firstOrNull { it.id == id }
        override suspend fun getFileByName(name: String): FileItemEntity? = null
        override fun getOcrScannedFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getAllActiveFiles(): Flow<List<FileItemEntity>> = flowOf(activeFiles)
        override fun getRecentFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getCategoryStats(): Flow<List<CategoryStat>> = flowOf(emptyList())
        override suspend fun getFilteredFilesPaged(category: String?, query: String, limit: Int, offset: Int): List<FileItemEntity> = emptyList()
        override fun getFilesByCategory(category: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getRecycleBinFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getVaultFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getDuplicateFilesByHash(): Flow<List<FileItemEntity>> = flowOf(duplicateFiles)
        override suspend fun insertFile(file: FileItemEntity): Long = 0L
        override suspend fun insertFiles(files: List<FileItemEntity>) {}
        override suspend fun updateFile(file: FileItemEntity) {}
        override suspend fun getFileByPath(path: String): FileItemEntity? = null
        override suspend fun insertFileDirect(file: FileItemEntity): Long = 0L
        override suspend fun getAllOrdinaryFilesDirect(): List<FileItemEntity> = emptyList()
        override suspend fun deleteFilesByIds(ids: List<Long>) {}
        override suspend fun deleteFileById(id: Long) {}
        override suspend fun emptyRecycleBin() {}
        override suspend fun getVaultFileByName(name: String): FileItemEntity? = null
        override fun getAllVaultItems(): Flow<List<VaultItemEntity>> = flowOf(emptyList())
        override suspend fun insertVaultItem(item: VaultItemEntity): Long = 0L
        override suspend fun deleteVaultItemById(id: Long) {}
        override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = flowOf(cloudItems)
        override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long {
            insertedCloudItems.add(item)
            cloudItems.add(item)
            return item.id
        }
        override suspend fun deleteCloudSyncItem(id: Long) {
            deletedCloudItemIds.add(id)
            cloudItems.removeAll { it.id == id }
        }
        override suspend fun setPluginEnabled(id: String, enabled: Boolean) {}
        override suspend fun insertPlugins(plugins: List<PluginEntity>) {}
    }

    class FakeOcrEngine : OcrEngine {
        var resultText = ""
        var shouldThrowException = false
        var lastCapturedPath: String? = null

        override suspend fun extractRealOcrText(filePath: String): String {
            if (shouldThrowException) {
                throw RuntimeException("ML Kit not initialized / GMS Core error")
            }
            lastCapturedPath = filePath
            return resultText
        }

        override suspend fun extractOcrBlocks(filePath: String): List<com.example.data.OcrTextBlock> {
            if (shouldThrowException) {
                throw RuntimeException("ML Kit not initialized / GMS Core error")
            }
            lastCapturedPath = filePath
            return if (resultText.isNotEmpty()) {
                listOf(
                    com.example.data.OcrTextBlock(
                        text = resultText,
                        boundingBox = android.graphics.Rect(10, 10, 100, 50),
                        imageWidth = 500,
                        imageHeight = 500
                    )
                )
            } else emptyList()
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        mlKitOcrEngine = MLKitOcrEngine(context)

        fakeDao = FakeFileDao()
        fakeOcrEngine = FakeOcrEngine()
        repository = SmartManagerRepository(context, fakeDao, fakeOcrEngine)
    }

    @Test
    fun testMLKitOcrEngine_nonExistentFile_returnsEmptyStringWithoutCrash() = runBlocking {
        val nonExistentPath = "/non_existent_dir_xyz/non_existent_image.jpg"
        val result = mlKitOcrEngine.extractRealOcrText(nonExistentPath)

        assertEquals("", result)
    }

    @Test
    fun testMLKitOcrEngine_nonExistentPdf_returnsEmptyStringWithoutCrash() = runBlocking {
        val nonExistentPdfPath = "/non_existent_dir_xyz/non_existent_document.pdf"
        val result = mlKitOcrEngine.extractRealOcrText(nonExistentPdfPath)

        assertEquals("", result)
    }

    @Test
    fun testMLKitOcrEngine_unreadableFile_returnsEmptyStringWithoutCrash() = runBlocking {
        val tempFile = File.createTempFile("unreadable_", ".tmp")
        try {
            tempFile.setReadable(false)
            val result = mlKitOcrEngine.extractRealOcrText(tempFile.absolutePath)
            assertEquals("", result)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun test_empty_or_null_ocr_result_does_not_generate_fabricated_data() = runBlocking {
        fakeOcrEngine.resultText = ""

        val testFile = FileItemEntity(
            id = 501L,
            name = "receipt.jpg",
            path = "/storage/emulated/0/DCIM/receipt.jpg",
            category = FileCategory.IMAGES.name,
            sizeBytes = 1200L,
            md5Hash = "some_existing_hash",
            ocrText = ""
        )

        fakeDao.unhashedFiles.add(testFile)
        fakeDao.plugins.add(
            PluginEntity("ocr_engine", "ML Kit OCR Engine", "OCR", "Extract text", isEnabled = true, isCore = true)
        )

        val latch = java.util.concurrent.CountDownLatch(1)
        fakeDao.onUpdateCallback = {
            latch.countDown()
        }

        repository.startIncrementalDuplicateScan()
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)

        val updatedFile = fakeDao.updatedFiles.find { it.id == 501L }
        if (updatedFile != null) {
            assertEquals("", updatedFile.ocrText)
            assertFalse(updatedFile.ocrText.contains("27AAAC"))
            assertFalse(updatedFile.ocrText.contains("GSTIN"))
            assertFalse(updatedFile.ocrText.contains("Amount"))
            assertFalse(updatedFile.ocrText.contains("Date"))
        }
    }

    @Test
    fun test_mocked_real_ocr_result_correctly_saved_to_entity() = runBlocking {
        fakeOcrEngine.resultText = "AUTHENTIC OCR CONTENT EXTRACTED FROM IMAGE"

        val testFile = FileItemEntity(
            id = 502L,
            name = "invoice_real.jpg",
            path = "/storage/emulated/0/DCIM/invoice_real.jpg",
            category = FileCategory.IMAGES.name,
            sizeBytes = 2200L,
            md5Hash = "hash_502",
            ocrText = ""
        )

        fakeDao.unhashedFiles.add(testFile)
        fakeDao.plugins.add(
            PluginEntity("ocr_engine", "ML Kit OCR Engine", "OCR", "Extract text", isEnabled = true, isCore = true)
        )

        val latch = java.util.concurrent.CountDownLatch(1)
        fakeDao.onUpdateCallback = {
            latch.countDown()
        }

        repository.startIncrementalDuplicateScan()
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)

        val updatedFile = fakeDao.updatedFiles.find { it.id == 502L }
        assertNotNull(updatedFile)
        assertEquals("AUTHENTIC OCR CONTENT EXTRACTED FROM IMAGE", updatedFile!!.ocrText)
        assertFalse(updatedFile.ocrText.contains("27AAAC"))
    }

    @Test
    fun test_ocr_engine_unavailability_handled_gracefully_without_fabrication() = runBlocking {
        fakeOcrEngine.shouldThrowException = true

        val testFile = FileItemEntity(
            id = 503L,
            name = "document_failed.jpg",
            path = "/storage/emulated/0/DCIM/document_failed.jpg",
            category = FileCategory.IMAGES.name,
            sizeBytes = 3200L,
            md5Hash = "hash_503",
            ocrText = ""
        )

        fakeDao.unhashedFiles.add(testFile)
        fakeDao.plugins.add(
            PluginEntity("ocr_engine", "ML Kit OCR Engine", "OCR", "Extract text", isEnabled = true, isCore = true)
        )

        val latch = java.util.concurrent.CountDownLatch(1)
        fakeDao.onUpdateCallback = {
            latch.countDown()
        }

        repository.startIncrementalDuplicateScan()
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)

        val updatedFile = fakeDao.updatedFiles.find { it.id == 503L }
        if (updatedFile != null) {
            assertEquals("", updatedFile.ocrText)
            assertFalse(updatedFile.ocrText.contains("27AAAC"))
            assertFalse(updatedFile.ocrText.contains("GSTIN"))
        }
    }

    @Test
    fun documentStats_countsOnlyNonVaultNonRecycleBinDocuments() = runBlocking {
        fakeDao.activeFiles.addAll(
            listOf(
                FileItemEntity(
                    id = 601L,
                    name = "indexed.pdf",
                    path = "/tmp/indexed.pdf",
                    category = FileCategory.DOCUMENTS.name,
                    sizeBytes = 1L,
                    md5Hash = "hash"
                ),
                FileItemEntity(
                    id = 602L,
                    name = "pending.pdf",
                    path = "/tmp/pending.pdf",
                    category = FileCategory.DOCUMENTS.name,
                    sizeBytes = 1L
                ),
                FileItemEntity(
                    id = 603L,
                    name = "vault.pdf",
                    path = "/tmp/vault.pdf",
                    category = FileCategory.DOCUMENTS.name,
                    sizeBytes = 1L,
                    md5Hash = "vault",
                    isVault = true
                ),
                FileItemEntity(
                    id = 604L,
                    name = "deleted.pdf",
                    path = "/tmp/deleted.pdf",
                    category = FileCategory.DOCUMENTS.name,
                    sizeBytes = 1L,
                    md5Hash = "deleted",
                    isRecycleBin = true
                ),
                FileItemEntity(
                    id = 605L,
                    name = "photo.jpg",
                    path = "/tmp/photo.jpg",
                    category = FileCategory.IMAGES.name,
                    sizeBytes = 1L,
                    md5Hash = "photo"
                )
            )
        )

        val stats = repository.documentStats.first()

        assertEquals(1, stats.first)
        assertEquals(1, stats.second)
        assertEquals(0.5f, stats.third)
    }

    @Test
    fun exactDuplicates_groupsOnlyRepeatedNonBlankHashes() = runBlocking {
        val firstDuplicate = FileItemEntity(
            id = 611L,
            name = "one.txt",
            path = "/tmp/one.txt",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 1L,
            md5Hash = "same"
        )
        val secondDuplicate = firstDuplicate.copy(id = 612L, name = "two.txt", path = "/tmp/two.txt")
        fakeDao.duplicateFiles.addAll(
            listOf(
                firstDuplicate,
                secondDuplicate,
                firstDuplicate.copy(id = 613L, name = "blank-a.txt", path = "/tmp/blank-a.txt", md5Hash = ""),
                firstDuplicate.copy(id = 614L, name = "blank-b.txt", path = "/tmp/blank-b.txt", md5Hash = "")
            )
        )

        val groups = repository.exactDuplicates.first()

        assertEquals(1, groups.size)
        assertEquals(100, groups.single().similarityScore)
        assertEquals(listOf(611L, 612L), groups.single().files.map { it.id })
    }

    @Test
    fun searchSemanticFiles_usesLocalFallbackWhenModelAssetsAreUnavailable() = runBlocking {
        fakeDao.activeFiles += FileItemEntity(
            id = 621L,
            name = "notes.txt",
            path = "/tmp/notes.txt",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 1L
        )

        assertTrue(repository.isSemanticSearchAvailable)
        assertFalse(repository.searchSemanticFiles("notes").first().isEmpty())
    }

    @Test
    fun enqueueCloudSyncItem_rejectsDisabledProviderAndDuplicate() = runBlocking {
        fakeDao.plugins += PluginEntity(
            "gdrive_sync",
            "Drive",
            "CLOUD_PROVIDER",
            "Drive sync",
            isEnabled = false,
            isCore = false
        )
        assertFalse(repository.enqueueCloudSyncItem("GOOGLE_DRIVE", "report.pdf", 10L))

        fakeDao.plugins[0] = fakeDao.plugins[0].copy(isEnabled = true)
        fakeDao.cloudItems += CloudSyncItemEntity(
            id = 631L,
            provider = "google_drive",
            fileName = "report.pdf",
            fileSize = 10L,
            status = "PENDING"
        )
        assertFalse(repository.enqueueCloudSyncItem("GOOGLE_DRIVE", "report.pdf", 10L))
        assertTrue(fakeDao.insertedCloudItems.isEmpty())
    }

    @Test
    fun enqueueRetryAndCancelCloudSyncItem_preserveQueueStateContracts() = runBlocking {
        fakeDao.plugins += PluginEntity(
            "gdrive_sync",
            "Drive",
            "CLOUD_PROVIDER",
            "Drive sync",
            isEnabled = true,
            isCore = false
        )
        assertFalse(
            repository.enqueueCloudSyncItem(
                "GOOGLE_DRIVE",
                "report.pdf",
                10L,
                filePath = "/tmp/report.pdf",
                isCore = true
            )
        )
        assertTrue(fakeDao.insertedCloudItems.isEmpty())

        val failed = CloudSyncItemEntity(
            id = 641L,
            provider = "GOOGLE_DRIVE",
            fileName = "failed.pdf",
            fileSize = 20L,
            status = "FAILED"
        )
        fakeDao.cloudItems += failed
        val authorizedRepository = SmartManagerRepository(
            context = context,
            dao = fakeDao,
            ocrEngine = fakeOcrEngine,
            cloudTransferAllowed = { true },
        )
        assertTrue(authorizedRepository.retryCloudSyncItem(641L))
        assertEquals("QUEUED", fakeDao.insertedCloudItems.last().status)

        val synced = CloudSyncItemEntity(
            id = 642L,
            provider = "GOOGLE_DRIVE",
            fileName = "synced.pdf",
            fileSize = 20L,
            status = "SYNCED"
        )
        fakeDao.cloudItems += synced
        assertFalse(repository.retryCloudSyncItem(642L))
        assertFalse(repository.cancelCloudSyncItem(642L))
        assertTrue(repository.cancelCloudSyncItem(641L))
        assertEquals(listOf(641L), fakeDao.deletedCloudItemIds)
    }

    @Test
    fun withRetry_returnsOnFirstSuccessfulAttemptAfterTransientFailures() = runBlocking {
        var attempts = 0

        val result = repository.withRetry(RetryOperation.DATABASE_WRITE, maxAttempts = 3, initialDelayMs = 0, factor = 2.0) {
            attempts++
            if (attempts < 3) throw SQLiteDatabaseLockedException("database locked")
            "persisted"
        }

        assertEquals("persisted", result)
        assertEquals(3, attempts)
    }

    @Test
    fun withRetry_doesNotRetryPermanentFailure() = runBlocking {
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
