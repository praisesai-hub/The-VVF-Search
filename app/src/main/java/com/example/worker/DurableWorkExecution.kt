package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import com.example.data.AppDatabase
import com.example.data.WorkOperationLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext

internal enum class DurableWorkOutcome {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal data class DurableWorkResult(
    val workerResult: ListenableWorker.Result,
    val outcome: DurableWorkOutcome,
) {
    companion object {
        fun success(): DurableWorkResult = DurableWorkResult(
            workerResult = ListenableWorker.Result.success(),
            outcome = DurableWorkOutcome.SUCCESS,
        )

        fun retry(): DurableWorkResult = DurableWorkResult(
            workerResult = ListenableWorker.Result.retry(),
            outcome = DurableWorkOutcome.RETRY,
        )

        fun failure(): DurableWorkResult = DurableWorkResult(
            workerResult = ListenableWorker.Result.failure(),
            outcome = DurableWorkOutcome.FAILURE,
        )
    }
}

suspend fun executeWithDurableLease(
    context: Context,
    worker: CoroutineWorker,
    workName: String,
    operationId: String,
    block: suspend () -> DurableWorkResult,
): ListenableWorker.Result {
    val store = AppDatabase.getDatabase(context).workOperationStore()
    val lease = WorkOperationLease(store, operationId, workName, worker.id.toString())
    if (!lease.claim()) return ListenableWorker.Result.success()

    val heartbeat = lease.startHeartbeat(CoroutineScope(currentCoroutineContext()))
    return try {
        val completed = block()
        when (completed.outcome) {
            DurableWorkOutcome.SUCCESS -> lease.complete()
            DurableWorkOutcome.RETRY -> lease.finishForRetry("RETRY_SCHEDULED")
            DurableWorkOutcome.FAILURE -> lease.fail("WORK_FAILED")
        }
        completed.workerResult
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unexpected: Exception) {
        lease.fail("UNEXPECTED_FAILURE")
        throw unexpected
    } finally {
        heartbeat.cancel()
    }
}
