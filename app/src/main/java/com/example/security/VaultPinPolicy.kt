package com.example.security

/** Single source of truth for vault PIN validation across all layers. */
object VaultPinPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    fun isValid(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all(Char::isDigit)
}
