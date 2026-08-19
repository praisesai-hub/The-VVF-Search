package com.example.domain

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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
    fun enqueueDuplicateCleanupWork() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<DuplicateCleanupWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "DuplicateCleanupWork",
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (error: Exception) {
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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "CloudSyncWork",
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (error: Exception) {
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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "CacheCleanupWork",
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (error: Exception) {
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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "BackgroundIndexWork",
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (error: Exception) {
            Log.e("WorkCoordinator", "Failed to enqueue BackgroundIndexWorker", error)
        }
    }
}
