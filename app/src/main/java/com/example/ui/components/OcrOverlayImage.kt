package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.OcrTextBlock
import java.io.File

@Composable
fun OcrOverlayImage(
    filePath: String,
    ocrBlocks: List<OcrTextBlock>,
    modifier: Modifier = Modifier
) {
    var selectedText by remember { mutableStateOf<String?>(null) }
    val bitmap = remember(filePath) {
        try {
            val file = File(filePath)
            if (file.exists() && file.canRead()) {
                BitmapFactory.decodeFile(filePath)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (bitmap != null) {
            val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
            val naturalWidth = bitmap.width.toFloat()
            val naturalHeight = bitmap.height.toFloat()

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                val maxContainerWidth = constraints.maxWidth.toFloat()
                // ContentScale.Fit scale calculation
                val scale = if (naturalWidth > 0 && naturalHeight > 0) {
                    maxContainerWidth / naturalWidth
                } else 1f
                val displayHeight = (naturalHeight * scale).dp

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(displayHeight)
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "OCR Image Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                // Default click on image resets selection
                                selectedText = null
                            }
                    ) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height

                        ocrBlocks.forEach { block ->
                            val imgW = if (block.imageWidth > 0) block.imageWidth.toFloat() else naturalWidth
                            val imgH = if (block.imageHeight > 0) block.imageHeight.toFloat() else naturalHeight

                            if (imgW > 0 && imgH > 0) {
                                val scaleX = canvasWidth / imgW
                                val scaleY = canvasHeight / imgH

                                val left = block.boundingBox.left * scaleX
                                val top = block.boundingBox.top * scaleY
                                val width = block.boundingBox.width() * scaleX
                                val height = block.boundingBox.height() * scaleY

                                // Draw bounding box overlay
                                drawRoundRect(
                                    color = Color(0xFFFFD54F), // Gold / Amber
                                    topLeft = Offset(left, top),
                                    size = Size(width, height),
                                    cornerRadius = CornerRadius(4f, 4f),
                                    style = Stroke(width = 3.dp.toPx())
                                )

                                drawRect(
                                    color = Color(0x33FFD54F),
                                    topLeft = Offset(left, top),
                                    size = Size(width, height)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color.DarkGray, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Image Preview Unavailable",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }

        if (selectedText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedText = null }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Selected Block Text:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedText!!,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}
