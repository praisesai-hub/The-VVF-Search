package com.example.data

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultOperationCoordinatorTest {
    private lateinit var context: Context
    private lateinit var dao: FileDao
    private lateinit var coordinator: VaultOperationCoordinator
    private lateinit var testDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dao = mockk(relaxed = true)
        coordinator = VaultOperationCoordinator(context, dao)
        testDir = File(context.cacheDir, "vault-operation-test-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun recovery_rollsBackUncommittedEncryptionWhenPlaintextStillExists() = runBlocking {
        val source = File(testDir, "source.txt").apply { writeText("plaintext") }
        val encrypted = File(testDir, "encrypted.vvf").apply { writeText("ciphertext") }
        val operation = encryptionOperation(
            state = VaultOperationState.SOURCE_REMOVAL_PENDING,
            sourcePath = source.absolutePath,
            encryptedPath = encrypted.absolutePath
        )
        coEvery { dao.getIncompleteVaultOperations() } returns listOf(operation)
        coEvery { dao.upsertVaultOperation(any()) } just Runs

        coordinator.recoverIncompleteOperations()

        assertTrue(source.exists())
        assertFalse(encrypted.exists())
        coVerify(exactly = 0) { dao.commitVaultEncryptionMetadata(any(), any(), any()) }
        coVerify { dao.upsertVaultOperation(match { it.state == VaultOperationState.COMPLETED }) }
    }

    @Test
    fun recovery_commitsEncryptionMetadataAfterCrashFollowingSourceRemoval() = runBlocking {
        val encrypted = File(testDir, "encrypted.vvf").apply { writeText("ciphertext") }
        val source = fileItem(id = 7L, path = File(testDir, "deleted-source.txt").absolutePath)
        val operation = encryptionOperation(
            state = VaultOperationState.SOURCE_REMOVED,
            sourcePath = source.path,
            encryptedPath = encrypted.absolutePath
        )
        coEvery { dao.getIncompleteVaultOperations() } returns listOf(operation)
        coEvery { dao.getFileById(source.id) } returns source
        coEvery { dao.getVaultItemByEncryptedPath(encrypted.absolutePath) } returns null
        coEvery { dao.commitVaultEncryptionMetadata(any(), any(), any()) } just Runs
        coEvery { dao.upsertVaultOperation(any()) } just Runs

        coordinator.recoverIncompleteOperations()

        assertTrue(encrypted.exists())
        coVerify(exactly = 1) { dao.commitVaultEncryptionMetadata(any(), any(), any()) }
        coVerify { dao.upsertVaultOperation(match { it.state == VaultOperationState.COMPLETED }) }
    }

    @Test
    fun recovery_commitsRestoreMetadataAfterCrashFollowingVaultRemoval() = runBlocking {
        val destination = File(testDir, "restored.txt").apply { writeText("plaintext") }
        val target = fileItem(id = 9L, path = File(testDir, "original.txt").absolutePath)
        val operation = VaultOperationEntity(
            id = "restore-op",
            operationType = VaultOperationType.RESTORE,
            state = VaultOperationState.VAULT_REMOVED,
            sourceFileId = target.id,
            vaultItemId = 11L,
            encryptedFilePath = File(testDir, "deleted.vvf").absolutePath,
            restoreDestinationPath = destination.absolutePath,
            originalName = "original.txt",
            category = "DOCUMENTS",
            sizeBytes = 9L,
            ivBase64 = "AQIDBA=="
        )
        coEvery { dao.getIncompleteVaultOperations() } returns listOf(operation)
        coEvery { dao.getFileById(target.id) } returns target
        coEvery { dao.commitVaultRestoreMetadata(any(), 11L, any()) } just Runs
        coEvery { dao.upsertVaultOperation(any()) } just Runs

        coordinator.recoverIncompleteOperations()

        coVerify(exactly = 1) { dao.commitVaultRestoreMetadata(any(), 11L, any()) }
        coVerify { dao.upsertVaultOperation(match { it.state == VaultOperationState.COMPLETED }) }
    }

    @Test
    fun prepareEncryption_persistsDurableIntentBeforeDestructiveWork() = runBlocking {
        val source = fileItem(id = 41L, path = File(testDir, "source.pdf").absolutePath)

        val operation = coordinator.prepareEncryption(source, isBiometricProtected = true)

        assertEquals(VaultOperationType.ENCRYPT, operation.operationType)
        assertEquals(VaultOperationState.PREPARED, operation.state)
        assertEquals(source.id, operation.sourceFileId)
        assertEquals(source.path, operation.sourcePath)
        assertTrue(operation.isBiometricProtected)
        coVerify {
            dao.upsertVaultOperation(
                match { it.id == operation.id && it.state == VaultOperationState.PREPARED }
            )
        }
    }

    @Test
    fun prepareRestore_usesOriginalPathAndRejectsExistingDestination() = runBlocking {
        val destination = File(testDir, "restore-target.txt")
        val target = fileItem(id = 42L, path = destination.absolutePath)
        val vaultItem = VaultItemEntity(
            id = 17L,
            originalName = "restore-target.txt",
            encryptedName = "ENC_restore-target.vvf",
            encryptedFilePath = File(testDir, "ENC_restore-target.vvf").absolutePath,
            ivBase64 = "AQID",
            category = "DOCUMENTS",
            sizeBytes = 22L,
            isBiometricProtected = true
        )

        val operation = coordinator.prepareRestore(vaultItem, target)

        assertEquals(VaultOperationType.RESTORE, operation.operationType)
        assertEquals(destination.absolutePath, operation.restoreDestinationPath)
        assertEquals(vaultItem.id, operation.vaultItemId)
        assertTrue(operation.isBiometricProtected)
        destination.writeText("existing")
        try {
            coordinator.prepareRestore(vaultItem, target)
            throw AssertionError("existing restore destinations must be rejected")
        } catch (_: IllegalStateException) {
            assertTrue(destination.exists())
        }
    }

    @Test
    fun markEncrypted_persistsPathNameAndBase64Iv() = runBlocking {
        val operation = encryptionOperation(
            state = VaultOperationState.PREPARED,
            sourcePath = File(testDir, "source.txt").absolutePath,
            encryptedPath = ""
        )

        val updated = coordinator.markEncrypted(
            operation,
            VaultStorageResult(
                vaultFilePath = File(testDir, "ENC_source.vvf").absolutePath,
                encryptedFileName = "ENC_source.vvf",
                iv = byteArrayOf(1, 2, 3)
            )
        )

        assertEquals(VaultOperationState.ENCRYPTED, updated.state)
        assertEquals("ENC_source.vvf", updated.encryptedFileName)
        assertEquals("AQID", updated.ivBase64)
        coVerify { dao.upsertVaultOperation(match { it.state == VaultOperationState.ENCRYPTED }) }
    }

    @Test
    fun commitEncryptionMetadata_handlesMissingSourceAndExistingVaultIdempotently() = runBlocking {
        val operation = encryptionOperation(
            state = VaultOperationState.SOURCE_REMOVED,
            sourcePath = File(testDir, "missing.txt").absolutePath,
            encryptedPath = File(testDir, "encrypted.vvf").absolutePath
        )
        coEvery { dao.getFileById(operation.sourceFileId) } returns null

        val missingSource = coordinator.commitEncryptionMetadata(operation)

        assertEquals(VaultOperationState.RECOVERY_REQUIRED, missingSource.state)
        coVerify(exactly = 0) { dao.commitVaultEncryptionMetadata(any(), any(), any()) }

        val source = fileItem(operation.sourceFileId, operation.sourcePath)
        coEvery { dao.getFileById(operation.sourceFileId) } returns source
        coEvery { dao.getVaultItemByEncryptedPath(operation.encryptedFilePath) } returns VaultItemEntity(
            id = 19L,
            originalName = operation.originalName,
            encryptedName = operation.encryptedFileName,
            encryptedFilePath = operation.encryptedFilePath,
            category = operation.category,
            sizeBytes = operation.sizeBytes
        )

        val idempotent = coordinator.commitEncryptionMetadata(operation)

        assertEquals(VaultOperationState.METADATA_COMMITTED, idempotent.state)
        coVerify(exactly = 0) { dao.commitVaultEncryptionMetadata(any(), any(), any()) }
        coVerify { dao.upsertVaultOperation(match { it.state == VaultOperationState.METADATA_COMMITTED }) }
    }

    @Test
    fun recovery_marksUnknownOperationTypeAsRecoveryRequired() = runBlocking {
        val operation = encryptionOperation(
            state = VaultOperationState.PREPARED,
            sourcePath = File(testDir, "source.txt").absolutePath,
            encryptedPath = File(testDir, "encrypted.vvf").absolutePath
        ).copy(operationType = "UNSUPPORTED")
        coEvery { dao.getIncompleteVaultOperations() } returns listOf(operation)

        coordinator.recoverIncompleteOperations()

        coVerify {
            dao.upsertVaultOperation(
                match {
                    it.state == VaultOperationState.RECOVERY_REQUIRED &&
                        it.recoveryError == "Unknown vault operation type"
                }
            )
        }
    }

    private fun encryptionOperation(
        state: String,
        sourcePath: String,
        encryptedPath: String
    ) = VaultOperationEntity(
        id = "encrypt-op",
        operationType = VaultOperationType.ENCRYPT,
        state = state,
        sourceFileId = 7L,
        sourcePath = sourcePath,
        encryptedFilePath = encryptedPath,
        encryptedFileName = "encrypted.vvf",
        originalName = "source.txt",
        category = "DOCUMENTS",
        sizeBytes = 9L,
        ivBase64 = "AQIDBA=="
    )

    private fun fileItem(id: Long, path: String) = FileItemEntity(
        id = id,
        name = File(path).name,
        path = path,
        category = "DOCUMENTS",
        sizeBytes = 9L
    )
}
