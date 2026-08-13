package com.example.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.CategoryStat
import com.example.data.CloudSyncItemEntity
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

class FakeFileDao : FileDao {
    val cloudSyncItems = mutableListOf<CloudSyncItemEntity>()

    override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> {
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
    var exceptionToThrow: Exception? = null

    override suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult {
        exceptionToThrow?.let { throw it }
        if (shouldFail) {
            return CloudSyncResult.Error(
                message = "Upload failed",
                isRetryable = isRetryable
            )
        }
        return CloudSyncResult.Success(bytesTransferred = file.length())
    }

    override suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult {
        return CloudSyncResult.NotSupported
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var fakeDao: FakeFileDao
    private lateinit var fakeAdapter: FakeCloudProviderAdapter

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
    }

    @After
    fun tearDown() {
    }

    private fun createWorker(runAttemptCount: Int = 0): CloudSyncWorker {
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
                    providerAdapterOverride = fakeAdapter,
                    authManagerOverride = GoogleAuthManager(
                        appContext.getSharedPreferences("cloud_sync_test_auth", Context.MODE_PRIVATE)
                    )
                )
            }
        }

        return TestListenableWorkerBuilder<CloudSyncWorker>(context)
            .setWorkerFactory(workerFactory)
            .setRunAttemptCount(runAttemptCount)
            .build()
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

        val worker = createWorker(runAttemptCount = 0)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)

        val itemsInDb = fakeDao.getCloudSyncItems().first()
        val updatedItem = itemsInDb.find { it.id == 101L }
        assertEquals("SYNCED", updatedItem?.status)
    }

    @Test
    fun testNetworkFailure_updatesStatusToFailedAndReturnsRetry() = runBlocking {
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

        fakeAdapter.exceptionToThrow = IOException("Network connection dropped")

        val worker = createWorker(runAttemptCount = 0)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)

        val itemsInDb = fakeDao.getCloudSyncItems().first()
        val updatedItem = itemsInDb.find { it.id == 102L }
        assertEquals("FAILED", updatedItem?.status)
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

        fakeAdapter.exceptionToThrow = IOException("Network connection dropped")

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
