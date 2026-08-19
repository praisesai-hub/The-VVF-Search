package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.FileDao
import com.example.data.VaultOperationCoordinator

/** Reconciles durable vault transactions left incomplete by process death or device restart. */
class VaultRecoveryWorker @JvmOverloads constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val daoOverride: FileDao? = null,
    private val coordinatorOverride: VaultOperationCoordinator? = null
) : CoroutineWorker(appContext, workerParams) {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result = try {
        val dao = daoOverride ?: AppDatabase.getDatabase(applicationContext).fileDao()
        val coordinator = coordinatorOverride ?: VaultOperationCoordinator(applicationContext, dao)
        coordinator.recoverIncompleteOperations()
        Result.success()
    } catch (error: Exception) {
        Log.e(TAG, "Vault operation recovery failed", error)
        if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        const val WORK_NAME = "VVF_VAULT_OPERATION_RECOVERY"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val TAG = "VaultRecoveryWorker"
    }
}
