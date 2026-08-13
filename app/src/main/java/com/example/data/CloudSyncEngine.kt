package com.example.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Core engine responsible for orchestrating cloud sync tasks.
 * Delegates the heavy lifting of individual provider protocols to respective [CloudProviderAdapter]s.
 */
class CloudSyncEngine(
    private val context: Context,
    private val dao: FileDao,
    private val authManager: GoogleAuthManager,
    private val providerAdapterOverride: CloudProviderAdapter? = null
) {
    companion object {
        private const val TAG = "CloudSyncEngine"
    }

    /**
     * Synchronizes a single [CloudSyncItemEntity].
     * Resolves the appropriate provider, performs the upload/sync, and returns the result.
     */
    suspend fun syncItem(item: CloudSyncItemEntity): CloudSyncResult {
        val file = File(item.filePath)
        if (!file.exists() || !file.isFile) {
            return CloudSyncResult.Error(
                message = "File does not exist or is invalid: ${file.absolutePath}",
                isRetryable = false
            )
        }

        val adapter = getAdapterForProvider(item.provider)
            ?: return CloudSyncResult.Error(
                message = "No supported provider adapter found for ${item.provider}",
                isRetryable = false
            )

        Log.i(TAG, "Starting sync for item ${item.id} (${item.fileName}) with provider ${item.provider}...")
        return try {
            adapter.uploadFile(file, item.fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during upload for item ${item.id}", e)
            CloudSyncResult.Error(
                message = e.message ?: "Upload failed",
                isRetryable = isExceptionRetryable(e),
                cause = e
            )
        }
    }

    /**
     * Resolves the correct [CloudProviderAdapter] based on the provider string.
     */
    private fun getAdapterForProvider(provider: String): CloudProviderAdapter? {
        if (providerAdapterOverride != null) {
            return providerAdapterOverride
        }
        return when (provider.uppercase()) {
            "GOOGLE_DRIVE" -> GoogleDriveProviderAdapter(authManager)
            else -> null
        }
    }

    private fun isExceptionRetryable(e: Exception): Boolean {
        return e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e is java.io.IOException ||
                e.message?.contains("Unable to resolve host") == true
    }
}
