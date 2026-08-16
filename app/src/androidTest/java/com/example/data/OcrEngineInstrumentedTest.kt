package com.example.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrEngineInstrumentedTest {
    private lateinit var testRoot: File
    private lateinit var engine: MLKitOcrEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        engine = MLKitOcrEngine(context)
        testRoot = File(context.cacheDir, "ocr-instrumented-${System.nanoTime()}")
        assertTrue(testRoot.mkdirs())
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun missingImage_failsClosedForTextAndBlocks() = runBlocking {
        val missing = File(testRoot, "missing.jpg")

        assertEquals("", engine.extractRealOcrText(missing.absolutePath))
        assertTrue(engine.extractOcrBlocks(missing.absolutePath).isEmpty())
    }

    @Test
    fun malformedImage_failsClosedForTextAndBlocks() = runBlocking {
        val malformed = File(testRoot, "malformed.png").apply {
            writeText("not an image")
        }

        assertEquals("", engine.extractRealOcrText(malformed.absolutePath))
        assertTrue(engine.extractOcrBlocks(malformed.absolutePath).isEmpty())
    }

    @Test
    fun malformedPdf_failsClosedAfterRendererError() = runBlocking {
        val malformed = File(testRoot, "malformed.pdf").apply {
            writeText("not a PDF")
        }

        assertEquals("", engine.extractRealOcrText(malformed.absolutePath))
        assertTrue(engine.extractOcrBlocks(malformed.absolutePath).isEmpty())
    }
}
