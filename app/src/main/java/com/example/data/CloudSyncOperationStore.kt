package com.example.data

import androidx.room.Dao
import androidx.room.Query

/**
 * Atomic state transitions for crash-safe cloud operations. Every transition is
 * guarded by operation ID and lease owner so an old worker cannot overwrite a
 * newer claim after a lease expires.
 */
@Dao
interface CloudSyncOperationStore {
    @Query(
        """
        UPDATE cloud_sync
        SET status = 'QUEUED', leaseOwner = NULL, leaseExpiresAtMs = 0, heartbeatAtMs = 0
        WHERE status = 'UPLOADING' AND (leaseExpiresAtMs = 0 OR leaseExpiresAtMs <= :nowMs)
        """
    )
    suspend fun releaseExpiredLeases(nowMs: Long): Int

    @Query(
        """
        UPDATE cloud_sync
        SET status = 'UPLOADING',
            leaseOwner = :leaseOwner,
            leaseExpiresAtMs = :leaseExpiresAtMs,
            heartbeatAtMs = :nowMs,
            startedAtMs = CASE WHEN startedAtMs = 0 THEN :nowMs ELSE startedAtMs END,
            attemptCount = attemptCount + 1
        WHERE operationId = :operationId
          AND status IN ('PENDING', 'QUEUED')
          AND (leaseOwner IS NULL OR leaseExpiresAtMs = 0 OR leaseExpiresAtMs <= :nowMs)
        """
    )
    suspend fun claim(
        operationId: String,
        leaseOwner: String,
        nowMs: Long,
        leaseExpiresAtMs: Long
    ): Int

    @Query(
        """
        UPDATE cloud_sync
        SET heartbeatAtMs = :nowMs, leaseExpiresAtMs = :leaseExpiresAtMs
        WHERE operationId = :operationId AND status = 'UPLOADING' AND leaseOwner = :leaseOwner
        """
    )
    suspend fun heartbeat(
        operationId: String,
        leaseOwner: String,
        nowMs: Long,
        leaseExpiresAtMs: Long
    ): Int

    @Query(
        """
        UPDATE cloud_sync
        SET status = 'SYNCED',
            lastSyncedMs = :nowMs,
            heartbeatAtMs = :nowMs,
            completedAtMs = :nowMs,
            leaseOwner = NULL,
            leaseExpiresAtMs = 0,
            lastErrorCode = NULL
        WHERE operationId = :operationId AND status = 'UPLOADING' AND leaseOwner = :leaseOwner
        """
    )
    suspend fun markCompleted(operationId: String, leaseOwner: String, nowMs: Long): Int

    @Query(
        """
        UPDATE cloud_sync
        SET remoteFileId = COALESCE(NULLIF(:remoteFileId, ''), remoteFileId),
            resumableSessionUri = COALESCE(NULLIF(:resumableSessionUri, ''), resumableSessionUri),
            resumableBytesCommitted = CASE WHEN :bytesCommitted >= 0 THEN :bytesCommitted ELSE resumableBytesCommitted END
        WHERE operationId = :operationId AND status = 'UPLOADING' AND leaseOwner = :leaseOwner
        """
    )
    suspend fun updateTransferState(
        operationId: String,
        leaseOwner: String,
        remoteFileId: String,
        resumableSessionUri: String,
        bytesCommitted: Long
    ): Int

    @Query(
        """
        UPDATE cloud_sync
        SET status = :status,
            heartbeatAtMs = :nowMs,
            completedAtMs = CASE WHEN :status = 'FAILED' THEN :nowMs ELSE 0 END,
            leaseOwner = NULL,
            leaseExpiresAtMs = 0,
            lastErrorCode = :errorCode
        WHERE operationId = :operationId AND status = 'UPLOADING' AND leaseOwner = :leaseOwner
        """
    )
    suspend fun markFailed(
        operationId: String,
        leaseOwner: String,
        status: String,
        errorCode: String?,
        nowMs: Long
    ): Int
}
