# Vault authentication and lockout contract

The vault requires an authentication secret between **8 and 128 characters**, with at least one digit and no whitespace. The current Compose keypad supplies eight-or-more digit credentials; the domain contract also permits longer passphrases for future text-entry surfaces. Four-digit credentials are rejected during initialization and rotation.

The vault stores the PBKDF2 verifier, the wrapped vault DEK, and lockout state in the encrypted secure preference store. Failed PIN attempts are persisted as an atomic state transition. After five failed attempts, authentication is locked for 30 seconds. Each subsequent group of five failures increases the delay exponentially, capped at 24 hours. A successful PIN or biometric unlock atomically clears the failed-attempt count and lock deadline.

Lockout state is based on wall-clock timestamps persisted in secure storage, so it survives process death and engine recreation. The UI does not perform a second pre-verification call; a PIN submission invokes the repository unlock operation once, preventing duplicate attempt accounting. A lockout response clears the entered secret from UI state and does not expose the stored verifier, wrapped key, or any access credential.

The JVM regression suite verifies that failed attempts survive recreation, that the lockout deadline is durable, and that backoff escalates after the initial lockout threshold. Android instrumented coverage continues to exercise Keystore-backed envelope creation, unlock, biometric wrapping, and session invalidation.
