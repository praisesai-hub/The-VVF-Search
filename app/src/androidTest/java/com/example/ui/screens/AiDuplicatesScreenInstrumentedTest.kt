package com.example.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.DuplicateGroup
import com.example.data.FileItemEntity
import com.example.ui.MainViewModel
import com.example.ui.duplicateScanProgress
import com.example.ui.documentDuplicates
import com.example.ui.documentStats
import com.example.ui.isDuplicateScanning
import com.example.ui.startDuplicateScan
import com.example.ui.semanticDuplicates
import com.example.ui.theme.VVFSmartManagerTheme
import com.example.ui.videoDuplicates
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiDuplicatesScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun duplicateCleanerRendersPopulatedGroupsAndInvokesControls() {
        val viewModel = mockViewModel(semanticSearchAvailable = true)
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
                    semanticSearchResults = listOf(
                        file(7L, "Search OCR match", "application/pdf", ocrText = "invoice total"),
                        file(8L, "Search tag match", "application/pdf", tags = "invoice")
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("Auto-clean duplicates in background").assertIsDisplayed()
        composeTestRule.onNodeWithTag("auto_clean_duplicates_switch").performClick()
        verify { viewModel.setAutoCleanDuplicatesBg(true) }

        composeTestRule.onNodeWithTag("start_scan_button").performClick()
        verify { viewModel.startDuplicateScan() }
        composeTestRule.onNodeWithTag("similarity_slider").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 files selected for deletion").assertIsDisplayed()

        composeTestRule.onNodeWithText("Exact hashes").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("exact-a.txt").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Visual matches").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("visual-a.jpg").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Video matches").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("clip.mp4").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Semantic matches").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("semantic.pdf").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Document matches").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("document.docx").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("section_tab_1").performClick()
        composeTestRule.onNodeWithText("OCR Indexed Documents (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("ocr-bill.png").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Extracted Text: total due").assertIsDisplayed()

        composeTestRule.onNodeWithTag("section_tab_2").performClick()
        composeTestRule.onNodeWithTag("semantic_search_input").assertIsEnabled()
        composeTestRule.onNodeWithTag("semantic_search_input").performTextInput("invoice")
        verify { viewModel.setSemanticQuery("invoice") }
        composeTestRule.onNodeWithText("Search OCR match").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Search tag match").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Match OCR: invoice total...").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Match Tag/Name: invoice").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun semanticSearchUnavailableRendersFailClosedState() {
        val viewModel = mockViewModel(semanticSearchAvailable = false)

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
        composeTestRule.onNodeWithTag("section_tab_2").performClick()
        composeTestRule.onNodeWithTag("semantic_search_input").assertIsNotEnabled()
        composeTestRule.onAllNodesWithText("No semantic search results found.").assertCountEquals(0)
    }

    private fun mockViewModel(semanticSearchAvailable: Boolean): MainViewModel {
        val viewModel = mockk<MainViewModel>(relaxed = true)
        every { viewModel.isDuplicateScanning } returns MutableStateFlow(false)
        every { viewModel.duplicateScanProgress } returns MutableStateFlow(1f)
        every { viewModel.videoDuplicates } returns MutableStateFlow(
            listOf(DuplicateGroup("Video matches", 3, 91, listOf(file(3L, "clip.mp4", "video/mp4"))))
        )
        every { viewModel.semanticDuplicates } returns MutableStateFlow(
            listOf(DuplicateGroup("Semantic matches", 4, 88, listOf(file(4L, "semantic.pdf", "application/pdf"))))
        )
        every { viewModel.documentDuplicates } returns MutableStateFlow(
            listOf(DuplicateGroup("Document matches", 7, 93, listOf(file(5L, "document.docx", "application/msword"))))
        )
        every { viewModel.documentStats } returns MutableStateFlow(Triple(4, 6, 0.4f))
        every { viewModel.autoCleanDuplicatesBg } returns MutableStateFlow(false)
        every { viewModel.isSemanticSearchAvailable } returns semanticSearchAvailable
        return viewModel
    }

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
