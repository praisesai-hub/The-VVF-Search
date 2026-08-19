package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.context.drive.DriveAuthorizationFactory
import com.example.context.drive.DriveAuthorizationPort
import com.example.data.AppDatabase
import com.example.data.CloudProviderAdapter
import com.example.data.CloudSyncItemEntity
import com.example.data.CloudSyncOperationStore
import com.example.data.CloudSyncPolicy
import com.example.data.CloudSyncResult
import com.example.data.FileDao
import com.example.domain.error.DiagnosticLogger
import com.example.domain.error.DomainErrorMapper
import com.example.domain.retry.RetryDecision
import com.example.domain.retry.RetryOperation
import com.example.domain.retry.RetryPolicy
import com.example.domain.WorkCoordinator
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class CloudSyncWorker @JvmOverloads constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val daoOverride: FileDao? = null,
    private val operationStoreOverride: CloudSyncOperationStore? = null,
    private val providerAdapterOverride: CloudProviderAdapter? = null,
    private val authManagerOverride: DriveAuthorizationPort? = null,
    private val transferAllowed: (() -> Boolean)? = null
) : CoroutineWorker(appContext, workerParams) {

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    override suspend fun doWork(): Result = executeWithDurableLease(
        context = applicationContext,
        worker = this,
        workName = WORK_NAME,
        operationId = inputData.getString(WorkCoordinator.OPERATION_ID_KEY) ?: "worker:${id}",
        block = { runWork() }
    )

    private suspend fun runWork(): Result = coroutineScope {
        if (!(transferAllowed?.invoke() ?: CloudSyncPolicy.canTransfer(applicationContext))) {
            Log.i(TAG, "Cloud transfer blocked: explicit opt-in or build provisioning is missing.")
            return@coroutineScope Result.success()
        }

        val leaseOwner = id.toString()
        val leaseStore = operationStoreOverride
            ?: AppDatabase.getDatabase(applicationContext).cloudSyncOperationStore()
        return@coroutineScope try {
            Log.i(TAG, "Starting background CloudSyncWorker with provider adapter contract...")
            val dao = daoOverride ?: AppDatabase.getDatabase(applicationContext).fileDao()
            val nowMs = System.currentTimeMillis()
            leaseStore.releaseExpiredLeases(nowMs)

            val syncItems = dao.getCloudSyncItems().first()
            val enabledProviders = dao.getAllPlugins().first()
                .filter { it.isEnabled }
                .mapNotNull { plugin ->
                    when (plugin.pluginId) {
                        "gdrive_sync" -> "GOOGLE_DRIVE"
                        "onedrive_sync" -> "ONEDRIVE"
                        "dropbox_sync" -> "DROPBOX"
                        else -> null
                    }
                }.toSet()

            val pendingOrQueued = syncItems
                .filter { it.status == "PENDING" || it.status == "QUEUED" || it.status == "UPLOADING" }
                .filter { it.provider in enabledProviders }

            if (pendingOrQueued.isEmpty()) {
                Log.i(TAG, "No pending cloud sync items for enabled plugins.")
                return@coroutineScope Result.success()
            }

            val driveAuthorization = authManagerOverride
                ?: DriveAuthorizationFactory.getInstance(applicationContext)
            val syncEngine = com.example.data.CloudSyncEngine(
                context = applicationContext,
                dao = dao,
                authManager = driveAuthorization,
                providerAdapterOverride = providerAdapterOverride
            )

            var syncedCount = 0
            var failedCount = 0
            var retryableFailedCount = 0

            for (item in pendingOrQueued) {
                val operationId = item.operationId.ifBlank { "legacy-${item.id}" }
                val claimTimeMs = System.currentTimeMillis()
                val claimed = leaseStore.claim(
                    operationId = operationId,
                    leaseOwner = leaseOwner,
                    nowMs = claimTimeMs,
                    leaseExpiresAtMs = claimTimeMs + LEASE_DURATION_MS
                )
                if (claimed == 0) continue

                val heartbeatJob: Job = launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        val heartbeatMs = System.currentTimeMillis()
                        leaseStore.heartbeat(
                            operationId = operationId,
                            leaseOwner = leaseOwner,
                            nowMs = heartbeatMs,
                            leaseExpiresAtMs = heartbeatMs + LEASE_DURATION_MS
                        )
                    }
                }

                try {
                    val claimedItem = item.copy(
                        operationId = operationId,
                        status = "UPLOADING",
                        leaseOwner = leaseOwner,
                        heartbeatAtMs = claimTimeMs
                    )
                    when (val syncResult = syncEngine.syncItem(claimedItem)) {
                        is CloudSyncResult.Success -> {
                            if (leaseStore.markCompleted(operationId, leaseOwner, System.currentTimeMillis()) > 0) {
                                syncedCount++
                            } else {
                                Log.w(TAG, "Completion ignored because the cloud operation lease was lost.")
                            }
                        }
                        is CloudSyncResult.Error -> {
                            val canRetry = syncResult.isRetryable &&
                                runAttemptCount + 1 < RetryDecision.DEFAULT_MAX_ATTEMPTS
                            val errorCode = syncResult.domainError?.diagnostics?.reasonCode
                                ?: if (syncResult.isRetryable) "RETRYABLE_TRANSFER_FAILURE" else "TRANSFER_FAILURE"
                            val nextStatus = if (canRetry) "QUEUED" else "FAILED"
                            leaseStore.markFailed(
                                operationId = operationId,
                                leaseOwner = leaseOwner,
                                status = nextStatus,
                                errorCode = errorCode,
                                nowMs = System.currentTimeMillis()
                            )
                            failedCount++
                            if (canRetry) retryableFailedCount++
                        }
                        is CloudSyncResult.NotSupported -> {
                            leaseStore.markFailed(
                                operationId = operationId,
                                leaseOwner = leaseOwner,
                                status = "FAILED",
                                errorCode = "PROVIDER_NOT_SUPPORTED",
                                nowMs = System.currentTimeMillis()
                            )
                            failedCount++
                        }
                    }
                } finally {
                    heartbeatJob.cancel()
                }
            }

            Log.i(TAG, "CloudSyncWorker finished. Synced: $syncedCount, Failed: $failedCount (Retryable: $retryableFailedCount)")
            if (retryableFailedCount > 0) {
                if (runAttemptCount + 1 < RetryDecision.DEFAULT_MAX_ATTEMPTS) Result.retry() else Result.failure()
            } else if (failedCount > 0) {
                Result.failure()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            val diagnostic = DomainErrorMapper.fromThrowable("CLOUD_SYNC_WORKER", e)
            DiagnosticLogger.log(TAG, diagnostic)
            if (RetryPolicy.shouldRetry(RetryOperation.CLOUD_TRANSFER, e, runAttemptCount)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "VVF_CLOUD_SYNC_WORK"
        const val LEASE_DURATION_MS = 120_000L
        const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val TAG = "CloudSyncWorker"
    }
}
