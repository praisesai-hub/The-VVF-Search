package com.example.data

import android.content.Context
import com.example.domain.WorkCoordinator
import com.example.worker.FakeFileDao
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmartManagerRepositoryCloudQueuePlainJvmTest {
    private lateinit var context: Context
    private lateinit var dao: FakeFileDao
    private lateinit var workCoordinator: WorkCoordinator
    private lateinit var repository: SmartManagerRepository

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        dao = FakeFileDao()
        workCoordinator = mockk(relaxed = true)
        runBlocking {
            dao.insertPlugins(
                listOf(
                    PluginEntity(
                        pluginId = "gdrive_sync",
                        name = "Google Drive",
                        category = "CLOUD_PROVIDER",
                        description = "Google Drive sync",
                        isEnabled = true,
                        isCore = true,
                    )
                )
            )
        }
        repository = SmartManagerRepository(
            context = context,
            dao = dao,
            workCoordinatorOverride = workCoordinator,
        )
    }

    @Test
    fun enqueue_isDeniedByDefaultEvenWhenProviderIsEnabled(): Unit = runBlocking {
        assertFalse(repository.enqueueCloudSyncItem("GOOGLE_DRIVE", "private.pdf", 10L, "/private/private.pdf"))
        assertNull(repository.observeCloudSyncItems().first().find { it.fileName == "private.pdf" })
    }

    @Test
    fun enqueue_rejectsUnknownAndDisabledProviders(): Unit = runBlocking {
        assertFalse(repository.enqueueCloudSyncItem("UNKNOWN", "unknown.pdf", 1L))

        dao.setPluginEnabled("gdrive_sync", false)
        assertFalse(repository.enqueueCloudSyncItem("GOOGLE_DRIVE", "disabled.pdf", 1L))
        assertTrue(repository.observeCloudSyncItems().first().isEmpty())
    }

    @Test
    fun authorizedQueue_supportsEnqueueRetryAndCancel(): Unit = runBlocking {
        val authorized = SmartManagerRepository(
            context = context,
            dao = dao,
            cloudTransferAllowed = { true },
            workCoordinatorOverride = workCoordinator,
        )

        assertTrue(authorized.enqueueCloudSyncItem("GOOGLE_DRIVE", "queued.pdf", 12L, "/private/queued.pdf"))
        val queued = authorized.observeCloudSyncItems().first().single()
        assertEquals("QUEUED", queued.status)
        assertEquals("GOOGLE_DRIVE", queued.provider)

        assertTrue(authorized.retryCloudSyncItem(queued.id))
        assertEquals("QUEUED", authorized.observeCloudSyncItems().first().single().status)
        assertTrue(authorized.cancelCloudSyncItem(queued.id))
        assertTrue(authorized.observeCloudSyncItems().first().isEmpty())
    }

    @Test
    fun retryAndCancel_ignoreMissingAndTerminalItems(): Unit = runBlocking {
        val authorized = SmartManagerRepository(
            context = context,
            dao = dao,
            cloudTransferAllowed = { true },
            workCoordinatorOverride = workCoordinator,
        )
        val syncedId = dao.insertCloudSyncItem(
            CloudSyncItemEntity(
                provider = "GOOGLE_DRIVE",
                fileName = "synced.pdf",
                fileSize = 4L,
                status = "SYNCED",
                operationId = "synced-operation",
            )
        )

        assertFalse(authorized.retryCloudSyncItem(999L))
        assertFalse(authorized.cancelCloudSyncItem(999L))
        assertFalse(authorized.retryCloudSyncItem(syncedId))
        assertFalse(authorized.cancelCloudSyncItem(syncedId))
        assertEquals("SYNCED", authorized.observeCloudSyncItems().first().single().status)
    }
}
