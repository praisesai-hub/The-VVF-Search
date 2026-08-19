package com.example.context

import com.example.context.cloud.CloudProviderRegistry
import com.example.context.drive.DriveAuthorizationPort
import com.example.data.CloudProviderAdapter
import com.example.data.CloudSyncResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedContextContractTest {
    @Test
    fun `cloud provider registry selects only supported provider or injected adapter`() {
        val authorization = object : DriveAuthorizationPort {
            override fun authorizationHeader(): String? = "Bearer test"
            override fun isAuthorized(): Boolean = true
        }
        val adapter = fakeAdapter("TEST")
        val registry = CloudProviderRegistry(authorization, adapter)

        assertEquals(adapter, registry.adapterFor("GOOGLE_DRIVE"))
        assertNull(CloudProviderRegistry(authorization).adapterFor("UNSUPPORTED"))
    }

    @Test
    fun `drive authorization is a narrow transfer contract`() {
        val authorization = object : DriveAuthorizationPort {
            override fun authorizationHeader(): String? = "Bearer scoped-token"
            override fun isAuthorized(): Boolean = true
        }

        assertEquals("Bearer scoped-token", authorization.authorizationHeader())
        assertEquals(true, authorization.isAuthorized())
        assertNotNull(authorization)
    }

    private fun fakeAdapter(id: String) = object : CloudProviderAdapter {
        override val providerId: String = id
        override suspend fun uploadFile(file: File, remotePath: String): CloudSyncResult =
            CloudSyncResult.Success()
        override suspend fun downloadFile(remotePath: String, destinationFile: File): CloudSyncResult =
            CloudSyncResult.Success()
    }
}
