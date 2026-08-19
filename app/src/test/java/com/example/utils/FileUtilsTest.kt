package com.example.utils

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FileUtilsTest {
    private var originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `non-positive sizes return unknown label`() {
        assertEquals("अज्ञात साइज़", formatFileSize(0, "अज्ञात साइज़"))
        assertEquals("अज्ञात साइज़", formatFileSize(-1, "अज्ञात साइज़"))
    }

    @Test
    fun `sizes use expected binary units and precision`() {
        assertEquals("1.00 B", formatFileSize(1, "अज्ञात साइज़"))
        assertEquals("1023.00 B", formatFileSize(1023, "अज्ञात साइज़"))
        assertEquals("1.00 KB", formatFileSize(1024, "अज्ञात साइज़"))
        assertEquals("1.00 MB", formatFileSize(1024 * 1024, "अज्ञात साइज़"))
        assertEquals("1.00 GB", formatFileSize(1024L * 1024L * 1024L, "अज्ञात साइज़"))
    }

    @Test
    fun `values beyond supported units remain in gigabytes`() {
        assertEquals("2.00 GB", formatFileSize(2L * 1024L * 1024L * 1024L, "अज्ञात साइज़"))
    }

    @Test
    fun `date uses day month year format`() {
        assertEquals("01 Jan 1970", formatDate(0L))
    }
}
