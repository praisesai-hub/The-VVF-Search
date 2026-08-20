package com.example.data

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityJsonAdapterCoverageTest {
    private val moshi = Moshi.Builder().build()

    @Test
    fun fileItemRoundTripPreservesFingerprintAndIndexEvidence() {
        val source = FileItemEntity(
            id = 7L,
            name = "receipt.pdf",
            path = "/data/receipt.pdf",
            originalPath = "/source/receipt.pdf",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 2048L,
            dateModifiedMs = 1234L,
            md5Hash = "content-hash",
            ocrText = "receipt text",
            tags = "finance",
            visualSimilarityHash = "candidate-hash",
            semanticEmbeddingVersion = 2,
            semanticIndexed = true,
            semanticEmbeddingString = "0.1,0.2",
            videoFingerprintVersion = 1,
            videoSampleHashes = "a;b;c",
            videoDurationMs = 100L,
            videoWidth = 1920,
            videoHeight = 1080,
            videoAudioSignature = "audio",
            videoChunkHash = "chunk",
            documentCandidateFingerprint = "document-candidate",
        )

        val adapter = moshi.adapter(FileItemEntity::class.java)
        val restored = requireNotNull(adapter.fromJson(requireNotNull(adapter.toJson(source))))

        assertEquals(source, restored)
    }

    @Test
    fun vaultCloudPluginAndDuplicateGroupRoundTrip() {
        val vault = VaultItemEntity(
            id = 9L,
            originalName = "vault.txt",
            encryptedName = "encrypted.bin",
            encryptedFilePath = "/vault/encrypted.bin",
            ivBase64 = "iv",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 33L,
            encryptedAtMs = 456L,
            isBiometricProtected = false,
            vaultFormatVersion = 1,
        )
        val cloud = CloudSyncItemEntity(
            id = 11L,
            provider = "GOOGLE_DRIVE",
            fileName = "sync.txt",
            filePath = "/sync/sync.txt",
            fileSize = 44L,
            status = "UPLOADING",
            lastSyncedMs = 567L,
            isCore = true,
            operationId = "operation-11",
            leaseOwner = "worker",
            leaseExpiresAtMs = 600L,
            attemptCount = 2,
            startedAtMs = 500L,
            heartbeatAtMs = 550L,
            completedAtMs = 0L,
            lastErrorCode = null,
            remoteFileId = "remote-11",
            resumableSessionUri = "https://upload.example/session",
            resumableBytesCommitted = 12L,
        )
        val plugin = PluginEntity("plugin", "Plugin", "TEST", "Description", true, false)
        val duplicate = DuplicateGroup(
            title = "Exact duplicate",
            level = 1,
            similarityScore = 100,
            files = listOf(
                FileItemEntity(
                    id = 15L,
                    name = "duplicate.txt",
                    path = "/data/duplicate.txt",
                    category = FileCategory.DOCUMENTS.name,
                    sizeBytes = 1L,
                ),
            ),
        )

        val vaultAdapter = moshi.adapter(VaultItemEntity::class.java)
        val cloudAdapter = moshi.adapter(CloudSyncItemEntity::class.java)
        val pluginAdapter = moshi.adapter(PluginEntity::class.java)
        val duplicateAdapter = moshi.adapter(DuplicateGroup::class.java)

        assertEquals(vault, vaultAdapter.fromJson(requireNotNull(vaultAdapter.toJson(vault))))
        assertEquals(cloud, cloudAdapter.fromJson(requireNotNull(cloudAdapter.toJson(cloud))))
        assertEquals(plugin, pluginAdapter.fromJson(requireNotNull(pluginAdapter.toJson(plugin))))
        assertEquals(duplicate, duplicateAdapter.fromJson(requireNotNull(duplicateAdapter.toJson(duplicate))))
    }
}
