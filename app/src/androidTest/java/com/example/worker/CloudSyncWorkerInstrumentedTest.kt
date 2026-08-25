package com.example.worker

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.CategoryStat
import com.example.data.CloudProviderAdapter
import com.example.data.CloudSyncItemEntity
import com.example.data.CloudSyncOperationStore
import com.example.data.CloudSyncResult
import com.example.data.FileDao
import com.example.data.FileItemEntity
import com.example.data.GoogleAuthManager
import com.example.data.PluginEntity
import com.example.data.VaultItemEntity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InstrumentedWorkerFileDao : FileDao {
    val cloudSyncItems = mutableListOf<CloudSyncItemEntity>()
    val plugins = mutableListOf<PluginEntity>()
    var exceptionOnCloudSyncItems: Exception? = null

    override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> {
        exceptionOnCloudSyncItems?.let { throw it }
        return flowOf(cloudSyncItems.toList())
    }

    override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long {
        val index = cloudSyncItems.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            cloudSyncItems[index] = item
        } else {
            cloudSyncItems += item
        }
        return item.id
    }

    override suspend fun deleteCloudSyncItem(id: Long): Unit {
        cloudSyncItems.removeAll { it.id == id }
    }

    override fun getAllPlugins(): Flow<List<PluginEntity>> = flowOf(plugins.toList())

    override suspend fun getFileById(id: Long): FileItemEntity? = null
    override suspend fun getFileByName(name: String): FileItemEntity? = null
    override fun getOcrScannedFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun getAllActiveFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun getRecentFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun getCategoryStats(): Flow<List<CategoryStat>> = flowOf(emptyList())
    override suspend fun getFilteredFilesPaged(
        category: String?,
        query: String,
        limit: Int,
        offset: Int
    ): List<FileItemEntity> = emptyList()
    override fun getFilesByCategory(category: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun getRecycleBinFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun getVaultFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun searchFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override suspend fun getUnhashedFiles(): List<FileItemEntity> = emptyList()
    override suspend fun updateFiles(files: List<FileItemEntity>): Unit = Unit
    override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? = null
    override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>): Unit = Unit
    override fun getDuplicateFilesByHash(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override suspend fun insertFile(file: FileItemEntity): Long = 0L
    override suspend fun insertFiles(files: List<FileItemEntity>): Unit = Unit
    override suspend fun updateFile(file: FileItemEntity): Unit = Unit
    override suspend fun getFileByPath(path: String): FileItemEntity? = null
    override suspend fun insertFileDirect(file: FileItemEntity): Long = 0L
    override suspend fun getAllOrdinaryFilesDirect(): List<FileItemEntity> = emptyList()
    override suspend fun deleteFilesByIds(ids: List<Long>): Unit = Unit
    override suspend fun deleteFileById(id: Long): Unit = Unit
    override suspend fun emptyRecycleBin(): Unit = Unit
    override suspend fun getVaultFileByName(name: String): FileItemEntity? = null
    override fun getAllVaultItems(): Flow<List<VaultItemEntity>> = flowOf(emptyList())
    override suspend fun insertVaultItem(item: VaultItemEntity): Long = 0L
    override suspend fun deleteVaultItemById(id: Long): Unit = Unit
    override suspend fun setPluginEnabled(id: String, enabled: Boolean): Unit = Unit
    override suspend fun insertPlugins(plugins: List<PluginEntity>): Unit = Unit
}

