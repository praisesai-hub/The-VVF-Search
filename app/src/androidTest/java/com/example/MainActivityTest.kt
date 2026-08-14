package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

