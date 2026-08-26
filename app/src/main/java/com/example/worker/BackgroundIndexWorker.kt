package com.example.worker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.storage.StorageScanner
import com.example.domain.error.DiagnosticLogger
import com.example.domain.error.DomainErrorMapper
import com.example.domain.retry.RetryOperation
import com.example.domain.retry.RetryPolicy
import com.example.domain.WorkCoordinator

class BackgroundIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "BackgroundIndexWorker"
        const val WORK_NAME = "VVF_BACKGROUND_STORAGE_INDEX"
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result = kotlinx.coroutines.coroutineScope {
        executeWithDurableLease(
        context = applicationContext,
        worker = this@BackgroundIndexWorker,
        scope = this,
        workName = WORK_NAME,
        operationId = inputData.getString(WorkCoordinator.OPERATION_ID_KEY) ?: "worker:${id}",
        block = { runWork() }
        )
    }

    private suspend fun runWork(): androidx.work.ListenableWorker.Result {
        Log.i(TAG, "Starting background file storage indexing...")
        return try {
            val scanner = StorageScanner(applicationContext)
            val db = AppDatabase.getDatabase(applicationContext)
            var totalDiscovered = 0
            scanner.scanDeviceStorageFlow().collect { batch ->
                if (batch.isNotEmpty()) {
                    db.fileDao().upsertFilesPreservingMetadata(batch)
                    totalDiscovered += batch.size
                }
            }

            if (!isStopped) {
                // This worker does not have an authoritative view of every indexed
                // source, notably persisted SAF trees and partial MediaStore access.
                // Never treat this partial view as proof that absent DB records are stale.
                if (totalDiscovered > 0) {
                    Log.i(TAG, "Successfully indexed $totalDiscovered discovered storage files into database.")
                } else {
                    Log.w(TAG, "Background scan completed with 0 files discovered; existing index was preserved.")
                }
            } else {
                Log.w(TAG, "Worker was stopped before indexing completed.")
            }

            androidx.work.ListenableWorker.Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val diagnostic = DomainErrorMapper.fromThrowable("BACKGROUND_INDEXING", e)
            DiagnosticLogger.log(TAG, diagnostic)
            if (RetryPolicy.shouldRetry(RetryOperation.INDEXING, e, runAttemptCount)) {
                androidx.work.ListenableWorker.Result.retry()
            } else {
                androidx.work.ListenableWorker.Result.failure()
            }
        }
    }
}
