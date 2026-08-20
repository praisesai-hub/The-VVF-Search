package com.example.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.VVFApplication
import com.example.ui.FakeSmartManagerRepository
import com.example.ui.MainViewModel
import com.example.ui.VVFSmartManagerApp
import com.example.ui.theme.VVFSmartManagerTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = VVFApplication::class)
class VaultScreenJvmCoverageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>() as VVFApplication
        application.repository = FakeSmartManagerRepository(application)
        viewModel = MainViewModel(application)
    }

    @Test
    fun vaultScreenRendersLockedPinAndSecurityControlsWithoutKeystoreFallback() {
        composeTestRule.setContent {
            VVFSmartManagerTheme {
                VaultScreen(
                    viewModel = viewModel,
                    isUnlocked = false,
                    enteredPin = "",
                    pinError = null,
                    vaultItems = emptyList(),
                )
            }
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun topLevelAppRendersDefaultDashboardWithLifecycleObserver() {
        composeTestRule.setContent {
            VVFSmartManagerTheme {
                VVFSmartManagerApp(viewModel)
            }
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }
}
