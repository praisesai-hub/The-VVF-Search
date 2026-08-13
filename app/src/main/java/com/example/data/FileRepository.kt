package com.example.data

import android.content.Context
import com.example.storage.PhysicalStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class FileRepository(
    private val context: Context,
    private val dao: FileDao
) {
    fun getAllActiveFiles(): Flow<List<FileItemEntity>> = dao.getAllActiveFiles()

    suspend fun getFileById(id: Long): FileItemEntity? = dao.getFileById(id)

    suspend fun getFilteredFilesPaged(category: String?, query: String, limit: Int, offset: Int): List<FileItemEntity> =
        dao.getFilteredFilesPaged(category, query, limit, offset)

    suspend fun renameFile(file: FileItemEntity, newName: String): FileItemEntity = withContext(Dispatchers.IO) {
        val renameResult = PhysicalStorageManager.renameFile(context, file.path, newName)
        if (renameResult.isFailure) {
            throw renameResult.exceptionOrNull() ?: java.io.IOException("Failed to physically rename file")
        }
        val newPath = renameResult.getOrThrow()
        val updatedFile = file.copy(name = newName, path = newPath)
        dao.updateFile(updatedFile)
        updatedFile
    }

    suspend fun addTagToFile(file: FileItemEntity, tag: String): FileItemEntity = withContext(Dispatchers.IO) {
        val currentTags = if (file.tags.isBlank()) tag else "${file.tags}, $tag"
        val updatedFile = file.copy(tags = currentTags)
        dao.updateFile(updatedFile)
        updatedFile
    }
}
