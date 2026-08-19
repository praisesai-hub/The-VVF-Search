package com.example.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexDaoDefaultMethodTest {
    @Test
    fun replaceBuckets_removesStaleRowsAndIndexesOnlyValidSemanticFiles(): Unit = runBlocking {
        val dao = FakeSearchIndexDao()
        val unindexed = file(id = 1L, indexed = false, embedding = "")
        val indexed = file(id = 2L, indexed = true, embedding = "1,-1,0.5")

        dao.replaceSemanticAnnBuckets(unindexed)
        dao.replaceSemanticAnnBuckets(indexed)

        assertEquals(listOf(1L, 2L), dao.deletedFileIds)
        assertEquals(4, dao.insertedBuckets.size)
        assertTrue(dao.insertedBuckets.all { it.fileId == 2L && it.embeddingVersion == 7 })
    }

    @Test
    fun updateFilesAndIndex_updatesSourceRowsBeforeDerivedAnnBuckets(): Unit = runBlocking {
        val dao = FakeSearchIndexDao()
        val fileDao = mockk<FileDao>()
        val files = listOf(file(3L, true, "0.1,0.2"), file(4L, false, ""))
        coEvery { fileDao.updateFiles(files) } returns Unit

        dao.updateFilesAndSemanticAnnIndex(fileDao, files)

        coVerify(exactly = 1) { fileDao.updateFiles(files) }
        assertEquals(listOf(3L, 4L), dao.deletedFileIds)
        assertTrue(dao.insertedBuckets.all { it.fileId == 3L })
    }

    @Test
    fun ensureIndex_isIdempotentAndBuildsBatchesBeforeMarkingState(): Unit = runBlocking {
        val alreadyBuilt = FakeSearchIndexDao(indexExists = true)
        alreadyBuilt.ensureSemanticAnnIndex(7)
        assertTrue(alreadyBuilt.requestedOffsets.isEmpty())
        assertTrue(alreadyBuilt.markedVersions.isEmpty())

        val rebuilding = FakeSearchIndexDao(
            indexExists = false,
            rowsByOffset = mapOf(
                0 to listOf(file(5L, true, "1,0"), file(6L, false, "")),
                2 to emptyList()
            )
        )
        rebuilding.ensureSemanticAnnIndex(7)

        assertEquals(listOf(0, 2), rebuilding.requestedOffsets)
        assertEquals(listOf(7), rebuilding.markedVersions)
        assertEquals(4, rebuilding.insertedBuckets.size)
        assertTrue(rebuilding.insertedBuckets.all { it.fileId == 5L })
    }

    private fun file(id: Long, indexed: Boolean, embedding: String) = FileItemEntity(
        id = id,
        name = "file-$id",
        path = "/files/$id",
        category = "DOCUMENTS",
        sizeBytes = 1L,
        semanticEmbeddingVersion = 7,
        semanticIndexed = indexed,
        semanticEmbeddingString = embedding
    )

    private class FakeSearchIndexDao(
        private val indexExists: Boolean = false,
        private val rowsByOffset: Map<Int, List<FileItemEntity>> = emptyMap()
    ) : SearchIndexDao {
        val deletedFileIds = mutableListOf<Long>()
        val insertedBuckets = mutableListOf<SemanticAnnBucketEntity>()
        val markedVersions = mutableListOf<Int>()
        val requestedOffsets = mutableListOf<Int>()

        override fun observeFilesByFts(
            ftsQuery: String,
            category: String?,
            limit: Int
        ): Flow<List<FileItemEntity>> = flowOf(emptyList())

        override suspend fun insertSemanticAnnBuckets(buckets: List<SemanticAnnBucketEntity>) {
            insertedBuckets += buckets
        }

        override suspend fun deleteSemanticAnnBucketsForFile(fileId: Long) {
            deletedFileIds += fileId
        }

        override suspend fun markSemanticAnnIndexBuilt(state: SemanticAnnIndexStateEntity) {
            markedVersions += state.embeddingVersion
        }

        override suspend fun hasSemanticAnnIndex(embeddingVersion: Int): Boolean = indexExists

        override suspend fun getSemanticRowsForAnnIndex(
            embeddingVersion: Int,
            limit: Int,
            offset: Int
        ): List<FileItemEntity> {
            requestedOffsets += offset
            return rowsByOffset[offset].orEmpty()
        }

        override fun observeSemanticCandidates(
            embeddingVersion: Int,
            bucketKeys: List<String>,
            limit: Int
        ): Flow<List<FileItemEntity>> = flowOf(emptyList())
    }
}
