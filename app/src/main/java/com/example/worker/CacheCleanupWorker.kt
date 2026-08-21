package com.example.worker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
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

    private suspend fun runWork(): Result {
        Log.i(TAG, "Starting CacheCleanupWorker...")
        return try {
            val cacheDir = applicationContext.cacheDir
            val externalCacheDir = applicationContext.externalCacheDir
            
            var deletedFilesCount = 0
            var deletedSize = 0L

            val dirsToClean = listOfNotNull(cacheDir, externalCacheDir)
            for (dir in dirsToClean) {
                if (dir.exists()) {
                    dir.walkBottomUp().forEach { file ->
                        if (file.isFile) {
                            val size = file.length()
                            if (file.delete()) {
                                deletedFilesCount++
                                deletedSize += size
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "Cache cleanup complete. Deleted $deletedFilesCount files, freeing ${deletedSize / 1024} KB.")
            
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val diagnostic = DomainErrorMapper.fromThrowable("CACHE_CLEANUP", e)
            DiagnosticLogger.log(TAG, diagnostic)
            if (RetryPolicy.shouldRetry(RetryOperation.CACHE_CLEANUP, e, runAttemptCount)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "CacheCleanupWorker"
        private const val TAG = "CacheCleanupWorker"
    }
}
