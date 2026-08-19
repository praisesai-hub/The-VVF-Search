package com.example.security

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the SQLCipher passphrase envelope. The generated random passphrase is
 * never exposed through UI state or ordinary SharedPreferences.
 */
class DatabasePassphraseProvider(
    context: Context,
    private val store: StringKeyValueStore = SecureKeyValueStore(
        context = context,
        fileName = STORE_FILE_NAME,
        keyAlias = KEY_ALIAS
    ),
    private val random: SecureRandom = SecureRandom()
) {
    @Synchronized
    fun getOrCreate(): ByteArray {
        val existing = store.getString(PASSPHRASE_KEY)
        if (existing != null) return decodeExisting(existing)

        val passphrase = ByteArray(PASSPHRASE_BYTES).also(random::nextBytes)
        val encoded = Base64.getEncoder().encodeToString(passphrase)
        try {
            check(store.commit(mapOf(PASSPHRASE_KEY to encoded))) {
                "Unable to durably store database passphrase"
            }
            return passphrase
        } catch (error: IllegalStateException) {
            passphrase.fill(0)
            throw IllegalStateException("Unable to create encrypted database key", error)
        } catch (error: SecurityException) {
            passphrase.fill(0)
            throw IllegalStateException("Unable to create encrypted database key", error)
        }
    }

    private fun decodeExisting(encoded: String): ByteArray = try {
        Base64.getDecoder().decode(encoded).also { passphrase ->
            require(passphrase.size == PASSPHRASE_BYTES) { "Invalid database passphrase" }
        }
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("Unable to read encrypted database key", error)
    }

    private companion object {
        const val STORE_FILE_NAME = "database_passphrase.enc"
        const val KEY_ALIAS = "VVF_SMART_MANAGER_DATABASE_KEY_V1"
        const val PASSPHRASE_KEY = "sqlcipher_passphrase_v1"
        const val PASSPHRASE_BYTES = 32
    }
}
