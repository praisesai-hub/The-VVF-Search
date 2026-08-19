package com.example.data

import android.content.Context
import com.example.security.KeystoreVaultManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultRepositoryTest {

    private lateinit var context: Context
    private lateinit var fakeDao: FakeFileDao
    private lateinit var keystoreVaultManager: KeystoreVaultManager
    private lateinit var repository: VaultRepository

    class FakeFileDao : FileDao {
        var updatedFile: FileItemEntity? = null
        var insertedVaultItem: VaultItemEntity? = null
        var deletedVaultItemId: Long? = null
        val filesById = mutableMapOf<Long, FileItemEntity>()

        override suspend fun updateFile(file: FileItemEntity) {
            updatedFile = file
            filesById[file.id] = file
        }

        override suspend fun insertVaultItem(item: VaultItemEntity): Long {
            insertedVaultItem = item.copy(id = 1L)
            return 1L
        }

        override suspend fun deleteVaultItemById(id: Long) {
            deletedVaultItemId = id
            if (insertedVaultItem?.id == id) insertedVaultItem = null
        }
        override suspend fun getVaultItemByEncryptedPath(path: String): VaultItemEntity? =
            insertedVaultItem?.takeIf { it.encryptedFilePath == path }
        override suspend fun upsertVaultOperation(operation: VaultOperationEntity) = Unit
        override suspend fun getIncompleteVaultOperations(): List<VaultOperationEntity> = emptyList()

        override suspend fun getVaultFileByName(name: String): FileItemEntity? {
            return FileItemEntity(id = 5, name = "secret.png", path = "/secret.png", category = "IMAGES", sizeBytes = 1200, isVault = true)
        }

        // Dummy overrides
        override suspend fun getFileById(id: Long): FileItemEntity? = filesById[id]
        override suspend fun getFileByName(name: String): FileItemEntity? = null
        override fun getOcrScannedFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getAllActiveFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getRecentFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getCategoryStats(): Flow<List<CategoryStat>> = flowOf(emptyList())
        override suspend fun getFilteredFilesPaged(category: String?, query: String, limit: Int, offset: Int): List<FileItemEntity> = emptyList()
        override fun getFilesByCategory(category: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getRecycleBinFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getVaultFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override suspend fun getUnhashedFiles(): List<FileItemEntity> = emptyList()
        override suspend fun updateFiles(files: List<FileItemEntity>) {}
        override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? = null
        override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>) {}
        override fun getDuplicateFilesByHash(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override suspend fun insertFile(file: FileItemEntity): Long = 0L
        override suspend fun insertFiles(files: List<FileItemEntity>) {}
        override suspend fun getFileByPath(path: String): FileItemEntity? = null
        override suspend fun insertFileDirect(file: FileItemEntity): Long = 0L
        override suspend fun getAllOrdinaryFilesDirect(): List<FileItemEntity> = emptyList()
        override suspend fun deleteFilesByIds(ids: List<Long>) {}
        override suspend fun deleteFileById(id: Long) {}
        override suspend fun emptyRecycleBin() {}
        override fun getAllVaultItems(): Flow<List<VaultItemEntity>> = flowOf(emptyList())
        override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = flowOf(emptyList())
        override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long = 0L
        override suspend fun deleteCloudSyncItem(id: Long) {}
        override suspend fun setPluginEnabled(id: String, enabled: Boolean) {}
        override suspend fun insertPlugins(plugins: List<PluginEntity>) {}
        override fun getAllPlugins(): Flow<List<PluginEntity>> = flowOf(emptyList())
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        fakeDao = FakeFileDao()
        keystoreVaultManager = KeystoreVaultManager()
        keystoreVaultManager.deleteBiometricWrapKey()
        val testPrefs = context.getSharedPreferences("vault-repository-test", Context.MODE_PRIVATE)
        check(testPrefs.edit().clear().commit())
        val engine = VaultManagerEngine(
            context = context,
            keystoreVaultManager = keystoreVaultManager,
            injectedVaultPrefs = testPrefs
        )
        repository = VaultRepository(context, fakeDao, keystoreVaultManager, engine)
    }

    @Test
    fun testEncryptToVaultSuccess(): Unit = runBlocking {
        assertTrue(repository.initializeVaultPin("246810"))
        assertTrue(repository.unlockWithPin("246810"))
        val tempFile = java.io.File(context.filesDir, "secret.png")
        tempFile.writeText("sensitive secure data to encrypt")
        val file = FileItemEntity(id = 5, name = "secret.png", path = tempFile.absolutePath, category = "IMAGES", sizeBytes = tempFile.length())
        fakeDao.filesById[file.id] = file

        repository.encryptToVault(file)

        assertNotNull(fakeDao.updatedFile)
        assertTrue(fakeDao.updatedFile!!.isVault)
        assertNotNull(fakeDao.insertedVaultItem)
        assertEquals("secret.png", fakeDao.insertedVaultItem!!.originalName)
        assertEquals(2, fakeDao.insertedVaultItem!!.vaultFormatVersion)
        
        // Original file should be securely wiped and deleted
        assertFalse(tempFile.exists())
    }

    @Test
    fun testUnlockFromVaultDelegation(): Unit = runBlocking {
        assertTrue(repository.initializeVaultPin("246810"))
        assertTrue(repository.unlockWithPin("246810"))
        val tempFile = java.io.File(context.filesDir, "secret.png")
        if (tempFile.exists()) tempFile.delete()
        
        // Encrypt first to have a valid encrypted file in vault
        val originalFile = FileItemEntity(id = 5, name = "secret.png", path = tempFile.absolutePath, category = "IMAGES", sizeBytes = 1200)
        fakeDao.filesById[originalFile.id] = originalFile
        tempFile.writeText("sensitive data to encrypt")
        
        repository.encryptToVault(originalFile)
        
        val vaultItem = fakeDao.insertedVaultItem
        assertNotNull(vaultItem)
        
        val success = repository.unlockFromVault(vaultItem!!, originalFile)
        assertTrue(success)

        assertNotNull(fakeDao.updatedFile)
        assertFalse(fakeDao.updatedFile!!.isVault)
        assertEquals(1L, fakeDao.deletedVaultItemId)
        
        // Restored file should exist now
        assertTrue(tempFile.exists())
        assertEquals("sensitive data to encrypt", tempFile.readText())
        
        // Clean up
        tempFile.delete()
        Unit
    }
}
