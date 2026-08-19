package com.example.data

import android.content.SharedPreferences
import com.example.security.SharedPreferencesKeyValueStore
import com.example.security.StringKeyValueStore
import com.example.context.drive.DriveAuthorizationPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class GoogleAuthState {
    object SignedOut : GoogleAuthState()
    object Authenticating : GoogleAuthState()
    data class SignedIn(val email: String, val displayName: String?) : GoogleAuthState()
    data class Error(val message: String, val cause: Throwable? = null) : GoogleAuthState()
}

class GoogleAuthManager(private val secureStore: StringKeyValueStore) : DriveAuthorizationPort {
    constructor(sharedPrefs: SharedPreferences) : this(SharedPreferencesKeyValueStore(sharedPrefs))

    private val _authState = MutableStateFlow<GoogleAuthState>(GoogleAuthState.SignedOut)
    val authState: StateFlow<GoogleAuthState> = _authState.asStateFlow()

    init { restoreSession() }

    private fun restoreSession() {
        val accessToken = secureStore.getString(KEY_ACCESS_TOKEN)
        val refreshToken = secureStore.getString(KEY_REFRESH_TOKEN)
        val email = secureStore.getString(KEY_EMAIL)
        val displayName = secureStore.getString(KEY_DISPLAY_NAME)

        if (accessToken != null && email != null &&
            SecureOAuthSessionValidator.isValid(accessToken, refreshToken)
        ) {
            _authState.value = GoogleAuthState.SignedIn(email, displayName)
        } else {
            if (accessToken != null || refreshToken != null || email != null) clearStoredCredentials()
            _authState.value = GoogleAuthState.SignedOut
        }
    }

    @Synchronized
    fun saveSession(accessToken: String, refreshToken: String?, email: String, displayName: String?) {
        if (email.isBlank() || !SecureOAuthSessionValidator.isValid(accessToken, refreshToken)) {
            _authState.value = GoogleAuthState.Error("Invalid Google OAuth session")
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

    /** Legacy raw-token accessor; CloudTransfer must use [authorizationHeader] instead. */
    @Deprecated("Use DriveAuthorizationPort.authorizationHeader")
    fun getAccessToken(): String? = secureStore.getString(KEY_ACCESS_TOKEN)

    fun getRefreshToken(): String? = secureStore.getString(KEY_REFRESH_TOKEN)

    override fun authorizationHeader(): String? = getAccessToken()
        ?.takeIf { it.isNotBlank() }
        ?.let { "Bearer $it" }

    override fun isAuthorized(): Boolean = _authState.value is GoogleAuthState.SignedIn

    companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EMAIL = "email"
        const val KEY_DISPLAY_NAME = "display_name"
    }
}
