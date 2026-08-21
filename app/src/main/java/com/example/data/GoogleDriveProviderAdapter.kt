package com.example.data

import com.example.context.drive.DriveAuthorizationPort
import com.example.domain.error.DiagnosticLogger
import com.example.domain.error.DomainErrorMapper
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Google Drive adapter with operation-ID deduplication and resumable upload recovery. */
class GoogleDriveProviderAdapter(
    private val driveAuthorization: DriveAuthorizationPort,
    private val httpClient: OkHttpClient = OkHttpClient()
) : CloudProviderAdapter {
    override val providerId: String = "GOOGLE_DRIVE"

    override suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult =
        uploadFile(file, remotePath, "", CloudTransferState()) {}

    override suspend fun uploadFile(file: File, remotePath: String, operationId: String): CloudSyncResult =
        uploadFile(file, remotePath, operationId, CloudTransferState()) {}

    @Suppress("LongMethod", "NestedBlockDepth", "ReturnCount")
    override suspend fun uploadFile(
        file: File,
        remotePath: String,
        operationId: String,
        transferState: CloudTransferState,
        onProgress: suspend (CloudTransferProgress) -> Unit
    ): CloudSyncResult {
        if (!file.exists() || !file.isFile) {
            return CloudSyncResult.Error("The selected file is unavailable.", false)
        }
        val authorization = driveAuthorization.authorizationHeader() ?: return CloudSyncResult.Error(
            "Upload failed: user is not authenticated with Google Drive.", false
        )
        return try {
            findExistingOperationFileId(authorization, operationId)?.let { existing ->
                return CloudSyncResult.Success(file.length(), existing)
            }

            val sessionUri = resolveResumableSessionUri(
                authorization,
                file,
                remotePath,
                operationId,
                transferState
            ) ?: return CloudSyncResult.Error(
                "Cloud upload could not be initialized: Missing 'Location' header.",
                false
            )

            onProgress(
                CloudTransferProgress(
                    resumableSessionUri = sessionUri,
                    bytesCommitted = transferState.bytesCommitted
                )
            )
            var offset = transferState.bytesCommitted.coerceIn(0L, file.length())
            if (transferState.resumableSessionUri.isNotBlank()) {
                when (val probe = queryUploadOffset(authorization, sessionUri, file.length())) {
                    is UploadProbe.Completed -> return CloudSyncResult.Success(
                        file.length(), probe.fileId, sessionUri, file.length()
                    )
                    is UploadProbe.Offset -> offset = probe.offset
                    UploadProbe.Unknown -> Unit
                }
            }

            uploadRemainingChunks(
                UploadRequestContext(
                    authorization = authorization,
                    file = file,
                    sessionUri = sessionUri,
                    mimeType = determineMimeType(file).toMediaTypeOrNull(),
                    onProgress = onProgress
                ),
                offset,
            )
        } catch (e: DriveHttpException) {
            classifyHttpError(
                "Resumable upload preparation failed",
                e.code,
                transferState.resumableSessionUri,
                transferState.bytesCommitted
            )
        } catch (_: MissingUploadLocationException) {
            CloudSyncResult.Error("Cloud upload could not be initialized: Missing 'Location' header.", false)
        } catch (e: Exception) {
            classifyException(
                exception = e,
                providerId = providerId,
                sessionUri = transferState.resumableSessionUri,
                bytesCommitted = transferState.bytesCommitted
            )
        }
    }

    private fun findExistingOperationFileId(authorization: String, operationId: String): String? {
        if (operationId.isBlank()) return null
        val operationQuery =
            "appProperties has { key='vvf_operation_id' " +
                "and value='${escapeDriveQueryValue(operationId)}' } and trashed = false"
        return findFileIds(httpClient, authorization, operationQuery).firstOrNull()
    }

    private fun resolveResumableSessionUri(
        authorization: String,
        file: File,
        remotePath: String,
        operationId: String,
        transferState: CloudTransferState
    ): String? {
        if (transferState.resumableSessionUri.isNotBlank()) return transferState.resumableSessionUri
        val metadata = mutableMapOf<String, Any>(
            "name" to remotePath,
            "description" to "Uploaded via Smart Vault Engine"
        )
        if (operationId.isNotBlank()) {
            metadata["appProperties"] = mapOf("vvf_operation_id" to operationId)
        }
        return initiateResumableUpload(
            httpClient,
            authorization,
            determineMimeType(file),
            file.length(),
            Gson().toJson(metadata)
        )
    }

    private suspend fun uploadRemainingChunks(
        context: UploadRequestContext,
        initialOffset: Long
    ): CloudSyncResult {
        var offset = initialOffset
        var terminalResult: CloudSyncResult? = null
        while (offset < context.file.length() && terminalResult == null) {
            when (val outcome = uploadChunk(context, offset)) {
                is UploadChunkOutcome.Continue -> {
                    offset = outcome.nextOffset
                    context.onProgress(
                        CloudTransferProgress(
                            resumableSessionUri = context.sessionUri,
                            bytesCommitted = offset
                        )
                    )
                }
                is UploadChunkOutcome.Completed -> {
                    val completedBytes = context.file.length()
                    context.onProgress(
                        CloudTransferProgress(
                            remoteFileId = outcome.remoteFileId,
                            resumableSessionUri = context.sessionUri,
                            bytesCommitted = completedBytes
                        )
                    )
                    terminalResult = CloudSyncResult.Success(
                        bytesTransferred = completedBytes,
                        remoteFileId = outcome.remoteFileId,
                        resumableSessionUri = context.sessionUri,
                        bytesCommitted = completedBytes
                    )
                }
                is UploadChunkOutcome.Error -> terminalResult = outcome.error
            }
        }
        return terminalResult ?: CloudSyncResult.Error(
            "Cloud upload ended without a completion response.",
            true,
            resumableSessionUri = context.sessionUri,
            bytesCommitted = offset
        )
    }

    private fun uploadChunk(context: UploadRequestContext, offset: Long): UploadChunkOutcome {
        val endExclusive = minOf(offset + UPLOAD_CHUNK_BYTES, context.file.length())
        val request = Request.Builder()
            .url(context.sessionUri)
            .header("Authorization", context.authorization)
            .header("Content-Length", (endExclusive - offset).toString())
            .header("Content-Range", "bytes $offset-${endExclusive - 1}/${context.file.length()}")
            .put(FileSliceRequestBody(context.file, offset, endExclusive - offset, context.mimeType))
            .build()
        return httpClient.newCall(request).execute().use { response ->
            when {
                response.code == HTTP_RESUME_INCOMPLETE -> UploadChunkOutcome.Continue(
                    parseCommittedOffset(
                        response.header("Content-Range") ?: response.header("Range"),
                        offset,
                        endExclusive
                    )
                )
                response.isSuccessful -> {
                    val remoteId = extractFileIdFromJson(response.body?.string().orEmpty())
                    if (remoteId == null) {
                        UploadChunkOutcome.Error(
                            CloudSyncResult.Error(
                                "Failed to parse remote file ID from response",
                                false,
                                resumableSessionUri = context.sessionUri,
                                bytesCommitted = endExclusive
                            )
                        )
                    } else {
                        UploadChunkOutcome.Completed(remoteId)
                    }
                }
                else -> UploadChunkOutcome.Error(
                    classifyHttpError("File upload failed", response.code, context.sessionUri, offset)
                )
            }
        }
    }

    private data class UploadRequestContext(
        val authorization: String,
        val file: File,
        val sessionUri: String,
        val mimeType: MediaType?,
        val onProgress: suspend (CloudTransferProgress) -> Unit
    )

    private sealed class UploadChunkOutcome {
        data class Continue(val nextOffset: Long) : UploadChunkOutcome()
        data class Completed(val remoteFileId: String) : UploadChunkOutcome()
        data class Error(val error: CloudSyncResult.Error) : UploadChunkOutcome()
    }

    @Suppress("NestedBlockDepth", "ReturnCount")
    override suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult {
        val authorization = driveAuthorization.authorizationHeader() ?: return CloudSyncResult.Error(
            "Download failed: user is not authenticated with Google Drive.", false
        )
        return try {
            val fileId = findFileIds(
                httpClient,
                authorization,
                "name = '${escapeDriveQueryValue(remotePath)}' and trashed = false"
            ).firstOrNull() ?: return CloudSyncResult.Error(
                "File not found in Google Drive.", false
            )
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .header("Authorization", authorization)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return classifyHttpError("Media download failed", response.code)
                }
                val body = response.body ?: return CloudSyncResult.Error(
                    "Media download response body was empty.", false
                )
                val bytes = body.byteStream().use { input ->
                    destinationFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (bytes <= 0L) {
                    destinationFile.delete()
                    return CloudSyncResult.Error("Media download response body was empty.", false)
                }
                CloudSyncResult.Success(bytes, fileId, bytesCommitted = bytes)
            }
        } catch (e: DriveHttpException) {
            classifyHttpError("File search failed", e.code)
        } catch (e: Exception) {
            classifyException(exception = e, providerId = providerId)
        }
    }

    private fun queryUploadOffset(authorization: String, sessionUri: String, size: Long): UploadProbe {
        val request = Request.Builder()
            .url(sessionUri)
            .header("Authorization", authorization)
            .header("Content-Length", "0")
            .header("Content-Range", "bytes */$size")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == HTTP_RESUME_INCOMPLETE -> UploadProbe.Offset(
                        parseCommittedOffset(
                            response.header("Content-Range") ?: response.header("Range"),
                            0L,
                            0L
                        )
                    )
                    response.isSuccessful -> extractFileIdFromJson(response.body?.string().orEmpty())
                        ?.let(UploadProbe::Completed) ?: UploadProbe.Unknown
                    else -> UploadProbe.Unknown
                }
            }
        }.getOrDefault(UploadProbe.Unknown)
    }

    private fun classifyHttpError(
        context: String,
        code: Int,
        sessionUri: String? = null,
        bytesCommitted: Long = 0L
    ): CloudSyncResult.Error {
        val retryable = code == HTTP_REQUEST_TIMEOUT ||
            code == HTTP_RATE_LIMITED ||
            code >= HTTP_SERVER_ERROR_MIN ||
            (code == HTTP_NOT_FOUND && sessionUri != null)
        return CloudSyncResult.Error(
            message = "$context: HTTP $code",
            isRetryable = retryable,
            domainError = DomainErrorMapper.fromThrowable(
                operation = "DRIVE_HTTP_REQUEST",
                cause = IOException("HTTP $code"),
                provider = providerId
            ),
            resumableSessionUri = sessionUri,
            bytesCommitted = bytesCommitted
        )
    }

    private sealed class UploadProbe {
        data class Offset(val offset: Long) : UploadProbe()
        data class Completed(val fileId: String) : UploadProbe()
        data object Unknown : UploadProbe()
    }

    private class FileSliceRequestBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val mediaType: MediaType?
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(TRANSFER_COPY_BUFFER_BYTES)
                var remaining = length
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) throw IOException("Unexpected end of upload source")
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    companion object {
        private const val UPLOAD_CHUNK_BYTES = 256L * 1024L
        private const val HTTP_RESUME_INCOMPLETE = 308
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_RATE_LIMITED = 429
        private const val HTTP_SERVER_ERROR_MIN = 500
        private const val HTTP_NOT_FOUND = 404
        private const val TRANSFER_COPY_BUFFER_BYTES = 64 * 1024
    }
}

