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
import com.example.ui.changeVaultPin
import com.example.ui.clearPinDigit
import com.example.ui.enteredPin
import com.example.ui.isVaultPinSetupRequired
import com.example.ui.isVaultUnlocked
import com.example.ui.lockVault
import com.example.ui.onBiometricError
import com.example.ui.onBiometricSuccess
import com.example.ui.pinError
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
            assertFalse(repository.verifyVaultPin("1234"))
            assertTrue(repository.initializeVaultPin("2468"))
            assertTrue(repository.hasVaultPin())
            assertFalse(repository.verifyVaultPin("0000"))

            assertTrue(repository.changeVaultPin("2468", "9876"))
            assertFalse(repository.verifyVaultPin("2468"))
            assertTrue(repository.verifyVaultPin("9876"))
        } finally {
            database.close()
        }
    }

    @Test
    fun vaultPin_productionStore_survives_manager_reopen() {
        val first = VaultManagerEngine(app, KeystoreVaultManager())
        assertTrue(first.initializeVaultPin("1357"))

        val reopened = VaultManagerEngine(app, KeystoreVaultManager())

        assertTrue(reopened.hasVaultPin())
        assertTrue(reopened.verifyVaultPin("1357"))
    }

    @Test
    fun mainViewModelCompat_pinAndBiometricStateMachine_handlesRealVaultState() {
        val viewModel = MainViewModel(app)

        assertTrue(viewModel.isVaultPinSetupRequired)
        viewModel.appendPinDigit("x")
        "2468".forEach(viewModel::appendPinDigit)
        assertEquals("Re-enter the new PIN to confirm.", viewModel.pinError.value)
        assertEquals("", viewModel.enteredPin.value)

        "1357".forEach(viewModel::appendPinDigit)
        assertEquals("PINs did not match. Try again.", viewModel.pinError.value)
        assertFalse(viewModel.isVaultUnlocked.value)

        "2468".forEach(viewModel::appendPinDigit)
        "2468".forEach(viewModel::appendPinDigit)
        assertTrue(viewModel.isVaultUnlocked.value)
        assertNull(viewModel.pinError.value)

        viewModel.lockVault()
        assertFalse(viewModel.isVaultUnlocked.value)
        "0000".forEach(viewModel::appendPinDigit)
        assertEquals("Incorrect PIN. Try again.", viewModel.pinError.value)

        viewModel.clearPinDigit()
        assertEquals("", viewModel.enteredPin.value)
        viewModel.onBiometricError("Biometric unavailable")
        assertEquals("Biometric unavailable", viewModel.pinError.value)
        viewModel.onBiometricSuccess()
        assertTrue(viewModel.isVaultUnlocked.value)
        assertNull(viewModel.pinError.value)
    }

    @Test
    fun mainViewModel_changeVaultPin_handlesUpdates() {
        val viewModel = MainViewModel(app)

        assertFalse(viewModel.changeVaultPin("1111", "5555"))
        assertEquals("Failed to update PIN. Check current PIN.", viewModel.pinError.value)

        assertTrue(viewModel.repository.initializeVaultPin("1234"))
        assertTrue(viewModel.changeVaultPin("1234", "5555"))
        assertNull(viewModel.pinError.value)
    }
}
