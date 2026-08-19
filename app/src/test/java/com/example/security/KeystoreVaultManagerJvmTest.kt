package com.example.security

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeystoreVaultManagerJvmTest {
    @Test
    fun pinHashingRemainsAvailableWithoutOpeningTheAndroidKeystore(): Unit {
        val manager = KeystoreVaultManager()

        val first = manager.hashPin("246810")
        val second = manager.hashPin("246810")

        assertNotEquals(first, second)
        assertTrue(manager.verifyPin("246810", first))
        assertTrue(manager.verifyPin("246810", second))
        assertFalse(manager.verifyPin("246811", first))
        assertEquals(210_000, first.substringBefore(':').toInt())
    }

    @Test
    fun legacyAndMalformedPinRecordsAreHandledFailClosed(): Unit {
        val manager = KeystoreVaultManager()
        val legacy = MessageDigest.getInstance("SHA-256")
            .digest("VVF_SMART_MANAGER_SALT:246810".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertTrue(manager.verifyPin("246810", legacy))
        assertFalse(manager.verifyPin("wrong", legacy))
        listOf(
            "",
            "not-a-record",
            "not-an-int:00:00",
            "9999:00:00",
            "2000001:00:00",
            "210000:0:00",
            "210000:gg:00",
            "210000:00:gg"
        ).forEach { malformed ->
            assertFalse(manager.verifyPin("246810", malformed))
        }
    }

    @Test
    fun keystoreBackedCryptoFailsClosedWhenAndroidKeystoreIsUnavailable(): Unit {
        val manager = KeystoreVaultManager()

        assertThrows(IllegalStateException::class.java) {
            manager.encryptBytes("not persisted in test runtime".toByteArray())
        }
        assertThrows(IllegalStateException::class.java) {
            manager.prepareBiometricEncryptionCipher()
        }
    }

    @Test
    fun randomDekAndEncryptedPayloadValueContractsAreStable(): Unit {
        val manager = KeystoreVaultManager()
        val firstDek = manager.randomVaultDek()
        val secondDek = manager.randomVaultDek()
        val expected = KeystoreVaultManager.EncryptedResult(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val equivalent = KeystoreVaultManager.EncryptedResult(byteArrayOf(1, 2), byteArrayOf(3, 4))

        assertEquals(32, firstDek.size)
        assertEquals(32, secondDek.size)
        assertFalse(firstDek.contentEquals(secondDek))
        assertArrayEquals(byteArrayOf(1, 2), expected.ciphertext)
        assertEquals(expected, equivalent)
        assertEquals(expected.hashCode(), equivalent.hashCode())
    }
}
