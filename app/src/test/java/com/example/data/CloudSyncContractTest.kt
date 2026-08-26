package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncContractTest {
    @Test
    fun notSupported_isDistinctFromTransferError() {
        val result: CloudSyncResult = CloudSyncResult.NotSupported

        assertTrue(result is CloudSyncResult.NotSupported)
        assertTrue(result !is CloudSyncResult.Error)
    }

    @Test
    fun success_preservesDurableTransferState() {
        val result = CloudSyncResult.Success(
            bytesTransferred = 4096L,
            remoteFileId = "remote-1",
            resumableSessionUri = "session-1",
            bytesCommitted = 4096L
        )

        assertEquals(4096L, result.bytesTransferred)
        assertEquals("remote-1", result.remoteFileId)
        assertEquals("session-1", result.resumableSessionUri)
        assertEquals(4096L, result.bytesCommitted)
    }
}
