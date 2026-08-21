package com.example.storage

import com.example.data.FileCategory
import com.example.data.FileItemEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDuplicateEvidenceTest {
    private data class VideoDetails(
        val durationMs: Long = 10_000L,
        val width: Int = 1920,
        val height: Int = 1080,
        val chunkHash: String? = null
    )

    private fun video(
        id: Long,
        samples: String,
        details: VideoDetails = VideoDetails()
    ) = FileItemEntity(
        id = id,
        name = "video-$id.mp4",
        path = "/videos/video-$id.mp4",
        category = FileCategory.VIDEO.name,
        sizeBytes = 1L,
        videoFingerprintVersion = 2,
        videoSampleHashes = samples,
        videoDurationMs = details.durationMs,
        videoWidth = details.width,
        videoHeight = details.height,
        videoAudioSignature = "yes|video/mp4|1000000",
        videoChunkHash = details.chunkHash ?: "chunk-$id"
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
            VideoDetails(durationMs = 20_000L, width = 1280, height = 720)
        )

        assertFalse(VideoDuplicateEvidence.compare(first, differentMedia, threshold = 95).matches)
    }

    @Test
    fun equalChunkHash_isStrongEvidenceEvenWhenSamplesAreUnavailable() {
        val chunkDetails = VideoDetails(chunkHash = "same-chunk")
        val first = video(1L, "", chunkDetails)
        val second = video(2L, "", chunkDetails)

        assertTrue(VideoDuplicateEvidence.compare(first, second, threshold = 95).matches)
    }
}
