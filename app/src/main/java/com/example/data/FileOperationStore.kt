package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

object FileOperationStatus {
    const val PREPARED = "PREPARED"
    const val PHYSICAL_COMPLETED = "PHYSICAL_COMPLETED"
    const val COMMITTED = "COMMITTED"
    const val FAILED = "FAILED"
}

@Entity(
    tableName = "file_operations",
    indices = []
)
data class FileOperationEntity(
    @PrimaryKey val operationId: String,
    val operationType: String,
    val fileId: Long,
    val sourcePath: String,
    val targetPath: String,
    val status: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastErrorCode: String? = null
)

@Dao
interface FileOperationStore {
    @Query("SELECT * FROM file_operations WHERE status IN ('PREPARED', 'PHYSICAL_COMPLETED') ORDER BY createdAtMs ASC")
    suspend fun getOpenOperations(): List<FileOperationEntity>

    @Query("SELECT * FROM file_operations WHERE fileId = :fileId AND operationType = :operationType AND status IN ('PREPARED', 'PHYSICAL_COMPLETED') ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun findOpenOperation(fileId: Long, operationType: String): FileOperationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: FileOperationEntity)

    @Query("UPDATE file_operations SET status = :status, sourcePath = :sourcePath, targetPath = :targetPath, updatedAtMs = :nowMs, lastErrorCode = :errorCode WHERE operationId = :operationId")
    suspend fun transition(
        operationId: String,
        status: String,
        sourcePath: String,
        targetPath: String,
        nowMs: Long,
        errorCode: String?
    ): Int

    @Query("DELETE FROM file_operations WHERE operationId = :operationId")
    suspend fun delete(operationId: String): Int
}
