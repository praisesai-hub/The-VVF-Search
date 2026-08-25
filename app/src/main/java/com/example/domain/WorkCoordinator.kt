package com.example.domain

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import com.example.worker.BackgroundIndexWorker
import com.example.worker.CacheCleanupWorker
import com.example.worker.CloudSyncWorker
import com.example.worker.DuplicateCleanupWorker
import java.util.concurrent.TimeUnit

/**
 * Application orchestration boundary for one-off background work.
 * Repositories expose data operations; this coordinator owns WorkManager policy.
 */
class WorkCoordinator(private val context: Context) {
    companion object {
        const val OPERATION_ID_KEY = "work_operation_id"
    }

    private fun operationData() = workDataOf(OPERATION_ID_KEY to UUID.randomUUID().toString())

    private object BackoffSeconds {
        const val DUPLICATE_CLEANUP = 30L
        const val CLOUD_SYNC = 10L
        const val CACHE_CLEANUP = 30L
        const val BACKGROUND_INDEXING = 15L
    }

    fun enqueueDuplicateCleanupWork() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<DuplicateCleanupWorker>()
                .setConstraints(constraints)
                .setInputData(operationData())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BackoffSeconds.DUPLICATE_CLEANUP, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "DuplicateCleanupWork",
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (error: IllegalStateException) {
            Log.e("WorkCoordinator", "Failed to enqueue DuplicateCleanupWorker", error)
        }
    }

    fun enqueueCloudSyncWork(allowed: Boolean) {
        if (!allowed) {
            Log.i("WorkCoordinator", "Cloud sync enqueue blocked by release policy.")
            return
        }
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(constraints)
                .setInputData(operationData())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BackoffSeconds.CLOUD_SYNC, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "CloudSyncWork",
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (error: IllegalStateException) {
            Log.e("WorkCoordinator", "Failed to enqueue CloudSyncWorker", error)
        }
    }

    fun enqueueCacheCleanupWork() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<CacheCleanupWorker>()
                .setConstraints(constraints)
                .setInputData(operationData())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BackoffSeconds.CACHE_CLEANUP, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "CacheCleanupWork",
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (error: IllegalStateException) {
            Log.e("WorkCoordinator", "Failed to enqueue CacheCleanupWorker", error)
        }
    }

    fun enqueueBackgroundIndexWork() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<BackgroundIndexWorker>()
                .setConstraints(constraints)
                .setInputData(operationData())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BackoffSeconds.BACKGROUND_INDEXING, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "BackgroundIndexWork",
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (error: IllegalStateException) {
            Log.e("WorkCoordinator", "Failed to enqueue BackgroundIndexWorker", error)
        }
    }
}
