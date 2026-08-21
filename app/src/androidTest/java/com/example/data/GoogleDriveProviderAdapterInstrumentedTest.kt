package com.example.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

@RunWith(AndroidJUnit4::class)
class GoogleDriveProviderAdapterInstrumentedTest {

    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var authManager: GoogleAuthManager
    private lateinit var fakeInterceptor: RecordingInterceptor
    private lateinit var adapter: GoogleDriveProviderAdapter

    @Before
    fun setUp(): Unit {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        authManager = GoogleAuthManager(preferences)
        fakeInterceptor = RecordingInterceptor()
        adapter = GoogleDriveProviderAdapter(
            driveAuthorization = authManager,
            httpClient = OkHttpClient.Builder().addInterceptor(fakeInterceptor).build(),
        )
    }

    @After
    fun tearDown(): Unit {
        authManager.clearSession()
        preferences.edit().clear().commit()
    }

    @Test
    fun providerIdAndAuthenticationGuardsAreStable(): Unit {
        assertEquals("GOOGLE_DRIVE", adapter.providerId)

        val missing = runBlocking {
            adapter.uploadFile(File("/does/not/exist.txt"), "remote.txt")
        }
        assertTrue(missing is CloudSyncResult.Error)
        assertFalse((missing as CloudSyncResult.Error).isRetryable)

        val destination = temporaryFile("guard")
        val unauthenticated = runBlocking {
            adapter.downloadFile("remote.txt", destination)
        }
        assertTrue(unauthenticated is CloudSyncResult.Error)
        assertTrue((unauthenticated as CloudSyncResult.Error).message.contains("not authenticated"))
    }

    @Test
    fun uploadSuccessCoversMimeTypesAndRemoteIdParsing(): Unit {
        authenticate()
        val mimeTypes = mapOf(
            ".pdf" to "application/pdf",
            ".png" to "image/png",
            ".jpg" to "image/jpeg",
            ".jpeg" to "image/jpeg",
            ".txt" to "text/plain",
            ".json" to "application/json",
            ".bin" to "application/octet-stream",
        )

        mimeTypes.forEach { (extension, expectedMimeType) ->
            val file = temporaryFile("upload", extension).apply { writeText("payload") }
            var requestCount = 0
            fakeInterceptor.responseProvider = { request ->
                requestCount += 1
                if (requestCount == 1) {
                    assertEquals(expectedMimeType, request.header("X-Upload-Content-Type"))
                    response(request, 200, "{}", location = "https://upload.test/session/$extension")
                } else {
                    response(request, 200, "{\"id\":\"remote-$extension\"}")
                }
            }

            val result = runBlocking { adapter.uploadFile(file, "remote$extension") }
            assertTrue("Upload failed for $extension: $result", result is CloudSyncResult.Success)
            assertEquals(file.length(), (result as CloudSyncResult.Success).bytesTransferred)
            file.delete()
        }
    }

    @Test
    fun uploadFailureContractsAreClassified(): Unit {
        authenticate()
        val file = temporaryFile("failure", ".txt").apply { writeText("payload") }

        fakeInterceptor.responseProvider = { request ->
            response(request, 500, "server unavailable")
        }
        val serverError = runBlocking { adapter.uploadFile(file, "remote.txt") }
        assertTrue(serverError is CloudSyncResult.Error)
        assertTrue((serverError as CloudSyncResult.Error).isRetryable)
        assertTrue(serverError.message.contains("HTTP 500"))

        fakeInterceptor.responseProvider = { request ->
            response(request, 200, "{}")
        }
        val missingLocation = runBlocking { adapter.uploadFile(file, "remote.txt") }
        assertTrue(missingLocation is CloudSyncResult.Error)
        val missingLocationError = missingLocation as CloudSyncResult.Error
        assertFalse(missingLocationError.isRetryable)
        assertTrue(missingLocationError.message.contains("Missing 'Location'"))

        var requestCount = 0
        fakeInterceptor.responseProvider = { request ->
            requestCount += 1
            if (requestCount == 1) {
                response(request, 200, "{}", location = "https://upload.test/session/malformed")
            } else {
                response(request, 200, "{\"name\":\"without-id\"}")
            }
        }
        val malformedResponse = runBlocking { adapter.uploadFile(file, "remote.txt") }
        assertTrue(malformedResponse is CloudSyncResult.Error)
        val malformedResponseError = malformedResponse as CloudSyncResult.Error
        assertFalse(malformedResponseError.isRetryable)
        assertTrue(malformedResponseError.message.contains("Failed to parse remote file ID"))

        fakeInterceptor.responseProvider = { throw ConnectException("offline") }
        val networkFailure = runBlocking { adapter.uploadFile(file, "remote.txt") }
        assertTrue(networkFailure is CloudSyncResult.Error)
        val networkFailureError = networkFailure as CloudSyncResult.Error
        assertTrue(networkFailureError.isRetryable)
        assertEquals("Network connection is unavailable.", networkFailureError.message)
        file.delete()
    }

