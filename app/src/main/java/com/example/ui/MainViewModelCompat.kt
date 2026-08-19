package com.example.ui

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.WeakHashMap

private class VmCompatState {
    val vaultUnlocked = MutableStateFlow(false)
    val vaultAutoLockTimeoutMs = MutableStateFlow(DEFAULT_VAULT_AUTO_LOCK_TIMEOUT_MS)
    val vaultActivityGeneration = MutableStateFlow(0L)
    var vaultAutoLockSettingsLoaded = false
    val enteredPin = MutableStateFlow("")
    val pinError = MutableStateFlow<String?>(null)
    val setupPinFirstEntry = MutableStateFlow<String?>(null)
    val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val pageLoading = MutableStateFlow(false)
    var failedPinAttempts: Int = 0
    var pinLockedUntilElapsedMs: Long = 0L
}

internal const val DEFAULT_VAULT_AUTO_LOCK_TIMEOUT_MS = 60_000L
private const val MIN_VAULT_AUTO_LOCK_TIMEOUT_MS = 15_000L
private const val MAX_VAULT_AUTO_LOCK_TIMEOUT_MS = 15 * 60_000L
private const val VAULT_AUTO_LOCK_TIMEOUT_PREF = "vault_auto_lock_timeout_ms"
private const val APP_SETTINGS_PREF = "vvf_app_settings"
private val compatStates = WeakHashMap<MainViewModel, VmCompatState>()
private fun MainViewModel.compatMessage(resId: Int): String =
    getApplication<Application>().getString(resId)

