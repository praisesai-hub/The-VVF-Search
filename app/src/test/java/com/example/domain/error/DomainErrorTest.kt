package com.example.domain.error

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainErrorTest {
    @Test
    fun `mapped user message never exposes internal path or cause text`() {
        val path = "/storage/emulated/0/personal-document.pdf"
        val error = DomainErrorMapper.fromThrowable(
            operation = "VAULT_MOVE",
            cause = IOException("ENOSPC while writing $path"),
            fileId = 123L
        )

        assertEquals("There is not enough storage to complete this operation.", error.userMessage.value)
        assertFalse(error.userMessage.value.contains(path))
        assertEquals("operation=VAULT_MOVE file_id=123 reason=NO_SPACE", error.diagnostics.asLogFields())
    }

    @Test
    fun `diagnostic fields omit sensitive path and token keys`() {
        val context = DiagnosticContext(
            operation = "DRIVE_TRANSFER",
            provider = "GOOGLE_DRIVE",
            reasonCode = "HTTP_500",
            attributes = mapOf(
                "path" to "/private/file.pdf",
                "access_token" to "secret",
                "http_status" to "500"
            )
        )

        val fields = context.asLogFields()
        assertTrue(fields.contains("operation=DRIVE_TRANSFER"))
        assertTrue(fields.contains("provider=GOOGLE_DRIVE"))
        assertTrue(fields.contains("http_status=500"))
        assertFalse(fields.contains("/private/file.pdf"))
        assertFalse(fields.contains("secret"))
    }
}
