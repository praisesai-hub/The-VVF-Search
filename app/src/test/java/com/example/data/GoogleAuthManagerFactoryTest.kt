package com.example.data

import android.content.Context
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoogleAuthManagerFactoryTest {
    @Test
    fun factory_returnsSingletonAndRetiresLegacyPlainPreferenceFile(): Unit {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("access_token", "legacy-access-token")
            .putString("refresh_token", "legacy-refresh-token")
            .commit()

        val first = GoogleAuthManagerFactory.getInstance(context)
        val second = GoogleAuthManagerFactory.getInstance(context.applicationContext)
        val capability = GoogleAuthManagerFactory.getTokenProvider(context)

        assertNotNull(first)
        assertSame(first, second)
        assertSame(first, capability)
        assertTrue(
            context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .all
                .isEmpty()
        )
    }

    private companion object {
        const val LEGACY_PREFS = "secure_google_oauth_prefs"
    }
}
