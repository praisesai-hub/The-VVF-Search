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
    fun serializedSamples(): String =
        sampleHashes.filter { it.length == SAMPLE_HASH_HEX_LENGTH }.joinToString(";")

    companion object {
        const val CURRENT_VERSION = 2
        private const val SAMPLE_HASH_HEX_LENGTH = 16
    }
}
