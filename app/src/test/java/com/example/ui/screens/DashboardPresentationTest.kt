package com.example.ui.screens

import com.example.data.CategoryStat
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardPresentationTest {
    @Test
    fun storageSummary_usesRealNonNegativeCategoryStatistics(): Unit {
        val summary = dashboardStorageSummary(
            listOf(
                CategoryStat("IMAGES", count = 2, totalSize = 2_048L),
                CategoryStat("DOCUMENTS", count = 1, totalSize = 1_024L),
                CategoryStat("INVALID", count = -7, totalSize = -1L)
            )
        )

        assertEquals("Indexed storage: 3.0 KB", summary.indexedStorageLabel)
        assertEquals(3, summary.fileCount)
        assertEquals(3, summary.categoryCount)
        assertEquals("3 files across 3 categories", summary.detailLabel)
    }

    @Test
    fun storageSummary_emptyInputDoesNotInventCapacityOrHealthMetrics(): Unit {
        val summary = dashboardStorageSummary(emptyList())

        assertEquals("Indexed storage: अज्ञात साइज़", summary.indexedStorageLabel)
        assertEquals(0, summary.fileCount)
        assertEquals(0, summary.categoryCount)
        assertEquals("0 files across 0 categories", summary.detailLabel)
    }
}
