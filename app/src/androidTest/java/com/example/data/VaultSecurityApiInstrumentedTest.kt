package com.example.data

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.security.KeystoreVaultManager
import com.example.security.VaultCryptoSession
import com.example.security.VaultKeyEnvelope
import io.mockk.every
import io.mockk.mockk
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultSecurityApiInstrumentedTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var keystore: KeystoreVaultManager
    private lateinit var engine: VaultManagerEngine
    private lateinit var security: VaultSecurityDelegate

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences = context.getSharedPreferences("vault-security-api-test", Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        keystore = KeystoreVaultManager()
        keystore.deleteBiometricWrapKey()
        engine = VaultManagerEngine(context, keystore, injectedVaultPrefs = preferences)
        security = VaultSecurityDelegate(engine)
    }

    @Test
    fun sessionLifecycle_requiresAuthentication_andClosesKeyMaterial() {
        assertThrows(SecurityException::class.java) { security.requireAuthenticatedSession() }
        assertTrue(security.initializeVaultPin("24682468"))
        assertTrue(security.unlockWithPin("24682468"))

        val session = security.requireAuthenticatedSession()
        val encrypted = session.encryptBytes("authenticated payload".toByteArray())
        assertArrayEquals(
            "authenticated payload".toByteArray(),
            session.decryptBytes(encrypted.ciphertext, encrypted.iv)
        )
        assertTrue(session.copyKeyBytes().any { it != 0.toByte() })

        security.lockSession()
        assertThrows(SecurityException::class.java) { security.requireAuthenticatedSession() }
        assertThrows(IllegalStateException::class.java) {
            session.encryptBytes(byteArrayOf(1))
        }
    }

    @Test
    fun invalidPin_doesNotCreateAuthenticatedSession() {
        assertTrue(security.initializeVaultPin("24682468"))
        assertThrows(IllegalStateException::class.java) { security.unlockWithPin("00000000") }
        assertThrows(SecurityException::class.java) { security.requireAuthenticatedSession() }
        assertFalse(security.hasBiometricEnrollment())
    }

    @Test
    fun biometricEnrollment_requiresActiveSession_andAuthenticatedCryptoObject() {
        val noCryptoResult = mockk<BiometricPrompt.AuthenticationResult>()
        every { noCryptoResult.cryptoObject } returns null

        assertFalse(security.completeBiometricEnrollment(noCryptoResult))
        assertThrows(SecurityException::class.java) {
            security.completeBiometricUnlock(noCryptoResult)
        }
        assertThrows(IllegalStateException::class.java) {
            security.prepareBiometricUnlockCipher()
        }
        assertTrue(security.disableBiometricEnrollment())
        assertFalse(security.hasBiometricEnrollment())
    }

    @Test
    fun biometricEnrollment_persistsWrappedSessionKey_fromCryptoObjectCipher() {
        assertTrue(security.initializeVaultPin("24682468"))
        assertTrue(security.unlockWithPin("24682468"))

        val wrapKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val wrappingCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, wrapKey)
        }
        val authenticatedResult = mockk<BiometricPrompt.AuthenticationResult>()
        every { authenticatedResult.cryptoObject } returns BiometricPrompt.CryptoObject(wrappingCipher)

        assertTrue(security.completeBiometricEnrollment(authenticatedResult))
        assertTrue(security.hasBiometricEnrollment())

        security.lockSession()
        assertFalse(security.completeBiometricEnrollment(authenticatedResult))
        assertTrue(security.disableBiometricEnrollment())
        assertFalse(security.hasBiometricEnrollment())
    }

    @Test
    fun keyEnvelope_andSession_validation_failClosed() {
        val dek = ByteArray(32) { 9 }
        val wrapped = VaultKeyEnvelope.wrapWithPin(dek, "24682468")
        assertArrayEquals(dek, VaultKeyEnvelope.unwrapWithPin(wrapped, "24682468"))
        assertThrows(GeneralSecurityException::class.java) {
            VaultKeyEnvelope.unwrapWithPin(wrapped, "00000000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(ByteArray(31), "24682468")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.wrapWithPin(dek, "24a8")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultKeyEnvelope.unwrapWithPin(
                VaultKeyEnvelope.PinWrap(ByteArray(15), wrapped.iv, wrapped.ciphertext),
                "24682468"
            )
        }

        val session = VaultCryptoSession.fromKeyBytes(dek)
        val directCipher = session.getEncryptionCipher()
        val ciphertext = directCipher.doFinal("session".toByteArray())
        assertArrayEquals("session".toByteArray(), session.getDecryptionCipher(directCipher.iv).doFinal(ciphertext))
        session.close()
        assertThrows(IllegalStateException::class.java) { session.copyKeyBytes() }
    }
}
