package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreVaultManager {
    companion object {
        private const val TAG = "KeystoreVaultManager"
        private const val KEY_ALIAS = "VVF_SMART_MANAGER_VAULT_KEY"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val PBKDF2_ITERATIONS = 210_000
    }

    private val keyStore: KeyStore? = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (e: Throwable) { null }

    init { ensureSecretKeyExists() }

    private fun ensureSecretKeyExists() {
        if (keyStore != null) {
            try {
                if (!keyStore.containsAlias(KEY_ALIAS)) {
                    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                    keyGenerator.init(spec)
                    keyGenerator.generateKey()
                }
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to access Android Keystore: ${e.message}")
            }
        }
        throw IllegalStateException("Android Keystore is unavailable; refusing to create a non-persistent vault key")
    }

    private fun getSecretKey(): SecretKey {
        ensureSecretKeyExists()
        if (keyStore != null) {
            try {
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error accessing Android Keystore entry: ${e.message}")
            }
        }
        throw IllegalStateException("No valid persistent Android Keystore key available")
    }

    data class EncryptedResult(val ciphertext: ByteArray, val iv: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is EncryptedResult && ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
        override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()
    }

    fun encryptBytes(data: ByteArray): EncryptedResult {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = getSecretKey()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return EncryptedResult(cipher.doFinal(data), cipher.iv)
    }

    fun getEncryptionCipher(): Cipher {
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getSecretKey()) }
    }

    fun getDecryptionCipher(iv: ByteArray): Cipher {
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        }
    }

    fun decryptBytes(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        return getDecryptionCipher(iv).doFinal(ciphertext)
    }

    /** PBKDF2-HMAC-SHA256 with a random salt and a deliberately expensive work factor. */
    fun hashPin(pin: String): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt, PBKDF2_ITERATIONS)
            ?: throw IllegalStateException("PBKDF2 derivation failed")
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = hash.joinToString("") { "%02x".format(it) }
        return "$PBKDF2_ITERATIONS:$saltHex:$hashHex"
    }

    fun verifyPin(inputPin: String, storedHash: String): Boolean {
        if (storedHash.isBlank()) return false
        val parts = storedHash.split(":")
        if (parts.size != 3) {
            val legacyHash = hashLegacySha256(inputPin, "VVF_SMART_MANAGER_SALT")
            return MessageDigest.isEqual(
                legacyHash.lowercase().toByteArray(Charsets.UTF_8),
                storedHash.lowercase().toByteArray(Charsets.UTF_8)
            )
        }
        val iterations = parts[0].toIntOrNull() ?: return false
        if (iterations < 10_000 || iterations > 2_000_000) return false
        val salt = hexToByteArray(parts[1]) ?: return false
        val expectedHash = hexToByteArray(parts[2]) ?: return false
        val computedHash = pbkdf2(inputPin, salt, iterations) ?: return false
        return MessageDigest.isEqual(computedHash, expectedHash)
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray? = try {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } catch (e: Exception) {
        Log.e(TAG, "PBKDF2 failed", e)
        null
    }

    private fun hashLegacySha256(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest("$salt:$pin".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun hexToByteArray(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return ByteArray(hex.length / 2).also { result ->
            for (i in result.indices) {
                val value = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
                result[i] = value.toByte()
            }
        }
    }
}