private class InstrumentedWorkerOperationStore(
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
        if (index < 0) return 0
        val item = items[index]
        if (item.status !in setOf("PENDING", "QUEUED") ||
            (item.leaseOwner != null && item.leaseExpiresAtMs > nowMs)
        ) return 0
        items[index] = item.copy(
            operationId = operationId,
            status = "UPLOADING",
            leaseOwner = leaseOwner,
            leaseExpiresAtMs = leaseExpiresAtMs,
            heartbeatAtMs = nowMs,
            startedAtMs = if (item.startedAtMs == 0L) nowMs else item.startedAtMs,
            attemptCount = item.attemptCount + 1
        )
        return 1
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
        items[index] = items[index].copy(status = "SYNCED", completedAtMs = nowMs, heartbeatAtMs = nowMs, leaseOwner = null, leaseExpiresAtMs = 0L)
        return 1
    }

    override suspend fun updateTransferState(operationId: String, leaseOwner: String, remoteFileId: String, resumableSessionUri: String, bytesCommitted: Long): Int {
        val index = indexFor(operationId)
        if (index < 0 || items[index].leaseOwner != leaseOwner || items[index].status != "UPLOADING") return 0
        items[index] = items[index].copy(
            remoteFileId = remoteFileId.ifBlank { items[index].remoteFileId },
            resumableSessionUri = resumableSessionUri.ifBlank { items[index].resumableSessionUri },
            resumableBytesCommitted = bytesCommitted
        )
        return 1
    }

    override suspend fun markFailed(operationId: String, leaseOwner: String, status: String, errorCode: String?, nowMs: Long): Int {
        val index = indexFor(operationId)
        if (index < 0 || items[index].leaseOwner != leaseOwner || items[index].status != "UPLOADING") return 0
        items[index] = items[index].copy(status = status, lastErrorCode = errorCode, completedAtMs = if (status == "FAILED") nowMs else 0L, heartbeatAtMs = nowMs, leaseOwner = null, leaseExpiresAtMs = 0L)
        return 1
    }
}

private class InstrumentedWorkerCloudAdapter : CloudProviderAdapter {
    override val providerId: String = "GOOGLE_DRIVE"
    var exceptionToThrow: Exception? = null
    var returnNotSupported: Boolean = false
    val resultByRemotePath = mutableMapOf<String, CloudSyncResult>()

    override suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult {
        exceptionToThrow?.let { throw it }
        if (returnNotSupported) return CloudSyncResult.NotSupported
        return resultByRemotePath[remotePath] ?: CloudSyncResult.Success(file.length())
    }

    override suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult =
        CloudSyncResult.NotSupported
}

class CloudSyncWorkerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var fakeDao: InstrumentedWorkerFileDao
    private lateinit var fakeAdapter: InstrumentedWorkerCloudAdapter
    private lateinit var operationStore: InstrumentedWorkerOperationStore

    @Before
    fun setUp(): Unit {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        fakeDao = InstrumentedWorkerFileDao()
        fakeDao.plugins += PluginEntity(
            "gdrive_sync",
            "Google Drive Cloud Plugin",
            "CLOUD_PROVIDER",
            "Instrumented provider",
            isEnabled = true,
            isCore = false
        )
        fakeAdapter = InstrumentedWorkerCloudAdapter()
        operationStore = InstrumentedWorkerOperationStore(fakeDao.cloudSyncItems)
    }

    private fun createFile(prefix: String, content: String = "cloud sync instrumented data"): File =
        File.createTempFile(prefix, ".txt", context.cacheDir).also {
            it.writeText(content)
        }

    private fun item(
        id: Long,
        file: File,
        provider: String = "GOOGLE_DRIVE",
        status: String = "PENDING"
    ): CloudSyncItemEntity = CloudSyncItemEntity(
        id = id,
        provider = provider,
        fileName = file.name,
        filePath = file.absolutePath,
        fileSize = file.length(),
        status = status,
        lastSyncedMs = 0L,
        isCore = false
    )

    private fun createWorker(runAttemptCount: Int = 0): CloudSyncWorker {
        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = CloudSyncWorker(
                appContext,
                workerParameters,
                daoOverride = fakeDao,
                operationStoreOverride = operationStore,
                providerAdapterOverride = fakeAdapter,
                authManagerOverride = GoogleAuthManager(
                    appContext.getSharedPreferences("cloud_sync_worker_instrumented_auth", Context.MODE_PRIVATE)
                ),
                // Production is default-deny; this fixture explicitly authorizes the
                // operation paths it is designed to test.
                transferAllowed = { true },
            )
        }
        return TestListenableWorkerBuilder<CloudSyncWorker>(context)
            .setWorkerFactory(workerFactory)
            .setRunAttemptCount(runAttemptCount)
            .build()
    }

    @Test
    fun emptyQueue_returnsSuccess(): Unit = runBlocking {
        assertEquals(ListenableWorker.Result.success(), createWorker().doWork())
    }

    @Test
    fun successfulUpload_updatesSyncedStatus(): Unit = runBlocking {
        val file = createFile("worker_success")
        fakeDao.cloudSyncItems += item(301L, file)

        assertEquals(ListenableWorker.Result.success(), createWorker().doWork())
        val syncedItem = fakeDao.getCloudSyncItems().first().single()
        assertEquals("SYNCED", syncedItem.status)
        assertEquals(1, syncedItem.attemptCount)
        assertTrue(syncedItem.startedAtMs > 0L)
        assertTrue(syncedItem.completedAtMs > 0L)
    }

    @Test
    fun mixedStatuses_reprocessesEligibleItemsAndSkipsDisabledProvider(): Unit = runBlocking {
        val failed = createFile("worker_failed")
        val uploading = createFile("worker_uploading")
        val synced = createFile("worker_synced")
        val disabled = createFile("worker_disabled")
        fakeDao.cloudSyncItems += listOf(
            item(302L, failed, status = "QUEUED"),
            item(303L, uploading, status = "UPLOADING"),
            item(304L, synced, status = "SYNCED"),
            item(305L, disabled, provider = "DROPBOX")
        )

        assertEquals(ListenableWorker.Result.success(), createWorker().doWork())
        val statuses = fakeDao.getCloudSyncItems().first().associateBy { it.id }
        assertEquals("SYNCED", statuses.getValue(302L).status)
        assertEquals("SYNCED", statuses.getValue(303L).status)
        assertEquals("SYNCED", statuses.getValue(304L).status)
        assertEquals("PENDING", statuses.getValue(305L).status)
    }

    @Test
    fun retryableFailure_returnsRetryBeforeAttemptLimit(): Unit = runBlocking {
        val file = createFile("worker_retry")
        fakeDao.cloudSyncItems += item(306L, file)
        fakeAdapter.exceptionToThrow = IOException("temporary transfer failure")

        assertEquals(ListenableWorker.Result.retry(), createWorker(runAttemptCount = 0).doWork())
        val retryItem = fakeDao.getCloudSyncItems().first().single()
        assertEquals("QUEUED", retryItem.status)
        assertEquals(1, retryItem.attemptCount)
        assertEquals(null, retryItem.leaseOwner)
        assertEquals(0L, retryItem.completedAtMs)
    }

    @Test
    fun retryableFailure_returnsFailureAtAttemptLimit(): Unit = runBlocking {
        val file = createFile("worker_retry_limit")
        fakeDao.cloudSyncItems += item(307L, file)
        fakeAdapter.exceptionToThrow = IOException("temporary transfer failure")

        assertEquals(ListenableWorker.Result.failure(), createWorker(runAttemptCount = 3).doWork())
    }

    @Test
    fun notSupportedResult_persistsTerminalFailureAndFails(): Unit = runBlocking {
        val file = createFile("worker_unsupported")
        fakeDao.cloudSyncItems += item(308L, file)
        fakeAdapter.returnNotSupported = true

        assertEquals(ListenableWorker.Result.failure(), createWorker().doWork())
        val item = fakeDao.getCloudSyncItems().first().single()
        assertEquals("FAILED", item.status)
        assertEquals("PROVIDER_NOT_SUPPORTED", item.lastErrorCode)
    }

    @Test
    fun mixedPermanentAndRetryableFailures_returnsRetryThenFailure(): Unit = runBlocking {
        val permanent = createFile("worker_permanent")
        val retryable = createFile("worker_mixed_retry")
        fakeDao.cloudSyncItems += listOf(item(309L, permanent), item(310L, retryable))
        fakeAdapter.resultByRemotePath[permanent.name] = CloudSyncResult.Error("permanent", false)
        fakeAdapter.resultByRemotePath[retryable.name] = CloudSyncResult.Error("temporary", true)

        assertEquals(ListenableWorker.Result.retry(), createWorker().doWork())
        assertEquals(ListenableWorker.Result.failure(), createWorker(runAttemptCount = 3).doWork())
    }

    @Test
    fun missingFile_returnsFailureWithoutRetry(): Unit = runBlocking {
        val missing = File(context.cacheDir, "missing-worker-${System.nanoTime()}.txt")
        fakeDao.cloudSyncItems += item(311L, missing)

        assertEquals(ListenableWorker.Result.failure(), createWorker().doWork())
        assertEquals("FAILED", fakeDao.getCloudSyncItems().first().single().status)
    }

    @Test
    fun fatalDaoFailure_returnsFailure(): Unit = runBlocking {
        fakeDao.exceptionOnCloudSyncItems = IllegalStateException("database unavailable")

        assertEquals(ListenableWorker.Result.failure(), createWorker().doWork())
    }
}
