package com.example.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.storage.PhysicalStorageManager
import com.example.storage.VaultStorageResult
import java.io.File
import java.util.UUID

/** Reconciles durable vault-operation intent records after process interruption. */
@Suppress(
    "TooManyFunctions",
    "LongMethod",
    "ReturnCount",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "TooGenericExceptionCaught"
)
class VaultOperationCoordinator(
    private val context: Context,
    private val dao: FileDao
) {
    suspend fun prepareEncryption(
        file: FileItemEntity,
        isBiometricProtected: Boolean
    ): VaultOperationEntity = persist(
        VaultOperationEntity(
            id = UUID.randomUUID().toString(),
            operationType = VaultOperationType.ENCRYPT,
            sourceFileId = file.id,
            sourcePath = file.path,
            originalName = file.name,
            category = file.category,
            sizeBytes = file.sizeBytes,
            isBiometricProtected = isBiometricProtected
        )
    )

    suspend fun prepareRestore(
        vaultItem: VaultItemEntity,
        target: FileItemEntity
    ): VaultOperationEntity {
        val operationId = UUID.randomUUID().toString()
        val destination = if (target.path.startsWith("content://")) {
            File(
                PhysicalStorageManager.getRestoredDir(context),
                "RESTORED_${operationId}_${PhysicalStorageManager.safeTrashFileName(vaultItem.originalName)}"
            ).absolutePath
        } else {
            target.path
        }
        check(!File(destination).exists()) { "Restore destination already exists" }
        return persist(VaultOperationEntity(
            id = operationId,
            operationType = VaultOperationType.RESTORE,
            sourceFileId = target.id,
            vaultItemId = vaultItem.id,
            sourcePath = vaultItem.encryptedFilePath,
            encryptedFilePath = vaultItem.encryptedFilePath,
            encryptedFileName = vaultItem.encryptedName,
            restoreDestinationPath = destination,
            originalName = vaultItem.originalName,
            category = vaultItem.category,
            sizeBytes = vaultItem.sizeBytes,
            ivBase64 = vaultItem.ivBase64,
            isBiometricProtected = vaultItem.isBiometricProtected
        ))
    }

    suspend fun markEncrypted(
        operation: VaultOperationEntity,
        result: VaultStorageResult
    ): VaultOperationEntity = persist(
        operation.copy(
            state = VaultOperationState.ENCRYPTED,
            encryptedFilePath = result.vaultFilePath,
            encryptedFileName = result.encryptedFileName,
            ivBase64 = Base64.encodeToString(result.iv, Base64.NO_WRAP)
        )
    )

    suspend fun markState(
        operation: VaultOperationEntity,
        state: String,
        restoreDestinationPath: String = operation.restoreDestinationPath
    ): VaultOperationEntity = persist(
        operation.copy(state = state, restoreDestinationPath = restoreDestinationPath)
    )

    suspend fun commitEncryptionMetadata(operation: VaultOperationEntity): VaultOperationEntity {
        val source = dao.getFileById(operation.sourceFileId)
            ?: return markRecoveryRequired(operation, "Source metadata row is missing")
        val existing = dao.getVaultItemByEncryptedPath(operation.encryptedFilePath)
        if (existing == null) {
            dao.commitVaultEncryptionMetadata(
                source = source,
                vaultItem = VaultItemEntity(
                    originalName = operation.originalName,
                    encryptedName = operation.encryptedFileName,
                    encryptedFilePath = operation.encryptedFilePath,
                    ivBase64 = operation.ivBase64,
                    category = operation.category,
                    sizeBytes = operation.sizeBytes,
                    isBiometricProtected = operation.isBiometricProtected,
                    vaultFormatVersion = 2
                ),
                operation = operation
            )
        } else {
            dao.upsertVaultOperation(operation.copy(state = VaultOperationState.METADATA_COMMITTED))
        }
        return operation.copy(state = VaultOperationState.METADATA_COMMITTED)
    }

    suspend fun commitRestoreMetadata(operation: VaultOperationEntity): VaultOperationEntity {
        val target = dao.getFileById(operation.sourceFileId)
            ?: return markRecoveryRequired(operation, "Restore destination metadata row is missing")
        dao.commitVaultRestoreMetadata(
            restoredFile = target.copy(path = operation.restoreDestinationPath),
            vaultItemId = operation.vaultItemId,
            operation = operation
        )
        return operation.copy(state = VaultOperationState.METADATA_COMMITTED)
    }

    suspend fun markCompleted(operation: VaultOperationEntity): VaultOperationEntity =
        markState(operation, VaultOperationState.COMPLETED)

    suspend fun completeIfMetadataCommitted(operation: VaultOperationEntity): VaultOperationEntity =
        if (operation.state == VaultOperationState.METADATA_COMMITTED) {
            markCompleted(operation)
        } else {
            operation
        }

    suspend fun markRecoveryRequired(
        operation: VaultOperationEntity,
        reason: String
    ): VaultOperationEntity = persist(
        operation.copy(state = VaultOperationState.RECOVERY_REQUIRED, recoveryError = reason)
    )

    suspend fun recoverIncompleteOperations() {
        dao.getIncompleteVaultOperations().forEach { operation ->
            try {
                when (operation.operationType) {
                    VaultOperationType.ENCRYPT -> recoverEncryption(operation)
                    VaultOperationType.RESTORE -> recoverRestore(operation)
                    else -> markRecoveryRequired(operation, "Unknown vault operation type")
                }
            } catch (error: Exception) {
                Log.e(TAG, "Vault recovery failed for ${operation.id}", error)
                markRecoveryRequired(operation, error.message ?: "Unexpected recovery error")
            }
        }
    }

    private suspend fun recoverEncryption(operation: VaultOperationEntity) {
        val sourcePresence = PhysicalStorageManager.sourceExists(context, operation.sourcePath)
        if (sourcePresence.isFailure) {
            markRecoveryRequired(
                operation,
                "Unable to determine source presence: ${sourcePresence.exceptionOrNull()?.message}"
            )
            return
        }
        val sourceExists = sourcePresence.getOrThrow()
        val vaultExists = File(operation.encryptedFilePath).isFile
        when (operation.state) {
            VaultOperationState.PREPARED -> {
                if (sourceExists) markCompleted(operation)
                else markRecoveryRequired(operation, "Source disappeared before encryption completed")
            }
            VaultOperationState.ENCRYPTED,
            VaultOperationState.VERIFIED,
            VaultOperationState.SOURCE_REMOVAL_PENDING -> {
                if (sourceExists) {
                    if (vaultExists && !PhysicalStorageManager.removeEncryptedVaultFile(operation.encryptedFilePath)) {
                        markRecoveryRequired(operation, "Unable to remove uncommitted encrypted vault file")
                    } else {
                        markCompleted(operation)
                    }
                } else if (vaultExists) {
                    completeIfMetadataCommitted(
                        commitEncryptionMetadata(markState(operation, VaultOperationState.SOURCE_REMOVED))
                    )
                } else {
                    markRecoveryRequired(operation, "Both source and encrypted vault file are missing")
                }
            }
            VaultOperationState.SOURCE_REMOVED -> {
                if (vaultExists) completeIfMetadataCommitted(commitEncryptionMetadata(operation))
                else markRecoveryRequired(operation, "Encrypted vault file is missing after source removal")
            }
            VaultOperationState.METADATA_COMMITTED -> markCompleted(operation)
        }
    }

    private suspend fun recoverRestore(operation: VaultOperationEntity) {
        val vaultExists = File(operation.encryptedFilePath).isFile
        val destinationExists = File(operation.restoreDestinationPath).isFile
        when (operation.state) {
            VaultOperationState.PREPARED -> markCompleted(operation)
            VaultOperationState.RESTORE_WRITE_PENDING -> {
                if (destinationExists) {
                    if (vaultExists && !PhysicalStorageManager.removeEncryptedVaultFile(operation.encryptedFilePath)) {
                        markRecoveryRequired(operation, "Unable to remove encrypted vault file after restore")
                    } else {
                        completeIfMetadataCommitted(
                            commitRestoreMetadata(markState(operation, VaultOperationState.VAULT_REMOVED))
                        )
                    }
                } else {
                    markCompleted(operation)
                }
            }
            VaultOperationState.RESTORED,
            VaultOperationState.VAULT_REMOVAL_PENDING -> {
                if (!destinationExists) {
                    markCompleted(operation)
                } else if (
                    vaultExists &&
                    !PhysicalStorageManager.removeEncryptedVaultFile(operation.encryptedFilePath)
                ) {
                    markRecoveryRequired(operation, "Unable to remove encrypted vault file after restore")
                } else {
                    completeIfMetadataCommitted(
                        commitRestoreMetadata(markState(operation, VaultOperationState.VAULT_REMOVED))
                    )
                }
            }
            VaultOperationState.VAULT_REMOVED -> {
                if (destinationExists) {
                    completeIfMetadataCommitted(commitRestoreMetadata(operation))
                }
                else markRecoveryRequired(operation, "Restored destination is missing after vault removal")
            }
            VaultOperationState.METADATA_COMMITTED -> markCompleted(operation)
        }
    }

    private suspend fun persist(operation: VaultOperationEntity): VaultOperationEntity {
        val updated = operation.copy(updatedAtMs = System.currentTimeMillis())
        dao.upsertVaultOperation(updated)
        return updated
    }

    private companion object {
        const val TAG = "VaultOperationCoordinator"
    }
}