    @Test
    fun downloadSuccessWritesContentAndUsesExtractedFileId(): Unit {
        authenticate()
        val destination = temporaryFile("download", ".txt")
        var requestCount = 0
        fakeInterceptor.responseProvider = { request ->
            requestCount += 1
            if (requestCount == 1) {
                assertTrue(request.url.queryParameter("q")?.contains("name = 'remote.txt'") == true)
                response(request, 200, "{\"files\":[{\"id\":\"remote-id\"}]}")
            } else {
                assertTrue(request.url.toString().contains("/remote-id?alt=media"))
                response(request, 200, "downloaded content")
            }
        }

        val result = runBlocking { adapter.downloadFile("remote.txt", destination) }
        assertTrue(result is CloudSyncResult.Success)
        assertEquals("downloaded content", destination.readText())
        assertEquals(destination.length(), (result as CloudSyncResult.Success).bytesTransferred)
        destination.delete()
    }

    @Test
    fun downloadFailureContractsAreClassified(): Unit {
        authenticate()
        val destination = temporaryFile("download-failure", ".txt")

        fakeInterceptor.responseProvider = { request ->
            response(request, 404, "missing")
        }
        val searchError = runBlocking { adapter.downloadFile("remote.txt", destination) }
        assertTrue(searchError is CloudSyncResult.Error)
        assertFalse((searchError as CloudSyncResult.Error).isRetryable)
        assertTrue(searchError.message.contains("HTTP 404"))

        fakeInterceptor.responseProvider = { request ->
            response(request, 200, "{\"files\":[]}")
        }
        val notFound = runBlocking { adapter.downloadFile("remote.txt", destination) }
        assertTrue(notFound is CloudSyncResult.Error)
        assertFalse((notFound as CloudSyncResult.Error).isRetryable)
        assertEquals("The requested cloud file was not found.", notFound.message)

        var requestCount = 0
        fakeInterceptor.responseProvider = { request ->
            requestCount += 1
            if (requestCount == 1) {
                response(request, 200, "{\"files\":[{\"fileId\":\"remote-id\"}]}")
            } else {
                response(request, 503, "try later")
            }
        }
        val mediaError = runBlocking { adapter.downloadFile("remote.txt", destination) }
        assertTrue(mediaError is CloudSyncResult.Error)
        assertTrue((mediaError as CloudSyncResult.Error).isRetryable)
        assertTrue(mediaError.message.contains("HTTP 503"))

        fakeInterceptor.responseProvider = { throw UnknownHostException("no network") }
        val networkFailure = runBlocking { adapter.downloadFile("remote.txt", destination) }
        assertTrue(networkFailure is CloudSyncResult.Error)
        val networkFailureError = networkFailure as CloudSyncResult.Error
        assertTrue(networkFailureError.isRetryable)
        assertEquals("Network connection is unavailable.", networkFailureError.message)
        destination.delete()
    }

    @Test
    fun emptyMediaBodyFailsClosed(): Unit {
        authenticate()
        val destination = temporaryFile("download-empty", ".txt")
        val mockedHttpClient = mockk<okhttp3.OkHttpClient>()
        val mockedCall = mockk<Call>()
        val searchRequest = Request.Builder().url("https://search.test").build()
        val mediaRequest = Request.Builder().url("https://media.test").build()
        val searchResponse = response(
            searchRequest,
            200,
            "{\"files\":[{\"id\":\"remote-id\"}]}",
        )
        val emptyMediaResponse = response(mediaRequest, 200, "")
        every { mockedHttpClient.newCall(any()) } returns mockedCall
        every { mockedCall.execute() } returns searchResponse andThen emptyMediaResponse
        val isolatedAdapter = GoogleDriveProviderAdapter(authManager, mockedHttpClient)

        val result = runBlocking { isolatedAdapter.downloadFile("remote.txt", destination) }
        assertTrue(result is CloudSyncResult.Error)
        val error = result as CloudSyncResult.Error
        assertFalse(error.isRetryable)
        assertEquals("Media download response body was empty.", error.message)
        destination.delete()
    }

    private fun authenticate(): Unit {
        authManager.saveSession("access_123", "refresh_123", "user@test.com", "Test")
        assertTrue(authManager.isAuthorized())
    }

    private fun temporaryFile(prefix: String, suffix: String = ".tmp"): File =
        File.createTempFile(prefix, suffix).apply { deleteOnExit() }

    private fun response(
        request: Request,
        code: Int,
        body: String?,
        location: String? = null,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
        if (location != null) builder.header("Location", location)
        if (body != null) {
            builder.body(body.toResponseBody("application/json".toMediaTypeOrNull()))
        }
        return builder.build()
    }

    private class RecordingInterceptor : Interceptor {
        var responseProvider: (Request) -> Response = { request ->
            responseForDefault(request)
        }

        override fun intercept(chain: Interceptor.Chain): Response = responseProvider(chain.request())

        private fun responseForDefault(request: Request): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(500)
            .message("Not configured")
            .body("not configured".toResponseBody("text/plain".toMediaTypeOrNull()))
            .build()
    }

    companion object {
        private const val PREFERENCES_NAME = "google_drive_adapter_instrumented_test"
    }
}
