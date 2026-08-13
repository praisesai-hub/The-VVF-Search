package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class CacheCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error in CacheCleanupWorker: ${e.message}", e)
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "CacheCleanupWorker"
        private const val TAG = "CacheCleanupWorker"
    }
}
