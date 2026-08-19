package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileRepositoryTest {

    private lateinit var context: Context
    private lateinit var fakeDao: FakeFileDao
    private lateinit var repository: FileRepository

    class FakeFileDao : FileDao {
        var updatedFile: FileItemEntity? = null
        var updateFileCallCount = 0

        var getFilteredFilesPagedCalled = false
        var lastCategory: String? = null
        var lastQuery: String = ""
        var lastLimit: Int = 0
        var lastOffset: Int = 0
        var pagedReturnList: List<FileItemEntity> = emptyList()

        override suspend fun updateFile(file: FileItemEntity) {
            updatedFile = file
            updateFileCallCount++
        }

        override suspend fun getFilteredFilesPaged(
            category: String?,
            query: String,
            limit: Int,
            offset: Int
        ): List<FileItemEntity> {
            getFilteredFilesPagedCalled = true
            lastCategory = category
            lastQuery = query
            lastLimit = limit
            lastOffset = offset
            return pagedReturnList
        }

        override suspend fun getFileById(id: Long): FileItemEntity? = null
        override suspend fun getFileByName(name: String): FileItemEntity? = null
        override fun getOcrScannedFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getAllActiveFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getRecentFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
        override fun getCategoryStats(): Flow<List<CategoryStat>> = flowOf(emptyList())
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
        override suspend fun getVaultFileByName(name: String): FileItemEntity? = null
        override fun getAllVaultItems(): Flow<List<VaultItemEntity>> = flowOf(emptyList())
        override suspend fun insertVaultItem(item: VaultItemEntity): Long = 0L
        override suspend fun deleteVaultItemById(id: Long) {}
        override suspend fun getVaultItemByEncryptedPath(path: String): VaultItemEntity? = null
        override suspend fun upsertVaultOperation(operation: VaultOperationEntity) {}
        override suspend fun getIncompleteVaultOperations(): List<VaultOperationEntity> = emptyList()
        override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = flowOf(emptyList())
        override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long = 0L
        override suspend fun deleteCloudSyncItem(id: Long) {}
        override suspend fun setPluginEnabled(id: String, enabled: Boolean) {}
        override suspend fun insertPlugins(plugins: List<PluginEntity>) {}
        override fun getAllPlugins(): Flow<List<PluginEntity>> = flowOf(emptyList())
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        fakeDao = FakeFileDao()
        repository = FileRepository(context, fakeDao)
    }

    @Test
    fun testRenameFile_success_updatesDatabase() = runBlocking {
        val tempFile = File.createTempFile("test_file_", ".txt")
        tempFile.writeText("sample content")
        val originalFile = FileItemEntity(
            id = 1L,
            name = tempFile.name,
            path = tempFile.absolutePath,
            category = "DOCUMENTS",
            sizeBytes = tempFile.length()
        )
        val newName = "renamed_file.txt"

        try {
            val updatedFile = repository.renameFile(originalFile, newName)

            assertEquals(newName, updatedFile.name)
            assertTrue("New path should end with new name", updatedFile.path.endsWith(newName))
            assertEquals(1, fakeDao.updateFileCallCount)
            assertEquals(1L, fakeDao.updatedFile?.id)
            assertEquals(newName, fakeDao.updatedFile?.name)
            assertEquals(updatedFile.path, fakeDao.updatedFile?.path)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            val renamedFile = File(tempFile.parentFile, newName)
            if (renamedFile.exists()) {
                renamedFile.delete()
            }
        }
    }

    @Test
    fun testRenameFile_failure_throwsExceptionAndDoesNotUpdateDatabase() = runBlocking {
        val nonExistentPath = "/non_existent_directory_vvf/non_existent_file.txt"
        val originalFile = FileItemEntity(
            id = 2L,
            name = "non_existent_file.txt",
            path = nonExistentPath,
            category = "DOCUMENTS",
            sizeBytes = 100L
        )
        val newName = "should_fail.txt"

        try {
            repository.renameFile(originalFile, newName)
            fail("Expected exception for non-existent file rename")
        } catch (e: Exception) {
            assertTrue(
                "Exception should be FileNotFoundException or IOException, but was ${e.javaClass.simpleName}",
                e is FileNotFoundException || e is IOException
            )
        }

        assertEquals(0, fakeDao.updateFileCallCount)
        assertNull(fakeDao.updatedFile)
    }

    @Test
    fun testAddTagToFile_emptyTags() = runBlocking {
        val fileWithNoTags = FileItemEntity(
            id = 3L,
            name = "photo.jpg",
            path = "/storage/photo.jpg",
            category = "IMAGES",
            sizeBytes = 500L,
            tags = ""
        )

        val result = repository.addTagToFile(fileWithNoTags, "nature")

        assertEquals("nature", result.tags)
        assertEquals(1, fakeDao.updateFileCallCount)
        assertEquals(3L, fakeDao.updatedFile?.id)
        assertEquals("nature", fakeDao.updatedFile?.tags)
    }

    @Test
    fun testAddTagToFile_existingTagsPreserved() = runBlocking {
        val fileWithTags = FileItemEntity(
            id = 4L,
            name = "photo.jpg",
            path = "/storage/photo.jpg",
            category = "IMAGES",
            sizeBytes = 500L,
            tags = "family, vacation"
        )

        val result = repository.addTagToFile(fileWithTags, "2026")

        assertEquals("family, vacation, 2026", result.tags)
        assertEquals(1, fakeDao.updateFileCallCount)
        assertEquals(4L, fakeDao.updatedFile?.id)
        assertEquals("family, vacation, 2026", fakeDao.updatedFile?.tags)
    }

    @Test
    fun testGetFilteredFilesPaged_delegatesCompiledFtsQueryToDao() = runBlocking {
        val expectedList = listOf(
            FileItemEntity(id = 5L, name = "report.pdf", path = "/report.pdf", category = "DOCUMENTS", sizeBytes = 1024L)
        )
        fakeDao.pagedReturnList = expectedList

        val result = repository.getFilteredFilesPaged("DOCUMENTS", "report", 10, 0)

        assertEquals(expectedList, result)
        assertTrue(fakeDao.getFilteredFilesPagedCalled)
        assertEquals("DOCUMENTS", fakeDao.lastCategory)
        assertEquals("\"report\"*", fakeDao.lastQuery)
        assertEquals(10, fakeDao.lastLimit)
        assertEquals(0, fakeDao.lastOffset)
    }
}
