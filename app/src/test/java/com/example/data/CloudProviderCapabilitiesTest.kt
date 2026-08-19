package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProviderCapabilitiesTest {

    @Test
    fun registry_exposesOnlyGoogleDriveAsImplementedAcrossProviderAndPluginLookups() {
        val google = CloudProviderCapabilities.forProvider("google_drive")
        val oneDrive = CloudProviderCapabilities.forPlugin("onedrive_sync")

        assertEquals("Google Drive", google?.displayName)
        assertTrue(google?.isImplemented == true)
        assertEquals("ONEDRIVE", oneDrive?.providerId)
        assertFalse(oneDrive?.isImplemented == true)
        assertTrue(CloudProviderCapabilities.isImplementedProvider("GOOGLE_DRIVE"))
        assertFalse(CloudProviderCapabilities.isImplementedProvider("dropbox"))
        assertTrue(CloudProviderCapabilities.isImplementedPlugin("gdrive_sync"))
        assertFalse(CloudProviderCapabilities.isImplementedPlugin("onedrive_sync"))
    }

    @Test
    fun registry_rejectsUnknownProviderAndPluginInsteadOfAdvertisingAvailability() {
        assertNull(CloudProviderCapabilities.forProvider("UNLISTED"))
        assertNull(CloudProviderCapabilities.forPlugin("unlisted_sync"))
        assertFalse(CloudProviderCapabilities.isImplementedProvider("UNLISTED"))
        assertFalse(CloudProviderCapabilities.isImplementedPlugin("unlisted_sync"))
    }
}
