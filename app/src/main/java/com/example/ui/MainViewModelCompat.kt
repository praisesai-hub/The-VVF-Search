package com.example.ui

import android.app.Application
import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.security.VaultKeyEnvelope
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
}

internal const val DEFAULT_VAULT_AUTO_LOCK_TIMEOUT_MS = 60_000L
private const val MIN_VAULT_AUTO_LOCK_TIMEOUT_MS = 15_000L
private const val MAX_VAULT_AUTO_LOCK_TIMEOUT_MS = 15 * 60_000L
private const val VAULT_AUTO_LOCK_TIMEOUT_PREF = "vault_auto_lock_timeout_ms"
private const val APP_SETTINGS_PREF = "vvf_app_settings"
private val compatStates = WeakHashMap<MainViewModel, VmCompatState>()
private fun MainViewModel.compatState(): VmCompatState = synchronized(compatStates) {
    compatStates.getOrPut(this) { VmCompatState() }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
private fun <T> Flow<T>.stateInCompat(vm: MainViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

val MainViewModel.filteredFiles: StateFlow<List<FileItemEntity>> get() = files
val MainViewModel.categoryStats: StateFlow<List<CategoryStat>> get() = dashboardStats
val MainViewModel.recentFiles: StateFlow<List<FileItemEntity>> get() = repository.recentFiles.stateInCompat(this, emptyList())
val MainViewModel.recycleBinFiles: StateFlow<List<FileItemEntity>> get() = repository.recycleBinFiles.stateInCompat(this, emptyList())
val MainViewModel.ocrScannedFiles: StateFlow<List<FileItemEntity>> get() = repository.ocrScannedFiles.stateInCompat(this, emptyList())
val MainViewModel.level1ExactDuplicates: StateFlow<List<DuplicateGroup>> get() = repository.exactDuplicates.stateInCompat(this, emptyList())
val MainViewModel.level3VisualDuplicates: StateFlow<List<DuplicateGroup>> get() = repository.getVisualDuplicates(similarityThreshold).stateInCompat(this, emptyList())
val MainViewModel.videoDuplicates: StateFlow<List<DuplicateGroup>> get() = repository.getVideoDuplicates(similarityThreshold).stateInCompat(this, emptyList())
val MainViewModel.semanticDuplicates: StateFlow<List<DuplicateGroup>> get() = repository.getSemanticDuplicates(similarityThreshold).stateInCompat(this, emptyList())
val MainViewModel.documentDuplicates: StateFlow<List<DuplicateGroup>> get() = repository.getDocumentDuplicates().stateInCompat(this, emptyList())
val MainViewModel.documentStats: StateFlow<Triple<Int, Int, Float>> get() = repository.documentStats.stateInCompat(this, Triple(0, 0, 1f))
val MainViewModel.selectedDuplicateIds: StateFlow<Set<Long>> get() = compatState().selectedIds
val MainViewModel.isDuplicateScanning: StateFlow<Boolean> get() = repository.isScanning
val MainViewModel.duplicateScanProgress: StateFlow<Float> get() = repository.scanProgress
val MainViewModel.isPageLoading: StateFlow<Boolean> get() = compatState().pageLoading
val MainViewModel.isVaultUnlocked: StateFlow<Boolean> get() = compatState().vaultUnlocked
val MainViewModel.vaultAutoLockTimeoutMs: StateFlow<Long>
    get() = compatState().also { loadVaultAutoLockSettings(it) }.vaultAutoLockTimeoutMs
val MainViewModel.vaultActivityGeneration: StateFlow<Long>
    get() = compatState().vaultActivityGeneration
val MainViewModel.enteredPin: StateFlow<String> get() = compatState().enteredPin
val MainViewModel.pinError: StateFlow<String?> get() = compatState().pinError
val MainViewModel.isVaultPinSetupRequired: Boolean get() = !repository.hasVaultPin()
val MainViewModel.isVaultPinUpgradeRequired: Boolean get() = repository.requiresVaultPinUpgrade()
val MainViewModel.hasBiometricEnrollment: Boolean get() = repository.hasBiometricEnrollment()

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
val MainViewModel.semanticSearchResults: StateFlow<List<FileItemEntity>> get() =
    semanticQuery.debounce(200).flatMapLatest { repository.searchSemanticFiles(it) }
        .stateInCompat(this, emptyList())

fun MainViewModel.startDuplicateScan() { repository.startIncrementalDuplicateScan() }

/**
 * Changes the vault idle timeout. Values are deliberately bounded so the vault cannot be
 * accidentally configured to remain unlocked indefinitely.
 */
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
fun MainViewModel.recordVaultActivity() {
    val state = compatState()
    if (state.vaultUnlocked.value) state.vaultActivityGeneration.value += 1
}

/** Immediately clears an authenticated vault session when the app is no longer foregrounded. */
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

fun MainViewModel.toggleDuplicateSelection(id: Long) {
    val state = compatState()
    state.selectedIds.value = state.selectedIds.value.toMutableSet().apply {
        if (!add(id)) remove(id)
    }
}

fun MainViewModel.autoSelectExtraDuplicates() {
    val groups = level1ExactDuplicates.value + level3VisualDuplicates.value + videoDuplicates.value + semanticDuplicates.value + documentDuplicates.value
    compatState().selectedIds.value = groups.flatMap { it.files.drop(1).map { file -> file.id } }.toSet()
}

fun MainViewModel.cleanSelectedDuplicates() {
    val ids = selectedDuplicateIds.value
    if (ids.isEmpty()) return
    viewModelScope.launch {
        repository.cleanSelectedDuplicates(ids)
        compatState().selectedIds.value = emptySet()
    }
}

fun MainViewModel.moveToRecycleBin(file: FileItemEntity) { viewModelScope.launch { repository.moveToRecycleBin(file) } }
fun MainViewModel.restoreFromRecycleBin(file: FileItemEntity) { viewModelScope.launch { repository.restoreFromRecycleBin(file) } }
fun MainViewModel.deletePermanently(file: FileItemEntity) { viewModelScope.launch { repository.deletePermanently(file) } }
fun MainViewModel.emptyRecycleBin() { viewModelScope.launch { repository.emptyRecycleBin() } }
fun MainViewModel.encryptToVault(file: FileItemEntity) { viewModelScope.launch { repository.encryptToVault(file) } }
fun MainViewModel.rescanPhysicalStorage() { viewModelScope.launch { repository.rescanPhysicalStorage() } }
fun MainViewModel.loadNextPage() { }

fun MainViewModel.appendPinDigit(digit: String) {
    val state = compatState()
    val lockout = repository.vaultPinLockoutStatus()
    if (lockout.isLocked) {
        state.enteredPin.value = ""
        state.pinError.value = pinLockoutMessage(lockout.remainingMs)
        return
    }
    processPinDigit(state, digit)
}

private fun MainViewModel.processPinDigit(state: VmCompatState, digit: String) {
    if (!state.acceptsPinDigit(digit)) return
    val pin = state.enteredPin.value + digit
    state.enteredPin.value = pin
    state.pinError.value = null
    if (pin.length != VaultKeyEnvelope.PIN_LENGTH) return
    if (isVaultPinSetupRequired) {
        handlePinSetup(state, pin)
    } else {
        handlePinUnlock(state, pin)
    }
}

private fun VmCompatState.acceptsPinDigit(digit: String): Boolean =
    enteredPin.value.length < VaultKeyEnvelope.PIN_LENGTH && digit.length == 1 && digit[0].isDigit()

private fun MainViewModel.handlePinSetup(state: VmCompatState, pin: String) {
    val firstEntry = state.setupPinFirstEntry.value
    if (firstEntry == null) {
        state.setupPinFirstEntry.value = pin
        state.enteredPin.value = ""
        state.pinError.value = "Re-enter the new PIN to confirm."
        return
    }
    if (firstEntry != pin || !repository.initializeVaultPin(pin)) {
        state.setupPinFirstEntry.value = null
        state.enteredPin.value = ""
        state.pinError.value = "PINs did not match. Try again."
        return
    }
    state.setupPinFirstEntry.value = null
    if (repository.unlockVaultWithPin(pin)) {
        state.vaultUnlocked.value = true
        onVaultSessionAuthenticated()
        state.enteredPin.value = ""
        state.pinError.value = null
    } else {
        state.vaultUnlocked.value = false
        state.pinError.value = "Vault session could not be created."
    }
}

private fun MainViewModel.handlePinUnlock(state: VmCompatState, pin: String) {
    val outcome = try {
        if (repository.unlockVaultWithPin(pin)) PinUnlockOutcome.SUCCESS else PinUnlockOutcome.INVALID
    } catch (_: VaultPinUpgradeRequiredException) {
        PinUnlockOutcome.UPGRADE_REQUIRED
    } catch (_: VaultPinLockedException) {
        PinUnlockOutcome.LOCKED
    } catch (_: SecurityException) {
        PinUnlockOutcome.INVALID
    }
    when (outcome) {
        PinUnlockOutcome.SUCCESS -> {
            state.vaultUnlocked.value = true
            onVaultSessionAuthenticated()
            state.enteredPin.value = ""
            state.pinError.value = null
        }
        PinUnlockOutcome.UPGRADE_REQUIRED -> {
            state.enteredPin.value = ""
            state.pinError.value = "Your existing PIN must be upgraded to six digits before unlocking."
        }
        PinUnlockOutcome.LOCKED -> {
            state.enteredPin.value = ""
            state.pinError.value = pinLockoutMessage(repository.vaultPinLockoutStatus().remainingMs)
        }
        PinUnlockOutcome.INVALID -> showInvalidPinError(state)
    }
}

private fun MainViewModel.showInvalidPinError(state: VmCompatState) {
    state.enteredPin.value = ""
    val lockout = repository.vaultPinLockoutStatus()
    state.pinError.value = if (lockout.isLocked) {
        pinLockoutMessage(lockout.remainingMs)
    } else {
        "Incorrect PIN. Try again."
    }
}

private enum class PinUnlockOutcome {
    SUCCESS,
    INVALID,
    UPGRADE_REQUIRED,
    LOCKED
}

private const val MILLIS_PER_SECOND = 1_000L
private const val ROUND_UP_MILLIS_OFFSET = MILLIS_PER_SECOND - 1L

private fun pinLockoutMessage(remainingMs: Long): String {
    val seconds = ((remainingMs + ROUND_UP_MILLIS_OFFSET) / MILLIS_PER_SECOND).coerceAtLeast(1L)
    return "Too many incorrect attempts. Try again in $seconds seconds."
}

fun MainViewModel.clearPinDigit() {
    val state = compatState()
    state.enteredPin.value = state.enteredPin.value.dropLast(1)
    state.pinError.value = null
}

fun MainViewModel.lockVault() {
    val state = compatState()
    repository.lockVaultSession()
    state.vaultUnlocked.value = false
    state.vaultActivityGeneration.value += 1
    state.enteredPin.value = ""
    state.setupPinFirstEntry.value = null
    state.pinError.value = null
}

fun MainViewModel.onBiometricSuccess(result: BiometricPrompt.AuthenticationResult) {
    val state = compatState()
    try {
        if (!repository.completeBiometricUnlock(result)) {
            state.pinError.value = "Authenticated vault session could not be created."
            return
        }
        state.vaultUnlocked.value = true
        onVaultSessionAuthenticated()
        state.pinError.value = null
    } catch (_: java.security.GeneralSecurityException) {
        state.vaultUnlocked.value = false
        state.pinError.value = "Biometric vault unlock failed."
    } catch (_: SecurityException) {
        state.vaultUnlocked.value = false
        state.pinError.value = "Biometric vault unlock failed."
    }
}

fun MainViewModel.onBiometricEnrollmentSuccess(result: BiometricPrompt.AuthenticationResult): Boolean {
    return try {
        val success = repository.completeBiometricEnrollment(result)
        if (!success) compatState().pinError.value = "Biometric enrollment could not be saved."
        success
    } catch (_: java.security.GeneralSecurityException) {
        compatState().pinError.value = "Biometric enrollment failed."
        false
    } catch (_: SecurityException) {
        compatState().pinError.value = "Biometric enrollment failed."
        false
    }
}

/** Callback-only unlock is intentionally fail-closed and cannot change vault state. */
@Deprecated("Use onBiometricSuccess(AuthenticationResult)")
fun MainViewModel.onBiometricSuccess() {
    onBiometricError("Authenticated CryptoObject is required.")
}

fun MainViewModel.onBiometricError(message: String) {
    compatState().pinError.value = message
}

fun MainViewModel.changeVaultPin(oldPin: String, newPin: String): Boolean {
    val state = compatState()
    val success = repository.changeVaultPin(oldPin, newPin)
    if (success) {
        state.pinError.value = null
    } else {
        state.pinError.value = "Failed to update PIN. Check current PIN."
    }
    return success
}
