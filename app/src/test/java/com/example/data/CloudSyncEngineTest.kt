package com.example.data

import android.content.Context
import java.io.File
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private class RecordingCloudProviderAdapter : CloudProviderAdapter {
    override val providerId: String = "TEST_PROVIDER"
    var uploadedFile: File? = null
    var uploadedSource: CloudUploadSource? = null
    var uploadedRemotePath: String? = null
    var uploadedIdempotencyKey: String? = null
    var uploadedContentHash: String? = null
    var uploadedSessionUri: String? = null
    var downloadedRemoteFileId: String? = null
    var checkpointToEmit: CloudSyncCheckpoint? = null
    var result: CloudSyncResult = CloudSyncResult.Success()
    var exceptionToThrow: Exception? = null

    override suspend fun uploadFile(
        source: CloudUploadSource,
        remotePath: String,
        remoteFileId: String,
        idempotencyKey: String,
        remoteRevisionId: String,
        contentHash: String,
        uploadSessionUri: String,
        etag: String,
        onCheckpoint: suspend (CloudSyncCheckpoint) -> Unit
    ): CloudSyncResult {
        uploadedSource = source
        uploadedFile = (source as? CloudUploadSource.LocalFile)?.asFile()
        uploadedRemotePath = remotePath
        uploadedIdempotencyKey = idempotencyKey
        uploadedContentHash = contentHash
        uploadedSessionUri = uploadSessionUri
        checkpointToEmit?.let { onCheckpoint(it) }
        exceptionToThrow?.let { throw it }
        return result
    }

    override suspend fun downloadFile(remoteFileId: String, destinationFile: File): CloudSyncResult {
        downloadedRemoteFileId = remoteFileId
        return result
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSyncEngineTest {
    private lateinit var context: Context
    private lateinit var authManager: GoogleAuthManager
    private val temporaryFiles = mutableListOf<File>()

    @Before
    fun setUp(): Unit {
        context = RuntimeEnvironment.getApplication()
        authManager = GoogleAuthManager(
            context.getSharedPreferences("cloud_sync_engine_test_auth", Context.MODE_PRIVATE)
        )
    }

    @After
    fun tearDown(): Unit {
        temporaryFiles.forEach(File::delete)
    }

    private fun createFile(name: String = "sync-engine-test.txt"): File =
        File.createTempFile(name.removeSuffix(".txt"), ".txt").also {
            it.writeText("sync engine test payload")
            temporaryFiles += it
        }

    private fun item(
        provider: String = "GOOGLE_DRIVE",
        file: File,
        fileName: String = "remote-name.txt"
    ): CloudSyncItemEntity = CloudSyncItemEntity(
        id = 41L,
        provider = provider,
        fileName = fileName,
        filePath = file.absolutePath,
        fileSize = file.length(),
        status = "PENDING"
    )

    @Test
    fun downloadItem_usesPersistedRemoteIdAndRejectsBlankIds(): Unit = runBlocking {
        val source = createFile()
        val destination = createFile("download-target.txt")
        val adapter = RecordingCloudProviderAdapter().apply {
            result = CloudSyncResult.Success(remoteFileId = "drive-file-101")
        }
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager, adapter)

        val missingIdResult = engine.downloadItem(item(file = source), destination)
        assertTrue(missingIdResult is CloudSyncResult.Error)
        assertTrue((missingIdResult as CloudSyncResult.Error).message.contains("persisted remote file ID"))
        assertEquals(null, adapter.downloadedRemoteFileId)

        val persistedItem = item(file = source).copy(remoteFileId = "drive-file-101")
        val downloaded = engine.downloadItem(persistedItem, destination)
        assertTrue(downloaded is CloudSyncResult.Success)
        assertEquals("drive-file-101", adapter.downloadedRemoteFileId)
    }

    @Test
    fun missingFile_returnsNonRetryableErrorWithoutAdapterCall(): Unit = runBlocking {
        val missing = File(context.cacheDir, "missing-${System.nanoTime()}.txt")
        val adapter = RecordingCloudProviderAdapter()
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager, adapter)

        val result = engine.syncItem(item(file = missing))

        assertTrue(result is CloudSyncResult.Error)
        result as CloudSyncResult.Error
        assertEquals(false, result.isRetryable)
        assertTrue(result.message.contains("missing"))
        assertEquals(null, adapter.uploadedFile)
    }

    @Test
    fun unsupportedProvider_withoutOverride_returnsNotSupported(): Unit = runBlocking {
        val file = createFile()
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager)

        val result = engine.syncItem(item(provider = "DROPBOX", file = file))

        assertTrue(result is CloudSyncResult.NotSupported)
    }

    @Test
    fun adapterOverride_receivesFileAndRemoteName_andPropagatesSuccess(): Unit = runBlocking {
        val file = createFile()
        val adapter = RecordingCloudProviderAdapter().apply {
            result = CloudSyncResult.Success(bytesTransferred = file.length())
        }
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager, adapter)

        val result = engine.syncItem(item(provider = "DROPBOX", file = file, fileName = "remote.txt"))

        assertTrue(result is CloudSyncResult.Success)
        assertEquals(file, adapter.uploadedFile)
        assertEquals("remote.txt", adapter.uploadedRemotePath)
        assertTrue(adapter.uploadedIdempotencyKey?.matches(Regex("[0-9a-f]{64}")) == true)
        assertTrue(adapter.uploadedContentHash?.matches(Regex("[0-9a-f]{64}")) == true)
    }

    @Test
    fun unreadableContentUri_returnsNonRetryableErrorWithoutFileCoercion(): Unit = runBlocking {
        val adapter = RecordingCloudProviderAdapter().apply {
            result = CloudSyncResult.Success(
                bytesTransferred = 12L,
                remoteFileId = "drive-file-42",
                idempotencyKey = "request-key"
            )
        }
        val item = CloudSyncItemEntity(
            id = 42L,
            provider = "GOOGLE_DRIVE",
            fileName = "saf-document.pdf",
            filePath = "content://test.documents/saf-document.pdf",
            fileSize = 12L,
            status = "QUEUED"
        )
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager, adapter)

        val result = engine.syncItem(item)

        assertTrue(result is CloudSyncResult.Error)
        result as CloudSyncResult.Error
        assertEquals(false, result.isRetryable)
        assertEquals(null, adapter.uploadedFile)
        assertEquals(null, adapter.uploadedSource)
    }

    @Test
    fun adapterOverride_propagatesNotSupportedResult(): Unit = runBlocking {
        val file = createFile()
        val adapter = RecordingCloudProviderAdapter().apply {
            result = CloudSyncResult.NotSupported
        }
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager, adapter)

        val result = engine.syncItem(item(file = file))

        assertSame(CloudSyncResult.NotSupported, result)
    }

    @Test
    fun unknownHostException_isMappedToRetryableErrorWithCause(): Unit = runBlocking {
        val file = createFile()
        val failure = UnknownHostException("host unavailable")
        val adapter = RecordingCloudProviderAdapter().apply { exceptionToThrow = failure }
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager, adapter)

        val result = engine.syncItem(item(file = file))

        assertTrue(result is CloudSyncResult.Error)
        result as CloudSyncResult.Error
        assertEquals(true, result.isRetryable)
        assertSame(failure, result.cause)
        assertEquals("host unavailable", result.message)
    }

    @Test
    fun resolveHostMessage_isMappedToRetryableError(): Unit = runBlocking {
        val file = createFile()
        val adapter = RecordingCloudProviderAdapter().apply {
            exceptionToThrow = IllegalStateException("Unable to resolve host storage.example")
        }
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager, adapter)

        val result = engine.syncItem(item(file = file))

        assertTrue(result is CloudSyncResult.Error)
        result as CloudSyncResult.Error
        assertEquals(true, result.isRetryable)
        assertEquals("Unable to resolve host storage.example", result.message)
    }

    @Test
    fun checkpoint_isPersistedBeforeProviderResultForLostResponseRecovery(): Unit = runBlocking {
        val file = createFile()
        val dao = FakeFileDaoForCloudSyncEngine()
        val adapter = RecordingCloudProviderAdapter().apply {
            checkpointToEmit = CloudSyncCheckpoint(
                remoteFileId = "drive-42",
                contentHash = "hash-42",
                uploadSessionUri = "https://upload.example/resumable/42",
                idempotencyKey = "identity-42"
            )
            result = CloudSyncResult.Error(
                message = "response lost",
                isRetryable = true,
                remoteFileId = "drive-42",
                contentHash = "hash-42",
                uploadSessionUri = "https://upload.example/resumable/42",
                idempotencyKey = "identity-42"
            )
        }
        val engine = CloudSyncEngine(context, dao, authManager, adapter)

        val result = engine.syncItem(item(file = file))

        assertTrue(result is CloudSyncResult.Error)
        assertEquals("https://upload.example/resumable/42", dao.lastCloudSyncItem?.uploadSessionUri)
        assertEquals("drive-42", dao.lastCloudSyncItem?.remoteFileId)
        assertEquals("identity-42", dao.lastCloudSyncItem?.idempotencyKey)
    }
}

