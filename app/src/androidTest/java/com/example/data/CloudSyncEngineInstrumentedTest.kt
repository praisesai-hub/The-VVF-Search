package com.example.data

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.UnknownHostException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InstrumentedCloudProviderAdapter : CloudProviderAdapter {
    override val providerId: String = "TEST_PROVIDER"
    var uploadedFile: File? = null
    var uploadedSource: CloudUploadSource? = null
    var uploadedPayload: ByteArray? = null
    var uploadedRemotePath: String? = null
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
        uploadedPayload = source.openStream().use { it.readBytes() }
        uploadedRemotePath = remotePath
        exceptionToThrow?.let { throw it }
        return result
    }

    override suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult =
        CloudSyncResult.NotSupported
}

class CloudSyncEngineInstrumentedTest {
    private lateinit var context: Context
    private lateinit var authManager: GoogleAuthManager
    private val temporaryFiles = mutableListOf<File>()
    private val temporaryContentUris = mutableListOf<Uri>()

    @Before
    fun setUp(): Unit {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        authManager = GoogleAuthManager(
            context.getSharedPreferences("cloud_sync_engine_instrumented_auth", Context.MODE_PRIVATE)
        )
    }

    @After
    fun tearDown(): Unit {
        temporaryFiles.forEach(File::delete)
        temporaryContentUris.forEach { uri -> context.contentResolver.delete(uri, null, null) }
    }

    private fun createFile(name: String = "sync-engine-test.txt"): File =
        File.createTempFile(name.removeSuffix(".txt"), ".txt", context.cacheDir).also {
            it.writeText("sync engine instrumented payload")
            temporaryFiles += it
        }

    private fun item(
        provider: String = "GOOGLE_DRIVE",
        file: File,
        fileName: String = "remote-name.txt"
    ): CloudSyncItemEntity = CloudSyncItemEntity(
        id = 51L,
        provider = provider,
        fileName = fileName,
        filePath = file.absolutePath,
        fileSize = file.length(),
        status = "PENDING"
    )

    private fun createContentUri(payload: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "saf-sync-${System.nanoTime()}.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "Unable to create app-owned MediaStore source" }
        context.contentResolver.openOutputStream(uri)?.use { output -> output.write(payload) }
            ?: error("Unable to open MediaStore source for writing")
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        temporaryContentUris += uri
        return uri
    }

    @Test
    fun missingFile_returnsNonRetryableErrorWithoutAdapterCall(): Unit = runBlocking {
        val missing = File(context.cacheDir, "missing-${System.nanoTime()}.txt")
        val adapter = InstrumentedCloudProviderAdapter()
        val engine = CloudSyncEngine(context, InstrumentedCloudSyncDao(), authManager, adapter)

        val result = engine.syncItem(item(file = missing))

        assertTrue(result is CloudSyncResult.Error)
        result as CloudSyncResult.Error
        assertEquals(false, result.isRetryable)
        assertTrue(result.message.contains("does not exist"))
        assertNull(adapter.uploadedFile)
    }

    @Test
    fun unsupportedProvider_withoutOverride_returnsNotSupported(): Unit = runBlocking {
        val file = createFile()
        val engine = CloudSyncEngine(context, InstrumentedCloudSyncDao(), authManager)

        val result = engine.syncItem(item(provider = "DROPBOX", file = file))

        assertTrue(result is CloudSyncResult.NotSupported)
    }

    @Test
    fun adapterOverride_receivesFileAndRemoteName_andPropagatesSuccess(): Unit = runBlocking {
        val file = createFile()
        val adapter = InstrumentedCloudProviderAdapter().apply {
            result = CloudSyncResult.Success(bytesTransferred = file.length())
        }
        val engine = CloudSyncEngine(context, InstrumentedCloudSyncDao(), authManager, adapter)

        val result = engine.syncItem(item(provider = "DROPBOX", file = file, fileName = "remote.txt"))

        assertTrue(result is CloudSyncResult.Success)
        result as CloudSyncResult.Success
        assertEquals(file.length(), result.bytesTransferred)
        assertTrue(result.contentHash.isNotBlank())
        assertTrue(result.idempotencyKey.isNotBlank())
        assertEquals(file, adapter.uploadedFile)
        assertEquals("remote.txt", adapter.uploadedRemotePath)
    }

    @Test
    fun contentUri_uploadsByStreamingWithoutFileCoercion(): Unit = runBlocking {
        val payload = "SAF streaming payload".encodeToByteArray()
        val contentUri = createContentUri(payload)
        val adapter = InstrumentedCloudProviderAdapter().apply {
            result = CloudSyncResult.Success(bytesTransferred = payload.size.toLong())
        }
        val sourceItem = CloudSyncItemEntity(
            id = 52L,
            provider = "GOOGLE_DRIVE",
            fileName = "saf-document.txt",
            filePath = contentUri.toString(),
            fileSize = payload.size.toLong(),
            status = "PENDING"
        )
        val engine = CloudSyncEngine(context, InstrumentedCloudSyncDao(), authManager, adapter)

        val result = engine.syncItem(sourceItem)

        assertTrue(result is CloudSyncResult.Success)
        assertTrue(adapter.uploadedSource is CloudUploadSource.ContentUri)
        assertNull(adapter.uploadedFile)
        assertEquals(payload.toList(), adapter.uploadedPayload?.toList())
        result as CloudSyncResult.Success
        assertFalse(result.contentHash.isBlank())
        assertFalse(result.idempotencyKey.isBlank())
    }

    @Test
    fun adapterOverride_propagatesNotSupportedResult(): Unit = runBlocking {
        val file = createFile()
        val adapter = InstrumentedCloudProviderAdapter().apply {
            result = CloudSyncResult.NotSupported
        }
        val engine = CloudSyncEngine(context, InstrumentedCloudSyncDao(), authManager, adapter)

        val result = engine.syncItem(item(file = file))

        assertSame(CloudSyncResult.NotSupported, result)
    }

    @Test
    fun unknownHostException_isMappedToRetryableErrorWithCause(): Unit = runBlocking {
        val file = createFile()
        val failure = UnknownHostException("host unavailable")
        val adapter = InstrumentedCloudProviderAdapter().apply { exceptionToThrow = failure }
        val engine = CloudSyncEngine(context, InstrumentedCloudSyncDao(), authManager, adapter)

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
        val adapter = InstrumentedCloudProviderAdapter().apply {
            exceptionToThrow = IllegalStateException("Unable to resolve host storage.example")
        }
        val engine = CloudSyncEngine(context, InstrumentedCloudSyncDao(), authManager, adapter)

        val result = engine.syncItem(item(file = file))

        assertTrue(result is CloudSyncResult.Error)
        result as CloudSyncResult.Error
        assertEquals(true, result.isRetryable)
        assertEquals("Unable to resolve host storage.example", result.message)
    }
}

private class InstrumentedCloudSyncDao : FileDao {
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
    override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = flowOf(emptyList())
    override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long = 0L
    override suspend fun deleteCloudSyncItem(id: Long): Unit = Unit
    override fun getAllPlugins(): Flow<List<PluginEntity>> = flowOf(emptyList())
    override suspend fun setPluginEnabled(id: String, enabled: Boolean): Unit = Unit
    override suspend fun insertPlugins(plugins: List<PluginEntity>): Unit = Unit
}
