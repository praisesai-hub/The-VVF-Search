package com.example.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Owns the SQLCipher passphrase envelope. The generated random passphrase is
 * never exposed through UI state or ordinary SharedPreferences.
 */
class DatabasePassphraseProvider private constructor(
    private val store: StringKeyValueStore,
    private val random: SecureRandom,
    private val codec: PassphraseBase64Codec,
    @Suppress("UNUSED_PARAMETER") private val constructionMarker: Unit
) {
    constructor(context: Context) : this(
        store = SecureKeyValueStore(
            context = context,
            fileName = STORE_FILE_NAME,
            keyAlias = KEY_ALIAS
        ),
        random = SecureRandom(),
        codec = AndroidPassphraseBase64Codec,
        constructionMarker = Unit
    )

    internal constructor(store: StringKeyValueStore, random: SecureRandom = SecureRandom()) :
        this(store, random, JvmPassphraseBase64Codec, Unit)
    @Synchronized
    fun getOrCreate(): ByteArray {
        val existing = store.getString(PASSPHRASE_KEY)
        if (existing != null) return decodeExisting(existing)

        val passphrase = ByteArray(PASSPHRASE_BYTES).also(random::nextBytes)
        val encoded = codec.encode(passphrase)
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
        codec.decode(encoded).also { passphrase ->
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

private interface PassphraseBase64Codec {
    fun encode(value: ByteArray): String
    fun decode(value: String): ByteArray
}

private object AndroidPassphraseBase64Codec : PassphraseBase64Codec {
    override fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    override fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}

private object JvmPassphraseBase64Codec : PassphraseBase64Codec {
    override fun encode(value: ByteArray): String = java.util.Base64.getEncoder().encodeToString(value)

    override fun decode(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)
}
