package com.example.data

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.example.security.KeystoreVaultManager
import com.example.security.VaultCryptoSession
import com.example.storage.PhysicalStorageManager
import com.example.storage.VaultRestoreRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val CURRENT_VAULT_FORMAT_VERSION = 2

class VaultRepository(
    private val context: Context,
    private val dao: FileDao,
    private val keystoreVaultManager: KeystoreVaultManager,
    vaultManagerEngine: VaultManagerEngine = VaultManagerEngine(context, keystoreVaultManager)
) : VaultSecurityApi by VaultSecurityDelegate(vaultManagerEngine) {
    private val engine = vaultManagerEngine
    private val operationCoordinator = VaultOperationCoordinator(context, dao)

    suspend fun encryptToVault(file: FileItemEntity) = withContext(Dispatchers.IO) {
        val session = requireAuthenticatedSession()
        val prepared = operationCoordinator.prepareEncryption(
            file = file,
            isBiometricProtected = engine.hasBiometricEnrollment
        )
        val vaultStorageResult = PhysicalStorageManager.encryptSourceStreaming(
            context = context,
            srcPath = file.path,
            session = session
        )

        if (vaultStorageResult.isSuccess) {
            val result = vaultStorageResult.getOrThrow()
            val encrypted = operationCoordinator.markEncrypted(prepared, result)
            val verification = PhysicalStorageManager.verifyEncryptedVaultFile(
                vaultFilePath = result.vaultFilePath,
                iv = result.iv,
                session = session
            )
            if (verification.isFailure) {
                PhysicalStorageManager.removeEncryptedVaultFile(result.vaultFilePath)
                operationCoordinator.markCompleted(encrypted)
                throw verification.exceptionOrNull()
                    ?: java.io.IOException("Encrypted vault file verification failed")
            }
            val verified = operationCoordinator.markState(encrypted, VaultOperationState.VERIFIED)
            val removalPending = operationCoordinator.markState(
                verified,
                VaultOperationState.SOURCE_REMOVAL_PENDING
            )
            if (!PhysicalStorageManager.removeSourceAfterVaultEncryption(context, file.path)) {
                throw java.io.IOException("Failed to remove plaintext source after encryption")
            }
            val sourceRemoved = operationCoordinator.markState(
                removalPending,
                VaultOperationState.SOURCE_REMOVED
            )
            val completed = operationCoordinator.completeIfMetadataCommitted(
                operationCoordinator.commitEncryptionMetadata(sourceRemoved)
            )
            check(completed.state == VaultOperationState.COMPLETED) {
                "Vault encryption metadata commit requires recovery"
            }
        } else {
            operationCoordinator.markCompleted(prepared)
            throw vaultStorageResult.exceptionOrNull()
                ?: java.io.IOException("Failed to encrypt and wipe source file")
        }
    }

    suspend fun unlockFromVault(vaultItem: VaultItemEntity, file: FileItemEntity?): Boolean =
        withContext(Dispatchers.IO) {
            val session = requireAuthenticatedSession()
            val migratedItem = if (vaultItem.vaultFormatVersion < CURRENT_VAULT_FORMAT_VERSION) {
                migrateLegacyItem(vaultItem, session)
            } else {
                vaultItem
            }
            val targetFile = file ?: dao.getVaultFileByName(migratedItem.originalName)
                ?: throw java.io.IOException("Vault restore target metadata is missing")
            val prepared = operationCoordinator.prepareRestore(migratedItem, targetFile)
            val writePending = operationCoordinator.markState(
                prepared,
                VaultOperationState.RESTORE_WRITE_PENDING
            )
            val iv = Base64.decode(migratedItem.ivBase64, Base64.DEFAULT)
            val decryptResult = PhysicalStorageManager.decryptToRestoreDestinationStreaming(
                context = context,
                request = VaultRestoreRequest(
                    vaultFilePath = migratedItem.encryptedFilePath,
                    originalPath = targetFile.path,
                    originalName = migratedItem.originalName,
                    iv = iv,
                    restoreDestinationPath = prepared.restoreDestinationPath
                ),
                session = session
            )
            if (decryptResult.isFailure) {
                operationCoordinator.markCompleted(writePending)
                throw decryptResult.exceptionOrNull()
                    ?: java.io.IOException("Failed to physically decrypt vault file")
            }
            val restored = operationCoordinator.markState(
                writePending,
                VaultOperationState.RESTORED,
                decryptResult.getOrThrow()
            )
            val removalPending = operationCoordinator.markState(
                restored,
                VaultOperationState.VAULT_REMOVAL_PENDING
            )
            if (!PhysicalStorageManager.removeEncryptedVaultFile(migratedItem.encryptedFilePath)) {
                throw java.io.IOException("Unable to delete encrypted vault file after restoration")
            }
            val vaultRemoved = operationCoordinator.markState(
                removalPending,
                VaultOperationState.VAULT_REMOVED
            )
            val completed = operationCoordinator.completeIfMetadataCommitted(
                operationCoordinator.commitRestoreMetadata(vaultRemoved)
            )
            check(completed.state == VaultOperationState.COMPLETED) {
                "Vault restore metadata commit requires recovery"
            }
            true
        }

    suspend fun recoverIncompleteOperations() = withContext(Dispatchers.IO) {
        operationCoordinator.recoverIncompleteOperations()
    }

    /** Re-encrypts a legacy direct-Keystore vault file with the authenticated V2 session. */
    private suspend fun migrateLegacyItem(
        item: VaultItemEntity,
        session: VaultCryptoSession
    ): VaultItemEntity {
        val legacyFile = File(item.encryptedFilePath)
        check(legacyFile.exists()) { "Legacy vault file is missing" }
        val legacyIv = Base64.decode(item.ivBase64, Base64.DEFAULT)
        val plaintext = keystoreVaultManager.decryptBytes(legacyFile.readBytes(), legacyIv)
        return try {
            val encrypted = session.encryptBytes(plaintext)
            val atomicFile = AtomicFile(legacyFile)
            val output = atomicFile.startWrite()
            try {
                output.write(encrypted.ciphertext)
                output.flush()
                atomicFile.finishWrite(output)
            } catch (error: java.io.IOException) {
                atomicFile.failWrite(output)
                throw error
            }
            check(legacyFile.exists() && legacyFile.length() > 0L) {
                "V2 vault replacement was not written"
            }
            val migrated = item.copy(
                ivBase64 = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP),
                isBiometricProtected = engine.hasBiometricEnrollment,
                vaultFormatVersion = CURRENT_VAULT_FORMAT_VERSION
            )
            dao.insertVaultItem(migrated)
            migrated
        } finally {
            plaintext.fill(0)
        }
    }
}
