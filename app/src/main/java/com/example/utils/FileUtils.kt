package com.example.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatFileSize(size: Long): String {
    if (size <= 0L) return "अज्ञात साइज़"
    val units = arrayOf("B", "KB", "MB", "GB")
    var s = size.toDouble()
    var i = 0
    while (s >= 1024 && i < units.size - 1) {
        s /= 1024
        i++
    }
    return "%.2f %s".format(s, units[i])
}

fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
