package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import com.example.domain.error.DiagnosticLogger
import com.example.domain.error.DomainErrorMapper
import com.example.domain.retry.RetryOperation
import com.example.domain.retry.RetryPolicy
import com.example.domain.WorkCoordinator

class CacheCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = executeWithDurableLease(
        context = applicationContext,
        worker = this,
        workName = WORK_NAME,
        operationId = inputData.getString(WorkCoordinator.OPERATION_ID_KEY) ?: "worker:${id}",
        block = { runWork() }
    )

    private suspend fun runWork(): DurableWorkResult {
        Log.i(TAG, "Starting CacheCleanupWorker...")
        return try {
            val cacheDir = applicationContext.cacheDir
            val externalCacheDir = applicationContext.externalCacheDir
            
            val dirsToClean = listOfNotNull(cacheDir, externalCacheDir)
            val cleanup = dirsToClean.fold(CacheCleanupTotals()) { totals, dir ->
                totals + cleanDirectory(dir)
            }

            Log.i(TAG, "Cache cleanup complete. Deleted ${cleanup.files} files, freeing ${cleanup.bytes / 1024} KB.")
            
            DurableWorkResult.success()
        } catch (e: Exception) {
            val diagnostic = DomainErrorMapper.fromThrowable("CACHE_CLEANUP", e)
            DiagnosticLogger.log(TAG, diagnostic)
            if (RetryPolicy.shouldRetry(RetryOperation.CACHE_CLEANUP, e, runAttemptCount)) {
                DurableWorkResult.retry()
            } else {
                DurableWorkResult.failure()
            }
        }
    }

    private fun cleanDirectory(directory: File): CacheCleanupTotals {
        if (!directory.exists()) return CacheCleanupTotals()
        return directory.walkBottomUp()
            .filter(File::isFile)
            .fold(CacheCleanupTotals()) { totals, file ->
                if (!file.delete()) totals else totals + CacheCleanupTotals(files = 1, bytes = file.length())
            }
    }

    private data class CacheCleanupTotals(val files: Int = 0, val bytes: Long = 0L) {
        operator fun plus(other: CacheCleanupTotals) = CacheCleanupTotals(
            files = files + other.files,
            bytes = bytes + other.bytes
        )
    }

    companion object {
        const val WORK_NAME = "CacheCleanupWorker"
        private const val TAG = "CacheCleanupWorker"
    }
}
