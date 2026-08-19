package com.example.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultKeyEnvelopeTest {
    @Test
    fun sixDigitPinWrap_roundTripsOnlyTheCorrectPin(): Unit {
        val dek = ByteArray(32).also(SecureRandom()::nextBytes)
        val wrap = VaultKeyEnvelope.wrapWithPin(dek, "246810")

        assertArrayEquals(dek, VaultKeyEnvelope.unwrapWithPin(wrap, "246810"))
        assertThrows(Exception::class.java) {
            VaultKeyEnvelope.unwrapWithPin(wrap, "246811")
        }
    }

    @Test
    fun newPinWrap_rejectsInvalidDekAndNonSixDigitPins(): Unit {
        val dek = ByteArray(32)

        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(ByteArray(31), "246810")
        }
        listOf("", "1234", "12345", "1234567", "abcdef", "12345a").forEach { pin ->
            assertThrows(IllegalArgumentException::class.java) {
                VaultKeyEnvelope.wrapWithPin(dek, pin)
            }
        }
    }

    @Test
    fun unwrap_rejectsMalformedSaltAndWrongSizedPlaintext(): Unit {
        val malformedSalt = VaultKeyEnvelope.PinWrap(
            salt = ByteArray(15),
            iv = ByteArray(12),
            ciphertext = ByteArray(16)
        )
        val shortDek = legacyWrap(ByteArray(16), "2468")

        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.unwrapWithPin(malformedSalt, "246810")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.unwrapLegacyV2WithPin(shortDek, "2468")
        }
    }

    @Test
    fun legacyFourDigitWrap_canOnlyBeReadThroughMigrationPath(): Unit {
        val dek = ByteArray(32).also(SecureRandom()::nextBytes)
        val legacy = legacyWrap(dek, "2468")

        assertArrayEquals(dek, VaultKeyEnvelope.unwrapLegacyV2WithPin(legacy, "2468"))
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.unwrapLegacyV2WithPin(legacy, "246810")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(dek, "2468")
        }
    }

    private fun legacyWrap(dek: ByteArray, pin: String): VaultKeyEnvelope.PinWrap {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 210_000, 256))
                .encoded,
            "AES"
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return VaultKeyEnvelope.PinWrap(salt, cipher.iv, cipher.doFinal(dek))
    }
}
