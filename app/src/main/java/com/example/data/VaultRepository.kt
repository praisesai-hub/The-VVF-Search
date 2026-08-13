package com.example.data

import android.content.Context
import android.util.Base64
import com.example.security.KeystoreVaultManager
import com.example.storage.PhysicalStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VaultRepository(
    private val context: Context,
    private val dao: FileDao,
    private val keystoreVaultManager: KeystoreVaultManager,
    private val vaultManagerEngine: VaultManagerEngine = VaultManagerEngine(context, keystoreVaultManager)
) {
    fun hasVaultPin(): Boolean = vaultManagerEngine.hasVaultPin()
    fun getStoredVaultPinHash(): String = vaultManagerEngine.getStoredVaultPinHash()
    fun initializeVaultPin(pin: String): Boolean = vaultManagerEngine.initializeVaultPin(pin)
    fun verifyVaultPin(inputPin: String, storedHash: String = ""): Boolean = vaultManagerEngine.verifyVaultPin(inputPin, storedHash)
    fun changeVaultPin(oldPin: String, newPin: String): Boolean = vaultManagerEngine.changeVaultPin(oldPin, newPin)

    suspend fun encryptToVault(file: FileItemEntity) = withContext(Dispatchers.IO) {
        val vaultStorageResult = PhysicalStorageManager.encryptAndWipeSource(context, file.path, keystoreVaultManager)

        if (vaultStorageResult.isSuccess) {
            val res = vaultStorageResult.getOrThrow()
            val ivBase64 = Base64.encodeToString(res.iv, Base64.NO_WRAP)
            dao.updateFile(file.copy(isVault = true))
            dao.insertVaultItem(
                VaultItemEntity(
                    originalName = file.name,
                    encryptedName = res.encryptedFileName,
                    encryptedFilePath = res.vaultFilePath,
                    ivBase64 = ivBase64,
                    category = file.category,
                    sizeBytes = file.sizeBytes
                )
            )
        } else {
            throw vaultStorageResult.exceptionOrNull() ?: java.io.IOException("Failed to encrypt and wipe source file")
        }
    }

    suspend fun unlockFromVault(vaultItem: VaultItemEntity, file: FileItemEntity?): Boolean = withContext(Dispatchers.IO) {
        try {
            val targetFile = file ?: dao.getVaultFileByName(vaultItem.originalName)
            if (targetFile != null) {
                val iv = Base64.decode(vaultItem.ivBase64, Base64.DEFAULT)
                val decryptResult = PhysicalStorageManager.decryptAndRestore(
                    context,
                    vaultItem.encryptedFilePath,
                    targetFile.path,
                    iv,
                    keystoreVaultManager
                )
                if (decryptResult.isSuccess) {
                    dao.updateFile(targetFile.copy(isVault = false))
                    dao.deleteVaultItemById(vaultItem.id)
                    true
                } else {
                    throw decryptResult.exceptionOrNull() ?: java.io.IOException("Failed to physically decrypt vault file")
                }
            } else {
                dao.deleteVaultItemById(vaultItem.id)
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("VaultRepository", "Failed to unlock from vault: ${e.message}", e)
            throw e
        }
    }
}
