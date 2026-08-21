package com.example.data

import android.content.Context
import com.example.storage.PhysicalStorageManager
import java.io.File

/** Durable state machine for destructive file operations and crash recovery. */
internal class FileOperationCoordinator(
    private val context: Context,
    private val dao: FileDao,
    private val operationStore: FileOperationStore
) {
    suspend fun moveToRecycleBin(file: FileItemEntity) {
        val currentFile = dao.getFileById(file.id) ?: return
        if (currentFile.isRecycleBin) return
        val operation = prepare(
            type = MOVE_TO_TRASH,
            fileId = currentFile.id,
            sourcePath = currentFile.path,
            targetPath = PhysicalStorageManager.trashPathForOperation(
                context,
                currentFile.path,
                operationId(currentFile.id, MOVE_TO_TRASH)
            )
        )
        val physicalResult = if (operation.status == PHYSICAL_COMPLETED) {
            Result.success(operation.targetPath)
        } else {
            PhysicalStorageManager.moveToTrash(context, operation.sourcePath, operation.operationId)
        }
        if (physicalResult.isFailure) {
            operationStore.update(operation.failed("PHYSICAL_MOVE_FAILED"))
            throw physicalResult.exceptionOrNull() ?: java.io.IOException("Failed to move file to trash")
        }
        val targetPath = physicalResult.getOrThrow()
        operationStore.update(operation.physicalCompleted(targetPath))
        val latest = dao.getFileById(currentFile.id) ?: return
        val originalPath = latest.originalPath.ifBlank { operation.sourcePath }
        dao.updateFile(
            latest.copy(
                path = targetPath,
                originalPath = originalPath,
                isRecycleBin = true,
                deletedTimestampMs = System.currentTimeMillis()
            )
        )
        complete(operation, targetPath)
    }

    suspend fun restoreFromRecycleBin(file: FileItemEntity) {
        val currentFile = dao.getFileById(file.id) ?: return
        if (!currentFile.isRecycleBin) return
        val targetPath = currentFile.originalPath.ifBlank { currentFile.path }
        val operation = prepare(RESTORE, currentFile.id, currentFile.path, targetPath)
        val physicalResult = if (operation.status == PHYSICAL_COMPLETED) {
            Result.success(operation.targetPath)
        } else {
            PhysicalStorageManager.restoreFromTrash(context, operation.sourcePath, operation.targetPath)
        }
        if (physicalResult.isFailure) {
            operationStore.update(operation.failed("PHYSICAL_RESTORE_FAILED"))
            throw physicalResult.exceptionOrNull() ?: java.io.IOException("Failed to restore file from trash")
        }
        val restoredPath = physicalResult.getOrThrow()
        operationStore.update(operation.physicalCompleted(restoredPath))
        val latest = dao.getFileById(currentFile.id) ?: return
        dao.updateFile(
            latest.copy(
                path = restoredPath,
                originalPath = "",
                isRecycleBin = false,
                deletedTimestampMs = 0L
            )
        )
        complete(operation, restoredPath)
    }

    suspend fun deletePermanently(file: FileItemEntity) {
        val currentFile = dao.getFileById(file.id) ?: return
        val operation = prepare(DELETE, currentFile.id, currentFile.path, "")
        if (operation.status != PHYSICAL_COMPLETED) {
            val deleted = PhysicalStorageManager.deleteFile(context, operation.sourcePath)
            if (!deleted && File(operation.sourcePath).exists()) {
                operationStore.update(operation.failed("PHYSICAL_DELETE_FAILED"))
                throw java.io.IOException("Failed to physically delete file")
            }
            operationStore.update(operation.physicalCompleted(""))
        }
        dao.deleteFileById(currentFile.id)
        complete(operation, "")
    }

    suspend fun recoverPending() {
        operationStore.getOpenOperations().forEach { operation ->
            when (operation.operationType) {
                MOVE_TO_TRASH -> recoverMoveToTrash(operation)
                RESTORE -> recoverRestore(operation)
                DELETE -> recoverDelete(operation)
            }
        }
    }

    private suspend fun recoverMoveToTrash(operation: FileOperationEntity) {
        val current = dao.getFileById(operation.fileId) ?: return discard(operation)
        if (current.isRecycleBin) return discard(operation)
        val result = when {
            operation.status == PHYSICAL_COMPLETED -> Result.success(operation.targetPath)
            !operation.sourcePath.startsWith("content://") &&
                !File(operation.sourcePath).exists() &&
                File(operation.targetPath).exists() -> Result.success(operation.targetPath)
            else -> PhysicalStorageManager.moveToTrash(context, operation.sourcePath, operation.operationId)
        }
        if (result.isSuccess) {
            val targetPath = result.getOrThrow()
            operationStore.update(operation.physicalCompleted(targetPath))
            dao.updateFile(
                current.copy(
                    path = targetPath,
                    originalPath = operation.sourcePath,
                    isRecycleBin = true,
                    deletedTimestampMs = System.currentTimeMillis()
                )
            )
            discard(operation)
        }
    }

    private suspend fun recoverRestore(operation: FileOperationEntity) {
        val current = dao.getFileById(operation.fileId) ?: return discard(operation)
        if (!current.isRecycleBin) return discard(operation)
        val result = if (operation.status == PHYSICAL_COMPLETED) {
            Result.success(operation.targetPath)
        } else {
            PhysicalStorageManager.restoreFromTrash(context, operation.sourcePath, operation.targetPath)
        }
        if (result.isSuccess) {
            val targetPath = result.getOrThrow()
            operationStore.update(operation.physicalCompleted(targetPath))
            dao.updateFile(
                current.copy(
                    path = targetPath,
                    originalPath = "",
                    isRecycleBin = false,
                    deletedTimestampMs = 0L
                )
            )
            discard(operation)
        }
    }

    private suspend fun recoverDelete(operation: FileOperationEntity) {
        if (
            operation.status == PHYSICAL_COMPLETED ||
            PhysicalStorageManager.deleteFile(context, operation.sourcePath) ||
            !File(operation.sourcePath).exists()
        ) {
            operationStore.update(operation.physicalCompleted(""))
            dao.deleteFileById(operation.fileId)
            discard(operation)
        }
    }

    private suspend fun prepare(
        type: String,
        fileId: Long,
        sourcePath: String,
        targetPath: String
    ): FileOperationEntity {
        operationStore.findOpenOperation(fileId, type)?.let { return it }
        val now = System.currentTimeMillis()
        return FileOperationEntity(
            operationId = operationId(fileId, type),
            operationType = type,
            fileId = fileId,
            sourcePath = sourcePath,
            targetPath = targetPath,
            status = PREPARED,
            createdAtMs = now,
            updatedAtMs = now
        ).also(operationStore::insert)
    }

    private suspend fun complete(operation: FileOperationEntity, targetPath: String) {
        operationStore.update(operation.committed(targetPath))
        discard(operation)
    }

    private suspend fun discard(operation: FileOperationEntity) {
        operationStore.delete(operation.operationId)
    }

    private fun FileOperationEntity.physicalCompleted(targetPath: String): FileOperationEntity = copy(
        status = PHYSICAL_COMPLETED,
        targetPath = targetPath,
        updatedAtMs = System.currentTimeMillis(),
        lastErrorCode = null
    )

    private fun FileOperationEntity.committed(targetPath: String): FileOperationEntity = copy(
        status = COMMITTED,
        targetPath = targetPath,
        updatedAtMs = System.currentTimeMillis(),
        lastErrorCode = null
    )

    private fun FileOperationEntity.failed(errorCode: String): FileOperationEntity = copy(
        status = FAILED,
        updatedAtMs = System.currentTimeMillis(),
        lastErrorCode = errorCode
    )

    private fun operationId(fileId: Long, type: String): String = "file-$type-$fileId"

    private companion object {
        const val PREPARED = FileOperationStatus.PREPARED
        const val PHYSICAL_COMPLETED = FileOperationStatus.PHYSICAL_COMPLETED
        const val COMMITTED = FileOperationStatus.COMMITTED
        const val FAILED = FileOperationStatus.FAILED
        const val MOVE_TO_TRASH = "MOVE_TO_TRASH"
        const val RESTORE = "RESTORE"
        const val DELETE = "DELETE"
    }
}
