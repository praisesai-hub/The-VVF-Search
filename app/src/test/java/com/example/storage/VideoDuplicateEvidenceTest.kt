package com.example.storage

import com.example.data.FileCategory
import com.example.data.FileItemEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDuplicateEvidenceTest {
    private fun video(
        id: Long,
        samples: String,
        durationMs: Long = 10_000L,
        width: Int = 1920,
        height: Int = 1080,
        chunkHash: String = "chunk-$id"
    ) = FileItemEntity(
        id = id,
        name = "video-$id.mp4",
        path = "/videos/video-$id.mp4",
        category = FileCategory.VIDEO.name,
        sizeBytes = 1L,
        videoFingerprintVersion = 2,
        videoSampleHashes = samples,
        videoDurationMs = durationMs,
        videoWidth = width,
        videoHeight = height,
        videoAudioSignature = "yes|video/mp4|1000000",
        videoChunkHash = chunkHash
    )

    @Test
    fun sameSamplesAndCompatibleMetadata_match() {
        val first = video(1L, "0000000000000000;1111111111111111;2222222222222222;3333333333333333")
        val second = video(2L, first.videoSampleHashes)

        val result = VideoDuplicateEvidence.compare(first, second, threshold = 95)

        assertTrue(result.matches)
        assertTrue(result.score >= 95)
    }

    @Test
    fun sameFirstSampleButDifferentLaterSamples_doNotMatch() {
        val first = video(1L, "0000000000000000;1111111111111111;2222222222222222;3333333333333333")
        val second = video(2L, "0000000000000000;aaaaaaaaaaaaaaaa;bbbbbbbbbbbbbbbb;cccccccccccccccc")

        assertFalse(VideoDuplicateEvidence.compare(first, second, threshold = 70).matches)
    }

    @Test
    fun durationOrResolutionMismatch_doesNotMatchWithoutChunkEquality() {
        val first = video(1L, "0000000000000000;1111111111111111;2222222222222222;3333333333333333")
        val differentMedia = video(
            2L,
            first.videoSampleHashes,
            durationMs = 20_000L,
            width = 1280,
            height = 720
        )

        assertFalse(VideoDuplicateEvidence.compare(first, differentMedia, threshold = 95).matches)
    }

    @Test
    fun equalChunkHash_isStrongEvidenceEvenWhenSamplesAreUnavailable() {
        val first = video(1L, "", chunkHash = "same-chunk")
        val second = video(2L, "", chunkHash = "same-chunk")

        assertTrue(VideoDuplicateEvidence.compare(first, second, threshold = 95).matches)
    }
}
