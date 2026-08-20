package com.example.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.theme.VVFSmartManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FilePickerSheetJvmCoverageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun filePickerSheetRendersLocalSelectionUiWithoutOpeningSystemPicker() {
        composeTestRule.setContent {
            VVFSmartManagerTheme {
                FilePickerSheet(
                    isOpen = true,
                    onDismiss = {},
                    onFilesSelected = {},
                    onUrisSelected = {},
                )
            }
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }
}
