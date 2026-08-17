package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CloudSyncPolicy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivacyPolicyTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CrashReportingPolicy.setConsent(context, false)
        CloudSyncPolicy.setExplicitOptIn(context, false)
    }

    @After
    fun tearDown() {
        CrashReportingPolicy.setConsent(context, false)
        CloudSyncPolicy.setExplicitOptIn(context, false)
    }

    @Test
    fun crashReporting_isDisabledUntilExplicitConsent() {
        assertFalse(CrashReportingPolicy.hasConsent(context))

        CrashReportingPolicy.setConsent(context, true)

        assertTrue(CrashReportingPolicy.hasConsent(context))
    }

    @Test
    fun cloudSync_isDeniedByDefaultAndStillRequiresBuildProvisioningAfterOptIn() {
        assertFalse(CloudSyncPolicy.hasExplicitOptIn(context))
        assertFalse(CloudSyncPolicy.canTransfer(context))

        CloudSyncPolicy.setExplicitOptIn(context, true)

        assertTrue(CloudSyncPolicy.hasExplicitOptIn(context))
        assertFalse(CloudSyncPolicy.canTransfer(context))
    }
}
