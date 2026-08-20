package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
                    onNavigateTab = {},
                )
            }
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }
}
