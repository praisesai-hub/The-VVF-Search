package com.example.storage

import com.example.data.FileItemEntity
import kotlin.math.abs
import kotlin.math.max

object VideoDuplicateEvidence {
    private const val MIN_TEMPORAL_SAMPLES = 3
    private const val HASH_BUCKET_PREFIX_LENGTH = 4
    private const val DHASH_HEX_LENGTH = 16
    private const val DHASH_BIT_LENGTH = 64
    private const val NO_MATCH_SCORE = 0
    private const val MAX_MATCH_SCORE = 100
    private const val MIN_DURATION_TOLERANCE_MS = 1_000L
    private const val DURATION_TOLERANCE_DIVISOR = 20L
    private const val ASPECT_RATIO_TOLERANCE = 0.02
    private const val DIMENSION_TOLERANCE_RATIO = 0.25

    fun sampleHashes(file: FileItemEntity): List<String> {
        val stored = file.videoSampleHashes
            .split(';')
            .map(String::trim)
            .filter { it.length == DHASH_HEX_LENGTH }
        return if (stored.size >= MIN_TEMPORAL_SAMPLES) stored else emptyList()
    }

    fun bucketKeys(file: FileItemEntity): Set<String> = sampleHashes(file)
        .map { hash -> "video_${hash.substring(0, HASH_BUCKET_PREFIX_LENGTH)}" }
        .toSet()

    fun compare(first: FileItemEntity, second: FileItemEntity, threshold: Int): Comparison {
        val firstSamples = sampleHashes(first)
        val secondSamples = sampleHashes(second)
        val metadataMatch = durationCompatible(first, second) &&
            resolutionCompatible(first, second) &&
            audioCompatible(first, second)
        val cryptographicMatch = first.md5Hash.isNotBlank() && first.md5Hash == second.md5Hash
        val chunkMatch = first.videoChunkHash.isNotBlank() &&
            first.videoChunkHash == second.videoChunkHash
        val hasEnoughTemporalEvidence = firstSamples.size >= MIN_TEMPORAL_SAMPLES &&
            secondSamples.size >= MIN_TEMPORAL_SAMPLES
        val comparison = when {
            cryptographicMatch || (chunkMatch && metadataMatch) -> Comparison(true, MAX_MATCH_SCORE)
            !hasEnoughTemporalEvidence -> Comparison(false, NO_MATCH_SCORE)
            else -> temporalComparison(firstSamples, secondSamples, threshold, metadataMatch, chunkMatch)
        }
        return comparison
    }

    private fun temporalComparison(
        firstSamples: List<String>,
        secondSamples: List<String>,
        threshold: Int,
        metadataMatch: Boolean,
        chunkMatch: Boolean
    ): Comparison {
        val maxDistance = ((MAX_MATCH_SCORE - threshold) * DHASH_BIT_LENGTH) / MAX_MATCH_SCORE
        val compared = firstSamples.zip(secondSamples).take(MIN_TEMPORAL_SAMPLES)
        val distances = compared.map { (left, right) -> hammingDistance(left, right) }
        val matchingSamples = distances.count { it <= maxDistance }
        val averageDistance = distances.average()
        val temporalMatch = matchingSamples == MIN_TEMPORAL_SAMPLES && averageDistance <= maxDistance
        val score = if (chunkMatch) {
            MAX_MATCH_SCORE
        } else {
            ((DHASH_BIT_LENGTH.toDouble() - averageDistance.coerceIn(0.0, DHASH_BIT_LENGTH.toDouble())) *
                MAX_MATCH_SCORE.toDouble() / DHASH_BIT_LENGTH).toInt()
        }
        return Comparison(temporalMatch && metadataMatch, score)
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
        return first.videoAudioSignature.isBlank() ||
            second.videoAudioSignature.isBlank() ||
            first.videoAudioSignature == second.videoAudioSignature
    }

    private fun resolutionCompatible(first: FileItemEntity, second: FileItemEntity): Boolean {
        if (!hasPositiveVideoDimensions(first) || !hasPositiveVideoDimensions(second)) return false
        val firstRatio = first.videoWidth.toDouble() / first.videoHeight.toDouble()
        val secondRatio = second.videoWidth.toDouble() / second.videoHeight.toDouble()
        val aspectRatioCompatible = abs(firstRatio - secondRatio) <= ASPECT_RATIO_TOLERANCE
        val widthCompatible = abs(first.videoWidth - second.videoWidth).toDouble() <=
            max(first.videoWidth, second.videoWidth).toDouble() * DIMENSION_TOLERANCE_RATIO
        val heightCompatible = abs(first.videoHeight - second.videoHeight).toDouble() <=
            max(first.videoHeight, second.videoHeight).toDouble() * DIMENSION_TOLERANCE_RATIO
        return aspectRatioCompatible && widthCompatible && heightCompatible
    }

    private fun hasPositiveVideoDimensions(file: FileItemEntity): Boolean =
        file.videoWidth > 0 && file.videoHeight > 0

    private fun hammingDistance(first: String, second: String): Int {
        if (first.length != DHASH_HEX_LENGTH || second.length != DHASH_HEX_LENGTH) return DHASH_BIT_LENGTH
        return first.zip(second).sumOf { (left, right) ->
            (left.digitToInt(DHASH_HEX_LENGTH) xor right.digitToInt(DHASH_HEX_LENGTH)).countOneBits()
        }
    }
}
