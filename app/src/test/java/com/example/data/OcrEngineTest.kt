package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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

        override suspend fun getFileById(id: Long): FileItemEntity? = null
        override suspend fun getFileByName(name: String): FileItemEntity? = null
        override fun getOcrScannedFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getAllActiveFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getRecentFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getCategoryStats(): Flow<List<CategoryStat>> = flowOf(emptyList())
        override suspend fun getFilteredFilesPaged(category: String?, query: String, limit: Int, offset: Int): List<FileItemEntity> = emptyList()
        override fun getFilesByCategory(category: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getRecycleBinFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getVaultFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getDuplicateFilesByHash(): Flow<List<FileItemEntity>> = flowOf(emptyList())
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
        override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = flowOf(emptyList())
        override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long = 0L
        override suspend fun deleteCloudSyncItem(id: Long) {}
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
}
