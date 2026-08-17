package com.example

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testBottomNavigationSwitchesScreens() {
        // 1. Verify Dashboard is displayed by default.
        composeTestRule.onNodeWithTag("dashboard_health_card").assertIsDisplayed()

        // 2. Click on Vault and verify the real PIN entry controls appear.
        composeTestRule.onNodeWithTag("nav_tab_2").performClick()
        composeTestRule.onNodeWithTag("pin_key_1").assertIsDisplayed()

        // 3. Click on Files and verify the real file-manager search control appears.
        composeTestRule.onNodeWithTag("nav_tab_1").performClick()
        composeTestRule.onNodeWithTag("file_search_input").assertIsDisplayed()
    }

    @Test
    fun testDashboardRoadmapAndQuickActions() {
        composeTestRule.onNodeWithText("View Report").performClick()
        composeTestRule.onNodeWithText("Golden Rule Audit Report").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hide").performClick()
        composeTestRule.onAllNodesWithText("Golden Rule Audit Report").assertCountEquals(0)

        composeTestRule.onNodeWithText("Clean Dupes").performClick()
        composeTestRule.onNodeWithTag("section_tab_0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_tab_0").performClick()

        composeTestRule.onNodeWithText("Secure Vault").performClick()
        composeTestRule.onNodeWithTag("pin_key_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_tab_0").performClick()

        composeTestRule.onNodeWithText("Cloud Sync").performClick()
        composeTestRule.onNodeWithText("Cloud Providers").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_tab_0").performClick()
    }

    @Test
    fun testFileManagerSearchCategoryAndEmptyRecycleBin() {
        composeTestRule.onNodeWithTag("nav_tab_1").performClick()
        composeTestRule.onNodeWithTag("file_search_input").performTextInput("report")
        composeTestRule.onNodeWithContentDescription("Clear").performClick()
        composeTestRule.onNodeWithText("Images").performClick()
        composeTestRule.onNodeWithText("View Trash").performClick()
        composeTestRule.onNodeWithText("Recycle Bin is empty.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hide Trash").performClick()
        composeTestRule.onAllNodesWithText("Recycle Bin is empty.").assertCountEquals(0)
    }

    @Test
    fun testVaultPinSetupUnlockOptionsAndLock() {
        composeTestRule.onNodeWithTag("nav_tab_2").performClick()
        val setupRequired = composeTestRule.onAllNodesWithText("Create a 4-Digit Master PIN").fetchSemanticsNodes().isNotEmpty()
        if (setupRequired) {
            listOf("1", "2", "3", "4").forEach { digit ->
                composeTestRule.onNodeWithTag("pin_key_$digit").performClick()
            }
            composeTestRule.onNodeWithText("Re-enter the new PIN to confirm.").assertIsDisplayed()
            listOf("1", "2", "3", "4").forEach { digit ->
                composeTestRule.onNodeWithTag("pin_key_$digit").performClick()
            }
            composeTestRule.onNodeWithText("Encrypted Vault Unlocked").assertIsDisplayed()
            composeTestRule.onNodeWithText("1 minute").performClick()
            composeTestRule.onNodeWithText("5 minutes").assertIsDisplayed()
            composeTestRule.onNodeWithTag("change_pin_button").performClick()
            composeTestRule.onNodeWithText("Current PIN").assertIsDisplayed()
            composeTestRule.onNodeWithText("Cancel").performClick()
            composeTestRule.onNodeWithText("Lock Vault").performClick()
            composeTestRule.onNodeWithTag("pin_key_1").assertIsDisplayed()
        } else {
            composeTestRule.onNodeWithTag("pin_key_1").assertIsDisplayed()
        }
    }

    @Test
    fun testAiDuplicateCleanerControlsAndSectionStates() {
        composeTestRule.onNodeWithTag("nav_tab_3").performClick()
        composeTestRule.onNodeWithText("Dupe Cleaner").assertIsDisplayed()

        composeTestRule.onNodeWithTag("auto_clean_duplicates_switch").assertIsOff()
        composeTestRule.onNodeWithTag("auto_clean_duplicates_switch").performClick()
        composeTestRule.onNodeWithTag("auto_clean_duplicates_switch").assertIsOn()
        composeTestRule.onNodeWithTag("auto_clean_duplicates_switch").performClick()
        composeTestRule.onNodeWithTag("auto_clean_duplicates_switch").assertIsOff()

        composeTestRule.onNodeWithTag("similarity_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("start_scan_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("section_tab_1").performClick()
        composeTestRule.onNodeWithText("ML Kit OCR Text Recognition Engine").assertIsDisplayed()
        composeTestRule.onNodeWithText("OCR Indexed Documents (0)").assertIsDisplayed()

        composeTestRule.onNodeWithTag("section_tab_2").performClick()
        composeTestRule.onNodeWithTag("semantic_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("semantic_search_input").assertIsEnabled()
    }

    @Test
    fun testAiCloudPluginAndAboutScreensAreReachable() {
        // The duplicate screen exposes three real sections.
        composeTestRule.onNodeWithTag("nav_tab_3").performClick()
        composeTestRule.onNodeWithTag("section_tab_0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("section_tab_1").performClick()
        composeTestRule.onNodeWithText("OCR Engine").assertIsDisplayed()
        composeTestRule.onNodeWithTag("section_tab_2").performClick()
        composeTestRule.onNodeWithText("AI Search").assertIsDisplayed()

        // The cloud screen exposes cloud-sync and plugin-manager sections.
        composeTestRule.onNodeWithTag("nav_tab_4").performClick()
        composeTestRule.onNodeWithText("Cloud Providers").assertIsDisplayed()
        composeTestRule.onNodeWithTag("section_tab_1").performClick()
        composeTestRule.onNodeWithText("Plugin Manager").assertIsDisplayed()

        // About is reachable from the top-bar action and exposes a tagged root.
        composeTestRule.onNodeWithTag("about_menu_item").performClick()
        composeTestRule.onNodeWithTag("about_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("about_back_button").performClick()
        composeTestRule.onNodeWithTag("dashboard_health_card").assertIsDisplayed()
    }
}

