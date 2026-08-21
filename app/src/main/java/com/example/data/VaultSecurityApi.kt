package com.example.data

import androidx.biometric.BiometricPrompt
import com.example.security.VaultCryptoSession
import javax.crypto.Cipher

data class VaultLockoutState(
    val failedAttempts: Int,
    val lockedUntilMs: Long
) {
    val isLockedOut: Boolean get() = lockedUntilMs > System.currentTimeMillis()
}

interface VaultPinApi {
    fun hasVaultPin(): Boolean
    fun getVaultLockoutState(): VaultLockoutState
    fun getStoredVaultPinHash(): String
    fun initializeVaultPin(pin: String): Boolean
    fun verifyVaultPin(inputPin: String, storedHash: String = ""): Boolean
    fun changeVaultPin(oldPin: String, newPin: String): Boolean
    fun unlockWithPin(pin: String): Boolean
}

interface VaultBiometricApi {
    fun hasBiometricEnrollment(): Boolean
    fun prepareBiometricEnrollmentCipher(): Cipher
    fun completeBiometricEnrollment(result: BiometricPrompt.AuthenticationResult): Boolean
    fun prepareBiometricUnlockCipher(): Cipher
    fun completeBiometricUnlock(result: BiometricPrompt.AuthenticationResult): Boolean
    fun disableBiometricEnrollment(): Boolean
}

interface VaultSessionApi {
    fun lockSession()
    fun requireAuthenticatedSession(): VaultCryptoSession
}

interface VaultSecurityApi : VaultPinApi, VaultBiometricApi, VaultSessionApi

internal class VaultSecurityDelegate(
    engine: VaultManagerEngine,
    sessions: VaultSessionHolder = VaultSessionHolder()
) : VaultSecurityApi,
    VaultPinApi by VaultPinDelegate(engine, sessions),
    VaultBiometricApi by VaultBiometricDelegate(engine, sessions),
    VaultSessionApi by VaultSessionDelegate(sessions)

internal class VaultSessionHolder {
    @Volatile
    var activeSession: VaultCryptoSession? = null

    fun replace(newSession: VaultCryptoSession): Boolean {
        activeSession?.close()
        activeSession = newSession
        return true
    }

    fun close() {
        activeSession?.close()
        activeSession = null
    }
}

private class VaultPinDelegate(
    private val engine: VaultManagerEngine,
    private val sessions: VaultSessionHolder
) : VaultPinApi {
    override fun hasVaultPin(): Boolean = engine.hasVaultPin

    override fun getVaultLockoutState(): VaultLockoutState = engine.getVaultLockoutState()

    override fun getStoredVaultPinHash(): String = engine.getStoredVaultPinHash()

    override fun initializeVaultPin(pin: String): Boolean = engine.initializeVaultPin(pin)

    override fun verifyVaultPin(inputPin: String, storedHash: String): Boolean =
        engine.verifyVaultPin(inputPin, storedHash)

    override fun changeVaultPin(oldPin: String, newPin: String): Boolean =
        engine.changeVaultPin(oldPin, newPin)

    override fun unlockWithPin(pin: String): Boolean =
        sessions.replace(engine.unlockWithPin(pin))
}

private class VaultBiometricDelegate(
    private val engine: VaultManagerEngine,
    private val sessions: VaultSessionHolder
) : VaultBiometricApi {
    override fun hasBiometricEnrollment(): Boolean = engine.hasBiometricEnrollment

    override fun prepareBiometricEnrollmentCipher(): Cipher =
        engine.prepareBiometricEnrollmentCipher()

    override fun completeBiometricEnrollment(result: BiometricPrompt.AuthenticationResult): Boolean {
        val session = sessions.activeSession ?: return false
        return engine.completeBiometricEnrollment(session, result)
    }

    override fun prepareBiometricUnlockCipher(): Cipher = engine.prepareBiometricUnlockCipher()

    override fun completeBiometricUnlock(result: BiometricPrompt.AuthenticationResult): Boolean =
        sessions.replace(engine.completeBiometricUnlock(result))

    override fun disableBiometricEnrollment(): Boolean = engine.disableBiometricEnrollment()
}

private class VaultSessionDelegate(
    private val sessions: VaultSessionHolder
) : VaultSessionApi {
    override fun lockSession() = sessions.close()

    override fun requireAuthenticatedSession(): VaultCryptoSession =
        sessions.activeSession ?: throw SecurityException("Vault authentication is required")
}
