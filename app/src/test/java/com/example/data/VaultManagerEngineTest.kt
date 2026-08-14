package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.security.KeystoreVaultManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
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
    }

    @Test
    fun initializeVaultPin_persistsHashAndReportsSuccessOnlyAfterCommit() {
        val prefs = CommitControlledPreferences(commitResult = true)
        every { keystore.hashPin("1234") } returns "derived-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertTrue(engine.initializeVaultPin("1234"))
        assertEquals("derived-hash", engine.getStoredVaultPinHash())
        assertTrue(engine.hasVaultPin())
        verify(exactly = 1) { keystore.hashPin("1234") }
    }

    @Test
    fun initializeVaultPin_failsClosedWhenDurableCommitFails() {
        val prefs = CommitControlledPreferences(commitResult = false)
        every { keystore.hashPin("1234") } returns "derived-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.initializeVaultPin("1234"))
        assertFalse(engine.hasVaultPin())
        assertEquals("", engine.getStoredVaultPinHash())
        verify(exactly = 1) { keystore.hashPin("1234") }
    }

    @Test
    fun changeVaultPin_returnsFalseAndPreservesExistingHashWhenCommitFails() {
        val prefs = CommitControlledPreferences(commitResult = false).apply {
            putPersistedValue("vault_pin_hash", "existing-hash")
        }
        every { keystore.verifyPin("1111", "existing-hash") } returns true
        every { keystore.hashPin("2222") } returns "new-hash"
        val engine = VaultManagerEngine(context, keystore, prefs)

        assertFalse(engine.changeVaultPin("1111", "2222"))
        assertEquals("existing-hash", engine.getStoredVaultPinHash())
        verify(exactly = 1) { keystore.verifyPin("1111", "existing-hash") }
        verify(exactly = 1) { keystore.hashPin("2222") }
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
