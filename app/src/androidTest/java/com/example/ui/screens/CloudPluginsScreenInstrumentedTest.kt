package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.VVFApplication
import com.example.ui.MainViewModel
import com.example.ui.theme.VVFSmartManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CloudPluginsScreenInstrumentedTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun signedOutSurface_displaysFoundationBrandingAndFailClosedConnectAction() {
        val application = ApplicationProvider.getApplicationContext<VVFApplication>()
        val viewModel = MainViewModel(application)

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                CloudPluginsScreen(
                    viewModel = viewModel,
                    cloudSyncItems = emptyList(),
                    plugins = emptyList(),
                )
            }
        }

        composeTestRule.onNodeWithTag("vvf_login_brand_logo").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("VVF Foundation logo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("google_drive_connect_btn").assertIsDisplayed().performClick()
        composeTestRule.runOnIdle {
            assertEquals(
                "Google sign-in requires the real OAuth authorization flow; local/mock sign-in is disabled.",
                viewModel.globalError.value,
            )
        }
    }

    @Test
    fun cloudQueue_andConflictControls_renderWithoutAuthentication() {
        val application = ApplicationProvider.getApplicationContext<VVFApplication>()
        val viewModel = MainViewModel(application)
        val item = com.example.data.CloudSyncItemEntity(
            id = 901L,
            provider = "GOOGLE_DRIVE",
            fileName = "fixture.pdf",
            filePath = "/fixture.pdf",
            fileSize = 1024L,
            status = "FAILED",
            lastSyncedMs = 0L,
            isCore = false,
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                CloudPluginsScreen(viewModel, listOf(item), emptyList())
            }
        }

        composeTestRule.onNodeWithTag("vvf_login_brand_logo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cloud Sync Queue (1)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sync_item_901").assertIsDisplayed()
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToNode(
            androidx.compose.ui.test.hasText("Keep Local")
        )
        composeTestRule.onNodeWithText("Keep Local").performClick()
        composeTestRule.onNodeWithText("Keep Cloud").assertIsDisplayed()
    }
}
