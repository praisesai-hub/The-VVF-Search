package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.storage.StorageScanner

class BackgroundIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "BackgroundIndexWorker"
        const val WORK_NAME = "VVF_BACKGROUND_STORAGE_INDEX"
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        Log.i(TAG, "Starting background file storage indexing...")
        return try {
            val scanner = StorageScanner(applicationContext)
            val db = AppDatabase.getDatabase(applicationContext)
            val discoveredPaths = mutableSetOf<String>()
            var totalDiscovered = 0
            scanner.scanDeviceStorageFlow().collect { batch ->
                if (batch.isNotEmpty()) {
                    db.fileDao().upsertFilesPreservingMetadata(batch)
                    totalDiscovered += batch.size
                    batch.forEach { item -> discoveredPaths.add(item.path) }
                }
            }

            if (!isStopped) {
                db.fileDao().reconcileStaleRecords(discoveredPaths)
                if (totalDiscovered > 0) {
                    Log.i(TAG, "Successfully indexed and synced $totalDiscovered real storage files into database.")
                } else {
                    Log.w(TAG, "Background scan finished with 0 files discovered.")
                }
            } else {
                Log.w(TAG, "Worker was stopped before stale record reconciliation could run.")
            }

            androidx.work.ListenableWorker.Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background storage indexing failed: ${e.message}", e)
            if (runAttemptCount >= 3) {
                Log.e(TAG, "Background storage indexing failed after $runAttemptCount attempts. Abandoning retry.")
                androidx.work.ListenableWorker.Result.failure()
            } else {
                androidx.work.ListenableWorker.Result.retry()
            }
        }
    }
}
