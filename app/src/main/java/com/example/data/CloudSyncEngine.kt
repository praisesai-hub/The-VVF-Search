package com.example.data

import android.content.Context
import android.util.Log
import com.example.context.cloud.CloudProviderRegistry
import com.example.context.drive.DriveAuthorizationPort
import com.example.domain.error.DomainErrorMapper
import com.example.domain.retry.RetryOperation
import com.example.domain.retry.RetryPolicy
import java.io.File

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
        val file = sourceFileOrNull(item)
            ?: return nonRetryableError("CLOUD_TRANSFER", java.io.IOException("source file unavailable"), item)
        val adapter = getAdapterForProvider(item.provider)
            ?: return nonRetryableError(
                "CLOUD_PROVIDER_RESOLUTION",
                IllegalArgumentException("unsupported provider"),
                item
            )

        Log.i(TAG, "Starting sync for item ${item.id} (${item.fileName}) with provider ${item.provider}...")
        return uploadItem(adapter, file, item)
    }

    private fun sourceFileOrNull(item: CloudSyncItemEntity): File? =
        File(item.filePath).takeIf { it.exists() && it.isFile }

    private fun nonRetryableError(
        operation: String,
        cause: Throwable,
        item: CloudSyncItemEntity
    ): CloudSyncResult.Error {
        val error = DomainErrorMapper.fromThrowable(
            operation = operation,
            cause = cause,
            fileId = item.id,
            provider = item.provider
        )
        return CloudSyncResult.Error(
            message = error.userMessage.value,
            isRetryable = false,
            domainError = error
        )
    }

    private suspend fun uploadItem(
        adapter: CloudProviderAdapter,
        file: File,
        item: CloudSyncItemEntity
    ): CloudSyncResult =
        runCatching {
            adapter.uploadFile(
                file = file,
                remotePath = item.fileName,
                operationId = item.operationId,
                transferState = CloudTransferState(
                    remoteFileId = item.remoteFileId,
                    resumableSessionUri = item.resumableSessionUri,
                    bytesCommitted = item.resumableBytesCommitted
                )
            ) { progress -> persistTransferProgress(item, progress) }
        }.fold(
            onSuccess = { it },
            onFailure = { cause ->
                if (cause !is Exception) throw cause
                transferFailure(item, cause)
            }
        )

    private suspend fun persistTransferProgress(item: CloudSyncItemEntity, progress: CloudTransferProgress) {
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

    private fun transferFailure(item: CloudSyncItemEntity, cause: Exception): CloudSyncResult.Error {
        val error = DomainErrorMapper.fromThrowable(
            operation = "CLOUD_TRANSFER",
            cause = cause,
            fileId = item.id,
            provider = item.provider
        )
        com.example.domain.error.DiagnosticLogger.log(TAG, error)
        return CloudSyncResult.Error(
            message = error.userMessage.value,
            isRetryable = isExceptionRetryable(cause),
            cause = cause,
            domainError = error
        )
    }

    /**
     * Resolves the correct [CloudProviderAdapter] based on the provider string.
     */
    private fun getAdapterForProvider(provider: String): CloudProviderAdapter? =
        providerRegistry.adapterFor(provider)

    private fun isExceptionRetryable(e: Exception): Boolean =
        RetryPolicy.classify(RetryOperation.CLOUD_TRANSFER, e).retryable
}