private fun determineMimeType(file: File): String = when (file.extension.lowercase()) {
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "txt" -> "text/plain"
    "json" -> "application/json"
    else -> "application/octet-stream"
}

private fun escapeDriveQueryValue(value: String): String =
    value.replace("\\", "\\\\").replace("'", "\\'")

private fun initiateResumableUpload(
    httpClient: OkHttpClient,
    authorization: String,
    mimeType: String,
    size: Long,
    metadataJson: String
): String? {
    val request = Request.Builder()
        .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
        .header("Authorization", authorization)
        .header("Content-Type", "application/json; charset=UTF-8")
        .header("X-Upload-Content-Type", mimeType)
        .header("X-Upload-Content-Length", size.toString())
        .post(metadataJson.toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull()))
        .build()
    return httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw DriveHttpException(response.code)
        response.header("Location") ?: throw MissingUploadLocationException()
    }
}

private fun extractFileIdFromJson(json: String): String? =
    """"(?:id|fileId)"\s*:\s*"([^"]+)""".toRegex().find(json)?.groupValues?.getOrNull(1)

private fun parseCommittedOffset(range: String?, currentOffset: Long, endExclusive: Long): Long {
    val end = range
        ?.trim()
        ?.let { value ->
            Regex("bytes\\s*[= ]\\s*\\d+-(\\d+)(?:/\\d+)?").find(value)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: value.substringAfterLast('-').substringBefore('/').toLongOrNull()
        }
    return if (end != null) maxOf(currentOffset, end + 1L) else endExclusive.coerceAtLeast(currentOffset)
}

