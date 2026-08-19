package com.example.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTokenizerTest {
    @Test
    fun `Devanagari text is preserved as searchable tokens`() {
        assertEquals(listOf("सुरक्षित", "वॉल्ट"), SearchTextTokenizer.tokenize("सुरक्षित वॉल्ट"))
    }

    @Test
    fun `mixed script punctuation and compatibility forms normalize consistently`() {
        assertEquals(
            "invoice 2026 भुगतान",
            SearchTextTokenizer.normalize("Invoice—２０２６ भुगतान")
        )
    }

    @Test
    fun `contains query matches Hindi text independent of punctuation and case`() {
        assertTrue(SearchTextTokenizer.containsQuery("सुरक्षित-वॉल्ट फ़ाइल", "सुरक्षित वॉल्ट"))
        assertTrue(SearchTextTokenizer.containsQuery("Invoice भुगतान", "invoice भुगतान"))
    }

    @Test
    fun `empty or punctuation-only input produces no tokens`() {
        assertTrue(SearchTextTokenizer.tokenize("— … !!!").isEmpty())
        assertEquals("", SearchTextTokenizer.normalize("   "))
    }
}
