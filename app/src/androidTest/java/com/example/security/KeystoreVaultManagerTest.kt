package com.example.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class KeystoreVaultManagerTest {

    @Test
    fun testHashPinVerification() {
        val manager = KeystoreVaultManager()
        val pin = "1234"
        val hash = manager.hashPin(pin)
        
        assertTrue(manager.verifyPin(pin, hash))
        assertFalse(manager.verifyPin("4321", hash))
    }

    @Test
    fun testRandomizedSaltGeneratesDistinctHashesForSamePin() {
        val manager = KeystoreVaultManager()
        val pin = "1234"
        val hash1 = manager.hashPin(pin)
        val hash2 = manager.hashPin(pin)
        
        assertNotEquals(hash1, hash2)
        assertTrue(manager.verifyPin(pin, hash1))
        assertTrue(manager.verifyPin(pin, hash2))
    }

    @Test
    fun testLegacySha256FallbackCompatibility() {
        val manager = KeystoreVaultManager()
        val pin = "1234"
        // Generate a legacy SHA-256 hash: digest of combined "VVF_SMART_MANAGER_SALT:1234"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val combined = "VVF_SMART_MANAGER_SALT:1234".toByteArray(Charsets.UTF_8)
        val legacyHash = digest.digest(combined).joinToString("") { "%02x".format(it) }

        // verifyPin should match the legacy SHA-256 hash correctly
        assertTrue(manager.verifyPin(pin, legacyHash))
        assertFalse(manager.verifyPin("4321", legacyHash))
    }

    @Test
    fun testEncryptAndDecryptBytes() {
        val manager = KeystoreVaultManager()
        val originalData = "Hello, secret vault!".toByteArray(Charsets.UTF_8)
        
        val encryptedResult = manager.encryptBytes(originalData)
        assertFalse(originalData.contentEquals(encryptedResult.ciphertext))
        
        val decryptedData = manager.decryptBytes(encryptedResult.ciphertext, encryptedResult.iv)
        assertArrayEquals(originalData, decryptedData)
    }

    @Test
    fun testTwoDifferentInstancesGiveDistinctHashesForSamePin() {
        val manager1 = KeystoreVaultManager()
        val manager2 = KeystoreVaultManager()
        val pin = "1234"
        val hash1 = manager1.hashPin(pin)
        val hash2 = manager2.hashPin(pin)
        
        assertNotEquals(hash1, hash2)
        
        assertTrue(manager1.verifyPin(pin, hash1))
        assertTrue(manager2.verifyPin(pin, hash2))
    }

    @Test
    fun testGeneratedSaltVerificationWorksAcrossInstancesPersistence() {
        val manager1 = KeystoreVaultManager()
        val pin = "1234"
        val hashFromInstance1 = manager1.hashPin(pin)
        
        val manager2 = KeystoreVaultManager()
        assertTrue(manager2.verifyPin(pin, hashFromInstance1))
        assertFalse(manager2.verifyPin("wrong_pin", hashFromInstance1))
    }

    @Test
    fun testPbkdf2IterationCountIsAtLeast10000() {
        val manager = KeystoreVaultManager()
        val hash = manager.hashPin("1234")
        val parts = hash.split(":")
        assertEquals(3, parts.size)
        
        val iterations = parts[0].toIntOrNull()
        assertNotNull(iterations)
        assertTrue("PBKDF2 iteration count must be at least 10000", iterations!! >= 10000)
    }

    @Test
    fun testMalformedPbkdf2RecordsFailClosed() {
        val manager = KeystoreVaultManager()

        assertFalse(manager.verifyPin("1234", ""))
        assertFalse(manager.verifyPin("1234", "not-a-pbkdf2-record"))
        assertFalse(manager.verifyPin("1234", "not-a-number:00:00"))
        assertFalse(manager.verifyPin("1234", "9999:00:00"))
        assertFalse(manager.verifyPin("1234", "2000001:00:00"))
        assertFalse(manager.verifyPin("1234", "210000:0:00"))
        assertFalse(manager.verifyPin("1234", "210000:gg:00"))
    }

    @Test
    fun testCipherFactoriesRoundTripAndTamperingFails() {
        val manager = KeystoreVaultManager()
        val original = "cipher factory payload".toByteArray(Charsets.UTF_8)

        val encryptionCipher = manager.getEncryptionCipher()
        val ciphertext = encryptionCipher.doFinal(original)
        val decryptionCipher = manager.getDecryptionCipher(encryptionCipher.iv)

        assertArrayEquals(original, decryptionCipher.doFinal(ciphertext))

        val tampered = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            manager.decryptBytes(tampered, encryptionCipher.iv)
            throw AssertionError("Tampered ciphertext must not decrypt successfully")
        } catch (_: javax.crypto.AEADBadTagException) {
            // Expected authenticated-encryption failure.
        }
    }

    @Test
    fun testBiometricWrapLifecycleAndRandomDekRemainFailClosed() {
        val manager = KeystoreVaultManager("VVF_TEST_BIOMETRIC_${System.nanoTime()}")
        assertEquals(32, manager.randomVaultDek().size)
        manager.deleteBiometricWrapKey()
        assertFalse(manager.biometricWrapKeyExists())

        val preparation = runCatching { manager.prepareBiometricEncryptionCipher() }
        if (preparation.isSuccess) {
            assertTrue(manager.biometricWrapKeyExists())
            assertTrue(preparation.getOrThrow().iv.isNotEmpty())
        } else {
            assertTrue(preparation.exceptionOrNull()?.message.orEmpty().isNotBlank())
        }
        manager.deleteBiometricWrapKey()
        assertFalse(manager.biometricWrapKeyExists())
    }

    @Test
    fun testEncryptedResultUsesContentEqualityAndHashing() {
        val first = KeystoreVaultManager.EncryptedResult(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val equivalent = KeystoreVaultManager.EncryptedResult(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val different = KeystoreVaultManager.EncryptedResult(byteArrayOf(9), byteArrayOf(4))

        assertEquals(first, equivalent)
        assertEquals(first.hashCode(), equivalent.hashCode())
        assertNotEquals(first, different)
    }
}