private fun classifyException(
    exception: Exception,
    providerId: String,
    sessionUri: String? = null,
    bytesCommitted: Long = 0L
): CloudSyncResult.Error {
    val retryable = exception is java.net.UnknownHostException ||
        exception is java.net.ConnectException ||
        exception is IOException ||
        exception.message?.contains("Unable to resolve host") == true
    val domainError = DomainErrorMapper.fromThrowable(
        operation = "DRIVE_TRANSFER",
        cause = exception,
        provider = providerId
    )
    DiagnosticLogger.log("GoogleDriveProviderAdapter", domainError)
    return CloudSyncResult.Error(
        message = domainError.userMessage.value,
        isRetryable = retryable,
        cause = exception,
        domainError = domainError,
        resumableSessionUri = sessionUri,
        bytesCommitted = bytesCommitted
    )
}

private fun findFileIds(httpClient: OkHttpClient, authorization: String, query: String): List<String> {
    val ids = mutableListOf<String>()
    var pageToken: String? = null
    do {
        val urlBuilder = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "nextPageToken,incompleteSearch,files(id)")
            .addQueryParameter("pageSize", DRIVE_QUERY_PAGE_SIZE.toString())
        pageToken?.let { urlBuilder.addQueryParameter("pageToken", it) }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", authorization)
            .get()
            .build()
        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DriveHttpException(response.code)
            response.body?.string().orEmpty()
        }
        val root = Gson().fromJson(body, Map::class.java)
        val files = root["files"] as? List<*> ?: emptyList<Any>()
        files.mapNotNullTo(ids) {
            val file = it as? Map<*, *>
            (file?.get("id") ?: file?.get("fileId"))?.toString()
        }
        pageToken = root["nextPageToken"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
    } while (pageToken != null)
    return ids
}

private class DriveHttpException(val code: Int) : IOException("HTTP $code")

private class MissingUploadLocationException : IOException("Missing Location header")

private const val DRIVE_QUERY_PAGE_SIZE = 1_000
