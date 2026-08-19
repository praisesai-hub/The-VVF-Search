package com.example.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DuplicateManagerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var recycleBin: File

    @Before
    fun setUp(): Unit {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        testRoot = File(context.cacheDir, "duplicate-manager-instrumented-${System.nanoTime()}")
        assertTrue(testRoot.mkdirs())
        recycleBin = File(context.getExternalFilesDir(null) ?: context.filesDir, ".recycle_bin")
    }

    @After
    fun tearDown(): Unit {
        testRoot.deleteRecursively()
        recycleBin.deleteRecursively()
    }

    private fun createSource(name: String, content: String = "duplicate payload"): File =
        File(testRoot, name).also {
            it.writeText(content)
        }

    private fun fileItem(
        id: Long,
        source: File,
        md5Hash: String = "hash-$id",
        originalPath: String = "",
        isRecycleBin: Boolean = false,
    ): FileItemEntity = FileItemEntity(
        id = id,
        name = source.name,
        path = source.absolutePath,
        originalPath = originalPath,
        category = "DOCUMENTS",
        sizeBytes = source.length(),
        md5Hash = md5Hash,
        isRecycleBin = isRecycleBin,
    )

    @Test
    fun movesRealFileToTrash_andPersistsRecycleBinMetadata(): Unit = runBlocking {
        val source = createSource("duplicate.txt")
        val dao = InstrumentedDuplicateFileDao()
        val matching = fileItem(11L, createSource("duplicate-matching.txt"))
        dao.filesById[1L] = fileItem(1L, source, md5Hash = "exact-hash")
        dao.filesById[11L] = matching.copy(md5Hash = "exact-hash")
        dao.duplicateFiles.addAll(listOf(dao.filesById.getValue(1L), dao.filesById.getValue(11L)))

        DuplicateManager(dao, context).cleanSelectedDuplicates(setOf(1L))

        assertFalse(source.exists())
        assertEquals(1, dao.movedFiles.size)
        val moved = dao.movedFiles.single()
        assertTrue(moved.isRecycleBin)
        assertEquals(source.absolutePath, moved.originalPath)
        assertNotEquals(source.absolutePath, moved.path)
        assertTrue(moved.path.contains(".recycle_bin"))
        assertEquals("duplicate payload", File(moved.path).readText())
        assertTrue(moved.deletedTimestampMs > 0L)
    }

    @Test
    fun hashlessVisualCandidate_isNotMovedToTrash(): Unit = runBlocking {
        val source = createSource("duplicate-with-original.txt", "preserve original")
        val dao = InstrumentedDuplicateFileDao()
        dao.filesById[2L] = fileItem(
            id = 2L,
            source = source,
            md5Hash = "",
        )

        DuplicateManager(dao, context).cleanSelectedDuplicates(setOf(2L))

        assertTrue(dao.movedFiles.isEmpty())
        assertTrue(source.exists())
    }

    @Test
    fun hashAlreadyInRecycleBin_skipsPhysicalMoveAndBatchUpdate(): Unit = runBlocking {
        val source = createSource("already-recycled-hash.txt")
        val dao = InstrumentedDuplicateFileDao()
        dao.filesById[3L] = fileItem(3L, source, md5Hash = "same-hash")
        dao.recycleBinByHash["same-hash"] = fileItem(
            id = 99L,
            source = File(testRoot, "existing-trash.txt"),
            md5Hash = "same-hash",
            isRecycleBin = true,
        )

        DuplicateManager(dao, context).cleanSelectedDuplicates(setOf(3L))

        assertTrue(source.exists())
        assertTrue(dao.movedFiles.isEmpty())
    }

    @Test
    fun missingAndAlreadyRecycledIds_areIgnored(): Unit = runBlocking {
        val source = createSource("already-recycled.txt")
        val dao = InstrumentedDuplicateFileDao()
        dao.filesById[4L] = fileItem(4L, source, isRecycleBin = true)

        DuplicateManager(dao, context).cleanSelectedDuplicates(setOf(404L, 4L))

        assertTrue(source.exists())
        assertTrue(dao.movedFiles.isEmpty())
    }

    @Test
    fun missingPhysicalFile_skipsBatchUpdate(): Unit = runBlocking {
        val missing = File(testRoot, "missing.txt")
        val dao = InstrumentedDuplicateFileDao()
        dao.filesById[5L] = fileItem(5L, missing)

        DuplicateManager(dao, context).cleanSelectedDuplicates(setOf(5L))

        assertTrue(dao.movedFiles.isEmpty())
    }

    @Test
    fun withoutContext_updatesRecycleBinMetadataWithoutMovingPhysicalFile(): Unit = runBlocking {
        val source = createSource("logical-only.txt")
        val dao = InstrumentedDuplicateFileDao()
        val exact = fileItem(6L, source, md5Hash = "logical-hash")
        val matching = exact.copy(id = 7L)
        dao.filesById[6L] = exact
        dao.filesById[7L] = matching
        dao.duplicateFiles.addAll(listOf(exact, matching))

        DuplicateManager(dao).cleanSelectedDuplicates(setOf(6L))

        assertTrue(source.exists())
        val moved = dao.movedFiles.single()
        assertEquals(source.absolutePath, moved.path)
        assertEquals(source.absolutePath, moved.originalPath)
        assertTrue(moved.isRecycleBin)
    }
}

