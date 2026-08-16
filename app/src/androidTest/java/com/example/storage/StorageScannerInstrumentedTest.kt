package com.example.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.FileCategory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageScannerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var scanner: StorageScanner

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scanner = StorageScanner(context)
        testRoot = File(context.cacheDir, "scanner-instrumented-${System.nanoTime()}")
        assertTrue(testRoot.mkdirs())
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun scanDeviceStorage_discoversAppPrivateFilesAndComputesRealHashes() = runBlocking {
        val document = File(testRoot, "runtime-report.PDF").apply {
            writeText("The VVF Search runtime document")
        }
        val image = File(testRoot, "runtime-image.png")
        val bitmap = createDescendingBitmap()
        try {
            image.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }

        val discovered = scanner.scanDeviceStorage(computeHashes = true)

        val documentItem = discovered.firstOrNull { it.path == document.absolutePath }
        assertNotNull(documentItem)
        assertEquals(FileCategory.DOCUMENTS.name, documentItem?.category)
        assertEquals(document.length(), documentItem?.sizeBytes)
        assertEquals(
            "3a9adfc6318d375bde9d6d375d20833b9b05dd0d8fa8b626fae8e6cb63993073",
            documentItem?.md5Hash,
        )

        val imageItem = discovered.firstOrNull { it.path == image.absolutePath }
        assertNotNull(imageItem)
        assertEquals(FileCategory.IMAGES.name, imageItem?.category)
        assertTrue(imageItem?.md5Hash?.matches(Regex("[0-9a-f]{64}")) == true)
        assertTrue(imageItem?.visualSimilarityHash?.matches(Regex("[0-9a-f]{16}")) == true)
    }

    @Test
    fun contentUriHash_readsRealFileStreamAndMissingUriFailsClosed() = runBlocking {
        val source = File(testRoot, "content-uri.txt").apply { writeText("content uri payload") }

        assertEquals(
            "411e3eda58aec0151e5fe75ee6acd965bd9267404b9deec9a301bfc21a9aa72b",
            scanner.computeContentUriHash(source.toUri()),
        )
        assertEquals("", scanner.computeContentUriHash(File(testRoot, "missing.txt").toUri()))
    }

    @Test
    fun scanDeviceStorageFlow_emitsFullAndFinalBatches(): Unit = runBlocking {
        val expectedPaths = (0 until 105).map { index ->
            File(testRoot, "batch-$index.txt").apply { writeText("batch payload $index") }.absolutePath
        }.toSet()

        val batches = scanner.scanDeviceStorageFlow(computeHashes = false).toList()
        val discoveredPaths = batches.flatten().map { it.path }.toSet()

        assertTrue(batches.any { it.size == 100 })
        assertTrue(expectedPaths.all { it in discoveredPaths })
    }

    @Test
    fun scanDeviceStorage_skipsHiddenAndroidAndEmptyFiles(): Unit = runBlocking {
        File(testRoot, ".hidden.txt").writeText("hidden")
        File(testRoot, "nested").apply {
            mkdirs()
            resolve("private.txt").writeText("private")
        }
        File(testRoot, "empty.txt").createNewFile()
        val visible = File(testRoot, "visible.txt").apply { writeText("visible") }
        val androidRoot = File(context.cacheDir, "Android")
        val createdAndroidRoot = !androidRoot.exists()
        if (createdAndroidRoot) assertTrue(androidRoot.mkdirs())
        assertTrue(androidRoot.isDirectory)
        val excludedAndroidFile = File(androidRoot, "scanner-${System.nanoTime()}.txt").apply {
            writeText("excluded")
        }

        try {
            val discovered = scanner.scanDeviceStorage(computeHashes = false)
            val testRootPaths = discovered
                .filter { it.path.startsWith(testRoot.absolutePath) }
                .map { it.path }

            assertEquals(
                setOf(visible.absolutePath, File(testRoot, "nested/private.txt").absolutePath),
                testRootPaths.toSet(),
            )
            assertFalse(discovered.any { it.path == excludedAndroidFile.absolutePath })
        } finally {
            assertTrue(excludedAndroidFile.delete())
            if (createdAndroidRoot) assertTrue(androidRoot.delete())
        }
    }

    @Test
    fun computeVideoDHash_invalidMediaFailsClosed(): Unit = runBlocking {
        val invalid = File(testRoot, "invalid.mp4").apply { writeText("not a media stream") }

        assertEquals("", scanner.computeVideoDHash(invalid))
        assertEquals("", scanner.computeVideoDHash(File(testRoot, "missing.mp4")))
    }

    @Test
    fun computeDocumentFingerprint_handlesEmptyUnsupportedAndLargeFiles(): Unit = runBlocking {
        val empty = File(testRoot, "empty.txt").apply { createNewFile() }
        val large = File(testRoot, "large.txt").apply { writeText("x".repeat(9_000)) }
        val unsupported = File(testRoot, "archive.zip").apply { writeText("archive") }

        assertEquals("", scanner.computeDocumentFingerprint(empty))
        assertEquals("", scanner.computeDocumentFingerprint(unsupported))
        assertEquals(16, scanner.computeDocumentFingerprint(large).length)
    }

    @Test
    fun sampledBitmapAndUnsupportedImagePaths_failSafely(): Unit {
        runBlocking {
            val invalid = File(testRoot, "invalid.png").apply { writeText("not an image") }

            assertTrue(scanner.computeDHash(invalid).isEmpty())
            assertEquals("", scanner.computeDHash(File(testRoot, "missing.jpg")))
            assertTrue(scanner.decodeSampledBitmapFromFile(invalid, 64, 64) == null)

            val valid = File(testRoot, "valid.png")
            val bitmap = createDescendingBitmap()
            try {
                valid.outputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }
            val sampled = scanner.decodeSampledBitmapFromFile(valid, 64, 64)
            assertNotNull(sampled)
            assertNotEquals("", scanner.computeDHash(valid))
            if (sampled != null) sampled.recycle()
        }
    }

    @Test
    fun categoryAndFileTypePredicates_coverSupportedAndFallbackExtensions() {
        assertEquals(FileCategory.IMAGES, scanner.determineCategory("photo.JPEG"))
        assertEquals(FileCategory.DOCUMENTS, scanner.determineCategory("report.csv"))
        assertEquals(FileCategory.AUDIO, scanner.determineCategory("voice.ogg"))
        assertEquals(FileCategory.VIDEO, scanner.determineCategory("clip.webm"))
        assertEquals(FileCategory.ARCHIVES, scanner.determineCategory("backup.tar"))
        assertEquals(FileCategory.APKS, scanner.determineCategory("bundle.apks"))
        assertEquals(FileCategory.OTHER, scanner.determineCategory("unknown.bin"))
        assertTrue(scanner.isImageFile("photo.webp"))
        assertTrue(scanner.isVideoFile("clip.mp4"))
        assertTrue(scanner.isPdfFile("report.PDF"))
        assertTrue(scanner.isDocumentFile("report.docx"))
        assertFalse(scanner.isImageFile("report.txt"))
        assertFalse(scanner.isVideoFile("photo.jpg"))
    }

    @Test
    fun hammingDistance_validatesLengthAndHexInput() {
        assertEquals(0, scanner.calculateHammingDistance("0000000000000000", "0000000000000000"))
        assertEquals(64, scanner.calculateHammingDistance("0000000000000000", "ffffffffffffffff"))
        assertEquals(-1, scanner.calculateHammingDistance("short", "0000000000000000"))
        assertEquals(-1, scanner.calculateHammingDistance("zzzzzzzzzzzzzzzz", "0000000000000000"))
    }

    @Test
    fun fileHashAndQuietDHash_failClosedForMissingOrUnsupportedFiles() = runBlocking {
        val missing = File(testRoot, "missing.txt")
        assertEquals("", scanner.computeFileHash(missing))
        assertEquals("", scanner.computeDHashQuietly(missing))
        assertEquals("", scanner.computeDHash(File(testRoot, "not-image.txt").apply { writeText("payload") }))
        assertEquals("", scanner.computeDocumentFingerprint(File(testRoot, "not-document.bin").apply { writeText("payload") }))
    }

    private fun createDescendingBitmap(): Bitmap {
        return Bitmap.createBitmap(9, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until 8) {
                for (x in 0 until 9) {
                    val brightness = (255 - x * 24).coerceAtLeast(0)
                    bitmap.setPixel(x, y, Color.rgb(brightness, brightness, brightness))
                }
            }
        }
    }
}
