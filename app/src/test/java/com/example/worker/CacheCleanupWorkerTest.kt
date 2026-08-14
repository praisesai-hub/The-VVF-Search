package com.example.worker

import android.content.Context
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CacheCleanupWorkerTest {
    private lateinit var context: Context
    private lateinit var fixture: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        fixture = File(context.cacheDir, "vvf-cache-cleanup-test/nested")
        fixture.mkdirs()
        File(fixture, "small.bin").writeBytes(ByteArray(7) { 1 })
        File(fixture, "empty.bin").createNewFile()
    }

    @After
    fun tearDown() {
        File(context.cacheDir, "vvf-cache-cleanup-test").deleteRecursively()
    }

    @Test
    fun `worker deletes nested cache files and succeeds`() = runBlocking {
        val worker = TestListenableWorkerBuilder.from(context, CacheCleanupWorker::class.java).build()

        val result = worker.startWork().get()

        assertEquals(ListenableWorker.Result.Success::class.java, result::class.java)
        assertFalse(File(context.cacheDir, "vvf-cache-cleanup-test/nested/small.bin").exists())
        assertFalse(File(context.cacheDir, "vvf-cache-cleanup-test/nested/empty.bin").exists())
    }
}
