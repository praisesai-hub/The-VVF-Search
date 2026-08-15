package com.example.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.FileItemEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileManagerItemRowInstrumentedTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun menuActions_dispatchCallbacks_andTagDialogRejectsBlankInput() {
        var renameCalls = 0
        var encryptCalls = 0
        var deleteCalls = 0
        var ocrCalls = 0
        val tags = mutableListOf<String>()
        val file = FileItemEntity(
            name = "report.pdf",
            path = "/tmp/report.pdf",
            category = "DOCUMENTS",
            sizeBytes = 1024,
            tags = "urgent",
            ocrText = "invoice text"
        )

        composeTestRule.setContent {
            MaterialTheme {
                FileManagerItemRow(
                    file = file,
                    onRename = { renameCalls++ },
                    onEncrypt = { encryptCalls++ },
                    onDelete = { deleteCalls++ },
                    onAddTag = { tags += it },
                    onOcrOverlay = { ocrCalls++ }
                )
            }
        }

        composeTestRule.onNodeWithText("report.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tags: urgent").assertIsDisplayed()
        composeTestRule.onNodeWithText("OCR Scanned").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Rename File").performClick()
        assertEquals(1, renameCalls)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Encrypt to Vault").performClick()
        assertEquals(1, encryptCalls)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("OCR Overlay Preview").performClick()
        assertEquals(1, ocrCalls)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Add Tag").performClick()
        composeTestRule.onNodeWithText("Add").performClick()
        assertEquals(emptyList<String>(), tags)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Add Tag").performClick()
        composeTestRule.onNodeWithTag("file_tag_input").performTextInput("finance")
        composeTestRule.onNodeWithText("Add").performClick()
        assertEquals(listOf("finance"), tags)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Move to Trash").performClick()
        assertEquals(1, deleteCalls)
    }
}
