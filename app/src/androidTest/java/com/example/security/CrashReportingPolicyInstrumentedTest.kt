package com.example.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrashReportingPolicyInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun resetConsent() {
        CrashReportingPolicy.setConsent(context, false)
    }

    @Test
    fun consentIsDisabledByDefaultAndPersistsExplicitChanges() {
        assertFalse(CrashReportingPolicy.hasConsent(context))
        CrashReportingPolicy.setConsent(context, true)
        assertTrue(CrashReportingPolicy.hasConsent(context))
        CrashReportingPolicy.setConsent(context, false)
        assertFalse(CrashReportingPolicy.hasConsent(context))
    }
}
