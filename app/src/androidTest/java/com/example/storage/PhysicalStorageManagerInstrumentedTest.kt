package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhysicalStorageManagerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testRoot = File(context.cacheDir, "physical-storage-instrumented-${System.nanoTime()}")
        assertTrue(testRoot.mkdirs())
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun renameMoveRestoreAndDelete_preserveDataIntegrity() {
        val source = File(testRoot, "original.txt").apply { writeText("VVF runtime data") }

        val renamed = PhysicalStorageManager.renameFile(context, source.absolutePath, "renamed.txt")
        assertTrue(renamed.isSuccess)
        val renamedFile = File(testRoot, "renamed.txt")
        assertTrue(renamedFile.exists())
        assertEquals("VVF runtime data", renamedFile.readText())

        val moved = PhysicalStorageManager.moveToTrash(context, renamedFile.absolutePath)
        assertTrue(moved.isSuccess)
        assertFalse(renamedFile.exists())
        val trashPath = moved.getOrThrow()
        assertEquals("VVF runtime data", File(trashPath).readText())

        val restoredPath = File(testRoot, "restored.txt").absolutePath
        val restored = PhysicalStorageManager.restoreFromTrash(context, trashPath, restoredPath)
        assertTrue(restored.isSuccess)
        assertEquals(restoredPath, restored.getOrThrow())
        assertEquals("VVF runtime data", File(restoredPath).readText())

        assertTrue(PhysicalStorageManager.deleteFile(context, restoredPath))
        assertFalse(File(restoredPath).exists())
    }

    @Test
    fun encryptAndDecrypt_roundTripRemovesSourceFiles() {
        val source = File(testRoot, "secret.txt").apply { writeText("device-only secret") }
        val encryptedBytes = byteArrayOf(9, 8, 7, 6)
        val iv = byteArrayOf(1, 2, 3, 4)

        val encrypted = PhysicalStorageManager.encryptAndWipeSource(context, source.absolutePath) { bytes ->
            assertEquals("device-only secret", bytes.toString(Charsets.UTF_8))
            encryptedBytes to iv
        }
        assertTrue(encrypted.isSuccess)
        assertFalse(source.exists())
        val vaultPath = encrypted.getOrThrow().vaultFilePath
        assertEquals(encryptedBytes.toList(), File(vaultPath).readBytes().toList())

        val restoredPath = File(testRoot, "restored-secret.txt").absolutePath
        val restored = PhysicalStorageManager.decryptAndRestore(context, vaultPath, restoredPath) { bytes ->
            assertEquals(encryptedBytes.toList(), bytes.toList())
            "device-only secret".toByteArray()
        }
        assertTrue(restored.isSuccess)
        assertEquals("device-only secret", File(restoredPath).readText())
        assertFalse(File(vaultPath).exists())
    }

    @Test
    fun unsafeNamesAndMissingOperations_failClosed() {
        assertEquals("secret_name.txt", PhysicalStorageManager.safeTrashFileName("/tmp/secret name.txt"))
        assertEquals("content.bin", PhysicalStorageManager.safeTrashFileName("///"))
        assertEquals(128, PhysicalStorageManager.safeTrashFileName("x".repeat(140)).length)

        val missing = File(testRoot, "missing.txt").absolutePath
        assertTrue(PhysicalStorageManager.moveToTrash(context, missing).isFailure)
        assertTrue(PhysicalStorageManager.deleteFile(context, missing))
        assertTrue(
            PhysicalStorageManager.restoreFromTrash(
                context,
                File(testRoot, "missing.vvf").absolutePath,
                File(testRoot, "restored.txt").absolutePath,
            ).isFailure,
        )
    }

    @Test
    fun directoriesAndUriFallbacks_areDeterministic(): Unit {
        assertTrue(PhysicalStorageManager.getRecycleBinDir(context).isDirectory)
        assertTrue(PhysicalStorageManager.getVaultDir(context).isDirectory)
        assertTrue(PhysicalStorageManager.getRestoredDir(context).isDirectory)

        val fallbackUri = android.net.Uri.parse("content://vvf.test.provider/fallback name.txt")
        assertEquals(
            "source_file.txt",
            PhysicalStorageManager.getFileNameFromVaultPathOrUri(
                context,
                "/tmp/ENC_123_source file.txt.vvf",
                fallbackUri,
            ),
        )
        assertEquals(
            "original_name.pdf",
            PhysicalStorageManager.getFileNameFromTrashPathOrUri(context, "456_original name.pdf", fallbackUri),
        )
        assertEquals("fallback_name.txt", PhysicalStorageManager.getFileNameFromContentUri(context, fallbackUri))
        assertEquals(-1L, PhysicalStorageManager.getFileSizeFromContentUri(context, fallbackUri))
    }

    @Test
    fun pathGuards_acceptApprovedRootsAndRejectTraversalOrContentUris() {
        assertTrue(PhysicalStorageManager.validateSafeFileName("report.txt").isSuccess)
        assertTrue(PhysicalStorageManager.validateSafeFileName(" report.txt").isFailure)
        assertTrue(PhysicalStorageManager.validateSafeFileName("../escape.txt").isFailure)
        assertTrue(PhysicalStorageManager.validateSafeFileName("report/escape.txt").isFailure)
        assertTrue(
            PhysicalStorageManager.resolveAllowedPhysicalPath(context, File(testRoot, "safe.txt").path)
                .isSuccess
        )
        assertTrue(
            PhysicalStorageManager.resolveAllowedPhysicalPath(context, "/tmp/vvf-outside.txt")
                .isFailure
        )
        assertTrue(
            PhysicalStorageManager.resolveAllowedPhysicalPath(
                context,
                "content://com.example.provider/document/1",
            ).isFailure
        )
    }

    @Test
    fun invalidNamesAndMissingEncryptionOperations_failClosed(): Unit {
        val missing = File(testRoot, "missing-encryption.txt").absolutePath
        assertTrue(PhysicalStorageManager.renameFile(context, missing, "../escape.txt").isFailure)
        assertTrue(PhysicalStorageManager.renameFile(context, missing, "renamed.txt").isFailure)
        assertTrue(
            PhysicalStorageManager.encryptAndWipeSource(context, missing) { error("encrypt action must not run") }.isFailure,
        )
        assertTrue(
            PhysicalStorageManager.decryptAndRestore(context, missing, File(testRoot, "restored.txt").absolutePath) {
                error("decrypt action must not run")
            }.isFailure,
        )
    }

    @Test
    fun keystoreStreamRoundTrip_removesEncryptedSource(): Unit {
        val source = File(testRoot, "keystore-secret.txt").apply { writeText("keystore-backed device data") }
        val manager = com.example.security.KeystoreVaultManager()

        val encrypted = PhysicalStorageManager.encryptAndWipeSource(context, source.absolutePath, manager)
        assertTrue(encrypted.isSuccess)
        assertFalse(source.exists())
        val vaultPath = encrypted.getOrThrow().vaultFilePath
        assertTrue(File(vaultPath).exists())

        val restoredPath = File(testRoot, "keystore-restored.txt").absolutePath
        val restored = PhysicalStorageManager.decryptAndRestore(
            context,
            vaultPath,
            restoredPath,
            encrypted.getOrThrow().iv,
            manager,
        )
        assertTrue(restored.isSuccess)
        assertEquals("keystore-backed device data", File(restoredPath).readText())
        assertFalse(File(vaultPath).exists())
    }

    @Test
    fun emptySourceCanBeEncryptedAndRestored(): Unit {
        val source = File(testRoot, "empty.txt").apply { writeBytes(byteArrayOf()) }
        val encrypted = PhysicalStorageManager.encryptAndWipeSource(context, source.absolutePath) { _ ->
            byteArrayOf(1, 2, 3) to byteArrayOf(4, 5, 6)
        }
        assertTrue(encrypted.isSuccess)
        assertFalse(source.exists())

        val restoredPath = File(testRoot, "empty-restored.txt").absolutePath
        val restored = PhysicalStorageManager.decryptAndRestore(context, encrypted.getOrThrow().vaultFilePath, restoredPath) {
            byteArrayOf()
        }
        assertTrue(restored.isSuccess)
        assertTrue(File(restoredPath).exists())
        assertEquals(0L, File(restoredPath).length())
    }

    @Test
    fun contentUriRenameAndDelete_useSafDocumentOperations() {
        val sourceUri = insertMediaFile("vvf-rename-${System.nanoTime()}.txt")
        try {
            context.contentResolver.openOutputStream(sourceUri)!!.use { it.write("rename me".toByteArray()) }
            publishMediaFile(sourceUri)

            val renamed = PhysicalStorageManager.renameFile(context, sourceUri.toString(), "vvf-renamed-${System.nanoTime()}.txt")

            assertTrue(renamed.isSuccess)
            assertTrue(PhysicalStorageManager.deleteFile(context, renamed.getOrThrow()))
        } finally {
            context.contentResolver.delete(sourceUri, null, null)
        }
    }

    @Test
    fun unavailableContentUriDeletion_failsClosedWithoutVerification() {
        val unavailableUri = Uri.parse("content://vvf.test.provider/unavailable-delete-${System.nanoTime()}.txt")

        assertFalse(PhysicalStorageManager.deleteFile(context, unavailableUri.toString()))
    }

    @Test
    fun contentUriTrash_copiesDataAndDeletesOriginal() {
        val sourceBytes = "content provider trash".toByteArray()
        val sourceUri = insertMediaFile("vvf-trash-${System.nanoTime()}.txt")
        var trashPath: String? = null
        try {
            context.contentResolver.openOutputStream(sourceUri)!!.use { it.write(sourceBytes) }
            publishMediaFile(sourceUri)

            val moved = PhysicalStorageManager.moveToTrash(context, sourceUri.toString())

            assertTrue(moved.isSuccess)
            trashPath = moved.getOrThrow()
            assertArrayEquals(sourceBytes, File(trashPath).readBytes())
            assertTrue(PhysicalStorageManager.deleteFile(context, trashPath))
        } finally {
            trashPath?.let { File(it).delete() }
            context.contentResolver.delete(sourceUri, null, null)
        }
    }

    @Test
    fun contentUriEncryptionAndDecryption_preserveDataAndDeleteVaultSource() {
        val sourceBytes = "content-provider secret".toByteArray()
        val sourceUri = insertMediaFile("vvf-source-${System.nanoTime()}.txt")
        val destinationUri = insertMediaFile("vvf-destination-${System.nanoTime()}.txt")
        var vaultPath: String? = null
        try {
            context.contentResolver.openOutputStream(sourceUri)!!.use { it.write(sourceBytes) }
            publishMediaFile(sourceUri)
            publishMediaFile(destinationUri)
            assertEquals(sourceBytes.size.toLong(), PhysicalStorageManager.getFileSizeFromContentUri(context, sourceUri))
            assertNotNull(PhysicalStorageManager.getFileNameFromContentUri(context, sourceUri))

            val encrypted = PhysicalStorageManager.encryptAndWipeSource(context, sourceUri.toString()) { bytes ->
                assertArrayEquals(sourceBytes, bytes)
                byteArrayOf(8, 7, 6, 5) to byteArrayOf(1, 2, 3, 4)
            }
            assertTrue(encrypted.isSuccess)
            vaultPath = encrypted.getOrThrow().vaultFilePath
            assertTrue(File(vaultPath).exists())

            val restored = PhysicalStorageManager.decryptAndRestore(
                context,
                vaultPath,
                destinationUri.toString(),
            ) { encryptedBytes ->
                assertArrayEquals(byteArrayOf(8, 7, 6, 5), encryptedBytes)
                sourceBytes
            }
            assertTrue(restored.isSuccess)
            val restoredPath = restored.getOrThrow()
            if (restoredPath.startsWith("content://")) {
                assertEquals(destinationUri.toString(), restoredPath)
                context.contentResolver.openInputStream(destinationUri)!!.use { input ->
                    assertArrayEquals(sourceBytes, input.readBytes())
                }
            } else {
                val restoredFile = File(restoredPath)
                assertTrue(restoredFile.exists())
                assertArrayEquals(sourceBytes, restoredFile.readBytes())
                assertTrue(restoredFile.delete())
            }
            assertFalse(File(vaultPath).exists())
            assertTrue(PhysicalStorageManager.deleteFile(context, destinationUri.toString()))
        } finally {
            vaultPath?.let { File(it).delete() }
            context.contentResolver.delete(sourceUri, null, null)
            context.contentResolver.delete(destinationUri, null, null)
        }
    }

    @Test
    fun contentUriKeystoreStreamRoundTrip_preservesDataAndRemovesVaultSource() {
        val sourceBytes = "content-provider keystore stream secret".toByteArray()
        val sourceUri = insertMediaFile("vvf-keystore-stream-source-${System.nanoTime()}.txt")
        val destinationUri = insertMediaFile("vvf-keystore-stream-destination-${System.nanoTime()}.txt")
        var vaultPath: String? = null
        try {
            context.contentResolver.openOutputStream(sourceUri)!!.use { it.write(sourceBytes) }
            publishMediaFile(sourceUri)
            publishMediaFile(destinationUri)
            val manager = com.example.security.KeystoreVaultManager()

            val encrypted = PhysicalStorageManager.encryptAndWipeSource(context, sourceUri.toString(), manager)

            assertTrue(encrypted.isSuccess)
            vaultPath = encrypted.getOrThrow().vaultFilePath
            assertTrue(File(vaultPath).exists())

            val restored = PhysicalStorageManager.decryptAndRestore(
                context,
                vaultPath,
                destinationUri.toString(),
                encrypted.getOrThrow().iv,
                manager,
            )

            assertTrue(restored.isSuccess)
            val restoredPath = restored.getOrThrow()
            if (restoredPath.startsWith("content://")) {
                assertEquals(destinationUri.toString(), restoredPath)
                context.contentResolver.openInputStream(destinationUri)!!.use { input ->
                    assertArrayEquals(sourceBytes, input.readBytes())
                }
            } else {
                val restoredFile = File(restoredPath)
                assertTrue(restoredFile.exists())
                assertArrayEquals(sourceBytes, restoredFile.readBytes())
                assertTrue(restoredFile.delete())
            }
            assertFalse(File(vaultPath).exists())
        } finally {
            vaultPath?.let { File(it).delete() }
            context.contentResolver.delete(sourceUri, null, null)
            context.contentResolver.delete(destinationUri, null, null)
        }
    }

    @Test
    fun contentUriRestore_fallsBackToRestoredDirectoryWhenWriteIsUnavailable() {
        val trashFile = File(testRoot, "456_original-content.txt").apply { writeText("restored content") }
        val unavailableUri = Uri.parse("content://vvf.test.provider/unavailable-content.txt")

        val restored = PhysicalStorageManager.restoreFromTrash(context, trashFile.absolutePath, unavailableUri.toString())

        assertTrue(restored.isSuccess)
        val restoredFile = File(restored.getOrThrow())
        assertTrue(restoredFile.exists())
        assertEquals("restored content", restoredFile.readText())
        assertFalse(trashFile.exists())
        restoredFile.delete()
    }

    @Test
    fun sizeGuards_failClosedWithoutDeletingSourceOrVault() {
        val oversizedSource = File(testRoot, "oversized-source.bin").apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(50 * 1024 * 1024L + 1L) }
        }
        val encryption = PhysicalStorageManager.encryptAndWipeSource(context, oversizedSource.absolutePath) {
            error("oversized source must be rejected before encryption")
        }
        assertTrue(encryption.isFailure)
        assertTrue(oversizedSource.exists())

        val oversizedVault = File(testRoot, "oversized-vault.vvf").apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(50 * 1024 * 1024L + 1L) }
        }
        val restoration = PhysicalStorageManager.decryptAndRestore(
            context,
            oversizedVault.absolutePath,
            File(testRoot, "guarded-restore.txt").absolutePath,
        ) {
            error("oversized vault must be rejected before decryption")
        }
        assertTrue(restoration.isFailure)
        assertTrue(oversizedVault.exists())
    }

    private fun insertMediaFile(displayName: String): Uri {
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VVF-Test")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values)
            ?: throw AssertionError("MediaStore test row could not be created")
    }

    private fun publishMediaFile(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        assertEquals(1, context.contentResolver.update(uri, values, null, null))
    }
}
