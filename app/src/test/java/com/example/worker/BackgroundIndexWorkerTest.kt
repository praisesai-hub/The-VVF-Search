package com.example.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.AppDatabase
import com.example.data.FileDao
import com.example.storage.StorageScanResult
import com.example.storage.StorageScanner
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

private class StubStorageScanner(
    context: Context,
    private val result: StorageScanResult,
) : StorageScanner(context) {
    override suspend fun scanDeviceStorageWithResult(
        computeHashes: Boolean,
        onBatchDiscovered: suspend (List<com.example.data.FileItemEntity>) -> Unit,
    ): StorageScanResult = result
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundIndexWorkerTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun createWorker(scanner: StorageScanner, database: AppDatabase): BackgroundIndexWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: androidx.work.WorkerParameters,
            ): ListenableWorker = BackgroundIndexWorker(appContext, workerParameters, scanner, database)
        }
        return TestListenableWorkerBuilder<BackgroundIndexWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun partialScan_doesNotReconcileStaleRecords() = runTest {
        val dao = mockk<FileDao>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        every { database.fileDao() } returns dao
        val scanner = StubStorageScanner(
            context,
            StorageScanResult.Partial(setOf("/visible.txt"), 1, "MediaStore query was unavailable"),
        )

        val result = createWorker(scanner, database).doWork()

        assertEquals(ListenableWorker.Result.Success::class.java, result::class.java)
        coVerify(exactly = 0) { dao.reconcileStaleRecords(any()) }
    }

    @Test
    fun completeScan_reconcilesExactlyOnceWithDiscoveredPaths() = runTest {
        val dao = mockk<FileDao>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        every { database.fileDao() } returns dao
        val discoveredPaths = setOf("/visible.txt")
        val scanner = StubStorageScanner(
            context,
            StorageScanResult.Complete(discoveredPaths, 1),
        )

        val result = createWorker(scanner, database).doWork()

        assertEquals(ListenableWorker.Result.Success::class.java, result::class.java)
        coVerify(exactly = 1) { dao.reconcileStaleRecords(discoveredPaths) }
    }

    @Test
    fun doWork_withoutReadableExternalStorage_returnsSuccess() = runBlocking {
        val worker = TestListenableWorkerBuilder<BackgroundIndexWorker>(context).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.Success::class.java, result::class.java)
    }
}
