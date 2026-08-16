package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.biometric.BiometricPrompt
import com.example.security.KeystoreVaultManager
import com.example.security.LegacyEncryptedPreferencesMigration
import com.example.security.SecureKeyValueStore
import com.example.security.SharedPreferencesKeyValueStore
import com.example.security.StringKeyValueStore
import com.example.security.VaultCryptoSession
import com.example.security.VaultKeyEnvelope
import javax.crypto.Cipher

private const val VAULT_PIN_HASH_KEY = "vault_pin_hash"
private const val ENVELOPE_VERSION_KEY = "vault_envelope_version"
private const val PIN_WRAP_SALT_KEY = "vault_pin_wrap_salt"
private const val PIN_WRAP_IV_KEY = "vault_pin_wrap_iv"
private const val PIN_WRAP_CIPHERTEXT_KEY = "vault_pin_wrap_ciphertext"
private const val BIOMETRIC_WRAP_IV_KEY = "vault_biometric_wrap_iv"
private const val BIOMETRIC_WRAP_CIPHERTEXT_KEY = "vault_biometric_wrap_ciphertext"

class VaultManagerEngine(
    private val context: Context,
    private val keystoreVaultManager: KeystoreVaultManager,
    private val injectedVaultPrefs: SharedPreferences? = null,
    private val injectedVaultStore: StringKeyValueStore? = null
) {
    private val vaultStore: StringKeyValueStore by lazy {
        injectedVaultStore
            ?: injectedVaultPrefs?.let(::SharedPreferencesKeyValueStore)
            ?: createSecureVaultStore(context)
    }

    fun hasVaultPin(): Boolean = stored(vaultStore, VAULT_PIN_HASH_KEY).isNotBlank()

    internal val storedVaultPinHash: String
        get() = stored(vaultStore, VAULT_PIN_HASH_KEY)

    fun initializeVaultPin(pin: String): Boolean {
        if (hasVaultPin() || pin.length != 4 || !pin.all(Char::isDigit)) return false
        val dek = keystoreVaultManager.randomVaultDek()
        val pinWrap = VaultKeyEnvelope.wrapWithPin(dek, pin)
        val values = mapOf(
            VAULT_PIN_HASH_KEY to keystoreVaultManager.hashPin(pin),
            PIN_WRAP_SALT_KEY to encode(pinWrap.salt),
            PIN_WRAP_IV_KEY to encode(pinWrap.iv),
            PIN_WRAP_CIPHERTEXT_KEY to encode(pinWrap.ciphertext),
            ENVELOPE_VERSION_KEY to VaultKeyEnvelope.VERSION.toString()
        )
        val committed = vaultStore.commit(values)
        dek.fill(0)
        return committed
    }

    fun verifyVaultPin(inputPin: String, storedHash: String = ""): Boolean {
        val expectedHash = if (storedHash.isNotBlank()) storedHash else getStoredVaultPinHash()
        return expectedHash.isNotBlank() && keystoreVaultManager.verifyPin(inputPin, expectedHash)
    }

    fun unlockWithPin(pin: String): VaultCryptoSession {
        check(verifyVaultPin(pin)) { "Invalid vault PIN" }
        val dek = if (hasPinEnvelope(vaultStore)) {
            unwrapPinDek(vaultStore, pin)
        } else {
            migrateLegacyPinToEnvelope(vaultStore, keystoreVaultManager, pin)
        }
        return VaultCryptoSession.fromKeyBytes(dek).also { dek.fill(0) }
    }

    fun changeVaultPin(oldPin: String, newPin: String): Boolean {
        val validInput = verifyVaultPin(oldPin) && newPin.length == 4 && newPin.all(Char::isDigit)
        if (!validInput) return false
        val currentDek = try {
            if (hasPinEnvelope(vaultStore)) {
                unwrapPinDek(vaultStore, oldPin)
            } else {
                migrateLegacyPinToEnvelope(vaultStore, keystoreVaultManager, oldPin)
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.security.GeneralSecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
        return currentDek?.let { dek ->
            try {
                val pinWrap = VaultKeyEnvelope.wrapWithPin(dek, newPin)
                vaultStore.commit(
                    mapOf(
                        VAULT_PIN_HASH_KEY to keystoreVaultManager.hashPin(newPin),
                        PIN_WRAP_SALT_KEY to encode(pinWrap.salt),
                        PIN_WRAP_IV_KEY to encode(pinWrap.iv),
                        PIN_WRAP_CIPHERTEXT_KEY to encode(pinWrap.ciphertext),
                        ENVELOPE_VERSION_KEY to VaultKeyEnvelope.VERSION.toString()
                    )
                )
            } finally {
                dek.fill(0)
            }
        } ?: false
    }

    val hasBiometricEnrollment: Boolean
        get() = stored(vaultStore, BIOMETRIC_WRAP_IV_KEY).isNotBlank() &&
            stored(vaultStore, BIOMETRIC_WRAP_CIPHERTEXT_KEY).isNotBlank()

    /** Must be called only after a PIN-created VaultCryptoSession exists. */
    fun prepareBiometricEnrollmentCipher(): Cipher =
        keystoreVaultManager.prepareBiometricEncryptionCipher()

    /** Completes enrollment using the cipher returned by BiometricPrompt.AuthenticationResult. */
    fun completeBiometricEnrollment(
        session: VaultCryptoSession,
        result: BiometricPrompt.AuthenticationResult
    ): Boolean {
        val cipher = result.cryptoObject?.cipher ?: return false
        val sessionKey = session.copyKeyBytes()
        val wrapped = try {
            cipher.doFinal(sessionKey)
        } finally {
            sessionKey.fill(0)
        }
        return vaultStore.commit(
            mapOf(
                BIOMETRIC_WRAP_IV_KEY to encode(cipher.iv),
                BIOMETRIC_WRAP_CIPHERTEXT_KEY to encode(wrapped),
                ENVELOPE_VERSION_KEY to VaultKeyEnvelope.VERSION.toString()
            )
        )
    }

    /** Prepares the decrypt cipher that must be passed into BiometricPrompt.CryptoObject. */
    fun prepareBiometricUnlockCipher(): Cipher {
        check(hasBiometricEnrollment) { "Biometric vault enrollment is unavailable" }
        return keystoreVaultManager.prepareBiometricDecryptionCipher(
            decode(stored(vaultStore, BIOMETRIC_WRAP_IV_KEY))
        )
    }

    /** Completes unlock only from an authenticated CryptoObject result. */
    fun completeBiometricUnlock(result: BiometricPrompt.AuthenticationResult): VaultCryptoSession {
        val cipher = result.cryptoObject?.cipher
            ?: throw SecurityException("Authenticated CryptoObject is required")
        val wrapped = decode(stored(vaultStore, BIOMETRIC_WRAP_CIPHERTEXT_KEY))
        val dek = cipher.doFinal(wrapped)
        return VaultCryptoSession.fromKeyBytes(dek).also { dek.fill(0) }
    }

    fun disableBiometricEnrollment(): Boolean {
        val committed = vaultStore.commit(
            mapOf(
                BIOMETRIC_WRAP_IV_KEY to "",
                BIOMETRIC_WRAP_CIPHERTEXT_KEY to ""
            )
        )
        if (committed) keystoreVaultManager.deleteBiometricWrapKey()
        return committed
    }
}

internal fun VaultManagerEngine.getStoredVaultPinHash(): String = storedVaultPinHash

private fun createSecureVaultStore(context: Context): StringKeyValueStore {
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

private fun hasPinEnvelope(store: StringKeyValueStore): Boolean =
    stored(store, PIN_WRAP_SALT_KEY).isNotBlank() &&
        stored(store, PIN_WRAP_IV_KEY).isNotBlank() &&
        stored(store, PIN_WRAP_CIPHERTEXT_KEY).isNotBlank()

private fun unwrapPinDek(store: StringKeyValueStore, pin: String): ByteArray {
    val salt = decode(stored(store, PIN_WRAP_SALT_KEY))
    val iv = decode(stored(store, PIN_WRAP_IV_KEY))
    val ciphertext = decode(stored(store, PIN_WRAP_CIPHERTEXT_KEY))
    return VaultKeyEnvelope.unwrapWithPin(VaultKeyEnvelope.PinWrap(salt, iv, ciphertext), pin)
}

private fun migrateLegacyPinToEnvelope(
    store: StringKeyValueStore,
    keystoreVaultManager: KeystoreVaultManager,
    pin: String
): ByteArray {
    val dek = keystoreVaultManager.randomVaultDek()
    val pinWrap = VaultKeyEnvelope.wrapWithPin(dek, pin)
    val committed = store.commit(
        mapOf(
            PIN_WRAP_SALT_KEY to encode(pinWrap.salt),
            PIN_WRAP_IV_KEY to encode(pinWrap.iv),
            PIN_WRAP_CIPHERTEXT_KEY to encode(pinWrap.ciphertext),
            ENVELOPE_VERSION_KEY to VaultKeyEnvelope.VERSION.toString()
        )
    )
    check(committed) {
        dek.fill(0)
        "Vault envelope migration was not durably committed"
    }
    return dek
}

private fun stored(store: StringKeyValueStore, key: String): String =
    store.getString(key, "").orEmpty()

private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

private fun decode(value: String): ByteArray {
    require(value.isNotBlank()) { "Missing vault envelope field" }
    return Base64.decode(value, Base64.NO_WRAP)
}
