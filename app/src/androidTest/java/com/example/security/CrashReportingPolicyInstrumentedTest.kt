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
class CrashReportingPolicyInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("vvf_privacy_choices", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("vvf_privacy_choices", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun consent_isDefaultDenyAndCanBeExplicitlyChanged() {
        assertFalse(CrashReportingPolicy.hasConsent(context))

        CrashReportingPolicy.setConsent(context, true)
        assertTrue(CrashReportingPolicy.hasConsent(context))

        CrashReportingPolicy.setConsent(context, false)
        assertFalse(CrashReportingPolicy.hasConsent(context))
    }
}
