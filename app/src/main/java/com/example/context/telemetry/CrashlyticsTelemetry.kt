package com.example.context.telemetry

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.security.CrashReportingPolicy

/** Firebase Crashlytics adapter; explicit consent is required in release builds. */
object CrashlyticsTelemetry : TelemetryPort {
    override fun initialize(context: Context) {
        try {
            com.google.firebase.FirebaseApp.initializeApp(context)
            val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            val consented = !BuildConfig.DEBUG && CrashReportingPolicy.hasConsent(context)
            crashlytics.setCrashlyticsCollectionEnabled(consented)
            if (consented) crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
            Log.i("CrashlyticsTelemetry", "Initialized with explicit user consent=$consented.")
        } catch (error: IllegalArgumentException) {
            logConfigurationPending(error)
        } catch (error: IllegalStateException) {
            logConfigurationPending(error)
        }
    }

    private fun logConfigurationPending(error: RuntimeException) {
        Log.w(
            "CrashlyticsTelemetry",
            "Initialization pending; Firebase configuration may be unavailable: ${error.message}"
        )
    }
}
