package com.example.data

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
        val error = result as CloudSyncResult.Error
        assertEquals("The selected file is unavailable.", error.message)
        assertFalse(error.message.contains(nonExistentFile.absolutePath))
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
            if (request.method == "POST") {
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

        var callCount = 0
        fakeInterceptor.responseProvider = { request ->
            callCount++
            if (callCount == 1) {
                val searchResponseJson = """
                    {
                        "files": [
                            {
                                "id": "gdrive_file_id_456",
                                "name": "remote.txt"
                            }
                        ]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(searchResponseJson.toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            } else {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("downloaded content".toResponseBody("text/plain".toMediaTypeOrNull()))
                    .build()
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            adapter.downloadFile("remote.txt", tempFile)
        }

        assertTrue(result is CloudSyncResult.Success)
        assertEquals("downloaded content", tempFile.readText())
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
        assertTrue(error.message.contains("Missing 'Location'"))
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
        assertEquals("Network connection is unavailable.", error.message)
    }

    @Test
    fun testDownloadFile_WhenNotAuthenticated() {
        val destination = File.createTempFile("download_unauthenticated", ".txt")
        destination.deleteOnExit()

        val result = kotlinx.coroutines.runBlocking {
            adapter.downloadFile("remote.txt", destination)
        }

        assertTrue(result is CloudSyncResult.Error)
        assertFalse((result as CloudSyncResult.Error).isRetryable)
        assertTrue(result.message.contains("user is not authenticated"))
    }

    @Test
    fun testDownloadFile_SearchHttpErrorIsNotRetryableForNotFound() {
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

        val result = kotlinx.coroutines.runBlocking { adapter.downloadFile("remote.txt", destination) }

        assertTrue(result is CloudSyncResult.Error)
        val error = result as CloudSyncResult.Error
        assertTrue(error.message.contains("File search failed: HTTP 404"))
        assertFalse(error.isRetryable)
    }

    @Test
    fun testDownloadFile_WhenSearchReturnsNoMatchingFile() {
        val destination = File.createTempFile("download_not_found", ".txt")
        destination.deleteOnExit()
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        fakeInterceptor.responseProvider = { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"files\":[]}".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val result = kotlinx.coroutines.runBlocking { adapter.downloadFile("remote.txt", destination) }

        assertTrue(result is CloudSyncResult.Error)
        assertTrue((result as CloudSyncResult.Error).message.contains("File not found"))
    }

    @Test
    fun testDownloadFile_MediaServerErrorIsRetryable() {
        val destination = File.createTempFile("download_media_error", ".txt")
        destination.deleteOnExit()
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
                    .body(
                        "{\"files\":[{\"fileId\":\"id-789\"}]}"
                            .toResponseBody("application/json".toMediaTypeOrNull()),
                    )
                    .build()
            } else {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Unavailable")
                    .body("try later".toResponseBody("text/plain".toMediaTypeOrNull()))
                    .build()
            }
        }

        val result = kotlinx.coroutines.runBlocking { adapter.downloadFile("remote.txt", destination) }

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

        val result = kotlinx.coroutines.runBlocking { adapter.downloadFile("remote.txt", destination) }

        assertTrue(result is CloudSyncResult.Error)
        val error = result as CloudSyncResult.Error
        assertTrue(error.isRetryable)
        assertEquals("Network connection is unavailable.", error.message)
    }

    @Test
    fun uploadOperationLookup_escapesApostrophesAndBackslashesInQueryValues() {
        val file = File.createTempFile("upload_escape", ".txt").apply {
            writeText("content")
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        var capturedQuery: String? = null
        fakeInterceptor.responseProvider = { request ->
            capturedQuery = request.url.queryParameter("q")
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"files\":[{\"id\":\"existing-id\"}]}".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val result = kotlinx.coroutines.runBlocking {
            adapter.uploadFile(file, "remote.txt", "op'\\test")
        }

        assertEquals("existing-id", (result as CloudSyncResult.Success).remoteFileId)
        assertTrue(capturedQuery.orEmpty().contains("op\\'\\\\test"))
    }

    @Test
    fun uploadFile_reusesPersistedSessionAndResumesFromServerOffset() {
        val file = File.createTempFile("upload_resume", ".txt").apply {
            writeText("0123456789")
            deleteOnExit()
        }
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        val ranges = mutableListOf<String>()
        val progress = mutableListOf<CloudTransferProgress>()
        fakeInterceptor.responseProvider = { request ->
            val range = request.header("Content-Range").orEmpty()
            ranges += range
            if (range.startsWith("bytes */")) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(308)
                    .message("Resume Incomplete")
                    .header("Range", "bytes=0-2")
                    .build()
            } else {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"id\":\"remote-resumed\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            adapter.uploadFile(
                file,
                "remote.txt",
                "",
                CloudTransferState("", "https://upload.googleapis.com/session-1", 0L)
            ) { progress += it }
        }

        assertEquals("remote-resumed", (result as CloudSyncResult.Success).remoteFileId)
        if (ranges != listOf("bytes */10", "bytes 3-9/10")) {
            error("Unexpected Content-Range sequence: $ranges")
        }
        assertEquals(10L, progress.last().bytesCommitted)
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
