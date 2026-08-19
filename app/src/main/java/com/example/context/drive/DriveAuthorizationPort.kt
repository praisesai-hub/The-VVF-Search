package com.example.context.drive

/**
 * Narrow authorization port exposed to Drive providers and CloudTransfer.
 * Identity state and raw credential storage remain outside this boundary.
 */
interface DriveAuthorizationPort {
    /** Returns a short-lived authorization header, never a persisted token object. */
    fun authorizationHeader(): String?

    fun isAuthorized(): Boolean
}
