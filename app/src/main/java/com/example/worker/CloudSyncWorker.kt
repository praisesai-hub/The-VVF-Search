package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.CloudSyncItemEntity
import com.example.data.FileDao
import com.example.data.CloudProviderAdapter
import com.example.data.CloudSyncResult
import kotlinx.coroutines.flow.first
import java.io.File

class CloudSyncWorker @JvmOverloads constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val daoOverride: FileDao? = null,
    private val providerAdapterOverride: CloudProviderAdapter? = null
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting background CloudSyncWorker with provider adapter contract...")
        return try {
            val dao = daoOverride ?: AppDatabase.getDatabase(applicationContext).fileDao()

            val syncItems = dao.getCloudSyncItems().first()
            val plugins = dao.getAllPlugins().first()
            val enabledProviders = plugins
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
                .filter { it.status == "PENDING" || it.status == "QUEUED" || it.status == "FAILED" || it.status == "UPLOADING" }
                .filter { it.provider in enabledProviders }

            if (pendingOrQueued.isEmpty()) {
                Log.i(TAG, "No pending cloud sync items for enabled plugins.")
                return Result.success()
            }

            val authManager = com.example.data.GoogleAuthManagerFactory.getInstance(applicationContext)
            val syncEngine = com.example.data.CloudSyncEngine(
                context = applicationContext,
                dao = dao,
                authManager = authManager,
                providerAdapterOverride = providerAdapterOverride
            )

            var syncedCount = 0
            var failedCount = 0
            var retryableFailedCount = 0

            for (item in pendingOrQueued) {
                // Update state to UPLOADING to reflect progress
                val uploadingItem = item.copy(status = "UPLOADING")
                dao.insertCloudSyncItem(uploadingItem)

                val syncResult = syncEngine.syncItem(item)
                when (syncResult) {
                    is CloudSyncResult.Success -> {
                        val updatedItem = item.copy(
                            status = "SYNCED",
                            lastSyncedMs = System.currentTimeMillis()
                        )
                        dao.insertCloudSyncItem(updatedItem)
                        syncedCount++
                    }
                    is CloudSyncResult.Error -> {
                        val updatedItem = item.copy(
                            status = "FAILED",
                            lastSyncedMs = System.currentTimeMillis()
                        )
                        dao.insertCloudSyncItem(updatedItem)
                        failedCount++
                        if (syncResult.isRetryable) {
                            retryableFailedCount++
                        }
                    }
                    is CloudSyncResult.NotSupported -> {
                        val updatedItem = item.copy(
                            status = "NOT_SUPPORTED",
                            lastSyncedMs = System.currentTimeMillis()
                        )
                        dao.insertCloudSyncItem(updatedItem)
                        failedCount++
                    }
                }
            }

            Log.i(TAG, "CloudSyncWorker finished. Synced: $syncedCount, Failed: $failedCount (Retryable: $retryableFailedCount)")
            if (retryableFailedCount > 0) {
                if (runAttemptCount >= 3) {
                    Log.e(TAG, "CloudSyncWorker failed after $runAttemptCount attempts. Abandoning retry.")
                    Result.failure()
                } else {
                    Result.retry()
                }
            } else if (failedCount > 0) {
                // Permanent failure (e.g. missing file) - do not retry
                Result.failure()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in CloudSyncWorker: ${e.message}", e)
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "VVF_CLOUD_SYNC_WORK"
        private const val TAG = "CloudSyncWorker"
    }
}
