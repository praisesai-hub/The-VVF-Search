package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val ANN_REBUILD_BATCH_SIZE = 250
private const val ANN_BUCKET_INSERT_BATCH_SIZE = 900

/**
 * Separate index DAO. FileDao stays focused on source-of-truth file metadata, while this DAO
 * owns virtual FTS5 and derived ANN tables.
 */
@Dao
interface SearchIndexDao {
    @SkipQueryVerification
    @Query(
        """
        SELECT files.* FROM files
        JOIN file_search_fts ON file_search_fts.rowid = files.id
        WHERE file_search_fts MATCH :ftsQuery
          AND files.isVault = 0 AND files.isRecycleBin = 0
          AND (:category IS NULL OR files.category = :category)
        ORDER BY bm25(file_search_fts), files.dateModifiedMs DESC
        LIMIT :limit
        """
    )
    fun observeFilesByFts(
        ftsQuery: String,
        category: String?,
        limit: Int
    ): Flow<List<FileItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemanticAnnBuckets(buckets: List<SemanticAnnBucketEntity>)

    @Query("DELETE FROM semantic_ann_buckets WHERE fileId = :fileId")
    suspend fun deleteSemanticAnnBucketsForFile(fileId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markSemanticAnnIndexBuilt(state: SemanticAnnIndexStateEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM semantic_ann_state WHERE embeddingVersion = :embeddingVersion)")
    suspend fun hasSemanticAnnIndex(embeddingVersion: Int): Boolean

    @Query(
        """
        SELECT * FROM files
        WHERE isVault = 0 AND isRecycleBin = 0
          AND semanticIndexed = 1
          AND semanticEmbeddingVersion = :embeddingVersion
          AND semanticEmbeddingString != ''
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSemanticRowsForAnnIndex(
        embeddingVersion: Int,
        limit: Int,
        offset: Int
    ): List<FileItemEntity>

    @Transaction
    suspend fun replaceSemanticAnnBuckets(file: FileItemEntity) {
        deleteSemanticAnnBucketsForFile(file.id)
        if (file.semanticIndexed && file.semanticEmbeddingString.isNotBlank()) {
            val buckets = SemanticAnnIndex.bucketsFor(
                fileId = file.id,
                embeddingVersion = file.semanticEmbeddingVersion,
                embedding = file.semanticEmbeddingString
            )
            if (buckets.isNotEmpty()) insertSemanticAnnBuckets(buckets)
        }
    }

    @Transaction
    suspend fun updateFilesAndSemanticAnnIndex(fileDao: FileDao, files: List<FileItemEntity>) {
        fileDao.updateFiles(files)
        files.forEach(::replaceSemanticAnnBuckets)
    }

    @Transaction
    suspend fun ensureSemanticAnnIndex(embeddingVersion: Int) {
        if (hasSemanticAnnIndex(embeddingVersion)) return
        var offset = 0
        while (true) {
            val sourceBatch = getSemanticRowsForAnnIndex(
                embeddingVersion = embeddingVersion,
                limit = ANN_REBUILD_BATCH_SIZE,
                offset = offset
            )
            if (sourceBatch.isEmpty()) break
            sourceBatch
                .flatMap { file ->
                    SemanticAnnIndex.bucketsFor(
                        fileId = file.id,
                        embeddingVersion = embeddingVersion,
                        embedding = file.semanticEmbeddingString
                    )
                }
                .chunked(ANN_BUCKET_INSERT_BATCH_SIZE)
                .forEach(::insertSemanticAnnBuckets)
            offset += sourceBatch.size
        }
        markSemanticAnnIndexBuilt(SemanticAnnIndexStateEntity(embeddingVersion))
    }

    @Query(
        """
        SELECT DISTINCT files.* FROM files
        JOIN semantic_ann_buckets ON semantic_ann_buckets.fileId = files.id
        WHERE semantic_ann_buckets.embeddingVersion = :embeddingVersion
          AND semantic_ann_buckets.bucketKey IN (:bucketKeys)
          AND files.isVault = 0 AND files.isRecycleBin = 0
          AND files.semanticIndexed = 1
          AND files.semanticEmbeddingVersion = :embeddingVersion
        LIMIT :limit
        """
    )
    fun observeSemanticCandidates(
        embeddingVersion: Int,
        bucketKeys: List<String>,
        limit: Int
    ): Flow<List<FileItemEntity>>
}
