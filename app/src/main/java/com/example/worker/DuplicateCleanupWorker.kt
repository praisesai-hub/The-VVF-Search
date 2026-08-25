package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.domain.error.DiagnosticContext
import com.example.domain.error.DiagnosticLogger
import com.example.domain.error.DomainError
import com.example.domain.error.UserMessage
import com.example.domain.retry.RetryOperation
import com.example.domain.retry.RetryPolicy
import com.example.domain.WorkCoordinator
import com.example.storage.PhysicalStorageManager
import com.example.storage.StorageScanner
import kotlinx.coroutines.flow.first

class DuplicateCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = kotlinx.coroutines.coroutineScope {
        executeWithDurableLease(
        context = applicationContext,
        worker = this@DuplicateCleanupWorker,
        scope = this,
        workName = WORK_NAME,
        operationId = inputData.getString(WorkCoordinator.OPERATION_ID_KEY) ?: "worker:${id}",
        block = { runWork() }
        )
    }

    // Query, policy, and recycle-bin transitions remain one cleanup transaction boundary.
    @Suppress("detekt.LongMethod", "detekt.NestedBlockDepth")
    private suspend fun runWork(): Result {
        Log.i(TAG, "Starting background DuplicateCleanupWorker...")
        return try {
            val prefs = applicationContext.getSharedPreferences("vvf_app_settings", Context.MODE_PRIVATE)
            val isAutoCleanEnabled = prefs.getBoolean("auto_clean_duplicates_bg", false)
            if (!isAutoCleanEnabled) {
                Log.i(TAG, "Auto-clean duplicates in background is disabled in settings. Skipping cleanup execution.")
                return Result.success()
            }

            val db = AppDatabase.getDatabase(applicationContext)
            val dao = db.fileDao()

            val activeFiles = dao.getAllActiveFiles().first()
            var duplicatesFound = 0
            var bytesCleaned = 0L

            // Group by MD5 hash for exact duplicates
            val exactDuplicateGroups = activeFiles
                .filter { it.md5Hash.isNotBlank() }
                .groupBy { it.md5Hash }
                .filter { it.value.size > 1 }

            for ((_, duplicateList) in exactDuplicateGroups) {
                // Keep the oldest/first file, mark redundant ones for cleanup/recycle bin
                val sorted = duplicateList.sortedBy { it.dateModifiedMs }
                val redundant = sorted.drop(1)
                for (file in redundant) {
                    // Idempotency guard: a previous attempt may already have persisted this content in the recycle bin.
                    if (file.md5Hash.isNotBlank() && dao.findInRecycleBinByHash(file.md5Hash) != null) {
                        continue
                    }

                    val trashResult = PhysicalStorageManager.moveToTrash(applicationContext, file.path)
                    if (trashResult.isSuccess) {
                        val newPath = trashResult.getOrThrow()
                        val originalPathToKeep = if (file.originalPath.isNotBlank()) file.originalPath else file.path
                        val recycledFile = file.copy(
                            path = newPath,
                            originalPath = originalPathToKeep,
                            isRecycleBin = true,
                            deletedTimestampMs = System.currentTimeMillis()
                        )

                        // Persist immediately after each physical move instead of waiting for the entire batch.
                        // This narrows the crash window and makes retries idempotent.
                        dao.moveFilesToRecycleBinAtomic(listOf(recycledFile))
                        duplicatesFound++
                        bytesCleaned += file.sizeBytes
                    } else {
                        val diagnostic = DomainError.OperationFailed(
                            userMessage = UserMessage("The duplicate file could not be moved."),
                            diagnostics = DiagnosticContext(
                                operation = "DUPLICATE_MOVE_TO_TRASH",
                                fileId = file.id,
                                reasonCode = "PHYSICAL_MOVE_FAILED"
                            ),
                            internalCause = trashResult.exceptionOrNull()
                        )
                        DiagnosticLogger.log(TAG, diagnostic, DiagnosticLogger.Level.WARN)
                    }
                }
            }

            if (duplicatesFound > 0) {
                Log.i(
                    TAG,
                    "DuplicateCleanupWorker moved $duplicatesFound duplicate files (${bytesCleaned / 1024} KB) to Recycle Bin."
                )
                sendNotification(applicationContext, duplicatesFound, bytesCleaned)
            } else {
                Log.i(TAG, "DuplicateCleanupWorker completed. No exact duplicate clutter found.")
            }

            Result.success()
        } catch (e: Exception) {
            val diagnostic = DomainError.OperationFailed(
                userMessage = UserMessage("Duplicate cleanup could not be completed."),
                diagnostics = DiagnosticContext(
                    operation = "DUPLICATE_CLEANUP",
                    reasonCode = "UNEXPECTED_FAILURE"
                ),
                internalCause = e
            )
            DiagnosticLogger.log(TAG, diagnostic)
            if (RetryPolicy.shouldRetry(RetryOperation.DUPLICATE_CLEANUP, e, runAttemptCount)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun sendNotification(context: Context, filesCount: Int, bytesSaved: Long) {
        try {
            val channelId = "vvf_duplicate_cleanup_channel"
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Duplicate Cleanup Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifies when background auto-clean moves duplicate files to Recycle Bin"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val formattedSize = if (bytesSaved > 1024 * 1024) "${bytesSaved / (1024 * 1024)} MB" else "${bytesSaved / 1024} KB"
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Auto Duplicate Cleanup")
                .setContentText("$filesCount duplicate files ($formattedSize) moved to Recycle Bin")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(1002, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification: ${e.message}")
        }
    }

    companion object {
        const val WORK_NAME = "VVF_DUPLICATE_CLEANUP_WORK"
        private const val TAG = "DuplicateCleanupWorker"
    }
}
