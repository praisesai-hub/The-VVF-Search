package com.example.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.context.cloud.CloudProviderRegistry
import com.example.context.drive.DriveAuthorizationPort
import com.example.domain.error.DomainErrorMapper
import com.example.domain.retry.RetryOperation
import com.example.domain.retry.RetryPolicy
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Core engine responsible for orchestrating cloud sync tasks.
 * Delegates the heavy lifting of individual provider protocols to respective [CloudProviderAdapter]s.
 */
class CloudSyncEngine(
    private val context: Context,
    private val dao: FileDao,
    private val authManager: DriveAuthorizationPort,
    private val providerAdapterOverride: CloudProviderAdapter? = null,
    private val operationStore: CloudSyncOperationStore? = null
) {
    private val providerRegistry = CloudProviderRegistry(authManager, providerAdapterOverride)
    companion object {
        private const val TAG = "CloudSyncEngine"
    }

    /**
     * Synchronizes a single [CloudSyncItemEntity].
     * Resolves the appropriate provider, performs the upload/sync, and returns the result.
     */
    suspend fun syncItem(item: CloudSyncItemEntity): CloudSyncResult {
        val adapter = getAdapterForProvider(item.provider)
            ?: run {
                val error = DomainErrorMapper.fromThrowable(
                    operation = "CLOUD_PROVIDER_RESOLUTION",
                    cause = IllegalArgumentException("unsupported provider"),
                    fileId = item.id,
                    provider = item.provider
                )
                return CloudSyncResult.Error(
                    message = error.userMessage.value,
                    isRetryable = false,
                    domainError = error
                )
            }

        val resolvedSource = try {
            resolveUploadSource(item.filePath)
        } catch (e: Exception) {
            val error = DomainErrorMapper.fromThrowable(
                operation = "CLOUD_TRANSFER",
                cause = IOException("source file unavailable"),
                fileId = item.id,
                provider = item.provider
            )
            return CloudSyncResult.Error(
                message = error.userMessage.value,
                isRetryable = false,
                domainError = error
            )
        }
        val file = resolvedSource.file
        if (!file.exists() || !file.isFile) {
            resolvedSource.deleteIfTemporary()
            val error = DomainErrorMapper.fromThrowable(
                operation = "CLOUD_TRANSFER",
                cause = IOException("source file unavailable"),
                fileId = item.id,
                provider = item.provider
            )
            return CloudSyncResult.Error(
                message = error.userMessage.value,
                isRetryable = false,
                domainError = error
            )
        }

        Log.i(TAG, "Starting sync for item ${item.id} (${item.fileName}) with provider ${item.provider}...")
        return try {
            adapter.uploadFile(
                file = file,
                remotePath = item.fileName,
                operationId = item.operationId,
                transferState = CloudTransferState(
                    remoteFileId = item.remoteFileId,
                    resumableSessionUri = item.resumableSessionUri,
                    bytesCommitted = item.resumableBytesCommitted
                )
            ) { progress ->
                val leaseOwner = item.leaseOwner.orEmpty()
                if (leaseOwner.isNotBlank()) {
                    operationStore?.updateTransferState(
                        operationId = item.operationId,
                        leaseOwner = leaseOwner,
                        remoteFileId = progress.remoteFileId.orEmpty(),
                        resumableSessionUri = progress.resumableSessionUri.orEmpty(),
                        bytesCommitted = progress.bytesCommitted
                    )
                }
            }
        } catch (e: Exception) {
            val error = DomainErrorMapper.fromThrowable(
                operation = "CLOUD_TRANSFER",
                cause = e,
                fileId = item.id,
                provider = item.provider
            )
            com.example.domain.error.DiagnosticLogger.log(TAG, error)
            CloudSyncResult.Error(
                message = error.userMessage.value,
                isRetryable = isExceptionRetryable(e),
                cause = e,
                domainError = error
            )
        } finally {
            resolvedSource.deleteIfTemporary()
        }
    }

    /**
     * Resolves the correct [CloudProviderAdapter] based on the provider string.
     */
    private fun getAdapterForProvider(provider: String): CloudProviderAdapter? =
        providerRegistry.adapterFor(provider)

    private suspend fun resolveUploadSource(path: String): UploadSource = withContext(Dispatchers.IO) {
        if (!path.startsWith("content://")) return@withContext UploadSource(File(path), false)
        val temporaryFile = File.createTempFile("cloud-upload-", ".bin", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(Uri.parse(path))
                ?: throw IOException("source content unavailable")
            input.use { stream -> temporaryFile.outputStream().use { output -> stream.copyTo(output) } }
            UploadSource(temporaryFile, true)
        } catch (e: Exception) {
            runCatching { temporaryFile.delete() }
            throw e
        }
    }

    private fun isExceptionRetryable(e: Exception): Boolean =
        RetryPolicy.classify(RetryOperation.CLOUD_TRANSFER, e).retryable

    private data class UploadSource(
        val file: File,
        val temporary: Boolean,
    ) {
        fun deleteIfTemporary() {
            if (temporary) runCatching { file.delete() }
        }
    }
}
