package com.example.security

import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        val manager = KeystoreVaultManager(alias, injectedKeyStorePort = store)
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
        val manager = KeystoreVaultManager(alias, injectedKeyStorePort = availableStore())

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
        val manager = KeystoreVaultManager(alias, injectedKeyStorePort = store)

        assertTrue(manager.biometricWrapKeyExists())
        manager.deleteBiometricWrapKey()
        manager.deleteBiometricWrapKey()

        assertEquals(listOf("VVF_VAULT_BIOMETRIC_WRAP_KEY_V2"), store.deletedAliases)
    }

    @Test
    fun legacyKeyLookupFailureLogsDiagnosticsAndFailsClosed() {
        val store = availableStore()
        val diagnostics = mutableListOf<String>()
        val manager = KeystoreVaultManager(
            alias,
            injectedKeyStorePort = store,
            diagnosticLogger = { message, _ -> diagnostics += message }
        )
        store.throwOnLegacyAliasLookup = true

        val failure = runCatching { manager.getEncryptionCipher() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            listOf("Failed to access Android Keystore", "Error accessing legacy vault key"),
            diagnostics
        )
    }

    @Test
    fun provisionsMissingLegacyAndBiometricKeysThroughThePort() {
        val store = availableStore().apply { aliases.clear() }
        val manager = KeystoreVaultManager(alias, injectedKeyStorePort = store)

        assertTrue(store.createdVaultAliases.contains(alias))
        manager.prepareBiometricEncryptionCipher()

        assertTrue(store.createdBiometricAliases.isNotEmpty())
    }

    @Test
    fun failsClosedWhenPortCannotProvideASecretKeyOrProvisionAKey() {
        val missingKeyStore = availableStore().apply { secretKeys.clear() }
        val manager = KeystoreVaultManager(
            alias,
            injectedKeyStorePort = missingKeyStore,
            diagnosticLogger = { _, _ -> }
        )
        val plaintext = "vault content".encodeToByteArray()

        val missingKey = runCatching { manager.encryptBytes(plaintext) }.exceptionOrNull()
        assertTrue(missingKey is IllegalStateException)

        val failingPort = availableStore().apply {
            aliases.clear()
            failVaultCreation = true
        }
        val unavailable = runCatching {
            KeystoreVaultManager(
                alias,
                injectedKeyStorePort = failingPort,
                diagnosticLogger = { _, _ -> }
            )
        }.exceptionOrNull()
        assertTrue(unavailable is IllegalStateException)
    }

    @Test
    fun acceptsValidLegacySha256PinHashWithoutTreatingMalformedPbkdf2AsLegacy() {
        val manager = KeystoreVaultManager(alias, injectedKeyStorePort = availableStore())
        val legacyHash = MessageDigest.getInstance("SHA-256")
            .digest("VVF_SMART_MANAGER_SALT:946281".toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertTrue(manager.verifyPin("946281", legacyHash))
        assertFalse(manager.verifyPin("946282", legacyHash))
        assertFalse(manager.verifyPin("946281", "210000:invalid:00"))
    }

    @Test
    fun biometricStatusAndProvisioningFailClosedWhenPortFails() {
        val lookupFailingStore = availableStore().apply {
            throwOnBiometricAliasLookup = true
        }
        val lookupFailingManager = KeystoreVaultManager(alias, injectedKeyStorePort = lookupFailingStore)
        assertFalse(lookupFailingManager.biometricWrapKeyExists())

        val provisioningFailingStore = availableStore().apply {
            aliases.remove("VVF_VAULT_BIOMETRIC_WRAP_KEY_V2")
            failBiometricCreation = true
        }
        val provisioningFailingManager = KeystoreVaultManager(
            alias,
            injectedKeyStorePort = provisioningFailingStore
        )

        val failure = runCatching { provisioningFailingManager.prepareBiometricEncryptionCipher() }
            .exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun encryptedResultUsesContentEqualityAndStableContentHashCode() {
        val first = KeystoreVaultManager.EncryptedResult(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val sameContent = KeystoreVaultManager.EncryptedResult(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val differentCiphertext = KeystoreVaultManager.EncryptedResult(byteArrayOf(9, 2), byteArrayOf(3, 4))
        val differentIv = KeystoreVaultManager.EncryptedResult(byteArrayOf(1, 2), byteArrayOf(9, 4))

        assertEquals(first, sameContent)
        assertEquals(first.hashCode(), sameContent.hashCode())
        assertNotEquals(first, differentCiphertext)
        assertNotEquals(first, differentIv)
        assertNotEquals(first, "not-an-encrypted-result")
    }

    @Test
    fun pinVerificationRejectsUnsupportedIterationsAndMalformedHexSegments() {
        val manager = KeystoreVaultManager(alias, injectedKeyStorePort = availableStore())

        assertFalse(manager.verifyPin("946281", "9999:00:00"))
        assertFalse(manager.verifyPin("946281", "2000001:00:00"))
        assertFalse(manager.verifyPin("946281", "210000:0:00"))
        assertFalse(manager.verifyPin("946281", "210000:00:0"))
        assertFalse(manager.verifyPin("946281", "210000:00:gg"))
    }

    private fun availableStore(): FakeVaultKeyStorePort = FakeVaultKeyStorePort().apply {
        aliases += alias
        aliases += "VVF_VAULT_BIOMETRIC_WRAP_KEY_V2"
        secretKeys[alias] = secretKey
        secretKeys["VVF_VAULT_BIOMETRIC_WRAP_KEY_V2"] = secretKey
    }

    private class FakeVaultKeyStorePort : VaultKeyStorePort {
        val aliases = mutableSetOf<String>()
        val secretKeys = mutableMapOf<String, javax.crypto.SecretKey>()
        val createdVaultAliases = mutableListOf<String>()
        val createdBiometricAliases = mutableListOf<String>()
        val deletedAliases = mutableListOf<String>()
        var failVaultCreation = false
        var failBiometricCreation = false
        var throwOnBiometricAliasLookup = false
        var throwOnLegacyAliasLookup = false

        override fun containsAlias(alias: String): Boolean {
            if (throwOnLegacyAliasLookup && alias == "unit-test-vault-key") {
                error("test legacy lookup failure")
            }
            if (throwOnBiometricAliasLookup && alias == "VVF_VAULT_BIOMETRIC_WRAP_KEY_V2") {
                error("test biometric lookup failure")
            }
            return aliases.contains(alias)
        }

        override fun getSecretKey(alias: String): javax.crypto.SecretKey? = secretKeys[alias]

        override fun createVaultKey(alias: String) {
            if (failVaultCreation) error("test key provisioning failed")
            aliases += alias
            secretKeys[alias] = SecretKeySpec(ByteArray(32) { 4 }, "AES")
            createdVaultAliases += alias
        }

        override fun createBiometricWrapKey(alias: String) {
            if (failBiometricCreation) error("test biometric provisioning failure")
            aliases += alias
            secretKeys[alias] = SecretKeySpec(ByteArray(32) { 5 }, "AES")
            createdBiometricAliases += alias
        }

        override fun deleteKey(alias: String) {
            aliases -= alias
            secretKeys.remove(alias)
            deletedAliases += alias
        }
    }
}
