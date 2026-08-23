package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
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
    private lateinit var fakeOperationStore: TestFileOperationStore
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

        private fun rows(): List<FileItemEntity> =
            (activeFiles + unhashedFiles + recycleBinFiles).distinctBy { it.id }

        private fun replaceRow(file: FileItemEntity) {
            activeFiles.removeAll { it.id == file.id }
            unhashedFiles.removeAll { it.id == file.id }
            recycleBinFiles.removeAll { it.id == file.id }
            when {
                file.isRecycleBin -> recycleBinFiles += file
                else -> activeFiles += file
            }
        }

        private fun merge(existing: FileItemEntity?, incoming: FileItemEntity): FileItemEntity =
            if (existing == null) incoming else existing.copy(
                name = incoming.name,
                category = incoming.category,
                sizeBytes = incoming.sizeBytes,
                dateModifiedMs = incoming.dateModifiedMs,
                md5Hash = incoming.md5Hash.ifBlank { existing.md5Hash },
                ocrText = incoming.ocrText.ifBlank { existing.ocrText },
                tags = incoming.tags.ifBlank { existing.tags },
                originalPath = incoming.originalPath.ifBlank { existing.originalPath },
                visualSimilarityHash = incoming.visualSimilarityHash.ifBlank { existing.visualSimilarityHash },
                semanticEmbeddingVersion = if (incoming.semanticEmbeddingVersion > 0) incoming.semanticEmbeddingVersion else existing.semanticEmbeddingVersion,
                semanticIndexed = incoming.semanticIndexed || existing.semanticIndexed,
                semanticEmbeddingString = incoming.semanticEmbeddingString.ifBlank { existing.semanticEmbeddingString },
                videoFingerprintVersion = if (incoming.videoFingerprintVersion > 0) incoming.videoFingerprintVersion else existing.videoFingerprintVersion,
                videoSampleHashes = incoming.videoSampleHashes.ifBlank { existing.videoSampleHashes },
                videoDurationMs = if (incoming.videoDurationMs > 0) incoming.videoDurationMs else existing.videoDurationMs,
                videoWidth = if (incoming.videoWidth > 0) incoming.videoWidth else existing.videoWidth,
                videoHeight = if (incoming.videoHeight > 0) incoming.videoHeight else existing.videoHeight,
                videoAudioSignature = incoming.videoAudioSignature.ifBlank { existing.videoAudioSignature },
                videoChunkHash = incoming.videoChunkHash.ifBlank { existing.videoChunkHash },
                documentCandidateFingerprint = incoming.documentCandidateFingerprint.ifBlank { existing.documentCandidateFingerprint },
                isVault = existing.isVault,
                isRecycleBin = existing.isRecycleBin,
                deletedTimestampMs = existing.deletedTimestampMs,
            )

        override suspend fun getUnhashedFiles(): List<FileItemEntity> = rows()
            .filter { !it.isVault && !it.isRecycleBin && (it.md5Hash.isBlank() || !it.semanticIndexed) }
        override fun getAllPlugins(): Flow<List<PluginEntity>> = flow { emit(plugins.toList()) }
        override suspend fun updateFiles(files: List<FileItemEntity>) {
            files.forEach { replaceRow(it) }
            updatedFiles += files
            onUpdate?.invoke()
        }

        override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? =
            recycleBinFiles.firstOrNull { it.md5Hash == hash }
        override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>) = updateFiles(files)
        override suspend fun getFileById(id: Long): FileItemEntity? = rows().firstOrNull { it.id == id }
        override suspend fun getFileByName(name: String): FileItemEntity? = rows().firstOrNull { it.name == name }
        override fun getOcrScannedFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getAllActiveFiles(): Flow<List<FileItemEntity>> = flow {
            emit(rows().filter { !it.isVault && !it.isRecycleBin })
        }
        override fun getRecentFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getCategoryStats(): Flow<List<CategoryStat>> = flowOf(emptyList())
        override suspend fun getFilteredFilesPaged(category: String?, query: String, limit: Int, offset: Int): List<FileItemEntity> = emptyList()
        override fun getFilesByCategory(category: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getRecycleBinFiles(): Flow<List<FileItemEntity>> = flow {
            emit(rows().filter { it.isRecycleBin })
        }
        override fun getVaultFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getDuplicateFilesByHash(): Flow<List<FileItemEntity>> = flow { emit(duplicateFiles.toList()) }
        override suspend fun insertFile(file: FileItemEntity): Long {
            val existing = getFileByPath(file.path)
            val persisted = merge(existing, file)
            replaceRow(persisted)
            return existing?.id ?: file.id
        }
        override suspend fun insertFiles(files: List<FileItemEntity>) {
            files.forEach { insertFile(it) }
        }
        override suspend fun updateFile(file: FileItemEntity) {
            replaceRow(file)
            updatedSingleFiles += file
        }
        override suspend fun getFileByPath(path: String): FileItemEntity? = rows().firstOrNull { it.path == path }
        override suspend fun insertFileDirect(file: FileItemEntity): Long {
            replaceRow(file)
            return file.id
        }
        override suspend fun getAllOrdinaryFilesDirect(): List<FileItemEntity> = rows()
            .filter { !it.isVault && !it.isRecycleBin }
        override suspend fun deleteFilesByIds(ids: List<Long>) {
            ids.forEach { deleteFileById(it) }
        }
        override suspend fun deleteFileById(id: Long) {
            activeFiles.removeAll { it.id == id }
            unhashedFiles.removeAll { it.id == id }
            recycleBinFiles.removeAll { it.id == id }
            deletedFileIds += id
        }
        override suspend fun emptyRecycleBin() {
            val ids = recycleBinFiles.map { it.id }
            recycleBinFiles.clear()
            deletedFileIds += ids
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

    private class TestFileOperationStore : FileOperationStore {
        private val operations = linkedMapOf<String, FileOperationEntity>()
        val transitions = mutableListOf<FileOperationEntity>()

        override suspend fun getOpenOperations(): List<FileOperationEntity> = operations.values
            .filter { it.status == FileOperationStatus.PREPARED || it.status == FileOperationStatus.PHYSICAL_COMPLETED }
            .sortedBy { it.createdAtMs }

        override suspend fun findOpenOperation(fileId: Long, operationType: String): FileOperationEntity? = operations.values
            .filter {
                it.fileId == fileId &&
                    it.operationType == operationType &&
                    (it.status == FileOperationStatus.PREPARED || it.status == FileOperationStatus.PHYSICAL_COMPLETED)
            }
            .maxByOrNull { it.createdAtMs }

        override suspend fun insert(operation: FileOperationEntity) {
            operations[operation.operationId] = operation
            transitions += operation
        }

        override suspend fun transition(
            operationId: String,
            status: String,
            sourcePath: String,
            targetPath: String,
            nowMs: Long,
            errorCode: String?
        ): Int {
            val current = operations[operationId] ?: return 0
            val updated = current.copy(
                status = status,
                sourcePath = sourcePath,
                targetPath = targetPath,
                updatedAtMs = nowMs,
                lastErrorCode = errorCode
            )
            operations[operationId] = updated
            transitions += updated
            return 1
        }

        override suspend fun delete(operationId: String): Int =
            if (operations.remove(operationId) != null) 1 else 0

        fun hasOpenOperations(): Boolean = operations.values.any {
            it.status == FileOperationStatus.PREPARED || it.status == FileOperationStatus.PHYSICAL_COMPLETED
        }

        fun statusFor(operationId: String): String? = operations[operationId]?.status
    }

    private class TestOcrEngine : OcrEngine {
        var text: String = ""
        var closeCallCount = 0
        var blockUntilCancelled = false
        var cancellationObserved = false
        val extractionStarted = CountDownLatch(1)

        override suspend fun extractRealOcrText(filePath: String): String {
            if (blockUntilCancelled) {
                extractionStarted.countDown()
                try {
                    awaitCancellation()
                } catch (error: CancellationException) {
                    cancellationObserved = true
                    throw error
                }
            }
            return text
        }

        override suspend fun extractOcrBlocks(filePath: String): List<OcrTextBlock> = emptyList()

        override fun close() {
            closeCallCount++
        }
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        fakeDao = TestFileDao()
        fakeOcr = TestOcrEngine()
        fakeOperationStore = TestFileOperationStore()
        repository = SmartManagerRepository(
            context = context,
            dao = fakeDao,
            ocrEngine = fakeOcr,
            fileOperationStoreOverride = fakeOperationStore,
        )
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
    fun trimMemory_closesInitializedOcrEngine(): Unit {
        assertNotNull(repository.activeOcrEngine)

        repository.trimMemory()

        assertEquals(1, fakeOcr.closeCallCount)
    }

    @Test
    fun cleanup_cancelsActiveScanAndClosesInitializedOcrEngine(): Unit = runBlocking {
        fakeOcr.blockUntilCancelled = true
        fakeDao.unhashedFiles += image(305L, "cleanup-blocked.png")
        assertNotNull(repository.activeOcrEngine)
        repository.startIncrementalDuplicateScan()

        assertTrue(fakeOcr.extractionStarted.await(5, TimeUnit.SECONDS))
        repository.cleanup()

        withTimeout(5_000L) {
            while (!fakeOcr.cancellationObserved || repository.isScanning.value) delay(10L)
        }
        assertTrue(fakeOcr.cancellationObserved)
        assertEquals(1, fakeOcr.closeCallCount)
        assertFalse(repository.isScanning.value)
        assertEquals(1.0f, repository.scanProgress.value)

        repository.cleanup()
        assertEquals(1, fakeOcr.closeCallCount)
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
    fun recycleBinOperations_failedPhysicalMoveDoesNotMutateDao(): Unit = runBlocking {
        val missing = document(304L, "missing-physical-file.txt")
        fakeDao.activeFiles += missing
        try {
            repository.moveToRecycleBin(missing)
            throw AssertionError("moveToRecycleBin should fail when the source file is missing")
        } catch (_: Exception) {
            assertEquals(missing, fakeDao.getFileById(missing.id))
            assertTrue(fakeDao.updatedSingleFiles.isEmpty())
            assertEquals(FileOperationStatus.FAILED, fakeOperationStore.statusFor("file-MOVE_TO_TRASH-${missing.id}"))
            assertFalse(fakeOperationStore.hasOpenOperations())
        }
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
            assertTrue(fakeDao.getRecycleBinFiles().first().any { it.id == ordinary.id && it.isRecycleBin })
            assertTrue(fakeDao.getAllActiveFiles().first().none { it.id == ordinary.id })

            fakeDao.updatedSingleFiles.clear()
            fakeDao.activeFiles.clear()
            fakeDao.activeFiles += recycled
            repository.restoreFromRecycleBin(recycled)

            assertTrue(source.exists())
            val restored = fakeDao.updatedSingleFiles.single()
            assertFalse(restored.isRecycleBin)
            assertEquals(source.absolutePath, restored.path)
            assertTrue(restored.originalPath.isBlank())
            assertTrue(fakeDao.getAllActiveFiles().first().any { it.id == ordinary.id && !it.isRecycleBin })
            assertTrue(fakeDao.getRecycleBinFiles().first().none { it.id == ordinary.id })
            assertFalse(fakeOperationStore.hasOpenOperations())
            assertTrue(fakeOperationStore.transitions.any { it.status == FileOperationStatus.COMMITTED })
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
