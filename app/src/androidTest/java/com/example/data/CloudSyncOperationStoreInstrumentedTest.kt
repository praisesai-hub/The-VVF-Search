package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudSyncOperationStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var store: CloudSyncOperationStore
    private lateinit var fileDao: FileDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = database.cloudSyncOperationStore()
        fileDao = database.fileDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun leaseLifecycle_enforcesOwnerAndPersistsTransferState(): Unit = runBlocking {
        val queued = cloudItem(
            operationId = "cloud-operation-1",
            status = "QUEUED",
            lastErrorCode = "old-error",
            remoteFileId = "old-remote",
            resumableSessionUri = "old-session",
            resumableBytesCommitted = 8L,
        )
        fileDao.insertCloudSyncItem(queued)

        assertEquals(1, store.claim("cloud-operation-1", "worker-a", 100L, 200L))
        assertEquals(0, store.claim("cloud-operation-1", "worker-b", 150L, 250L))

        var current = fileDao.getCloudSyncItems().first().single()
        assertEquals("UPLOADING", current.status)
        assertEquals("worker-a", current.leaseOwner)
        assertEquals(1, current.attemptCount)
        assertEquals(100L, current.startedAtMs)
        assertEquals(100L, current.heartbeatAtMs)

        assertEquals(0, store.heartbeat("cloud-operation-1", "worker-b", 160L, 260L))
        assertEquals(1, store.heartbeat("cloud-operation-1", "worker-a", 170L, 270L))
        assertEquals(1, store.updateTransferState("cloud-operation-1", "worker-a", "remote-1", "session-1", 42L))
        assertEquals(1, store.updateTransferState("cloud-operation-1", "worker-a", "", "", -1L))

        current = fileDao.getCloudSyncItems().first().single()
        assertEquals(170L, current.heartbeatAtMs)
        assertEquals(270L, current.leaseExpiresAtMs)
        assertEquals("remote-1", current.remoteFileId)
        assertEquals("session-1", current.resumableSessionUri)
        assertEquals(42L, current.resumableBytesCommitted)
        assertEquals("old-error", current.lastErrorCode)

        assertEquals(0, store.markCompleted("cloud-operation-1", "worker-b", 300L))
        assertEquals(1, store.markCompleted("cloud-operation-1", "worker-a", 300L))

        current = fileDao.getCloudSyncItems().first().single()
        assertEquals("SYNCED", current.status)
        assertEquals(300L, current.lastSyncedMs)
        assertEquals(300L, current.completedAtMs)
        assertNull(current.leaseOwner)
        assertEquals(0L, current.leaseExpiresAtMs)
        assertNull(current.lastErrorCode)
        assertEquals("remote-1", current.remoteFileId)
        assertEquals(42L, current.resumableBytesCommitted)
    }

    @Test
    fun expiredLeases_areReleasedButActiveLeasesRemainClaimed(): Unit = runBlocking {
        fileDao.insertCloudSyncItem(
            cloudItem(
                operationId = "expired-operation",
                status = "UPLOADING",
                leaseOwner = "old-worker",
                leaseExpiresAtMs = 100L,
                heartbeatAtMs = 90L,
            )
        )
        fileDao.insertCloudSyncItem(
            cloudItem(
                operationId = "active-operation",
                status = "UPLOADING",
                leaseOwner = "active-worker",
                leaseExpiresAtMs = 500L,
                heartbeatAtMs = 200L,
            )
        )

        assertEquals(1, store.releaseExpiredLeases(300L))

        val rows = fileDao.getCloudSyncItems().first().associateBy { it.operationId }
        val expired = rows.getValue("expired-operation")
        assertEquals("QUEUED", expired.status)
        assertNull(expired.leaseOwner)
        assertEquals(0L, expired.leaseExpiresAtMs)
        assertEquals(0L, expired.heartbeatAtMs)

        val active = rows.getValue("active-operation")
        assertEquals("UPLOADING", active.status)
        assertEquals("active-worker", active.leaseOwner)
        assertEquals(500L, active.leaseExpiresAtMs)

        assertEquals(0, store.markFailed("expired-operation", "old-worker", "FAILED", "STALE", 301L))
        assertFalse(rows.getValue("active-operation").status == "QUEUED")
    }

    @Test
    fun failedTransition_clearsLeaseAndRecordsTerminalError(): Unit = runBlocking {
        fileDao.insertCloudSyncItem(
            cloudItem(
                operationId = "failed-operation",
                status = "PENDING",
            )
        )
        assertEquals(1, store.claim("failed-operation", "worker-a", 10L, 20L))
        assertEquals(0, store.markFailed("failed-operation", "worker-b", "FAILED", "NETWORK", 30L))
        assertEquals(1, store.markFailed("failed-operation", "worker-a", "FAILED", "NETWORK", 30L))

        val current = fileDao.getCloudSyncItems().first().single()
        assertEquals("FAILED", current.status)
        assertEquals("NETWORK", current.lastErrorCode)
        assertEquals(30L, current.completedAtMs)
        assertEquals(30L, current.heartbeatAtMs)
        assertNull(current.leaseOwner)
        assertEquals(0L, current.leaseExpiresAtMs)
    }

    private fun cloudItem(
        operationId: String,
        status: String,
        lastErrorCode: String? = null,
        remoteFileId: String = "",
        resumableSessionUri: String = "",
        resumableBytesCommitted: Long = 0L,
        leaseOwner: String? = null,
        leaseExpiresAtMs: Long = 0L,
        heartbeatAtMs: Long = 0L,
    ) = CloudSyncItemEntity(
        provider = "GOOGLE_DRIVE",
        fileName = "$operationId.txt",
        filePath = "/private/$operationId.txt",
        fileSize = 12L,
        status = status,
        lastSyncedMs = 0L,
        operationId = operationId,
        lastErrorCode = lastErrorCode,
        remoteFileId = remoteFileId,
        resumableSessionUri = resumableSessionUri,
        resumableBytesCommitted = resumableBytesCommitted,
        leaseOwner = leaseOwner,
        leaseExpiresAtMs = leaseExpiresAtMs,
        heartbeatAtMs = heartbeatAtMs,
    )
}
