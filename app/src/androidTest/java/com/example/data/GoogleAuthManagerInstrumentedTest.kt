package com.example.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.security.StringKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleAuthManagerInstrumentedTest {

    @Test
    fun saveAndClearSession_updatesStateAndSecureStore() {
        val store = FakeStringKeyValueStore()
        val manager = GoogleAuthManager(store)

        manager.saveSession("access-token", "refresh-token", "user@example.com", "User")

        assertTrue(manager.authState.value is GoogleAuthState.SignedIn)
        assertTrue(manager.isAuthorized())
        assertEquals("access-token", manager.accessTokenOrNull())
        assertEquals("refresh-token", manager.refreshTokenOrNull())

        manager.clearSession()

        assertEquals(GoogleAuthState.SignedOut, manager.authState.value)
        assertFalse(manager.isAuthorized())
        assertNull(manager.accessTokenOrNull())
        assertNull(manager.refreshTokenOrNull())
        assertNull(store.values[GoogleAuthManager.KEY_EMAIL])
    }

    @Test
    fun restoreSession_acceptsValidStoredCredentials() {
        val store = FakeStringKeyValueStore(
            mutableMapOf(
                GoogleAuthManager.KEY_ACCESS_TOKEN to "access-token",
                GoogleAuthManager.KEY_REFRESH_TOKEN to "refresh-token",
                GoogleAuthManager.KEY_EMAIL to "user@example.com",
                GoogleAuthManager.KEY_DISPLAY_NAME to "User",
            ),
        )

        val manager = GoogleAuthManager(store)
        val state = manager.authState.value

        assertTrue(state is GoogleAuthState.SignedIn)
        assertEquals("user@example.com", (state as GoogleAuthState.SignedIn).email)
        assertEquals("User", state.displayName)
    }

    @Test
    fun restoreSession_clearsIncompleteOrInvalidCredentials_failClosed() {
        val store = FakeStringKeyValueStore(
            mutableMapOf(
                GoogleAuthManager.KEY_ACCESS_TOKEN to "access-token",
                GoogleAuthManager.KEY_DISPLAY_NAME to "User",
            ),
        )

        val manager = GoogleAuthManager(store)

        assertEquals(GoogleAuthState.SignedOut, manager.authState.value)
        assertFalse(manager.isAuthorized())
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun invalidSessions_areRejectedWithoutPersistingCredentials() {
        val store = FakeStringKeyValueStore()
        val manager = GoogleAuthManager(store)

        manager.saveSession("", "refresh-token", "user@example.com", "User")
        assertTrue(manager.authState.value is GoogleAuthState.Error)
        assertTrue(store.values.isEmpty())

        manager.saveSession("access-token", null, "   ", "User")
        assertTrue(manager.authState.value is GoogleAuthState.Error)
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun failedSecureCommit_isReportedAsErrorAndNeverAuthorizes() {
        val store = FakeStringKeyValueStore(commitResult = false)
        val manager = GoogleAuthManager(store)

        manager.saveSession("access-token", "refresh-token", "user@example.com", "User")

        assertTrue(manager.authState.value is GoogleAuthState.Error)
        assertFalse(manager.isAuthorized())
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun failedCredentialRemoval_isReportedAsError() {
        val store = FakeStringKeyValueStore(
            mutableMapOf(
                GoogleAuthManager.KEY_ACCESS_TOKEN to "access-token",
                GoogleAuthManager.KEY_REFRESH_TOKEN to "refresh-token",
                GoogleAuthManager.KEY_EMAIL to "user@example.com",
            ),
            commitResult = true,
        )
        val manager = GoogleAuthManager(store)
        store.commitResult = false

        manager.clearSession()

        assertTrue(manager.authState.value is GoogleAuthState.Error)
        assertFalse(manager.isAuthorized())
    }

    private class FakeStringKeyValueStore(
        val values: MutableMap<String, String?> = mutableMapOf(),
        var commitResult: Boolean = true,
    ) : StringKeyValueStore {
        override fun getString(key: String, defaultValue: String?): String? =
            if (values.containsKey(key)) values[key] else defaultValue

        override fun commit(values: Map<String, String?>): Boolean {
            if (!commitResult) return false
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
            return true
        }
    }
}
