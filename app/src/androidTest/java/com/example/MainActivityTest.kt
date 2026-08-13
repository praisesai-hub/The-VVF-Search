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
        // 1. Verify Dashboard is displayed by default
        composeTestRule.onNodeWithTag("dashboard_health_card").assertIsDisplayed()

        // 2. Click on "Vault" tab and verify Vault screen appears
        composeTestRule.onNodeWithTag("nav_tab_2").performClick()
        composeTestRule.onNodeWithTag("pin_key_1").assertIsDisplayed()

        // 3. Click on "Files" tab and verify File Manager screen appears
        composeTestRule.onNodeWithTag("nav_tab_1").performClick()
        composeTestRule.onNodeWithTag("file_search_input").assertIsDisplayed()
    }
}

