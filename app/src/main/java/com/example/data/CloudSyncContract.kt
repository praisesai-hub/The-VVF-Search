package com.example.data

import java.io.File

/**
 * Provider-agnostic outcome of a cloud sync operation (upload/download).
 */
sealed class CloudSyncResult {
    data class Success(val bytesTransferred: Long = 0L) : CloudSyncResult()
    data class Error(
        val message: String,
        val isRetryable: Boolean,
        val cause: Throwable? = null
    ) : CloudSyncResult()
    object NotSupported : CloudSyncResult()
}

/**
 * Abstraction for cloud storage providers (REST, Drive, OneDrive, Dropbox, etc.).
 */
interface CloudProviderAdapter {
    val providerId: String
    suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult
    suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult
}

