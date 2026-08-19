package com.example.context.telemetry

import android.content.Context

/** Telemetry boundary; it must not expose identity or Drive authorization state. */
interface TelemetryPort {
    fun initialize(context: Context)
}
