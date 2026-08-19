package com.example.data

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
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
