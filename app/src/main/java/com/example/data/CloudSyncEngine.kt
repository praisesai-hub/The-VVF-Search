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
    private val providerAdapterOverride: CloudProviderAdapter? = null
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

        Log.i(TAG, "Starting sync for item ${item.id} (${item.fileName}) with provider ${item.provider}...")
        return try {
            adapter.uploadFile(file, item.fileName, item.operationId)
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
