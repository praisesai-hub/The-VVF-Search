package com.example.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureOAuthSessionValidatorTest {

    @Test
    fun isValid_acceptsNonBlankNonDemoCredentials() {
        assertTrue(
            SecureOAuthSessionValidator.isValid(
                accessToken = "real-issued-access-token",
                refreshToken = "real-issued-refresh-token"
            )
        )
    }

    @Test
    fun isValid_rejectsBlankAccessTokens() {
        assertFalse(SecureOAuthSessionValidator.isValid("", null))
        assertFalse(SecureOAuthSessionValidator.isValid("   ", "refresh-token"))
    }

    @Test
    fun isValid_rejectsKnownDemoAccessTokenPrefix() {
        assertFalse(
            SecureOAuthSessionValidator.isValid(
                accessToken = "ya29.a0AcEw0eB-demo-token",
                refreshToken = null
            )
        )
    }

    @Test
    fun isValid_rejectsKnownDemoRefreshTokenPrefix() {
        assertFalse(
            SecureOAuthSessionValidator.isValid(
                accessToken = "real-issued-access-token",
                refreshToken = "1//0-demo-refresh-token"
            )
        )
    }
}
