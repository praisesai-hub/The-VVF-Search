package com.example.domain

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OneTimeWorkRequest
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

    private fun enqueueUniqueWork(workName: String, request: OneTimeWorkRequest) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
        } catch (error: IllegalStateException) {
            Log.e("WorkCoordinator", "WorkManager is unavailable for $workName", error)
        } catch (error: IllegalArgumentException) {
            Log.e("WorkCoordinator", "Work request is invalid for $workName", error)
        } catch (error: SecurityException) {
            Log.e("WorkCoordinator", "Work scheduling is not permitted for $workName", error)
        }
    }

    fun enqueueDuplicateCleanupWork() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<DuplicateCleanupWorker>()
            .setConstraints(constraints)
            .setInputData(operationData())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BackoffSeconds.DUPLICATE_CLEANUP, TimeUnit.SECONDS)
            .build()
        enqueueUniqueWork("DuplicateCleanupWork", request)
    }

    fun enqueueCloudSyncWork(allowed: Boolean) {
        if (!allowed) {
            Log.i("WorkCoordinator", "Cloud sync enqueue blocked by release policy.")
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(constraints)
            .setInputData(operationData())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BackoffSeconds.CLOUD_SYNC, TimeUnit.SECONDS)
            .build()
        enqueueUniqueWork("CloudSyncWork", request)
    }

    fun enqueueCacheCleanupWork() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<CacheCleanupWorker>()
            .setConstraints(constraints)
            .setInputData(operationData())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BackoffSeconds.CACHE_CLEANUP, TimeUnit.SECONDS)
            .build()
        enqueueUniqueWork("CacheCleanupWork", request)
    }

    fun enqueueBackgroundIndexWork() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<BackgroundIndexWorker>()
            .setConstraints(constraints)
            .setInputData(operationData())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BackoffSeconds.BACKGROUND_INDEXING, TimeUnit.SECONDS)
            .build()
        enqueueUniqueWork("BackgroundIndexWork", request)
    }
}
