package com.example.data

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Google Drive adapter with escaped queries and crash-resilient resumable uploads. */
@Suppress(
    "LongMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "MaxLineLength",
    "TooManyFunctions",
    "LongParameterList"
)
class GoogleDriveProviderAdapter(
    private val tokenProvider: OAuthTokenProvider,
    private val httpClient: OkHttpClient = OkHttpClient()
) : CloudProviderAdapter {

    override val providerId: String = "GOOGLE_DRIVE"

    @Deprecated("Use the CloudUploadSource upload contract")
    override suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return CloudSyncResult.Error(
                message = "File does not exist or is invalid: ${file.absolutePath}",
                isRetryable = false
            )
        }
        val identity = UUID.nameUUIDFromBytes(
            "GOOGLE_DRIVE|${file.absolutePath}|$remotePath|${file.length()}"
                .toByteArray(StandardCharsets.UTF_8)
        ).toString()
        return uploadFile(
            source = CloudUploadSource.LocalFile(file),
            remotePath = remotePath,
            idempotencyKey = identity
        )
    }

    override suspend fun uploadFile(
        source: CloudUploadSource,
        remotePath: String,
        remoteFileId: String,
        idempotencyKey: String,
        remoteRevisionId: String,
        contentHash: String,
        uploadSessionUri: String,
        etag: String,
        onCheckpoint: suspend (CloudSyncCheckpoint) -> Unit
    ): CloudSyncResult {
        if (idempotencyKey.isBlank()) {
            return CloudSyncResult.Error("Upload failed: sync identity is missing.", isRetryable = false)
        }
        if (source.contentLength < 0L) {
            return CloudSyncResult.Error(
                "Upload failed: content provider did not report a reliable length.",
                isRetryable = false,
                contentHash = contentHash,
                idempotencyKey = idempotencyKey
            )
        }
        val token = tokenProvider.accessTokenOrNull() ?: return CloudSyncResult.Error(
            message = "Upload failed: user is not authenticated with Google Drive.",
            isRetryable = false,
            contentHash = contentHash,
            idempotencyKey = idempotencyKey
        )
        var recoveryCheckpoint = CloudSyncCheckpoint(
            remoteFileId = remoteFileId,
            remoteRevisionId = remoteRevisionId,
            contentHash = contentHash,
            uploadSessionUri = uploadSessionUri,
            etag = etag,
            idempotencyKey = idempotencyKey
        )

        return try {
            recoverCompletedSession(
                token = token,
                source = source,
                remoteFileId = remoteFileId,
                remoteRevisionId = remoteRevisionId,
                contentHash = contentHash,
                uploadSessionUri = uploadSessionUri,
                etag = etag,
                idempotencyKey = idempotencyKey,
                onCheckpoint = onCheckpoint
            )?.let { return it }

            val existingRemoteId = remoteFileId.ifBlank {
                findRemoteIdForSyncIdentity(token, idempotencyKey).orEmpty()
            }
            val checkpoint = CloudSyncCheckpoint(
                remoteFileId = existingRemoteId,
                remoteRevisionId = remoteRevisionId,
                contentHash = contentHash,
                etag = etag,
                idempotencyKey = idempotencyKey
            )
            val sessionUri = startResumableSession(
                token = token,
                source = source,
                remotePath = remotePath,
                checkpoint = checkpoint
            ) ?: return CloudSyncResult.Error(
                message = "Resumable upload preparation failed: missing Location header.",
                isRetryable = false,
                remoteFileId = existingRemoteId,
                remoteRevisionId = remoteRevisionId,
                contentHash = contentHash,
                etag = etag,
                idempotencyKey = idempotencyKey
            )
            val persistedCheckpoint = checkpoint.copy(uploadSessionUri = sessionUri)
            recoveryCheckpoint = persistedCheckpoint
            onCheckpoint(persistedCheckpoint)
            uploadToSession(
                token = token,
                source = source,
                sessionUri = sessionUri,
                offset = 0L,
                checkpoint = persistedCheckpoint,
                onCheckpoint = onCheckpoint
            )
        } catch (error: IOException) {
            checkpointedFailure(error, recoveryCheckpoint)
        } catch (error: SecurityException) {
            checkpointedFailure(error, recoveryCheckpoint)
        } catch (error: IllegalArgumentException) {
            checkpointedFailure(error, recoveryCheckpoint)
        } catch (error: IllegalStateException) {
            checkpointedFailure(error, recoveryCheckpoint)
        }
    }

    private suspend fun recoverCompletedSession(
        token: String,
        source: CloudUploadSource,
        remoteFileId: String,
        remoteRevisionId: String,
        contentHash: String,
        uploadSessionUri: String,
        etag: String,
        idempotencyKey: String,
        onCheckpoint: suspend (CloudSyncCheckpoint) -> Unit
    ): CloudSyncResult? {
        if (uploadSessionUri.isBlank()) return null
        val checkpoint = CloudSyncCheckpoint(
            remoteFileId = remoteFileId,
            remoteRevisionId = remoteRevisionId,
            contentHash = contentHash,
            uploadSessionUri = uploadSessionUri,
            etag = etag,
            idempotencyKey = idempotencyKey
        )
        val statusRequest = Request.Builder()
            .url(uploadSessionUri)
            .header("Authorization", "Bearer $token")
            .header("Content-Length", "0")
            .header("Content-Range", "bytes */${source.contentLength}")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        return httpClient.newCall(statusRequest).execute().use { response ->
            when {
                response.isSuccessful -> {
                    val result = completedUploadResult(response, source.contentLength, checkpoint)
                    if (result.remoteFileId.isNotBlank()) result else {
                        val recoveredId = findRemoteIdForSyncIdentity(token, idempotencyKey)
                        if (recoveredId.isNullOrBlank()) {
                            checkpointedFailure(
                                IOException("Completed upload response did not contain a file ID"),
                                remoteFileId,
                                remoteRevisionId,
                                contentHash,
                                uploadSessionUri,
                                etag,
                                idempotencyKey
                            )
                        } else {
                            CloudSyncResult.Success(
                                bytesTransferred = source.contentLength,
                                remoteFileId = recoveredId,
                                contentHash = contentHash,
                                etag = etag,
                                idempotencyKey = idempotencyKey
                            )
                        }
                    }
                }
                response.code == HTTP_RESUME_INCOMPLETE -> {
                    val offset = nextUploadOffset(response.header("Range"))
                    uploadToSession(token, source, uploadSessionUri, offset, checkpoint, onCheckpoint)
                }
                response.code == HTTP_NOT_FOUND || response.code == HTTP_GONE -> {
                    onCheckpoint(checkpoint.copy(uploadSessionUri = ""))
                    null
                }
                else -> checkpointedHttpFailure("Resumable session recovery failed", response, checkpoint)
            }
        }
    }

    private fun startResumableSession(
        token: String,
        source: CloudUploadSource,
        remotePath: String,
        checkpoint: CloudSyncCheckpoint
    ): String? {
        val metadataJson = Gson().toJson(
            mapOf(
                "name" to remotePath,
                "description" to "Uploaded via Smart Vault Engine",
                "appProperties" to mapOf(
                    SYNC_IDENTITY_PROPERTY to checkpoint.idempotencyKey,
                    CONTENT_HASH_PROPERTY to checkpoint.contentHash
                )
            )
        )
        val endpoint = if (checkpoint.remoteFileId.isBlank()) {
            DRIVE_UPLOAD_URL.toHttpUrl().newBuilder()
                .addQueryParameter("uploadType", "resumable")
                .build()
        } else {
            "$DRIVE_UPLOAD_URL/${checkpoint.remoteFileId}".toHttpUrl().newBuilder()
                .addQueryParameter("uploadType", "resumable")
                .build()
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("X-Upload-Content-Type", source.mimeType)
            .header("X-Upload-Content-Length", source.contentLength.toString())
            .method(
                if (checkpoint.remoteFileId.isBlank()) "POST" else "PATCH",
                metadataJson.toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure("Resumable upload preparation failed", response)
            response.header("Location")
        }
    }

    private suspend fun uploadToSession(
        token: String,
        source: CloudUploadSource,
        sessionUri: String,
        offset: Long,
        checkpoint: CloudSyncCheckpoint,
        onCheckpoint: suspend (CloudSyncCheckpoint) -> Unit
    ): CloudSyncResult {
        val request = Request.Builder()
            .url(sessionUri)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", contentRange(offset, source.contentLength))
            .put(streamingRequestBody(source, offset))
            .build()
        return httpClient.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> {
                    val completed = completedUploadResult(response, source.contentLength, checkpoint)
                    if (completed.remoteFileId.isBlank()) {
                        CloudSyncResult.Error(
                            message = "Failed to parse remote file ID from response",
                            isRetryable = false,
                            contentHash = checkpoint.contentHash,
                            uploadSessionUri = sessionUri,
                            etag = completed.etag,
                            idempotencyKey = checkpoint.idempotencyKey
                        )
                    } else {
                        completed
                    }
                }
                response.code == HTTP_RESUME_INCOMPLETE -> {
                    val nextOffset = nextUploadOffset(response.header("Range"))
                    onCheckpoint(checkpoint.copy(uploadSessionUri = sessionUri))
                    checkpointedFailure(
                        IOException("Resumable upload remains incomplete at byte $nextOffset"),
                        checkpoint.remoteFileId,
                        checkpoint.remoteRevisionId,
                        checkpoint.contentHash,
                        sessionUri,
                        checkpoint.etag,
                        checkpoint.idempotencyKey
                    )
                }
                else -> checkpointedHttpFailure("File upload failed", response, checkpoint)
            }
        }
    }

    private fun completedUploadResult(
        response: okhttp3.Response,
        bytesTransferred: Long,
        checkpoint: CloudSyncCheckpoint
    ): CloudSyncResult.Success {
        val responseBody = response.body?.string().orEmpty()
        return CloudSyncResult.Success(
            bytesTransferred = bytesTransferred,
            remoteFileId = extractJsonValue(responseBody, "id").orEmpty().ifBlank { checkpoint.remoteFileId },
            remoteRevisionId = extractJsonValue(responseBody, "headRevisionId")
                .orEmpty()
                .ifBlank { checkpoint.remoteRevisionId },
            contentHash = checkpoint.contentHash,
            etag = response.header("ETag").orEmpty().ifBlank { checkpoint.etag },
            idempotencyKey = checkpoint.idempotencyKey
        )
    }

    override suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult {
        val token = tokenProvider.accessTokenOrNull() ?: return CloudSyncResult.Error(
            message = "Download failed: user is not authenticated with Google Drive.",
            isRetryable = false
        )
        return try {
            val query = "name='${escapeDriveQueryValue(remotePath)}' and trashed=false"
            val searchRequest = Request.Builder()
                .url(driveFilesUrl(query))
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            httpClient.newCall(searchRequest).execute().use { searchResponse ->
                if (!searchResponse.isSuccessful) {
                    return classifyHttpError("File search failed", searchResponse)
                }
                val searchBody = searchResponse.body?.string().orEmpty()
                val fileId = extractJsonValue(searchBody, "id")
                    ?: extractJsonValue(searchBody, "fileId")
                    ?: return CloudSyncResult.Error(
                        "File not found on Google Drive: $remotePath",
                        isRetryable = false
                    )
                downloadMedia(token, fileId, destinationFile)
            }
        } catch (error: IOException) {
            checkpointedFailure(error, "", "", "", "", "", "")
        } catch (error: SecurityException) {
            checkpointedFailure(error, "", "", "", "", "", "")
        } catch (error: IllegalArgumentException) {
            checkpointedFailure(error, "", "", "", "", "", "")
        } catch (error: IllegalStateException) {
            checkpointedFailure(error, "", "", "", "", "", "")
        }
    }

    private fun downloadMedia(token: String, fileId: String, destinationFile: File): CloudSyncResult {
        val request = Request.Builder()
            .url(
                "$DRIVE_FILES_URL/$fileId".toHttpUrl().newBuilder()
                    .addQueryParameter("alt", "media")
                    .build()
            )
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return classifyHttpError("Media download failed", response)
            val body = response.body ?: return CloudSyncResult.Error(
                "Media download response body was empty.",
                isRetryable = false
            )
            val bytesTransferred = body.byteStream().use { input ->
                destinationFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (bytesTransferred <= 0L) {
                destinationFile.delete()
                CloudSyncResult.Error("Media download response body was empty.", isRetryable = false)
            } else {
                CloudSyncResult.Success(
                    bytesTransferred = bytesTransferred,
                    remoteFileId = fileId,
                    etag = response.header("ETag").orEmpty()
                )
            }
        }
    }

    private fun findRemoteIdForSyncIdentity(token: String, syncIdentity: String): String? {
        val escapedIdentity = escapeDriveQueryValue(syncIdentity)
        val query = "appProperties has { key='$SYNC_IDENTITY_PROPERTY' and value='$escapedIdentity' } " +
            "and trashed=false"
        val request = Request.Builder()
            .url(driveFilesUrl(query))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure("Sync identity lookup failed", response)
            return extractJsonValue(response.body?.string().orEmpty(), "id")
        }
    }

    /** Drive query literals require escaping backslashes before escaping apostrophes. */
    private fun escapeDriveQueryValue(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    private fun driveFilesUrl(query: String) = DRIVE_FILES_URL.toHttpUrl().newBuilder()
        .addQueryParameter("q", query)
        .addQueryParameter("fields", "files(id,headRevisionId),nextPageToken")
        .build()

    private fun streamingRequestBody(source: CloudUploadSource, offset: Long): RequestBody =
        object : RequestBody() {
            override fun contentType() = source.mimeType.toMediaTypeOrNull()
            override fun contentLength(): Long = source.contentLength - offset

            override fun writeTo(sink: BufferedSink) {
                source.openStream().use { input ->
                    skipFully(input, offset)
                    input.copyTo(sink.outputStream())
                }
            }
        }

    private fun skipFully(input: java.io.InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() == -1) {
                throw IOException("Unable to seek source stream to resumable upload offset")
            } else {
                remaining -= 1L
            }
        }
    }

    private fun contentRange(offset: Long, totalLength: Long): String =
        "bytes $offset-${totalLength - 1L}/$totalLength"

    private fun nextUploadOffset(rangeHeader: String?): Long {
        val endByte = rangeHeader?.substringAfter('-', "")?.toLongOrNull() ?: -1L
        return endByte + 1L
    }

    private fun extractJsonValue(json: String, key: String): String? =
        "\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            .find(json)
            ?.groupValues
            ?.get(1)

    private fun checkpointedHttpFailure(
        context: String,
        response: okhttp3.Response,
        checkpoint: CloudSyncCheckpoint
    ): CloudSyncResult.Error = CloudSyncResult.Error(
        message = "$context: HTTP ${response.code} - ${response.body?.string() ?: "No details provided"}",
        isRetryable = response.code == HTTP_REQUEST_TIMEOUT ||
            response.code == HTTP_TOO_MANY_REQUESTS || response.code >= HTTP_SERVER_ERROR_START,
        remoteFileId = checkpoint.remoteFileId,
        remoteRevisionId = checkpoint.remoteRevisionId,
        contentHash = checkpoint.contentHash,
        uploadSessionUri = checkpoint.uploadSessionUri,
        etag = checkpoint.etag,
        idempotencyKey = checkpoint.idempotencyKey
    )

    private fun classifyHttpError(context: String, response: okhttp3.Response): CloudSyncResult.Error =
        CloudSyncResult.Error(
            message = "$context: HTTP ${response.code} - ${response.body?.string() ?: "No details provided"}",
            isRetryable = response.code == HTTP_REQUEST_TIMEOUT ||
                response.code == HTTP_TOO_MANY_REQUESTS || response.code >= HTTP_SERVER_ERROR_START
        )

    private fun checkpointedFailure(
        error: Exception,
        checkpoint: CloudSyncCheckpoint
    ): CloudSyncResult.Error = checkpointedFailure(
        error = error,
        remoteFileId = checkpoint.remoteFileId,
        remoteRevisionId = checkpoint.remoteRevisionId,
        contentHash = checkpoint.contentHash,
        uploadSessionUri = checkpoint.uploadSessionUri,
        etag = checkpoint.etag,
        idempotencyKey = checkpoint.idempotencyKey
    )

    private fun checkpointedFailure(
        error: Exception,
        remoteFileId: String,
        remoteRevisionId: String,
        contentHash: String,
        uploadSessionUri: String,
        etag: String,
        idempotencyKey: String
    ): CloudSyncResult.Error = CloudSyncResult.Error(
        message = error.message ?: "Google Drive operation failed",
        isRetryable = error is java.net.UnknownHostException || error is java.net.ConnectException ||
            error is IOException || error.message?.contains("Unable to resolve host") == true,
        cause = error,
        remoteFileId = remoteFileId,
        remoteRevisionId = remoteRevisionId,
        contentHash = contentHash,
        uploadSessionUri = uploadSessionUri,
        etag = etag,
        idempotencyKey = idempotencyKey
    )

    private class HttpFailure(context: String, response: okhttp3.Response) : IOException(
        "$context: HTTP ${response.code} - ${response.body?.string() ?: "No details provided"}"
    )

    private companion object {
        const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        const val SYNC_IDENTITY_PROPERTY = "vvfSyncIdentity"
        const val CONTENT_HASH_PROPERTY = "vvfContentHash"
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_RESUME_INCOMPLETE = 308
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
        const val HTTP_SERVER_ERROR_START = 500
        val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaTypeOrNull()
    }
}
