package com.example.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.VVFApplication
import com.example.data.CategoryStat
import com.example.data.FileCategory
import com.example.data.FileItemEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.VVFSmartManagerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DashboardScreenJvmCoverageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dashboardRendersReportRecentFileAndNavigationActions() {
        val application = ApplicationProvider.getApplicationContext<VVFApplication>()
        val viewModel = MainViewModel(application)
        val navigatedTabs = mutableListOf<Int>()
        val recentFile = FileItemEntity(
            name = "jvm-dashboard-recent.jpg",
            path = "/data/jvm-dashboard-recent.jpg",
            category = FileCategory.IMAGES.name,
            sizeBytes = 1024L,
            tags = "fixture",
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    categoryStats = listOf(
                        CategoryStat(FileCategory.IMAGES.name, count = 2, totalSize = 2048L),
                        CategoryStat(FileCategory.DOCUMENTS.name, count = 1, totalSize = 1024L),
                    ),
                    recentFiles = listOf(recentFile),
                    onNavigateTab = { navigatedTabs += it },
                )
            }
        }

        composeTestRule.onNodeWithText("VVF Smart Manager v2.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("System Health: 94% Excellent").assertIsDisplayed()
        val list = composeTestRule.onAllNodes(hasScrollToIndexAction()).onFirst()
        list.performScrollToNode(hasText("Recent Storage Files"))
        composeTestRule.onNodeWithText(recentFile.name).assertIsDisplayed()

        list.performScrollToNode(hasText("View Report"))
        composeTestRule.onNodeWithText("View Report").performClick()
        composeTestRule.onNodeWithText("Golden Rule Audit Report").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hide").performClick()
        composeTestRule.onAllNodesWithText("Golden Rule Audit Report").assertCountEquals(0)

        list.performScrollToNode(hasText("Clean Dupes"))
        composeTestRule.onNodeWithText("Clean Dupes").performClick()
        list.performScrollToNode(hasText("Secure Vault"))
        composeTestRule.onNodeWithText("Secure Vault").performClick()
        list.performScrollToNode(hasText("Cloud Sync"))
        composeTestRule.onNodeWithText("Cloud Sync").performClick()
        list.performScrollToNode(hasText("Images"))
        composeTestRule.onNodeWithText("Images").performClick()

        assertTrue(navigatedTabs.containsAll(listOf(1, 2, 3, 4)))
    }
}
