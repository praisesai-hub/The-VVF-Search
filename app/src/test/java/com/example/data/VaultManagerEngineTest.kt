package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.security.KeystoreVaultManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
    fun lockoutState_sanitizesMalformedAndNegativePersistedValues() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_failed_attempts", "not-a-number")
            putPersistedValue("vault_locked_until_ms", "-1")
        }

        val state = VaultManagerEngine(context, keystore, prefs).getVaultLockoutState()

        assertEquals(0, state.failedAttempts)
        assertEquals(0L, state.lockedUntilMs)
    }

    @Test
    fun changeVaultPin_rejectsPersistedLockoutWithoutCheckingCredentials() {
        val prefs = CommitControlledPreferences(commitResult = true).apply {
            putPersistedValue("vault_pin_hash", "stored-hash")
            putPersistedValue("vault_failed_attempts", MAX_VAULT_FAILED_ATTEMPTS.toString())
            putPersistedValue("vault_locked_until_ms", "2000")
        }
        val engine = VaultManagerEngine(context, keystore, prefs, nowMs = { 1000L })

        assertFalse(engine.changeVaultPin("11111111", "22222222"))

        verify(exactly = 0) { keystore.verifyPin(any(), any()) }
        verify(exactly = 0) { keystore.hashPin(any()) }
    }

    @Test
    fun failedAuthentication_failsClosedWhenLockoutStateCannotBePersisted() {
        val prefs = CommitControlledPreferences(commitResult = false).apply {
            putPersistedValue("vault_pin_hash", "stored-hash")
        }
        every { keystore.verifyPin("00000000", "stored-hash") } returns false
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertThrows(IllegalStateException::class.java) {
            engine.unlockWithPin("00000000")
        }
    }

    @Test
    fun disableBiometricEnrollment_failsClosedWithoutDeletingKeyWhenCommitFails() {
        val prefs = CommitControlledPreferences(commitResult = false)
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.disableBiometricEnrollment())

        verify(exactly = 0) { keystore.deleteBiometricWrapKey() }
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
