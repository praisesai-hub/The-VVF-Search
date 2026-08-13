package com.example.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Creates the singleton Google OAuth session manager using encrypted storage.
 *
 * Authentication state must fail closed: falling back to ordinary SharedPreferences
 * would persist OAuth credentials in plaintext and is not acceptable for production.
 */
object GoogleAuthManagerFactory {

    @Volatile
    private var INSTANCE: GoogleAuthManager? = null

    fun getInstance(context: Context): GoogleAuthManager {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: run {
                val appContext = context.applicationContext
                val securePrefs = try {
                    val masterKey = MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()

                    EncryptedSharedPreferences.create(
                        appContext,
                        "secure_google_oauth_prefs",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (e: Throwable) {
                    throw IllegalStateException("Failed to initialize secure storage for Google OAuth", e)
                }

                GoogleAuthManager(securePrefs).also { INSTANCE = it }
            }
        }
    }
}
