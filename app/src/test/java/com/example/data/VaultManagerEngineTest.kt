package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import androidx.biometric.BiometricPrompt
import com.example.security.KeystoreVaultManager
import com.example.security.VaultCryptoSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VaultManagerEngineTest {
    private lateinit var context: Context
    private lateinit var keystore: KeystoreVaultManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        keystore = mockk(relaxed = true)
        every { keystore.randomVaultDek() } returns ByteArray(32) { index -> (index + 1).toByte() }
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), Base64.NO_WRAP) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg())
        }
        every { Base64.decode(any<String>(), Base64.NO_WRAP) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @org.junit.After
    fun tearDownBase64() {
        unmockkStatic(Base64::class)
    }

    @Test
    fun initializeVaultPin_persistsHashAndReportsSuccessOnlyAfterCommit() {
        val prefs = CommitControlledPreferences(commitResult = true)
        every { keystore.hashPin("123456") } returns "derived-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.initializeVaultPin("123456"))
        assertEquals("derived-hash", engine.getStoredVaultPinHash())
        assertTrue(engine.hasVaultPin())
        verify(exactly = 1) { keystore.hashPin("123456") }
    }

    @Test
    fun initializeVaultPin_failsClosedWhenDurableCommitFails() {
        val prefs = CommitControlledPreferences(commitResult = false)
        every { keystore.hashPin("123456") } returns "derived-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.initializeVaultPin("123456"))
        assertFalse(engine.hasVaultPin())
        assertEquals("", engine.getStoredVaultPinHash())
        verify(exactly = 1) { keystore.hashPin("123456") }
    }

    @Test
    fun changeVaultPin_returnsFalseAndPreservesExistingHashWhenCommitFails() {
        val prefs = CommitControlledPreferences(commitResult = false).apply {
            putPersistedValue("vault_pin_hash", "existing-hash")
        }
        every { keystore.verifyPin("1111", "existing-hash") } returns true
        every { keystore.hashPin("222222") } returns "new-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.changeVaultPin("1111", "222222"))
        assertEquals("existing-hash", engine.getStoredVaultPinHash())
        verify(exactly = 1) { keystore.verifyPin("1111", "existing-hash") }
        verify(exactly = 1) { keystore.hashPin("222222") }
    }

    @Test
    fun changeVaultPin_rejectsInvalidNewPinBeforeDerivingOrPersistingHash() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "existing-hash")
        }
        every { keystore.verifyPin("1111", "existing-hash") } returns true
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.changeVaultPin("1111", "bad"))
        assertEquals("existing-hash", engine.getStoredVaultPinHash())
        verify(exactly = 0) { keystore.hashPin(any()) }
    }

    @Test
    fun initializeVaultPin_rejectsInvalidAndDuplicatePinsBeforeHashing() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "existing-hash")
        }
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.initializeVaultPin("123456"))
        assertFalse(engine.initializeVaultPin("123"))
        assertFalse(engine.initializeVaultPin("12a4"))
        assertFalse(engine.initializeVaultPin("12345"))
        assertEquals("existing-hash", engine.getStoredVaultPinHash())
        verify(exactly = 0) { keystore.hashPin(any()) }
    }

    @Test
    fun verifyVaultPin_failsClosedWithoutStoredHashAndAcceptsVerifiedStoredHash() {
        val emptyEngine = VaultManagerEngine(context, keystore, CommitControlledPreferences(commitResult = true))
        assertFalse(emptyEngine.verifyVaultPin("123456"))

        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "stored-hash")
        }
        every { keystore.verifyPin("123456", "stored-hash") } returns true
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.verifyVaultPin("123456"))
        verify(exactly = 1) { keystore.verifyPin("123456", "stored-hash") }
    }

    @Test
    fun changeVaultPin_failsWhenOldPinDoesNotVerifyAndDoesNotDeriveNewHash() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "stored-hash")
        }
        every { keystore.verifyPin("1111", "stored-hash") } returns false
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.changeVaultPin("1111", "222222"))
        assertEquals("stored-hash", engine.getStoredVaultPinHash())
        verify(exactly = 0) { keystore.hashPin(any()) }
    }

    @Test
    fun changeVaultPin_updatesHashOnlyAfterOldPinVerifiesAndCommitSucceeds() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "old-hash")
        }
        every { keystore.verifyPin("1111", "old-hash") } returns true
        every { keystore.hashPin("222222") } returns "new-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.changeVaultPin("1111", "222222"))
        assertEquals("new-hash", engine.getStoredVaultPinHash())
        verify(exactly = 1) { keystore.verifyPin("1111", "old-hash") }
        verify(exactly = 1) { keystore.hashPin("222222") }
    }

    @Test
    fun unlockWithPin_persistsEscalatingBackoffAcrossEngineInstances() {
        var now = 1_000L
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "stored-hash")
            putPersistedValue("vault_envelope_version", "3")
        }
        every { keystore.verifyPin("000000", "stored-hash") } returns false
        val engine = VaultManagerEngine(context, keystore, prefs, currentTimeMillis = { now })

        repeat(5) { assertLockedAfterInvalidAttempt(engine) }
        assertEquals(5, engine.vaultPinLockoutStatus().failedAttempts)
        assertEquals(30_000L, engine.vaultPinLockoutStatus().remainingMs)

        now += 30_000L
        repeat(5) { index ->
            assertLockedAfterInvalidAttempt(engine)
            if (index < 4) now += engine.vaultPinLockoutStatus().remainingMs
        }
        assertEquals(10, engine.vaultPinLockoutStatus().failedAttempts)
        assertEquals(5 * 60_000L, engine.vaultPinLockoutStatus().remainingMs)

        now += 5 * 60_000L
        repeat(5) { index ->
            assertLockedAfterInvalidAttempt(engine)
            if (index < 4) now += engine.vaultPinLockoutStatus().remainingMs
        }
        assertEquals(15, engine.vaultPinLockoutStatus().failedAttempts)
        assertEquals(30 * 60_000L, engine.vaultPinLockoutStatus().remainingMs)

        val reopened = VaultManagerEngine(context, keystore, prefs, currentTimeMillis = { now })
        assertTrue(reopened.vaultPinLockoutStatus().isLocked)
        assertEquals(15, reopened.vaultPinLockoutStatus().failedAttempts)
    }

    @Test
    fun legacyFourDigitEnvelope_requiresUpgrade_thenChangesToSixDigits() {
        val oldPin = "2468"
        val newPin = "246810"
        val dek = ByteArray(32) { index -> (index + 1).toByte() }
        val legacyWrap = com.example.security.VaultKeyEnvelope.let { envelope ->
            val salt = ByteArray(16) { index -> (index + 9).toByte() }
            val encrypted = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.PBEKeySpec(oldPin.toCharArray(), salt, 210_000, 256)
            val key = javax.crypto.spec.SecretKeySpec(
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).encoded,
                "AES"
            )
            encrypted.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            Triple(salt, encrypted.iv, encrypted.doFinal(dek))
        }
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "legacy-hash")
            putPersistedValue("vault_envelope_version", "2")
            putPersistedValue("vault_pin_wrap_salt", java.util.Base64.getEncoder().encodeToString(legacyWrap.first))
            putPersistedValue("vault_pin_wrap_iv", java.util.Base64.getEncoder().encodeToString(legacyWrap.second))
            putPersistedValue(
                "vault_pin_wrap_ciphertext",
                java.util.Base64.getEncoder().encodeToString(legacyWrap.third)
            )
        }
        every { keystore.verifyPin(oldPin, "legacy-hash") } returns true
        every { keystore.hashPin(newPin) } returns "v3-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.requiresPinUpgrade())
        assertTrue(engine.changeVaultPin(oldPin, newPin))
        assertFalse(engine.requiresPinUpgrade())
        assertEquals("v3-hash", engine.getStoredVaultPinHash())
    }

    @Test
    fun prepareBiometricUnlockCipher_invalidatedKeyClearsEnrollmentAndRequiresReenrollment() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "v3-hash")
            putPersistedValue("vault_envelope_version", "3")
            putPersistedValue("vault_biometric_wrap_iv", "aXY=")
            putPersistedValue("vault_biometric_wrap_ciphertext", "Y2lwaGVydGV4dA==")
        }
        every { keystore.prepareBiometricDecryptionCipher(any()) } throws
            KeyPermanentlyInvalidatedException()
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertThrows(VaultBiometricReenrollmentRequiredException::class.java) {
            engine.prepareBiometricUnlockCipher()
        }

        assertFalse(engine.hasBiometricEnrollment)
        verify(exactly = 1) { keystore.deleteBiometricWrapKey() }
    }

    @Test
    fun unlockWithPin_rejectsLegacyEnvelopeBeforeCheckingCredentials() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "legacy-hash")
            putPersistedValue("vault_envelope_version", "2")
        }
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertThrows(VaultPinUpgradeRequiredException::class.java) {
            engine.unlockWithPin("2468")
        }

        verify(exactly = 0) { keystore.verifyPin(any(), any()) }
    }

    @Test
    fun unlockWithPin_failsClosedAndRecordsAttemptWhenEnvelopeCannotBeUnwrapped() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "stored-hash")
            putPersistedValue("vault_envelope_version", "3")
        }
        every { keystore.verifyPin("123456", "stored-hash") } returns true
        val engine = VaultManagerEngine(context, keystore, prefs, currentTimeMillis = { 1_000L })

        assertThrows(SecurityException::class.java) {
            engine.unlockWithPin("123456")
        }

        assertEquals(1, engine.vaultPinLockoutStatus(1_000L).failedAttempts)
    }

    @Test
    fun completeBiometricEnrollment_requiresAuthenticatedCryptoObject() {
        val result = mockk<BiometricPrompt.AuthenticationResult>()
        every { result.cryptoObject } returns null
        val engine = VaultManagerEngine(context, keystore, CommitControlledPreferences(commitResult = true))
        val session = VaultCryptoSession.fromKeyBytes(ByteArray(32) { 4 })

        assertFalse(engine.completeBiometricEnrollment(session, result))
        session.close()
    }

    @Test
    fun disableBiometricEnrollment_doesNotDeleteKeystoreKeyWhenCommitIsNotDurable() {
        val engine = VaultManagerEngine(context, keystore, CommitControlledPreferences(commitResult = false))

        assertFalse(engine.disableBiometricEnrollment())

        verify(exactly = 0) { keystore.deleteBiometricWrapKey() }
    }

    private fun assertLockedAfterInvalidAttempt(engine: VaultManagerEngine) {
        try {
            engine.unlockWithPin("000000")
        } catch (_: SecurityException) {
            return
        }
        throw AssertionError("Invalid vault PIN must never unlock a session")
    }

    private class CommitControlledPreferences(
        private val commitResult: Boolean
    ) : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        fun putPersistedValue(key: String, value: String) {
            values[key] = value
        }

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pendingValues = mutableMapOf<String, Any?>()
            private val pendingRemovals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = put(key, values)
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)

            private fun put(key: String, value: Any?): SharedPreferences.Editor {
                pendingValues[key] = value
                pendingRemovals.remove(key)
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                pendingValues.remove(key)
                pendingRemovals.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearRequested = true
                pendingValues.clear()
                pendingRemovals.clear()
                return this
            }

            override fun commit(): Boolean {
                if (!commitResult) return false
                persistPendingValues()
                return true
            }

            override fun apply() {
                persistPendingValues()
            }

            private fun persistPendingValues() {
                if (clearRequested) values.clear()
                pendingRemovals.forEach(values::remove)
                values.putAll(pendingValues)
            }
        }
    }
}