private fun MainViewModel.compatState(): VmCompatState = synchronized(compatStates) {
    compatStates.getOrPut(this) { VmCompatState() }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
private fun <T> Flow<T>.stateInCompat(vm: MainViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.filteredFiles: StateFlow<List<FileItemEntity>> get() = files
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.categoryStats: StateFlow<List<CategoryStat>> get() = dashboardStats
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.recentFiles: StateFlow<List<FileItemEntity>> get() = repository.recentFiles.stateInCompat(this, emptyList())
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.recycleBinFiles: StateFlow<List<FileItemEntity>> get() = repository.recycleBinFiles.stateInCompat(this, emptyList())
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.ocrScannedFiles: StateFlow<List<FileItemEntity>> get() = repository.ocrScannedFiles.stateInCompat(this, emptyList())
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.level1ExactDuplicates: StateFlow<List<DuplicateGroup>> get() = repository.exactDuplicates.stateInCompat(this, emptyList())
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.level3VisualDuplicates: StateFlow<List<DuplicateGroup>> get() = repository.getVisualDuplicates(similarityThreshold).stateInCompat(this, emptyList())
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.videoDuplicates: StateFlow<List<DuplicateGroup>> get() = repository.getVideoDuplicates(similarityThreshold).stateInCompat(this, emptyList())
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.semanticDuplicates: StateFlow<List<DuplicateGroup>> get() = repository.getSemanticDuplicates(similarityThreshold).stateInCompat(this, emptyList())
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.documentDuplicates: StateFlow<List<DuplicateGroup>> get() = repository.getDocumentDuplicates().stateInCompat(this, emptyList())
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.documentStats: StateFlow<Triple<Int, Int, Float>> get() = repository.documentStats.stateInCompat(this, Triple(0, 0, 1f))
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.selectedDuplicateIds: StateFlow<Set<Long>> get() = compatState().selectedIds
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.isDuplicateScanning: StateFlow<Boolean> get() = repository.isScanning
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.duplicateScanProgress: StateFlow<Float> get() = repository.scanProgress
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.isPageLoading: StateFlow<Boolean> get() = compatState().pageLoading
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.isVaultUnlocked: StateFlow<Boolean> get() = compatState().vaultUnlocked
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.vaultAutoLockTimeoutMs: StateFlow<Long>
    get() = compatState().also { loadVaultAutoLockSettings(it) }.vaultAutoLockTimeoutMs
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.vaultActivityGeneration: StateFlow<Long>
    get() = compatState().vaultActivityGeneration
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.enteredPin: StateFlow<String> get() = compatState().enteredPin
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.pinError: StateFlow<String?> get() = compatState().pinError
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.isVaultPinSetupRequired: Boolean get() = !repository.hasVaultPin()
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.hasBiometricEnrollment: Boolean get() = repository.hasBiometricEnrollment()

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
val MainViewModel.semanticSearchResults: StateFlow<List<FileItemEntity>> get() =
    semanticQuery.debounce(200).flatMapLatest { repository.searchSemanticFiles(it) }
        .stateInCompat(this, emptyList())

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.startDuplicateScan() { repository.startIncrementalDuplicateScan() }

/**
 * Changes the vault idle timeout. Values are deliberately bounded so the vault cannot be
 * accidentally configured to remain unlocked indefinitely.
 */
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.setVaultAutoLockTimeout(timeoutMs: Long) {
    val state = compatState()
    loadVaultAutoLockSettings(state)
    val normalized = timeoutMs.coerceIn(MIN_VAULT_AUTO_LOCK_TIMEOUT_MS, MAX_VAULT_AUTO_LOCK_TIMEOUT_MS)
    if (state.vaultAutoLockTimeoutMs.value == normalized) return
    state.vaultAutoLockTimeoutMs.value = normalized
    getApplication<Application>()
        .getSharedPreferences(APP_SETTINGS_PREF, Context.MODE_PRIVATE)
        .edit()
        .putLong(VAULT_AUTO_LOCK_TIMEOUT_PREF, normalized)
        .apply()
    recordVaultActivity()
}

/** Records activity only while an authenticated vault session is active. */
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.recordVaultActivity() {
    val state = compatState()
    if (state.vaultUnlocked.value) state.vaultActivityGeneration.value += 1
}

/** Immediately clears an authenticated vault session when the app is no longer foregrounded. */
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.lockVaultForBackground() {
    if (compatState().vaultUnlocked.value) lockVault()
}

private fun MainViewModel.onVaultSessionAuthenticated() {
    compatState().vaultActivityGeneration.value += 1
}

private fun MainViewModel.loadVaultAutoLockSettings(state: VmCompatState) {
    if (state.vaultAutoLockSettingsLoaded) return
    val persisted = getApplication<Application>()
        .getSharedPreferences(APP_SETTINGS_PREF, Context.MODE_PRIVATE)
        .getLong(VAULT_AUTO_LOCK_TIMEOUT_PREF, DEFAULT_VAULT_AUTO_LOCK_TIMEOUT_MS)
    state.vaultAutoLockTimeoutMs.value = persisted.coerceIn(
        MIN_VAULT_AUTO_LOCK_TIMEOUT_MS,
        MAX_VAULT_AUTO_LOCK_TIMEOUT_MS
    )
    state.vaultAutoLockSettingsLoaded = true
}

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.toggleDuplicateSelection(id: Long) {
    val state = compatState()
    state.selectedIds.value = state.selectedIds.value.toMutableSet().apply {
        if (!add(id)) remove(id)
    }
}

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.autoSelectExtraDuplicates() {
    // Destructive auto-selection is cryptographically exact-only. Perceptual,
    // semantic, and structural matches are candidates and require review.
    compatState().selectedIds.value = level1ExactDuplicates.value
        .flatMap { it.files.drop(1).map { file -> file.id } }
        .toSet()
}

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.cleanSelectedDuplicates() {
    val ids = selectedDuplicateIds.value
    if (ids.isEmpty()) return
    viewModelScope.launch {
        repository.cleanSelectedDuplicates(ids)
        compatState().selectedIds.value = emptySet()
    }
}

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.moveToRecycleBin(file: FileItemEntity) { viewModelScope.launch { repository.moveToRecycleBin(file) } }
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.restoreFromRecycleBin(file: FileItemEntity) { viewModelScope.launch { repository.restoreFromRecycleBin(file) } }
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.deletePermanently(file: FileItemEntity) { viewModelScope.launch { repository.deletePermanently(file) } }
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.emptyRecycleBin() { viewModelScope.launch { repository.emptyRecycleBin() } }
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.encryptToVault(file: FileItemEntity) { viewModelScope.launch { repository.encryptToVault(file) } }
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.rescanPhysicalStorage() { viewModelScope.launch { repository.rescanPhysicalStorage() } }
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.loadNextPage() { }

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.appendPinDigit(digit: String) {
    val state = compatState()
    val now = SystemClock.elapsedRealtime()
    if (state.isPinCooldownActive(now, compatMessage(R.string.pin_error_too_many_attempts))) return
    state.clearExpiredPinCooldown()
    processPinDigit(state, digit, now)
}

private fun MainViewModel.processPinDigit(state: VmCompatState, digit: String, now: Long) {
    if (!state.acceptsPinDigit(digit)) return
    val pin = state.enteredPin.value + digit
    state.enteredPin.value = pin
    state.pinError.value = null
    if (pin.length != MIN_VAULT_PIN_LENGTH) return
    if (isVaultPinSetupRequired) {
        handlePinSetup(state, pin)
    } else {
        handlePinUnlock(state, pin, now)
    }
}

private fun VmCompatState.isPinCooldownActive(now: Long, cooldownMessage: String): Boolean {
    if (pinLockedUntilElapsedMs <= now) return false
    enteredPin.value = ""
    pinError.value = cooldownMessage
    return true
}

private fun VmCompatState.clearExpiredPinCooldown() {
    if (pinLockedUntilElapsedMs == 0L) return
    pinLockedUntilElapsedMs = 0L
    failedPinAttempts = 0
    pinError.value = null
}

private fun VmCompatState.acceptsPinDigit(digit: String): Boolean =
    enteredPin.value.length < MIN_VAULT_PIN_LENGTH && digit.length == 1 && digit[0].isDigit()

private fun MainViewModel.handlePinSetup(state: VmCompatState, pin: String) {
    val firstEntry = state.setupPinFirstEntry.value
    if (firstEntry == null) {
        state.setupPinFirstEntry.value = pin
        state.enteredPin.value = ""
        state.pinError.value = compatMessage(R.string.pin_error_reenter)
        return
    }
    if (firstEntry != pin || !repository.initializeVaultPin(pin)) {
        state.setupPinFirstEntry.value = null
        state.enteredPin.value = ""
        state.pinError.value = compatMessage(R.string.pin_error_mismatch)
        return
    }
    state.setupPinFirstEntry.value = null
    state.failedPinAttempts = 0
    state.pinLockedUntilElapsedMs = 0L
    if (repository.unlockVaultWithPin(pin)) {
        state.vaultUnlocked.value = true
        onVaultSessionAuthenticated()
        state.enteredPin.value = ""
        state.pinError.value = null
    } else {
        state.vaultUnlocked.value = false
        state.pinError.value = compatMessage(R.string.pin_error_session)
    }
}

private fun MainViewModel.handlePinUnlock(state: VmCompatState, pin: String, now: Long) {
    val unlocked = try {
        repository.unlockVaultWithPin(pin)
    } catch (locked: VaultAuthenticationLockedOutException) {
        state.failedPinAttempts = MAX_VAULT_FAILED_ATTEMPTS
        state.pinLockedUntilElapsedMs = now +
            (locked.lockedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
        state.enteredPin.value = ""
        state.pinError.value = compatMessage(R.string.pin_error_too_many_attempts)
        return
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
    if (unlocked) {
        state.failedPinAttempts = 0
        state.pinLockedUntilElapsedMs = 0L
        state.vaultUnlocked.value = true
        onVaultSessionAuthenticated()
        state.enteredPin.value = ""
        state.pinError.value = null
        return
    }
    state.failedPinAttempts += 1
    state.enteredPin.value = ""
    state.pinError.value = if (state.failedPinAttempts >= MAX_VAULT_FAILED_ATTEMPTS) {
        state.pinLockedUntilElapsedMs = now + VAULT_BASE_LOCKOUT_MS
        compatMessage(R.string.pin_error_too_many_attempts)
    } else {
        compatMessage(R.string.pin_error_incorrect)
    }
}

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.clearPinDigit() {
    val state = compatState()
    state.enteredPin.value = state.enteredPin.value.dropLast(1)
    state.pinError.value = null
}

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.lockVault() {
    val state = compatState()
    repository.lockVaultSession()
    state.vaultUnlocked.value = false
    state.vaultActivityGeneration.value += 1
    state.enteredPin.value = ""
    state.setupPinFirstEntry.value = null
    state.pinError.value = null
}

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.onBiometricSuccess(result: BiometricPrompt.AuthenticationResult) {
    val state = compatState()
    try {
        if (!repository.completeBiometricUnlock(result)) {
            state.pinError.value = compatMessage(R.string.pin_error_biometric_session)

            return
        }
        state.failedPinAttempts = 0
        state.pinLockedUntilElapsedMs = 0L
        state.vaultUnlocked.value = true
        onVaultSessionAuthenticated()
        state.pinError.value = null
    } catch (_: java.security.GeneralSecurityException) {
        state.vaultUnlocked.value = false
        state.pinError.value = compatMessage(R.string.pin_error_biometric_unlock)
    } catch (_: SecurityException) {
        state.vaultUnlocked.value = false
        state.pinError.value = compatMessage(R.string.pin_error_biometric_unlock)
    }
}

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.onBiometricEnrollmentSuccess(result: BiometricPrompt.AuthenticationResult): Boolean {
    return try {
        val success = repository.completeBiometricEnrollment(result)
        if (!success) compatState().pinError.value = compatMessage(R.string.pin_error_biometric_enrollment_saved)
        success
    } catch (_: java.security.GeneralSecurityException) {
        compatState().pinError.value = compatMessage(R.string.pin_error_biometric_enrollment_failed)
        false
    } catch (_: SecurityException) {
        compatState().pinError.value = compatMessage(R.string.pin_error_biometric_enrollment_failed)
        false
    }
}

/** Callback-only unlock is intentionally fail-closed and cannot change vault state. */
@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases; use onBiometricSuccess(AuthenticationResult)")
fun MainViewModel.onBiometricSuccess() {
    onBiometricError(compatMessage(R.string.pin_error_biometric_crypto_required))
}

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.onBiometricError(message: String) {
    compatState().pinError.value = message
}

@Deprecated("Compatibility presentation surface; migrate UI behavior to explicit domain use cases")
fun MainViewModel.changeVaultPin(oldPin: String, newPin: String): Boolean {
    val state = compatState()
    val success = repository.changeVaultPin(oldPin, newPin)
    if (success) {
        state.pinError.value = null
    } else {
        state.pinError.value = compatMessage(R.string.pin_update_failed)
    }
    return success
}
