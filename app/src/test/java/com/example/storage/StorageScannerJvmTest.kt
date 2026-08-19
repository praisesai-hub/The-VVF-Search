package com.example.storage

import android.content.Context
import com.example.data.FileCategory
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class StorageScannerJvmTest {

    private lateinit var scanner: StorageScanner
    private lateinit var tempDirectory: java.nio.file.Path

    @Before
    fun setUp() {
        scanner = StorageScanner(mockk<Context>(relaxed = true))
        tempDirectory = Files.createTempDirectory("storage-scanner-jvm-test")
    }

    @After
    fun tearDown() {
        Files.walk(tempDirectory).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun determineCategory_isCaseInsensitiveAndUsesSafeFallbacks() {
        assertEquals(FileCategory.IMAGES, scanner.determineCategory("PHOTO.JPEG"))
        assertEquals(FileCategory.DOCUMENTS, scanner.determineCategory("report.PDF"))
        assertEquals(FileCategory.AUDIO, scanner.determineCategory("recording.FLAC"))
        assertEquals(FileCategory.VIDEO, scanner.determineCategory("clip.MP4"))
        assertEquals(FileCategory.ARCHIVES, scanner.determineCategory("backup.ZIP"))
        assertEquals(FileCategory.APKS, scanner.determineCategory("bundle.APK"))
        assertEquals(FileCategory.OTHER, scanner.determineCategory("README"))
        assertEquals(FileCategory.OTHER, scanner.determineCategory(".hidden"))
    }

    @Test
    fun hammingDistance_countsDifferingBits() {
        assertEquals(0, scanner.calculateHammingDistance("0000000000000000", "0000000000000000"))
        assertEquals(64, scanner.calculateHammingDistance("0000000000000000", "ffffffffffffffff"))
        assertEquals(1, scanner.calculateHammingDistance("a1b2c3d4e5f60718", "a1b2c3d4e5f60719"))
    }

    @Test
    fun hammingDistance_rejectsMalformedHashes() {
        assertEquals(-1, scanner.calculateHammingDistance("", "0000000000000000"))
        assertEquals(-1, scanner.calculateHammingDistance("short", "0000000000000000"))
        assertEquals(-1, scanner.calculateHammingDistance("000000000000000g", "0000000000000000"))
    }

    @Test
    fun filePredicates_acceptSupportedExtensionsWithoutCaseSensitivity() {
        assertTrue(scanner.isImageFile("photo.HEIC"))
        assertTrue(scanner.isVideoFile("video.WEBM"))
        assertTrue(scanner.isPdfFile("document.PDF"))
        assertTrue(scanner.isDocumentFile("spreadsheet.XLSX"))
        assertTrue(!scanner.isImageFile("document.pdf"))
        assertTrue(!scanner.isVideoFile("photo.png"))
        assertTrue(!scanner.isPdfFile("document.docx"))
        assertTrue(!scanner.isDocumentFile("song.mp3"))
    }

    @Test
    fun computeFileHash_returnsStableSha256AndFailsClosedForUnreadablePath() = runBlocking {
        val file = tempDirectory.resolve("payload.bin").toFile().apply {
            writeText("VVF test payload")
        }

        val first = scanner.computeFileHash(file)
        val second = scanner.computeFileHash(file)

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertNotEquals("", first)
        assertEquals("", scanner.computeFileHash(tempDirectory.resolve("missing.bin").toFile()))
    }

    @Test
    fun computeDocumentCandidateFingerprint_isStableAndChangesWhenContentChanges() = runBlocking {
        val file = tempDirectory.resolve("report.pdf").toFile().apply {
            writeText("first document body")
        }

        val first = scanner.computeDocumentCandidateFingerprint(file)
        file.writeText("second document body")
        val second = scanner.computeDocumentCandidateFingerprint(file)

        assertEquals(16, first.length)
        assertEquals(16, second.length)
        assertNotEquals(first, second)
        assertEquals("", scanner.computeDocumentCandidateFingerprint(tempDirectory.resolve("missing.pdf").toFile()))
        assertEquals(
            "",
            scanner.computeDocumentCandidateFingerprint(
                tempDirectory.resolve("note.txt").toFile().apply { writeText("") }
            )
        )
    }

    @Test
    fun computeDocumentCandidateFingerprint_usesLargeFileHeaderAndTailWithoutRejectingValidDocument() = runBlocking {
        val file = tempDirectory.resolve("large-report.pdf").toFile().apply {
            writeBytes(ByteArray(12_000) { index -> (index % 251).toByte() })
        }

        val first = scanner.computeDocumentCandidateFingerprint(file)
        java.io.RandomAccessFile(file, "rw").use { it.seek(0); it.write(byteArrayOf(99)) }
        val headerChanged = scanner.computeDocumentCandidateFingerprint(file)
        java.io.RandomAccessFile(file, "rw").use { it.seek(file.length() - 1); it.write(byteArrayOf(77)) }
        val tailChanged = scanner.computeDocumentCandidateFingerprint(file)

        assertEquals(16, first.length)
        assertNotEquals(first, headerChanged)
        assertNotEquals(headerChanged, tailChanged)
        assertEquals(
            "",
            scanner.computeDocumentCandidateFingerprint(
                tempDirectory.resolve("not-a-document.mp3").toFile().apply { writeText("payload") }
            )
        )
    }
}
