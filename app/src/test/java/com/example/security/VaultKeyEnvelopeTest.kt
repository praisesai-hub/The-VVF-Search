package com.example.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultKeyEnvelopeTest {
    @Test
    fun wrapAndUnwrap_acceptsEightCharacterCredential() {
        val dek = ByteArray(32) { it.toByte() }
        val wrapped = VaultKeyEnvelope.wrapWithPin(dek, "12345678")

        assertEquals(dek.toList(), VaultKeyEnvelope.unwrapWithPin(wrapped, "12345678").toList())
    }

    @Test
    fun wrap_rejectsObsoleteFourDigitCredential() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(ByteArray(32), "1234")
        }
    }

    @Test
    fun wrapRejectsInvalidDekAndCredentialPoliciesBeforeEncrypting() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(ByteArray(31), "12345678")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(ByteArray(32), "abcdefgh")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(ByteArray(32), "1234 5678")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(ByteArray(32), "1".repeat(129))
        }
    }

    @Test
    fun unwrapFailsClosedForWrongCredentialMalformedSaltAndTamperedCiphertext() {
        val wrapped = VaultKeyEnvelope.wrapWithPin(ByteArray(32) { 3 }, "12345678")

        assertThrows(Exception::class.java) {
            VaultKeyEnvelope.unwrapWithPin(wrapped, "87654321")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.unwrapWithPin(
                VaultKeyEnvelope.PinWrap(ByteArray(15), wrapped.iv, wrapped.ciphertext),
                "12345678"
            )
        }
        val tampered = wrapped.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertThrows(Exception::class.java) {
            VaultKeyEnvelope.unwrapWithPin(
                VaultKeyEnvelope.PinWrap(wrapped.salt, wrapped.iv, tampered),
                "12345678"
            )
        }
    }
}
