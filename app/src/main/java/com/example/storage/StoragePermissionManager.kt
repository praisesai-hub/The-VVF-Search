package com.example.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Centralizes the restricted full-device storage permission lifecycle. */
object StoragePermissionManager {
    fun hasFullDeviceAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun settingsIntent(context: Context, advancedModeEnabled: Boolean = false): Intent? {
        if (!advancedModeEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasFullDeviceAccess()) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    /** SAF is the default; full-device access is available only from explicit advanced mode. */
    fun shouldUseSafFallback(advancedModeEnabled: Boolean = false): Boolean =
        !advancedModeEnabled || !hasFullDeviceAccess()
}
