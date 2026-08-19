package com.example.storage

import com.example.data.FileItemEntity
import kotlin.math.abs
import kotlin.math.max

object VideoDuplicateEvidence {
    private const val MIN_TEMPORAL_SAMPLES = 3

    fun sampleHashes(file: FileItemEntity): List<String> {
        val stored = file.videoSampleHashes
            .split(';')
            .map(String::trim)
            .filter { it.length == 16 }
        return if (stored.size >= MIN_TEMPORAL_SAMPLES) stored else emptyList()
    }

    fun bucketKeys(file: FileItemEntity): Set<String> = sampleHashes(file)
        .map { hash -> "video_${hash.substring(0, 4)}" }
        .toSet()

    fun compare(first: FileItemEntity, second: FileItemEntity, threshold: Int): Comparison {
        val firstSamples = sampleHashes(first)
        val secondSamples = sampleHashes(second)
        val metadataMatch = durationCompatible(first, second) &&
            resolutionCompatible(first, second) &&
            audioCompatible(first, second)
        val cryptographicMatch = first.md5Hash.isNotBlank() && first.md5Hash == second.md5Hash
        if (cryptographicMatch) return Comparison(true, 100)
        val chunkMatch = first.videoChunkHash.isNotBlank() &&
            first.videoChunkHash == second.videoChunkHash
        if (chunkMatch && metadataMatch) return Comparison(true, 100)
        if (firstSamples.size < MIN_TEMPORAL_SAMPLES || secondSamples.size < MIN_TEMPORAL_SAMPLES) {
            return Comparison(false, 0)
        }

        val maxDistance = ((100 - threshold) * 64) / 100
        val compared = firstSamples.zip(secondSamples).take(MIN_TEMPORAL_SAMPLES)
        val distances = compared.map { (left, right) -> hammingDistance(left, right) }
        val matchingSamples = distances.count { it <= maxDistance }
        val averageDistance = distances.average()
        val temporalMatch = matchingSamples == MIN_TEMPORAL_SAMPLES && averageDistance <= maxDistance
        val match = temporalMatch && metadataMatch
        val score = if (chunkMatch) {
            100
        } else {
            ((64.0 - averageDistance.coerceIn(0.0, 64.0)) * 100.0 / 64.0).toInt()
        }
        return Comparison(match, score)
    }

    data class Comparison(val matches: Boolean, val score: Int)

    private fun durationCompatible(first: FileItemEntity, second: FileItemEntity): Boolean {
        if (first.videoDurationMs <= 0L || second.videoDurationMs <= 0L) return false
        val difference = abs(first.videoDurationMs - second.videoDurationMs)
        val tolerance = max(1_000L, max(first.videoDurationMs, second.videoDurationMs) / 20L)
        return difference <= tolerance
    }

    private fun audioCompatible(first: FileItemEntity, second: FileItemEntity): Boolean {
        return first.videoAudioSignature.isBlank() ||
            second.videoAudioSignature.isBlank() ||
            first.videoAudioSignature == second.videoAudioSignature
    }

    private fun resolutionCompatible(first: FileItemEntity, second: FileItemEntity): Boolean {
        if (first.videoWidth <= 0 || first.videoHeight <= 0 || second.videoWidth <= 0 || second.videoHeight <= 0) return false
        val firstRatio = first.videoWidth.toDouble() / first.videoHeight.toDouble()
        val secondRatio = second.videoWidth.toDouble() / second.videoHeight.toDouble()
        return abs(firstRatio - secondRatio) <= 0.02 &&
            abs(first.videoWidth - second.videoWidth).toDouble() <= max(first.videoWidth, second.videoWidth).toDouble() * 0.25 &&
            abs(first.videoHeight - second.videoHeight).toDouble() <= max(first.videoHeight, second.videoHeight).toDouble() * 0.25
    }

    private fun hammingDistance(first: String, second: String): Int {
        if (first.length != 16 || second.length != 16) return 64
        return first.zip(second).sum { (left, right) ->
            (left.digitToInt(16) xor right.digitToInt(16)).countOneBits()
        }
    }
}
