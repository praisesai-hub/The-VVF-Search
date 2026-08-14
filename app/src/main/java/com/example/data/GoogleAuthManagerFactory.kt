package com.example.data

import android.content.Context
import com.example.security.LegacyEncryptedPreferencesMigration
import com.example.security.SecureKeyValueStore

/**
 * Creates the singleton Google OAuth session manager using the project-owned encrypted store.
 * Authentication state must fail closed: no ordinary SharedPreferences fallback is permitted.
 */
object GoogleAuthManagerFactory {

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
                LegacyEncryptedPreferencesMigration.migrateIfNeeded(
                    context = appContext,
                    legacyName = "secure_google_oauth_prefs",
                    target = secureStore,
                    keys = setOf(
                        GoogleAuthManager.KEY_ACCESS_TOKEN,
                        GoogleAuthManager.KEY_REFRESH_TOKEN,
                        GoogleAuthManager.KEY_EMAIL,
                        GoogleAuthManager.KEY_DISPLAY_NAME
                    )
                )
                GoogleAuthManager(secureStore).also { INSTANCE = it }
            }
        }
    }
}
