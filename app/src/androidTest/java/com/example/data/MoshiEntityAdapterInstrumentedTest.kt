package com.example.data

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class MoshiEntityAdapterInstrumentedTest {
    private val moshi = Moshi.Builder().build()

    @Test
    fun fileItemAdapter_roundTripsAllFieldsAndSkipsUnknownFields(): Unit {
        val expected = FileItemEntity(
            id = 42L,
            name = "document.pdf",
            path = "/storage/emulated/0/Documents/document.pdf",
            originalPath = "/storage/emulated/0/Documents/document.pdf",
            category = "DOCUMENTS",
            sizeBytes = 4096L,
            dateModifiedMs = 123456789L,
            md5Hash = "md5-value",
            ocrText = "recognized text",
            tags = "important,work",
            isVault = true,
            isRecycleBin = false,
            deletedTimestampMs = 0L,
            visualSimilarityHash = "visual-hash",
            semanticEmbeddingVersion = 3,
            semanticIndexed = true,
            semanticEmbeddingString = "0.1,0.2,0.3",
        )
        val adapter = moshi.adapter(FileItemEntity::class.java)

        val json = adapter.toJson(expected)
        val actual = adapter.fromJson(json.replaceFirst("{", "{\"unknown\":true,"))

        assertNotNull(actual)
        assertEquals(expected, actual)
    }

    @Test
    fun nestedAndOtherEntityAdapters_roundTripAllModels(): Unit {
        val file = FileItemEntity(
            id = 7L,
            name = "photo.jpg",
            path = "/photos/photo.jpg",
            category = "IMAGES",
            sizeBytes = 2048L,
            dateModifiedMs = 11L,
            md5Hash = "hash",
            ocrText = "text",
            tags = "family",
            isVault = false,
            isRecycleBin = true,
            deletedTimestampMs = 12L,
            visualSimilarityHash = "sim",
            semanticEmbeddingVersion = 1,
            semanticIndexed = true,
            semanticEmbeddingString = "1.0,2.0",
        )
        val duplicate = DuplicateGroup(
            title = "same images",
            level = 2,
            similarityScore = 96,
            files = listOf(file),
        )
        val vault = VaultItemEntity(
            id = 8L,
            originalName = "secret.txt",
            encryptedName = "ENC_secret.txt.vvf",
            encryptedFilePath = "/data/user/0/app/.vault/ENC_secret.txt.vvf",
            ivBase64 = "AQIDBA==",
            category = "DOCUMENTS",
            sizeBytes = 128L,
            encryptedAtMs = 13L,
            isBiometricProtected = false,
        )
        val cloud = CloudSyncItemEntity(
            id = 9L,
            provider = "GOOGLE_DRIVE",
            fileName = "secret.txt",
            filePath = "/documents/secret.txt",
            fileSize = 128L,
            status = "SYNCED",
            lastSyncedMs = 14L,
            isCore = true,
        )
        val plugin = PluginEntity(
            pluginId = "ocr",
            name = "OCR",
            category = "OCR",
            description = "Text extraction",
            isEnabled = true,
            isCore = true,
        )

        assertEquals(duplicate, roundTrip(duplicate, DuplicateGroup::class.java))
        assertEquals(vault, roundTrip(vault, VaultItemEntity::class.java))
        assertEquals(cloud, roundTrip(cloud, CloudSyncItemEntity::class.java))
        assertEquals(plugin, roundTrip(plugin, PluginEntity::class.java))
    }

    @Test
    fun adapters_rejectNullAndMissingRequiredFields(): Unit {
        val fileAdapter = moshi.adapter(FileItemEntity::class.java)
        val pluginAdapter = moshi.adapter(PluginEntity::class.java)

        assertJsonDataException { fileAdapter.fromJson("{\"id\":1,\"name\":null}") }
        assertJsonDataException { pluginAdapter.fromJson("{\"pluginId\":\"ocr\",\"name\":\"OCR\"}") }

        val minimalFileJson = """
            {
              "id": 1,
              "name": "minimal.txt",
              "path": "/minimal.txt",
              "originalPath": "",
              "category": "DOCUMENTS",
              "sizeBytes": 1,
              "dateModifiedMs": 1,
              "md5Hash": "",
              "ocrText": "",
              "tags": "",
              "isVault": false,
              "isRecycleBin": false,
              "deletedTimestampMs": 0,
              "visualSimilarityHash": "",
              "semanticEmbeddingVersion": 0,
              "semanticIndexed": false,
              "semanticEmbeddingString": ""
            }
        """.trimIndent()
        assertEquals("minimal.txt", fileAdapter.fromJson(minimalFileJson)?.name)
    }

    private fun <T> roundTrip(value: T, type: Class<T>): T? {
        val adapter = moshi.adapter(type)
        return adapter.fromJson(adapter.toJson(value))
    }

    private fun assertJsonDataException(block: () -> Unit): Unit {
        try {
            block()
        } catch (_: JsonDataException) {
            assertTrue(true)
            return
        }
        throw AssertionError("Expected Moshi JsonDataException")
    }
}
