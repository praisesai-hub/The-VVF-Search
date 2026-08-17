package com.example.data

import android.content.Context
import com.example.security.KeystoreVaultManager
import com.example.security.VaultCryptoSession
import com.example.storage.PhysicalStorageManager
import com.example.storage.VaultStorageResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultRepositoryTest {
    private lateinit var context: Context
    private lateinit var dao: FileDao
    private lateinit var keystore: KeystoreVaultManager
    private lateinit var vaultEngine: VaultManagerEngine
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dao = mockk(relaxed = true)
        keystore = mockk(relaxed = true)
        vaultEngine = mockk(relaxed = true)
        every { vaultEngine.unlockWithPin(any()) } returns VaultCryptoSession.fromKeyBytes(ByteArray(32) { 7 })
        every { vaultEngine.hasBiometricEnrollment } returns false
        repository = VaultRepository(context, dao, keystore, vaultEngine)
        mockkObject(PhysicalStorageManager)
    }

    @After
    fun tearDown() {
        unmockkObject(PhysicalStorageManager)
    }

    @Test
    fun pinOperations_delegateToVaultManagerEngine() {
        every { vaultEngine.hasVaultPin() } returns true
        every { vaultEngine.getStoredVaultPinHash() } returns "stored-hash"
        every { vaultEngine.initializeVaultPin("1234") } returns true
        every { vaultEngine.verifyVaultPin("1234", "stored-hash") } returns true
        every { vaultEngine.changeVaultPin("1234", "5678") } returns true

        assertTrue(repository.hasVaultPin())
        assertEquals("stored-hash", repository.getStoredVaultPinHash())
        assertTrue(repository.initializeVaultPin("1234"))
        assertTrue(repository.verifyVaultPin("1234", "stored-hash"))
        assertTrue(repository.changeVaultPin("1234", "5678"))
    }

    @Test
    fun encryptToVault_updatesFileAndPersistsVaultMetadataAfterPhysicalSuccess() = runBlocking {
        val file = fileItem(id = 9L, name = "report.pdf", path = "/source/report.pdf")
        val result = VaultStorageResult(
            vaultFilePath = "/vault/ENC_123_report.pdf.vvf",
            encryptedFileName = "ENC_123_report.pdf.vvf",
            iv = byteArrayOf(1, 2, 3, 4)
        )
        repository.unlockWithPin("1234")
        every {
            PhysicalStorageManager.encryptAndWipeSourceStreaming(
                context,
                file.path,
                any()
            )
        } returns Result.success(result)
        val updated = slot<FileItemEntity>()
        val inserted = slot<VaultItemEntity>()
        coEvery { dao.updateFile(capture(updated)) } just Runs
        coEvery { dao.insertVaultItem(capture(inserted)) } returns 17L

        repository.encryptToVault(file)

        assertTrue(updated.captured.isVault)
        assertEquals(file.id, updated.captured.id)
        assertEquals(file.path, updated.captured.path)
        assertEquals(file.name, inserted.captured.originalName)
        assertEquals(result.encryptedFileName, inserted.captured.encryptedName)
        assertEquals(result.vaultFilePath, inserted.captured.encryptedFilePath)
        assertEquals("AQIDBA==", inserted.captured.ivBase64)
        assertEquals(file.category, inserted.captured.category)
        assertEquals(file.sizeBytes, inserted.captured.sizeBytes)
        coVerify(exactly = 1) { dao.updateFile(any()) }
        coVerify(exactly = 1) { dao.insertVaultItem(any()) }
    }

    @Test
    fun encryptToVault_throwsPhysicalFailureAndDoesNotMutateDao() = runBlocking {
        val file = fileItem(name = "report.pdf", path = "/source/report.pdf")
        val failure = IOException("source could not be securely wiped")
        repository.unlockWithPin("1234")
        every {
            PhysicalStorageManager.encryptAndWipeSourceStreaming(
                context,
                file.path,
                any()
            )
        } returns Result.failure(failure)

        try {
            repository.encryptToVault(file)
            fail("Expected physical encryption failure")
        } catch (error: IOException) {
            assertEquals(failure.message, error.message)
        }

        coVerify(exactly = 0) { dao.updateFile(any()) }
        coVerify(exactly = 0) { dao.insertVaultItem(any()) }
    }

    @Test
    fun unlockFromVault_usesDaoTargetAndClearsVaultAfterPhysicalSuccess() = runBlocking {
        val target = fileItem(id = 12L, name = "photo.jpg", path = "/source/photo.jpg")
        val vaultItem = vaultItem(id = 31L, originalName = target.name)
        coEvery { dao.getVaultFileByName(target.name) } returns target
        repository.unlockWithPin("1234")
        every {
            PhysicalStorageManager.decryptAndRestoreStreaming(
                context,
                any(),
                any()
            )
        } returns Result.success(target.path)
        val updated = slot<FileItemEntity>()
        coEvery { dao.updateFile(capture(updated)) } just Runs
        coEvery { dao.deleteVaultItemById(vaultItem.id) } just Runs

        assertTrue(repository.unlockFromVault(vaultItem, file = null))

        assertFalse(updated.captured.isVault)
        assertEquals(target.id, updated.captured.id)
        coVerify(exactly = 1) { dao.getVaultFileByName(target.name) }
        coVerify(exactly = 1) { dao.updateFile(any()) }
        coVerify(exactly = 1) { dao.deleteVaultItemById(vaultItem.id) }
    }

    @Test
    fun unlockFromVault_deletesOrphanedVaultMetadataWhenTargetIsMissing() = runBlocking {
        val vaultItem = vaultItem(id = 44L, originalName = "missing.txt")
        repository.unlockWithPin("1234")
        coEvery { dao.getVaultFileByName(vaultItem.originalName) } returns null
        coEvery { dao.deleteVaultItemById(vaultItem.id) } just Runs

        assertTrue(repository.unlockFromVault(vaultItem, file = null))

        coVerify(exactly = 1) { dao.getVaultFileByName(vaultItem.originalName) }
        coVerify(exactly = 1) { dao.deleteVaultItemById(vaultItem.id) }
        coVerify(exactly = 0) { dao.updateFile(any()) }
    }

    @Test
    fun unlockFromVault_rethrowsPhysicalFailureAndPreservesDaoState() = runBlocking {
        val target = fileItem(id = 16L, name = "secret.txt", path = "/source/secret.txt")
        val vaultItem = vaultItem(id = 52L, originalName = target.name)
        val failure = IOException("tampered vault data")
        repository.unlockWithPin("1234")
        every {
            PhysicalStorageManager.decryptAndRestoreStreaming(
                context,
                any(),
                any()
            )
        } returns Result.failure(failure)

        try {
            repository.unlockFromVault(vaultItem, target)
            fail("Expected physical decryption failure")
        } catch (error: IOException) {
            assertEquals(failure.message, error.message)
        }

        coVerify(exactly = 0) { dao.getVaultFileByName(any()) }
        coVerify(exactly = 0) { dao.updateFile(any()) }
        coVerify(exactly = 0) { dao.deleteVaultItemById(any()) }
    }

    private fun fileItem(
        id: Long = 1L,
        name: String,
        path: String,
        category: String = "DOCUMENTS"
    ) = FileItemEntity(
        id = id,
        name = name,
        path = path,
        category = category,
        sizeBytes = 128L,
        dateModifiedMs = 1_700_000_000_000L
    )

    private fun vaultItem(
        id: Long,
        originalName: String
    ) = VaultItemEntity(
        id = id,
        originalName = originalName,
        encryptedName = "ENC_123_$originalName.vvf",
        encryptedFilePath = "/vault/ENC_123_$originalName.vvf",
        ivBase64 = "AQIDBA==",
        category = "DOCUMENTS",
        sizeBytes = 128L,
        encryptedAtMs = 1_700_000_000_000L,
        vaultFormatVersion = 2
    )
}
