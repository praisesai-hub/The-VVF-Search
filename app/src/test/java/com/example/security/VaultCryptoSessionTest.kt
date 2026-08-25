package com.example.security

import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultCryptoSessionTest {
    @Test
    fun fromKeyBytes_rejectsNonAes256Keys_andCopiesAcceptedKey() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultCryptoSession.fromKeyBytes(ByteArray(16))
        }

        val source = ByteArray(32) { it.toByte() }
        val session = VaultCryptoSession.fromKeyBytes(source)
        source[0] = 99

        assertNotEquals(source[0], session.copyKeyBytes()[0])
        session.close()
    }

    @Test
    fun encryptAndDecryptBytes_roundTripWithFreshGcmIv() {
        val session = VaultCryptoSession.fromKeyBytes(ByteArray(32) { (it + 1).toByte() })

        val encrypted = session.encryptBytes("private payload".toByteArray())
        val decrypted = session.decryptBytes(encrypted.ciphertext, encrypted.iv)

        assertArrayEquals("private payload".toByteArray(), decrypted)
        assertTrue(encrypted.iv.isNotEmpty())
        session.close()
    }

    @Test
    fun directCiphers_roundTripAndUseIndependentCipherInstances() {
        val session = VaultCryptoSession.fromKeyBytes(ByteArray(32) { (it + 7).toByte() })
        val encryptionCipher = session.getEncryptionCipher()
        val ciphertext = encryptionCipher.doFinal("cipher payload".toByteArray())

        val decryptionCipher = session.getDecryptionCipher(encryptionCipher.iv)
        assertArrayEquals("cipher payload".toByteArray(), decryptionCipher.doFinal(ciphertext))
        assertNotEquals(encryptionCipher, decryptionCipher)
        session.close()
    }

    @Test
    fun decryptBytes_rejectsTamperedCiphertext() {
        val session = VaultCryptoSession.fromKeyBytes(ByteArray(32) { (it + 11).toByte() })
        val encrypted = session.encryptBytes("tamper-evident".toByteArray())
        val tampered = encrypted.ciphertext.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        assertThrows(GeneralSecurityException::class.java) {
            session.decryptBytes(tampered, encrypted.iv)
        }
        session.close()
    }

    @Test
    fun close_zeroizesKeyAndMakesEveryOperationFailClosed() {
        val session = VaultCryptoSession.fromKeyBytes(ByteArray(32) { 42 })

        session.close()
        session.close()

        val keyBytes = session.javaClass.getDeclaredField("keyBytes").apply { isAccessible = true }
            .get(session) as ByteArray
        assertTrue(keyBytes.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { session.encryptBytes(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { session.decryptBytes(byteArrayOf(1), ByteArray(12)) }
        assertThrows(IllegalStateException::class.java) { session.getEncryptionCipher() }
        assertThrows(IllegalStateException::class.java) { session.getDecryptionCipher(ByteArray(12)) }
    }
}
