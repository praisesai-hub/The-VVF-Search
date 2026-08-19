package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
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
private const val PIN_FAILED_ATTEMPTS_KEY = "vault_pin_failed_attempts"
private const val PIN_LOCKED_UNTIL_EPOCH_MS_KEY = "vault_pin_locked_until_epoch_ms"

private const val FIRST_LOCKOUT_ATTEMPT_THRESHOLD = 5
private const val SECOND_LOCKOUT_ATTEMPT_THRESHOLD = 10
private const val THIRD_LOCKOUT_ATTEMPT_THRESHOLD = 15
private const val FIRST_LOCKOUT_DURATION_MS = 30_000L
private const val SECOND_LOCKOUT_DURATION_MS = 5 * 60_000L
private const val THIRD_LOCKOUT_DURATION_MS = 30 * 60_000L

data class VaultPinLockoutStatus(
    val failedAttempts: Int,
    val lockedUntilEpochMs: Long,
    val remainingMs: Long
) {
    val isLocked: Boolean get() = remainingMs > 0L
}

class VaultPinLockedException(val remainingMs: Long) : SecurityException("Vault PIN is temporarily locked")
class VaultPinUpgradeRequiredException : SecurityException("Vault PIN must be upgraded to six digits")
class VaultBiometricReenrollmentRequiredException(cause: Throwable) :
    SecurityException("Vault biometrics changed and must be enrolled again after PIN unlock", cause)

