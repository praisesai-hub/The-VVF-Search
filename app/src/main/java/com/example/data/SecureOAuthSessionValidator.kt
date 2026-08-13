package com.example.data

/**
 * Rejects locally generated/demo OAuth credentials.
 * Real Google OAuth tokens are issued by Google's authorization flow and are
 * persisted only after that flow has completed successfully.
 */
object SecureOAuthSessionValidator {
    private val demoAccessToken = Regex("^ya29\\.a0AcEw0eB-")
    private val demoRefreshToken = Regex("^1//0")

    fun isValid(accessToken: String, refreshToken: String?): Boolean {
        if (accessToken.isBlank()) return false
        if (demoAccessToken.containsMatchIn(accessToken)) return false
        if (refreshToken != null && demoRefreshToken.containsMatchIn(refreshToken)) return false
        return true
    }
}
