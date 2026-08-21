package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartManagerRepositoryJvmCoverageTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: FileDao
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.fileDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cloudQueueRejectsDeniedDisabledAndUnsupportedTransfersWithoutSchedulingWork() = runBlocking {
        val denied = repository { false }
        assertFalse(denied.enqueueCloudSyncItem("GOOGLE_DRIVE", "denied.txt", 1L))

        dao.insertPlugins(
            listOf(PluginEntity("gdrive_sync", "Drive", "CLOUD_PROVIDER", "Drive", false, true))
        )
        val enabledByPolicy = repository { true }
        assertFalse(enabledByPolicy.enqueueCloudSyncItem("GOOGLE_DRIVE", "disabled.txt", 1L))
        assertFalse(enabledByPolicy.enqueueCloudSyncItem("UNSUPPORTED", "unsupported.txt", 1L))
        assertTrue(dao.getCloudSyncItems().first().isEmpty())
    }

    @Test
    fun cloudQueuePersistsEnabledTransferAndRejectsEquivalentQueuedPath() = runBlocking {
        dao.insertPlugins(
            listOf(PluginEntity("gdrive_sync", "Drive", "CLOUD_PROVIDER", "Drive", true, true))
        )
        val repository = repository { true }

        assertTrue(
            repository.enqueueCloudSyncItem(
                provider = "GOOGLE_DRIVE",
                fileName = "invoice.pdf",
                size = 55L,
                filePath = "/documents/invoice.pdf",
                isCore = true
            )
        )

        val queued = dao.getCloudSyncItems().first().single()
        assertEquals("QUEUED", queued.status)
        assertEquals("/documents/invoice.pdf", queued.filePath)
        assertTrue(queued.isCore)
        assertTrue(queued.operationId.isNotBlank())
        assertFalse(
            repository.enqueueCloudSyncItem(
                provider = "google_drive",
                fileName = "renamed.pdf",
                size = 99L,
                filePath = "/documents/invoice.pdf"
            )
        )
        assertEquals(1, dao.getCloudSyncItems().first().size)
    }

    @Test
    fun cloudCancellationOnlyRemovesNonSyncedRowsAndRetryRejectsMissingOrSyncedRows() = runBlocking {
        val syncedId = dao.insertCloudSyncItem(cloudItem("SYNCED"))
        val queuedId = dao.insertCloudSyncItem(cloudItem("QUEUED"))
        val repository = repository { true }

        assertFalse(repository.retryCloudSyncItem(-1L))
        assertFalse(repository.retryCloudSyncItem(syncedId))
        assertFalse(repository.cancelCloudSyncItem(syncedId))
        assertTrue(repository.cancelCloudSyncItem(queuedId))
        assertEquals(listOf(syncedId), dao.getCloudSyncItems().first().map { it.id })
    }

    @Test
    fun cloudRetryRequeuesFailedItemAndClearsItsExpiredTransferState() = runBlocking {
        dao.insertPlugins(
            listOf(PluginEntity("gdrive_sync", "Drive", "CLOUD_PROVIDER", "Drive", true, true))
        )
        val failedId = dao.insertCloudSyncItem(
            cloudItem("FAILED").copy(
                leaseOwner = "previous-worker",
                leaseExpiresAtMs = 99L,
                heartbeatAtMs = 88L,
                completedAtMs = 77L,
                lastErrorCode = "NETWORK_TIMEOUT"
            )
        )
        val repository = repository { true }

        assertTrue(repository.retryCloudSyncItem(failedId))

        val retried = dao.getCloudSyncItems().first().single()
        assertEquals("QUEUED", retried.status)
        assertEquals(null, retried.leaseOwner)
        assertEquals(0L, retried.leaseExpiresAtMs)
        assertEquals(0L, retried.heartbeatAtMs)
        assertEquals(0L, retried.completedAtMs)
        assertEquals(null, retried.lastErrorCode)
    }

    @Test
    fun recycleMovePersistsOriginalPathAfterPhysicalMoveAndClearsOpenOperation() = runBlocking {
        val source = File(context.cacheDir, "recycle-${System.nanoTime()}.txt").apply {
            writeText("recycle fixture")
        }
        val fileId = dao.insertFile(
            FileItemEntity(
                name = source.name,
                path = source.absolutePath,
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = source.length()
            )
        )
        val current = dao.getFileById(fileId) ?: error("recycle fixture missing")

        repository { false }.moveToRecycleBin(current)

        val recycled = dao.getFileById(fileId) ?: error("recycled fixture missing")
        assertTrue(recycled.isRecycleBin)
        assertEquals(source.absolutePath, recycled.originalPath)
        assertFalse(source.exists())
        assertTrue(File(recycled.path).exists())
        assertTrue(database.fileOperationStore().getOpenOperations().isEmpty())
        File(recycled.path).delete()
        Unit
    }

    @Test
    fun recycleRestoreReturnsFileToOriginalPathAndClearsItsOperationLedger() = runBlocking {
        val source = File(context.cacheDir, "restore-${System.nanoTime()}.txt").apply {
            writeText("restore fixture")
        }
        val fileId = dao.insertFile(
            FileItemEntity(
                name = source.name,
                path = source.absolutePath,
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = source.length()
            )
        )
        val repository = repository { false }
        repository.moveToRecycleBin(dao.getFileById(fileId) ?: error("restore fixture missing"))
        val recycled = dao.getFileById(fileId) ?: error("recycled restore fixture missing")

        repository.restoreFromRecycleBin(recycled)

        val restored = dao.getFileById(fileId) ?: error("restored fixture missing")
        assertEquals(source.absolutePath, restored.path)
        assertEquals("", restored.originalPath)
        assertFalse(restored.isRecycleBin)
        assertEquals(0L, restored.deletedTimestampMs)
        assertTrue(source.exists())
        assertEquals("restore fixture", source.readText())
        assertFalse(File(recycled.path).exists())
        assertTrue(database.fileOperationStore().getOpenOperations().isEmpty())
        assertTrue(source.delete())
    }

    @Test
    fun permanentDeleteRemovesPhysicalFileDaoRowAndOperationLedger() = runBlocking {
        val source = File(context.cacheDir, "delete-${System.nanoTime()}.txt").apply {
            writeText("delete fixture")
        }
        val fileId = dao.insertFile(
            FileItemEntity(
                name = source.name,
                path = source.absolutePath,
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = source.length()
            )
        )
        val repository = repository { false }

        repository.deletePermanently(dao.getFileById(fileId) ?: error("delete fixture missing"))

        assertFalse(source.exists())
        assertNull(dao.getFileById(fileId))
        assertTrue(database.fileOperationStore().getOpenOperations().isEmpty())
    }

    @Test
    fun recoverPendingFileOperationsCompletesPreparedMoveRestoreAndDelete() = runBlocking {
        val operationStore = database.fileOperationStore()
        val repository = repository { false }

        val moveSource = File(context.cacheDir, "recover-move-${System.nanoTime()}.txt").apply {
            writeText("move recovery fixture")
        }
        val moveId = dao.insertFile(
            FileItemEntity(
                name = moveSource.name,
                path = moveSource.absolutePath,
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = moveSource.length()
            )
        )
        val moveTarget = PhysicalStorageManager.trashPathForOperation(
            context,
            moveSource.absolutePath,
            "recovery-move-$moveId"
        )
        operationStore.insert(
            FileOperationEntity(
                operationId = "recovery-move-$moveId",
                operationType = "MOVE_TO_TRASH",
                fileId = moveId,
                sourcePath = moveSource.absolutePath,
                targetPath = moveTarget,
                status = FileOperationStatus.PREPARED,
                createdAtMs = 1L,
                updatedAtMs = 1L
            )
        )

        val restoreSource = File(context.cacheDir, "recover-restore-trash-${System.nanoTime()}.txt").apply {
            writeText("restore recovery fixture")
        }
        val restoreTarget = File(context.cacheDir, "recover-restore-target-${System.nanoTime()}.txt")
        val restoreId = dao.insertFile(
            FileItemEntity(
                name = restoreSource.name,
                path = restoreSource.absolutePath,
                originalPath = restoreTarget.absolutePath,
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = restoreSource.length(),
                isRecycleBin = true
            )
        )
        operationStore.insert(
            FileOperationEntity(
                operationId = "recovery-restore-$restoreId",
                operationType = "RESTORE",
                fileId = restoreId,
                sourcePath = restoreSource.absolutePath,
                targetPath = restoreTarget.absolutePath,
                status = FileOperationStatus.PREPARED,
                createdAtMs = 2L,
                updatedAtMs = 2L
            )
        )

        val deleteSource = File(context.cacheDir, "recover-delete-${System.nanoTime()}.txt").apply {
            writeText("delete recovery fixture")
        }
        val deleteId = dao.insertFile(
            FileItemEntity(
                name = deleteSource.name,
                path = deleteSource.absolutePath,
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = deleteSource.length()
            )
        )
        operationStore.insert(
            FileOperationEntity(
                operationId = "recovery-delete-$deleteId",
                operationType = "DELETE",
                fileId = deleteId,
                sourcePath = deleteSource.absolutePath,
                targetPath = "",
                status = FileOperationStatus.PREPARED,
                createdAtMs = 3L,
                updatedAtMs = 3L
            )
        )

        repository.recoverPendingFileOperations()

        val moved = dao.getFileById(moveId) ?: error("recovered move row missing")
        assertTrue(moved.isRecycleBin)
        assertEquals(moveSource.absolutePath, moved.originalPath)
        assertEquals(moveTarget, moved.path)
        assertFalse(moveSource.exists())
        assertTrue(File(moveTarget).exists())

        val restored = dao.getFileById(restoreId) ?: error("recovered restore row missing")
        assertFalse(restored.isRecycleBin)
        assertEquals(restoreTarget.absolutePath, restored.path)
        assertEquals("", restored.originalPath)
        assertFalse(restoreSource.exists())
        assertTrue(restoreTarget.exists())

        assertFalse(deleteSource.exists())
        assertNull(dao.getFileById(deleteId))
        assertTrue(operationStore.getOpenOperations().isEmpty())

        assertTrue(File(moveTarget).delete())
        assertTrue(restoreTarget.delete())
    }

    @Test
    fun incrementalScanPersistsDocumentCandidateEvidenceAndCompletesProgress() = runBlocking {
        dao.insertPlugins(
            listOf(PluginEntity("ocr_engine", "OCR", "AI", "test-only disabled OCR", false, false))
        )
        val source = File(context.cacheDir, "scan-${System.nanoTime()}.txt").apply {
            writeText("incremental scan document fixture")
        }
        val image = File(context.cacheDir, "scan-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.BLUE)
            image.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        } finally {
            bitmap.recycle()
        }
        val fileId = dao.insertFile(
            FileItemEntity(
                name = source.name,
                path = source.absolutePath,
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = source.length(),
                semanticIndexed = true
            )
        )
        val imageId = dao.insertFile(
            FileItemEntity(
                name = image.name,
                path = image.absolutePath,
                category = FileCategory.IMAGES.name,
                sizeBytes = image.length(),
                semanticIndexed = true
            )
        )
        val repository = repository { false }
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            repository.scanProgress.drop(1).first { it == 1.0f }
        }

        repository.startIncrementalDuplicateScan()

        withTimeout(5_000L) { completion.await() }
        val scanned = dao.getFileById(fileId) ?: error("incrementally scanned fixture missing")
        val scannedImage = dao.getFileById(imageId) ?: error("incrementally scanned image missing")
        assertTrue(scanned.md5Hash.isNotBlank())
        assertTrue(scanned.documentCandidateFingerprint.isNotBlank())
        assertTrue(scanned.semanticIndexed)
        assertTrue(scannedImage.md5Hash.isNotBlank())
        assertTrue(scannedImage.visualSimilarityHash.isNotBlank())
        assertTrue(scannedImage.semanticIndexed)
        assertEquals(1.0f, repository.scanProgress.value)
        assertFalse(repository.isScanning.value)
        assertTrue(source.delete())
        assertTrue(image.delete())
    }

    @Test
    fun databaseLockRetriesOnceAndThenReturnsSuccessfulResult() = runBlocking {
        val repository = repository { false }
        var attempts = 0

        val result = repository.withRetry(
            operation = com.example.domain.retry.RetryOperation.DATABASE_WRITE,
            maxAttempts = 2,
            initialDelayMs = 0L,
            factor = 1.0
        ) {
            attempts += 1
            if (attempts == 1) throw SQLiteDatabaseLockedException("fixture lock")
            "written"
        }

        assertEquals("written", result)
        assertEquals(2, attempts)
    }

    @Test
    fun retryRetriesOnlyTemporaryFileIoAndCloudTimeouts() = runBlocking {
        val repository = repository { false }
        var temporaryIoAttempts = 0
        var timeoutAttempts = 0

        val fileResult = repository.withRetry(
            operation = com.example.domain.retry.RetryOperation.FILE_STORAGE,
            maxAttempts = 2,
            initialDelayMs = 0L,
            factor = 1.0
        ) {
            temporaryIoAttempts += 1
            if (temporaryIoAttempts == 1) throw java.io.IOException("temporarily unavailable")
            "stored"
        }
        val cloudResult = repository.withRetry(
            operation = com.example.domain.retry.RetryOperation.CLOUD_TRANSFER,
            maxAttempts = 2,
            initialDelayMs = 0L,
            factor = 1.0
        ) {
            timeoutAttempts += 1
            if (timeoutAttempts == 1) throw java.util.concurrent.TimeoutException("fixture timeout")
            "uploaded"
        }

        assertEquals("stored", fileResult)
        assertEquals(2, temporaryIoAttempts)
        assertEquals("uploaded", cloudResult)
        assertEquals(2, timeoutAttempts)
    }

    @Test
    fun retryDoesNotRepeatPermissionInvalidInputOrCancellationFailures() = runBlocking {
        val repository = repository { false }
        var permissionAttempts = 0
        var invalidInputAttempts = 0
        var cancellationAttempts = 0

        val permissionFailure = runCatching {
            repository.withRetry(
                com.example.domain.retry.RetryOperation.FILE_STORAGE,
                maxAttempts = 3,
                initialDelayMs = 0L,
                factor = 1.0
            ) {
                permissionAttempts += 1
                throw SecurityException("permission denied")
            }
        }.exceptionOrNull()
        val invalidInputFailure = runCatching {
            repository.withRetry(
                com.example.domain.retry.RetryOperation.FILE_STORAGE,
                maxAttempts = 3,
                initialDelayMs = 0L,
                factor = 1.0
            ) {
                invalidInputAttempts += 1
                throw IllegalArgumentException("invalid request")
            }
        }.exceptionOrNull()
        val cancellationFailure = runCatching {
            repository.withRetry(
                com.example.domain.retry.RetryOperation.CLOUD_TRANSFER,
                maxAttempts = 3,
                initialDelayMs = 0L,
                factor = 1.0
            ) {
                cancellationAttempts += 1
                throw CancellationException("caller cancelled")
            }
        }.exceptionOrNull()

        assertTrue(permissionFailure is SecurityException)
        assertTrue(invalidInputFailure is IllegalArgumentException)
        assertTrue(cancellationFailure is CancellationException)
        assertEquals(1, permissionAttempts)
        assertEquals(1, invalidInputAttempts)
        assertEquals(1, cancellationAttempts)
    }

    private fun repository(transferAllowed: (Context) -> Boolean) = SmartManagerRepository(
        context = context,
        dao = dao,
        cloudTransferAllowed = transferAllowed,
        fileOperationStoreOverride = database.fileOperationStore()
    )

    private fun cloudItem(status: String) = CloudSyncItemEntity(
        provider = "GOOGLE_DRIVE",
        fileName = "${status.lowercase()}-${System.nanoTime()}.txt",
        filePath = "/sync/$status.txt",
        fileSize = 1L,
        status = status,
        operationId = "operation-$status-${System.nanoTime()}"
    )
}
