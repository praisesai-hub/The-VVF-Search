package com.example.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object VaultKeyEnvelope {
    const val VERSION = 2
    const val PBKDF2_ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val GCM_TAG_LENGTH = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES_ALGORITHM = "AES"
    private const val DEK_BYTES = 32
    private const val MIN_PIN_LENGTH = 8
    private const val MAX_PIN_LENGTH = 128
    private const val DERIVED_KEY_BITS = DEK_BYTES * 8

    data class PinWrap(val salt: ByteArray, val iv: ByteArray, val ciphertext: ByteArray)

    fun wrapWithPin(dek: ByteArray, pin: String): PinWrap {
        require(dek.size == DEK_BYTES) { "Vault DEK must be 256 bits" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, derivePinKey(pin, salt))
        return PinWrap(salt, cipher.iv, cipher.doFinal(dek))
    }

    fun unwrapWithPin(wrap: PinWrap, pin: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            derivePinKey(pin, wrap.salt),
            GCMParameterSpec(GCM_TAG_LENGTH, wrap.iv)
        )
        return cipher.doFinal(wrap.ciphertext).also {
            require(it.size == DEK_BYTES) { "Unwrapped vault DEK has invalid length" }
        }
    }

    private fun derivePinKey(pin: String, salt: ByteArray): SecretKeySpec {
        require(
            pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH &&
                pin.none(Char::isWhitespace) &&
                pin.any(Char::isDigit)
        ) {
            "Vault credential must be 8 to 128 non-whitespace characters and contain a digit"
        }
        require(salt.size == SALT_BYTES) { "Invalid PIN-wrap salt" }
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, DERIVED_KEY_BITS)
        return SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            AES_ALGORITHM
        )
    }
}
