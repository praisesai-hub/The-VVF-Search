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
        Index(value = ["ocrText"]),
        Index(value = ["contentIdentityVersion"])
    ]
)
data class FileItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val path: String,
    val originalPath: String = "",
    val category: String,
    val sizeBytes: Long,
    val dateModifiedMs: Long = System.currentTimeMillis(),
    val md5Hash: String = "",
    val ocrText: String = "",
    val tags: String = "",
    val isVault: Boolean = false,
    val isRecycleBin: Boolean = false,
    val deletedTimestampMs: Long = 0L,
    val visualSimilarityHash: String = "",
    val semanticEmbeddingVersion: Int = 0,
    val semanticIndexed: Boolean = false,
    val semanticEmbeddingString: String = "",
    val videoFingerprintVersion: Int = 0,
    val videoSampleHashes: String = "",
    val videoDurationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoAudioSignature: String = "",
    val videoChunkHash: String = "",
    val documentCandidateFingerprint: String = "",
    val contentIdentityVersion: Long = 1L
)

@JsonClass(generateAdapter = true)
data class DuplicateGroup(
    val title: String,
    val level: Int,
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
    val provider: String,
    val fileName: String,
    val filePath: String = "",
    val fileSize: Long,
    val status: String,
    val lastSyncedMs: Long = System.currentTimeMillis(),
    val isCore: Boolean = false,
    val operationId: String = "op-${UUID.randomUUID()}",
    val leaseOwner: String? = null,
    val leaseExpiresAtMs: Long = 0L,
    val attemptCount: Int = 0,
    val startedAtMs: Long = 0L,
    val heartbeatAtMs: Long = 0L,
    val completedAtMs: Long = 0L,
    val lastErrorCode: String? = null,
    val remoteFileId: String = "",
    val resumableSessionUri: String = "",
    val resumableBytesCommitted: Long = 0L
)

@JsonClass(generateAdapter = true)
@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val pluginId: String,
    val name: String,
    val category: String,
    val description: String,
    val isEnabled: Boolean,
    val isCore: Boolean
)
