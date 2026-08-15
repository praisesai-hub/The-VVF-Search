package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.OcrTextBlock
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrOverlayImageInstrumentedTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun missingImage_showsUnavailablePreview() {
        composeTestRule.setContent {
            MaterialTheme {
                OcrOverlayImage(
                    filePath = "/does/not/exist/ocr-preview.png",
                    ocrBlocks = emptyList()
                )
            }
        }

        composeTestRule.onNodeWithText("Image Preview Unavailable").assertIsDisplayed()
    }

    @Test
    fun readableImage_rendersPreviewAndOverlayBlocks() {
        val imageFile = File.createTempFile("ocr_overlay_", ".png")
        try {
            Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.WHITE)
                compress(Bitmap.CompressFormat.PNG, 100, imageFile.outputStream())
                recycle()
            }

            composeTestRule.setContent {
                MaterialTheme {
                    OcrOverlayImage(
                        filePath = imageFile.absolutePath,
                        ocrBlocks = listOf(
                            OcrTextBlock(
                                text = "visible block",
                                boundingBox = Rect(5, 5, 35, 20),
                                imageWidth = 80,
                                imageHeight = 40
                            ),
                            OcrTextBlock(
                                text = "fallback dimensions",
                                boundingBox = Rect(10, 10, 30, 25),
                                imageWidth = 0,
                                imageHeight = 0
                            )
                        )
                    )
                }
            }

            composeTestRule.onNodeWithContentDescription("OCR Image Preview").assertIsDisplayed()
        } finally {
            imageFile.delete()
        }
    }
}

