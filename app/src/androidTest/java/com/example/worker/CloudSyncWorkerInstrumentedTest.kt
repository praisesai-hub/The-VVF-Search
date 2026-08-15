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
                providerAdapterOverride = fakeAdapter,
                authManagerOverride = GoogleAuthManager(
                    appContext.getSharedPreferences("cloud_sync_worker_instrumented_auth", Context.MODE_PRIVATE)
                )
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
        assertEquals("SYNCED", fakeDao.getCloudSyncItems().first().single().status)
    }

    @Test
    fun mixedStatuses_reprocessesEligibleItemsAndSkipsDisabledProvider(): Unit = runBlocking {
        val failed = createFile("worker_failed")
        val uploading = createFile("worker_uploading")
        val synced = createFile("worker_synced")
        val disabled = createFile("worker_disabled")
        fakeDao.cloudSyncItems += listOf(
            item(302L, failed, status = "FAILED"),
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
        fakeAdapter.exceptionToThrow = IOException("network unavailable")

        assertEquals(ListenableWorker.Result.retry(), createWorker(runAttemptCount = 0).doWork())
        assertEquals("FAILED", fakeDao.getCloudSyncItems().first().single().status)
    }

    @Test
    fun retryableFailure_returnsFailureAtAttemptLimit(): Unit = runBlocking {
        val file = createFile("worker_retry_limit")
        fakeDao.cloudSyncItems += item(307L, file)
        fakeAdapter.exceptionToThrow = IOException("network unavailable")

        assertEquals(ListenableWorker.Result.failure(), createWorker(runAttemptCount = 3).doWork())
    }

    @Test
    fun notSupportedResult_persistsNotSupportedAndFails(): Unit = runBlocking {
        val file = createFile("worker_unsupported")
        fakeDao.cloudSyncItems += item(308L, file)
        fakeAdapter.returnNotSupported = true

        assertEquals(ListenableWorker.Result.failure(), createWorker().doWork())
        assertEquals("NOT_SUPPORTED", fakeDao.getCloudSyncItems().first().single().status)
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
