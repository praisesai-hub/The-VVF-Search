package com.example.storage

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoragePermissionManagerInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun advancedStoragePermissionPolicy_isExplicitAndFallsBackToSafWhenUnavailable() {
        assertTrue(StoragePermissionManager.shouldUseSafFallback(advancedModeEnabled = false))
        assertNull(StoragePermissionManager.settingsIntent(context, advancedModeEnabled = false))

        val hasAccess = StoragePermissionManager.hasFullDeviceAccess()
        assertEquals(!hasAccess, StoragePermissionManager.shouldUseSafFallback(advancedModeEnabled = true))

        val intent = StoragePermissionManager.settingsIntent(context, advancedModeEnabled = true)
        if (hasAccess) {
            assertNull(intent)
        } else {
            assertTrue(intent != null)
            assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intent?.action)
            assertTrue(intent?.data?.toString()?.endsWith(context.packageName) == true)
        }
    }
}
