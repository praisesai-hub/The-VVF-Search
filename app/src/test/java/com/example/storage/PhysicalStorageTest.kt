package com.example.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.VaultCryptoSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhysicalStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var testDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDir = tempFolder.newFolder("test_storage")
    }

    @Test
    fun testPhysicalRename() {
        val srcFile = File(testDir, "original.txt").apply { writeText("Hello World") }
        assertTrue(srcFile.exists())

        val result = PhysicalStorageManager.renameFile(context, srcFile.absolutePath, "renamed.txt")
        assertTrue(result.isSuccess)

        val renamedFile = File(testDir, "renamed.txt")
        assertTrue(renamedFile.exists())
        assertEquals("Hello World", renamedFile.readText())
        assertFalse(srcFile.exists())
    }

    @Test
    fun testPhysicalMoveToTrashAndRestore() {
        val srcFile = File(testDir, "document.pdf").apply { writeText("PDF Content") }
        val originalPath = srcFile.absolutePath

        val trashResult = PhysicalStorageManager.moveToTrash(context, originalPath)
        assertTrue(trashResult.isSuccess)
        val trashPath = trashResult.getOrThrow()

        assertFalse(File(originalPath).exists())
        assertTrue(File(trashPath).exists())

        val restoreResult = PhysicalStorageManager.restoreFromTrash(context, trashPath, originalPath)
        assertTrue(restoreResult.isSuccess)

        assertTrue(File(originalPath).exists())
        assertEquals("PDF Content", File(originalPath).readText())
    }

    @Test
    fun testPhysicalDelete() {
        val fileToDelete = File(testDir, "temp.txt").apply { writeText("Delete Me") }
        assertTrue(fileToDelete.exists())

        val deleted = PhysicalStorageManager.deleteFile(context, fileToDelete.absolutePath)
        assertTrue(deleted)
        assertFalse(fileToDelete.exists())
    }

    @Test
    fun testEncryptAndWipeSourceExceedsSizeLimit() {
        val srcFile = File(testDir, "large_file.bin")
        // Create a large file quickly using RandomAccessFile to set length
        java.io.RandomAccessFile(srcFile, "rw").use { raf ->
            raf.setLength(51 * 1024 * 1024L) // 51MB
        }
        assertTrue(srcFile.exists())
        assertEquals(51 * 1024 * 1024L, srcFile.length())

        val result = PhysicalStorageManager.encryptAndWipeSource(context, srcFile.absolutePath) { bytes ->
            Pair(bytes, byteArrayOf())
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains("exceeds the maximum secure vault limit of 50MB") == true)
    }

    @Test
    fun testDecryptAndRestoreExceedsSizeLimit() {
        val vaultFile = File(testDir, "large_vault_file.vvf")
        java.io.RandomAccessFile(vaultFile, "rw").use { raf ->
            raf.setLength(51 * 1024 * 1024L) // 51MB
        }
        assertTrue(vaultFile.exists())

        val result = PhysicalStorageManager.decryptAndRestore(context, vaultFile.absolutePath, File(testDir, "restored.bin").absolutePath) { bytes ->
            bytes
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains("exceeds the maximum secure vault limit of 50MB") == true)
    }

    @Test
    fun safeTrashFileName_sanitizesPathUnsafeCharactersAndLength() {
        assertEquals("secret_name.txt", PhysicalStorageManager.safeTrashFileName("/tmp/secret name.txt"))
        assertEquals(128, PhysicalStorageManager.safeTrashFileName("a".repeat(140)).length)
        assertEquals("content.bin", PhysicalStorageManager.safeTrashFileName("///"))
    }

    @Test
    fun operationTrashPath_isDeterministicAndSanitized() {
        val source = File(testDir, "report final.pdf")
        val operationId = "file-MOVE_TO_TRASH-42/unsafe"

        val first = PhysicalStorageManager.trashPathForOperation(context, source.absolutePath, operationId)
        val second = PhysicalStorageManager.trashPathForOperation(context, source.absolutePath, operationId)

        assertEquals(first, second)
        assertTrue(File(first).parentFile == PhysicalStorageManager.getRecycleBinDir(context))
        assertTrue(File(first).name.startsWith("op_file-MOVE_TO_TRASH-42_unsafe_"))
    }

    @Test
    fun storageDirectories_areCreatedWithinApplicationStorage() {
        val recycleBin = PhysicalStorageManager.getRecycleBinDir(context)
        val vault = PhysicalStorageManager.getVaultDir(context)
        val restored = PhysicalStorageManager.getRestoredDir(context)

        assertTrue(recycleBin.isDirectory)
        assertEquals(".recycle_bin", recycleBin.name)
        assertTrue(vault.isDirectory)
        assertEquals(".vault", vault.name)
        assertTrue(restored.isDirectory)
        assertEquals("Restored", restored.name)
    }

    @Test
    fun renameFile_rejectsPathTraversalWithoutChangingSource() {
        val srcFile = File(testDir, "protected.txt").apply { writeText("keep") }

        val result = PhysicalStorageManager.renameFile(context, srcFile.absolutePath, "../escape.txt")

        assertTrue(result.isFailure)
        assertTrue(srcFile.exists())
        assertEquals("keep", srcFile.readText())
    }

    @Test
    fun missingFileOperations_failClosed() {
        val missingSource = File(testDir, "missing.txt")
        val moveResult = PhysicalStorageManager.moveToTrash(context, missingSource.absolutePath)
        val restoreResult = PhysicalStorageManager.restoreFromTrash(
            context,
            File(testDir, "missing-trash.vvf").absolutePath,
            File(testDir, "restored.txt").absolutePath,
        )

        assertTrue(moveResult.isFailure)
        assertTrue(restoreResult.isFailure)
    }

    @Test
    fun encryptAndWipeSource_writesVaultAndRemovesPlaintextSource() {
        val source = File(testDir, "secret.txt").apply { writeText("plaintext") }
        val iv = byteArrayOf(1, 2, 3)
        val encrypted = byteArrayOf(9, 8, 7)

        val result = PhysicalStorageManager.encryptAndWipeSource(context, source.absolutePath) { bytes ->
            assertEquals("plaintext", bytes.toString(Charsets.UTF_8))
            Pair(encrypted, iv)
        }

        assertTrue(result.isSuccess)
        val vaultResult = result.getOrThrow()
        assertFalse(source.exists())
        assertTrue(File(vaultResult.vaultFilePath).exists())
        assertEquals(encrypted.toList(), File(vaultResult.vaultFilePath).readBytes().toList())
        assertEquals(iv.toList(), vaultResult.iv.toList())
    }

    @Test
    fun streamingVaultRoundTrip_handlesPayloadLargerThanLegacyLimitWithoutLoadingWholeFile() {
        val source = File(testDir, "large-secret.bin")
        val expected = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        source.writeBytes(expected)
        val session = VaultCryptoSession.fromKeyBytes(ByteArray(32) { 7 })

        val encrypted = PhysicalStorageManager.encryptAndWipeSourceStreaming(context, source.absolutePath, session)

        assertTrue(encrypted.isSuccess)
        assertFalse(source.exists())
        val vault = encrypted.getOrThrow()
        val restoreTarget = File(testDir, "restored-large-secret.bin")
        val restored = PhysicalStorageManager.decryptAndRestoreStreaming(
            context = context,
            request = VaultRestoreRequest(
                vaultFilePath = vault.vaultFilePath,
                originalPath = restoreTarget.absolutePath,
                originalName = source.name,
                iv = vault.iv
            ),
            session = session,
        )

        assertTrue(restored.isSuccess)
        assertEquals(expected.toList(), restoreTarget.readBytes().toList())
        assertFalse(File(vault.vaultFilePath).exists())
        session.close()
    }

    @Test
    fun streamingVaultRestore_tamperingFailsClosedAndPreservesEncryptedSource() {
        val source = File(testDir, "secret.bin").apply {
            writeBytes(ByteArray(128 * 1024) { 9 })
        }
        val session = VaultCryptoSession.fromKeyBytes(ByteArray(32) { 3 })
        val encrypted = PhysicalStorageManager
            .encryptAndWipeSourceStreaming(context, source.absolutePath, session)
            .getOrThrow()
        val vault = File(encrypted.vaultFilePath)
        java.io.RandomAccessFile(vault, "rw").use { file ->
            file.seek(file.length() - 1)
            file.write(file.read().xor(0x01))
        }
        val restoreTarget = File(testDir, "tampered-restored.bin")

        val restored = PhysicalStorageManager.decryptAndRestoreStreaming(
            context = context,
            request = VaultRestoreRequest(
                vaultFilePath = vault.absolutePath,
                originalPath = restoreTarget.absolutePath,
                originalName = source.name,
                iv = encrypted.iv
            ),
            session = session,
        )

        assertTrue(restored.isFailure)
        assertTrue(vault.exists())
        assertFalse(restoreTarget.exists())
        session.close()
    }

    @Test
    fun decryptAndRestore_restoresPlainFileAndDeletesVaultSource() {
        val vault = File(testDir, "encrypted.vvf").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val restored = File(testDir, "restored.txt")

        val result = PhysicalStorageManager.decryptAndRestore(context, vault.absolutePath, restored.absolutePath) { bytes ->
            assertEquals(listOf<Byte>(4, 5, 6), bytes.toList())
            "decrypted".toByteArray()
        }

        assertTrue(result.isSuccess)
        assertEquals(restored.absolutePath, result.getOrThrow())
        assertEquals("decrypted", restored.readText())
        assertFalse(vault.exists())
    }

    @Test
    fun decryptAndRestore_tamperedDataFailsWithoutDeletingVaultSource() {
        val vault = File(testDir, "tampered.vvf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val restored = File(testDir, "tampered-restored.txt")

        val result = PhysicalStorageManager.decryptAndRestore(context, vault.absolutePath, restored.absolutePath) {
            throw javax.crypto.AEADBadTagException("tampered")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.security.GeneralSecurityException)
        assertTrue(vault.exists())
        assertFalse(restored.exists())
    }

    @Test
    fun encodedNames_areRecoveredSafelyFromVaultAndTrashPaths() {
        val uri = android.net.Uri.parse("content://example/provider/opaque-id")

        assertEquals(
            "photo.jpg",
            PhysicalStorageManager.getFileNameFromVaultPathOrUri(
                context,
                "/vault/ENC_123_photo.jpg.vvf",
                uri,
            ),
        )
        assertEquals(
            "photo.jpg",
            PhysicalStorageManager.getFileNameFromTrashPathOrUri(context, "123_photo.jpg", uri),
        )
    }
}
