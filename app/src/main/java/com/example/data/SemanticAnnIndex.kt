package com.example.data

/**
 * Small, deterministic locality-sensitive-hash index for cosine-vector candidate retrieval.
 * Exact cosine reranking remains the authority; LSH only prevents a full-library vector scan.
 */
object SemanticAnnIndex {
    private const val BAND_COUNT = 4
    private const val BITS_PER_BAND = 16
    private const val BAND_OFFSET = 1
    private const val BAND_MULTIPLIER = 97
    private const val BIT_OFFSET = 1
    private const val BIT_MULTIPLIER = 31

    fun bucketsFor(fileId: Long, embeddingVersion: Int, embedding: String): List<SemanticAnnBucketEntity> {
        val vector = parseVector(embedding) ?: return emptyList()
        return signatures(vector).map { bucketKey ->
            SemanticAnnBucketEntity(fileId, embeddingVersion, bucketKey)
        }
    }

    fun probeKeys(embedding: FloatArray): List<String> {
        if (embedding.isEmpty()) return emptyList()
        return signatures(embedding).flatMap { bucketKey ->
            val band = bucketKey.substringBefore(':')
            val value = bucketKey.substringAfter(':').toInt()
            buildList {
                add(bucketKey)
                repeat(BITS_PER_BAND) { bit -> add("$band:${value xor (1 shl bit)}") }
            }
        }.distinct()
    }

    private fun parseVector(serialized: String): FloatArray? = try {
        serialized.takeIf { it.isNotBlank() }
            ?.split(',')
            ?.map { it.toFloat() }
            ?.toFloatArray()
            ?.takeIf { it.isNotEmpty() }
    } catch (_: NumberFormatException) {
        null
    }

    private fun signatures(vector: FloatArray): List<String> = List(BAND_COUNT) { band ->
        var value = 0
        repeat(BITS_PER_BAND) { bit ->
            val coordinate = (
                (band + BAND_OFFSET) * BAND_MULTIPLIER +
                    (bit + BIT_OFFSET) * BIT_MULTIPLIER
                ) % vector.size
            if (vector[coordinate] >= 0f) value = value or (1 shl bit)
        }
        "b$band:$value"
    }
}
