package com.example.security

import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreVaultManager(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    injectedKeyStore: KeyStore? = null,
    private val injectedKeyStorePort: VaultKeyStorePort? = null
) {
    companion object {
        private const val TAG = "KeystoreVaultManager"
        private const val DEFAULT_KEY_ALIAS = "VVF_SMART_MANAGER_VAULT_KEY"
        private const val BIOMETRIC_WRAP_KEY_ALIAS = "VVF_VAULT_BIOMETRIC_WRAP_KEY_V2"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val PBKDF2_ITERATIONS = 210_000
        private const val AES_KEY_SIZE_BYTES = 32

    }

    private val keyStorePort: VaultKeyStorePort? = injectedKeyStorePort
        ?: injectedKeyStore?.let(AndroidVaultKeyStorePort::fromKeyStore)
        ?: try {
        AndroidVaultKeyStorePort.open()
    } catch (e: Throwable) {
        Log.e(TAG, "Android Keystore unavailable", e)
        null
    }

    init { ensureSecretKeyExists() }

    private fun ensureSecretKeyExists() {
        val port = keyStorePort
        if (port != null) {
            try {
                if (!port.containsAlias(keyAlias)) port.createVaultKey(keyAlias)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to access Android Keystore: ${e.message}")
            }
        }
        throw IllegalStateException("Android Keystore is unavailable; refusing to create a non-persistent vault key")
    }

    private fun getSecretKey(alias: String): SecretKey {
        val port = checkNotNull(keyStorePort) { "Android Keystore is unavailable" }
        check(port.containsAlias(alias)) { "No Android Keystore key available for alias $alias" }
        return checkNotNull(port.getSecretKey(alias)) {
            "No valid persistent Android Keystore key available for alias $alias"
        }
    }

    private fun getLegacySecretKey(): SecretKey = try {
        ensureSecretKeyExists()
        getSecretKey(keyAlias)
    } catch (e: Exception) {
        Log.w(TAG, "Error accessing legacy vault key: ${e.message}")
        throw IllegalStateException("No valid persistent Android Keystore key available", e)
    }

    data class EncryptedResult(val ciphertext: ByteArray, val iv: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is EncryptedResult && ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
        override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()
    }

    /** Legacy encryption API retained only to migrate already-created vault records. */
    @Deprecated("Use VaultCryptoSession for new vault records")
    fun encryptBytes(data: ByteArray): EncryptedResult {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getLegacySecretKey())
        return EncryptedResult(cipher.doFinal(data), cipher.iv)
    }

    /** Legacy cipher factory retained only to read pre-V2 vault records during migration. */
    @Deprecated("Use VaultCryptoSession for new vault records")
    fun getEncryptionCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, getLegacySecretKey())
    }

    /** Legacy cipher factory retained only to read pre-V2 vault records during migration. */
    @Deprecated("Use VaultCryptoSession for new vault records")
    fun getDecryptionCipher(iv: ByteArray): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.DECRYPT_MODE, getLegacySecretKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
    }

    /** Legacy decrypt API retained only to read pre-V2 vault records during migration. */
    @Deprecated("Use VaultCryptoSession for new vault records")
    fun decryptBytes(ciphertext: ByteArray, iv: ByteArray): ByteArray = getDecryptionCipher(iv).doFinal(ciphertext)

    fun randomVaultDek(): ByteArray = ByteArray(AES_KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

    fun prepareBiometricEncryptionCipher(): Cipher {
        ensureBiometricWrapKeyExists()
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getSecretKey(BIOMETRIC_WRAP_KEY_ALIAS))
        }
    }

    fun prepareBiometricDecryptionCipher(iv: ByteArray): Cipher {
        ensureBiometricWrapKeyExists()
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getSecretKey(BIOMETRIC_WRAP_KEY_ALIAS), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        }
    }

    fun biometricWrapKeyExists(): Boolean = try {
        keyStorePort?.containsAlias(BIOMETRIC_WRAP_KEY_ALIAS) == true
    } catch (_: Exception) {
        false
    }

    fun deleteBiometricWrapKey() {
        keyStorePort?.let { if (it.containsAlias(BIOMETRIC_WRAP_KEY_ALIAS)) it.deleteKey(BIOMETRIC_WRAP_KEY_ALIAS) }
    }

    private fun ensureBiometricWrapKeyExists() {
        val port = checkNotNull(keyStorePort) { "Android Keystore is unavailable" }
        if (!port.containsAlias(BIOMETRIC_WRAP_KEY_ALIAS)) {
            port.createBiometricWrapKey(BIOMETRIC_WRAP_KEY_ALIAS)
        }
    }

    /** PBKDF2-HMAC-SHA256 with a random salt and a deliberately expensive work factor. */
    fun hashPin(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
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
