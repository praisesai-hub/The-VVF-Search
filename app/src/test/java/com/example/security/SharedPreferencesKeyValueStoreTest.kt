package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesKeyValueStoreTest {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var store: SharedPreferencesKeyValueStore

    @Before
    fun setUp(): Unit {
        preferences = ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("shared-preferences-adapter-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = SharedPreferencesKeyValueStore(preferences)
    }

    @After
    fun tearDown(): Unit {
        preferences.edit().clear().commit()
    }

    @Test
    fun commit_persistsUpdatesAndRemovesNullEntries(): Unit {
        assertTrue(store.commit(mapOf("access" to "token", "email" to "user@example.com")))

        assertTrue(store.commit(mapOf("access" to "updated", "email" to null)))

        assertEquals("updated", store.getString("access"))
        assertNull(store.getString("email"))
    }
}
