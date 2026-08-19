package com.example.security

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultCryptoSessionTest {
    @Test
    fun sessionCopiesInputKeyAndRoundTripsAesGcmPayload(): Unit {
        val sourceKey = ByteArray(32) { it.toByte() }
        val session = VaultCryptoSession.fromKeyBytes(sourceKey)
        sourceKey.fill(0)
        val plaintext = "vault content".toByteArray(StandardCharsets.UTF_8)

        val encrypted = session.encryptBytes(plaintext)

        assertNotEquals(String(plaintext, StandardCharsets.UTF_8), String(encrypted.ciphertext, StandardCharsets.UTF_8))
        assertArrayEquals(plaintext, session.decryptBytes(encrypted.ciphertext, encrypted.iv))
        assertArrayEquals(ByteArray(32) { it.toByte() }, session.copyKeyBytes())
        session.close()
    }

    @Test
    fun cipherFactoriesSupportStreamingAndInvalidIvFailsClosed(): Unit {
        val session = VaultCryptoSession.fromKeyBytes(ByteArray(32) { 7 })
        val plaintext = "streamed vault content".toByteArray(StandardCharsets.UTF_8)
        val encryption = session.getEncryptionCipher()
        val ciphertext = encryption.doFinal(plaintext)

        assertArrayEquals(plaintext, session.getDecryptionCipher(encryption.iv).doFinal(ciphertext))
        assertThrows(IllegalArgumentException::class.java) {
            session.getDecryptionCipher(ByteArray(2))
        }
        session.close()
    }

    @Test
    fun invalidKeyLengthAndClosedSessionOperationsAreRejected(): Unit {
        assertThrows(IllegalArgumentException::class.java) {
            VaultCryptoSession.fromKeyBytes(ByteArray(31))
        }
        val session = VaultCryptoSession.fromKeyBytes(ByteArray(32) { 3 })
        session.close()
        session.close()

        assertThrows(IllegalStateException::class.java) { session.copyKeyBytes() }
        assertThrows(IllegalStateException::class.java) { session.getEncryptionCipher() }
        assertThrows(IllegalStateException::class.java) { session.encryptBytes(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { session.decryptBytes(byteArrayOf(1), ByteArray(12)) }
        assertFalse(session.copyKeyBytesOrNull())
    }

    private fun VaultCryptoSession.copyKeyBytesOrNull(): Boolean = try {
        copyKeyBytes()
        true
    } catch (_: IllegalStateException) {
        false
    }
}