@Suppress(
    "TooManyFunctions",
    "TooGenericExceptionCaught",
    "ReturnCount",
    "ThrowsCount"
)
class VaultManagerEngine(
    private val context: Context,
    private val keystoreVaultManager: KeystoreVaultManager,
    private val injectedVaultPrefs: SharedPreferences? = null,
    private val injectedVaultStore: StringKeyValueStore? = null,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
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
        if (hasVaultPin() || !isCurrentPin(pin)) return false
        val dek = keystoreVaultManager.randomVaultDek()
        val pinWrap = VaultKeyEnvelope.wrapWithPin(dek, pin)
        val committed = vaultStore.commit(
            mapOf(
                VAULT_PIN_HASH_KEY to keystoreVaultManager.hashPin(pin),
                PIN_WRAP_SALT_KEY to encode(pinWrap.salt),
                PIN_WRAP_IV_KEY to encode(pinWrap.iv),
                PIN_WRAP_CIPHERTEXT_KEY to encode(pinWrap.ciphertext),
                ENVELOPE_VERSION_KEY to VaultKeyEnvelope.VERSION.toString(),
                PIN_FAILED_ATTEMPTS_KEY to "0",
                PIN_LOCKED_UNTIL_EPOCH_MS_KEY to "0"
            )
        )
        dek.fill(0)
        return committed
    }

    fun verifyVaultPin(inputPin: String, storedHash: String = ""): Boolean {
        val expectedHash = if (storedHash.isNotBlank()) storedHash else getStoredVaultPinHash()
        return expectedHash.isNotBlank() && keystoreVaultManager.verifyPin(inputPin, expectedHash)
    }

    fun vaultPinLockoutStatus(nowEpochMs: Long = currentTimeMillis()): VaultPinLockoutStatus {
        val failedAttempts = stored(vaultStore, PIN_FAILED_ATTEMPTS_KEY).toIntOrNull()
            ?.coerceAtLeast(0) ?: 0
        val lockedUntilEpochMs = stored(vaultStore, PIN_LOCKED_UNTIL_EPOCH_MS_KEY).toLongOrNull()
            ?.coerceAtLeast(0L) ?: 0L
        return VaultPinLockoutStatus(
            failedAttempts = failedAttempts,
            lockedUntilEpochMs = lockedUntilEpochMs,
            remainingMs = (lockedUntilEpochMs - nowEpochMs).coerceAtLeast(0L)
        )
    }

    /** Existing V2 and hash-only PINs must be changed before a session can be created. */
    fun requiresPinUpgrade(): Boolean = hasVaultPin() && storedEnvelopeVersion() < VaultKeyEnvelope.VERSION

    fun unlockWithPin(pin: String): VaultCryptoSession {
        val lockout = vaultPinLockoutStatus()
        if (lockout.isLocked) throw VaultPinLockedException(lockout.remainingMs)
        if (requiresPinUpgrade()) throw VaultPinUpgradeRequiredException()
        if (!verifyVaultPin(pin)) {
            recordFailedPinAttempt()
            throw SecurityException("Invalid vault PIN")
        }
        return try {
            val dek = unwrapPinDek(vaultStore, pin, storedEnvelopeVersion())
            resetPinLockout()
            VaultCryptoSession.fromKeyBytes(dek).also { dek.fill(0) }
        } catch (error: Exception) {
            recordFailedPinAttempt()
            throw SecurityException("Invalid vault PIN", error)
        }
    }

    /**
     * Accepts a verified legacy PIN only to atomically create a V3 envelope with a new six-digit
     * PIN. The legacy credential cannot unlock a vault session by itself.
     */
    fun changeVaultPin(oldPin: String, newPin: String): Boolean {
        if (vaultPinLockoutStatus().isLocked || !isCurrentPin(newPin)) return false
        if (!verifyVaultPin(oldPin)) {
            recordFailedPinAttempt()
            return false
        }
        val currentDek = try {
            if (hasPinEnvelope(vaultStore)) {
                unwrapPinDek(vaultStore, oldPin, storedEnvelopeVersion())
            } else {
                keystoreVaultManager.randomVaultDek()
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
                        ENVELOPE_VERSION_KEY to VaultKeyEnvelope.VERSION.toString(),
                        PIN_FAILED_ATTEMPTS_KEY to "0",
                        PIN_LOCKED_UNTIL_EPOCH_MS_KEY to "0"
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
        if (requiresPinUpgrade()) throw VaultPinUpgradeRequiredException()
        check(hasBiometricEnrollment) { "Biometric vault enrollment is unavailable" }
        return try {
            keystoreVaultManager.prepareBiometricDecryptionCipher(
                decode(stored(vaultStore, BIOMETRIC_WRAP_IV_KEY))
            )
        } catch (error: KeyPermanentlyInvalidatedException) {
            check(disableBiometricEnrollment()) {
                "Failed to clear invalidated biometric vault enrollment"
            }
            throw VaultBiometricReenrollmentRequiredException(error)
        }
    }

    /** Completes unlock only from an authenticated CryptoObject result. */
    fun completeBiometricUnlock(result: BiometricPrompt.AuthenticationResult): VaultCryptoSession {
        val cipher = result.cryptoObject?.cipher
            ?: throw SecurityException("Authenticated CryptoObject is required")
        val wrapped = decode(stored(vaultStore, BIOMETRIC_WRAP_CIPHERTEXT_KEY))
        val dek = cipher.doFinal(wrapped)
        resetPinLockout()
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

    private fun recordFailedPinAttempt(): VaultPinLockoutStatus {
        val current = vaultPinLockoutStatus()
        val failedAttempts = current.failedAttempts + 1
        val nowEpochMs = currentTimeMillis()
        val lockedUntilEpochMs = nowEpochMs + lockoutDurationFor(failedAttempts)
        check(
            vaultStore.commit(
                mapOf(
                    PIN_FAILED_ATTEMPTS_KEY to failedAttempts.toString(),
                    PIN_LOCKED_UNTIL_EPOCH_MS_KEY to lockedUntilEpochMs.toString()
                )
            )
        ) { "Failed to persist vault PIN lockout state" }
        return vaultPinLockoutStatus(nowEpochMs)
    }

    private fun resetPinLockout() {
        check(
            vaultStore.commit(
                mapOf(
                    PIN_FAILED_ATTEMPTS_KEY to "0",
                    PIN_LOCKED_UNTIL_EPOCH_MS_KEY to "0"
                )
            )
        ) { "Failed to clear vault PIN lockout state" }
    }

    private fun lockoutDurationFor(failedAttempts: Int): Long = when {
        failedAttempts >= THIRD_LOCKOUT_ATTEMPT_THRESHOLD -> THIRD_LOCKOUT_DURATION_MS
        failedAttempts >= SECOND_LOCKOUT_ATTEMPT_THRESHOLD -> SECOND_LOCKOUT_DURATION_MS
        failedAttempts >= FIRST_LOCKOUT_ATTEMPT_THRESHOLD -> FIRST_LOCKOUT_DURATION_MS
        else -> 0L
    }

    private fun storedEnvelopeVersion(): Int =
        stored(vaultStore, ENVELOPE_VERSION_KEY).toIntOrNull() ?: 0

    private fun isCurrentPin(pin: String): Boolean =
        pin.length == VaultKeyEnvelope.PIN_LENGTH && pin.all(Char::isDigit)
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

private fun unwrapPinDek(store: StringKeyValueStore, pin: String, envelopeVersion: Int): ByteArray {
    val salt = decode(stored(store, PIN_WRAP_SALT_KEY))
    val iv = decode(stored(store, PIN_WRAP_IV_KEY))
    val ciphertext = decode(stored(store, PIN_WRAP_CIPHERTEXT_KEY))
    val wrap = VaultKeyEnvelope.PinWrap(salt, iv, ciphertext)
    return if (envelopeVersion >= VaultKeyEnvelope.VERSION) {
        VaultKeyEnvelope.unwrapWithPin(wrap, pin)
    } else {
        VaultKeyEnvelope.unwrapLegacyV2WithPin(wrap, pin)
    }
}

private fun stored(store: StringKeyValueStore, key: String): String =
    store.getString(key, "").orEmpty()

private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

private fun decode(value: String): ByteArray {
    require(value.isNotBlank()) { "Missing vault envelope field" }
    return Base64.decode(value, Base64.NO_WRAP)
}