private class InstrumentedDuplicateFileDao : FileDao {
    val filesById = mutableMapOf<Long, FileItemEntity>()
    val recycleBinByHash = mutableMapOf<String, FileItemEntity>()
    val duplicateFiles = mutableListOf<FileItemEntity>()
    var movedFiles: List<FileItemEntity> = emptyList()

    override suspend fun getFileById(id: Long): FileItemEntity? = filesById[id]
    override suspend fun getFileByName(name: String): FileItemEntity? = null
    override fun getOcrScannedFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> = flowOf(emptyList())
    override fun getAllActiveFiles(): Flow<List<FileItemEntity>> = flowOf(emptyList())
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
    override suspend fun updateFiles(files: List<FileItemEntity>): Unit = Unit
    override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? = recycleBinByHash[hash]
    override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>): Unit {
        movedFiles = files
    }
    override fun getDuplicateFilesByHash(): Flow<List<FileItemEntity>> = flowOf(duplicateFiles.toList())
    override suspend fun insertFile(file: FileItemEntity): Long = 0L
    override suspend fun insertFiles(files: List<FileItemEntity>): Unit = Unit
    override suspend fun updateFile(file: FileItemEntity): Unit = Unit
    override suspend fun getFileByPath(path: String): FileItemEntity? = null
    override suspend fun insertFileDirect(file: FileItemEntity): Long = 0L
    override suspend fun getAllOrdinaryFilesDirect(): List<FileItemEntity> = emptyList()
    override suspend fun deleteFilesByIds(ids: List<Long>): Unit = Unit
    override suspend fun deleteFileById(id: Long): Unit = Unit
    override suspend fun emptyRecycleBin(): Unit = Unit
    override suspend fun getVaultFileByName(name: String): FileItemEntity? = null
    override fun getAllVaultItems(): Flow<List<VaultItemEntity>> = flowOf(emptyList())
    override suspend fun insertVaultItem(item: VaultItemEntity): Long = 0L
    override suspend fun deleteVaultItemById(id: Long): Unit = Unit
    override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = flowOf(emptyList())
    override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long = 0L
    override suspend fun deleteCloudSyncItem(id: Long): Unit = Unit
    override fun getAllPlugins(): Flow<List<PluginEntity>> = flowOf(emptyList())
    override suspend fun setPluginEnabled(id: String, enabled: Boolean): Unit = Unit
    override suspend fun insertPlugins(plugins: List<PluginEntity>): Unit = Unit
}
