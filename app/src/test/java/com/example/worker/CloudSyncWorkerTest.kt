package com.example.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.CategoryStat
import com.example.data.CloudSyncItemEntity
import com.example.data.CloudSyncOperationStore
import com.example.data.FileDao
import com.example.data.FileItemEntity
import com.example.data.GoogleAuthManager
import com.example.data.PluginEntity
import com.example.data.VaultItemEntity
import com.example.data.CloudProviderAdapter
import com.example.data.CloudSyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

class FakeFileDao : FileDao {
    val cloudSyncItems = mutableListOf<CloudSyncItemEntity>()
    var exceptionOnCloudSyncItems: Exception? = null

    override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> {
        exceptionOnCloudSyncItems?.let { throw it }
        return flowOf(cloudSyncItems)
    }

    private var autoSyncId = 1000L

    override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long {
        val assignedId = if (item.id == 0L) autoSyncId++ else item.id
        val newItem = item.copy(id = assignedId)
        val index = cloudSyncItems.indexOfFirst { it.id == assignedId }
        if (index != -1) {
            cloudSyncItems[index] = newItem
        } else {
            cloudSyncItems.add(newItem)
        }
        return assignedId
    }

    override suspend fun deleteCloudSyncItem(id: Long) {
        cloudSyncItems.removeAll { it.id == id }
    }

    // Dummy overrides for FileDao interface
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
    override suspend fun getUnhashedFiles(): List<FileItemEntity> = emptyList()
    override suspend fun updateFiles(files: List<FileItemEntity>) {}
    override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? = null
    override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>) {}
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
    val pluginsList = mutableListOf<PluginEntity>()

    override fun getAllPlugins(): Flow<List<PluginEntity>> = flowOf(pluginsList)
    override suspend fun setPluginEnabled(id: String, enabled: Boolean) {
        val idx = pluginsList.indexOfFirst { it.pluginId == id }
        if (idx != -1) {
            pluginsList[idx] = pluginsList[idx].copy(isEnabled = enabled)
        }
    }
    override suspend fun insertPlugins(plugins: List<PluginEntity>) {
        plugins.forEach { plugin ->
            val idx = pluginsList.indexOfFirst { it.pluginId == plugin.pluginId }
            if (idx != -1) {
                pluginsList[idx] = plugin
            } else {
                pluginsList.add(plugin)
            }
        }
    }
}

class FakeCloudProviderAdapter : CloudProviderAdapter {
    override val providerId: String = "GOOGLE_DRIVE"
    var shouldFail: Boolean = false
    var isRetryable: Boolean = true
    var returnNotSupported: Boolean = false
    var exceptionToThrow: Exception? = null
    val resultByRemotePath = mutableMapOf<String, CloudSyncResult>()

    override suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult {
        resultByRemotePath[remotePath]?.let { return it }
        exceptionToThrow?.let { throw it }
        return when {
            returnNotSupported -> CloudSyncResult.NotSupported
            shouldFail -> CloudSyncResult.Error(
                message = "Upload failed",
                isRetryable = isRetryable
            )
            else -> CloudSyncResult.Success(bytesTransferred = file.length())
        }
    }

    override suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult {
        return CloudSyncResult.NotSupported
    }
}

