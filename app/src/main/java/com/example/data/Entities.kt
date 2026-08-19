package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass
import java.util.UUID

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
    val semanticEmbeddingString: String = "", // comma-separated vector floats
    val videoFingerprintVersion: Int = 0,
    val videoSampleHashes: String = "", // semicolon-separated 64-bit dHashes
    val videoDurationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoAudioSignature: String = "",
    val videoChunkHash: String = ""
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

@JsonClass(generateAdapter = true)
@Entity(
    tableName = "cloud_sync",
    indices = [Index(value = ["operationId"], unique = true)]
)
data class CloudSyncItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String, // GOOGLE_DRIVE, ONEDRIVE, DROPBOX, NEXTCLOUD, S3, NAS
    val fileName: String,
    val filePath: String = "",
    val fileSize: Long,
    val status: String, // SYNCED, PENDING, QUEUED, UPLOADING, FAILED
    val lastSyncedMs: Long = System.currentTimeMillis(),
    val isCore: Boolean = false,
    val operationId: String = "op-${UUID.randomUUID()}",
    val leaseOwner: String? = null,
    val leaseExpiresAtMs: Long = 0L,
    val attemptCount: Int = 0,
    val startedAtMs: Long = 0L,
    val heartbeatAtMs: Long = 0L,
    val completedAtMs: Long = 0L,
    val lastErrorCode: String? = null
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
