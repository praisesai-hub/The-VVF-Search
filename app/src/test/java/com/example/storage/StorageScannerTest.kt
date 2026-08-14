package com.example.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.FileCategory
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageScannerTest {

    private val scanner = StorageScanner(mockk<Context>(relaxed = true))

    @Test
    fun determineCategory_isCaseInsensitiveAndRejectsUnknownExtensions() {
        assertEquals(FileCategory.IMAGES, scanner.determineCategory("PHOTO.JpG"))
        assertEquals(FileCategory.DOCUMENTS, scanner.determineCategory("report.PDF"))
        assertEquals(FileCategory.VIDEO, scanner.determineCategory("clip.Mp4"))
        assertEquals(FileCategory.ARCHIVES, scanner.determineCategory("backup.ZIP"))
        assertEquals(FileCategory.OTHER, scanner.determineCategory("README"))
    }

    @Test
    fun fileTypePredicates_coverSupportedAndUnsupportedExtensions() {
        assertTrue(scanner.isImageFile("photo.webp"))
        assertTrue(scanner.isVideoFile("clip.webm"))
        assertTrue(scanner.isPdfFile("statement.PDF"))
        assertTrue(scanner.isDocumentFile("sheet.xlsx"))
        assertFalse(scanner.isImageFile("photo.txt"))
        assertFalse(scanner.isVideoFile("clip.mp3"))
        assertFalse(scanner.isPdfFile("statement.docx"))
    }

    @Test
    fun computeFileHash_returnsSha256AndEmptyForMissingFile() = runBlocking {
        val file = File.createTempFile("storage-scanner-hash", ".txt")
        try {
            file.writeText("hello")
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                scanner.computeFileHash(file)
            )
            assertEquals("", scanner.computeFileHash(File(file.parentFile, "missing.txt")))
        } finally {
            file.delete()
        }
    }

    @Test
    fun computeDocumentFingerprint_isStableForSameContentAndChangesWithContent() = runBlocking {
        val first = File.createTempFile("fingerprint-a", ".pdf")
        val second = File.createTempFile("fingerprint-b", ".pdf")
        val unsupported = File.createTempFile("fingerprint", ".jpg")
        try {
            first.writeText("document payload")
            second.writeText("document payload changed")
            unsupported.writeText("image payload")

            val firstFingerprint = scanner.computeDocumentFingerprint(first)
            assertEquals(firstFingerprint, scanner.computeDocumentFingerprint(first))
            assertEquals(16, firstFingerprint.length)
            assertTrue(firstFingerprint.matches(Regex("[0-9a-f]{16}")))
            assertNotEquals(firstFingerprint, scanner.computeDocumentFingerprint(second))
            assertEquals("", scanner.computeDocumentFingerprint(unsupported))
        } finally {
            first.delete()
            second.delete()
            unsupported.delete()
        }
    }

    @Test
    fun computeDocumentFingerprint_usesHeaderAndTailForLargeDocuments() = runBlocking {
        val largeDocument = File.createTempFile("fingerprint-large", ".pdf")
        try {
            largeDocument.writeBytes(ByteArray(10_000) { index -> (index % 251).toByte() })

            val fingerprint = scanner.computeDocumentFingerprint(largeDocument)

            assertEquals(16, fingerprint.length)
            assertTrue(fingerprint.matches(Regex("[0-9a-f]{16}")))
            assertNotEquals("", fingerprint)
        } finally {
            largeDocument.delete()
        }
    }

    @Test
    fun computeDocumentFingerprint_returnsEmptyForEmptyDocument() = runBlocking {
        val emptyDocument = File.createTempFile("fingerprint-empty", ".pdf")
        try {
            assertEquals("", scanner.computeDocumentFingerprint(emptyDocument))
        } finally {
            emptyDocument.delete()
        }
    }

    @Test
    fun computeDHashFromBitmap_returnsNonZeroHashForDescendingBrightness() {
        val bitmap = Bitmap.createBitmap(9, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) {
            for (x in 0 until 9) {
                bitmap.setPixel(x, y, if (x < 4) Color.WHITE else Color.BLACK)
            }
        }
        try {
            val hash = scanner.computeDHashFromBitmap(bitmap)

            assertEquals(16, hash.length)
            assertTrue(hash.matches(Regex("[0-9a-f]{16}")))
            assertNotEquals("0000000000000000", hash)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun computeDHashFromBitmap_returnsZeroHashForUniformImage() {
        val bitmap = Bitmap.createBitmap(9, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try {
            assertEquals("0000000000000000", scanner.computeDHashFromBitmap(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun computeDHash_returnsEmptyForUnsupportedOrMissingFiles() = runBlocking {
        val unsupported = File.createTempFile("not-an-image", ".txt")
        try {
            assertEquals("", scanner.computeDHash(unsupported))
            assertEquals("", scanner.computeDHash(File(unsupported.parentFile, "missing.jpg")))
        } finally {
            unsupported.delete()
        }
    }

    @Test
    fun calculateHammingDistance_rejectsMalformedHashesAndCountsDifferentBits() {
        assertEquals(0, scanner.calculateHammingDistance("0000000000000000", "0000000000000000"))
        assertEquals(64, scanner.calculateHammingDistance("0000000000000000", "ffffffffffffffff"))
        assertEquals(-1, scanner.calculateHammingDistance("invalid", "0000000000000000"))
        assertEquals(-1, scanner.calculateHammingDistance("000000000000000", "0000000000000000"))
    }
}
