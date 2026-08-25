package com.example.data

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmartManagerRepositoryPureDataPlainJvmTest {
    private lateinit var repository: SmartManagerRepository
    private lateinit var dao: RepositoryCoverageFileDao

    @Before
    fun setUp() {
        dao = RepositoryCoverageFileDao()
        repository = SmartManagerRepository(
            context = mockk<Context>(relaxed = true),
            dao = dao,
        )
    }

    @Test
    fun documentStats_countsIndexedEligibleDocumentsOnly(): Unit = runBlocking {
        dao.activeFiles += listOf(
            document(1L, "indexed.pdf", md5Hash = "hash"),
            document(2L, "pending.pdf"),
            document(3L, "vault.pdf", md5Hash = "vault", isVault = true),
            document(4L, "recycled.pdf", md5Hash = "recycled", isRecycleBin = true),
            image(5L, "photo.jpg"),
        )

        val stats = repository.documentStats.first()

        assertEquals(1, stats.first)
        assertEquals(1, stats.second)
        assertEquals(0.5f, stats.third)
    }

    @Test
    fun exactDuplicates_groupsRepeatedNonBlankHashesOnly(): Unit = runBlocking {
        val first = document(10L, "one.txt", md5Hash = "same")
        dao.duplicateFiles += listOf(
            first,
            first.copy(id = 11L, name = "two.txt"),
            document(12L, "blank-one.txt"),
            document(13L, "blank-two.txt"),
        )

        val groups = repository.exactDuplicates.first()

        assertEquals(1, groups.size)
        assertEquals(100, groups.single().similarityScore)
        assertEquals(listOf(10L, 11L), groups.single().files.map { it.id })
        assertTrue(groups.single().title.contains("SHA-256 Hash Match"))
    }

    @Test
    fun repositoryLookup_delegatesToDaoBackedFlows(): Unit = runBlocking {
        val expected = image(30L, "lookup.jpg")
        dao.activeFiles += expected

        assertEquals(expected, repository.getFileById(30L))
        assertEquals(expected, repository.getFileByName("lookup.jpg"))
        assertNotNull(repository.activeFiles.first())
        assertEquals(listOf(expected), repository.activeFiles.first())
    }

    private fun document(
        id: Long,
        name: String,
        md5Hash: String = "",
        isVault: Boolean = false,
        isRecycleBin: Boolean = false,
    ) = FileItemEntity(
        id = id,
        name = name,
        path = "/docs/$name",
        category = FileCategory.DOCUMENTS.name,
        sizeBytes = 1L,
        md5Hash = md5Hash,
        isVault = isVault,
        isRecycleBin = isRecycleBin,
    )

    private fun image(id: Long, name: String) = FileItemEntity(
        id = id,
        name = name,
        path = "/images/$name",
        category = FileCategory.IMAGES.name,
        sizeBytes = 1L,
    )
}

private class RepositoryCoverageFileDao : FileDao {
    val activeFiles = mutableListOf<FileItemEntity>()
    val duplicateFiles = mutableListOf<FileItemEntity>()

    override suspend fun getFileById(id: Long): FileItemEntity? = activeFiles.firstOrNull { it.id == id }
    override suspend fun getFileByName(name: String): FileItemEntity? = activeFiles.firstOrNull { it.name == name }
    override fun getOcrScannedFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun getAllActiveFiles(): Flow<List<FileItemEntity>> = flowOf(activeFiles)
    override fun getRecentFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun getCategoryStats(): Flow<List<CategoryStat>> = flowOf(emptyList())
    override suspend fun getFilteredFilesPaged(
        category: String?,
        query: String,
        limit: Int,
        offset: Int,
    ): List<FileItemEntity> = emptyList()
    override fun getFilesByCategory(category: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun getRecycleBinFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun getVaultFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun searchFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override suspend fun getUnhashedFiles(): List<FileItemEntity> = emptyList()
    override suspend fun updateFiles(files: List<FileItemEntity>) = Unit
    override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? = null
    override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>) = Unit
    override fun getDuplicateFilesByHash(): Flow<List<FileItemEntity>> = flowOf(duplicateFiles)
    override suspend fun insertFile(file: FileItemEntity): Long = file.id
    override suspend fun insertFiles(files: List<FileItemEntity>) = Unit
    override suspend fun updateFile(file: FileItemEntity) = Unit
    override suspend fun getFileByPath(path: String): FileItemEntity? = activeFiles.firstOrNull { it.path == path }
    override suspend fun insertFileDirect(file: FileItemEntity): Long = file.id
    override suspend fun getAllOrdinaryFilesDirect(): List<FileItemEntity> = emptyList()
    override suspend fun deleteFilesByIds(ids: List<Long>) = Unit
    override suspend fun deleteFileById(id: Long) = Unit
    override suspend fun emptyRecycleBin() = Unit
    override suspend fun getVaultFileByName(name: String): FileItemEntity? = null
    override fun getAllVaultItems(): Flow<List<VaultItemEntity>> = flowOf(emptyList())
    override suspend fun insertVaultItem(item: VaultItemEntity): Long = item.id
    override suspend fun deleteVaultItemById(id: Long) = Unit
    override fun getAllPlugins(): Flow<List<PluginEntity>> = flowOf(emptyList())
    override suspend fun setPluginEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun insertPlugins(plugins: List<PluginEntity>) = Unit
    override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = flowOf(emptyList())
    override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long = item.id
    override suspend fun deleteCloudSyncItem(id: Long) = Unit
}
