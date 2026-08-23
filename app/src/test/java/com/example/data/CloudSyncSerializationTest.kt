package com.example.data

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSyncSerializationTest {
    private val adapter = Moshi.Builder()
        .build()
        .adapter(CloudSyncItemEntity::class.java)

    @Test
    fun cloudSyncItem_readsLegacyPayloadWithDefaults() {
        val decoded = adapter.fromJson(
            """
            {
              "id": 18,
              "provider": "GOOGLE_DRIVE",
              "fileName": "legacy.pdf",
              "fileSize": 128,
              "status": "PENDING"
            }
            """.trimIndent(),
        )

        assertEquals(18L, decoded?.id)
        assertEquals("legacy.pdf", decoded?.fileName)
        assertEquals("", decoded?.filePath)
        assertEquals(false, decoded?.isCore)
        assertEquals(null, decoded?.leaseOwner)
        assertEquals(0L, decoded?.resumableBytesCommitted)
    }

    @Test
    fun cloudSyncItem_roundTripsDurableResumableStateAndNullableFields() {
        val expected = CloudSyncItemEntity(
            id = 17L,
            provider = "GOOGLE_DRIVE",
            fileName = "report.pdf",
            filePath = "/data/user/0/com.example/files/report.pdf",
            fileSize = 4096L,
            status = "UPLOADING",
            lastSyncedMs = 123456789L,
            isCore = true,
            operationId = "operation-17",
            leaseOwner = "worker-17",
            leaseExpiresAtMs = 987654321L,
            attemptCount = 2,
            startedAtMs = 111L,
            heartbeatAtMs = 222L,
            completedAtMs = 0L,
            lastErrorCode = null,
            remoteFileId = "remote-17",
            resumableSessionUri = "https://upload.example/session-17",
            resumableBytesCommitted = 2048L,
        )

        val encoded = adapter.toJson(expected)
        val decoded = adapter.fromJson(encoded)

        assertEquals(expected, decoded)
        assertEquals("UPLOADING", decoded?.status)
        assertEquals("remote-17", decoded?.remoteFileId)
        assertEquals("https://upload.example/session-17", decoded?.resumableSessionUri)
        assertEquals(2048L, decoded?.resumableBytesCommitted)
        assertEquals(null, decoded?.lastErrorCode)
    }
}
