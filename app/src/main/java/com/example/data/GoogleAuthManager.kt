package com.example.data

import android.content.SharedPreferences
import com.example.security.SharedPreferencesKeyValueStore
import com.example.security.StringKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class GoogleAuthState {
    object SignedOut : GoogleAuthState()
    object Authenticating : GoogleAuthState()
    /** Deliberately excludes OAuth credentials so UI collectors cannot read bearer tokens. */
    data class SignedIn(val email: String, val displayName: String?) : GoogleAuthState()
    data class Error(val message: String, val cause: Throwable? = null) : GoogleAuthState()
}

/**
 * Internal cloud-only credential boundary. Presentation state exposes identity metadata, never
 * bearer tokens. Provider adapters receive this interface rather than reading UI state.
 */
interface OAuthTokenProvider {
    fun accessTokenOrNull(): String?
}

class GoogleAuthManager(
    private val secureStore: StringKeyValueStore,
    private val allowTestSessionInjection: Boolean = false
) : OAuthTokenProvider {
    /** SharedPreferences construction is retained exclusively for JVM and instrumented fixtures. */
    constructor(sharedPrefs: SharedPreferences) : this(
        secureStore = SharedPreferencesKeyValueStore(sharedPrefs),
        allowTestSessionInjection = true
    )

    private val _authState = MutableStateFlow<GoogleAuthState>(GoogleAuthState.SignedOut)
    val authState: StateFlow<GoogleAuthState> = _authState.asStateFlow()

    init { restoreSession() }

    private fun restoreSession() {
        val accessToken = secureStore.getString(KEY_ACCESS_TOKEN)
        val refreshToken = secureStore.getString(KEY_REFRESH_TOKEN)
        val email = secureStore.getString(KEY_EMAIL)
        val displayName = secureStore.getString(KEY_DISPLAY_NAME)

        if (canRestoreTestSession(accessToken, refreshToken, email)) {
            _authState.value = GoogleAuthState.SignedIn(requireNotNull(email), displayName)
        } else {
            if (accessToken != null || refreshToken != null || email != null) clearStoredCredentials()
            _authState.value = GoogleAuthState.SignedOut
        }
    }

    /**
     * Test-fixture bridge only. Production instances reject raw bearer-token injection because
     * token shape cannot establish that Google issued it. A future Drive OAuth authorization-code
     * lifecycle must supply its own provider-owned credential source instead.
     */
    @Deprecated("Test fixtures only. Production OAuth sessions must come from a verified provider flow.")
    @Synchronized
    internal fun saveSession(accessToken: String, refreshToken: String?, email: String, displayName: String?) {
        if (!allowTestSessionInjection || email.isBlank() || !isPlausibleTestSession(accessToken, refreshToken)) {
            rejectRawSessionInjection()
            return
        }
        try {
            check(secureStore.commit(
                mapOf(
                    KEY_ACCESS_TOKEN to accessToken,
                    KEY_REFRESH_TOKEN to refreshToken,
                    KEY_EMAIL to email,
                    KEY_DISPLAY_NAME to displayName
                )
            )) { "Secure authentication storage did not acknowledge the commit" }
            _authState.value = GoogleAuthState.SignedIn(email, displayName)
        } catch (e: Exception) {
            _authState.value = GoogleAuthState.Error("Failed to persist authentication securely", e)
        }
    }

    @Synchronized
    fun clearSession() {
        try {
            clearStoredCredentials()
            _authState.value = GoogleAuthState.SignedOut
        } catch (e: Exception) {
            _authState.value = GoogleAuthState.Error("Failed to clear authentication locally", e)
        }
    }

    private fun clearStoredCredentials() {
        check(secureStore.commit(
            mapOf(
                KEY_ACCESS_TOKEN to null,
                KEY_REFRESH_TOKEN to null,
                KEY_EMAIL to null,
                KEY_DISPLAY_NAME to null
            )
        )) { "Secure authentication storage did not acknowledge credential removal" }
    }

    override fun accessTokenOrNull(): String? =
        secureStore.getString(KEY_ACCESS_TOKEN).takeIf { allowTestSessionInjection }

    /** Retained for non-UI package-internal OAuth lifecycle code and tests. */
    internal fun refreshTokenOrNull(): String? =
        secureStore.getString(KEY_REFRESH_TOKEN).takeIf { allowTestSessionInjection }
    fun isAuthorized(): Boolean = _authState.value is GoogleAuthState.SignedIn

    companion object {
        private val demoAccessToken = Regex("^ya29\\.a0AcEw0eB-")
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EMAIL = "email"
        const val KEY_DISPLAY_NAME = "display_name"
    }

    private fun isPlausibleTestSession(accessToken: String, refreshToken: String?): Boolean =
        accessToken.isNotBlank() &&
            !demoAccessToken.containsMatchIn(accessToken) &&
            refreshToken?.isBlank() != true

    private fun canRestoreTestSession(
        accessToken: String?,
        refreshToken: String?,
        email: String?
    ): Boolean {
        if (!allowTestSessionInjection || accessToken == null || email == null) return false
        return isPlausibleTestSession(accessToken, refreshToken)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun rejectRawSessionInjection() {
        try {
            clearStoredCredentials()
            _authState.value = GoogleAuthState.Error("Raw Google OAuth sessions are not accepted in production")
        } catch (error: Exception) {
            _authState.value = GoogleAuthState.Error("Failed to clear rejected authentication material", error)
        }
    }
}
