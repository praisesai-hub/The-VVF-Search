package com.example

import androidx.work.Configuration
import io.mockk.mockk
import io.mockk.verify
import com.example.data.SmartManagerRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VVFApplicationTest {
    @Test
    fun `work manager configuration uses info minimum logging`() {
        val application = VVFApplication()

        val configuration: Configuration = application.workManagerConfiguration

        assertEquals(android.util.Log.INFO, configuration.minimumLoggingLevel)
    }

    @Test
    fun `onTrimMemory delegates to repository`() {
        val application = VVFApplication()
        val repository = mockk<SmartManagerRepository>(relaxed = true)
        application.repository = repository

        application.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        verify(exactly = 1) { repository.trimMemory() }
    }
}
