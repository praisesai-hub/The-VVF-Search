package com.example.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoragePermissionManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultPolicyUsesSafWithoutLaunchingRestrictedSettings() {
        assertTrue(StoragePermissionManager.shouldUseSafFallback())
        assertNull(StoragePermissionManager.settingsIntent(context))
    }

    @Test
    fun restrictedSettingsRequiresExplicitAdvancedMode() {
        assertNull(StoragePermissionManager.settingsIntent(context, advancedModeEnabled = false))
        val intent = StoragePermissionManager.settingsIntent(context, advancedModeEnabled = true)
        if (StoragePermissionManager.hasFullDeviceAccess()) {
            assertNull(intent)
        } else {
            assertEquals(
                "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                intent?.action,
            )
        }
    }
}
