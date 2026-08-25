package com.example.data

import android.content.Context
import com.example.context.drive.DriveAuthorizationPort
import com.example.domain.error.DiagnosticLogger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingCloudProviderAdapter : CloudProviderAdapter {
    override val providerId: String = "TEST_PROVIDER"
    var uploadedFile: File? = null
    var uploadedRemotePath: String? = null
    var uploadedOperationId: String? = null
    var result: CloudSyncResult = CloudSyncResult.Success()
    var exceptionToThrow: Exception? = null

    override suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult {
        uploadedFile = file
        uploadedRemotePath = remotePath
        exceptionToThrow?.let { throw it }
        return result
    }

    override suspend fun uploadFile(file: File, remotePath: String, operationId: String): CloudSyncResult {
        uploadedOperationId = operationId
        return uploadFile(file, remotePath)
    }

    override suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult =
        CloudSyncResult.NotSupported
}

class CloudSyncEngineTest {
    private val context: Context = mockk(relaxed = true)
    private val authManager: DriveAuthorizationPort = object : DriveAuthorizationPort {
        override fun authorizationHeader(): String? = null

        override fun isAuthorized(): Boolean = false
    }
    private val temporaryFiles = mutableListOf<File>()

    @Before
    fun setUp(): Unit {
        mockkObject(DiagnosticLogger)
        every { DiagnosticLogger.log(any(), any(), any()) } just Runs
    }

    @After
    fun tearDown(): Unit {
        unmockkObject(DiagnosticLogger)
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
        fileName: String = "remote-name.txt",
        operationId: String = ""
    ): CloudSyncItemEntity = CloudSyncItemEntity(
        id = 41L,
        provider = provider,
        fileName = fileName,
        filePath = file.absolutePath,
        fileSize = file.length(),
        status = "PENDING",
        operationId = operationId
    )

    @Test
    fun missingFile_returnsNonRetryableErrorWithoutAdapterCall(): Unit = runBlocking {
        val missing = File.createTempFile("missing-cloud-sync", ".txt").also {
            it.delete()
            temporaryFiles += it
        }
        val adapter = RecordingCloudProviderAdapter()
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager, adapter)

        val result = engine.syncItem(item(file = missing))

        assertTrue(result is CloudSyncResult.Error)
        result as CloudSyncResult.Error
        assertEquals(false, result.isRetryable)
        assertEquals("The file operation could not be completed.", result.message)
        assertFalse(result.message.contains(missing.absolutePath))
        assertEquals(null, adapter.uploadedFile)
    }

    @Test
    fun unsupportedProvider_withoutOverride_returnsNonRetryableError(): Unit = runBlocking {
        val file = createFile()
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager)

        val result = engine.syncItem(item(provider = "DROPBOX", file = file))

        assertTrue(result is CloudSyncResult.Error)
        result as CloudSyncResult.Error
        assertEquals(false, result.isRetryable)
        assertEquals("The operation could not be completed.", result.message)
        assertFalse(result.message.contains("DROPBOX"))
    }

    @Test
    fun adapterOverride_receivesFileAndRemoteName_andPropagatesSuccess(): Unit = runBlocking {
        val file = createFile()
        val adapter = RecordingCloudProviderAdapter().apply {
            result = CloudSyncResult.Success(bytesTransferred = file.length())
        }
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager, adapter)

        val result = engine.syncItem(item(provider = "DROPBOX", file = file, fileName = "remote.txt"))

        assertSame(adapter.result, result)
        assertEquals(file, adapter.uploadedFile)
        assertEquals("remote.txt", adapter.uploadedRemotePath)
    }

    @Test
    fun adapterOverride_receivesStableOperationIdForIdempotentUpload(): Unit = runBlocking {
        val file = createFile()
        val adapter = RecordingCloudProviderAdapter()
        val engine = CloudSyncEngine(context, FakeFileDaoForCloudSyncEngine(), authManager, adapter)

        engine.syncItem(item(file = file, operationId = "operation-41"))

        assertEquals("operation-41", adapter.uploadedOperationId)
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
        assertEquals("Network connection is unavailable.", result.message)
        assertFalse(result.message.contains("host unavailable"))
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
        assertEquals("Network connection is unavailable.", result.message)
        assertFalse(result.message.contains("storage.example"))
    }
}

private class FakeFileDaoForCloudSyncEngine : FileDao {
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
    override fun getCloudSyncItems() = kotlinx.coroutines.flow.flowOf(emptyList<CloudSyncItemEntity>())
    override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long = 0L
    override suspend fun deleteCloudSyncItem(id: Long) = Unit
    override fun getAllPlugins() = kotlinx.coroutines.flow.flowOf(emptyList<PluginEntity>())
    override suspend fun setPluginEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun insertPlugins(plugins: List<PluginEntity>) = Unit
}
