package com.example.worker

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundIndexWorkerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun doWork_withoutReadableExternalStorage_returnsSuccess() = runBlocking {
        val worker = TestListenableWorkerBuilder<BackgroundIndexWorker>(context).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.Success::class.java, result::class.java)
    }
}
