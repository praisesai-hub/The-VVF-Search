package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
            Log.e("VaultManagerEngine", "EncryptedSharedPreferences init failed, falling back to standard SharedPreferences: ${e.message}")
            context.getSharedPreferences("vvf_vault_prefs", Context.MODE_PRIVATE)
        }
    }

    fun getStoredVaultPinHash(): String {
        val stored = vaultPrefs.getString("vault_pin_hash", null)
        if (stored != null) return stored
        val defaultHash = keystoreVaultManager.hashPin("1234")
        vaultPrefs.edit().putString("vault_pin_hash", defaultHash).commit()
        return defaultHash
    }

    fun verifyVaultPin(inputPin: String, storedHash: String = ""): Boolean {
        val expectedHash = if (storedHash.isNotBlank()) storedHash else getStoredVaultPinHash()
        return keystoreVaultManager.verifyPin(inputPin, expectedHash)
    }

    fun changeVaultPin(oldPin: String, newPin: String): Boolean {
        if (verifyVaultPin(oldPin) && newPin.length == 4) {
            val newHash = keystoreVaultManager.hashPin(newPin)
            vaultPrefs.edit().putString("vault_pin_hash", newHash).commit()
            return true
        }
        return false
    }
}
