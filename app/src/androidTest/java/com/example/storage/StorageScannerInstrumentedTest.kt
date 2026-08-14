package com.example.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.FileCategory
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
    fun sampledBitmapAndUnsupportedImagePaths_failSafely() = runBlocking {
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
        sampled?.recycle()
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
