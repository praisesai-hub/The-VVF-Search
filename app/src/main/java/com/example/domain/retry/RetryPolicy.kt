package com.example.domain.retry

import android.database.sqlite.SQLiteDatabaseLockedException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** Operation families use an explicit retry contract instead of a catch-all exception loop. */
enum class RetryOperation {
    DATABASE_READ,
    DATABASE_WRITE,
    FILE_STORAGE,
    INDEXING,
    CLOUD_TRANSFER,
    DUPLICATE_CLEANUP,
    CACHE_CLEANUP
}

data class RetryDecision(
    val retryable: Boolean,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val reasonCode: String
) {
    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}

object RetryPolicy {
    const val INITIAL_DELAY_MS = 100L
    const val BACKOFF_FACTOR = 2.0

    fun classify(operation: RetryOperation, cause: Throwable): RetryDecision {
        val reasonCode = reasonCodeFor(cause.rootCause())
        return RetryDecision(retryable = isRetryable(operation, reasonCode), reasonCode = reasonCode)
    }

    private fun reasonCodeFor(cause: Throwable): String = when (cause) {
        is SQLiteDatabaseLockedException -> "DATABASE_LOCKED"
        is SocketTimeoutException, is TimeoutException -> "TIMEOUT"
        is FileNotFoundException -> "SOURCE_UNAVAILABLE"
        is SecurityException -> "PERMISSION_DENIED"
        is IllegalArgumentException -> "INVALID_INPUT"
        is IOException -> ioReasonCode(cause)
        else -> "NON_TRANSIENT_FAILURE"
    }

    private fun ioReasonCode(cause: IOException): String = when {
        isNetworkUnavailable(cause) -> "NETWORK_UNAVAILABLE"
        isTemporaryIo(cause) -> "TEMPORARY_IO"
        else -> "IO_FAILURE"
    }

    private fun isNetworkUnavailable(cause: Throwable): Boolean = when (cause) {
        is UnknownHostException, is ConnectException -> true
        else -> cause.message?.contains("unable to resolve host", ignoreCase = true) == true
    }

    private fun isRetryable(operation: RetryOperation, reasonCode: String): Boolean = when (operation) {
            RetryOperation.DATABASE_READ,
            RetryOperation.DATABASE_WRITE -> reasonCode == "DATABASE_LOCKED"
            RetryOperation.FILE_STORAGE,
            RetryOperation.INDEXING,
            RetryOperation.DUPLICATE_CLEANUP,
            RetryOperation.CACHE_CLEANUP -> reasonCode == "TEMPORARY_IO"
            RetryOperation.CLOUD_TRANSFER -> reasonCode in setOf(
                "TIMEOUT",
                "NETWORK_UNAVAILABLE",
                "TEMPORARY_IO"
            )
        }

    fun shouldRetry(operation: RetryOperation, cause: Throwable, runAttemptCount: Int): Boolean {
        val decision = classify(operation, cause)
        return decision.retryable && runAttemptCount + 1 < decision.maxAttempts
    }

    fun delayForAttempt(attempt: Int): Long =
        (INITIAL_DELAY_MS * BACKOFF_FACTOR.pow(attempt.coerceAtLeast(0))).toLong()

    private fun isTemporaryIo(error: IOException): Boolean {
        val message = error.message.orEmpty().lowercase()
        if (message.contains("enospc") || message.contains("no space") || message.contains("permission denied")) {
            return false
        }
        return message.contains("temporar") ||
            message.contains("try again") ||
            message.contains("resource busy") ||
            message.contains("connection reset")
    }

    private fun Throwable.rootCause(): Throwable {
        var current = this
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private fun Double.pow(exponent: Int): Double {
        var result = 1.0
        repeat(exponent) { result *= this }
        return result
    }
}
