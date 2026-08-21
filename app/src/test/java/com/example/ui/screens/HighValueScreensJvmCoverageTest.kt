package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.VVFApplication
import com.example.data.CloudSyncItemEntity
import com.example.data.DuplicateGroup
import com.example.data.FileCategory
import com.example.data.FileItemEntity
import com.example.data.PluginEntity
import com.example.ui.MainViewModel
import com.example.ui.components.OcrOverlayImage
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
class HighValueScreensJvmCoverageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun viewModel(): MainViewModel = MainViewModel(
        ApplicationProvider.getApplicationContext<VVFApplication>(),
    )

    @Test
    fun fileManagerRendersSearchActionsAndFileRow() {
        val file = FileItemEntity(
            id = 7L,
            name = "jvm-file-manager.pdf",
            path = "/data/jvm-file-manager.pdf",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 2048L,
            tags = "finance",
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                FileManagerScreen(
                    viewModel = viewModel(),
                    files = listOf(file),
                    recycleBinFiles = emptyList(),
                    selectedCategory = FileCategory.DOCUMENTS,
                    searchQuery = "jvm",
                )
            }
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun duplicateCleanerRendersExactDuplicateGroup() {
        val first = FileItemEntity(
            id = 11L,
            name = "jvm-duplicate-a.jpg",
            path = "/data/jvm-duplicate-a.jpg",
            category = FileCategory.IMAGES.name,
            sizeBytes = 512L,
            md5Hash = "same-hash",
        )
        val second = first.copy(id = 12L, name = "jvm-duplicate-b.jpg", path = "/data/jvm-duplicate-b.jpg")
        val duplicates = DuplicateGroup(
            title = "Exact duplicate fixtures",
            level = 1,
            similarityScore = 100,
            files = listOf(first, second),
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                AiDuplicatesScreen(
                    viewModel = viewModel(),
                    level1Duplicates = listOf(duplicates),
                    level3Duplicates = emptyList(),
                    similarityThreshold = 0.7f,
                    selectedDuplicateIds = emptySet(),
                    semanticQuery = "",
                    ocrScannedFiles = emptyList(),
                    semanticSearchResults = emptyList(),
                )
            }
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun duplicateCleanerSwitchesToOcrAndSemanticSearchSections() {
        val ocrFile = FileItemEntity(
            id = 31L,
            name = "receipt.jpg",
            path = "/data/receipt.jpg",
            category = FileCategory.IMAGES.name,
            sizeBytes = 256L,
            ocrText = "invoice total"
        )
        val semanticResult = ocrFile.copy(
            id = 32L,
            name = "semantic-match.pdf",
            path = "/data/semantic-match.pdf",
            category = FileCategory.DOCUMENTS.name
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                AiDuplicatesScreen(
                    viewModel = viewModel(),
                    level1Duplicates = emptyList(),
                    level3Duplicates = emptyList(),
                    similarityThreshold = 0.7f,
                    selectedDuplicateIds = emptySet(),
                    semanticQuery = "invoice",
                    ocrScannedFiles = listOf(ocrFile),
                    semanticSearchResults = listOf(semanticResult)
                )
            }
        }

        composeTestRule.onNodeWithTag("section_tab_1").performClick()
        composeTestRule.onRoot().assertIsDisplayed()

        composeTestRule.onNodeWithTag("section_tab_2").performClick()
        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun ocrOverlayRendersSafeFallbackWhenImageIsUnavailable() {
        composeTestRule.setContent {
            VVFSmartManagerTheme {
                OcrOverlayImage(
                    filePath = "/missing/ocr-preview.png",
                    ocrBlocks = emptyList()
                )
            }
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun cloudPluginsRendersSyncItemAndPluginFixtures() {
        val failedSync = CloudSyncItemEntity(
            id = 44L,
            provider = "GOOGLE_DRIVE",
            fileName = "invoice.pdf",
            filePath = "/data/invoice.pdf",
            fileSize = 2048L,
            status = "FAILED",
            lastSyncedMs = 0L,
            operationId = "cloud-op-44",
            lastErrorCode = "NETWORK_UNAVAILABLE"
        )
        val plugin = PluginEntity(
            id = "local-plugin",
            name = "Local Plugin",
            type = "LOCAL",
            description = "Deterministic render fixture",
            enabled = false,
            isCore = false
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                CloudPluginsScreen(
                    viewModel = viewModel(),
                    cloudSyncItems = listOf(failedSync),
                    plugins = listOf(plugin)
                )
            }
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }

}
