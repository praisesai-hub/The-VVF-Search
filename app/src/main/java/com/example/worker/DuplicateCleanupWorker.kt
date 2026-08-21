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
import com.example.data.FileDao
import com.example.data.FileItemEntity
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

    override suspend fun doWork(): Result = executeWithDurableLease(
        context = applicationContext,
        worker = this,
        workName = WORK_NAME,
        operationId = inputData.getString(WorkCoordinator.OPERATION_ID_KEY) ?: "worker:${id}",
        block = { runWork() }
    )

    private suspend fun runWork(): DurableWorkResult {
        Log.i(TAG, "Starting background DuplicateCleanupWorker...")
        return try {
            val prefs = applicationContext.getSharedPreferences("vvf_app_settings", Context.MODE_PRIVATE)
            val isAutoCleanEnabled = prefs.getBoolean("auto_clean_duplicates_bg", false)
            if (!isAutoCleanEnabled) {
                Log.i(TAG, "Auto-clean duplicates in background is disabled in settings. Skipping cleanup execution.")
                DurableWorkResult.success()
            } else {
                val totals = cleanExactDuplicates(AppDatabase.getDatabase(applicationContext).fileDao())
                reportCleanupTotals(totals)
                DurableWorkResult.success()
            }
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
                DurableWorkResult.retry()
            } else {
                DurableWorkResult.failure()
            }
        }
    }

    private suspend fun cleanExactDuplicates(dao: FileDao): CleanupTotals =
        dao.getAllActiveFiles().first()
            .filter { it.md5Hash.isNotBlank() }
            .groupBy { it.md5Hash }
            .filterValues { it.size > 1 }
            .values
            .fold(CleanupTotals()) { totals, duplicateList ->
                totals + recycleRedundantFiles(dao, duplicateList)
            }

    private suspend fun recycleRedundantFiles(
        dao: FileDao,
        duplicateList: List<FileItemEntity>
    ): CleanupTotals = duplicateList.sortedBy { it.dateModifiedMs }
        .drop(1)
        .fold(CleanupTotals()) { totals, file -> totals + recycleIfEligible(dao, file) }

    private suspend fun recycleIfEligible(dao: FileDao, file: FileItemEntity): CleanupTotals =
        if (file.md5Hash.isNotBlank() && dao.findInRecycleBinByHash(file.md5Hash) != null) {
            CleanupTotals()
        } else {
            moveDuplicateToRecycleBin(dao, file)
        }

    private suspend fun moveDuplicateToRecycleBin(dao: FileDao, file: FileItemEntity): CleanupTotals =
        PhysicalStorageManager.moveToTrash(applicationContext, file.path).fold(
            onSuccess = { newPath ->
                val originalPathToKeep = file.originalPath.ifBlank { file.path }
                val recycledFile = file.copy(
                    path = newPath,
                    originalPath = originalPathToKeep,
                    isRecycleBin = true,
                    deletedTimestampMs = System.currentTimeMillis()
                )
                dao.moveFilesToRecycleBinAtomic(listOf(recycledFile))
                CleanupTotals(duplicatesFound = 1, bytesCleaned = file.sizeBytes)
            },
            onFailure = { cause ->
                logMoveFailure(file, cause)
                CleanupTotals()
            }
        )

    private fun logMoveFailure(file: FileItemEntity, cause: Throwable?) {
        val diagnostic = DomainError.OperationFailed(
            userMessage = UserMessage("The duplicate file could not be moved."),
            diagnostics = DiagnosticContext(
                operation = "DUPLICATE_MOVE_TO_TRASH",
                fileId = file.id,
                reasonCode = "PHYSICAL_MOVE_FAILED"
            ),
            internalCause = cause
        )
        DiagnosticLogger.log(TAG, diagnostic, DiagnosticLogger.Level.WARN)
    }

    private fun reportCleanupTotals(totals: CleanupTotals) {
        if (totals.duplicatesFound > 0) {
            Log.i(
                TAG,
                "DuplicateCleanupWorker moved ${totals.duplicatesFound} duplicate files " +
                    "(${totals.bytesCleaned / 1024} KB) to Recycle Bin."
            )
            sendNotification(applicationContext, totals.duplicatesFound, totals.bytesCleaned)
        } else {
            Log.i(TAG, "DuplicateCleanupWorker completed. No exact duplicate clutter found.")
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

    private data class CleanupTotals(
        val duplicatesFound: Int = 0,
        val bytesCleaned: Long = 0L
    ) {
        operator fun plus(other: CleanupTotals): CleanupTotals = CleanupTotals(
            duplicatesFound = duplicatesFound + other.duplicatesFound,
            bytesCleaned = bytesCleaned + other.bytesCleaned
        )
    }

    companion object {
        const val WORK_NAME = "VVF_DUPLICATE_CLEANUP_WORK"
        private const val TAG = "DuplicateCleanupWorker"
    }
}
