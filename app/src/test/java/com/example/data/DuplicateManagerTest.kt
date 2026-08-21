package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DuplicateManagerTest {

    class FakeFileDao : FileDao {
        val filesMap = mutableMapOf<Long, FileItemEntity>()
        var moveFilesToRecycleBinAtomicCallCount = 0
        val movedFilesList = mutableListOf<FileItemEntity>()
        val duplicateFiles = mutableListOf<FileItemEntity>()

        override suspend fun getFileById(id: Long): FileItemEntity? {
            return filesMap[id]
        }

        override suspend fun findInRecycleBinByHash(hash: String): FileItemEntity? {
            return movedFilesList.find { it.md5Hash == hash && it.isRecycleBin }
        }

        override suspend fun moveFilesToRecycleBinAtomic(files: List<FileItemEntity>) {
            moveFilesToRecycleBinAtomicCallCount++
            movedFilesList.addAll(files)
            files.forEach {
                filesMap[it.id] = it
            }
        }

        override suspend fun updateFiles(files: List<FileItemEntity>) {
            movedFilesList.addAll(files)
        }

        // Dummy overrides for other required interface methods
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
        override fun getDuplicateFilesByHash(): Flow<List<FileItemEntity>> = flowOf(duplicateFiles.toList())
        override suspend fun insertFile(file: FileItemEntity): Long = 0L
        override suspend fun insertFiles(files: List<FileItemEntity>) {}
        override suspend fun updateFile(file: FileItemEntity) {}
        override suspend fun getFileByPath(path: String): FileItemEntity? = filesMap.values.find { it.path == path }
        override suspend fun insertFileDirect(file: FileItemEntity): Long {
            filesMap[file.id] = file
            return file.id
        }
        override suspend fun getAllOrdinaryFilesDirect(): List<FileItemEntity> = filesMap.values.filter { !it.isVault && !it.isRecycleBin }
        override suspend fun deleteFilesByIds(ids: List<Long>) {
            ids.forEach { filesMap.remove(it) }
        }
        override suspend fun deleteFileById(id: Long) {}
        override suspend fun emptyRecycleBin() {}
        override suspend fun getVaultFileByName(name: String): FileItemEntity? = null
        override fun getAllVaultItems(): Flow<List<VaultItemEntity>> = flowOf(emptyList())
        override suspend fun insertVaultItem(item: VaultItemEntity): Long = 0L
        override suspend fun deleteVaultItemById(id: Long) {}
        override fun getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = flowOf(emptyList())
        override suspend fun insertCloudSyncItem(item: CloudSyncItemEntity): Long = 0L
        override suspend fun deleteCloudSyncItem(id: Long) {}
        override fun getAllPlugins(): Flow<List<PluginEntity>> = flowOf(emptyList())
        override suspend fun setPluginEnabled(id: String, enabled: Boolean) {}
        override suspend fun insertPlugins(plugins: List<PluginEntity>) {}
    }

    @Test
    fun same_file_id_in_multiple_duplicate_groups_results_in_single_recycle_bin_operation() = runBlocking {
        val fakeDao = FakeFileDao()
        
        val testFile = FileItemEntity(
            id = 101L,
            name = "test_duplicate.png",
            path = "/storage/emulated/0/Pictures/test_duplicate.png",
            category = "IMAGES",
            sizeBytes = 2048L,
            md5Hash = "content_hash_12345"
        )
        val matchingFile = testFile.copy(id = 102L, name = "matching_duplicate.png", path = "/storage/emulated/0/Pictures/matching_duplicate.png")
        fakeDao.filesMap[testFile.id] = testFile
        fakeDao.filesMap[matchingFile.id] = matchingFile
        fakeDao.duplicateFiles.addAll(listOf(testFile, matchingFile))

        val duplicateManager = DuplicateManager(fakeDao)

        // Simulate selection of the same file ID multiple times
        val selectedIds = setOf(101L, 101L)

        duplicateManager.cleanSelectedDuplicates(selectedIds)

        // Verify the database update was atomic and performed once
        assertEquals(1, fakeDao.moveFilesToRecycleBinAtomicCallCount)
        assertEquals(1, fakeDao.movedFilesList.size)
        
        val movedFile = fakeDao.movedFilesList.first()
        assertEquals(101L, movedFile.id)
        assertTrue(movedFile.isRecycleBin)
    }

    @Test
    fun visualOrSemanticCandidate_withoutExactHash_isNeverMovedToTrash() = runBlocking {
        val fakeDao = FakeFileDao()
        val candidate = FileItemEntity(
            id = 501L,
            name = "visually-similar.jpg",
            path = "/pictures/visually-similar.jpg",
            category = "IMAGES",
            sizeBytes = 100L,
            visualSimilarityHash = "0011223344556677",
            md5Hash = ""
        )
        fakeDao.filesMap[candidate.id] = candidate

        DuplicateManager(fakeDao).cleanSelectedDuplicates(setOf(candidate.id))

        assertEquals(0, fakeDao.moveFilesToRecycleBinAtomicCallCount)
        assertTrue(fakeDao.movedFilesList.isEmpty())
        assertFalse(fakeDao.filesMap.getValue(candidate.id).isRecycleBin)
    }

    @Test
    fun content_hash_idempotency_check_prevents_reprocessing_already_recycled_hash() = runBlocking {
        val fakeDao = FakeFileDao()
        
        val recycledFile = FileItemEntity(
            id = 101L,
            name = "test_duplicate.png",
            path = "/storage/emulated/0/Pictures/test_duplicate.png",
            category = "IMAGES",
            sizeBytes = 2048L,
            md5Hash = "content_hash_12345",
            isRecycleBin = true
        )
        val activeFile = FileItemEntity(
            id = 102L,
            name = "test_duplicate_copy.png",
            path = "/storage/emulated/0/Pictures/test_duplicate_copy.png",
            category = "IMAGES",
            sizeBytes = 2048L,
            md5Hash = "content_hash_12345",
            isRecycleBin = false
        )
        
        fakeDao.filesMap[recycledFile.id] = recycledFile
        fakeDao.filesMap[activeFile.id] = activeFile
        fakeDao.movedFilesList.add(recycledFile) // Pre-seed recycle bin

        val duplicateManager = DuplicateManager(fakeDao)

        // Try to clean active duplicate copy
        duplicateManager.cleanSelectedDuplicates(setOf(102L))

        // Should detect pre-existing identical content hash in trash, and skip to avoid re-deletion
        assertEquals(0, fakeDao.moveFilesToRecycleBinAtomicCallCount)
        assertEquals(1, fakeDao.movedFilesList.size)
    }

    @Test
    fun missingOrAlreadyRecycledSelections_areNoOpsWithoutAtomicWrite() = runBlocking {
        val fakeDao = FakeFileDao()
        val alreadyRecycled = FileItemEntity(
            id = 7L,
            name = "already-recycled.jpg",
            path = "/trash/already-recycled.jpg",
            category = "IMAGES",
            sizeBytes = 100L,
            isRecycleBin = true
        )
        fakeDao.filesMap[alreadyRecycled.id] = alreadyRecycled
        val duplicateManager = DuplicateManager(fakeDao)

        duplicateManager.cleanSelectedDuplicates(setOf(7L, 999L))

        assertEquals(0, fakeDao.moveFilesToRecycleBinAtomicCallCount)
        assertTrue(fakeDao.movedFilesList.isEmpty())
        assertTrue(fakeDao.filesMap.getValue(7L).isRecycleBin)
    }

    @Test
    fun cleanSelectedDuplicates_preservesOriginalPathAndUsesSingleAtomicBatch() = runBlocking {
        val fakeDao = FakeFileDao()
        val withoutOriginalPath = FileItemEntity(
            id = 11L,
            name = "first.jpg",
            path = "/files/first.jpg",
            category = "IMAGES",
            sizeBytes = 10L,
            md5Hash = ""
        )
        val withOriginalPath = FileItemEntity(
            id = 12L,
            name = "second.jpg",
            path = "/files/second.jpg",
            originalPath = "/original/second.jpg",
            category = "IMAGES",
            sizeBytes = 20L,
            md5Hash = "hash-two"
        )
        val exactMatch = withOriginalPath.copy(id = 13L, name = "second-copy.jpg", path = "/files/second-copy.jpg")
        fakeDao.filesMap[withoutOriginalPath.id] = withoutOriginalPath
        fakeDao.filesMap[withOriginalPath.id] = withOriginalPath
        fakeDao.filesMap[exactMatch.id] = exactMatch
        fakeDao.duplicateFiles.addAll(listOf(withOriginalPath, exactMatch))
        val duplicateManager = DuplicateManager(fakeDao)

        duplicateManager.cleanSelectedDuplicates(setOf(11L, 12L))

        println("DUPLICATE_STATE=files=${fakeDao.filesMap} moved=${fakeDao.movedFilesList}")
        assertEquals("Atomic move calls: ${fakeDao.moveFilesToRecycleBinAtomicCallCount}", 1, fakeDao.moveFilesToRecycleBinAtomicCallCount)
        assertEquals("Moved files: ${fakeDao.movedFilesList}", 1, fakeDao.movedFilesList.size)
        val movedWithoutOriginal = fakeDao.filesMap.getValue(11L)
        val movedWithOriginal = fakeDao.filesMap.getValue(12L)
        assertFalse(movedWithoutOriginal.isRecycleBin)
        assertTrue(movedWithOriginal.isRecycleBin)
        assertEquals("", movedWithoutOriginal.originalPath)
        assertEquals("/original/second.jpg", movedWithOriginal.originalPath)
        assertEquals(0L, movedWithoutOriginal.deletedTimestampMs)
        assertTrue(movedWithOriginal.deletedTimestampMs > 0L)
    }
}
