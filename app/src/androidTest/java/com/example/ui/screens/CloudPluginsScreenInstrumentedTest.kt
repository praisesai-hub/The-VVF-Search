package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
        composeTestRule.onNodeWithTag("google_drive_connect_btn")
            .assertIsDisplayed()
            .assertIsNotEnabled()
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
        composeTestRule.onNodeWithText("Google Drive Sync Queue (1)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sync_item_901").assertIsDisplayed()
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToNode(
            androidx.compose.ui.test.hasText("Keep Local")
        )
        composeTestRule.onNodeWithText("Keep Local").performClick()
        composeTestRule.onNodeWithText("Keep Cloud").assertIsDisplayed()
    }

    @Test
    fun unsupportedCloudPlugin_isMarkedComingSoonAndCannotBeEnabled() {
        val application = ApplicationProvider.getApplicationContext<VVFApplication>()
        val viewModel = MainViewModel(application)
        val oneDrivePlugin = com.example.data.PluginEntity(
            pluginId = "onedrive_sync",
            name = "OneDrive",
            category = "CLOUD",
            description = "Sync files to OneDrive",
            isEnabled = true,
            isCore = false,
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                CloudPluginsScreen(viewModel, emptyList(), listOf(oneDrivePlugin))
            }
        }

        composeTestRule.onNodeWithText("Plugin Manager").performClick()
        composeTestRule.onNodeWithTag("provider_coming_soon_onedrive_sync").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Not available in this release. Sync files to OneDrive"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("plugin_switch_onedrive_sync").assertIsNotEnabled()
    }
}
