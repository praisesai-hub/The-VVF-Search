package com.example.security

import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultKeyEnvelopeTest {
    @Test
    fun wrapAndUnwrapWithPin_roundTripsAes256Dek() {
        val dek = ByteArray(32) { (it + 1).toByte() }

        val wrapped = VaultKeyEnvelope.wrapWithPin(dek, "12345678")
        val restored = VaultKeyEnvelope.unwrapWithPin(wrapped, "12345678")

        assertArrayEquals(dek, restored)
    }

    @Test
    fun unwrapWithPin_rejectsWrongPinWithoutReturningPlaintext() {
        val wrapped = VaultKeyEnvelope.wrapWithPin(ByteArray(32) { 7 }, "12345678")

        assertThrows(GeneralSecurityException::class.java) {
            VaultKeyEnvelope.unwrapWithPin(wrapped, "87654321")
        }
    }

    @Test
    fun wrapWithPin_rejectsInvalidDekAndInvalidPin() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(ByteArray(16), "12345678")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(ByteArray(32), "123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(ByteArray(32), "12a45678")
        }
    }

    @Test
    fun unwrapWithPin_rejectsMalformedSaltBeforeDecrypting() {
        val wrapped = VaultKeyEnvelope.wrapWithPin(ByteArray(32) { 3 }, "12345678")
        val malformed = wrapped.copy(salt = ByteArray(15))

        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.unwrapWithPin(malformed, "12345678")
        }
    }
}
