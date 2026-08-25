package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.R
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
        val strings = ApplicationProvider.getApplicationContext<VVFApplication>()
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

        val duplicateList = composeTestRule.onNode(hasScrollToIndexAction())
        val exactHeader = strings.getString(R.string.exact_hash_duplicates, 1)
        duplicateList.performScrollToNode(hasText(exactHeader))
        composeTestRule.onNodeWithText(exactHeader).assertExists()
        duplicateList.performScrollToNode(hasText("Exact hashes"))
        composeTestRule.onNodeWithText("Exact hashes").assertExists()
        duplicateList.performScrollToNode(hasText("exact-a.txt"))
        composeTestRule.onNodeWithText("exact-a.txt").assertExists()
        val visualHeader = strings.getString(R.string.visual_semantic_duplicates, 1)
        duplicateList.performScrollToNode(hasText(visualHeader))
        composeTestRule.onNodeWithText(visualHeader).assertExists()
        duplicateList.performScrollToNode(hasText("Visual matches"))
        composeTestRule.onNodeWithText("Visual matches").assertExists()
        duplicateList.performScrollToNode(hasText("visual-a.jpg"))
        composeTestRule.onNodeWithText("visual-a.jpg").assertExists()

        composeTestRule.onNodeWithTag("section_tab_1").performClick()
        composeTestRule.onNodeWithText("OCR Indexed Documents (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("ocr-bill.png").assertExists()
        composeTestRule.onNodeWithText("Extracted Text: total due").assertIsDisplayed()

        composeTestRule.onNodeWithTag("section_tab_2").performClick()
        composeTestRule.onNodeWithText("AI Search").assertIsDisplayed()
        composeTestRule.onNodeWithTag("semantic_search_input").assertIsEnabled()
    }

    @Test
    fun duplicateCleanerEmptyStateRemainsFailClosed() {
        val viewModel = realViewModel()
        val strings = ApplicationProvider.getApplicationContext<VVFApplication>()

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

        val duplicateList = composeTestRule.onNode(hasScrollToIndexAction())
        val exactHeader = strings.getString(R.string.exact_hash_duplicates, 0)
        duplicateList.performScrollToNode(hasText(exactHeader))
        composeTestRule.onNodeWithText(exactHeader).assertExists()
        val noExact = strings.getString(R.string.no_exact_hash_duplicates)
        duplicateList.performScrollToNode(hasText(noExact))
        composeTestRule.onNodeWithText(noExact).assertExists()
        val visualHeader = strings.getString(R.string.visual_semantic_duplicates, 0)
        duplicateList.performScrollToNode(hasText(visualHeader))
        composeTestRule.onNodeWithText(visualHeader).assertExists()
        val noVisual = strings.getString(R.string.no_visual_duplicates_matching, 80)
        duplicateList.performScrollToNode(hasText(noVisual))
        composeTestRule.onNodeWithText(noVisual).assertExists()
        val semanticHeader = strings.getString(R.string.semantic_vector_matches, 0)
        duplicateList.performScrollToNode(hasText(semanticHeader))
        composeTestRule.onNodeWithText(semanticHeader).assertExists()
        val noSemantic = strings.getString(R.string.no_semantic_vector_matches)
        duplicateList.performScrollToNode(hasText(noSemantic))
        composeTestRule.onNodeWithText(noSemantic).assertExists()
        composeTestRule.onNodeWithTag("section_tab_1").performClick()
        composeTestRule.onNodeWithText(strings.getString(R.string.ocr_indexed_documents, 0)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("section_tab_2").performClick()
        composeTestRule.onNodeWithTag("semantic_search_input").assertIsEnabled()
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
