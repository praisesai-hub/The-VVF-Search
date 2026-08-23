package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyPolicyInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CrashReportingPolicy.setConsent(context, false)
    }

    @After
    fun tearDown() {
        CrashReportingPolicy.setConsent(context, false)
    }

    @Test
    fun crashReporting_requiresExplicitConsentAndPersistsChoice() {
        assertFalse(CrashReportingPolicy.hasConsent(context))

        CrashReportingPolicy.setConsent(context, true)

        assertTrue(CrashReportingPolicy.hasConsent(context))

        CrashReportingPolicy.setConsent(context, false)

        assertFalse(CrashReportingPolicy.hasConsent(context))
    }
}
