package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSyncOperationStoreTest {
    private lateinit var database: AppDatabase
    private lateinit var fileDao: FileDao
    private lateinit var operationStore: CloudSyncOperationStore
    private val ids = AtomicLong(100L)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fileDao = database.fileDao()
        operationStore = database.cloudSyncOperationStore()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun operationStore_enforcesLeaseAndPersistsDurableTransferState() = runBlocking {
        val operationId = "operation-${ids.getAndIncrement()}"
        fileDao.insertCloudSyncItem(
            CloudSyncItemEntity(
                id = 101L,
                provider = "GOOGLE_DRIVE",
                fileName = "report.pdf",
                filePath = "/tmp/report.pdf",
                fileSize = 10L,
                status = "UPLOADING",
                operationId = operationId,
                leaseOwner = "old-worker",
                leaseExpiresAtMs = 10L,
                heartbeatAtMs = 1L,
            ),
        )

        assertEquals(1, operationStore.releaseExpiredLeases(nowMs = 20L))
        assertEquals(
            1,
            operationStore.claim(
                operationId = operationId,
                leaseOwner = "worker-1",
                nowMs = 30L,
                leaseExpiresAtMs = 130L,
            ),
        )
        assertEquals(
            0,
            operationStore.claim(
                operationId = operationId,
                leaseOwner = "worker-2",
                nowMs = 31L,
                leaseExpiresAtMs = 131L,
            ),
        )
        assertEquals(0, operationStore.heartbeat(operationId, "wrong-worker", 40L, 140L))
        assertEquals(1, operationStore.heartbeat(operationId, "worker-1", 40L, 140L))
        assertEquals(1, operationStore.updateTransferState(operationId, "worker-1", "remote-101", "session-101", 4L))
        assertEquals(1, operationStore.updateTransferState(operationId, "worker-1", "", "", -1L))

        val uploading = fileDao.getCloudSyncItems().first().single()
        assertEquals("UPLOADING", uploading.status)
        assertEquals("remote-101", uploading.remoteFileId)
        assertEquals("session-101", uploading.resumableSessionUri)
        assertEquals(4L, uploading.resumableBytesCommitted)
        assertEquals(140L, uploading.leaseExpiresAtMs)

        assertEquals(0, operationStore.markCompleted(operationId, "wrong-worker", 50L))
        assertEquals(1, operationStore.markCompleted(operationId, "worker-1", 50L))
        val completed = fileDao.getCloudSyncItems().first().single()
        assertEquals("SYNCED", completed.status)
        assertEquals(null, completed.leaseOwner)
        assertEquals(50L, completed.completedAtMs)
    }

    @Test
    fun operationStore_marksRetryableFailureOnlyForCurrentLeaseOwner() = runBlocking {
        val operationId = "operation-${ids.getAndIncrement()}"
        fileDao.insertCloudSyncItem(
            CloudSyncItemEntity(
                id = 102L,
                provider = "GOOGLE_DRIVE",
                fileName = "retry.pdf",
                fileSize = 5L,
                status = "QUEUED",
                operationId = operationId,
            ),
        )

        assertEquals(1, operationStore.claim(operationId, "worker-1", 10L, 110L))
        assertEquals(0, operationStore.markFailed(operationId, "worker-2", "FAILED", "NETWORK", 20L))
        assertEquals(1, operationStore.markFailed(operationId, "worker-1", "FAILED", "NETWORK", 20L))

        val failed = fileDao.getCloudSyncItems().first().single()
        assertEquals("FAILED", failed.status)
        assertEquals("NETWORK", failed.lastErrorCode)
        assertEquals(20L, failed.completedAtMs)
        assertTrue(failed.leaseOwner == null)
    }
}
