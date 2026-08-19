package com.example.data

import com.example.domain.error.DomainError
import java.io.File

/**
 * Durable provider state supplied when an upload is retried after process death.
 */
data class CloudTransferState(
    val remoteFileId: String = "",
    val resumableSessionUri: String = "",
    val bytesCommitted: Long = 0L
)

data class CloudTransferProgress(
    val remoteFileId: String? = null,
    val resumableSessionUri: String? = null,
    val bytesCommitted: Long = 0L
)

/**
 * Provider-agnostic outcome of a cloud sync operation (upload/download).
 */
sealed class CloudSyncResult {
    data class Success(
        val bytesTransferred: Long = 0L,
        val remoteFileId: String? = null,
        val resumableSessionUri: String? = null,
        val bytesCommitted: Long = bytesTransferred
    ) : CloudSyncResult()
    data class Error(
        val message: String,
        val isRetryable: Boolean,
        val cause: Throwable? = null,
        val domainError: DomainError? = null,
        val remoteFileId: String? = null,
        val resumableSessionUri: String? = null,
        val bytesCommitted: Long = 0L
    ) : CloudSyncResult()
    object NotSupported : CloudSyncResult()
}

/**
 * Abstraction for cloud storage providers (REST, Drive, OneDrive, Dropbox, etc.).
 */
interface CloudProviderAdapter {
    val providerId: String
    suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult

    /**
     * Upload with a stable operation key. Providers may use it for remote
     * deduplication; the default preserves compatibility with legacy adapters.
     */
    suspend fun uploadFile(file: File, remotePath: String, operationId: String): CloudSyncResult =
        uploadFile(file, remotePath)

    /**
     * Upload with durable resumable state and a lease-guarded progress callback.
     * Legacy providers retain the operation-ID behavior by default.
     */
    suspend fun uploadFile(
        file: File,
        remotePath: String,
        operationId: String,
        transferState: CloudTransferState,
        onProgress: suspend (CloudTransferProgress) -> Unit = {}
    ): CloudSyncResult = uploadFile(file, remotePath, operationId)
    suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult
}

