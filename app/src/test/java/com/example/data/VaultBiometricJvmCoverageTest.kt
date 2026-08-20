package com.example.data

import android.content.Context
import android.util.Base64
import androidx.biometric.BiometricPrompt
import com.example.security.KeystoreVaultManager
import com.example.security.StringKeyValueStore
import com.example.security.VaultKeyStorePort
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.Base64 as JavaBase64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VaultBiometricJvmCoverageTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), Base64.NO_WRAP) } answers {
            JavaBase64.getEncoder().encodeToString(firstArg())
        }
        every { Base64.decode(any<String>(), Base64.NO_WRAP) } answers {
            JavaBase64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    @Test
    fun biometricEnrollmentUnlockAndDisablePreserveOnlyWrappedSessionKeyMaterial() {
        val port = FakeVaultKeyStorePort()
        val manager = KeystoreVaultManager("vault-test-key", injectedKeyStorePort = port)
        val store = MapStore()
        val engine = VaultManagerEngine(context, manager, injectedVaultStore = store)

        assertTrue(engine.initializeVaultPin("12345678"))
        val pinSession = engine.unlockWithPin("12345678")
        val expectedDek = pinSession.copyKeyBytes()

        val enrollmentResult = authenticationResult(manager.prepareBiometricEncryptionCipher())
        assertTrue(engine.completeBiometricEnrollment(pinSession, enrollmentResult))
        assertTrue(engine.hasBiometricEnrollment)

        val unlockResult = authenticationResult(engine.prepareBiometricUnlockCipher())
        val unlockedSession = engine.completeBiometricUnlock(unlockResult)
        assertArrayEquals(expectedDek, unlockedSession.copyKeyBytes())
        assertEqualsLockoutReset(store)

        assertTrue(engine.disableBiometricEnrollment())
        assertFalse(engine.hasBiometricEnrollment)
        assertTrue(port.deletedAliases.contains(BIOMETRIC_ALIAS))

        expectedDek.fill(0)
        pinSession.close()
        unlockedSession.close()
    }

    @Test
    fun biometricPathsFailClosedWhenAuthenticatedCipherOrEnrollmentIsMissing() {
        val port = FakeVaultKeyStorePort()
        val manager = KeystoreVaultManager("vault-test-key", injectedKeyStorePort = port)
        val engine = VaultManagerEngine(context, manager, injectedVaultStore = MapStore())
        assertTrue(engine.initializeVaultPin("12345678"))
        val session = engine.unlockWithPin("12345678")

        val missingCrypto = mockk<BiometricPrompt.AuthenticationResult>()
        every { missingCrypto.cryptoObject } returns null

        assertFalse(engine.completeBiometricEnrollment(session, missingCrypto))
        assertThrows(IllegalStateException::class.java) { engine.prepareBiometricUnlockCipher() }
        assertThrows(SecurityException::class.java) { engine.completeBiometricUnlock(missingCrypto) }
        session.close()
    }

    @Test
    fun changeVaultPin_rewrapsTheSameDekAndRejectsThePreviousPin() {
        val manager = KeystoreVaultManager(
            "vault-test-key",
            injectedKeyStorePort = FakeVaultKeyStorePort()
        )
        val engine = VaultManagerEngine(context, manager, injectedVaultStore = MapStore())

        assertTrue(engine.initializeVaultPin("12345678"))
        val oldSession = engine.unlockWithPin("12345678")
        val expectedDek = oldSession.copyKeyBytes()

        assertTrue(engine.changeVaultPin("12345678", "87654321"))
        assertThrows(IllegalStateException::class.java) { engine.unlockWithPin("12345678") }

        val newSession = engine.unlockWithPin("87654321")
        assertArrayEquals(expectedDek, newSession.copyKeyBytes())

        expectedDek.fill(0)
        oldSession.close()
        newSession.close()
    }

    @Test
    fun legacyPinState_migratesToEnvelopeAndRemainsUnlockable() {
        val manager = KeystoreVaultManager(
            "vault-test-key",
            injectedKeyStorePort = FakeVaultKeyStorePort()
        )
        val store = MapStore()
        val oldPin = "12345678"
        assertTrue(store.commit(mapOf("vault_pin_hash" to manager.hashPin(oldPin))))
        val engine = VaultManagerEngine(context, manager, injectedVaultStore = store)

        val migratedSession = engine.unlockWithPin(oldPin)
        val expectedDek = migratedSession.copyKeyBytes()
        assertTrue(store.getString("vault_pin_wrap_ciphertext", "").orEmpty().isNotBlank())

        val reopenedSession = engine.unlockWithPin(oldPin)
        assertArrayEquals(expectedDek, reopenedSession.copyKeyBytes())

        expectedDek.fill(0)
        migratedSession.close()
        reopenedSession.close()
    }

    @Test
    fun changeVaultPin_failsClosedWhenStoredEnvelopeIsCorrupted() {
        val manager = KeystoreVaultManager(
            "vault-test-key",
            injectedKeyStorePort = FakeVaultKeyStorePort()
        )
        val store = MapStore()
        val engine = VaultManagerEngine(context, manager, injectedVaultStore = store)
        assertTrue(engine.initializeVaultPin("12345678"))
        assertTrue(store.commit(mapOf("vault_pin_wrap_ciphertext" to "not-base64")))

        assertFalse(engine.changeVaultPin("12345678", "87654321"))
        assertTrue(engine.verifyVaultPin("12345678"))
        assertFalse(engine.verifyVaultPin("87654321"))
    }

    private fun authenticationResult(cipher: javax.crypto.Cipher): BiometricPrompt.AuthenticationResult =
        mockk<BiometricPrompt.AuthenticationResult> {
            every { cryptoObject } returns BiometricPrompt.CryptoObject(cipher)
        }

    private fun assertEqualsLockoutReset(store: MapStore) {
        assertTrue(store.getString("vault_failed_attempts", null) == "0")
        assertTrue(store.getString("vault_locked_until_ms", null) == "0")
    }

    private class MapStore : StringKeyValueStore {
        private val values = mutableMapOf<String, String?>()

        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

        override fun commit(values: Map<String, String?>): Boolean {
            this.values.putAll(values)
            return true
        }
    }

    private class FakeVaultKeyStorePort : VaultKeyStorePort {
        private val aliases = mutableSetOf<String>()
        private val keys = mutableMapOf<String, SecretKey>()
        val deletedAliases = mutableListOf<String>()

        override fun containsAlias(alias: String): Boolean = aliases.contains(alias)

        override fun getSecretKey(alias: String): SecretKey? = keys[alias]

        override fun createVaultKey(alias: String) {
            aliases += alias
            keys[alias] = SecretKeySpec(ByteArray(32) { 11 }, "AES")
        }

        override fun createBiometricWrapKey(alias: String) {
            aliases += alias
            keys[alias] = SecretKeySpec(ByteArray(32) { 12 }, "AES")
        }

        override fun deleteKey(alias: String) {
            aliases -= alias
            keys.remove(alias)
            deletedAliases += alias
        }
    }

    private companion object {
        const val BIOMETRIC_ALIAS = "VVF_VAULT_BIOMETRIC_WRAP_KEY_V2"
    }
}
