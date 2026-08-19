package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

private const val ANN_REBUILD_BATCH_SIZE = 250
private const val ANN_BUCKET_INSERT_BATCH_SIZE = 900

/**
 * Separate index DAO. FileDao stays focused on source-of-truth file metadata, while this DAO
 * owns virtual FTS5 and derived ANN tables.
 */
@Suppress("TooManyFunctions") // Cohesive DAO boundary for FTS5 and ANN index maintenance.
@Dao
interface SearchIndexDao {
    @RawQuery(observedEntities = [FileItemEntity::class])
    fun observeFilesByFtsQuery(query: SupportSQLiteQuery): Flow<List<FileItemEntity>>

    /** The FTS5 virtual table is derived from `files`, which is the observable source of truth. */
    fun observeFilesByFts(
        ftsQuery: String,
        category: String?,
        limit: Int
    ): Flow<List<FileItemEntity>> = observeFilesByFtsQuery(
        SimpleSQLiteQuery(
            """
            SELECT files.* FROM files
            JOIN file_search_fts ON file_search_fts.rowid = files.id
            WHERE file_search_fts MATCH ?
              AND files.isVault = 0 AND files.isRecycleBin = 0
              AND (? IS NULL OR files.category = ?)
            ORDER BY bm25(file_search_fts), files.dateModifiedMs DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf<Any?>(ftsQuery, category, category, limit)
        )
    )

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
        for (file in files) {
            replaceSemanticAnnBuckets(file)
        }
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
            val buckets = sourceBatch.flatMap { file ->
                SemanticAnnIndex.bucketsFor(
                    fileId = file.id,
                    embeddingVersion = embeddingVersion,
                    embedding = file.semanticEmbeddingString
                )
            }
            for (bucketBatch in buckets.chunked(ANN_BUCKET_INSERT_BATCH_SIZE)) {
                insertSemanticAnnBuckets(bucketBatch)
            }
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
