package com.example.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GoogleAuthManagerTest {

    private lateinit var sharedPrefs: FakeSharedPreferences
    private lateinit var authManager: GoogleAuthManager

    @Before
    fun setUp() {
        sharedPrefs = FakeSharedPreferences()
    }

    @Test
    fun testInitialState_SignedOut_WhenNoTokens() {
        authManager = GoogleAuthManager(sharedPrefs)

        assertEquals(GoogleAuthState.SignedOut, authManager.authState.value)
        assertFalse(authManager.isAuthorized())
    }

    @Test
    fun testInitialState_SignedIn_WhenTokensExist() {
        sharedPrefs.edit()
            .putString(GoogleAuthManager.KEY_ACCESS_TOKEN, "mock_access_token")
            .putString(GoogleAuthManager.KEY_EMAIL, "test@example.com")
            .putString(GoogleAuthManager.KEY_DISPLAY_NAME, "Test User")
            .apply()

        authManager = GoogleAuthManager(sharedPrefs)

        val state = authManager.authState.value
        assertTrue(state is GoogleAuthState.SignedIn)
        val signedInState = state as GoogleAuthState.SignedIn
        assertEquals("test@example.com", signedInState.email)
        assertEquals("Test User", signedInState.displayName)
        assertEquals("mock_access_token", signedInState.accessToken)
        assertTrue(authManager.isAuthorized())
    }

    @Test
    fun testSaveSession_UpdatesStateAndPersists() {
        authManager = GoogleAuthManager(sharedPrefs)

        authManager.saveSession("new_access_token", "new_refresh_token", "user@example.com", "User Name")

        assertEquals("new_access_token", sharedPrefs.getString(GoogleAuthManager.KEY_ACCESS_TOKEN, null))
        assertEquals("new_refresh_token", sharedPrefs.getString(GoogleAuthManager.KEY_REFRESH_TOKEN, null))
        assertEquals("user@example.com", sharedPrefs.getString(GoogleAuthManager.KEY_EMAIL, null))
        assertEquals("User Name", sharedPrefs.getString(GoogleAuthManager.KEY_DISPLAY_NAME, null))

        val state = authManager.authState.value
        assertTrue(state is GoogleAuthState.SignedIn)
        val signedInState = state as GoogleAuthState.SignedIn
        assertEquals("user@example.com", signedInState.email)
        assertEquals("new_access_token", signedInState.accessToken)
    }

    @Test
    fun testClearSession_ClearsTokensAndSignsOut() {
        sharedPrefs.edit()
            .putString(GoogleAuthManager.KEY_ACCESS_TOKEN, "token")
            .putString(GoogleAuthManager.KEY_EMAIL, "user@example.com")
            .apply()

        authManager = GoogleAuthManager(sharedPrefs)
        assertTrue(authManager.isAuthorized())

        authManager.clearSession()

        assertNull(sharedPrefs.getString(GoogleAuthManager.KEY_ACCESS_TOKEN, null))
        assertNull(sharedPrefs.getString(GoogleAuthManager.KEY_EMAIL, null))

        assertEquals(GoogleAuthState.SignedOut, authManager.authState.value)
        assertFalse(authManager.isAuthorized())
    }

    // High performance Fake implementation of Android SharedPreferences
    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

        inner class FakeEditor : SharedPreferences.Editor {
            private val tempMap = mutableMapOf<String, Any?>()
            private val removeKeys = mutableSetOf<String>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                tempMap[key] = value
                removeKeys.remove(key)
                return this
            }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
                tempMap[key] = values
                removeKeys.remove(key)
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                tempMap[key] = value
                removeKeys.remove(key)
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                tempMap[key] = value
                removeKeys.remove(key)
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                tempMap[key] = value
                removeKeys.remove(key)
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                tempMap[key] = value
                removeKeys.remove(key)
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                removeKeys.add(key)
                tempMap.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                tempMap.clear()
                removeKeys.addAll(map.keys)
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                removeKeys.forEach { map.remove(it) }
                map.putAll(tempMap)
            }
        }
    }
}
