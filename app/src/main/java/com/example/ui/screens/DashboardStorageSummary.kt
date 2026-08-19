package com.example.ui.screens

import com.example.data.CategoryStat

internal data class DashboardStorageSummary(
    val indexedStorageLabel: String,
    val fileCount: Int,
    val categoryCount: Int
) {
    val detailLabel: String get() = "$fileCount files across $categoryCount categories"
}

internal fun dashboardStorageSummary(categoryStats: List<CategoryStat>): DashboardStorageSummary {
    val indexedBytes = categoryStats.sumOf { it.totalSize.coerceAtLeast(0L) }
    val fileCount = categoryStats.sumOf { it.count.coerceAtLeast(0) }
    return DashboardStorageSummary(
        indexedStorageLabel = "Indexed storage: ${formatFileSize(indexedBytes)}",
        fileCount = fileCount,
        categoryCount = categoryStats.size
    )
}
