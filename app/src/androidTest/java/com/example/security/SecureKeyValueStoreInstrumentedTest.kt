@file:Suppress("DEPRECATION")

package com.example.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureKeyValueStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var secureFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        secureFile = File(context.noBackupFilesDir, SECURE_FILE)
        secureFile.delete()
        File(context.noBackupFilesDir, "$SECURE_FILE.tmp").delete()
        context.deleteSharedPreferences(LEGACY_NAME)
    }

    @After
    fun tearDown() {
        secureFile.delete()
        File(context.noBackupFilesDir, "$SECURE_FILE.tmp").delete()
        context.deleteSharedPreferences(LEGACY_NAME)
    }

    @Test
    fun aesGcmStore_survives_reopen_and_removes_values() {
        val first = newStore()
        assertTrue(first.commit(mapOf("access_token" to "token", "email" to "user@example.com")))

        val reopened = newStore()
        assertEquals("token", reopened.getString("access_token"))
        assertEquals("user@example.com", reopened.getString("email"))

        assertTrue(reopened.commit(mapOf("access_token" to null)))
        assertEquals(null, reopened.getString("access_token"))
        assertEquals("user@example.com", reopened.getString("email"))
    }

    @Test
    fun aesGcmStore_rejects_tampered_envelope() {
        val store = newStore()
        store.commit(mapOf("secret" to "value"))
        secureFile.writeBytes(ByteArray(24) { 0x7F.toByte() })

        assertThrows(IllegalStateException::class.java) { store.getString("secret") }
    }

    @Test
    fun legacy_encrypted_preferences_are_migrated_once() {
        val legacyMasterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val legacy = EncryptedSharedPreferences.create(
            context,
            LEGACY_NAME,
            legacyMasterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        legacy.edit()
            .putString("access_token", "legacy-token")
            .putString("email", "legacy@example.com")
            .putString("unrelated", "not-migrated")
            .commit()

        val target = newStore()
        LegacyEncryptedPreferencesMigration.migrateIfNeeded(
            context = context,
            legacyName = LEGACY_NAME,
            target = target,
            keys = setOf("access_token", "email")
        )

        assertEquals("legacy-token", target.getString("access_token"))
        assertEquals("legacy@example.com", target.getString("email"))
        assertEquals(null, target.getString("unrelated"))
        assertTrue(secureFile.isFile)
    }

    private fun newStore() = SecureKeyValueStore(
        context = context,
        fileName = SECURE_FILE,
        keyAlias = KEY_ALIAS
    )

    private companion object {
        const val LEGACY_NAME = "vvf_secure_migration_test"
        const val SECURE_FILE = "vvf_secure_migration_test.secure"
        const val KEY_ALIAS = "VVF_TEST_SECURE_MIGRATION_KEY"
    }
}
