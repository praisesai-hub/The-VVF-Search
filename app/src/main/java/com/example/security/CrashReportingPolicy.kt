package com.example.security

import android.content.Context

/**
 * Remote crash reporting is strictly opt-in. The application never enables telemetry merely
 * because it is a release build or because Firebase configuration happens to be present.
 */
object CrashReportingPolicy {
    private const val PREFERENCES_NAME = "vvf_privacy_choices"
    private const val CRASH_REPORTING_CONSENT_KEY = "crash_reporting_consent"

    fun hasConsent(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getBoolean(CRASH_REPORTING_CONSENT_KEY, false)

    fun setConsent(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CRASH_REPORTING_CONSENT_KEY, enabled)
            .apply()
    }
}
