package com.example

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.data.SmartManagerRepository
import com.example.data.VaultManagerEngine
import com.example.security.KeystoreVaultManager
import java.io.File
import com.example.ui.MainViewModel
import com.example.ui.appendPinDigit
import com.example.ui.autoSelectExtraDuplicates
import com.example.ui.cleanSelectedDuplicates
import com.example.ui.changeVaultPin
import com.example.ui.clearPinDigit
import com.example.ui.enteredPin
import com.example.ui.isVaultPinSetupRequired
import com.example.ui.isVaultUnlocked
import com.example.ui.lockVault
import com.example.ui.onBiometricError
import com.example.ui.onBiometricSuccess
import com.example.ui.pinError
import com.example.ui.selectedDuplicateIds
import com.example.ui.toggleDuplicateSelection
import com.example.ui.loadNextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultPinInstrumentedTest {
    private lateinit var app: VVFApplication

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VVFApplication
        app.deleteSharedPreferences("vvf_vault_prefs")
        File(app.noBackupFilesDir, "vvf_vault_prefs.secure").delete()
        File(app.noBackupFilesDir, "vvf_vault_prefs.secure.tmp").delete()
    }

    @Test
    fun vaultPin_verificationAndChange_persistsCorrectly() {
        val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = SmartManagerRepository(app, database.fileDao())

            assertFalse(repository.hasVaultPin())
            assertFalse(repository.verifyVaultPin("123456"))
            assertTrue(repository.initializeVaultPin("246810"))
            assertTrue(repository.hasVaultPin())
            assertFalse(repository.verifyVaultPin("000000"))

            assertTrue(repository.changeVaultPin("246810", "987654"))
            assertFalse(repository.verifyVaultPin("246810"))
            assertTrue(repository.verifyVaultPin("987654"))
        } finally {
            database.close()
        }
    }

    @Test
    fun vaultPin_productionStore_survives_manager_reopen() {
        val first = VaultManagerEngine(app, KeystoreVaultManager())
        assertTrue(first.initializeVaultPin("135790"))

        val reopened = VaultManagerEngine(app, KeystoreVaultManager())

        assertTrue(reopened.hasVaultPin())
        assertTrue(reopened.verifyVaultPin("135790"))
    }

    @Test
    fun mainViewModelCompat_pinAndBiometricStateMachine_handlesRealVaultState() {
        val viewModel = MainViewModel(app)

        assertTrue(viewModel.isVaultPinSetupRequired)
        viewModel.appendPinDigit("x")
        "246810".forEach { digit -> viewModel.appendPinDigit(digit.toString()) }
        assertEquals("Re-enter the new PIN to confirm.", viewModel.pinError.value)
        assertEquals("", viewModel.enteredPin.value)

        "135790".forEach { digit -> viewModel.appendPinDigit(digit.toString()) }
        assertEquals("PINs did not match. Try again.", viewModel.pinError.value)
        assertFalse(viewModel.isVaultUnlocked.value)

        "246810".forEach { digit -> viewModel.appendPinDigit(digit.toString()) }
        "246810".forEach { digit -> viewModel.appendPinDigit(digit.toString()) }
        assertTrue(viewModel.isVaultUnlocked.value)
        assertNull(viewModel.pinError.value)

        viewModel.lockVault()
        assertFalse(viewModel.isVaultUnlocked.value)
        "000000".forEach { digit -> viewModel.appendPinDigit(digit.toString()) }
        assertEquals("Incorrect PIN. Try again.", viewModel.pinError.value)

        viewModel.clearPinDigit()
        assertEquals("", viewModel.enteredPin.value)
        viewModel.onBiometricError("Biometric unavailable")
        assertEquals("Biometric unavailable", viewModel.pinError.value)
        viewModel.onBiometricSuccess()
        assertFalse(viewModel.isVaultUnlocked.value)
        assertEquals("Authenticated CryptoObject is required.", viewModel.pinError.value)
    }

    @Test
    fun mainViewModelCompat_fiveFailedPins_triggerCooldown() {
        val viewModel = MainViewModel(app)
        assertTrue(viewModel.repository.initializeVaultPin("246810"))

        repeat(5) {
            "000000".forEach { digit -> viewModel.appendPinDigit(digit.toString()) }
        }

        assertEquals("Too many incorrect attempts. Try again in 30 seconds.", viewModel.pinError.value)
        viewModel.appendPinDigit("2")
        assertEquals("", viewModel.enteredPin.value)
        assertEquals("Too many incorrect attempts. Try again in 30 seconds.", viewModel.pinError.value)
    }

    @Test
    fun mainViewModelCompat_selectionAndEmptyOperations_areSafe() {
        val viewModel = MainViewModel(app)

        viewModel.toggleDuplicateSelection(42L)
        assertEquals(setOf(42L), viewModel.selectedDuplicateIds.value)
        viewModel.toggleDuplicateSelection(42L)
        assertTrue(viewModel.selectedDuplicateIds.value.isEmpty())
        viewModel.autoSelectExtraDuplicates()
        assertTrue(viewModel.selectedDuplicateIds.value.isEmpty())
        viewModel.cleanSelectedDuplicates()
        viewModel.loadNextPage()
    }

    @Test
    fun mainViewModel_changeVaultPin_handlesUpdates() {
        val viewModel = MainViewModel(app)

        assertFalse(viewModel.changeVaultPin("111111", "555555"))
        assertEquals("Failed to update PIN. Check current PIN.", viewModel.pinError.value)

        assertTrue(viewModel.repository.initializeVaultPin("123456"))
        assertTrue(viewModel.changeVaultPin("123456", "555555"))
        assertNull(viewModel.pinError.value)
    }
}
