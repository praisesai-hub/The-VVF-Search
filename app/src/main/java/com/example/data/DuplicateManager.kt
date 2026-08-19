package com.example.data

import android.content.Context
import android.util.Log
import com.example.storage.PhysicalStorageManager
import kotlinx.coroutines.flow.first

class DuplicateManager(
    private val dao: FileDao,
    private val context: Context? = null
) {
    /**
     * Cleans selected duplicate file IDs by moving them to the recycle bin.
     * Ensures idempotency by verifying if a file with the same unique content hash
     * is already in the recycle bin. Performs atomic database batch updates via transaction.
     */
    suspend fun cleanSelectedDuplicates(selectedIds: Set<Long>) {
        val exactDuplicateIds = dao.getDuplicateFilesByHash().first().map { it.id }.toSet()
        val filesToMove = mutableListOf<FileItemEntity>()
        for (id in selectedIds) {
            // Only cryptographically exact duplicate rows are eligible for destructive cleanup.
            // Visual, semantic, and structural candidates are intentionally ignored here even
            // if a caller bypasses the review-only UI.
            if (id !in exactDuplicateIds) continue
            val file = dao.getFileById(id) ?: continue
            
            if (file.isRecycleBin) continue
            
            // Content-hash idempotency check
            if (file.md5Hash.isNotBlank()) {
                val existingInRecycleBin = dao.findInRecycleBinByHash(file.md5Hash)
                if (existingInRecycleBin != null) {
                    continue
                }
            }

            // Real physical move to recycle bin
            var finalPath = file.path
            if (context != null) {
                try {
                    val trashResult = PhysicalStorageManager.moveToTrash(context, file.path)
                    if (trashResult.isSuccess) {
                        finalPath = trashResult.getOrThrow()
                    } else {
                        Log.e("DuplicateManager", "Physical move to trash failed for file: ${file.path}")
                        continue
                    }
                } catch (e: Exception) {
                    Log.e("DuplicateManager", "Exception moving file to trash: ${file.path}", e)
                    continue
                }
            }

            val originalPathToKeep = if (file.originalPath.isNotBlank()) file.originalPath else file.path
            filesToMove.add(
                file.copy(
                    path = finalPath,
                    originalPath = originalPathToKeep,
                    isRecycleBin = true,
                    deletedTimestampMs = System.currentTimeMillis()
                )
            )
        }

        if (filesToMove.isNotEmpty()) {
            dao.moveFilesToRecycleBinAtomic(filesToMove)
        }
    }
}
