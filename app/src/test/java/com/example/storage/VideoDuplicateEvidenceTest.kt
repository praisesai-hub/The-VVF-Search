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
        chunkHash: String = "chunk-$id",
        sizeBytes: Long = 1L,
        md5Hash: String = "",
        audioSignature: String = "yes|video/mp4|1000000"
    ) = FileItemEntity(
        id = id,
        name = "video-$id.mp4",
        path = "/videos/video-$id.mp4",
        category = FileCategory.VIDEO.name,
        sizeBytes = sizeBytes,
        md5Hash = md5Hash,
        videoFingerprintVersion = 2,
        videoSampleHashes = samples,
        videoDurationMs = durationMs,
        videoWidth = width,
        videoHeight = height,
        videoAudioSignature = audioSignature,
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
    fun distributedSamples_areAllComparedInsteadOfOnlyTheFirstThree() {
        val first = video(1L, "0000000000000000;1111111111111111;2222222222222222;3333333333333333")
        val second = video(2L, "0000000000000000;1111111111111111;2222222222222222;ffffffffffffffff")

        assertFalse(VideoDuplicateEvidence.compare(first, second, threshold = 70).matches)
    }

    @Test
    fun missingAudioMetadata_isUnknownAndDoesNotAuthorizeDuplicateMatch() {
        val first = video(1L, "0000000000000000;1111111111111111;2222222222222222", audioSignature = "")
        val second = video(2L, first.videoSampleHashes, audioSignature = "")

        assertFalse(VideoDuplicateEvidence.compare(first, second, threshold = 95).matches)
    }

    @Test
    fun md5Evidence_requiresMatchingPositiveSize() {
        val first = video(1L, "", sizeBytes = 100L, md5Hash = "same-md5")
        val differentSize = video(2L, "", sizeBytes = 101L, md5Hash = "same-md5")

        assertFalse(VideoDuplicateEvidence.compare(first, differentSize, threshold = 95).matches)
    }

    @Test
    fun equalChunkHash_isStrongEvidenceEvenWhenSamplesAreUnavailable() {
        val first = video(1L, "", chunkHash = "same-chunk")
        val second = video(2L, "", chunkHash = "same-chunk")

        assertTrue(VideoDuplicateEvidence.compare(first, second, threshold = 95).matches)
    }
}
