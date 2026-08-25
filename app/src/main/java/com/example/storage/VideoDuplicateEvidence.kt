package com.example.storage

import com.example.data.FileItemEntity
import kotlin.math.abs
import kotlin.math.max

object VideoDuplicateEvidence {
    private const val MIN_TEMPORAL_SAMPLES = 3
    private const val MAX_TEMPORAL_SAMPLES = 12
    private const val BUCKET_PREFIX_LENGTH = 8
    private const val HASH_HEX_LENGTH = 16
    private const val HEX_RADIX = 16
    private const val MAX_SCORE = 100
    private const val HASH_BIT_LENGTH = 64
    private const val MIN_DURATION_TOLERANCE_MS = 1_000L
    private const val DURATION_TOLERANCE_DIVISOR = 20L
    private const val RESOLUTION_RATIO_TOLERANCE = 0.02
    private const val RESOLUTION_DIMENSION_TOLERANCE = 0.25

    fun sampleHashes(file: FileItemEntity): List<String> {
        val stored = file.videoSampleHashes.split(';').map(String::trim).filter { it.length == HASH_HEX_LENGTH }
        return if (stored.size >= MIN_TEMPORAL_SAMPLES) stored else emptyList()
    }

    fun bucketKeys(file: FileItemEntity): Set<String> =
        sampleHashes(file)
            .map { hash -> "video_${hash.substring(0, BUCKET_PREFIX_LENGTH)}" }
            .toSet()

    fun compare(first: FileItemEntity, second: FileItemEntity, threshold: Int): Comparison {
        val firstSamples = sampleHashes(first)
        val secondSamples = sampleHashes(second)
        val metadataMatch =
            durationCompatible(first, second) &&
                resolutionCompatible(first, second) &&
                audioCompatible(first, second)
        val cryptographicMatch =
            first.sizeBytes > 0L &&
                first.sizeBytes == second.sizeBytes &&
                first.md5Hash.isNotBlank() &&
                first.md5Hash == second.md5Hash
        val chunkMatch =
            first.videoChunkHash.isNotBlank() && first.videoChunkHash == second.videoChunkHash

        return when {
            cryptographicMatch -> Comparison(true, MAX_SCORE)
            chunkMatch && metadataMatch -> Comparison(true, MAX_SCORE)
            firstSamples.size < MIN_TEMPORAL_SAMPLES || secondSamples.size < MIN_TEMPORAL_SAMPLES ->
                Comparison(false, 0)
            else -> compareTemporal(firstSamples, secondSamples, threshold, chunkMatch, metadataMatch)
        }
    }

    private fun compareTemporal(
        firstSamples: List<String>,
        secondSamples: List<String>,
        threshold: Int,
        chunkMatch: Boolean,
        metadataMatch: Boolean
    ): Comparison {
        val normalizedThreshold = threshold.coerceIn(0, MAX_SCORE)
        val maxDistance = ((MAX_SCORE - normalizedThreshold) * HASH_BIT_LENGTH) / MAX_SCORE
        val compared = firstSamples.zip(secondSamples).take(MAX_TEMPORAL_SAMPLES)
        val distances = compared.map { pair -> hammingDistance(pair.first, pair.second) }
        val matchingSamples = distances.count { it <= maxDistance }
        val averageDistance = distances.average()
        val temporalMatch =
            compared.size >= MIN_TEMPORAL_SAMPLES &&
                matchingSamples == compared.size &&
                averageDistance <= maxDistance
        val match = temporalMatch && metadataMatch
        val score =
            if (chunkMatch) {
                MAX_SCORE
            } else {
                (
                    (HASH_BIT_LENGTH - averageDistance.coerceIn(0.0, HASH_BIT_LENGTH.toDouble())) *
                        MAX_SCORE / HASH_BIT_LENGTH
                )
                    .toInt()
            }
        return Comparison(match, score)
    }

    data class Comparison(val matches: Boolean, val score: Int)

    private fun durationCompatible(first: FileItemEntity, second: FileItemEntity): Boolean {
        if (first.videoDurationMs <= 0L || second.videoDurationMs <= 0L) return false
        val difference = abs(first.videoDurationMs - second.videoDurationMs)
        val tolerance = max(
            MIN_DURATION_TOLERANCE_MS,
            max(first.videoDurationMs, second.videoDurationMs) / DURATION_TOLERANCE_DIVISOR
        )
        return difference <= tolerance
    }

    private fun audioCompatible(first: FileItemEntity, second: FileItemEntity): Boolean {
        if (first.videoAudioSignature.isBlank() || second.videoAudioSignature.isBlank())
            return false
        return first.videoAudioSignature == second.videoAudioSignature
    }

    @Suppress("detekt.ComplexCondition")
    private fun resolutionCompatible(first: FileItemEntity, second: FileItemEntity): Boolean {
        if (
            first.videoWidth <= 0 ||
                first.videoHeight <= 0 ||
                second.videoWidth <= 0 ||
                second.videoHeight <= 0
        )
            return false
        val firstRatio = first.videoWidth.toDouble() / first.videoHeight.toDouble()
        val secondRatio = second.videoWidth.toDouble() / second.videoHeight.toDouble()
        return abs(firstRatio - secondRatio) <= RESOLUTION_RATIO_TOLERANCE &&
            abs(first.videoWidth - second.videoWidth).toDouble() <=
                max(first.videoWidth, second.videoWidth).toDouble() * RESOLUTION_DIMENSION_TOLERANCE &&
            abs(first.videoHeight - second.videoHeight).toDouble() <=
                max(first.videoHeight, second.videoHeight).toDouble() * RESOLUTION_DIMENSION_TOLERANCE
    }

    private fun hammingDistance(first: String, second: String): Int {
        if (first.length != HASH_HEX_LENGTH || second.length != HASH_HEX_LENGTH) return HASH_BIT_LENGTH
        return first.zip(second).sumOf { pair ->
            val left = pair.first.digitToInt(HEX_RADIX)
            val right = pair.second.digitToInt(HEX_RADIX)
            (left xor right).countOneBits()
        }
    }
}
