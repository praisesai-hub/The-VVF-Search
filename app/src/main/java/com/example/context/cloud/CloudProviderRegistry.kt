package com.example.context.cloud

import com.example.context.drive.DriveAuthorizationPort
import com.example.data.CloudProviderAdapter
import com.example.data.GoogleDriveProviderAdapter

/** Provider selection belongs to CloudTransfer, not to identity or the transfer engine. */
class CloudProviderRegistry(
    private val driveAuthorization: DriveAuthorizationPort,
    private val overrideAdapter: CloudProviderAdapter? = null
) {
    fun adapterFor(provider: String): CloudProviderAdapter? {
        overrideAdapter?.let { return it }
        return when (provider.uppercase()) {
            "GOOGLE_DRIVE" -> GoogleDriveProviderAdapter(driveAuthorization)
            else -> null
        }
    }
}
