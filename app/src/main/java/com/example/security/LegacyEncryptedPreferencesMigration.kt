@file:Suppress("DEPRECATION")

package com.example.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * One-way migration reader for the deprecated EncryptedSharedPreferences format.
 * The legacy file is retained for rollback evidence; the new store becomes authoritative.
 */
object LegacyEncryptedPreferencesMigration {
    fun migrateIfNeeded(
        context: Context,
        legacyName: String,
        target: SecureKeyValueStore,
        keys: Set<String>
    ) {
        if (target.containsStoreFile()) return
        val legacyFile = File(context.applicationInfo.dataDir, "shared_prefs/$legacyName.xml")
        if (!legacyFile.isFile) return

        val legacyPreferences = try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                legacyName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: IOException) {
            legacyReadFailure(e)
        } catch (e: GeneralSecurityException) {
            legacyReadFailure(e)
        } catch (e: IllegalArgumentException) {
            legacyReadFailure(e)
        } catch (e: IllegalStateException) {
            legacyReadFailure(e)
        }

        val values = keys.mapNotNull { key ->
            legacyPreferences.getString(key, null)?.let { key to it }
        }.toMap()
        check(target.commit(values)) {
            "Legacy secure preference migration was not durable"
        }
    }

    private fun legacyReadFailure(cause: Throwable): Nothing =
        throw IllegalStateException("Unable to read legacy encrypted preferences", cause)

    /** Testable entry point for the same allow-list and durable-write semantics. */
    fun migrateEntries(
        target: StringKeyValueStore,
        legacyEntries: Map<String, String?>,
        keys: Set<String>
    ): Boolean {
        val values = legacyEntries.filterKeys(keys::contains).filterValues { it != null }
        return target.commit(values)
    }
}
