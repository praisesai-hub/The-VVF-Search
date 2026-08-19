package com.example.security

import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Short-lived in-memory holder for the vault data-encryption key.
 * The key is created only after PIN or CryptoObject authentication succeeds.
 */
class VaultCryptoSession private constructor(private val keyBytes: ByteArray) : AutoCloseable {
    companion object {
        private const val AES_KEY_SIZE_BYTES = 32
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_SIZE_BYTES = 12

        fun fromKeyBytes(bytes: ByteArray): VaultCryptoSession {
            require(bytes.size == AES_KEY_SIZE_BYTES) { "Vault DEK must be 256 bits" }
            return VaultCryptoSession(bytes.copyOf())
        }
    }

    private val closed = AtomicBoolean(false)

    data class EncryptedResult(val ciphertext: ByteArray, val iv: ByteArray)

    fun encryptBytes(data: ByteArray): EncryptedResult {
        checkOpen()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, currentKey())
        return EncryptedResult(cipher.doFinal(data), cipher.iv)
    }

    fun decryptBytes(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        checkOpen()
        return getDecryptionCipher(iv).doFinal(ciphertext)
    }

    fun getEncryptionCipher(): Cipher {
        checkOpen()
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, currentKey())
        }
    }

    fun getDecryptionCipher(iv: ByteArray): Cipher {
        checkOpen()
        require(iv.size == GCM_IV_SIZE_BYTES) { "Vault AES-GCM IV must be 96 bits" }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, currentKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        }
    }

    /** Returns a copy for wrapping during biometric enrollment; never persist or log it. */
    fun copyKeyBytes(): ByteArray {
        checkOpen()
        return keyBytes.copyOf()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) keyBytes.fill(0)
    }

    private fun currentKey(): SecretKeySpec {
        checkOpen()
        return SecretKeySpec(keyBytes.copyOf(), "AES")
    }

    private fun checkOpen() {
        check(!closed.get()) { "Vault session is closed" }
    }
}
