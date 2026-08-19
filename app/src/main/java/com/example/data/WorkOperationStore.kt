package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Entity(tableName = "work_operations")
data class WorkOperationEntity(
    @PrimaryKey val operationId: String,
    val workName: String,
    val status: String,
    val leaseOwner: String?,
    val leaseExpiresAtMs: Long,
    val attemptCount: Int,
    val startedAtMs: Long,
    val heartbeatAtMs: Long,
    val completedAtMs: Long,
    val lastErrorCode: String?
)

@Dao
interface WorkOperationStore {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(operation: WorkOperationEntity): Long

    @Query(
        """
        UPDATE work_operations
        SET status = 'QUEUED', leaseOwner = NULL, leaseExpiresAtMs = 0, heartbeatAtMs = 0
        WHERE leaseExpiresAtMs > 0 AND leaseExpiresAtMs <= :nowMs AND status = 'RUNNING'
        """
    )
    suspend fun releaseExpired(nowMs: Long): Int

    @Query(
        """
        UPDATE work_operations
        SET status = 'RUNNING', leaseOwner = :leaseOwner, leaseExpiresAtMs = :leaseExpiresAtMs,
            heartbeatAtMs = :nowMs, startedAtMs = CASE WHEN startedAtMs = 0 THEN :nowMs ELSE startedAtMs END,
            attemptCount = attemptCount + 1
        WHERE operationId = :operationId AND status IN ('QUEUED', 'RUNNING')
          AND (leaseOwner IS NULL OR leaseExpiresAtMs = 0 OR leaseExpiresAtMs <= :nowMs)
        """
    )
    suspend fun claimExisting(
        operationId: String,
        leaseOwner: String,
        nowMs: Long,
        leaseExpiresAtMs: Long
    ): Int

    @Query(
        """
        UPDATE work_operations
        SET heartbeatAtMs = :nowMs, leaseExpiresAtMs = :leaseExpiresAtMs
        WHERE operationId = :operationId AND status = 'RUNNING' AND leaseOwner = :leaseOwner
        """
    )
    suspend fun heartbeat(operationId: String, leaseOwner: String, nowMs: Long, leaseExpiresAtMs: Long): Int

    @Query(
        """
        UPDATE work_operations
        SET status = 'COMPLETED', completedAtMs = :nowMs, heartbeatAtMs = :nowMs,
            leaseOwner = NULL, leaseExpiresAtMs = 0, lastErrorCode = NULL
        WHERE operationId = :operationId AND status = 'RUNNING' AND leaseOwner = :leaseOwner
        """
    )
    suspend fun markCompleted(operationId: String, leaseOwner: String, nowMs: Long): Int

    @Query(
        """
        UPDATE work_operations
        SET status = :status, completedAtMs = CASE WHEN :status = 'FAILED' THEN :nowMs ELSE 0 END,
            heartbeatAtMs = :nowMs, leaseOwner = NULL, leaseExpiresAtMs = 0, lastErrorCode = :errorCode
        WHERE operationId = :operationId AND status = 'RUNNING' AND leaseOwner = :leaseOwner
        """
    )
    suspend fun markFinished(
        operationId: String,
        leaseOwner: String,
        status: String,
        errorCode: String?,
        nowMs: Long
    ): Int

    @androidx.room.Transaction
    suspend fun claim(
        operationId: String,
        workName: String,
        leaseOwner: String,
        nowMs: Long,
        leaseExpiresAtMs: Long
    ): Int {
        insertIfAbsent(
            WorkOperationEntity(
                operationId = operationId,
                workName = workName,
                status = "QUEUED",
                leaseOwner = null,
                leaseExpiresAtMs = 0L,
                attemptCount = 0,
                startedAtMs = 0L,
                heartbeatAtMs = 0L,
                completedAtMs = 0L,
                lastErrorCode = null
            )
        )
        return claimExisting(operationId, leaseOwner, nowMs, leaseExpiresAtMs)
    }
}

class WorkOperationLease(
    private val store: WorkOperationStore,
    private val operationId: String,
    private val workName: String,
    private val leaseOwner: String
) {
    suspend fun claim(nowMs: Long = System.currentTimeMillis()): Boolean =
        store.claim(operationId, workName, leaseOwner, nowMs, nowMs + LEASE_DURATION_MS) > 0

    fun startHeartbeat(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            val nowMs = System.currentTimeMillis()
            store.heartbeat(operationId, leaseOwner, nowMs, nowMs + LEASE_DURATION_MS)
        }
    }

    suspend fun complete(nowMs: Long = System.currentTimeMillis()) {
        store.markCompleted(operationId, leaseOwner, nowMs)
    }

    suspend fun finishForRetry(errorCode: String, nowMs: Long = System.currentTimeMillis()) {
        store.markFinished(operationId, leaseOwner, "QUEUED", errorCode, nowMs)
    }

    suspend fun fail(errorCode: String, nowMs: Long = System.currentTimeMillis()) {
        store.markFinished(operationId, leaseOwner, "FAILED", errorCode, nowMs)
    }

    companion object {
        const val LEASE_DURATION_MS = 120_000L
        const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
