package com.example.data

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.security.KeystoreVaultManager
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.every
import io.mockk.mockk

@RunWith(AndroidJUnit4::class)
class RepositorySecurityCoverageInstrumentedTest {
    private lateinit var context: Context
    private lateinit var keystore: KeystoreVaultManager
    private lateinit var dao: VaultRepositoryTest.FakeFileDao
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        keystore = KeystoreVaultManager()
        keystore.deleteBiometricWrapKey()
        dao = VaultRepositoryTest.FakeFileDao()
        val preferences = context.getSharedPreferences("repository-security-coverage", Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        val engine = VaultManagerEngine(context, keystore, injectedVaultPrefs = preferences)
        repository = VaultRepository(context, dao, keystore, engine)
    }

    @Test
    fun legacyVaultItem_isMigratedThroughAuthenticatedSession() = runBlocking {
        val pin = "2468"
        assertTrue(repository.initializeVaultPin(pin))
        assertTrue(repository.unlockWithPin(pin))

        val source = File(context.filesDir, "legacy-vault-source.txt")
        val legacyEncrypted = File(context.filesDir, "legacy-vault-encrypted.bin")
        val restoreTarget = File(context.filesDir, "legacy-vault-restored.txt")
        source.writeText("legacy confidential payload")
        val encrypted = keystore.encryptBytes(source.readBytes())
        legacyEncrypted.writeBytes(encrypted.ciphertext)
        source.delete()
        restoreTarget.delete()

        val legacyItem = VaultItemEntity(
            id = 42L,
            originalName = "legacy-vault-restored.txt",
            encryptedName = legacyEncrypted.name,
            encryptedFilePath = legacyEncrypted.absolutePath,
            ivBase64 = android.util.Base64.encodeToString(encrypted.iv, android.util.Base64.NO_WRAP),
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = encrypted.ciphertext.size.toLong(),
            vaultFormatVersion = 1
        )
        val target = FileItemEntity(
            id = 7L,
            name = "legacy-vault-restored.txt",
            path = restoreTarget.absolutePath,
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = "legacy confidential payload".length.toLong(),
            isVault = true
        )

        assertTrue(repository.unlockFromVault(legacyItem, target))
        assertTrue(restoreTarget.exists())
        assertTrue(restoreTarget.readText() == "legacy confidential payload")
        assertNotNull(dao.insertedVaultItem)
        assertEquals(2, dao.insertedVaultItem!!.vaultFormatVersion)

        source.delete()
        legacyEncrypted.delete()
        restoreTarget.delete()
    }

    @Test
    fun smartRepository_delegatesVaultAndFailClosedSyncPaths() = runBlocking {
        val facade = SmartManagerRepository(context, dao)

        assertFalse(facade.isSemanticSearchAvailable)
        assertTrue(facade.searchSemanticFiles("query").first().isEmpty())
        assertTrue(facade.initializeVaultPin("2468"))
        assertTrue(facade.hasVaultPin())
        assertTrue(facade.verifyVaultPin("2468"))
        assertTrue(facade.getStoredVaultPinHash().isNotBlank())
        assertTrue(facade.unlockVaultWithPin("2468"))

        val noCryptoResult = mockk<BiometricPrompt.AuthenticationResult>()
        every { noCryptoResult.cryptoObject } returns null
        assertFalse(facade.completeBiometricEnrollment(noCryptoResult))
        assertThrows(SecurityException::class.java) { facade.completeBiometricUnlock(noCryptoResult) }
        assertThrows(Exception::class.java) { facade.prepareBiometricUnlockCipher() }
        assertTrue(facade.disableBiometricEnrollment())
        facade.lockVaultSession()

        assertFalse(facade.enqueueCloudSyncItem("GOOGLE_DRIVE", "file.txt", 10L))
        assertFalse(facade.retryCloudSyncItem(999L))
        assertFalse(facade.cancelCloudSyncItem(999L))
        facade.enqueueDuplicateCleanupWork()
        facade.enqueueCloudSyncWork()
        facade.enqueueCacheCleanupWork()
        facade.enqueueBackgroundIndexWork()
    }
}
