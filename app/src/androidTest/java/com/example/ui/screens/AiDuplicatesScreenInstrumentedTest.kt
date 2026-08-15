package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.VVFApplication
import com.example.data.DuplicateGroup
import com.example.data.FileItemEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.VVFSmartManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiDuplicatesScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun duplicateCleanerRendersProvidedGroupsAndSections() {
        val viewModel = realViewModel()
        val exactFile = file(1L, "exact-a.txt", "text/plain", tags = "receipt")
        val visualFile = file(2L, "visual-a.jpg", "image/jpeg", sizeBytes = 2048L)

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                AiDuplicatesScreen(
                    viewModel = viewModel,
                    level1Duplicates = listOf(DuplicateGroup("Exact hashes", 1, 100, listOf(exactFile))),
                    level3Duplicates = listOf(DuplicateGroup("Visual matches", 3, 87, listOf(visualFile))),
                    similarityThreshold = 82f,
                    selectedDuplicateIds = setOf(exactFile.id),
                    semanticQuery = "bill",
                    ocrScannedFiles = listOf(file(6L, "ocr-bill.png", "image/png", ocrText = "total due")),
                    semanticSearchResults = emptyList()
                )
            }
        }

        composeTestRule.onNodeWithText("Auto-clean duplicates in background").assertIsDisplayed()
        composeTestRule.onNodeWithTag("auto_clean_duplicates_switch").performClick()
        composeTestRule.onNodeWithTag("auto_clean_duplicates_switch").assertIsOn()
        composeTestRule.onNodeWithTag("auto_clean_duplicates_switch").performClick()
        composeTestRule.onNodeWithTag("auto_clean_duplicates_switch").assertIsOff()
        composeTestRule.onNodeWithTag("start_scan_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("similarity_slider").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 files selected for deletion").assertIsDisplayed()

        composeTestRule.onNodeWithText("Exact hashes").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("exact-a.txt").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Visual matches").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("visual-a.jpg").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("section_tab_1").performClick()
        composeTestRule.onNodeWithText("OCR Indexed Documents (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("ocr-bill.png").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Extracted Text: total due").assertIsDisplayed()

        composeTestRule.onNodeWithTag("section_tab_2").performClick()
        composeTestRule.onNodeWithText("AI Search").assertIsDisplayed()
        composeTestRule.onNodeWithTag("semantic_search_input").assertIsNotEnabled()
    }

    @Test
    fun duplicateCleanerEmptyStateRemainsFailClosed() {
        val viewModel = realViewModel()

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                AiDuplicatesScreen(
                    viewModel = viewModel,
                    level1Duplicates = emptyList(),
                    level3Duplicates = emptyList(),
                    similarityThreshold = 80f,
                    selectedDuplicateIds = emptySet(),
                    semanticQuery = "",
                    ocrScannedFiles = emptyList(),
                    semanticSearchResults = emptyList()
                )
            }
        }

        composeTestRule.onNodeWithText("No exact hash duplicate files detected.").assertIsDisplayed()
        composeTestRule.onNodeWithText("No visual duplicates matching 80%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Semantic Search — Coming Soon (model not bundled)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("section_tab_1").performClick()
        composeTestRule.onNodeWithText("OCR Indexed Documents (0)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("section_tab_2").performClick()
        composeTestRule.onNodeWithTag("semantic_search_input").assertIsNotEnabled()
    }

    private fun realViewModel(): MainViewModel = MainViewModel(
        ApplicationProvider.getApplicationContext<VVFApplication>()
    )

    private fun file(
        id: Long,
        name: String,
        path: String,
        sizeBytes: Long = 1024L,
        ocrText: String = "",
        tags: String = ""
    ) = FileItemEntity(
        id = id,
        name = name,
        path = path,
        category = "DOCUMENTS",
        sizeBytes = sizeBytes,
        ocrText = ocrText,
        tags = tags
    )
}
