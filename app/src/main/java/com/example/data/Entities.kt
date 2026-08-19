package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

enum class FileCategory {
    IMAGES, DOCUMENTS, AUDIO, VIDEO, ARCHIVES, APKS, DOWNLOADS, OTHER
}

@JsonClass(generateAdapter = true)
@Entity(
    tableName = "files",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["category"]),
        Index(value = ["md5Hash"]),
        Index(value = ["isVault"]),
        Index(value = ["isRecycleBin"]),
        Index(value = ["name"]),
        Index(value = ["tags"]),
        Index(value = ["ocrText"])
    ]
)
data class FileItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val path: String,
    val originalPath: String = "",
    val category: String, // IMAGES, DOCUMENTS, etc.
    val sizeBytes: Long,
    val dateModifiedMs: Long = System.currentTimeMillis(),
    val md5Hash: String = "",
    val ocrText: String = "",
    val tags: String = "", // comma-separated tags
    val isVault: Boolean = false,
    val isRecycleBin: Boolean = false,
    val deletedTimestampMs: Long = 0L,
    val visualSimilarityHash: String = "", // for visual duplicate level 3-4
    val semanticEmbeddingVersion: Int = 0,
    val semanticIndexed: Boolean = false,
    val semanticEmbeddingString: String = "" // comma-separated vector floats
)

/**
 * Approximate-nearest-neighbor locality-sensitive-hash buckets for a semantic vector. The file
 * table remains the source of truth; this table only limits the candidate set before cosine
 * reranking.
 */
@Entity(
    tableName = "semantic_ann_buckets",
    primaryKeys = ["fileId", "embeddingVersion", "bucketKey"],
    indices = [
        Index(value = ["embeddingVersion", "bucketKey"]),
        Index(value = ["fileId"])
    ]
)
data class SemanticAnnBucketEntity(
    val fileId: Long,
    val embeddingVersion: Int,
    val bucketKey: String
)

@Entity(tableName = "semantic_ann_state")
data class SemanticAnnIndexStateEntity(
    @PrimaryKey val embeddingVersion: Int,
    val indexedAtMs: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class DuplicateGroup(
    val title: String,
    val level: Int, // 1 for exact hash, 2 for metadata/visual
    val similarityScore: Int,
    val files: List<FileItemEntity>
)

@JsonClass(generateAdapter = true)
@Entity(tableName = "vault_items")
data class VaultItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalName: String,
    val encryptedName: String,
    val encryptedFilePath: String = "",
    val ivBase64: String = "",
    val category: String,
    val sizeBytes: Long,
    val encryptedAtMs: Long = System.currentTimeMillis(),
    val isBiometricProtected: Boolean = true,
    val vaultFormatVersion: Int = 1
)

object VaultOperationType {
    const val ENCRYPT = "ENCRYPT"
    const val RESTORE = "RESTORE"
}

object VaultOperationState {
    const val PREPARED = "PREPARED"
    const val ENCRYPTED = "ENCRYPTED"
    const val VERIFIED = "VERIFIED"
    const val SOURCE_REMOVAL_PENDING = "SOURCE_REMOVAL_PENDING"
    const val SOURCE_REMOVED = "SOURCE_REMOVED"
    const val RESTORE_WRITE_PENDING = "RESTORE_WRITE_PENDING"
    const val RESTORED = "RESTORED"
    const val VAULT_REMOVAL_PENDING = "VAULT_REMOVAL_PENDING"
    const val VAULT_REMOVED = "VAULT_REMOVED"
    const val METADATA_COMMITTED = "METADATA_COMMITTED"
    const val COMPLETED = "COMPLETED"
    const val RECOVERY_REQUIRED = "RECOVERY_REQUIRED"
}

/**
 * Durable intent log for multi-resource vault operations. The file system and Room database
 * cannot share one transaction, so each irreversible step is recorded before it runs.
 */
@JsonClass(generateAdapter = true)
@Entity(
    tableName = "vault_operations",
    indices = [Index(value = ["state"]), Index(value = ["operationType"])]
)
data class VaultOperationEntity(
    @PrimaryKey val id: String,
    val operationType: String,
    val state: String = VaultOperationState.PREPARED,
    val sourceFileId: Long = 0L,
    val vaultItemId: Long = 0L,
    val sourcePath: String = "",
    val encryptedFilePath: String = "",
    val encryptedFileName: String = "",
    val restoreDestinationPath: String = "",
    val originalName: String = "",
    val category: String = "",
    val sizeBytes: Long = 0L,
    val ivBase64: String = "",
    val isBiometricProtected: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val recoveryError: String = ""
)

@JsonClass(generateAdapter = true)
@Entity(
    tableName = "cloud_sync",
    indices = [
        Index(value = ["idempotencyKey"]),
        Index(value = ["provider", "localFileStableId", "contentHash"])
    ]
)
data class CloudSyncItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // GOOGLE_DRIVE is the only executable adapter in this release; other identifiers are reserved.
    val provider: String,
    val fileName: String,
    val filePath: String = "",
    val fileSize: Long,
    val status: String, // SYNCED, PENDING, UPLOADING, FAILED
    val lastSyncedMs: Long = System.currentTimeMillis(),
    val isCore: Boolean = false,
    val remoteFileId: String = "",
    val idempotencyKey: String = "",
    val remoteRevisionId: String = "",
    val localFileStableId: String = "",
    val contentHash: String = "",
    val uploadSessionUri: String = "",
    val lastAttemptAtMs: Long = 0L,
    val attemptCount: Int = 0,
    val etag: String = ""
)

@JsonClass(generateAdapter = true)
@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val pluginId: String,
    val name: String,
    val category: String, // OCR, SEMANTIC_SEARCH, CLOUD_PROVIDER, ARCHIVER
    val description: String,
    val isEnabled: Boolean,
    val isCore: Boolean
)
