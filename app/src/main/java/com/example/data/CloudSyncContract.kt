package com.example.data

import com.example.domain.error.DomainError
import java.io.File

/**
 * Provider-agnostic outcome of a cloud sync operation (upload/download).
 */
sealed class CloudSyncResult {
    data class Success(val bytesTransferred: Long = 0L) : CloudSyncResult()
    data class Error(
        val message: String,
        val isRetryable: Boolean,
        val cause: Throwable? = null,
        val domainError: DomainError? = null
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
    suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult
}

