package com.example.security

import android.content.Context
import android.content.SharedPreferences
import com.example.data.CloudSyncPolicy
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivacyPoliciesPlainJvmTest {
    private val context: Context = mockk(relaxed = true)
    private val preferences: SharedPreferences = mockk(relaxed = true)
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)
    private val booleanValues = mutableMapOf<String, Boolean>()

    @Before
    fun setUp() {
        booleanValues.clear()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getBoolean(any(), any()) } answers {
            booleanValues[firstArg()] ?: secondArg()
        }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            booleanValues[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } just Runs
    }

    @Test
    fun crashReporting_isDefaultDenied_andPersistsOnlyExplicitConsent() {
        assertFalse(CrashReportingPolicy.hasConsent(context))

        CrashReportingPolicy.setConsent(context, true)
        assertTrue(CrashReportingPolicy.hasConsent(context))

        CrashReportingPolicy.setConsent(context, false)
        assertFalse(CrashReportingPolicy.hasConsent(context))
    }

    @Test
    fun cloudSync_isDefaultDenied_andOptInStillRequiresProvisionedBuild() {
        assertFalse(CloudSyncPolicy.hasExplicitOptIn(context))
        assertFalse(CloudSyncPolicy.canTransfer(context))

        CloudSyncPolicy.setExplicitOptIn(context, true)

        assertTrue(CloudSyncPolicy.hasExplicitOptIn(context))
        assertFalse(CloudSyncPolicy.canTransfer(context))
    }
}
