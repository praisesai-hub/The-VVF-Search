package com.example.context.drive

import android.content.Context
import com.example.data.GoogleAuthManagerFactory

/** Composition-root adapter for the DriveAuthorization bounded context. */
object DriveAuthorizationFactory {
    fun getInstance(context: Context): DriveAuthorizationPort =
        GoogleAuthManagerFactory.getInstance(context)
}
