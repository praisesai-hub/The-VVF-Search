package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A repeatable, streaming source for cloud transfers. File descriptors and InputStreams are never
 * kept in UI state or queued Room rows, so both local paths and SAF content URIs are supported.
 */
sealed class CloudUploadSource {
    abstract val displayName: String
    abstract val contentLength: Long
    abstract val mimeType: String
    abstract fun openStream(): InputStream

    data class LocalFile(private val file: File) : CloudUploadSource() {
        override val displayName: String get() = file.name
        override val contentLength: Long get() = file.length()
        override val mimeType: String get() = CloudUploadSource.mimeTypeForName(file.name)

        override fun openStream(): InputStream {
            if (!file.exists() || !file.isFile || !file.canRead()) {
                throw IOException("Local cloud upload file is missing or unreadable: ${file.absolutePath}")
            }
            return FileInputStream(file)
        }

        fun asFile(): File = file
    }

    data class ContentUri(
        private val context: Context,
        private val uri: Uri,
        override val displayName: String,
        private val declaredLength: Long
    ) : CloudUploadSource() {
        override val contentLength: Long by lazy {
            if (declaredLength > 0L) declaredLength else queryLength(context, uri)
        }
        override val mimeType: String by lazy {
            context.contentResolver.getType(uri).orEmpty()
                .ifBlank { CloudUploadSource.mimeTypeForName(displayName) }
        }

        override fun openStream(): InputStream =
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("Content provider returned no stream for $uri")
    }

    companion object {
        private fun queryLength(context: Context, uri: Uri): Long {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    return cursor.getLong(index).coerceAtLeast(0L)
                }
            }
            return -1L
        }

        fun mimeTypeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "txt" -> "text/plain"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}

/** Provider-agnostic outcome of a cloud sync operation. */
sealed class CloudSyncResult {
    data class Success(
        val bytesTransferred: Long = 0L,
        val remoteFileId: String = "",
        val remoteRevisionId: String = "",
        val contentHash: String = "",
        val uploadSessionUri: String = "",
        val etag: String = "",
        val idempotencyKey: String = ""
    ) : CloudSyncResult()

    data class Error(
        val message: String,
        val isRetryable: Boolean,
        val cause: Throwable? = null,
        val remoteFileId: String = "",
        val remoteRevisionId: String = "",
        val contentHash: String = "",
        val uploadSessionUri: String = "",
        val etag: String = "",
        val idempotencyKey: String = ""
    ) : CloudSyncResult()

    object NotSupported : CloudSyncResult()
}

/** Persisted as soon as a provider creates or updates resumable-transfer recovery state. */
data class CloudSyncCheckpoint(
    val remoteFileId: String = "",
    val remoteRevisionId: String = "",
    val contentHash: String = "",
    val uploadSessionUri: String = "",
    val etag: String = "",
    val idempotencyKey: String = ""
)

/** Abstraction for an executable cloud storage provider adapter, currently Google Drive only. */
@Suppress("LongParameterList")
interface CloudProviderAdapter {
    val providerId: String

    /**
     * New source-first upload contract. The default retains compatibility for existing local-file
     * test adapters while preventing a content URI from being converted into a java.io.File.
     */
    suspend fun uploadFile(
        source: CloudUploadSource,
        remotePath: String,
        remoteFileId: String = "",
        idempotencyKey: String = "",
        remoteRevisionId: String = "",
        contentHash: String = "",
        uploadSessionUri: String = "",
        etag: String = "",
        onCheckpoint: suspend (CloudSyncCheckpoint) -> Unit = {}
    ): CloudSyncResult {
        val localFile = (source as? CloudUploadSource.LocalFile)?.asFile()
            ?: return CloudSyncResult.Error(
                message = "This cloud provider adapter does not support SAF content URI uploads.",
                isRetryable = false
            )
        return uploadFile(localFile, remotePath)
    }

    /** Compatibility hook for existing local-file-only adapters and tests. */
    @Deprecated("Implement the CloudUploadSource upload contract instead")
    suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult = CloudSyncResult.NotSupported

    /**
     * Downloads a persisted provider file identifier. A display name or remote path must never be
     * used as a lookup key because Drive allows duplicate names across folders and drives.
     */
    suspend fun downloadFile(remoteFileId: String, destinationFile: File): CloudSyncResult
}
