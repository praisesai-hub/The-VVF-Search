package com.example.data

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import com.example.context.drive.DriveAuthorizationPort
import java.io.IOException

/**
 * Google Drive implementation of [CloudProviderAdapter] supporting authenticated access,
 * metadata creation, resumable upload preparation, secure upload, and remote file ID handling.
 */
class GoogleDriveProviderAdapter(
    private val driveAuthorization: DriveAuthorizationPort,
    private val httpClient: OkHttpClient = OkHttpClient()
) : CloudProviderAdapter {

    override val providerId: String = "GOOGLE_DRIVE"

    @Suppress("LongMethod", "NestedBlockDepth", "ReturnCount")
    override suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult =
        uploadFileWithOperationId(file, remotePath, null)

    @Suppress("LongMethod", "NestedBlockDepth", "ReturnCount")
    override suspend fun uploadFile(file: File, remotePath: String, operationId: String): CloudSyncResult =
        uploadFileWithOperationId(file, remotePath, operationId)

    @Suppress("LongMethod", "NestedBlockDepth", "ReturnCount")
    private suspend fun uploadFileWithOperationId(
        file: File,
        remotePath: String,
        operationId: String?
    ): CloudSyncResult {
        if (!file.exists() || !file.isFile) {
            return CloudSyncResult.Error(
                message = "The selected file is unavailable.",
                isRetryable = false
            )
        }

        val authorization = driveAuthorization.authorizationHeader() ?: return CloudSyncResult.Error(
            message = "Upload failed: user is not authenticated with Google Drive.",
            isRetryable = false
        )

        return try {
            if (!operationId.isNullOrBlank()) {
                val lookupRequest = Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files?q=appProperties%20has%20%7B%20key='vvf_operation_id'%20and%20value='$operationId'%20%7D%20and%20trashed=false")
                    .header("Authorization", authorization)
                    .get()
                    .build()
                httpClient.newCall(lookupRequest).execute().use { lookupResponse ->
                    val lookupBody = lookupResponse.body?.string().orEmpty()
                    if (!lookupResponse.isSuccessful) {
                        return classifyHttpError("Cloud idempotency lookup failed", lookupResponse.code, lookupBody)
                    }
                    if (hasExistingOperation(lookupBody)) return CloudSyncResult.Success()
                }
            }

            val mimeType = determineMimeType(file)
            val metadata = mutableMapOf<String, Any>(
                "name" to remotePath,
                "description" to "Uploaded via Smart Vault Engine"
            )
            if (!operationId.isNullOrBlank()) {
                metadata["appProperties"] = mapOf("vvf_operation_id" to operationId)
            }
            val metadataJson = Gson().toJson(metadata)

            // 1. Prepare Resumable Upload
            val initRequest = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", file.length().toString())
                .post(metadataJson.toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull()))
                .build()

            httpClient.newCall(initRequest).execute().use { initResponse ->
                if (!initResponse.isSuccessful) {
                    return classifyHttpError("Resumable upload preparation failed", initResponse.code, initResponse.body?.string())
                }

                val uploadUrl = initResponse.header("Location") ?: return CloudSyncResult.Error(
                    message = "Cloud upload could not be initialized.",
                    isRetryable = false
                )

                // 2. Perform upload
                val uploadRequest = Request.Builder()
                    .url(uploadUrl)
                    .put(file.asRequestBody(mimeType.toMediaTypeOrNull()))
                    .build()

                return httpClient.newCall(uploadRequest).execute().use { uploadResponse ->
                    if (!uploadResponse.isSuccessful) {
                        return classifyHttpError(
                            "File upload failed",
                            uploadResponse.code,
                            uploadResponse.body?.string()
                        )
                    }

                    val responseBody = uploadResponse.body?.string() ?: ""
                    val fileId = extractFileIdFromJson(responseBody)
                    if (fileId != null) {
                        CloudSyncResult.Success(bytesTransferred = file.length())
                    } else {
                        CloudSyncResult.Error(
                            message = "Failed to parse remote file ID from response",
                            isRetryable = false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            classifyException(e)
        }
    }

    private fun hasExistingOperation(body: String): Boolean = runCatching {
        val files = Gson().fromJson(body, Map::class.java)["files"] as? List<*>
        !files.isNullOrEmpty()
    }.getOrDefault(false)

    @Suppress("NestedBlockDepth", "ReturnCount")
    override suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult {
        val authorization = driveAuthorization.authorizationHeader() ?: return CloudSyncResult.Error(
            message = "Download failed: user is not authenticated with Google Drive.",
            isRetryable = false
        )

        return try {
            // 1. Search for file ID matching remotePath name
            val searchRequest = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?q=name='$remotePath' and trashed=false")
                .header("Authorization", authorization)
                .get()
                .build()

            httpClient.newCall(searchRequest).execute().use { searchResponse ->
                if (!searchResponse.isSuccessful) {
                    return classifyHttpError("File search failed", searchResponse.code, searchResponse.body?.string())
                }

                val searchBody = searchResponse.body?.string() ?: ""
                val fileId = extractFileIdFromJson(searchBody) ?: return CloudSyncResult.Error(
                    message = "The requested cloud file was not found.",
                    isRetryable = false
                )

                // 2. Download media content
                val downloadRequest = Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                    .header("Authorization", authorization)
                    .get()
                    .build()

                return httpClient.newCall(downloadRequest).execute().use { downloadResponse ->
                    if (!downloadResponse.isSuccessful) {
                        return classifyHttpError("Media download failed", downloadResponse.code, downloadResponse.body?.string())
                    }

                    val responseBody = downloadResponse.body ?: return CloudSyncResult.Error(
                        message = "Media download response body was empty.",
                        isRetryable = false
                    )

                    val bytesTransferred = responseBody.byteStream().use { input ->
                        destinationFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (bytesTransferred <= 0L) {
                        destinationFile.delete()
                        return CloudSyncResult.Error(
                            message = "Media download response body was empty.",
                            isRetryable = false
                        )
                    }
                    CloudSyncResult.Success(bytesTransferred = bytesTransferred)
                }
            }
        } catch (e: Exception) {
            classifyException(e)
        }
    }

    private fun determineMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "txt" -> "text/plain"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun extractFileIdFromJson(json: String): String? {
        val regex = """"(?:id|fileId)"\s*:\s*"([^"]+)"""".toRegex()
        val matchResult = regex.find(json)
        return matchResult?.groupValues?.get(1)
    }

    private fun classifyHttpError(context: String, code: Int, errorBody: String?): CloudSyncResult.Error {
        val safeMessage = "$context: HTTP $code"
        val isRetryable = code == 408 || code == 429 || code >= 500
        return CloudSyncResult.Error(
            message = safeMessage,
            isRetryable = isRetryable,
            domainError = com.example.domain.error.DomainErrorMapper.fromThrowable(
                operation = "DRIVE_HTTP_REQUEST",
                cause = IOException("HTTP $code"),
                provider = providerId
            )
        )
    }

    private fun classifyException(e: Exception): CloudSyncResult.Error {
        val isRetryable = e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e is IOException ||
                e.message?.contains("Unable to resolve host") == true
        val domainError = com.example.domain.error.DomainErrorMapper.fromThrowable(
            operation = "DRIVE_TRANSFER",
            cause = e,
            provider = providerId
        )
        com.example.domain.error.DiagnosticLogger.log("GoogleDriveProviderAdapter", domainError)
        return CloudSyncResult.Error(
            message = domainError.userMessage.value,
            isRetryable = isRetryable,
            cause = e,
            domainError = domainError
        )
    }
}
