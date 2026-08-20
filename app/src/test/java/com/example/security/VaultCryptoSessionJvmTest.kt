package com.example.security

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultCryptoSessionJvmTest {
    @Test
    fun rejectsNon256BitKeysAndDefensivelyCopiesAcceptedKeyMaterial() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultCryptoSession.fromKeyBytes(ByteArray(31))
        }

        val source = ByteArray(32) { 5 }
        val session = VaultCryptoSession.fromKeyBytes(source)
        source.fill(0)

        val exported = session.copyKeyBytes()
        assertEquals(32, exported.size)
        assertFalse(exported.all { it == 0.toByte() })
        exported.fill(0)
        assertFalse(session.copyKeyBytes().all { it == 0.toByte() })
        session.close()
    }

    @Test
    fun encryptsDecryptsAndRejectsTamperedCiphertext() {
        VaultCryptoSession.fromKeyBytes(ByteArray(32) { 11 }).use { session ->
            val plaintext = "private vault content".encodeToByteArray()
            val encrypted = session.encryptBytes(plaintext)

            assertArrayEquals(plaintext, session.decryptBytes(encrypted.ciphertext, encrypted.iv))

            val tampered = encrypted.ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            assertThrows(AEADBadTagException::class.java) {
                session.decryptBytes(tampered, encrypted.iv)
            }

            val cipher = session.getEncryptionCipher()
            val ciphertext = cipher.doFinal(plaintext)
            assertArrayEquals(plaintext, session.getDecryptionCipher(cipher.iv).doFinal(ciphertext))
        }
    }

    @Test
    fun closedSessionRejectsFutureKeyAndCipherOperations() {
        val session = VaultCryptoSession.fromKeyBytes(ByteArray(32) { 3 })
        session.close()
        session.close()

        assertThrows(IllegalStateException::class.java) { session.copyKeyBytes() }
        assertThrows(IllegalStateException::class.java) { session.encryptBytes(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { session.getEncryptionCipher() }
    }
}
