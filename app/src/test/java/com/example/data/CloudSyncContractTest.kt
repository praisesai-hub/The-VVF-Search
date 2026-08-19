package com.example.data

import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSyncContractTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("vvf-cloud-source-").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun localFileSource_streamsReadableBytesAndFailsForMissingFile(): Unit {
        val sourceFile = File(directory, "invoice.pdf").apply { writeText("pdf-content") }
        val source = CloudUploadSource.LocalFile(sourceFile)

        assertEquals("invoice.pdf", source.displayName)
        assertEquals(sourceFile.length(), source.contentLength)
        assertEquals("application/pdf", source.mimeType)
        assertEquals("pdf-content", source.openStream().bufferedReader().use { it.readText() })
        assertEquals(sourceFile, source.asFile())

        val missing = CloudUploadSource.LocalFile(File(directory, "missing.txt"))
        try {
            missing.openStream()
            throw AssertionError("missing local cloud source must not be opened")
        } catch (_: IOException) {
            assertFalse(File(directory, "missing.txt").exists())
        }
    }

    @Test
    fun contentUriSource_usesDeclaredLengthAndFailsClosedWhenProviderHasNoStream(): Unit {
        val source = CloudUploadSource.ContentUri(
            context = RuntimeEnvironment.getApplication(),
            uri = Uri.parse("content://unavailable.provider/receipt"),
            displayName = "receipt.unknown",
            declaredLength = 73L
        )

        assertEquals(73L, source.contentLength)
        assertEquals("application/octet-stream", source.mimeType)
        try {
            source.openStream()
            throw AssertionError("missing SAF stream must not be treated as an empty upload")
        } catch (_: IOException) {
            assertTrue(source.contentLength > 0L)
        }
    }

    @Test
    fun mimeTypeMappingCoversSupportedAndUnknownFileNames(): Unit {
        assertEquals("image/png", CloudUploadSource.mimeTypeForName("SCREEN.PNG"))
        assertEquals("image/jpeg", CloudUploadSource.mimeTypeForName("camera.jpeg"))
        assertEquals("text/plain", CloudUploadSource.mimeTypeForName("notes.txt"))
        assertEquals("application/json", CloudUploadSource.mimeTypeForName("manifest.json"))
        assertEquals("application/octet-stream", CloudUploadSource.mimeTypeForName("no-extension"))
    }

    @Test
    fun legacyProviderAdapter_delegatesLocalFileAndRefusesSafSource(): Unit = runBlocking {
        val adapter = LocalOnlyAdapter()
        val local = File(directory, "local.txt").apply { writeText("local") }
        val saf = CloudUploadSource.ContentUri(
            context = RuntimeEnvironment.getApplication(),
            uri = Uri.parse("content://unavailable.provider/local"),
            displayName = "local.txt",
            declaredLength = 5L
        )

        val localResult = adapter.uploadFile(CloudUploadSource.LocalFile(local), "remote.txt")
        val safResult = adapter.uploadFile(saf, "remote.txt")

        assertTrue(localResult is CloudSyncResult.Success)
        assertEquals("remote.txt", (localResult as CloudSyncResult.Success).remoteFileId)
        assertTrue(safResult is CloudSyncResult.Error)
        assertFalse((safResult as CloudSyncResult.Error).isRetryable)
        assertTrue(safResult.message.contains("does not support SAF"))
    }

    private class LocalOnlyAdapter : CloudProviderAdapter {
        override val providerId: String = "LOCAL_ONLY"

        override suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult =
            CloudSyncResult.Success(bytesTransferred = file.length(), remoteFileId = remotePath)

        override suspend fun downloadFile(remoteFileId: String, destinationFile: File): CloudSyncResult =
            CloudSyncResult.NotSupported
    }
}
