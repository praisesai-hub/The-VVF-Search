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
    fun `test hash pin verification`() {
        val manager = KeystoreVaultManager()
        val pin = "1234"
        val hash = manager.hashPin(pin)
        
        assertTrue(manager.verifyPin(pin, hash))
        assertFalse(manager.verifyPin("4321", hash))
    }

    @Test
    fun `test randomized salt generates distinct hashes for same pin`() {
        val manager = KeystoreVaultManager()
        val pin = "1234"
        val hash1 = manager.hashPin(pin)
        val hash2 = manager.hashPin(pin)
        
        assertNotEquals(hash1, hash2)
        assertTrue(manager.verifyPin(pin, hash1))
        assertTrue(manager.verifyPin(pin, hash2))
    }

    @Test
    fun `test legacy SHA-256 fallback compatibility`() {
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
    fun `test encrypt and decrypt bytes`() {
        val manager = KeystoreVaultManager()
        val originalData = "Hello, secret vault!".toByteArray(Charsets.UTF_8)
        
        val encryptedResult = manager.encryptBytes(originalData)
        assertFalse(originalData.contentEquals(encryptedResult.ciphertext))
        
        val decryptedData = manager.decryptBytes(encryptedResult.ciphertext, encryptedResult.iv)
        assertArrayEquals(originalData, decryptedData)
    }

    @Test
    fun `test two different instances give distinct hashes for same pin`() {
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
    fun `test generated salt verification works across instances persistence`() {
        val manager1 = KeystoreVaultManager()
        val pin = "1234"
        val hashFromInstance1 = manager1.hashPin(pin)
        
        val manager2 = KeystoreVaultManager()
        assertTrue(manager2.verifyPin(pin, hashFromInstance1))
        assertFalse(manager2.verifyPin("wrong_pin", hashFromInstance1))
    }

    @Test
    fun `test pbkdf2 iteration count is at least 10000`() {
        val manager = KeystoreVaultManager()
        val hash = manager.hashPin("1234")
        val parts = hash.split(":")
        assertEquals(3, parts.size)
        
        val iterations = parts[0].toIntOrNull()
        assertNotNull(iterations)
        assertTrue("PBKDF2 iteration count must be at least 10000", iterations!! >= 10000)
    }
}
