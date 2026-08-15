package com.example.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.FileCategory
import com.example.ui.theme.VVFSmartManagerTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FilePickerUIInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fixtureFile: File

    @Before
    fun createFixtureFile(): Unit {
        fixtureFile = File(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext.filesDir,
            "coverage_image_test.png"
        )
        fixtureFile.writeBytes(byteArrayOf(1, 2, 3, 4))
    }

    @After
    fun removeFixtureFile(): Unit {
        fixtureFile.delete()
    }

    @Test(timeout = 60_000)
    fun filePickerSheet_filtersAndProcessesSelectedLocalFile(): Unit {
        val selectedCount = AtomicInteger(0)
        var dismissed = false

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                FilePickerSheet(
                    isOpen = true,
                    onDismiss = { dismissed = true },
                    onFilesSelected = { selectedCount.set(it.size) },
                    onUrisSelected = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("picker_search_input")
            .assertIsDisplayed()
            .performTextInput("coverage_image")
        composeTestRule.onNodeWithText("Images").performClick()
        composeTestRule.onNodeWithTag("file_picker_item_coverage_image_test.png")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
        composeTestRule.onNodeWithTag("process_selected_files_btn")
            .assertIsEnabled()
            .performClick()

        check(selectedCount.get() == 1)
        check(dismissed)
    }

    @Test(timeout = 60_000)
    fun filePickerSheet_clearSelectionDisablesProcessButton(): Unit {
        composeTestRule.setContent {
            VVFSmartManagerTheme {
                FilePickerSheet(
                    isOpen = true,
                    onDismiss = {},
                    onFilesSelected = {},
                    onUrisSelected = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("file_picker_item_coverage_image_test.png")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.onNodeWithText("Clear").performClick()
        composeTestRule.onNodeWithTag("process_selected_files_btn").assertIsNotEnabled()
    }

    @Test(timeout = 60_000)
    fun filePickerSheet_closeInvokesDismiss(): Unit {
        var dismissed = false

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                FilePickerSheet(
                    isOpen = true,
                    onDismiss = { dismissed = true },
                    onFilesSelected = {},
                    onUrisSelected = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("file_picker_close_btn").performClick()
        check(dismissed)
    }

    @Test(timeout = 60_000)
    fun filePickerSheet_whenClosedRendersNothing(): Unit {
        composeTestRule.setContent {
            VVFSmartManagerTheme {
                FilePickerSheet(
                    isOpen = false,
                    onDismiss = {},
                    onFilesSelected = {},
                    onUrisSelected = {}
                )
            }
        }

        composeTestRule.onAllNodesWithTag("picker_search_input").assertCountEquals(0)
    }

    @Test(timeout = 60_000)
    fun pickableFileRowItem_togglesSelection(): Unit {
        var selected = false
        val file = PickableLocalFile(
            name = "row-test.pdf",
            path = "/tmp/row-test.pdf",
            sizeBytes = 1024L,
            category = FileCategory.DOCUMENTS
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                PickableFileRowItem(
                    file = file,
                    isSelected = selected,
                    onToggleSelect = { selected = !selected }
                )
            }
        }

        composeTestRule.onNodeWithTag("file_picker_item_row-test.pdf")
            .assertIsDisplayed()
            .performClick()
        check(selected)
    }
}

