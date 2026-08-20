package com.example.security

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreVaultManagerJvmTest {
    private val alias = "unit-test-vault-key"
    private val secretKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")

    @Test
    fun encryptsDecryptsAndPreparesLegacyAndBiometricCiphersFromPersistentKeyMaterial() {
        val store = availableStore()
        val manager = KeystoreVaultManager(alias, store)
        val plaintext = "vault content".encodeToByteArray()

        val encrypted = manager.encryptBytes(plaintext)

        assertFalse(encrypted.ciphertext.contentEquals(plaintext))
        assertArrayEquals(plaintext, manager.decryptBytes(encrypted.ciphertext, encrypted.iv))
        val legacyEncryptionCipher = manager.getEncryptionCipher()
        val legacyCiphertext = legacyEncryptionCipher.doFinal(plaintext)
        assertArrayEquals(
            plaintext,
            manager.getDecryptionCipher(legacyEncryptionCipher.iv).doFinal(legacyCiphertext)
        )

        val biometricCipher = manager.prepareBiometricEncryptionCipher()
        val biometricEncrypted = biometricCipher.doFinal(plaintext)
        assertArrayEquals(
            plaintext,
            manager.prepareBiometricDecryptionCipher(biometricCipher.iv).doFinal(biometricEncrypted)
        )
    }

    @Test
    fun createsHighEntropyDeksVerifiesPbkdf2PinsAndRejectsMalformedHashes() {
        val manager = KeystoreVaultManager(alias, availableStore())

        val firstDek = manager.randomVaultDek()
        val secondDek = manager.randomVaultDek()
        val storedHash = manager.hashPin("946281")

        assertTrue(firstDek.size == 32)
        assertTrue(secondDek.size == 32)
        assertNotEquals(firstDek.toList(), secondDek.toList())
        assertTrue(manager.verifyPin("946281", storedHash))
        assertFalse(manager.verifyPin("946282", storedHash))
        assertFalse(manager.verifyPin("946281", ""))
        assertFalse(manager.verifyPin("946281", "bad:hash"))
        assertFalse(manager.verifyPin("946281", "999:00:00"))
        assertFalse(manager.verifyPin("946281", "210000:zz:00"))
    }

    @Test
    fun reportsAndDeletesBiometricWrapKeyUsingTheProvidedPersistentStore() {
        val store = availableStore()
        val manager = KeystoreVaultManager(alias, store)

        assertTrue(manager.biometricWrapKeyExists())
        manager.deleteBiometricWrapKey()

        verify { store.deleteEntry(any()) }
    }

    private fun availableStore(): KeyStore = mockk<KeyStore> {
        every { containsAlias(any()) } returns true
        every { getEntry(any(), any()) } returns KeyStore.SecretKeyEntry(secretKey)
        every { deleteEntry(any()) } just Runs
    }
}
