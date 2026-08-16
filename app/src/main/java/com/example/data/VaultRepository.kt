package com.example.data

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.example.security.KeystoreVaultManager
import com.example.security.VaultCryptoSession
import com.example.storage.PhysicalStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val CURRENT_VAULT_FORMAT_VERSION = 2

class VaultRepository(
    private val context: Context,
    private val dao: FileDao,
    private val keystoreVaultManager: KeystoreVaultManager,
    vaultManagerEngine: VaultManagerEngine = VaultManagerEngine(context, keystoreVaultManager)
) : VaultSecurityApi by VaultSecurityDelegate(vaultManagerEngine) {
    private val engine = vaultManagerEngine

    suspend fun encryptToVault(file: FileItemEntity) = withContext(Dispatchers.IO) {
        val session = requireAuthenticatedSession()
        val vaultStorageResult = PhysicalStorageManager.encryptAndWipeSource(context, file.path) { bytes ->
            val encrypted = session.encryptBytes(bytes)
            encrypted.ciphertext to encrypted.iv
        }

        if (vaultStorageResult.isSuccess) {
            val result = vaultStorageResult.getOrThrow()
            val ivBase64 = Base64.encodeToString(result.iv, Base64.NO_WRAP)
            dao.updateFile(file.copy(isVault = true))
            dao.insertVaultItem(
                VaultItemEntity(
                    originalName = file.name,
                    encryptedName = result.encryptedFileName,
                    encryptedFilePath = result.vaultFilePath,
                    ivBase64 = ivBase64,
                    category = file.category,
                    sizeBytes = file.sizeBytes,
                    isBiometricProtected = engine.hasBiometricEnrollment,
                    vaultFormatVersion = CURRENT_VAULT_FORMAT_VERSION
                )
            )
        } else {
            throw vaultStorageResult.exceptionOrNull()
                ?: java.io.IOException("Failed to encrypt and wipe source file")
        }
    }

    suspend fun unlockFromVault(vaultItem: VaultItemEntity, file: FileItemEntity?): Boolean =
        withContext(Dispatchers.IO) {
            val session = requireAuthenticatedSession()
            val migratedItem = if (vaultItem.vaultFormatVersion < CURRENT_VAULT_FORMAT_VERSION) {
                migrateLegacyItem(vaultItem, session)
            } else {
                vaultItem
            }
            val targetFile = file ?: dao.getVaultFileByName(migratedItem.originalName)
            if (targetFile != null) {
                val iv = Base64.decode(migratedItem.ivBase64, Base64.DEFAULT)
                val decryptResult = PhysicalStorageManager.decryptAndRestore(
                    context,
                    migratedItem.encryptedFilePath,
                    targetFile.path
                ) { ciphertext -> session.decryptBytes(ciphertext, iv) }
                if (decryptResult.isSuccess) {
                    dao.updateFile(targetFile.copy(isVault = false))
                    dao.deleteVaultItemById(migratedItem.id)
                    true
                } else {
                    throw decryptResult.exceptionOrNull()
                        ?: java.io.IOException("Failed to physically decrypt vault file")
                }
            } else {
                dao.deleteVaultItemById(migratedItem.id)
                true
            }
        }

    /** Re-encrypts a legacy direct-Keystore vault file with the authenticated V2 session. */
    private suspend fun migrateLegacyItem(
        item: VaultItemEntity,
        session: VaultCryptoSession
    ): VaultItemEntity {
        val legacyFile = File(item.encryptedFilePath)
        check(legacyFile.exists()) { "Legacy vault file is missing" }
        val legacyIv = Base64.decode(item.ivBase64, Base64.DEFAULT)
        val plaintext = keystoreVaultManager.decryptBytes(legacyFile.readBytes(), legacyIv)
        return try {
            val encrypted = session.encryptBytes(plaintext)
            val atomicFile = AtomicFile(legacyFile)
            val output = atomicFile.startWrite()
            try {
                output.write(encrypted.ciphertext)
                output.flush()
                atomicFile.finishWrite(output)
            } catch (error: java.io.IOException) {
                atomicFile.failWrite(output)
                throw error
            }
            check(legacyFile.exists() && legacyFile.length() > 0L) {
                "V2 vault replacement was not written"
            }
            val migrated = item.copy(
                ivBase64 = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP),
                isBiometricProtected = engine.hasBiometricEnrollment,
                vaultFormatVersion = CURRENT_VAULT_FORMAT_VERSION
            )
            dao.insertVaultItem(migrated)
            migrated
        } finally {
            plaintext.fill(0)
        }
    }
}
