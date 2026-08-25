package com.example.data

import android.content.Context
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
    // Provider resolution, transfer, and result mapping remain one sync transaction boundary.
    @Suppress("detekt.LongMethod", "detekt.ReturnCount")
    suspend fun syncItem(item: CloudSyncItemEntity): CloudSyncResult {
        val file = File(item.filePath)
        if (!file.exists() || !file.isFile) {
            val error = DomainErrorMapper.fromThrowable(
                operation = "CLOUD_TRANSFER",
                cause = java.io.IOException("source file unavailable"),
                fileId = item.id,
                provider = item.provider
            )
            return CloudSyncResult.Error(
                message = error.userMessage.value,
                isRetryable = false,
                domainError = error
            )
        }

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
        }
    }

    /**
     * Resolves the correct [CloudProviderAdapter] based on the provider string.
     */
    private fun getAdapterForProvider(provider: String): CloudProviderAdapter? =
        providerRegistry.adapterFor(provider)

    private fun isExceptionRetryable(e: Exception): Boolean =
        RetryPolicy.classify(RetryOperation.CLOUD_TRANSFER, e).retryable
}
