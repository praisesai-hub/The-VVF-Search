package com.example.data

import com.example.ai.SemanticEmbeddingProvider
import com.example.storage.HammingDistanceCalculator
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class DuplicateDetectionEngineTest {

    private lateinit var fakeHammingCalculator: FakeHammingDistanceCalculator
    private lateinit var fakeSemanticProvider: FakeSemanticEmbeddingProvider
    private lateinit var duplicateDetectionEngine: DuplicateDetectionEngine

    // Hand-crafted Fake implementing HammingDistanceCalculator
    class FakeHammingDistanceCalculator : HammingDistanceCalculator {
        override fun calculateHammingDistance(hash1: String, hash2: String): Int {
            if (hash1.length != 16 || hash2.length != 16) return -1
            return try {
                val val1 = hash1.toULong(16)
                val val2 = hash2.toULong(16)
                java.lang.Long.bitCount((val1 xor val2).toLong())
            } catch (e: Exception) {
                -1
            }
        }
    }

    // Hand-crafted Fake implementing SemanticEmbeddingProvider to bypass native TF Lite libs
    class FakeSemanticEmbeddingProvider : SemanticEmbeddingProvider {
        override val embeddingVersion: Int = 1
        override fun isModelLoaded(): Boolean = true
        override suspend fun generateImageEmbedding(file: File): FloatArray? = null
        override suspend fun generateTextEmbedding(text: String): FloatArray? = null
    }

    @Before
    fun setUp() {
        fakeSemanticProvider = FakeSemanticEmbeddingProvider()
        fakeHammingCalculator = FakeHammingDistanceCalculator()
        duplicateDetectionEngine = DuplicateDetectionEngine(fakeHammingCalculator, fakeSemanticProvider)
    }

    // Baseline brute-force O(n²) visual duplicates implementation for correctness check
    private fun bruteForceVisualDuplicates(
        validImages: List<FileItemEntity>,
        threshold: Float
    ): List<DuplicateGroup> {
        val thresholdInt = threshold.toInt().coerceIn(50, 100)
        val maxDistance = ((100 - thresholdInt) * 64) / 100

        val visited = mutableSetOf<Long>()
        val resultGroups = mutableListOf<DuplicateGroup>()

        for (file1 in validImages) {
            if (visited.contains(file1.id)) continue

            val cluster = mutableListOf(file1)
            var minDistanceInCluster = 64

            for (file2 in validImages) {
                if (file1.id == file2.id || visited.contains(file2.id)) continue
                val distance = fakeHammingCalculator.calculateHammingDistance(file1.visualSimilarityHash, file2.visualSimilarityHash)
                if (distance in 0..maxDistance) {
                    cluster.add(file2)
                    if (distance < minDistanceInCluster) {
                        minDistanceInCluster = distance
                    }
                }
            }

            if (cluster.size > 1) {
                cluster.forEach { visited.add(it.id) }
                val avgScore = if (minDistanceInCluster < 64) ((64 - minDistanceInCluster) * 100) / 64 else 100
                resultGroups.add(
                    DuplicateGroup(
                        title = "Perceptual Image Match (${avgScore}% Visual Similarity): ${file1.name}",
                        level = 2,
                        similarityScore = avgScore,
                        files = cluster
                    )
                )
            }
        }
        return resultGroups
    }

    private fun createImageFile(id: Long, name: String, hash: String): FileItemEntity {
        return FileItemEntity(
            id = id,
            name = name,
            path = "/storage/emulated/0/DCIM/$name",
            category = FileCategory.IMAGES.name,
            sizeBytes = 1024L,
            visualSimilarityHash = hash
        )
    }

    private fun assertGroupsEqual(expected: List<DuplicateGroup>, actual: List<DuplicateGroup>) {
        val expectedSets = expected.map { group -> group.files.map { it.id }.sorted() }.toSet()
        val actualSets = actual.map { group -> group.files.map { it.id }.sorted() }.toSet()
        assertEquals(expectedSets, actualSets)
    }

    @Test
    fun test_visual_duplicates_correctness() = runBlocking {
        // (a) Correctness test — Compare optimized bucketing algorithm with brute-force on small sample (20 files)
        val files = mutableListOf<FileItemEntity>()

        // Group 1 (Base: 1111222233334444)
        files.add(createImageFile(1, "img1.jpg", "1111222233334444"))
        files.add(createImageFile(2, "img2.jpg", "1111222233334444")) // exact duplicate
        files.add(createImageFile(3, "img3.jpg", "1111222233334445")) // distance 1
        files.add(createImageFile(4, "img4.jpg", "111122223333444F")) // distance 3

        // Group 2 (Base: AAAA555566667777)
        files.add(createImageFile(5, "img5.jpg", "AAAA555566667777"))
        files.add(createImageFile(6, "img6.jpg", "AAAA555566667778")) // distance 1

        // Group 3 (Base: BBBBCCCCDDDDEEEE)
        files.add(createImageFile(7, "img7.jpg", "BBBBCCCCDDDDEEEE"))
        files.add(createImageFile(8, "img8.jpg", "BBBBCCCCDDDDEEEF")) // distance 3

        // Singletons (No duplicates, distinct bands)
        val singletonHashes = listOf(
            "0000000000000000",
            "9999999999999999",
            "0000999900009999",
            "9999000099990000",
            "0000000099999999",
            "9999999900000000",
            "0909090909090909",
            "9090909090909090",
            "0099009900990099",
            "9900990099009900",
            "0990099009900990",
            "9009900990099009"
        )
        for (i in 9..20) {
            val hash = singletonHashes[i - 9]
            files.add(createImageFile(i.toLong(), "img$i.jpg", hash))
        }

        val threshold = 90.0f // translates to maxDistance = 6 bits

        // Run baseline brute-force
        val expectedGroups = bruteForceVisualDuplicates(files, threshold)

        // Run optimized LSH bucketing flow
        val actualGroupsFlow = duplicateDetectionEngine.getVisualDuplicates(
            activeFilesFlow = flowOf(files),
            similarityThresholdFlow = flowOf(threshold)
        )
        val actualGroups = actualGroupsFlow.first()

        // Verify result matches exactly
        assertGroupsEqual(expectedGroups, actualGroups)
        
        // Assert we have exactly 3 duplicate groups found
        assertEquals(3, actualGroups.size)
    }

    @Test
    fun test_visual_duplicates_performance_and_scale() = runBlocking {
        // (b) Performance/scale test — On 5,000 synthetic hash entries, the optimized bucketing algorithm completed within 2 seconds
        val count = 5000
        val files = ArrayList<FileItemEntity>(count)

        // Generate 5000 realistic files distributed over 1000 base buckets
        for (i in 0 until count) {
            val groupKey = i % 1000
            val hexPart = String.format("%04x", groupKey)
            val hash = "${hexPart}111122223333"
            files.add(createImageFile(i.toLong(), "img_scale_$i.jpg", hash))
        }

        val threshold = 95.0f

        val startTime = System.currentTimeMillis()

        val groupsFlow = duplicateDetectionEngine.getVisualDuplicates(
            activeFilesFlow = flowOf(files),
            similarityThresholdFlow = flowOf(threshold)
        )
        val groups = groupsFlow.first()

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        println("Duplicate detection on $count files finished in $duration ms. Found ${groups.size} groups.")

        // Verify it took less than 2000 ms (2.0 seconds) to complete
        assertTrue("Optimized LSH took too long: $duration ms", duration < 2000)
    }

    @Test
    fun test_visual_duplicates_edge_cases() = runBlocking {
        // (c) Edge Case: Empty list
        val emptyFlow = duplicateDetectionEngine.getVisualDuplicates(
            activeFilesFlow = flowOf(emptyList()),
            similarityThresholdFlow = flowOf(90.0f)
        )
        assertTrue(emptyFlow.first().isEmpty())

        // (c) Edge Case: Single file
        val singleFile = createImageFile(1L, "one.jpg", "1111222233334444")
        val singleFlow = duplicateDetectionEngine.getVisualDuplicates(
            activeFilesFlow = flowOf(listOf(singleFile)),
            similarityThresholdFlow = flowOf(90.0f)
        )
        assertTrue(singleFlow.first().isEmpty())

        // (c) Edge Case: All files distinct (no duplicates)
        val distinctFiles = mutableListOf<FileItemEntity>()
        for (i in 1..10) {
            val hexChar = i.toString(16).substring(0, 1)
            distinctFiles.add(createImageFile(i.toLong(), "distinct_$i.jpg", hexChar.repeat(16)))
        }
        val distinctFlow = duplicateDetectionEngine.getVisualDuplicates(
            activeFilesFlow = flowOf(distinctFiles),
            similarityThresholdFlow = flowOf(95.0f)
        )
        assertTrue(distinctFlow.first().isEmpty())
    }

    @Test
    fun testThresholdSensitivityForVisualAndSemanticDuplicates() = runBlocking {
        // Image 1 hash: "0000000000000000" (all zeros)
        // Image 2 hash: "00000000000003FF" (10 bits set -> hamming distance 10)
        val img1 = createImageFile(1001L, "img1.jpg", "0000000000000000")
        val img2 = createImageFile(1002L, "img2.jpg", "00000000000003FF")
        val activeFiles = flowOf(listOf(img1, img2))

        // At 95% threshold, max distance is 3 bits -> Hamming distance 10 should NOT be duplicate
        val highThresholdVisuals = duplicateDetectionEngine.getVisualDuplicates(activeFiles, flowOf(95.0f)).first()
        assertTrue("At 95% threshold, 10-bit distance images must not be flagged as duplicates", highThresholdVisuals.isEmpty())

        // At 70% threshold, max distance is 19 bits -> Hamming distance 10 SHOULD be duplicate
        val lowThresholdVisuals = duplicateDetectionEngine.getVisualDuplicates(activeFiles, flowOf(70.0f)).first()
        assertEquals("At 70% threshold, 10-bit distance images must be flagged as duplicates", 1, lowThresholdVisuals.size)

        // Semantic items with cosine similarity ~0.80
        // e.g. vec1 = [1, 0], vec2 = [0.8, 0.6] -> dot = 0.8 / (1 * 1) = 0.8
        val doc1 = FileItemEntity(
            id = 1003L,
            name = "doc1.txt",
            path = "/doc1.txt",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 100L,
            semanticEmbeddingString = "1.0,0.0",
            semanticIndexed = true
        )
        val doc2 = FileItemEntity(
            id = 1004L,
            name = "doc2.txt",
            path = "/doc2.txt",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 100L,
            semanticEmbeddingString = "0.8,0.6",
            semanticIndexed = true
        )
        val docFiles = flowOf(listOf(doc1, doc2))

        // At 95% threshold (0.95 required), 0.80 similarity should NOT be duplicate
        val highThresholdSemantics = duplicateDetectionEngine.getSemanticDuplicates(docFiles, flowOf(95.0f)).first()
        assertTrue("At 95% threshold, 0.80 similarity documents must not be flagged as duplicates", highThresholdSemantics.isEmpty())

        // At 70% threshold (0.70 required), 0.80 similarity SHOULD be duplicate
        val lowThresholdSemantics = duplicateDetectionEngine.getSemanticDuplicates(docFiles, flowOf(70.0f)).first()
        assertEquals("At 70% threshold, 0.80 similarity documents must be flagged as duplicates", 1, lowThresholdSemantics.size)
    }
}