private class FakeCloudSyncOperationStore(
    private val items: MutableList<CloudSyncItemEntity>
) : CloudSyncOperationStore {
    private fun indexFor(operationId: String): Int = items.indexOfFirst {
        it.operationId == operationId || (it.operationId.isBlank() && operationId == "legacy-${it.id}")
    }

    override suspend fun releaseExpiredLeases(nowMs: Long): Int {
        var released = 0
        items.forEachIndexed { index, item ->
            if (item.status == "UPLOADING" && (item.leaseExpiresAtMs == 0L || item.leaseExpiresAtMs <= nowMs)) {
                items[index] = item.copy(status = "QUEUED", leaseOwner = null, leaseExpiresAtMs = 0L, heartbeatAtMs = 0L)
                released++
            }
        }
        return released
    }

    override suspend fun claim(operationId: String, leaseOwner: String, nowMs: Long, leaseExpiresAtMs: Long): Int {
        val index = indexFor(operationId)
        return if (index < 0) {
            0
        } else {
            val item = items[index]
            if (
                item.status !in setOf("PENDING", "QUEUED") ||
                (item.leaseOwner != null && item.leaseExpiresAtMs > nowMs)
            ) {
                0
            } else {
                items[index] = item.copy(
                    operationId = operationId,
                    status = "UPLOADING",
                    leaseOwner = leaseOwner,
                    leaseExpiresAtMs = leaseExpiresAtMs,
                    heartbeatAtMs = nowMs,
                    startedAtMs = if (item.startedAtMs == 0L) nowMs else item.startedAtMs,
                    attemptCount = item.attemptCount + 1
                )
                1
            }
        }
    }

    override suspend fun heartbeat(operationId: String, leaseOwner: String, nowMs: Long, leaseExpiresAtMs: Long): Int {
        val index = indexFor(operationId)
        if (index < 0 || items[index].leaseOwner != leaseOwner || items[index].status != "UPLOADING") return 0
        items[index] = items[index].copy(heartbeatAtMs = nowMs, leaseExpiresAtMs = leaseExpiresAtMs)
        return 1
    }

    override suspend fun markCompleted(operationId: String, leaseOwner: String, nowMs: Long): Int {
        val index = indexFor(operationId)
        if (index < 0 || items[index].leaseOwner != leaseOwner || items[index].status != "UPLOADING") return 0
        items[index] = items[index].copy(
            status = "SYNCED",
            lastSyncedMs = nowMs,
            heartbeatAtMs = nowMs,
            completedAtMs = nowMs,
            leaseOwner = null,
            leaseExpiresAtMs = 0L,
            lastErrorCode = null
        )
        return 1
    }

    override suspend fun updateTransferState(
        operationId: String,
        leaseOwner: String,
        remoteFileId: String,
        resumableSessionUri: String,
        bytesCommitted: Long
    ): Int {
        val index = indexFor(operationId)
        if (index < 0 || items[index].leaseOwner != leaseOwner || items[index].status != "UPLOADING") return 0
        items[index] = items[index].copy(
            remoteFileId = remoteFileId.ifBlank { items[index].remoteFileId },
            resumableSessionUri = resumableSessionUri.ifBlank { items[index].resumableSessionUri },
            resumableBytesCommitted = bytesCommitted
        )
        return 1
    }

    override suspend fun markFailed(
        operationId: String,
        leaseOwner: String,
        status: String,
        errorCode: String?,
        nowMs: Long
    ): Int {
        val index = indexFor(operationId)
        if (index < 0 || items[index].leaseOwner != leaseOwner || items[index].status != "UPLOADING") return 0
        items[index] = items[index].copy(
            status = status,
            heartbeatAtMs = nowMs,
            completedAtMs = if (status == "FAILED") nowMs else 0L,
            leaseOwner = null,
            leaseExpiresAtMs = 0L,
            lastErrorCode = errorCode
        )
        return 1
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var fakeDao: FakeFileDao
    private lateinit var fakeAdapter: FakeCloudProviderAdapter
    private lateinit var fakeOperationStore: FakeCloudSyncOperationStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        fakeDao = FakeFileDao()
        fakeDao.pluginsList += PluginEntity(
            "gdrive_sync",
            "Google Drive Cloud Plugin",
            "CLOUD_PROVIDER",
            "Test-only enabled provider",
            isEnabled = true,
            isCore = false
        )
        fakeAdapter = FakeCloudProviderAdapter()
        fakeOperationStore = FakeCloudSyncOperationStore(fakeDao.cloudSyncItems)
    }

    @After
    fun tearDown() {
    }

    private fun createWorker(
        runAttemptCount: Int = 0,
        transferAllowed: () -> Boolean = { true }
    ): CloudSyncWorker {
        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker {
                return CloudSyncWorker(
                    appContext,
                    workerParameters,
                    daoOverride = fakeDao,
                    operationStoreOverride = fakeOperationStore,
                    providerAdapterOverride = fakeAdapter,
                    authManagerOverride = GoogleAuthManager(
                        appContext.getSharedPreferences("cloud_sync_test_auth", Context.MODE_PRIVATE)
                    ),
                    transferAllowed = transferAllowed
                )
            }
        }

        return TestListenableWorkerBuilder<CloudSyncWorker>(context)
            .setWorkerFactory(workerFactory)
            .setRunAttemptCount(runAttemptCount)
            .build()
    }

    @Test
    fun transferDenied_failsClosedWithoutTouchingQueue() = runBlocking {
        val queued = CloudSyncItemEntity(
            id = 99L,
            provider = "GOOGLE_DRIVE",
            fileName = "private.txt",
            filePath = "/private.txt",
            fileSize = 1L,
            status = "QUEUED"
        )
        fakeDao.insertCloudSyncItem(queued)
        val worker = createWorker(transferAllowed = { false })
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("QUEUED", fakeDao.getCloudSyncItems().first().single().status)
    }

    @Test
    fun testEmptyQueue_returnsSuccessWithoutChangingItems() = runBlocking {
        val worker = createWorker(runAttemptCount = 0)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(emptyList<CloudSyncItemEntity>(), fakeDao.getCloudSyncItems().first())
    }

    @Test
    fun testSuccessfulUpload_updatesStatusToSyncedAndReturnsSuccess() = runBlocking {
        val tempFile = File.createTempFile("sync_test_success", ".txt")
        tempFile.writeText("sample data for upload")
        tempFile.deleteOnExit()

        val syncItem = CloudSyncItemEntity(
            id = 101L,
            provider = "GOOGLE_DRIVE",
            fileName = tempFile.name,
            filePath = tempFile.absolutePath,
            fileSize = tempFile.length(),
            status = "PENDING",
            lastSyncedMs = 0L,
            isCore = false
        )
        fakeDao.insertCloudSyncItem(syncItem)

        fakeAdapter.shouldFail = false
        fakeAdapter.exceptionToThrow = null
        fakeAdapter.resultByRemotePath[tempFile.name] = CloudSyncResult.Success(
            bytesTransferred = tempFile.length(),
            remoteFileId = "remote-101",
            resumableSessionUri = "session-101",
            bytesCommitted = tempFile.length()
        )

        val worker = createWorker(runAttemptCount = 0)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)

        val itemsInDb = fakeDao.getCloudSyncItems().first()
        val updatedItem = itemsInDb.find { it.id == 101L }
        assertEquals("SYNCED", updatedItem?.status)
        assertTrue(!updatedItem?.operationId.isNullOrBlank())
        assertEquals(1, updatedItem?.attemptCount)
        assertEquals(null, updatedItem?.leaseOwner)
        assertTrue((updatedItem?.startedAtMs ?: 0L) > 0L)
        assertTrue((updatedItem?.heartbeatAtMs ?: 0L) > 0L)
        assertTrue((updatedItem?.completedAtMs ?: 0L) > 0L)
        assertEquals("remote-101", updatedItem?.remoteFileId)
        assertEquals("session-101", updatedItem?.resumableSessionUri)
        assertEquals(tempFile.length(), updatedItem?.resumableBytesCommitted)
    }

    @Test
    fun testNetworkFailure_releasesLeaseAndRequeuesWithStableOperationState() = runBlocking {
        val tempFile = File.createTempFile("sync_test_failure", ".txt")
        tempFile.writeText("sample data for upload")
        tempFile.deleteOnExit()

        val syncItem = CloudSyncItemEntity(
            id = 102L,
            provider = "GOOGLE_DRIVE",
            fileName = tempFile.name,
            filePath = tempFile.absolutePath,
            fileSize = tempFile.length(),
            status = "PENDING",
            lastSyncedMs = 0L,
            isCore = false
        )
        fakeDao.insertCloudSyncItem(syncItem)

        fakeAdapter.exceptionToThrow = UnknownHostException("Network connection dropped")

        val worker = createWorker(runAttemptCount = 0)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)

        val itemsInDb = fakeDao.getCloudSyncItems().first()
        val updatedItem = itemsInDb.find { it.id == 102L }
        assertEquals("QUEUED", updatedItem?.status)
        assertTrue(!updatedItem?.operationId.isNullOrBlank())
        assertEquals(1, updatedItem?.attemptCount)
        assertEquals(null, updatedItem?.leaseOwner)
        assertEquals(0L, updatedItem?.completedAtMs)
        assertEquals("NETWORK_UNAVAILABLE", updatedItem?.lastErrorCode)
    }

    @Test
    fun testNetworkFailureWithMaxRunAttempts_returnsFailure() = runBlocking {
        val tempFile = File.createTempFile("sync_test_max_retry", ".txt")
        tempFile.writeText("sample data for upload")
        tempFile.deleteOnExit()

        val syncItem = CloudSyncItemEntity(
            id = 103L,
            provider = "GOOGLE_DRIVE",
            fileName = tempFile.name,
            filePath = tempFile.absolutePath,
            fileSize = tempFile.length(),
            status = "PENDING",
            lastSyncedMs = 0L,
            isCore = false
        )
        fakeDao.insertCloudSyncItem(syncItem)

        fakeAdapter.exceptionToThrow = UnknownHostException("Network connection dropped")

        val worker = createWorker(runAttemptCount = 3)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun testDisabledPluginProvider_skipsSyncAndReturnsSuccess() = runBlocking {
        val tempFile = File.createTempFile("sync_test_disabled_plugin", ".txt")
        tempFile.writeText("sample data for upload")
        tempFile.deleteOnExit()

        // Insert a disabled Dropbox plugin
        fakeDao.insertPlugins(
            listOf(
                PluginEntity("dropbox_sync", "Dropbox Cloud Plugin", "CLOUD_PROVIDER", "Dropbox Cloud integration", isEnabled = false, isCore = false)
            )
        )

        val syncItem = CloudSyncItemEntity(
            id = 104L,
            provider = "DROPBOX",
            fileName = tempFile.name,
            filePath = tempFile.absolutePath,
            fileSize = tempFile.length(),
            status = "PENDING",
            lastSyncedMs = 0L,
            isCore = false
        )
        fakeDao.insertCloudSyncItem(syncItem)

        val worker = createWorker(runAttemptCount = 0)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)

        val itemsInDb = fakeDao.getCloudSyncItems().first()
        val itemInDb = itemsInDb.find { it.id == 104L }
        // Item status should remain PENDING because it was skipped
        assertEquals("PENDING", itemInDb?.status)
    }

    @Test
    fun testHttp4xxFailure_returnsFailureWithoutRetry() = runBlocking {
        val tempFile = File.createTempFile("sync_test_404", ".txt")
        tempFile.writeText("sample data for upload")
        tempFile.deleteOnExit()

        val syncItem = CloudSyncItemEntity(
            id = 105L,
            provider = "GOOGLE_DRIVE",
            fileName = tempFile.name,
            filePath = tempFile.absolutePath,
            fileSize = tempFile.length(),
            status = "PENDING",
            lastSyncedMs = 0L,
            isCore = false
        )
        fakeDao.insertCloudSyncItem(syncItem)

        fakeAdapter.shouldFail = true
        fakeAdapter.isRetryable = false

        val worker = createWorker(runAttemptCount = 0)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)

        val itemsInDb = fakeDao.getCloudSyncItems().first()
        val itemInDb = itemsInDb.find { it.id == 105L }
        assertEquals("FAILED", itemInDb?.status)
    }

    @Test
    fun testProviderNotSupported_updatesStatusAndReturnsFailure() = runBlocking {
        val tempFile = File.createTempFile("sync_test_unsupported", ".txt")
        tempFile.writeText("sample data for upload")
        tempFile.deleteOnExit()

        val syncItem = CloudSyncItemEntity(
            id = 107L,
            provider = "GOOGLE_DRIVE",
            fileName = tempFile.name,
            filePath = tempFile.absolutePath,
            fileSize = tempFile.length(),
            status = "QUEUED",
            lastSyncedMs = 0L,
            isCore = false
        )
        fakeDao.insertCloudSyncItem(syncItem)
        fakeAdapter.returnNotSupported = true

        val result = createWorker(runAttemptCount = 0).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(
            "FAILED",
            fakeDao.getCloudSyncItems().first().find { it.id == 107L }?.status
        )
    }

    private fun createTempSyncFile(prefix: String, content: String): File =
        File.createTempFile(prefix, ".txt").also {
            it.writeText(content)
            it.deleteOnExit()
        }

    private suspend fun insertSyncItem(id: Long, provider: String, file: File, status: String): Unit {
        fakeDao.insertCloudSyncItem(
            CloudSyncItemEntity(
                id,
                provider,
                file.name,
                file.absolutePath,
                file.length(),
                status
            )
        )
    }

    @Test
    fun testMixedStatuses_reprocessesFailedAndUploadingOnlyForEnabledProvider() = runBlocking {
        val failedFile = createTempSyncFile("sync_mixed_failed", "failed item")
        val uploadingFile = createTempSyncFile("sync_mixed_uploading", "uploading item")
        val alreadySyncedFile = createTempSyncFile("sync_mixed_synced", "synced item")
        val disabledProviderFile = createTempSyncFile("sync_mixed_disabled", "disabled provider item")
        insertSyncItem(201L, "GOOGLE_DRIVE", failedFile, "FAILED")
        insertSyncItem(202L, "GOOGLE_DRIVE", uploadingFile, "UPLOADING")
        insertSyncItem(203L, "GOOGLE_DRIVE", alreadySyncedFile, "SYNCED")
        insertSyncItem(204L, "DROPBOX", disabledProviderFile, "PENDING")

        val result = createWorker(runAttemptCount = 0).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val items = fakeDao.getCloudSyncItems().first().associateBy { it.id }
        assertEquals("FAILED", items.getValue(201L).status)
        assertEquals("SYNCED", items.getValue(202L).status)
        assertEquals("SYNCED", items.getValue(203L).status)
        assertEquals("PENDING", items.getValue(204L).status)
    }

    @Test
    fun testMixedPermanentAndRetryableFailures_retryThenStopsAtMaxAttempts() = runBlocking {
        val permanentFile = File.createTempFile("sync_mixed_permanent", ".txt").also {
            it.writeText("permanent failure")
            it.deleteOnExit()
        }
        val retryableFile = File.createTempFile("sync_mixed_retryable", ".txt").also {
            it.writeText("retryable failure")
            it.deleteOnExit()
        }
        fakeDao.insertCloudSyncItem(
            CloudSyncItemEntity(
                205L,
                "GOOGLE_DRIVE",
                permanentFile.name,
                permanentFile.absolutePath,
                permanentFile.length(),
                "PENDING"
            )
        )
        fakeDao.insertCloudSyncItem(
            CloudSyncItemEntity(
                206L,
                "GOOGLE_DRIVE",
                retryableFile.name,
                retryableFile.absolutePath,
                retryableFile.length(),
                "PENDING"
            )
        )
        fakeAdapter.resultByRemotePath[permanentFile.name] = CloudSyncResult.Error("permanent", isRetryable = false)
        fakeAdapter.resultByRemotePath[retryableFile.name] = CloudSyncResult.Error("temporary", isRetryable = true)

        assertEquals(ListenableWorker.Result.retry(), createWorker(runAttemptCount = 0).doWork())
        assertEquals(ListenableWorker.Result.failure(), createWorker(runAttemptCount = 3).doWork())
        fakeDao.getCloudSyncItems().first().forEach { item ->
            if (item.id == 205L || item.id == 206L) assertEquals("FAILED", item.status)
        }
    }

    @Test
    fun testFatalDaoFailure_returnsFailure() = runBlocking {
        fakeDao.exceptionOnCloudSyncItems = IllegalStateException("database unavailable")

        val result = createWorker(runAttemptCount = 0).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun testMissingLocalFile_returnsFailureWithoutRetry() = runBlocking {
        val nonExistentFile = File(context.cacheDir, "non_existent_file_${System.currentTimeMillis()}.txt")

        val syncItem = CloudSyncItemEntity(
            id = 106L,
            provider = "GOOGLE_DRIVE",
            fileName = nonExistentFile.name,
            filePath = nonExistentFile.absolutePath,
            fileSize = 100L,
            status = "PENDING",
            lastSyncedMs = 0L,
            isCore = false
        )
        fakeDao.insertCloudSyncItem(syncItem)

        val worker = createWorker(runAttemptCount = 0)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)

        val itemsInDb = fakeDao.getCloudSyncItems().first()
        val itemInDb = itemsInDb.find { it.id == 106L }
        assertEquals("FAILED", itemInDb?.status)
    }
}
