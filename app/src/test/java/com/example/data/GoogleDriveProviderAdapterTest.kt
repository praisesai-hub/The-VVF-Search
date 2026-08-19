package com.example.data

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class GoogleDriveProviderAdapterTest {

    private lateinit var authManager: GoogleAuthManager
    private lateinit var sharedPrefs: FakeSharedPreferences
    private lateinit var fakeInterceptor: FakeInterceptor
    private lateinit var httpClient: OkHttpClient
    private lateinit var adapter: GoogleDriveProviderAdapter

    @Before
    fun setUp() {
        sharedPrefs = FakeSharedPreferences()
        authManager = GoogleAuthManager(sharedPrefs)
        fakeInterceptor = FakeInterceptor()
        httpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()
        adapter = GoogleDriveProviderAdapter(authManager, httpClient)
    }

    @Test
    fun testUploadFile_WhenFileDoesNotExist() {
        val nonExistentFile = File("non_existent_file.txt")
        val result = kotlinx.coroutines.runBlocking {
            adapter.uploadFile(nonExistentFile, "remote.txt")
        }
        assertTrue(result is CloudSyncResult.Error)
        assertEquals("File does not exist or is invalid: ${nonExistentFile.absolutePath}", (result as CloudSyncResult.Error).message)
    }

    @Test
    fun testUploadFile_WhenNotAuthenticated() {
        val tempFile = File.createTempFile("test_upload", ".txt")
        tempFile.writeText("hello")
        tempFile.deleteOnExit()

        val result = kotlinx.coroutines.runBlocking {
            adapter.uploadFile(tempFile, "remote.txt")
        }
        assertTrue(result is CloudSyncResult.Error)
        assertTrue((result as CloudSyncResult.Error).message.contains("user is not authenticated"))
    }

    @Test
    fun testUploadFile_Success() {
        val tempFile = File.createTempFile("test_upload", ".txt")
        tempFile.writeText("hello")
        tempFile.deleteOnExit()

        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")

        fakeInterceptor.responseProvider = { request ->
            if (request.method == "GET" && request.url.encodedPath.endsWith("/drive/v3/files")) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"files\":[]}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            } else if (request.method == "POST") {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Location", "https://upload.googleapis.com/resumable/file_id_123")
                    .body("".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            } else {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"id\":\"file_id_123\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            adapter.uploadFile(tempFile, "remote.txt")
        }

        if (result is CloudSyncResult.Error) {
            fail("Upload failed with error: ${result.message}, cause: ${result.cause?.stackTraceToString()}")
        }
        assertTrue(result is CloudSyncResult.Success)
        assertEquals(tempFile.length(), (result as CloudSyncResult.Success).bytesTransferred)
    }

    @Test
    @Suppress("LongMethod")
    fun uploadFile_308AcknowledgementImmediatelyContinuesFromServerRange() {
        val firstChunkSize = 256 * 1024
        val tempFile = File.createTempFile("resumable_chunk", ".bin").apply {
            writeBytes(ByteArray(firstChunkSize + 17) { 0x5A.toByte() })
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        var requestCount = 0
        fakeInterceptor.responseProvider = { request ->
            requestCount += 1
            when (requestCount) {
                1 -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"files\":[]}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
                2 -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Location", "https://upload.googleapis.com/resumable/chunked")
                    .body("".toResponseBody())
                    .build()
                3 -> {
                    assertEquals(
                        "bytes 0-${firstChunkSize - 1}/${tempFile.length()}",
                        request.header("Content-Range")
                    )
                    assertEquals(firstChunkSize.toLong(), request.body?.contentLength())
                    val uploadedChunk = Buffer().also { buffer -> request.body?.writeTo(buffer) }
                    assertEquals(firstChunkSize.toLong(), uploadedChunk.size)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(308)
                        .message("Resume Incomplete")
                        .header("Range", "bytes=0-${firstChunkSize - 1}")
                        .body("".toResponseBody())
                        .build()
                }
                4 -> {
                    assertEquals(
                        "bytes $firstChunkSize-${tempFile.length() - 1}/${tempFile.length()}",
                        request.header("Content-Range")
                    )
                    assertEquals(17L, request.body?.contentLength())
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{\"id\":\"chunked-file\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                }
                else -> error("Unexpected request #$requestCount: ${request.method} ${request.url}")
            }
        }

        val result = kotlinx.coroutines.runBlocking { adapter.uploadFile(tempFile, "chunked.bin") }

        assertTrue(result is CloudSyncResult.Success)
        result as CloudSyncResult.Success
        assertEquals("chunked-file", result.remoteFileId)
        assertEquals(4, requestCount)
    }

    @Test
    fun testUploadFile_HttpError() {
        val tempFile = File.createTempFile("test_upload", ".txt")
        tempFile.writeText("hello")
        tempFile.deleteOnExit()

        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")

        fakeInterceptor.responseProvider = { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body("Server Error".toResponseBody("text/plain".toMediaTypeOrNull()))
                .build()
        }

        val result = kotlinx.coroutines.runBlocking {
            adapter.uploadFile(tempFile, "remote.txt")
        }

        if (result is CloudSyncResult.Success) {
            fail("Expected HTTP Error, but succeeded!")
        }
        assertTrue(result is CloudSyncResult.Error)
        val errorResult = result as CloudSyncResult.Error
        assertTrue("Expected HTTP 500 in message: ${errorResult.message}", errorResult.message.contains("HTTP 500"))
        assertTrue(errorResult.isRetryable)
    }

    @Test
    fun testDownloadFile_Success() {
        val tempFile = File.createTempFile("test_download", ".txt")
        tempFile.deleteOnExit()

        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")

        fakeInterceptor.responseProvider = { request ->
            assertEquals("/drive/v3/files/gdrive_file_id_456", request.url.encodedPath)
            assertEquals("media", request.url.queryParameter("alt"))
            assertEquals("true", request.url.queryParameter("supportsAllDrives"))
            assertEquals(null, request.url.queryParameter("q"))
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("downloaded content".toResponseBody("text/plain".toMediaTypeOrNull()))
                .build()
        }

        val result = kotlinx.coroutines.runBlocking {
            adapter.downloadFile("gdrive_file_id_456", tempFile)
        }

        assertTrue(result is CloudSyncResult.Success)
        assertEquals("downloaded content", tempFile.readText())
        assertEquals("gdrive_file_id_456", (result as CloudSyncResult.Success).remoteFileId)
    }

    @Test
    fun testDownloadFile_UsesPersistedIdWithoutFilenameQuery() {
        val tempFile = File.createTempFile("test_download_id_only", ".txt")
        tempFile.deleteOnExit()
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")

        fakeInterceptor.responseProvider = { request ->
            assertEquals("/drive/v3/files/persisted-file-id", request.url.encodedPath)
            assertEquals(null, request.url.queryParameter("q"))
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("id-only content".toResponseBody("text/plain".toMediaTypeOrNull()))
                .build()
        }

        val result = kotlinx.coroutines.runBlocking { adapter.downloadFile("persisted-file-id", tempFile) }

        assertTrue(result is CloudSyncResult.Success)
        assertEquals("id-only content", tempFile.readText())
    }

    @Test
    fun uploadFile_lostFinalResponse_recoversCompletedPersistedSessionWithoutNewUpload() {
        val sourceFile = File.createTempFile("lost_final_response", ".txt").apply {
            writeText("durable source")
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        var requestCount = 0
        fakeInterceptor.responseProvider = { request ->
            requestCount += 1
            assertEquals("PUT", request.method)
            assertEquals("bytes */${sourceFile.length()}", request.header("Content-Range"))
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("ETag", "etag-recovered")
                .body(
                    "{\"id\":\"drive-recovered\",\"headRevisionId\":\"revision-recovered\"}"
                        .toResponseBody("application/json".toMediaTypeOrNull())
                )
                .build()
        }

        val result = kotlinx.coroutines.runBlocking {
            adapter.uploadFile(
                source = CloudUploadSource.LocalFile(sourceFile),
                remotePath = "durable.txt",
                remoteFileId = "drive-recovered",
                idempotencyKey = "provider|stable-id|sha256",
                contentHash = "sha256",
                uploadSessionUri = "https://upload.googleapis.com/resumable/recovered-session"
            )
        }

        assertTrue(result is CloudSyncResult.Success)
        result as CloudSyncResult.Success
        assertEquals(1, requestCount)
        assertEquals("drive-recovered", result.remoteFileId)
        assertEquals("revision-recovered", result.remoteRevisionId)
        assertEquals("etag-recovered", result.etag)
        assertEquals("sha256", result.contentHash)
    }

    @Test
    fun testUploadFile_MissingLocationHeaderFailsClosed() {
        val file = File.createTempFile("upload_missing_location", ".pdf").apply {
            writeText("pdf-like content")
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        fakeInterceptor.responseProvider = { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val result = kotlinx.coroutines.runBlocking { adapter.uploadFile(file, "remote.pdf") }

        assertTrue(result is CloudSyncResult.Error)
        val error = result as CloudSyncResult.Error
        assertTrue(error.message.contains("Location header"))
        assertFalse(error.isRetryable)
    }

    @Test
    fun testUploadFile_RateLimitIsRetryable() {
        val file = File.createTempFile("upload_rate_limit", ".txt").apply {
            writeText("content")
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        fakeInterceptor.responseProvider = { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .body("rate limited".toResponseBody("text/plain".toMediaTypeOrNull()))
                .build()
        }

        val result = kotlinx.coroutines.runBlocking { adapter.uploadFile(file, "remote.txt") }

        assertTrue(result is CloudSyncResult.Error)
        val error = result as CloudSyncResult.Error
        assertTrue(error.message.contains("HTTP 429"))
        assertTrue(error.isRetryable)
    }

    @Test
    fun testUploadFile_MalformedRemoteResponseIsNonRetryable() {
        val file = File.createTempFile("upload_malformed", ".json").apply {
            writeText("{}")
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        var callCount = 0
        fakeInterceptor.responseProvider = { request ->
            callCount++
            if (callCount == 1) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"files\":[]}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            } else if (callCount == 2) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Location", "https://upload.googleapis.com/resumable/malformed")
                    .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            } else {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"name\":\"without-id\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
        }

        val result = kotlinx.coroutines.runBlocking { adapter.uploadFile(file, "remote.json") }

        assertTrue(result is CloudSyncResult.Error)
        val error = result as CloudSyncResult.Error
        assertTrue(error.message.contains("Failed to parse remote file ID"))
        assertFalse(error.isRetryable)
    }

    @Test
    fun testUploadFile_ConnectionExceptionIsRetryable() {
        val file = File.createTempFile("upload_network", ".txt").apply {
            writeText("content")
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        fakeInterceptor.responseProvider = { throw java.net.ConnectException("offline") }

        val result = kotlinx.coroutines.runBlocking { adapter.uploadFile(file, "remote.txt") }

        assertTrue(result is CloudSyncResult.Error)
        val error = result as CloudSyncResult.Error
        assertTrue(error.isRetryable)
        assertEquals("offline", error.message)
    }

    @Test
    fun testDownloadFile_WhenNotAuthenticated() {
        val destination = File.createTempFile("download_unauthenticated", ".txt")
        destination.deleteOnExit()

        val result = kotlinx.coroutines.runBlocking {
            adapter.downloadFile("remote-file-id", destination)
        }

        assertTrue(result is CloudSyncResult.Error)
        assertFalse((result as CloudSyncResult.Error).isRetryable)
        assertTrue(result.message.contains("user is not authenticated"))
    }

    @Test
    fun testDownloadFile_MediaHttpErrorIsNotRetryableForNotFound() {
        val destination = File.createTempFile("download_search_error", ".txt")
        destination.deleteOnExit()
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        fakeInterceptor.responseProvider = { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("missing".toResponseBody("text/plain".toMediaTypeOrNull()))
                .build()
        }

        val result = kotlinx.coroutines.runBlocking { adapter.downloadFile("remote-file-id", destination) }

        assertTrue(result is CloudSyncResult.Error)
        val error = result as CloudSyncResult.Error
        assertTrue(error.message.contains("Media download failed: HTTP 404"))
        assertFalse(error.isRetryable)
        assertEquals("remote-file-id", error.remoteFileId)
    }

    @Test
    fun testDownloadFile_RejectsMissingPersistedRemoteIdWithoutNetworkCall() {
        val destination = File.createTempFile("download_not_found", ".txt")
        destination.deleteOnExit()
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        fakeInterceptor.responseProvider = { error("Download should not issue a network request") }

        val result = kotlinx.coroutines.runBlocking { adapter.downloadFile("", destination) }

        assertTrue(result is CloudSyncResult.Error)
        assertTrue((result as CloudSyncResult.Error).message.contains("file ID is missing"))
    }

    @Test
    fun testDownloadFile_MediaServerErrorIsRetryable() {
        val destination = File.createTempFile("download_media_error", ".txt")
        destination.deleteOnExit()
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        fakeInterceptor.responseProvider = { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Unavailable")
                .body("try later".toResponseBody("text/plain".toMediaTypeOrNull()))
                .build()
        }

        val result = kotlinx.coroutines.runBlocking { adapter.downloadFile("id-789", destination) }

        assertTrue(result is CloudSyncResult.Error)
        assertTrue((result as CloudSyncResult.Error).message.contains("HTTP 503"))
        assertTrue(result.isRetryable)
    }

    @Test
    fun testDownloadFile_ConnectionExceptionIsRetryable() {
        val destination = File.createTempFile("download_network", ".txt")
        destination.deleteOnExit()
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        fakeInterceptor.responseProvider = { throw java.net.UnknownHostException("no network") }

        val result = kotlinx.coroutines.runBlocking { adapter.downloadFile("remote-file-id", destination) }

        assertTrue(result is CloudSyncResult.Error)
        val error = result as CloudSyncResult.Error
        assertTrue(error.isRetryable)
        assertEquals("no network", error.message)
    }

    @Test
    fun uploadFile_completedPersistedSessionWithoutId_recoversEscapedSyncIdentity() {
        val source = File.createTempFile("recovery_escaped_identity", ".txt").apply {
            writeText("recoverable")
            deleteOnExit()
        }
        val identity = "owner's\\stable-id"
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        var requestCount = 0
        fakeInterceptor.responseProvider = { request ->
            requestCount += 1
            when (requestCount) {
                1 -> {
                    assertEquals("PUT", request.method)
                    assertEquals("bytes */${source.length()}", request.header("Content-Range"))
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                }
                2 -> {
                    assertEquals("GET", request.method)
                    assertTrue(request.url.queryParameter("q").orEmpty().contains("owner\\'s\\\\stable-id"))
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            "{\"files\":[{\"id\":\"recovered-id\"}]}"
                                .toResponseBody("application/json".toMediaTypeOrNull())
                        )
                        .build()
                }
                else -> error("Unexpected request #$requestCount")
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            adapter.uploadFile(
                source = CloudUploadSource.LocalFile(source),
                remotePath = "recoverable.txt",
                idempotencyKey = identity,
                contentHash = "content-hash",
                uploadSessionUri = "https://upload.googleapis.com/resumable/completed"
            )
        }

        assertTrue(result is CloudSyncResult.Success)
        assertEquals("recovered-id", (result as CloudSyncResult.Success).remoteFileId)
        assertEquals(2, requestCount)
    }

    @Test
    @Suppress("LongMethod")
    fun uploadFile_stalePersistedSessionClearsCheckpointThenReplacesKnownRemoteFile() {
        val source = File.createTempFile("stale_session", ".txt").apply {
            writeText("replacement")
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        val checkpoints = mutableListOf<CloudSyncCheckpoint>()
        var requestCount = 0
        fakeInterceptor.responseProvider = { request ->
            requestCount += 1
            when (requestCount) {
                1 -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Expired")
                    .body("expired".toResponseBody())
                    .build()
                2 -> {
                    assertEquals("PATCH", request.method)
                    assertTrue(request.url.encodedPath.endsWith("/drive/v3/files/known-id"))
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Location", "https://upload.googleapis.com/resumable/replacement")
                        .body("".toResponseBody())
                        .build()
                }
                3 -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        "{\"id\":\"known-id\",\"headRevisionId\":\"revision-2\"}"
                            .toResponseBody("application/json".toMediaTypeOrNull())
                    )
                    .build()
                else -> error("Unexpected request #$requestCount")
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            adapter.uploadFile(
                source = CloudUploadSource.LocalFile(source),
                remotePath = "replacement.txt",
                remoteFileId = "known-id",
                idempotencyKey = "known-identity",
                uploadSessionUri = "https://upload.googleapis.com/resumable/expired",
                onCheckpoint = { checkpoints += it }
            )
        }

        assertTrue(result is CloudSyncResult.Success)
        assertEquals("known-id", (result as CloudSyncResult.Success).remoteFileId)
        assertEquals(3, requestCount)
        assertEquals("", checkpoints.first().uploadSessionUri)
        assertEquals("https://upload.googleapis.com/resumable/replacement", checkpoints.last().uploadSessionUri)
    }

    @Test
    fun uploadFile_invalid308AcknowledgementFailsClosedWithPersistedSession() {
        val source = File.createTempFile("invalid_308", ".txt").apply {
            writeText("content")
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        var requestCount = 0
        fakeInterceptor.responseProvider = { request ->
            requestCount += 1
            when (requestCount) {
                1 -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"files\":[]}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
                2 -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Location", "https://upload.googleapis.com/resumable/invalid-range")
                    .body("".toResponseBody())
                    .build()
                3 -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(308)
                    .message("Resume Incomplete")
                    .header("Range", "bytes=0-0")
                    .body("".toResponseBody())
                    .build()
                else -> error("Unexpected request #$requestCount")
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            adapter.uploadFile(
                source = CloudUploadSource.LocalFile(source),
                remotePath = "invalid-range.txt",
                idempotencyKey = "invalid-range"
            )
        }

        assertTrue(result is CloudSyncResult.Error)
        result as CloudSyncResult.Error
        assertTrue(result.message.contains("Invalid resumable upload range acknowledgement"))
        assertTrue(result.isRetryable)
        assertEquals("https://upload.googleapis.com/resumable/invalid-range", result.uploadSessionUri)
    }

    @Test
    fun downloadFile_emptyBodyFailsClosedWithoutCreatingDestination() {
        val destination = File.createTempFile("download_empty", ".txt").apply {
            delete()
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        fakeInterceptor.responseProvider = { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody("text/plain".toMediaTypeOrNull()))
                .build()
        }

        val result = kotlinx.coroutines.runBlocking { adapter.downloadFile("empty-file", destination) }

        assertTrue(result is CloudSyncResult.Error)
        assertTrue((result as CloudSyncResult.Error).message.contains("response body was empty"))
        assertFalse(destination.exists())
    }

    private class FakeInterceptor : Interceptor {
        lateinit var responseProvider: (Request) -> Response

        override fun intercept(chain: Interceptor.Chain): Response {
            return responseProvider(chain.request())
        }
    }

    private class FakeSharedPreferences : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}

        inner class FakeEditor : android.content.SharedPreferences.Editor {
            private val tempMap = mutableMapOf<String, Any?>()
            private val removeKeys = mutableSetOf<String>()
            override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor { tempMap[key] = value; removeKeys.remove(key); return this }
            override fun putStringSet(key: String, values: Set<String>?): android.content.SharedPreferences.Editor { tempMap[key] = values; removeKeys.remove(key); return this }
            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor { tempMap[key] = value; removeKeys.remove(key); return this }
            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor { tempMap[key] = value; removeKeys.remove(key); return this }
            override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor { tempMap[key] = value; removeKeys.remove(key); return this }
            override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor { tempMap[key] = value; removeKeys.remove(key); return this }
            override fun remove(key: String): android.content.SharedPreferences.Editor { removeKeys.add(key); tempMap.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { tempMap.clear(); removeKeys.addAll(map.keys); return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { removeKeys.forEach { map.remove(it) }; map.putAll(tempMap) }
        }
    }
}
