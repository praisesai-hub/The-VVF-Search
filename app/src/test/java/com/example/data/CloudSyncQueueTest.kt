package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.worker.FakeFileDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSyncQueueTest {

    private lateinit var context: Context
    private lateinit var fakeDao: FakeFileDao
    private lateinit var repository: SmartManagerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakeDao = FakeFileDao()
        runBlocking {
            fakeDao.insertPlugins(
                listOf(
                    PluginEntity("gdrive_sync", "Google Drive", "CLOUD_PROVIDER", "Google Drive sync", isEnabled = true, isCore = true)
                )
            )
        }
        repository = SmartManagerRepository(context = context, dao = fakeDao)
    }

    @Test
    fun testEnqueueCloudSyncItem_isRejectedWhenCloudBuildIsNotProvisioned() = runBlocking {
        val result = repository.enqueueCloudSyncItem(
            provider = "GOOGLE_DRIVE",
            fileName = "test_doc.pdf",
            size = 1024L,
            filePath = "/sdcard/test_doc.pdf"
        )

        assertFalse(result)
        assertNull(repository.observeCloudSyncItems().first().find { it.fileName == "test_doc.pdf" })
    }

    @Test
    fun testEnqueueCloudSyncItem_remainsDeniedEvenWhenProviderIsEnabled() = runBlocking {
        val firstResult = repository.enqueueCloudSyncItem(
            provider = "GOOGLE_DRIVE",
            fileName = "duplicate_doc.pdf",
            size = 2048L,
            filePath = "/sdcard/duplicate_doc.pdf"
        )
        assertFalse(firstResult)

        // A second request remains denied and cannot add an active queue item.
        val secondResult = repository.enqueueCloudSyncItem(
            provider = "GOOGLE_DRIVE",
            fileName = "duplicate_doc.pdf",
            size = 2048L,
            filePath = "/sdcard/duplicate_doc.pdf"
        )
        assertFalse(secondResult)

        val items = repository.observeCloudSyncItems().first()
        val duplicateCount = items.count { it.fileName == "duplicate_doc.pdf" }
        assertEquals(0, duplicateCount)
    }

    @Test
    fun testEnqueueCloudSyncItem_missingProviderRejected() = runBlocking {
        val result = repository.enqueueCloudSyncItem(
            provider = "ONEDRIVE",
            fileName = "missing_provider.pdf",
            size = 512L,
            filePath = "/sdcard/missing_provider.pdf"
        )

        assertFalse(result)
        assertNull(repository.observeCloudSyncItems().first().find { it.fileName == "missing_provider.pdf" })
    }

    @Test
    fun testEnqueueCloudSyncItem_disabledProviderRejected() = runBlocking {
        // Disable Google Drive plugin
        fakeDao.insertPlugins(
            listOf(
                PluginEntity("gdrive_sync", "Google Drive", "CLOUD_PROVIDER", "Google Drive sync", isEnabled = false, isCore = true)
            )
        )
        fakeDao.setPluginEnabled("gdrive_sync", false)

        val result = repository.enqueueCloudSyncItem(
            provider = "GOOGLE_DRIVE",
            fileName = "disabled_doc.pdf",
            size = 512L,
            filePath = "/sdcard/disabled_doc.pdf"
        )

        assertFalse(result)
        val items = repository.observeCloudSyncItems().first()
        val disabledItem = items.find { it.fileName == "disabled_doc.pdf" }
        assertNull(disabledItem)
    }

    @Test
    fun testEnqueueCloudSyncItem_authorizedQueuePersistsStableIdentityAndRejectsDuplicate() = runBlocking {
        val authorizedRepository = SmartManagerRepository(
            context = context,
            dao = fakeDao,
            cloudTransferAllowed = { true }
        )
        val path = "content://documents/stable-contract.pdf"

        val first = authorizedRepository.enqueueCloudSyncItem(
            provider = "GOOGLE_DRIVE",
            fileName = "stable-contract.pdf",
            size = 4096L,
            filePath = path,
            isCore = true
        )
        val duplicate = authorizedRepository.enqueueCloudSyncItem(
            provider = "google_drive",
            fileName = "renamed-contract.pdf",
            size = 4096L,
            filePath = path,
            isCore = true
        )

        assertTrue(first)
        assertFalse(duplicate)
        val queued = authorizedRepository.observeCloudSyncItems().first()
        assertEquals(1, queued.size)
        assertEquals("QUEUED", queued.single().status)
        assertEquals(path, queued.single().localFileStableId)
        assertTrue(queued.single().isCore)
    }

    @Test
    fun testRetryCloudSyncItem_failedToQueued() = runBlocking {
        val id = fakeDao.insertCloudSyncItem(
            CloudSyncItemEntity(
                id = 10L,
                provider = "GOOGLE_DRIVE",
                fileName = "failed_doc.pdf",
                filePath = "/sdcard/failed_doc.pdf",
                fileSize = 1024L,
                status = "FAILED",
                lastSyncedMs = 0L
            )
        )

        val authorizedRepository = SmartManagerRepository(
            context = context,
            dao = fakeDao,
            cloudTransferAllowed = { true },
        )
        val retryResult = authorizedRepository.retryCloudSyncItem(id)
        assertTrue(retryResult)

        val items = repository.observeCloudSyncItems().first()
        val item = items.find { it.id == id }
        assertEquals("QUEUED", item?.status)
    }

    @Test
    fun testRetryCloudSyncItem_syncedItemIgnored() = runBlocking {
        val id = fakeDao.insertCloudSyncItem(
            CloudSyncItemEntity(
                id = 20L,
                provider = "GOOGLE_DRIVE",
                fileName = "synced_doc.pdf",
                filePath = "/sdcard/synced_doc.pdf",
                fileSize = 1024L,
                status = "SYNCED",
                lastSyncedMs = System.currentTimeMillis()
            )
        )

        val retryResult = repository.retryCloudSyncItem(id)
        assertFalse(retryResult)

        val items = repository.observeCloudSyncItems().first()
        val item = items.find { it.id == id }
        assertEquals("SYNCED", item?.status)
    }

    @Test
    fun testCancelCloudSyncItem_queuedRemovedFromDb() = runBlocking {
        val id = fakeDao.insertCloudSyncItem(
            CloudSyncItemEntity(
                id = 30L,
                provider = "GOOGLE_DRIVE",
                fileName = "cancel_doc.pdf",
                filePath = "/sdcard/cancel_doc.pdf",
                fileSize = 1024L,
                status = "QUEUED",
                lastSyncedMs = 0L
            )
        )

        val cancelResult = repository.cancelCloudSyncItem(id)
        assertTrue(cancelResult)

        val items = repository.observeCloudSyncItems().first()
        val item = items.find { it.id == id }
        assertNull(item)
    }

    @Test
    fun testCancelCloudSyncItem_syncedItemIgnored() = runBlocking {
        val id = fakeDao.insertCloudSyncItem(
            CloudSyncItemEntity(
                id = 40L,
                provider = "GOOGLE_DRIVE",
                fileName = "synced_cancel.pdf",
                filePath = "/sdcard/synced_cancel.pdf",
                fileSize = 1024L,
                status = "SYNCED",
                lastSyncedMs = System.currentTimeMillis()
            )
        )

        val cancelResult = repository.cancelCloudSyncItem(id)
        assertFalse(cancelResult)

        val items = repository.observeCloudSyncItems().first()
        val item = items.find { it.id == id }
        assertEquals("SYNCED", item?.status)
    }
}
