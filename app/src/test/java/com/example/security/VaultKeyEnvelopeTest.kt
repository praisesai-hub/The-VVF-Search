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
}
