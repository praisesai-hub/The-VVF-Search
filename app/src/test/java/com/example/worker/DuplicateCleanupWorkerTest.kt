package com.example.worker

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DuplicateCleanupWorkerTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("vvf_app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun doWork_returnsSuccessWithoutOpeningDatabaseWhenAutoCleanIsDisabled() = runBlocking {
        val worker = TestListenableWorkerBuilder<DuplicateCleanupWorker>(context).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.Success::class.java, result::class.java)
    }

    @Test
    fun doWork_enabledWithNoDuplicates_returnsSuccess() = runBlocking {
        context.getSharedPreferences("vvf_app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_clean_duplicates_bg", true)
            .commit()
        val worker = TestListenableWorkerBuilder<DuplicateCleanupWorker>(context).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.Success::class.java, result::class.java)
    }
}
