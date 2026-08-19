package com.example.data

/**
 * The single source of truth for cloud providers that are executable in this release.
 * UI, enqueueing, plugin toggles and workers must use this registry rather than inferring
 * support from a plugin row alone.
 */
data class CloudProviderCapability(
    val providerId: String,
    val pluginId: String,
    val displayName: String,
    val isImplemented: Boolean
)

object CloudProviderCapabilities {
    private val capabilities = listOf(
        CloudProviderCapability("GOOGLE_DRIVE", "gdrive_sync", "Google Drive", true),
        CloudProviderCapability("ONEDRIVE", "onedrive_sync", "OneDrive", false),
        CloudProviderCapability("DROPBOX", "dropbox_sync", "Dropbox", false),
        CloudProviderCapability("NEXTCLOUD", "nextcloud_sync", "Nextcloud", false),
        CloudProviderCapability("S3", "s3_sync", "S3", false),
        CloudProviderCapability("NAS", "nas_sync", "NAS", false)
    )

    fun forProvider(providerId: String): CloudProviderCapability? =
        capabilities.find { it.providerId.equals(providerId, ignoreCase = true) }

    fun forPlugin(pluginId: String): CloudProviderCapability? =
        capabilities.find { it.pluginId == pluginId }

    fun isImplementedProvider(providerId: String): Boolean =
        forProvider(providerId)?.isImplemented == true

    fun isImplementedPlugin(pluginId: String): Boolean =
        forPlugin(pluginId)?.isImplemented == true
}
