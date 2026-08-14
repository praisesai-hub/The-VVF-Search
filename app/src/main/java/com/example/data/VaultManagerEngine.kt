package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.security.KeystoreVaultManager
import com.example.security.LegacyEncryptedPreferencesMigration
import com.example.security.SecureKeyValueStore
import com.example.security.SharedPreferencesKeyValueStore
import com.example.security.StringKeyValueStore

class VaultManagerEngine(
    private val context: Context,
    private val keystoreVaultManager: KeystoreVaultManager,
    private val injectedVaultPrefs: SharedPreferences? = null,
    private val injectedVaultStore: StringKeyValueStore? = null
) {
    private val vaultStore: StringKeyValueStore by lazy {
        injectedVaultStore
            ?: injectedVaultPrefs?.let(::SharedPreferencesKeyValueStore)
            ?: createSecureVaultStore()
    }

    private fun createSecureVaultStore(): StringKeyValueStore {
        val store = SecureKeyValueStore(
            context = context,
            fileName = "vvf_vault_prefs.secure",
            keyAlias = "VVF_SECURE_PREFS_VAULT_KEY"
        )
        LegacyEncryptedPreferencesMigration.migrateIfNeeded(
            context = context,
            legacyName = "vvf_vault_prefs",
            target = store,
            keys = setOf(VAULT_PIN_HASH_KEY)
        )
        return store
    }

    fun hasVaultPin(): Boolean = vaultStore.getString(VAULT_PIN_HASH_KEY, null).orEmpty().isNotBlank()

    fun getStoredVaultPinHash(): String = vaultStore.getString(VAULT_PIN_HASH_KEY, "").orEmpty()

    fun initializeVaultPin(pin: String): Boolean {
        if (hasVaultPin() || pin.length != 4 || !pin.all(Char::isDigit)) return false
        return persistVaultPinHash(keystoreVaultManager.hashPin(pin))
    }

    fun verifyVaultPin(inputPin: String, storedHash: String = ""): Boolean {
        val expectedHash = if (storedHash.isNotBlank()) storedHash else getStoredVaultPinHash()
        return expectedHash.isNotBlank() && keystoreVaultManager.verifyPin(inputPin, expectedHash)
    }

    fun changeVaultPin(oldPin: String, newPin: String): Boolean {
        if (!verifyVaultPin(oldPin) || newPin.length != 4 || !newPin.all(Char::isDigit)) return false
        return persistVaultPinHash(keystoreVaultManager.hashPin(newPin))
    }

    /** PIN updates must report durable storage failure and never silently fall back to plaintext. */
    private fun persistVaultPinHash(hash: String): Boolean =
        vaultStore.commit(mapOf(VAULT_PIN_HASH_KEY to hash))

    private companion object {
        const val VAULT_PIN_HASH_KEY = "vault_pin_hash"
    }
}
