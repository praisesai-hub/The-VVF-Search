package com.example.domain.error

import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

class UserSafeException(
    val domainError: DomainError
) : IOException(domainError.userMessage.value, domainError.internalCause)

@JvmInline
value class UserMessage(val value: String)

data class DiagnosticContext(
    val operation: String,
    val fileId: Long? = null,
    val provider: String? = null,
    val reasonCode: String,
    val attributes: Map<String, String> = emptyMap()
) {
    /** Diagnostic attributes are deliberately key/value metadata, never paths or tokens. */
    fun asLogFields(): String = buildList {
        add("operation=$operation")
        fileId?.let { add("file_id=$it") }
        provider?.let { add("provider=$it") }
        add("reason=$reasonCode")
        attributes.toSortedMap().forEach { (key, value) ->
            if (key !in SENSITIVE_KEYS) add("$key=$value")
        }
    }.joinToString(" ")

    private companion object {
        val SENSITIVE_KEYS = setOf(
            "path", "file_path", "absolute_path", "remote_path", "uri",
            "token", "access_token", "refresh_token", "authorization", "secret"
        )
    }
}

sealed class DomainError {
    abstract val userMessage: UserMessage
    abstract val diagnostics: DiagnosticContext
    abstract val internalCause: Throwable?

    class OperationFailed(
        override val userMessage: UserMessage,
        override val diagnostics: DiagnosticContext,
        override val internalCause: Throwable? = null
    ) : DomainError()
}

object DomainErrorMapper {
    fun fromThrowable(
        operation: String,
        cause: Throwable,
        fileId: Long? = null,
        provider: String? = null
    ): DomainError {
        val reasonCode = when {
            cause is SecurityException -> "AUTH_REQUIRED"
            cause is UnknownHostException ||
                cause is ConnectException ||
                cause.message?.contains("unable to resolve host", ignoreCase = true) == true -> "NETWORK_UNAVAILABLE"
            cause.message?.contains("ENOSPC", ignoreCase = true) == true -> "NO_SPACE"
            cause is IOException -> "IO_FAILURE"
            else -> "UNEXPECTED_FAILURE"
        }
        val userMessage = when (reasonCode) {
            "AUTH_REQUIRED" -> "Authentication is required to complete this operation."
            "NETWORK_UNAVAILABLE" -> "Network connection is unavailable."
            "NO_SPACE" -> "There is not enough storage to complete this operation."
            "IO_FAILURE" -> "The file operation could not be completed."
            else -> "The operation could not be completed."
        }
        return DomainError.OperationFailed(
            userMessage = UserMessage(userMessage),
            diagnostics = DiagnosticContext(
                operation = operation,
                fileId = fileId,
                provider = provider,
                reasonCode = reasonCode
            ),
            internalCause = cause
        )
    }
}

object DiagnosticLogger {
    fun log(tag: String, error: DomainError, level: Level = Level.ERROR) {
        val fields = error.diagnostics.asLogFields()
        when (level) {
            Level.WARN -> Log.w(tag, fields, error.internalCause)
            Level.ERROR -> Log.e(tag, fields, error.internalCause)
        }
    }

    enum class Level { WARN, ERROR }
}
