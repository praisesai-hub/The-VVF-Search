package com.example.data

import com.example.context.drive.DriveAuthorizationPort
import com.example.domain.error.DiagnosticLogger
import com.example.domain.error.DomainErrorMapper
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** Google Drive adapter with operation-ID deduplication and resumable upload recovery. */
// Protocol endpoints remain co-located to preserve shared authorization and retry semantics.
@Suppress(
    "detekt.TooManyFunctions",
    "detekt.LongMethod",
    "detekt.CyclomaticComplexMethod",
)
class GoogleDriveProviderAdapter(
    private val driveAuthorization: DriveAuthorizationPort,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : CloudProviderAdapter {
    override val providerId: String = "GOOGLE_DRIVE"

    override suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult =
        uploadFile(file, remotePath, "", CloudTransferState()) {}

    override suspend fun uploadFile(
        file: File,
        remotePath: String,
        operationId: String,
    ): CloudSyncResult = uploadFile(file, remotePath, operationId, CloudTransferState()) {}

    @Suppress(
        "detekt.LongMethod",
        "detekt.CyclomaticComplexMethod",
        "detekt.NestedBlockDepth",
        "detekt.ReturnCount",
    )
    override suspend fun uploadFile(
        file: File,
        remotePath: String,
        operationId: String,
        transferState: CloudTransferState,
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ): CloudSyncResult {
        if (!file.exists() || !file.isFile) {
            return CloudSyncResult.Error("The selected file is unavailable.", false)
        }
        val authorization =
            driveAuthorization.authorizationHeader()
                ?: return CloudSyncResult.Error(
                    "Upload failed: user is not authenticated with Google Drive.",
                    false,
                )
        return try {
            if (operationId.isNotBlank()) {
                val existing =
                    findFileIds(
                            authorization,
                            "appProperties has { key='vvf_operation_id' " +
                                "and value='${escapeDriveQueryValue(operationId)}' } " +
                                "and trashed = false",
                        )
                        .firstOrNull()
                if (existing != null) return CloudSyncResult.Success(file.length(), existing)
            }

            val mimeType = determineMimeType(file)
            val metadata =
                mutableMapOf<String, Any>(
                    "name" to remotePath,
                    "description" to "Uploaded via Smart Vault Engine",
                )
            if (operationId.isNotBlank())
                metadata["appProperties"] = mapOf("vvf_operation_id" to operationId)
            val metadataJson = Gson().toJson(metadata)
            val sessionUri =
                if (transferState.resumableSessionUri.isNotBlank()) {
                    transferState.resumableSessionUri
                } else {
                    initiateResumableUpload(authorization, mimeType, file.length(), metadataJson)
                        ?: return CloudSyncResult.Error(
                            "Cloud upload could not be initialized: Missing 'Location' header.",
                            false,
                        )
                }

            onProgress(
                CloudTransferProgress(
                    resumableSessionUri = sessionUri,
                    bytesCommitted = transferState.bytesCommitted,
                )
            )
            var offset = transferState.bytesCommitted.coerceIn(0L, file.length())
            if (transferState.resumableSessionUri.isNotBlank()) {
                when (val probe = queryUploadOffset(authorization, sessionUri, file.length())) {
                    is UploadProbe.Completed ->
                        return CloudSyncResult.Success(
                            file.length(),
                            probe.fileId,
                            sessionUri,
                            file.length(),
                        )
                    is UploadProbe.Offset -> offset = probe.offset
                    UploadProbe.Unknown -> Unit
                }
            }

            while (offset < file.length()) {
                val endExclusive = minOf(offset + UPLOAD_CHUNK_BYTES, file.length())
                val request =
                    Request.Builder()
                        .url(sessionUri)
                        .header("Authorization", authorization)
                        .header("Content-Length", (endExclusive - offset).toString())
                        .header(
                            "Content-Range",
                            "bytes $offset-${endExclusive - 1}/${file.length()}",
                        )
                        .put(
                            FileSliceRequestBody(
                                file,
                                offset,
                                endExclusive - offset,
                                mimeType.toMediaTypeOrNull(),
                            )
                        )
                        .build()
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == RESUMABLE_INCOMPLETE_STATUS -> {
                            offset =
                                parseCommittedOffset(response.header("Range"), offset, endExclusive)
                            onProgress(
                                CloudTransferProgress(
                                    resumableSessionUri = sessionUri,
                                    bytesCommitted = offset,
                                )
                            )
                        }
                        response.isSuccessful -> {
                            val remoteId =
                                extractFileIdFromJson(response.body?.string().orEmpty())
                                    ?: return CloudSyncResult.Error(
                                        "Failed to parse remote file ID from response",
                                        false,
                                        resumableSessionUri = sessionUri,
                                        bytesCommitted = endExclusive,
                                    )
                            onProgress(CloudTransferProgress(remoteId, sessionUri, file.length()))
                            return CloudSyncResult.Success(
                                file.length(),
                                remoteId,
                                sessionUri,
                                file.length(),
                            )
                        }
                        else ->
                            return classifyHttpError(
                                "File upload failed",
                                response.code,
                                sessionUri,
                                offset,
                            )
                    }
                }
            }
            CloudSyncResult.Error(
                "Cloud upload ended without a completion response.",
                true,
                resumableSessionUri = sessionUri,
                bytesCommitted = offset,
            )
        } catch (e: DriveHttpException) {
            classifyHttpError(
                "Resumable upload preparation failed",
                e.code,
                transferState.resumableSessionUri,
                transferState.bytesCommitted,
            )
        } catch (_: MissingUploadLocationException) {
            CloudSyncResult.Error(
                "Cloud upload could not be initialized: Missing 'Location' header.",
                false,
            )
        } catch (e: Exception) {
            classifyException(e, transferState.resumableSessionUri, transferState.bytesCommitted)
        }
    }

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "ReturnCount",
    )
    override suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult {
        val authorization =
            driveAuthorization.authorizationHeader()
                ?: return CloudSyncResult.Error(
                    "Download failed: user is not authenticated with Google Drive.",
                    false,
                )
        return try {
            val fileId =
                findFileIds(
                        authorization,
                        "name = '${escapeDriveQueryValue(remotePath)}' and trashed = false",
                    )
                    .firstOrNull()
                    ?: return CloudSyncResult.Error(
                        "The requested cloud file was not found.",
                        false,
                    )
            val request =
                Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                    .header("Authorization", authorization)
                    .get()
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return classifyHttpError(
                        "Media download failed",
                        response.code,
                    )
                }
                val body =
                    response.body
                        ?: return CloudSyncResult.Error(
                            "Media download response body was empty.",
                            false,
                        )
                destinationFile.parentFile?.mkdirs()
                val temporaryFile =
                    File(destinationFile.parentFile ?: File("."), ".${destinationFile.name}.part")
                try {
                    val bytes =
                        body.byteStream().use { input ->
                            temporaryFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    if (bytes <= 0L) {
                        return CloudSyncResult.Error(
                            "Media download response body was empty.",
                            false,
                        )
                    }
                    if (destinationFile.exists() && !destinationFile.delete()) {
                        return CloudSyncResult.Error(
                            "Unable to replace the local download safely.",
                            false,
                        )
                    }
                    if (!temporaryFile.renameTo(destinationFile)) {
                        return CloudSyncResult.Error(
                            "Unable to finalize the local download safely.",
                            false,
                        )
                    }
                    CloudSyncResult.Success(bytes, fileId, bytesCommitted = bytes)
                } finally {
                    if (temporaryFile.exists()) temporaryFile.delete()
                }
            }
        } catch (e: DriveHttpException) {
            classifyHttpError("File search failed", e.code, null)
        } catch (e: Exception) {
            classifyException(e)
        }
    }

    private fun initiateResumableUpload(
        authorization: String,
        mimeType: String,
        size: Long,
        metadataJson: String,
    ): String? {
        val request =
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", size.toString())
                .post(
                    metadataJson.toRequestBody(
                        "application/json; charset=UTF-8".toMediaTypeOrNull()
                    )
                )
                .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DriveHttpException(response.code)
            response.header("Location") ?: throw MissingUploadLocationException()
        }
    }

    private fun queryUploadOffset(
        authorization: String,
        sessionUri: String,
        size: Long,
    ): UploadProbe {
        val request =
            Request.Builder()
                .url(sessionUri)
                .header("Authorization", authorization)
                .header("Content-Length", "0")
                .header("Content-Range", "bytes */$size")
                .put(ByteArray(0).toRequestBody(null))
                .build()
        return runCatching {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == RESUMABLE_INCOMPLETE_STATUS ->
                            UploadProbe.Offset(
                                parseCommittedOffset(response.header("Range"), 0L, 0L)
                            )
                        response.isSuccessful ->
                            extractFileIdFromJson(response.body?.string().orEmpty())
                                ?.let(UploadProbe::Completed) ?: UploadProbe.Unknown
                        else -> UploadProbe.Unknown
                    }
                }
            }
            .getOrDefault(UploadProbe.Unknown)
    }

    private fun findFileIds(authorization: String, query: String): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val urlBuilder =
                "https://www.googleapis.com/drive/v3/files"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("spaces", "drive")
                    .addQueryParameter("fields", "nextPageToken,incompleteSearch,files(id)")
                    .addQueryParameter("pageSize", "1000")
            pageToken?.let { urlBuilder.addQueryParameter("pageToken", it) }
            val request =
                Request.Builder()
                    .url(urlBuilder.build())
                    .header("Authorization", authorization)
                    .get()
                    .build()
            val body =
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw DriveHttpException(response.code)
                    response.body?.string().orEmpty()
                }
            val root = Gson().fromJson(body, Map::class.java)
            val files = root["files"] as? List<*> ?: emptyList<Any>()
            files.mapNotNullTo(ids) {
                val file = it as? Map<*, *>
                (file?.get("id") ?: file?.get("fileId"))?.toString()
            }
            pageToken =
                root["nextPageToken"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        } while (pageToken != null)
        return ids
    }

    private fun determineMimeType(file: File): String =
        when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg",
            "jpeg" -> "image/jpeg"
            "txt" -> "text/plain"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

    private fun extractFileIdFromJson(json: String): String? =
        """"(?:id|fileId)"\s*:\s*"([^"]+)""".toRegex().find(json)?.groupValues?.get(1)

    private fun escapeDriveQueryValue(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    private fun parseCommittedOffset(
        range: String?,
        currentOffset: Long,
        endExclusive: Long,
    ): Long {
        val end =
            range
                ?.substringAfterLast('-')
                ?.trim()
                ?.toLongOrNull()
        return if (end != null) maxOf(currentOffset, end + 1L)
        else endExclusive.coerceAtLeast(currentOffset)
    }

    private fun classifyHttpError(
        context: String,
        code: Int,
        sessionUri: String? = null,
        bytesCommitted: Long = 0L,
    ): CloudSyncResult.Error {
        val retryable =
            code == REQUEST_TIMEOUT_STATUS ||
                code == TOO_MANY_REQUESTS_STATUS ||
                code >= SERVER_ERROR_STATUS_THRESHOLD ||
                (code == NOT_FOUND_STATUS && sessionUri != null)
        return CloudSyncResult.Error(
            message = "$context: HTTP $code",
            isRetryable = retryable,
            domainError =
                DomainErrorMapper.fromThrowable(
                    operation = "DRIVE_HTTP_REQUEST",
                    cause = IOException("HTTP $code"),
                    provider = providerId,
                ),
            resumableSessionUri = sessionUri,
            bytesCommitted = bytesCommitted,
        )
    }

    private fun classifyException(
        exception: Exception,
        sessionUri: String? = null,
        bytesCommitted: Long = 0L,
    ): CloudSyncResult.Error {
        val retryable =
            exception is java.net.UnknownHostException ||
                exception is java.net.ConnectException ||
                exception is IOException ||
                exception.message?.contains("Unable to resolve host") == true
        val domainError =
            DomainErrorMapper.fromThrowable(
                operation = "DRIVE_TRANSFER",
                cause = exception,
                provider = providerId,
            )
        DiagnosticLogger.log("GoogleDriveProviderAdapter", domainError)
        return CloudSyncResult.Error(
            message = domainError.userMessage.value,
            isRetryable = retryable,
            cause = exception,
            domainError = domainError,
            resumableSessionUri = sessionUri,
            bytesCommitted = bytesCommitted,
        )
    }

    private class DriveHttpException(val code: Int) : IOException("HTTP $code")

    private class MissingUploadLocationException : IOException("Missing Location header")

    private sealed class UploadProbe {
        data class Offset(val offset: Long) : UploadProbe()

        data class Completed(val fileId: String) : UploadProbe()

        data object Unknown : UploadProbe()
    }

    private class FileSliceRequestBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val mediaType: MediaType?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
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

    private companion object {
        const val RESUMABLE_INCOMPLETE_STATUS = 308
        const val REQUEST_TIMEOUT_STATUS = 408
        const val TOO_MANY_REQUESTS_STATUS = 429
        const val NOT_FOUND_STATUS = 404
        const val SERVER_ERROR_STATUS_THRESHOLD = 500
        const val TRANSFER_BUFFER_SIZE = 64 * 1024
        const val UPLOAD_CHUNK_BYTES = 256L * 1024L
    }
}
