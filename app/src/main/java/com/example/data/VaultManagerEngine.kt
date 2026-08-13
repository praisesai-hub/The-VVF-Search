package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.security.KeystoreVaultManager

class VaultManagerEngine(
    private val context: Context,
    private val keystoreVaultManager: KeystoreVaultManager
) {
    private val vaultPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "vvf_vault_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize secure vault storage", e)
        }
    }

    fun hasVaultPin(): Boolean = vaultPrefs.getString("vault_pin_hash", null).orEmpty().isNotBlank()

    fun getStoredVaultPinHash(): String = vaultPrefs.getString("vault_pin_hash", "").orEmpty()

    fun initializeVaultPin(pin: String): Boolean {
        if (hasVaultPin() || pin.length != 4 || !pin.all(Char::isDigit)) return false
        val hash = keystoreVaultManager.hashPin(pin)
        return vaultPrefs.edit().putString("vault_pin_hash", hash).commit()
    }

    fun verifyVaultPin(inputPin: String, storedHash: String = ""): Boolean {
        val expectedHash = if (storedHash.isNotBlank()) storedHash else getStoredVaultPinHash()
        return expectedHash.isNotBlank() && keystoreVaultManager.verifyPin(inputPin, expectedHash)
    }

    fun changeVaultPin(oldPin: String, newPin: String): Boolean {
        if (verifyVaultPin(oldPin) && newPin.length == 4 && newPin.all(Char::isDigit)) {
            val newHash = keystoreVaultManager.hashPin(newPin)
            vaultPrefs.edit().putString("vault_pin_hash", newHash).commit()
            return true
        }
        return false
    }
}