private class FakeFileDaoForCloudSyncEngine : FileDao {
    var lastCloudSyncItem: CloudSyncItemEntity? = null
    override suspend fun getFileById(id: Long): FileItemEntity? = null
    override suspend fun getFileByName(name: String): FileItemEntity? = null
    override fun getOcrScannedFiles() = kotlinx.coroutines.flow.flowOf(emptyList<FileItemEntity>())
    override fun searchSemanticFiles(query: String) = kotlinx.coroutines.flow.flowOf(emptyList<FileItemEntity>())
    override fun getAllActiveFiles() = kotlinx.coroutines.flow.flowOf(emptyList<FileItemEntity>())
    override fun getRecentFiles() = kotlinx.coroutines.flow.flowOf(emptyList<FileItemEntity>())
    override fun getCategoryStats() = kotlinx.coroutines.flow.flowOf(emptyList<CategoryStat>())
    override suspend fun getFilteredFilesPaged(
        category: String?,
        query: String,
        limit: Int,
        offset: Int
    ) = emptyList<FileItemEntity>()
    override fun getFilesByCategory(category: String) = kotlinx.coroutines.flow.flowOf(emptyList<FileItemEntity>())
    override fun getRecycleBinFiles() = kotlinx.coroutines.flow.flowOf(emptyList<FileItemEntity>())
    override fun getVaultFiles() = kotlinx.coroutines.flow.flowOf(emptyList<FileItemEntity>())
    override fun searchFiles(query: String) = kotlinx.coroutines.flow.flowOf(emptyList<FileItemEntity>())
    override suspend fun getUnhashedFiles() = emptyList<FileItemEntity>()
    override suspend fun updateFiles(files: List<FileItemEntity>) = Unit
    override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? = null
    override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>) = Unit
    override fun getDuplicateFilesByHash() = kotlinx.coroutines.flow.flowOf(emptyList<FileItemEntity>())
    override suspend fun insertFile(file: FileItemEntity): Long = 0L
    override suspend fun insertFiles(files: List<FileItemEntity>) = Unit
    override suspend fun updateFile(file: FileItemEntity) = Unit
    override suspend fun getFileByPath(path: String): FileItemEntity? = null
    override suspend fun insertFileDirect(file: FileItemEntity): Long = 0L
    override suspend fun getAllOrdinaryFilesDirect() = emptyList<FileItemEntity>()
    override suspend fun deleteFilesByIds(ids: List<Long>) = Unit
    override suspend fun deleteFileById(id: Long) = Unit
    override suspend fun emptyRecycleBin() = Unit
    override suspend fun getVaultFileByName(name: String): FileItemEntity? = null
    override fun getAllVaultItems() = kotlinx.coroutines.flow.flowOf(emptyList<VaultItemEntity>())
    override suspend fun insertVaultItem(item: VaultItemEntity): Long = 0L
    override suspend fun deleteVaultItemById(id: Long) = Unit
    override suspend fun getVaultItemByEncryptedPath(path: String): VaultItemEntity? = null
    override suspend fun upsertVaultOperation(operation: VaultOperationEntity) = Unit
    override suspend fun getIncompleteVaultOperations(): List<VaultOperationEntity> = emptyList()
    override fun getCloudSyncItems() = kotlinx.coroutines.flow.flowOf(emptyList<CloudSyncItemEntity>())
    override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long {
        lastCloudSyncItem = item
        return item.id
    }
    override suspend fun deleteCloudSyncItem(id: Long) = Unit
    override fun getAllPlugins() = kotlinx.coroutines.flow.flowOf(emptyList<PluginEntity>())
    override suspend fun setPluginEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun insertPlugins(plugins: List<PluginEntity>) = Unit
}
