package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect,
    val imageWidth: Int,
    val imageHeight: Int
)

interface OcrEngine {
    suspend fun extractRealOcrText(filePath: String): String
    suspend fun extractOcrBlocks(filePath: String): List<OcrTextBlock>
}

class MLKitOcrEngine(private val context: Context) : OcrEngine {
    private val recognizers by lazy {
        listOf<TextRecognizer>(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
        )
    }

    override suspend fun extractOcrBlocks(filePath: String): List<OcrTextBlock> = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) return@withContext emptyList()

        val pdfPages = if (file.extension.equals("pdf", ignoreCase = true)) renderPdfPages(file) else emptyList()
        if (file.extension.equals("pdf", ignoreCase = true)) {
            if (pdfPages.isEmpty()) return@withContext emptyList()
            return@withContext try {
                pdfPages.flatMap { bitmap ->
                    try {
                        val image = InputImage.fromBitmap(bitmap, 0)
                        recognize(image, filePath).flatMap { visionText ->
                            visionText.textBlocks.mapNotNull { block ->
                                block.boundingBox?.let { box ->
                                    OcrTextBlock(
                                        text = block.text,
                                        boundingBox = box,
                                        imageWidth = image.width,
                                        imageHeight = image.height,
                                    )
                                }
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PDF OCR failed for file=${File(filePath).name}: ${e::class.simpleName}")
                emptyList()
            }
        }

        val image = try {
            InputImage.fromFilePath(context, android.net.Uri.fromFile(file))
        } catch (e: Exception) {
            Log.e(TAG, "Image loading for OCR failed for file=${File(filePath).name}: ${e::class.simpleName}")
            return@withContext emptyList()
        }
        recognize(image, filePath).flatMap { visionText ->
            visionText.textBlocks.mapNotNull { block ->
                block.boundingBox?.let { box ->
                    OcrTextBlock(
                        text = block.text,
                        boundingBox = box,
                        imageWidth = image.width,
                        imageHeight = image.height,
                    )
                }
            }
        }
    }

    override suspend fun extractRealOcrText(filePath: String): String = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) return@withContext ""

        val pdfPages = if (file.extension.equals("pdf", ignoreCase = true)) renderPdfPages(file) else emptyList()
        if (file.extension.equals("pdf", ignoreCase = true)) {
            if (pdfPages.isEmpty()) return@withContext ""
            return@withContext try {
                pdfPages.map { bitmap ->
                    try {
                        recognizedText(InputImage.fromBitmap(bitmap, 0), filePath)
                    } finally {
                        bitmap.recycle()
                    }
                }.filter(String::isNotBlank).joinToString("\n\n")
            } catch (e: Exception) {
                Log.e(TAG, "PDF OCR failed for file=${File(filePath).name}: ${e::class.simpleName}")
                ""
            }
        }

        val image = try {
            InputImage.fromFilePath(context, android.net.Uri.fromFile(file))
        } catch (e: Exception) {
            Log.e(TAG, "Image loading for OCR failed for file=${File(filePath).name}: ${e::class.simpleName}")
            return@withContext ""
        }
        recognizedText(image, filePath)
    }

    private fun renderPdfPages(file: File): List<Bitmap> {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use(::renderPages)
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF page rendering for OCR failed for file=${file.name}: ${e::class.simpleName}")
            emptyList()
        }
    }

    private fun renderPages(renderer: PdfRenderer): List<Bitmap> = buildList {
        for (pageIndex in 0 until renderer.pageCount) {
            add(renderPage(renderer, pageIndex))
        }
    }

    private fun renderPage(renderer: PdfRenderer, pageIndex: Int): Bitmap =
        renderer.openPage(pageIndex).use { page ->
            Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }

    private suspend fun recognize(image: InputImage, filePath: String): List<Text> =
        recognizers.mapNotNull { recognizer ->
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (continuation.isActive) continuation.resume(visionText)
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "ML Kit OCR failed for file=${File(filePath).name}: ${error::class.simpleName}")
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        }

    private suspend fun recognizedText(image: InputImage, filePath: String): String =
        recognize(image, filePath)
            .asSequence()
            .map { it.text.trim() }
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("\n")

    private companion object {
        const val TAG = "MLKitOcrEngine"
    }
}
