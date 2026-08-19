package com.example.storage

data class VideoFingerprint(
    val version: Int = CURRENT_VERSION,
    val sampleHashes: List<String>,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val audioSignature: String,
    val chunkHash: String
) {
    fun serializedSamples(): String = sampleHashes.filter { it.length == 16 }.joinToString(";")

    companion object {
        const val CURRENT_VERSION = 2
    }
}
