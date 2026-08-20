package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSyncOperationStoreJvmCoverageTest {
    private lateinit var database: AppDatabase
    private lateinit var fileDao: FileDao
    private lateinit var store: CloudSyncOperationStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fileDao = database.fileDao()
        store = database.cloudSyncOperationStore()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun leasesAndTransferStateFollowCrashSafeOperationLifecycle() = runBlocking {
        val now = 1_000L
        val expiredOperation = "expired-${System.nanoTime()}"
        val operation = "active-${System.nanoTime()}"
        val failureOperation = "failure-${System.nanoTime()}"
        insertCloudItem(
            operationId = expiredOperation,
            status = "UPLOADING",
            leaseOwner = "abandoned-worker",
            leaseExpiresAtMs = now - 1L
        )
        insertCloudItem(operationId = operation, status = "QUEUED")
        insertCloudItem(operationId = failureOperation, status = "PENDING")

        assertEquals(1, store.releaseExpiredLeases(now))
        assertEquals("QUEUED", row(expiredOperation).status)
        assertNull(row(expiredOperation).leaseOwner)

        assertEquals(1, store.claim(operation, "worker-a", now, now + 100L))
        assertEquals(0, store.claim(operation, "worker-b", now, now + 100L))
        assertEquals("UPLOADING", row(operation).status)
        assertEquals("worker-a", row(operation).leaseOwner)
        assertEquals(1, row(operation).attemptCount)
        assertEquals(now, row(operation).startedAtMs)

        assertEquals(1, store.heartbeat(operation, "worker-a", now + 10L, now + 110L))
        assertEquals(1, store.updateTransferState(
            operationId = operation,
            leaseOwner = "worker-a",
            remoteFileId = "remote-id",
            resumableSessionUri = "https://upload.example/session",
            bytesCommitted = 512L
        ))
        assertEquals("remote-id", row(operation).remoteFileId)
        assertEquals("https://upload.example/session", row(operation).resumableSessionUri)
        assertEquals(512L, row(operation).resumableBytesCommitted)
        assertEquals(now + 10L, row(operation).heartbeatAtMs)

        assertEquals(1, store.markCompleted(operation, "worker-a", now + 20L))
        assertEquals("SYNCED", row(operation).status)
        assertNull(row(operation).leaseOwner)
        assertEquals(now + 20L, row(operation).completedAtMs)

        assertEquals(1, store.claim(failureOperation, "worker-f", now, now + 100L))
        assertEquals(1, store.markFailed(
            operationId = failureOperation,
            leaseOwner = "worker-f",
            status = "FAILED",
            errorCode = "NETWORK_UNAVAILABLE",
            nowMs = now + 30L
        ))
        assertEquals("FAILED", row(failureOperation).status)
        assertEquals("NETWORK_UNAVAILABLE", row(failureOperation).lastErrorCode)
        assertTrue(row(failureOperation).completedAtMs > 0L)
    }

    private suspend fun insertCloudItem(
        operationId: String,
        status: String,
        leaseOwner: String? = null,
        leaseExpiresAtMs: Long = 0L
    ) {
        fileDao.insertCloudSyncItem(
            CloudSyncItemEntity(
                provider = "GOOGLE_DRIVE",
                fileName = "$operationId.txt",
                filePath = "/sync/$operationId.txt",
                fileSize = 12L,
                status = status,
                operationId = operationId,
                leaseOwner = leaseOwner,
                leaseExpiresAtMs = leaseExpiresAtMs
            )
        )
    }

    private suspend fun row(operationId: String): CloudSyncItemEntity =
        fileDao.getCloudSyncItems().first().single { it.operationId == operationId }
}
