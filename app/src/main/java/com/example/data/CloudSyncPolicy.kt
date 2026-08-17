package com.example.data

import android.content.Context
import com.example.BuildConfig

/**
 * Privacy boundary for all cloud transfers. Cloud sync is disabled by default in every build and
 * cannot run unless the release is explicitly provisioned and the device owner has opted in.
 */
object CloudSyncPolicy {
    private const val PREFERENCES_NAME = "vvf_cloud_sync_privacy"
    private const val EXPLICIT_OPT_IN_KEY = "explicit_cloud_sync_opt_in"

    fun isBuildProvisioned(): Boolean = BuildConfig.CLOUD_SYNC_ENABLED

    fun hasExplicitOptIn(context: Context): Boolean = context
        .applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getBoolean(EXPLICIT_OPT_IN_KEY, false)

    fun canTransfer(context: Context): Boolean =
        isBuildProvisioned() && hasExplicitOptIn(context)

    fun setExplicitOptIn(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(EXPLICIT_OPT_IN_KEY, enabled)
            .apply()
    }
}
