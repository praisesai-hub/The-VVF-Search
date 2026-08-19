package com.example.data

import com.example.ai.SemanticEmbeddingProvider
import com.example.storage.HammingDistanceCalculator
import com.example.storage.StorageScanner
import com.example.storage.VideoDuplicateEvidence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers

class DuplicateDetectionEngine(
    private val storageScanner: HammingDistanceCalculator,
    private val tfliteProvider: SemanticEmbeddingProvider
) {
    fun getVisualDuplicates(
        activeFilesFlow: Flow<List<FileItemEntity>>,
        similarityThresholdFlow: Flow<Float>
    ): Flow<List<DuplicateGroup>> {
        return combine(activeFilesFlow, similarityThresholdFlow) { files, threshold ->
            val validImages = files.filter { 
                it.category == FileCategory.IMAGES.name && 
                it.visualSimilarityHash.isNotBlank() && 
                !it.isVault && 
                !it.isRecycleBin 
            }
            if (validImages.size < 2) {
                emptyList()
            } else {
                val thresholdInt = threshold.toInt().coerceIn(50, 100)
                val maxDistance = ((100 - thresholdInt) * 64) / 100

                // LSH bucketing for images dHash (4 bands of 4 hex chars / 16 bits each)
                val buckets = HashMap<String, MutableList<FileItemEntity>>()
                for (file in validImages) {
                    val hash = file.visualSimilarityHash
                    if (hash.length >= 16) {
                        buckets.getOrPut("b1_" + hash.substring(0, 4)) { mutableListOf() }.add(file)
                        buckets.getOrPut("b2_" + hash.substring(4, 8)) { mutableListOf() }.add(file)
                        buckets.getOrPut("b3_" + hash.substring(8, 12)) { mutableListOf() }.add(file)
                        buckets.getOrPut("b4_" + hash.substring(12, 16)) { mutableListOf() }.add(file)
                    }
                }

                val visited = mutableSetOf<Long>()
                val resultGroups = mutableListOf<DuplicateGroup>()

                for (file1 in validImages) {
                    if (visited.contains(file1.id)) continue

                    val hash1 = file1.visualSimilarityHash
                    if (hash1.length < 16) continue
                    // Retrieve candidate matches sharing at least one LSH bucket to avoid O(N^2)
                    val candidates = mutableSetOf<FileItemEntity>()
                    buckets["b1_" + hash1.substring(0, 4)]?.let { candidates.addAll(it) }
                    buckets["b2_" + hash1.substring(4, 8)]?.let { candidates.addAll(it) }
                    buckets["b3_" + hash1.substring(8, 12)]?.let { candidates.addAll(it) }
                    buckets["b4_" + hash1.substring(12, 16)]?.let { candidates.addAll(it) }

                    candidates.remove(file1)
                    candidates.removeAll { visited.contains(it.id) }

                    val cluster = mutableListOf(file1)
                    var minDistanceInCluster = 64

                    for (file2 in candidates) {
                        val distance = storageScanner.calculateHammingDistance(file1.visualSimilarityHash, file2.visualSimilarityHash)
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
                resultGroups
            }
        }.flowOn(Dispatchers.Default)
    }

    fun getVideoDuplicates(
        activeFilesFlow: Flow<List<FileItemEntity>>,
        similarityThresholdFlow: Flow<Float>
    ): Flow<List<DuplicateGroup>> {
        return combine(activeFilesFlow, similarityThresholdFlow) { files, threshold ->
            val validVideos = files.filter {
                it.category == FileCategory.VIDEO.name &&
                VideoDuplicateEvidence.sampleHashes(it).size >= 3 &&
                !it.isVault &&
                !it.isRecycleBin
            }
            if (validVideos.size < 2) {
                emptyList()
            } else {
                val thresholdInt = threshold.toInt().coerceIn(50, 100)

                // Bucket by the first nibble of every temporal sample, not one keyframe.
                val buckets = HashMap<String, MutableList<FileItemEntity>>()
                for (file in validVideos) {
                    VideoDuplicateEvidence.bucketKeys(file).forEach { key ->
                        buckets.getOrPut(key) { mutableListOf() }.add(file)
                    }
                }

                val visited = mutableSetOf<Long>()
                val resultGroups = mutableListOf<DuplicateGroup>()

                for (file1 in validVideos) {
                    if (visited.contains(file1.id)) continue

                    val candidates = mutableSetOf<FileItemEntity>()
                    VideoDuplicateEvidence.bucketKeys(file1).forEach { key ->
                        buckets[key]?.let { candidates.addAll(it) }
                    }
                    candidates.remove(file1)
                    candidates.removeAll { visited.contains(it.id) }

                    val cluster = mutableListOf(file1)
                    var bestScoreInCluster = 0

                    for (file2 in candidates) {
                        val comparison = VideoDuplicateEvidence.compare(file1, file2, thresholdInt)
                        if (comparison.matches) {
                            cluster.add(file2)
                            bestScoreInCluster = maxOf(bestScoreInCluster, comparison.score)
                        }
                    }

                    if (cluster.size > 1) {
                        cluster.forEach { visited.add(it.id) }
                        val avgScore = bestScoreInCluster
                        resultGroups.add(
                            DuplicateGroup(
                                title = "Multi-Sample Video Match (${avgScore}% Visual Similarity): ${file1.name}",
                                level = 2,
                                similarityScore = avgScore,
                                files = cluster
                            )
                        )
                    }
                }
                resultGroups
            }
        }.flowOn(Dispatchers.Default)
    }

    fun getSemanticDuplicates(
        activeFilesFlow: Flow<List<FileItemEntity>>,
        similarityThresholdFlow: Flow<Float>
    ): Flow<List<DuplicateGroup>> {
        return combine(activeFilesFlow, similarityThresholdFlow) { files, threshold ->
            val validFiles = files.filter { 
                it.semanticIndexed && 
                it.semanticEmbeddingString.isNotBlank() && 
                !it.isVault && 
                !it.isRecycleBin 
            }
            if (validFiles.size < 2) {
                emptyList()
            } else {
                val minSimilarity = threshold / 100f
                val parsedVectors = validFiles.mapNotNull { file ->
                    val vec = tfliteProvider.stringToFloatArray(file.semanticEmbeddingString)
                    if (vec != null) file to vec else null
                }

                val visited = mutableSetOf<Long>()
                val resultGroups = mutableListOf<DuplicateGroup>()

                // LSH-like dominant-feature coordinate bucketing (top 3 indices with largest values)
                val buckets = HashMap<String, MutableList<Pair<FileItemEntity, FloatArray>>>()
                for (pair in parsedVectors) {
                    val (_, vec) = pair
                    val peaks = vec.indices.sortedByDescending { vec[it] }.take(3)
                    for (p in peaks) {
                        buckets.getOrPut("peak_$p") { mutableListOf() }.add(pair)
                    }
                }

                for (pair1 in parsedVectors) {
                    val (file1, vec1) = pair1
                    if (visited.contains(file1.id)) continue

                    val peaks = vec1.indices.sortedByDescending { vec1[it] }.take(3)
                    val candidates = mutableSetOf<Pair<FileItemEntity, FloatArray>>()
                    for (p in peaks) {
                        buckets["peak_$p"]?.let { candidates.addAll(it) }
                    }

                    val cluster = mutableListOf(file1)
                    var maxScoreInCluster = 0.0f

                    for (pair2 in candidates) {
                        val (file2, vec2) = pair2
                        if (file1.id == file2.id || visited.contains(file2.id)) continue

                        val similarity = tfliteProvider.calculateCosineSimilarity(vec1, vec2)
                        if (similarity >= minSimilarity) {
                            cluster.add(file2)
                            if (similarity > maxScoreInCluster) {
                                maxScoreInCluster = similarity
                            }
                        }
                    }

                    if (cluster.size > 1) {
                        cluster.forEach { visited.add(it.id) }
                        val pctScore = (maxScoreInCluster * 100).toInt()
                        resultGroups.add(
                            DuplicateGroup(
                                title = "AI Vector Match (${pctScore}% Semantic Similarity): ${file1.name}",
                                level = 3,
                                similarityScore = pctScore,
                                files = cluster
                            )
                        )
                    }
                }
                resultGroups
            }
        }.flowOn(Dispatchers.Default)
    }

    fun getDocumentDuplicates(activeFilesFlow: Flow<List<FileItemEntity>>): Flow<List<DuplicateGroup>> {
        return activeFilesFlow.map { files ->
            val validDocs = files.filter {
                it.category == FileCategory.DOCUMENTS.name &&
                it.documentCandidateFingerprint.isNotBlank() &&
                !it.isVault &&
                !it.isRecycleBin 
            }
            if (validDocs.size < 2) {
                emptyList()
            } else {
                val groups = validDocs.groupBy { it.documentCandidateFingerprint }
                    .filter { it.value.size > 1 && it.key.isNotBlank() }
                    .map { (fp, duplicateList) ->
                        DuplicateGroup(
                            title = "Document Candidate Fingerprint Match: ${duplicateList.first().name}",
                            level = 2,
                            similarityScore = 95,
                            files = duplicateList
                        )
                    }
                groups
            }
        }.flowOn(Dispatchers.Default)
    }
}
