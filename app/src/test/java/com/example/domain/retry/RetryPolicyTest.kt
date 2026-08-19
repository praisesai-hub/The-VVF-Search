package com.example.domain.retry

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseLockedException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun databaseLockIsRetryableButConstraintIsTerminal() {
        assertTrue(
            RetryPolicy.classify(
                RetryOperation.DATABASE_WRITE,
                SQLiteDatabaseLockedException("database locked")
            ).retryable
        )
        assertFalse(
            RetryPolicy.classify(
                RetryOperation.DATABASE_WRITE,
                SQLiteConstraintException("unique constraint")
            ).retryable
        )
    }

    @Test
    fun cloudTimeoutAndNetworkFailureAreRetryable() {
        assertTrue(
            RetryPolicy.classify(RetryOperation.CLOUD_TRANSFER, SocketTimeoutException("timeout")).retryable
        )
        assertTrue(
            RetryPolicy.classify(
                RetryOperation.CLOUD_TRANSFER,
                IOException("connection reset by peer")
            ).retryable
        )
    }

    @Test
    fun permissionDiskFullAndMissingFileAreTerminal() {
        assertFalse(
            RetryPolicy.classify(RetryOperation.FILE_STORAGE, SecurityException("permission denied")).retryable
        )
        assertFalse(
            RetryPolicy.classify(RetryOperation.FILE_STORAGE, IOException("ENOSPC: no space left")).retryable
        )
        assertFalse(
            RetryPolicy.classify(RetryOperation.FILE_STORAGE, FileNotFoundException("missing source")).retryable
        )
    }

    @Test
    fun retryBudgetAllowsThreeTotalAttemptsNotFour() {
        val error = IOException("temporary I/O failure")
        assertTrue(RetryPolicy.shouldRetry(RetryOperation.INDEXING, error, runAttemptCount = 0))
        assertTrue(RetryPolicy.shouldRetry(RetryOperation.INDEXING, error, runAttemptCount = 1))
        assertFalse(RetryPolicy.shouldRetry(RetryOperation.INDEXING, error, runAttemptCount = 2))
    }
}
