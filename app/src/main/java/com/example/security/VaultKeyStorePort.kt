package com.example.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Narrow Android Keystore boundary used by [KeystoreVaultManager].
 *
 * The production implementation always provisions keys in Android Keystore. The
 * port exists so lifecycle and fail-closed policy can be tested on the JVM with
 * an ephemeral fake; it must never be implemented with persistent software keys.
 */
interface VaultKeyStorePort {
    fun containsAlias(alias: String): Boolean
    fun getSecretKey(alias: String): SecretKey?
    fun createVaultKey(alias: String)
    fun createBiometricWrapKey(alias: String)
    fun deleteKey(alias: String)
}

internal class AndroidVaultKeyStorePort private constructor(
    private val keyStore: KeyStore
) : VaultKeyStorePort {
    override fun containsAlias(alias: String): Boolean = keyStore.containsAlias(alias)

    override fun getSecretKey(alias: String): SecretKey? =
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    override fun createVaultKey(alias: String) {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    override fun createBiometricWrapKey(alias: String) {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    override fun deleteKey(alias: String) {
        keyStore.deleteEntry(alias)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        fun open(): AndroidVaultKeyStorePort = AndroidVaultKeyStorePort(
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        )

        fun fromKeyStore(keyStore: KeyStore): AndroidVaultKeyStorePort = AndroidVaultKeyStorePort(keyStore)
    }
}
