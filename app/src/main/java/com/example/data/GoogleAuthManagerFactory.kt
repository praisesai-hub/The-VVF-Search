package com.example.data

import android.content.Context
import com.example.security.SecureKeyValueStore

/**
 * Creates the singleton Google OAuth session manager using the project-owned encrypted store.
 * Authentication state must fail closed: no ordinary SharedPreferences fallback is permitted.
 */
object GoogleAuthManagerFactory {
    private const val LEGACY_OAUTH_PREFS_NAME = "secure_google_oauth_prefs"

    @Volatile
    private var INSTANCE: GoogleAuthManager? = null

    fun getInstance(context: Context): GoogleAuthManager {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: run {
                val appContext = context.applicationContext
                val secureStore = SecureKeyValueStore(
                    context = appContext,
                    fileName = "secure_google_oauth_prefs.secure",
                    keyAlias = "VVF_SECURE_PREFS_GOOGLE_OAUTH_KEY"
                )
                // No raw OAuth credential can be authenticity-validated offline. Do not migrate
                // legacy access/refresh tokens into the current store; retire them fail-closed.
                appContext.deleteSharedPreferences(LEGACY_OAUTH_PREFS_NAME)
                GoogleAuthManager(secureStore).also { INSTANCE = it }
            }
        }
    }

    /**
     * Cloud execution code receives only the credential capability it requires. UI code continues
     * to obtain [GoogleAuthManager] when it must observe non-secret authentication state.
     */
    internal fun getTokenProvider(context: Context): OAuthTokenProvider = getInstance(context)
}
