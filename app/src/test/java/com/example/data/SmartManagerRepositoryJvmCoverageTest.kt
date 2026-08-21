package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
