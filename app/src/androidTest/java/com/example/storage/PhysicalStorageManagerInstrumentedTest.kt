package com.example.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
