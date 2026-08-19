package com.example.data

import com.example.ai.SemanticEmbeddingProvider
import com.example.storage.HammingDistanceCalculator
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DuplicateDetectionEngineInstrumentedTest {
    private lateinit var engine: DuplicateDetectionEngine

    private class TestHammingCalculator : HammingDistanceCalculator {
        override fun calculateHammingDistance(hash1: String, hash2: String): Int {
            if (hash1.length != 16 || hash2.length != 16) return -1
            return try {
                val first = hash1.toULong(16)
                val second = hash2.toULong(16)
                java.lang.Long.bitCount((first xor second).toLong())
            } catch (_: NumberFormatException) {
                -1
            }
        }
    }

    private class TestSemanticProvider : SemanticEmbeddingProvider {
        override val embeddingVersion: Int = 1

        override fun isModelLoaded(): Boolean = true

        override suspend fun generateImageEmbedding(file: File): FloatArray? = null

        override suspend fun generateTextEmbedding(text: String): FloatArray? = null
    }

    @Before
    fun setUp() {
        engine = DuplicateDetectionEngine(TestHammingCalculator(), TestSemanticProvider())
    }

    @Test
    fun visualDuplicates_groupOnlyEligibleImages(): Unit {
        runBlocking {
            val first = image(1L, "first.jpg", "0011223344556677")
            val second = first.copy(id = 2L, name = "second.jpg")
            val vault = first.copy(id = 3L, name = "vault.jpg", isVault = true)
            val recycled = first.copy(id = 4L, name = "recycled.jpg", isRecycleBin = true)
            val malformed = first.copy(id = 5L, name = "malformed.jpg", visualSimilarityHash = "short")
            val wrongCategory = first.copy(id = 6L, name = "note.txt", category = FileCategory.DOCUMENTS.name)

            val groups = engine.getVisualDuplicates(
                flowOf(listOf(first, second, vault, recycled, malformed, wrongCategory)),
                flowOf(100f)
            ).first()

            assertEquals(1, groups.size)
            assertEquals(setOf(1L, 2L), groups.single().files.map { it.id }.toSet())
            assertEquals(100, groups.single().similarityScore)
            assertTrue(groups.single().title.contains("Visual Similarity"))
        }
    }

    @Test
    fun videoDuplicates_applyThresholdAndExcludeUnsafeItems(): Unit {
        runBlocking {
            val first = image(10L, "first.mp4", "0000000000000000").copy(
                category = FileCategory.VIDEO.name,
                videoFingerprintVersion = 2,
                videoSampleHashes = "0000000000000000;1111111111111111;2222222222222222;3333333333333333",
                videoDurationMs = 10_000L,
                videoWidth = 1920,
                videoHeight = 1080,
                videoAudioSignature = "yes|video/mp4|1000000",
                videoChunkHash = "chunk-a"
            )
            val near = first.copy(
                id = 11L,
                name = "near.mp4",
                videoSampleHashes = "00000000000003ff;1111111111111111;2222222222222222;3333333333333333",
                videoChunkHash = "chunk-b"
            )
            val vault = first.copy(id = 12L, name = "vault.mp4", isVault = true)

            val highThreshold = engine.getVideoDuplicates(
                flowOf(listOf(first, near, vault)),
                flowOf(95f)
            ).first()
            assertTrue(highThreshold.isEmpty())

            val lowThreshold = engine.getVideoDuplicates(
                flowOf(listOf(first, near, vault)),
                flowOf(70f)
            ).first()
            assertEquals(1, lowThreshold.size)
            assertEquals(setOf(10L, 11L), lowThreshold.single().files.map { it.id }.toSet())
            assertTrue(lowThreshold.single().title.contains("Video"))
        }
    }

    @Test
    fun semanticDuplicates_parseVectorsAndApplySimilarityThreshold(): Unit {
        runBlocking {
            val first = semanticFile(20L, "one.txt", "1.0,0.0")
            val second = semanticFile(21L, "two.txt", "0.8,0.6")
            val malformed = semanticFile(22L, "bad.txt", "not,a,vector")
            val vault = semanticFile(23L, "vault.txt", "1.0,0.0", isVault = true)

            val highThreshold = engine.getSemanticDuplicates(
                flowOf(listOf(first, second, malformed, vault)),
                flowOf(95f)
            ).first()
            assertTrue(highThreshold.isEmpty())

            val lowThreshold = engine.getSemanticDuplicates(
                flowOf(listOf(first, second, malformed, vault)),
                flowOf(70f)
            ).first()
            assertEquals(1, lowThreshold.size)
            assertEquals(setOf(20L, 21L), lowThreshold.single().files.map { it.id }.toSet())
            assertEquals(80, lowThreshold.single().similarityScore)
        }
    }

    @Test
    fun documentDuplicates_requireMatchingFingerprintAndSafeFiles(): Unit {
        runBlocking {
            val first = FileItemEntity(
                id = 30L,
                name = "one.pdf",
                path = "/docs/one.pdf",
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = 1L,
                documentCandidateFingerprint = "same-fingerprint"
            )
            val second = first.copy(id = 31L, name = "two.pdf")
            val different = first.copy(id = 32L, documentCandidateFingerprint = "different")
            val vault = first.copy(id = 33L, isVault = true)
            val recycled = first.copy(id = 34L, isRecycleBin = true)
            val image = first.copy(id = 35L, category = FileCategory.IMAGES.name)

            val groups = engine.getDocumentDuplicates(
                flowOf(listOf(first, second, different, vault, recycled, image))
            ).first()

            assertEquals(1, groups.size)
            assertEquals(setOf(30L, 31L), groups.single().files.map { it.id }.toSet())
            assertEquals(95, groups.single().similarityScore)
            assertTrue(groups.single().title.contains("Candidate Fingerprint"))
        }
    }

    @Test
    fun duplicateFlows_returnEmptyForInsufficientOrMalformedInput(): Unit {
        runBlocking {
            val empty = flowOf(emptyList<FileItemEntity>())
            val shortImage = image(40L, "short.jpg", "bad")

            assertTrue(engine.getVisualDuplicates(empty, flowOf(90f)).first().isEmpty())
            assertTrue(engine.getVideoDuplicates(empty, flowOf(90f)).first().isEmpty())
            assertTrue(engine.getSemanticDuplicates(empty, flowOf(90f)).first().isEmpty())
            assertTrue(engine.getDocumentDuplicates(empty).first().isEmpty())
            assertTrue(
                engine.getVisualDuplicates(
                    flowOf(listOf(shortImage, shortImage.copy(id = 41L))),
                    flowOf(90f)
                ).first().isEmpty()
            )
        }
    }

    private fun image(id: Long, name: String, hash: String): FileItemEntity {
        return FileItemEntity(
            id = id,
            name = name,
            path = "/storage/emulated/0/$name",
            category = FileCategory.IMAGES.name,
            sizeBytes = 1L,
            visualSimilarityHash = hash
        )
    }

    private fun semanticFile(
        id: Long,
        name: String,
        embedding: String,
        isVault: Boolean = false
    ): FileItemEntity {
        return FileItemEntity(
            id = id,
            name = name,
            path = "/docs/$name",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 1L,
            semanticEmbeddingString = embedding,
            semanticIndexed = true,
            isVault = isVault
        )
    }
}
