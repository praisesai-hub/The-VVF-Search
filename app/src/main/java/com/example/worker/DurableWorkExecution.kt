package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import com.example.data.AppDatabase
import com.example.data.WorkOperationLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

suspend fun executeWithDurableLease(
    context: Context,
    worker: CoroutineWorker,
    scope: CoroutineScope,
    workName: String,
    operationId: String,
    block: suspend () -> ListenableWorker.Result
): ListenableWorker.Result {
    val store = AppDatabase.getDatabase(context).workOperationStore()
    val lease = WorkOperationLease(store, operationId, workName, worker.id.toString())
    if (!lease.claim()) return ListenableWorker.Result.success()

    val heartbeat = lease.startHeartbeat(scope)
    return try {
        val result = block()
        when (result) {
            is ListenableWorker.Result.Success -> lease.complete()
            is ListenableWorker.Result.Retry -> lease.finishForRetry("RETRY_SCHEDULED")
            is ListenableWorker.Result.Failure -> lease.fail("WORK_FAILED")
        }
        result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unexpected: Exception) {
        lease.fail("UNEXPECTED_FAILURE")
        throw unexpected
    } finally {
        heartbeat.cancel()
    }
}
