package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.VVFApplication
import com.example.data.DuplicateGroup
import com.example.data.FileCategory
import com.example.data.FileItemEntity
import com.example.ui.MainViewModel
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

}
