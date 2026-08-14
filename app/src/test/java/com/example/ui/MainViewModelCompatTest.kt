@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.ui

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.example.VVFApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = VVFApplication::class)
class MainViewModelCompatTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>() as VVFApplication
        app.repository = FakeSmartManagerRepository(app)
        viewModel = MainViewModel(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun toggleDuplicateSelection_addsThenRemovesTheSameId() {
        assertEquals(emptySet<Long>(), viewModel.selectedDuplicateIds.value)

        viewModel.toggleDuplicateSelection(42L)
        assertEquals(setOf(42L), viewModel.selectedDuplicateIds.value)

        viewModel.toggleDuplicateSelection(42L)
        assertEquals(emptySet<Long>(), viewModel.selectedDuplicateIds.value)
    }

    @Test
    fun appendPinDigit_ignoresInvalidInputAndClearRemovesLastDigit() {
        viewModel.appendPinDigit("12")
        viewModel.appendPinDigit("x")
        assertEquals("", viewModel.enteredPin.value)

        viewModel.appendPinDigit("1")
        viewModel.appendPinDigit("2")
        assertEquals("12", viewModel.enteredPin.value)

        viewModel.clearPinDigit()
        assertEquals("1", viewModel.enteredPin.value)
        assertEquals(null, viewModel.pinError.value)
    }

    @Test
    fun lockVault_clearsUnlockStatePinAndSetupState() {
        viewModel.onBiometricSuccess()
        viewModel.appendPinDigit("1")
        viewModel.appendPinDigit("2")
        viewModel.onBiometricError("Biometric unavailable")

        viewModel.lockVault()

        assertFalse(viewModel.isVaultUnlocked.value)
        assertEquals("", viewModel.enteredPin.value)
        assertEquals(null, viewModel.pinError.value)
    }

    @Test
    fun biometricCallbacksUpdateVaultStateAndError() {
        viewModel.onBiometricError("Sensor locked")
        assertEquals("Sensor locked", viewModel.pinError.value)
        assertFalse(viewModel.isVaultUnlocked.value)

        viewModel.onBiometricSuccess()
        assertTrue(viewModel.isVaultUnlocked.value)
        assertEquals(null, viewModel.pinError.value)
    }

    @Test
    fun cleanSelectedDuplicates_withEmptySelectionIsSafeNoOp() {
        viewModel.cleanSelectedDuplicates()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptySet<Long>(), viewModel.selectedDuplicateIds.value)
    }

    @Test
    fun compatibilityAliasesExposeUnderlyingStateFlows() {
        assertSame(viewModel.files, viewModel.filteredFiles)
        assertSame(viewModel.dashboardStats, viewModel.categoryStats)
        assertSame(viewModel.repository.isScanning, viewModel.isDuplicateScanning)
        assertSame(viewModel.repository.scanProgress, viewModel.duplicateScanProgress)
        assertEquals(emptyList< com.example.data.FileItemEntity>(), viewModel.recentFiles.value)
        assertEquals(emptyList< com.example.data.DuplicateGroup>(), viewModel.level1ExactDuplicates.value)
        assertEquals(Triple(0, 0, 1f), viewModel.documentStats.value)
        assertFalse(viewModel.isVaultPinSetupRequired)
    }

    @Test
    fun signInToGoogleFailsClosedAndClearGlobalErrorClearsMessage() {
        viewModel.signInToGoogle("user@example.com", "User")

        assertTrue(viewModel.globalError.value.orEmpty().contains("real OAuth authorization flow"))

        viewModel.clearGlobalError()
        assertEquals(null, viewModel.globalError.value)
    }
}
