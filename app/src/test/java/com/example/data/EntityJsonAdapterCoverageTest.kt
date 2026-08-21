package com.example.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

        val vaultAdapter = directVaultAdapter()
        val cloudAdapter = moshi.adapter(CloudSyncItemEntity::class.java)
        val pluginAdapter = moshi.adapter(PluginEntity::class.java)
        val duplicateAdapter = moshi.adapter(DuplicateGroup::class.java)

        assertEquals(vault, vaultAdapter.fromJson(requireNotNull(vaultAdapter.toJson(vault))))
        assertEquals(cloud, cloudAdapter.fromJson(requireNotNull(cloudAdapter.toJson(cloud))))
        assertEquals(plugin, pluginAdapter.fromJson(requireNotNull(pluginAdapter.toJson(plugin))))
        assertEquals(duplicate, duplicateAdapter.fromJson(requireNotNull(duplicateAdapter.toJson(duplicate))))
    }

    @Test
    fun generatedVaultAdapterRejectsMissingRequiredFieldsAndSerializesAllVaultFields() {
        val adapter = directVaultAdapter()
        val source = VaultItemEntity(
            id = 24L,
            originalName = "contract.txt",
            encryptedName = "contract.vvf",
            encryptedFilePath = "/vault/contract.vvf",
            ivBase64 = "iv-value",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 4096L,
            encryptedAtMs = 99L,
            isBiometricProtected = true,
            vaultFormatVersion = 2,
        )

        val json = adapter.toJson(source)

        assertEquals(source, adapter.fromJson(json))
        assertThrows(JsonDataException::class.java) {
            adapter.fromJson("""{"id":24,"encryptedName":"contract.vvf"}""")
        }
    }

    @Test
    fun generatedVaultAdapter_appliesDefaultsAndSkipsUnknownFields() {
        val adapter = directVaultAdapter()
        val beforeDecode = System.currentTimeMillis()

        val decoded = requireNotNull(
            adapter.fromJson(
                """{
                    "originalName":"legacy.txt",
                    "encryptedName":"legacy.vvf",
                    "category":"DOCUMENTS",
                    "sizeBytes":12,
                    "unknownFutureField":"ignored"
                }"""
            )
        )

        assertEquals(0L, decoded.id)
        assertEquals("", decoded.encryptedFilePath)
        assertEquals("", decoded.ivBase64)
        assertEquals(12L, decoded.sizeBytes)
        assertEquals(true, decoded.isBiometricProtected)
        assertEquals(1, decoded.vaultFormatVersion)
        assertEquals(true, decoded.encryptedAtMs >= beforeDecode)
    }

    @Test
    fun generatedVaultAdapter_rejectsNullRequiredFields() {
        val adapter = directVaultAdapter()

        assertThrows(JsonDataException::class.java) {
            adapter.fromJson(
                """{
                    "originalName":null,
                    "encryptedName":"broken.vvf",
                    "category":"DOCUMENTS",
                    "sizeBytes":1
                }"""
            )
        }
    }

    @Test
    fun generatedVaultAdapter_rejectsNullForEveryNonNullableVaultField() {
        val adapter = directVaultAdapter()
        val validJson = FULL_VAULT_JSON
        val nullPayloads = listOf(
            validJson.replace("\"originalName\":\"source.txt\"", "\"originalName\":null"),
            validJson.replace("\"encryptedName\":\"source.vvf\"", "\"encryptedName\":null"),
            validJson.replace("\"encryptedFilePath\":\"/vault/source.vvf\"", "\"encryptedFilePath\":null"),
            validJson.replace("\"ivBase64\":\"iv\"", "\"ivBase64\":null"),
            validJson.replace("\"category\":\"DOCUMENTS\"", "\"category\":null"),
            validJson.replace("\"sizeBytes\":1", "\"sizeBytes\":null"),
            validJson.replace("\"encryptedAtMs\":2", "\"encryptedAtMs\":null"),
            validJson.replace("\"isBiometricProtected\":true", "\"isBiometricProtected\":null"),
            validJson.replace("\"vaultFormatVersion\":3", "\"vaultFormatVersion\":null"),
        )

        nullPayloads.forEach { payload ->
            assertThrows(JsonDataException::class.java) { adapter.fromJson(payload) }
        }
    }

    @Test
    fun generatedVaultAdapter_rejectsEachMissingRequiredVaultField() {
        val adapter = directVaultAdapter()
        val missingRequiredPayloads = listOf(
            """{"encryptedName":"source.vvf","category":"DOCUMENTS","sizeBytes":1}""",
            """{"originalName":"source.txt","category":"DOCUMENTS","sizeBytes":1}""",
            """{"originalName":"source.txt","encryptedName":"source.vvf","sizeBytes":1}""",
            """{"originalName":"source.txt","encryptedName":"source.vvf","category":"DOCUMENTS"}""",
        )

        missingRequiredPayloads.forEach { payload ->
            assertThrows(JsonDataException::class.java) { adapter.fromJson(payload) }
        }
    }

    @Test
    fun generatedVaultAdapter_describesItselfAndRejectsNullSerialization() {
        val adapter = directVaultAdapter()

        assertEquals("GeneratedJsonAdapter(VaultItemEntity)", adapter.toString())
        assertThrows(NullPointerException::class.java) { adapter.toJson(null) }
    }

    @Test
    fun generatedCloudAdapter_appliesDefaultsAndSkipsUnknownFields() {
        val adapter = moshi.adapter(CloudSyncItemEntity::class.java)
        val beforeDecode = System.currentTimeMillis()

        val decoded = requireNotNull(
            adapter.fromJson(
                """{
                    "provider":"GOOGLE_DRIVE",
                    "fileName":"legacy.txt",
                    "fileSize":12,
                    "status":"QUEUED",
                    "futureCheckpoint":"ignored"
                }"""
            )
        )

        assertEquals(0L, decoded.id)
        assertEquals("", decoded.filePath)
        assertEquals(false, decoded.isCore)
        assertEquals(true, decoded.lastSyncedMs >= beforeDecode)
        assertEquals(true, decoded.operationId.startsWith("op-"))
        assertEquals(null, decoded.leaseOwner)
        assertEquals(0L, decoded.resumableBytesCommitted)
    }

    @Test
    fun generatedCloudAdapter_rejectsNullForEveryNonNullableCloudField() {
        val adapter = moshi.adapter(CloudSyncItemEntity::class.java)
        val validJson = FULL_CLOUD_JSON
        val nullPayloads = listOf(
            validJson.replace("\"provider\":\"GOOGLE_DRIVE\"", "\"provider\":null"),
            validJson.replace("\"fileName\":\"source.txt\"", "\"fileName\":null"),
            validJson.replace("\"filePath\":\"/source.txt\"", "\"filePath\":null"),
            validJson.replace("\"fileSize\":1", "\"fileSize\":null"),
            validJson.replace("\"status\":\"QUEUED\"", "\"status\":null"),
            validJson.replace("\"lastSyncedMs\":2", "\"lastSyncedMs\":null"),
            validJson.replace("\"isCore\":false", "\"isCore\":null"),
            validJson.replace("\"operationId\":\"operation-1\"", "\"operationId\":null"),
            validJson.replace("\"leaseExpiresAtMs\":3", "\"leaseExpiresAtMs\":null"),
            validJson.replace("\"attemptCount\":4", "\"attemptCount\":null"),
            validJson.replace("\"startedAtMs\":5", "\"startedAtMs\":null"),
            validJson.replace("\"heartbeatAtMs\":6", "\"heartbeatAtMs\":null"),
            validJson.replace("\"completedAtMs\":7", "\"completedAtMs\":null"),
            validJson.replace("\"remoteFileId\":\"remote-1\"", "\"remoteFileId\":null"),
            validJson.replace("\"resumableSessionUri\":\"session-1\"", "\"resumableSessionUri\":null"),
            validJson.replace("\"resumableBytesCommitted\":8", "\"resumableBytesCommitted\":null"),
        )

        nullPayloads.forEach { payload ->
            assertThrows(JsonDataException::class.java) { adapter.fromJson(payload) }
        }
    }

    @Test
    fun generatedCloudAdapter_rejectsEachMissingRequiredCloudField() {
        val adapter = moshi.adapter(CloudSyncItemEntity::class.java)
        val missingRequiredPayloads = listOf(
            """{"fileName":"source.txt","fileSize":1,"status":"QUEUED"}""",
            """{"provider":"GOOGLE_DRIVE","fileSize":1,"status":"QUEUED"}""",
            """{"provider":"GOOGLE_DRIVE","fileName":"source.txt","status":"QUEUED"}""",
            """{"provider":"GOOGLE_DRIVE","fileName":"source.txt","fileSize":1}""",
        )

        missingRequiredPayloads.forEach { payload ->
            assertThrows(JsonDataException::class.java) { adapter.fromJson(payload) }
        }
    }

    @Test
    fun generatedCloudAdapter_describesItselfAndRejectsNullSerialization() {
        val adapter = directCloudAdapter()

        assertEquals("GeneratedJsonAdapter(CloudSyncItemEntity)", adapter.toString())
        assertThrows(NullPointerException::class.java) { adapter.toJson(null) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun directVaultAdapter(): JsonAdapter<VaultItemEntity> {
        val adapterClass = Class.forName("com.example.data.VaultItemEntityJsonAdapter")
        val adapter = runCatching {
            adapterClass.getDeclaredConstructor(Moshi::class.java).newInstance(moshi)
        }.getOrElse {
            adapterClass.getDeclaredConstructor().newInstance()
        }
        return adapter as JsonAdapter<VaultItemEntity>
    }

    @Suppress("UNCHECKED_CAST")
    private fun directCloudAdapter(): JsonAdapter<CloudSyncItemEntity> {
        val adapterClass = Class.forName("com.example.data.CloudSyncItemEntityJsonAdapter")
        val adapter = runCatching {
            adapterClass.getDeclaredConstructor(Moshi::class.java).newInstance(moshi)
        }.getOrElse {
            adapterClass.getDeclaredConstructor().newInstance()
        }
        return adapter as JsonAdapter<CloudSyncItemEntity>
    }

    private companion object {
        const val FULL_VAULT_JSON = """{
            "id":1,
            "originalName":"source.txt",
            "encryptedName":"source.vvf",
            "encryptedFilePath":"/vault/source.vvf",
            "ivBase64":"iv",
            "category":"DOCUMENTS",
            "sizeBytes":1,
            "encryptedAtMs":2,
            "isBiometricProtected":true,
            "vaultFormatVersion":3
        }"""

        const val FULL_CLOUD_JSON = """{
            "id":1,
            "provider":"GOOGLE_DRIVE",
            "fileName":"source.txt",
            "filePath":"/source.txt",
            "fileSize":1,
            "status":"QUEUED",
            "lastSyncedMs":2,
            "isCore":false,
            "operationId":"operation-1",
            "leaseOwner":"worker-1",
            "leaseExpiresAtMs":3,
            "attemptCount":4,
            "startedAtMs":5,
            "heartbeatAtMs":6,
            "completedAtMs":7,
            "lastErrorCode":"none",
            "remoteFileId":"remote-1",
            "resumableSessionUri":"session-1",
            "resumableBytesCommitted":8
        }"""
    }
}
