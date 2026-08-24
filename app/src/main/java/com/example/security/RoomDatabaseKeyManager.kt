package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owns the persistent Room/SQLCipher passphrase.
 *
 * The SQLCipher passphrase is random and never stored in plaintext. It is wrapped
 * by an AES-256-GCM key held by Android Keystore and the wrapped value is kept in
 * private app storage. Keystore failures are fatal; there is no plaintext or
 * in-memory database-key fallback.
 */
class RoomDatabaseKeyManager(context: Context) {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "VVF_SMART_MANAGER_ROOM_DB_WRAP_KEY_V1"
        private const val PREFS = "vvf_room_database_key"
        private const val IV = "iv"
        private const val CIPHERTEXT = "ciphertext"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256
        private const val DATABASE_KEY_BYTES = 32
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOrCreateKey(): ByteArray {
        ensureWrappingKey()
        val encodedIv = preferences.getString(IV, null)
        val encodedCiphertext = preferences.getString(CIPHERTEXT, null)
        if (encodedIv != null && encodedCiphertext != null) {
            return decrypt(
                Base64.decode(encodedIv, Base64.NO_WRAP),
                Base64.decode(encodedCiphertext, Base64.NO_WRAP)
            )
        }

        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val ciphertext = cipher.doFinal(databaseKey)
        check(
            preferences.edit()
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
        ) { "Unable to persist Room database key metadata" }
        return databaseKey
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        check(keyStore.containsAlias(KEY_ALIAS)) { "Room database wrapping key is missing" }
        return checkNotNull((keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey) {
            "Room database wrapping key is invalid"
        }
    }

    private fun ensureWrappingKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        generator.generateKey()
    }
}
