package com.example.ui

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.WeakHashMap

private class VmCompatState {
    val vaultUnlocked = MutableStateFlow(false)
    val enteredPin = MutableStateFlow("")
    val pinError = MutableStateFlow<String?>(null)
    val setupPinFirstEntry = MutableStateFlow<String?>(null)
    val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val pageLoading = MutableStateFlow(false)
    var failedPinAttempts: Int = 0
    var pinLockedUntilElapsedMs: Long = 0L
}

private const val PIN_LOCKOUT_THRESHOLD = 5
private const val PIN_LOCKOUT_DURATION_MS = 30_000L
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
val MainViewModel.enteredPin: StateFlow<String> get() = compatState().enteredPin
val MainViewModel.pinError: StateFlow<String?> get() = compatState().pinError
val MainViewModel.isVaultPinSetupRequired: Boolean get() = !repository.hasVaultPin()

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
val MainViewModel.semanticSearchResults: StateFlow<List<FileItemEntity>> get() =
    semanticQuery.debounce(200).flatMapLatest { repository.searchSemanticFiles(it) }
        .stateInCompat(this, emptyList())

fun MainViewModel.startDuplicateScan() { repository.startIncrementalDuplicateScan() }

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
    val now = SystemClock.elapsedRealtime()
    if (state.pinLockedUntilElapsedMs > now) {
        state.enteredPin.value = ""
        state.pinError.value = "Too many incorrect attempts. Try again in 30 seconds."
        return
    }
    if (state.pinLockedUntilElapsedMs != 0L) {
        state.pinLockedUntilElapsedMs = 0L
        state.failedPinAttempts = 0
        state.pinError.value = null
    }
    if (state.enteredPin.value.length >= 4 || digit.length != 1 || !digit[0].isDigit()) return
    val pin = state.enteredPin.value + digit
    state.enteredPin.value = pin
    state.pinError.value = null
    if (pin.length == 4) {
        if (isVaultPinSetupRequired) {
            val firstEntry = state.setupPinFirstEntry.value
            if (firstEntry == null) {
                state.setupPinFirstEntry.value = pin
                state.enteredPin.value = ""
                state.pinError.value = "Re-enter the new PIN to confirm."
            } else if (firstEntry == pin && repository.initializeVaultPin(pin)) {
                state.setupPinFirstEntry.value = null
                state.failedPinAttempts = 0
                state.pinLockedUntilElapsedMs = 0L
                state.vaultUnlocked.value = true
                state.enteredPin.value = ""
                state.pinError.value = null
            } else {
                state.setupPinFirstEntry.value = null
                state.enteredPin.value = ""
                state.pinError.value = "PINs did not match. Try again."
            }
        } else if (repository.verifyVaultPin(pin, repository.getStoredVaultPinHash())) {
            state.failedPinAttempts = 0
            state.pinLockedUntilElapsedMs = 0L
            state.vaultUnlocked.value = true
            state.enteredPin.value = ""
        } else {
            state.failedPinAttempts += 1
            state.enteredPin.value = ""
            if (state.failedPinAttempts >= PIN_LOCKOUT_THRESHOLD) {
                state.pinLockedUntilElapsedMs = now + PIN_LOCKOUT_DURATION_MS
                state.pinError.value = "Too many incorrect attempts. Try again in 30 seconds."
            } else {
                state.pinError.value = "Incorrect PIN. Try again."
            }
        }
    }
}

fun MainViewModel.clearPinDigit() {
    val state = compatState()
    state.enteredPin.value = state.enteredPin.value.dropLast(1)
    state.pinError.value = null
}

fun MainViewModel.lockVault() {
    val state = compatState()
    state.vaultUnlocked.value = false
    state.enteredPin.value = ""
    state.setupPinFirstEntry.value = null
    state.pinError.value = null
}

fun MainViewModel.onBiometricSuccess() {
    val state = compatState()
    state.failedPinAttempts = 0
    state.pinLockedUntilElapsedMs = 0L
    state.vaultUnlocked.value = true
    state.pinError.value = null
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
