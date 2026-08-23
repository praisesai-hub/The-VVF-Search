package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.security.KeystoreVaultManager
import com.example.security.VaultKeyEnvelope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
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
        every { keystore.hashPin("12345678") } returns "derived-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.initializeVaultPin("12345678"))
        assertEquals("derived-hash", engine.getStoredVaultPinHash())
        assertTrue(engine.hasVaultPin())
        verify(exactly = 1) { keystore.hashPin("12345678") }
    }

    @Test
    fun initializeVaultPin_failsClosedWhenDurableCommitFails() {
        val prefs = CommitControlledPreferences(commitResult = false)
        every { keystore.hashPin("12345678") } returns "derived-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.initializeVaultPin("12345678"))
        assertFalse(engine.hasVaultPin())
        assertEquals("", engine.getStoredVaultPinHash())
        verify(exactly = 1) { keystore.hashPin("12345678") }
    }

    @Test
    fun changeVaultPin_returnsFalseAndPreservesExistingHashWhenCommitFails() {
        val prefs = CommitControlledPreferences(commitResult = false).apply {
            putPersistedValue("vault_pin_hash", "existing-hash")
        }
        every { keystore.verifyPin("11111111", "existing-hash") } returns true
        every { keystore.hashPin("22222222") } returns "new-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.changeVaultPin("11111111", "22222222"))
        assertEquals("existing-hash", engine.getStoredVaultPinHash())
        verify(exactly = 1) { keystore.verifyPin("11111111", "existing-hash") }
        verify(exactly = 0) { keystore.hashPin("22222222") }
    }

    @Test
    fun changeVaultPin_rejectsInvalidNewPinBeforeDerivingOrPersistingHash() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "existing-hash")
        }
        every { keystore.verifyPin("11111111", "existing-hash") } returns true
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.changeVaultPin("11111111", "bad"))
        assertEquals("existing-hash", engine.getStoredVaultPinHash())
        verify(exactly = 0) { keystore.hashPin(any()) }
    }

    @Test
    fun initializeVaultPin_rejectsInvalidAndDuplicatePinsBeforeHashing() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "existing-hash")
        }
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.initializeVaultPin("12345678"))
        assertFalse(engine.initializeVaultPin("123"))
        assertFalse(engine.initializeVaultPin("12a4"))
        assertFalse(engine.initializeVaultPin("1234567"))
        assertEquals("existing-hash", engine.getStoredVaultPinHash())
        verify(exactly = 0) { keystore.hashPin(any()) }
    }

    @Test
    fun verifyVaultPin_failsClosedWithoutStoredHashAndAcceptsVerifiedStoredHash() {
        val emptyEngine = VaultManagerEngine(context, keystore, CommitControlledPreferences(commitResult = true))
        assertFalse(emptyEngine.verifyVaultPin("12345678"))

        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "stored-hash")
        }
        every { keystore.verifyPin("12345678", "stored-hash") } returns true
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.verifyVaultPin("12345678"))
        verify(exactly = 1) { keystore.verifyPin("12345678", "stored-hash") }
    }

    @Test
    fun verifyVaultPin_legacySha256HashIsUpgradedAfterSuccessfulDurableCommit() {
        val pin = "12345678"
        val legacyHash = legacySha256(pin)
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", legacyHash)
        }
        every { keystore.verifyPin(pin, legacyHash) } returns true
        every { keystore.hashPin(pin) } returns "210000:0011:2233"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.verifyVaultPin(pin))
        assertEquals("210000:0011:2233", engine.getStoredVaultPinHash())
        verify(exactly = 1) { keystore.hashPin(pin) }
    }

    @Test
    fun verifyVaultPin_legacySha256HashFailsClosedWhenUpgradeCommitFails() {
        val pin = "12345678"
        val legacyHash = legacySha256(pin)
        val prefs = CommitControlledPreferences(commitResult = false).apply {
            putPersistedValue("vault_pin_hash", legacyHash)
        }
        every { keystore.verifyPin(pin, legacyHash) } returns true
        every { keystore.hashPin(pin) } returns "210000:0011:2233"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.verifyVaultPin(pin))
        assertEquals(legacyHash, engine.getStoredVaultPinHash())
        verify(exactly = 1) { keystore.hashPin(pin) }
    }

    @Test
    fun changeVaultPin_failsWhenOldPinDoesNotVerifyAndDoesNotDeriveNewHash() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "stored-hash")
        }
        every { keystore.verifyPin("11111111", "stored-hash") } returns false
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.changeVaultPin("11111111", "22222222"))
        assertEquals("stored-hash", engine.getStoredVaultPinHash())
        verify(exactly = 0) { keystore.hashPin(any()) }
    }

    @Test
    fun changeVaultPin_updatesHashOnlyAfterOldPinVerifiesAndCommitSucceeds() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "old-hash")
        }
        every { keystore.verifyPin("11111111", "old-hash") } returns true
        every { keystore.hashPin("22222222") } returns "new-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.changeVaultPin("11111111", "22222222"))
        assertEquals("new-hash", engine.getStoredVaultPinHash())
        verify(exactly = 1) { keystore.verifyPin("11111111", "old-hash") }
        verify(exactly = 1) { keystore.hashPin("22222222") }
    }

    @Test
    fun failedPinAttempts_persistLockoutAcrossEngineRecreationAndBackoff() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "stored-hash")
        }
        every { keystore.verifyPin("00000000", "stored-hash") } returns false
        var now = 1_000L
        val engine = VaultManagerEngine(context, keystore, prefs, nowMs = { now })

        repeat(MAX_VAULT_FAILED_ATTEMPTS) {
            assertThrows(IllegalStateException::class.java) {
                engine.unlockWithPin("00000000")
            }
        }
        assertEquals(MAX_VAULT_FAILED_ATTEMPTS, engine.getVaultLockoutState().failedAttempts)
        assertEquals(now + VAULT_BASE_LOCKOUT_MS, engine.getVaultLockoutState().lockedUntilMs)

        val reopened = VaultManagerEngine(context, keystore, prefs, nowMs = { now })
        assertThrows(VaultAuthenticationLockedOutException::class.java) {
            reopened.unlockWithPin("00000000")
        }

        now += VAULT_BASE_LOCKOUT_MS + 1L
        assertThrows(IllegalStateException::class.java) {
            reopened.unlockWithPin("00000000")
        }
        assertTrue(reopened.getVaultLockoutState().lockedUntilMs > now)
    }


    @Test
    fun unlockWithPin_existingEnvelope_returnsDekAndResetsFailedState(): Unit {
        val prefs = CommitControlledPreferences(commitResult = true)
        val pin = "12345678"
        val dek = ByteArray(32) { index -> (index + 7).toByte() }
        val wrapped = VaultKeyEnvelope.wrapWithPin(dek, pin)
        prefs.putPersistedValue("vault_pin_hash", "stored-hash")
        prefs.putPersistedValue("vault_pin_wrap_salt", java.util.Base64.getEncoder().encodeToString(wrapped.salt))
        prefs.putPersistedValue("vault_pin_wrap_iv", java.util.Base64.getEncoder().encodeToString(wrapped.iv))
        prefs.putPersistedValue("vault_pin_wrap_ciphertext", java.util.Base64.getEncoder().encodeToString(wrapped.ciphertext))
        prefs.putPersistedValue("vault_failed_attempts", "2")
        prefs.putPersistedValue("vault_locked_until_ms", "0")
        every { keystore.verifyPin(pin, "stored-hash") } returns true
        val engine = VaultManagerEngine(context, keystore, prefs, nowMs = { 1000L })

        val session = engine.unlockWithPin(pin)

        assertArrayEquals(dek, session.copyKeyBytes())
        assertEquals(0, engine.getVaultLockoutState().failedAttempts)
        assertEquals(0L, engine.getVaultLockoutState().lockedUntilMs)
        session.close()
    }

    @Test
    fun unlockWithPin_legacyHashMigratesEnvelopeBeforeReturningSession(): Unit {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "legacy-hash")
        }
        val pin = "87654321"
        val dek = ByteArray(32) { index -> (index + 11).toByte() }
        every { keystore.verifyPin(pin, "legacy-hash") } returns true
        every { keystore.randomVaultDek() } returns dek.copyOf()
        val engine = VaultManagerEngine(context, keystore, prefs)

        val session = engine.unlockWithPin(pin)

        assertArrayEquals(dek, session.copyKeyBytes())
        assertTrue(prefs.getString("vault_pin_wrap_salt", null).orEmpty().isNotBlank())
        assertTrue(prefs.getString("vault_pin_wrap_iv", null).orEmpty().isNotBlank())
        assertTrue(prefs.getString("vault_pin_wrap_ciphertext", null).orEmpty().isNotBlank())
        session.close()
    }

    @Test
    fun changeVaultPin_existingEnvelopeRewrapsSameDekAndPersistsNewHash(): Unit {
        val prefs = CommitControlledPreferences(commitResult = true)
        val oldPin = "11112222"
        val newPin = "33334444"
        val dek = ByteArray(32) { index -> (index + 19).toByte() }
        val wrapped = VaultKeyEnvelope.wrapWithPin(dek, oldPin)
        prefs.putPersistedValue("vault_pin_hash", "old-hash")
        prefs.putPersistedValue("vault_pin_wrap_salt", java.util.Base64.getEncoder().encodeToString(wrapped.salt))
        prefs.putPersistedValue("vault_pin_wrap_iv", java.util.Base64.getEncoder().encodeToString(wrapped.iv))
        prefs.putPersistedValue("vault_pin_wrap_ciphertext", java.util.Base64.getEncoder().encodeToString(wrapped.ciphertext))
        every { keystore.verifyPin(oldPin, "old-hash") } returns true
        every { keystore.hashPin(newPin) } returns "new-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.changeVaultPin(oldPin, newPin))

        assertEquals("new-hash", engine.getStoredVaultPinHash())
        val newWrap = VaultKeyEnvelope.PinWrap(
            salt = java.util.Base64.getDecoder().decode(prefs.getString("vault_pin_wrap_salt", "").orEmpty()),
            iv = java.util.Base64.getDecoder().decode(prefs.getString("vault_pin_wrap_iv", "").orEmpty()),
            ciphertext = java.util.Base64.getDecoder().decode(prefs.getString("vault_pin_wrap_ciphertext", "").orEmpty()),
        )
        assertArrayEquals(dek, VaultKeyEnvelope.unwrapWithPin(newWrap, newPin))
    }

    @Test
    fun malformedLockoutValues_failClosedToZeroState(): Unit {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_failed_attempts", "not-a-number")
            putPersistedValue("vault_locked_until_ms", "-5")
        }
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertEquals(0, engine.getVaultLockoutState().failedAttempts)
        assertEquals(0L, engine.getVaultLockoutState().lockedUntilMs)
    }

    @Test
    fun disableBiometricEnrollment_clearsStoredWrapAndDeletesKeystoreWrapOnlyAfterCommit(): Unit {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_biometric_wrap_iv", "iv")
            putPersistedValue("vault_biometric_wrap_ciphertext", "ciphertext")
        }
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.disableBiometricEnrollment())

        assertFalse(engine.hasBiometricEnrollment)
        verify(exactly = 1) { keystore.deleteBiometricWrapKey() }
    }

    @Test
    fun disableBiometricEnrollment_commitFailure_preservesEnrollmentAndKeystoreWrap(): Unit {
        val prefs = CommitControlledPreferences(commitResult = false).apply {
            putPersistedValue("vault_biometric_wrap_iv", "iv")
            putPersistedValue("vault_biometric_wrap_ciphertext", "ciphertext")
        }
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.disableBiometricEnrollment())

        assertTrue(engine.hasBiometricEnrollment)
        verify(exactly = 0) { keystore.deleteBiometricWrapKey() }
    }

    @Test
    fun changeVaultPin_corruptExistingEnvelope_failsClosedAndPreservesHash(): Unit {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "old-hash")
            putPersistedValue("vault_pin_wrap_salt", "not-base64")
            putPersistedValue("vault_pin_wrap_iv", "not-base64")
            putPersistedValue("vault_pin_wrap_ciphertext", "not-base64")
        }
        every { keystore.verifyPin("11112222", "old-hash") } returns true
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.changeVaultPin("11112222", "33334444"))
        assertEquals("old-hash", engine.getStoredVaultPinHash())
    }

    private fun legacySha256(pin: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("VVF_SMART_MANAGER_SALT:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
