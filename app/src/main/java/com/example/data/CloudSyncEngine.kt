package com.example.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Core engine for provider selection, stable sync identity, streaming and durable recovery. */
@Suppress("LongMethod", "NestedBlockDepth", "ReturnCount", "TooManyFunctions")
class CloudSyncEngine(
    private val context: Context,
    private val dao: FileDao,
    private val tokenProvider: OAuthTokenProvider,
    private val providerAdapterOverride: CloudProviderAdapter? = null
) {
    companion object {
        private const val TAG = "CloudSyncEngine"
        private const val BUFFER_SIZE_BYTES = 32 * 1024
    }

    suspend fun syncItem(item: CloudSyncItemEntity): CloudSyncResult {
        val source = createUploadSource(item) ?: return CloudSyncResult.Error(
            message = "Cloud source does not exist, is missing, unreadable, or not supported: " +
                item.filePath,
            isRetryable = false
        )
        val itemWithIdentity = try {
            ensureSyncIdentity(item, source)
        } catch (error: IOException) {
            return unreadableSourceError(item, error)
        } catch (error: SecurityException) {
            return unreadableSourceError(item, error)
        } catch (error: UnsupportedOperationException) {
            return unreadableSourceError(item, error)
        }
        val adapter = getAdapterForProvider(itemWithIdentity.provider)
            ?: return CloudSyncResult.NotSupported
        Log.i(TAG, "Starting streaming sync for item ${itemWithIdentity.id} with ${itemWithIdentity.provider}")
        return try {
            normalizeRecoveryMetadata(itemWithIdentity, adapter.uploadFile(
                source = source,
                remotePath = itemWithIdentity.fileName,
                remoteFileId = itemWithIdentity.remoteFileId,
                idempotencyKey = itemWithIdentity.idempotencyKey,
                remoteRevisionId = itemWithIdentity.remoteRevisionId,
                contentHash = itemWithIdentity.contentHash,
                uploadSessionUri = itemWithIdentity.uploadSessionUri,
                etag = itemWithIdentity.etag,
                onCheckpoint = { checkpoint -> persistCheckpoint(itemWithIdentity, checkpoint) }
            )
            )
        } catch (error: IOException) {
            uploadFailure(itemWithIdentity, error)
        } catch (error: SecurityException) {
            uploadFailure(itemWithIdentity, error)
        } catch (error: IllegalArgumentException) {
            uploadFailure(itemWithIdentity, error)
        } catch (error: IllegalStateException) {
            uploadFailure(itemWithIdentity, error)
        }
    }

    private suspend fun ensureSyncIdentity(
        item: CloudSyncItemEntity,
        source: CloudUploadSource
    ): CloudSyncItemEntity {
        val contentHash = calculateContentHash(source)
        val localFileStableId = item.localFileStableId.ifBlank { item.filePath }
        val idempotencyKey = syncIdentityKey(item.provider, localFileStableId, contentHash)
        val contentChanged = item.contentHash.isNotBlank() && item.contentHash != contentHash
        val synchronizedItem = item.copy(
            localFileStableId = localFileStableId,
            contentHash = contentHash,
            idempotencyKey = idempotencyKey,
            remoteFileId = if (contentChanged) "" else item.remoteFileId,
            remoteRevisionId = if (contentChanged) "" else item.remoteRevisionId,
            uploadSessionUri = if (contentChanged) "" else item.uploadSessionUri,
            etag = if (contentChanged) "" else item.etag
        )
        if (synchronizedItem != item) dao.insertCloudSyncItem(synchronizedItem)
        return synchronizedItem
    }

    private suspend fun persistCheckpoint(
        item: CloudSyncItemEntity,
        checkpoint: CloudSyncCheckpoint
    ) {
        dao.insertCloudSyncItem(
            item.copy(
                remoteFileId = checkpoint.remoteFileId.ifBlank { item.remoteFileId },
                remoteRevisionId = checkpoint.remoteRevisionId.ifBlank { item.remoteRevisionId },
                contentHash = checkpoint.contentHash.ifBlank { item.contentHash },
                uploadSessionUri = checkpoint.uploadSessionUri,
                etag = checkpoint.etag.ifBlank { item.etag },
                idempotencyKey = checkpoint.idempotencyKey.ifBlank { item.idempotencyKey }
            )
        )
    }

    private fun normalizeRecoveryMetadata(
        item: CloudSyncItemEntity,
        result: CloudSyncResult
    ): CloudSyncResult = when (result) {
        is CloudSyncResult.Success -> result.copy(
            remoteFileId = result.remoteFileId.ifBlank { item.remoteFileId },
            remoteRevisionId = result.remoteRevisionId.ifBlank { item.remoteRevisionId },
            contentHash = result.contentHash.ifBlank { item.contentHash },
            uploadSessionUri = result.uploadSessionUri.ifBlank { item.uploadSessionUri },
            etag = result.etag.ifBlank { item.etag },
            idempotencyKey = result.idempotencyKey.ifBlank { item.idempotencyKey }
        )
        is CloudSyncResult.Error -> result.copy(
            remoteFileId = result.remoteFileId.ifBlank { item.remoteFileId },
            remoteRevisionId = result.remoteRevisionId.ifBlank { item.remoteRevisionId },
            contentHash = result.contentHash.ifBlank { item.contentHash },
            uploadSessionUri = result.uploadSessionUri.ifBlank { item.uploadSessionUri },
            etag = result.etag.ifBlank { item.etag },
            idempotencyKey = result.idempotencyKey.ifBlank { item.idempotencyKey }
        )
        CloudSyncResult.NotSupported -> CloudSyncResult.NotSupported
    }

    private fun calculateContentHash(source: CloudUploadSource): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        source.openStream().use { input ->
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead <= 0) break
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }

    private fun syncIdentityKey(
        provider: String,
        localFileStableId: String,
        contentHash: String
    ): String {
        val canonicalIdentity = listOf(
            provider.uppercase(Locale.US),
            localFileStableId,
            contentHash
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(canonicalIdentity.toByteArray()).joinToString("") { byte ->
            "%02x".format(Locale.US, byte)
        }
    }

    private fun createUploadSource(item: CloudSyncItemEntity): CloudUploadSource? = when {
        item.filePath.startsWith("content://") -> CloudUploadSource.ContentUri(
            context = context,
            uri = Uri.parse(item.filePath),
            displayName = item.fileName,
            declaredLength = item.fileSize
        )
        item.filePath.isBlank() -> null
        else -> CloudUploadSource.LocalFile(File(item.filePath)).takeIf { fileSource ->
            try {
                fileSource.openStream().close()
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    private fun getAdapterForProvider(provider: String): CloudProviderAdapter? {
        providerAdapterOverride?.let { return it }
        return when (
            CloudProviderCapabilities.forProvider(provider)
                ?.takeIf { it.isImplemented }
                ?.providerId
                ?.uppercase(Locale.US)
        ) {
            "GOOGLE_DRIVE" -> GoogleDriveProviderAdapter(tokenProvider)
            else -> null
        }
    }

    private fun uploadFailure(item: CloudSyncItemEntity, error: Exception): CloudSyncResult.Error {
        Log.e(TAG, "Exception during upload for item ${item.id}", error)
        return CloudSyncResult.Error(
            message = error.message ?: "Upload failed",
            isRetryable = isExceptionRetryable(error),
            cause = error,
            remoteFileId = item.remoteFileId,
            remoteRevisionId = item.remoteRevisionId,
            contentHash = item.contentHash,
            uploadSessionUri = item.uploadSessionUri,
            etag = item.etag,
            idempotencyKey = item.idempotencyKey
        )
    }

    private fun unreadableSourceError(
        item: CloudSyncItemEntity,
        error: Exception
    ): CloudSyncResult.Error = CloudSyncResult.Error(
        message = "Cloud source could not be opened for hashing: ${item.filePath}",
        isRetryable = false,
        cause = error
    )

    private fun isExceptionRetryable(error: Exception): Boolean =
        error is java.net.UnknownHostException || error is java.net.ConnectException || error is IOException ||
            error.message?.contains("Unable to resolve host") == true
}
