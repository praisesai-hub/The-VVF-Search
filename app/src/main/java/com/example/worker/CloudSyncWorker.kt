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
import com.example.data.CloudTransferProgress
import com.example.data.FileDao
import com.example.domain.error.DiagnosticLogger
import com.example.domain.error.DomainErrorMapper
import com.example.domain.retry.RetryDecision
import com.example.domain.retry.RetryOperation
import com.example.domain.retry.RetryPolicy
import com.example.domain.WorkCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

internal data class CloudSyncWorkerDependencies(
    val dao: FileDao? = null,
    val operationStore: CloudSyncOperationStore? = null,
    val providerAdapter: CloudProviderAdapter? = null,
    val driveAuthorization: DriveAuthorizationPort? = null,
    val transferAllowed: (() -> Boolean)? = null
)

class CloudSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    private var dependencies = CloudSyncWorkerDependencies()

    internal constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        dependencies: CloudSyncWorkerDependencies
    ) : this(appContext, workerParams) {
        this.dependencies = dependencies
    }

    override suspend fun doWork(): Result = executeWithDurableLease(
        context = applicationContext,
        worker = this,
        workName = WORK_NAME,
        operationId = inputData.getString(WorkCoordinator.OPERATION_ID_KEY) ?: "worker:${id}",
        block = { runWork() }
    )

    private suspend fun runWork(): DurableWorkResult = coroutineScope {
        if (!(dependencies.transferAllowed?.invoke() ?: CloudSyncPolicy.canTransfer(applicationContext))) {
            Log.i(TAG, "Cloud transfer blocked: explicit opt-in or build provisioning is missing.")
            return@coroutineScope DurableWorkResult.success()
        }

        val leaseOwner = id.toString()
        val leaseStore = dependencies.operationStore
            ?: AppDatabase.getDatabase(applicationContext).cloudSyncOperationStore()
        return@coroutineScope try {
            Log.i(TAG, "Starting background CloudSyncWorker with provider adapter contract...")
            val dao = dependencies.dao ?: AppDatabase.getDatabase(applicationContext).fileDao()
            val nowMs = System.currentTimeMillis()
            leaseStore.releaseExpiredLeases(nowMs)

            val pendingOrQueued = pendingSyncItems(dao)

            if (pendingOrQueued.isEmpty()) {
                Log.i(TAG, "No pending cloud sync items for enabled plugins.")
                return@coroutineScope DurableWorkResult.success()
            }

            val driveAuthorization = dependencies.driveAuthorization
                ?: DriveAuthorizationFactory.getInstance(applicationContext)
            val syncEngine = com.example.data.CloudSyncEngine(
                context = applicationContext,
                dao = dao,
                authManager = driveAuthorization,
                providerAdapterOverride = dependencies.providerAdapter,
                operationStore = leaseStore
            )

            val summary = processSyncItems(
                items = pendingOrQueued,
                leaseStore = leaseStore,
                syncEngine = syncEngine,
                leaseOwner = leaseOwner
            )
            Log.i(TAG, summary.logMessage())
            summary.toWorkerResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val diagnostic = DomainErrorMapper.fromThrowable("CLOUD_SYNC_WORKER", e)
            DiagnosticLogger.log(TAG, diagnostic)
            if (RetryPolicy.shouldRetry(RetryOperation.CLOUD_TRANSFER, e, runAttemptCount)) {
                DurableWorkResult.retry()
            } else {
                DurableWorkResult.failure()
            }
        }
    }

    private suspend fun pendingSyncItems(dao: FileDao): List<CloudSyncItemEntity> {
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
        return dao.getCloudSyncItems().first()
            .filter { it.status == "PENDING" || it.status == "QUEUED" || it.status == "UPLOADING" }
            .filter { it.provider in enabledProviders }
    }

    private suspend fun processSyncItems(
        items: List<CloudSyncItemEntity>,
        leaseStore: CloudSyncOperationStore,
        syncEngine: com.example.data.CloudSyncEngine,
        leaseOwner: String
    ): CloudSyncRunSummary = coroutineScope {
        var summary = CloudSyncRunSummary()
        for (item in items) {
            summary = summary.record(processSyncItem(item, leaseStore, syncEngine, leaseOwner))
        }
        summary
    }

    private suspend fun CoroutineScope.processSyncItem(
        item: CloudSyncItemEntity,
        leaseStore: CloudSyncOperationStore,
        syncEngine: com.example.data.CloudSyncEngine,
        leaseOwner: String
    ): CloudSyncItemOutcome {
        val operationId = item.operationId.ifBlank { "legacy-${item.id}" }
        val claimTimeMs = System.currentTimeMillis()
        val claimed = leaseStore.claim(
            operationId = operationId,
            leaseOwner = leaseOwner,
            nowMs = claimTimeMs,
            leaseExpiresAtMs = claimTimeMs + LEASE_DURATION_MS
        )
        if (claimed == 0) return CloudSyncItemOutcome.SKIPPED

        val heartbeatJob = startHeartbeat(operationId, leaseStore, leaseOwner)
        return try {
            val claimedItem = item.copy(
                operationId = operationId,
                status = "UPLOADING",
                leaseOwner = leaseOwner,
                heartbeatAtMs = claimTimeMs
            )
            resolveSyncResult(operationId, syncEngine.syncItem(claimedItem), leaseStore, leaseOwner)
        } finally {
            heartbeatJob.cancel()
        }
    }

    private fun CoroutineScope.startHeartbeat(
        operationId: String,
        leaseStore: CloudSyncOperationStore,
        leaseOwner: String
    ): Job = launch {
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

    private suspend fun resolveSyncResult(
        operationId: String,
        result: CloudSyncResult,
        leaseStore: CloudSyncOperationStore,
        leaseOwner: String
    ): CloudSyncItemOutcome = when (result) {
        is CloudSyncResult.Success -> {
            persistTransferState(
                operationId,
                CloudTransferProgress(
                    remoteFileId = result.remoteFileId,
                    resumableSessionUri = result.resumableSessionUri,
                    bytesCommitted = result.bytesCommitted
                ),
                leaseStore,
                leaseOwner
            )
            if (leaseStore.markCompleted(operationId, leaseOwner, System.currentTimeMillis()) > 0) {
                CloudSyncItemOutcome.SYNCED
            } else {
                Log.w(TAG, "Completion ignored because the cloud operation lease was lost.")
                CloudSyncItemOutcome.SKIPPED
            }
        }
        is CloudSyncResult.Error -> {
            persistTransferState(
                operationId,
                CloudTransferProgress(
                    remoteFileId = result.remoteFileId,
                    resumableSessionUri = result.resumableSessionUri,
                    bytesCommitted = result.bytesCommitted
                ),
                leaseStore,
                leaseOwner
            )
            val canRetry = result.isRetryable && runAttemptCount + 1 < RetryDecision.DEFAULT_MAX_ATTEMPTS
            leaseStore.markFailed(
                operationId = operationId,
                leaseOwner = leaseOwner,
                status = if (canRetry) "QUEUED" else "FAILED",
                errorCode = result.domainError?.diagnostics?.reasonCode
                    ?: if (result.isRetryable) "RETRYABLE_TRANSFER_FAILURE" else "TRANSFER_FAILURE",
                nowMs = System.currentTimeMillis()
            )
            if (canRetry) CloudSyncItemOutcome.RETRYABLE_FAILURE else CloudSyncItemOutcome.FAILURE
        }
        is CloudSyncResult.NotSupported -> {
            leaseStore.markFailed(
                operationId = operationId,
                leaseOwner = leaseOwner,
                status = "FAILED",
                errorCode = "PROVIDER_NOT_SUPPORTED",
                nowMs = System.currentTimeMillis()
            )
            CloudSyncItemOutcome.FAILURE
        }
    }

    private suspend fun persistTransferState(
        operationId: String,
        progress: CloudTransferProgress,
        leaseStore: CloudSyncOperationStore,
        leaseOwner: String
    ) {
        leaseStore.updateTransferState(
            operationId = operationId,
            leaseOwner = leaseOwner,
            remoteFileId = progress.remoteFileId.orEmpty(),
            resumableSessionUri = progress.resumableSessionUri.orEmpty(),
            bytesCommitted = progress.bytesCommitted
        )
    }

    companion object {
        const val WORK_NAME = "VVF_CLOUD_SYNC_WORK"
        const val LEASE_DURATION_MS = 120_000L
        const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val TAG = "CloudSyncWorker"
    }

    private enum class CloudSyncItemOutcome {
        SYNCED,
        RETRYABLE_FAILURE,
        FAILURE,
        SKIPPED,
    }

    private data class CloudSyncRunSummary(
        val syncedCount: Int = 0,
        val failedCount: Int = 0,
        val retryableFailedCount: Int = 0
    ) {
        fun record(outcome: CloudSyncItemOutcome): CloudSyncRunSummary = when (outcome) {
            CloudSyncItemOutcome.SYNCED -> copy(syncedCount = syncedCount + 1)
            CloudSyncItemOutcome.RETRYABLE_FAILURE -> copy(
                failedCount = failedCount + 1,
                retryableFailedCount = retryableFailedCount + 1
            )
            CloudSyncItemOutcome.FAILURE -> copy(failedCount = failedCount + 1)
            CloudSyncItemOutcome.SKIPPED -> this
        }

        fun toWorkerResult(): DurableWorkResult = when {
            retryableFailedCount > 0 -> DurableWorkResult.retry()
            failedCount > 0 -> DurableWorkResult.failure()
            else -> DurableWorkResult.success()
        }

        fun logMessage(): String =
            "CloudSyncWorker finished. Synced: $syncedCount, Failed: $failedCount (Retryable: $retryableFailedCount)"
    }
}
