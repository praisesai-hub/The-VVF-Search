package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.get
import androidx.core.graphics.scale
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Interface for AI Semantic Duplicate Detection.
 * Generates vector embeddings for images, documents, and text.
 */
interface SemanticEmbeddingProvider {
    val embeddingVersion: Int
    fun isModelLoaded(): Boolean
    suspend fun generateImageEmbedding(file: File): FloatArray?
    suspend fun generateTextEmbedding(text: String): FloatArray?

    /**
     * Calculates cosine similarity between two float vector embeddings (range -1.0 to 1.0).
     */
    fun calculateCosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
        if (emb1.size != emb2.size || emb1.isEmpty()) return 0.0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in emb1.indices) {
            dotProduct += emb1[i] * emb2[i]
            normA += emb1[i] * emb1[i]
            normB += emb2[i] * emb2[i]
        }
        if (normA == 0.0f || normB == 0.0f) return 0.0f
        return (dotProduct / (sqrt(normA.toDouble()) * sqrt(normB.toDouble()))).toFloat()
    }

    /**
     * Converts FloatArray embedding vector to comma-separated String for Room database storage.
     */
    fun floatArrayToString(vector: FloatArray): String {
        return vector.joinToString(",")
    }

    /**
     * Parses comma-separated String from Room database into FloatArray embedding vector.
     */
    fun stringToFloatArray(str: String): FloatArray? {
        if (str.isBlank()) return null
        return try {
            str.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Lightweight, memory-safe On-Device Feature Embedding Engine.
 * Generates normalized 128-dimensional dense vector space embeddings using character trigram
 * feature hashing + word term-frequency weighting for text, and spatial color grid feature vectors for images.
 */
object LightweightEmbeddingEngine {
    private const val DIMENSION = 128

    fun generateTextEmbedding(text: String): FloatArray? {
        if (text.isBlank()) return null
        val vector = FloatArray(DIMENSION)
        val words = SearchTextTokenizer.tokenize(text)
        if (words.isEmpty()) return null

        for (word in words) {
            val wordHash = (word.hashCode() and 0x7FFFFFFF) % DIMENSION
            vector[wordHash] += 2.0f

            // Character trigrams for subword / fuzzy semantic similarity
            val codePoints = word.codePoints().toArray()
            if (codePoints.size >= 3) {
                for (i in 0..codePoints.size - 3) {
                    val tri = String(codePoints, i, 3)
                    val triHash = (tri.hashCode() and 0x7FFFFFFF) % DIMENSION
                    vector[triHash] += 1.0f
                }
            }
        }

        return normalize(vector)
    }

    fun generateImageEmbedding(file: File): FloatArray? {
        if (!file.exists() || !file.canRead()) return null
        val vector = FloatArray(DIMENSION)
        val lowerName = file.name.lowercase()

        try {
            if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".webp") || lowerName.endsWith(".bmp")) {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 4
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                if (bitmap != null) {
                    val scaled = bitmap.scale(8, 8, true)
                    var idx = 0
                    for (x in 0 until 8) {
                        for (y in 0 until 8) {
                            val pixel = scaled[x, y]
                            val r = (pixel shr 16 and 0xFF) / 255.0f
                            val g = (pixel shr 8 and 0xFF) / 255.0f
                            val b = (pixel and 0xFF) / 255.0f
                            if (idx < DIMENSION - 2) {
                                vector[idx++] += r
                                vector[idx++] += g
                                vector[idx++] += b
                            }
                        }
                    }
                    if (scaled != bitmap) scaled.recycle()
                    bitmap.recycle()
                    return normalize(vector)
                }
            }
        } catch (e: Exception) {
            Log.w("LightweightEmbedding", "Image feature extraction failed for ${file.name}: ${e.message}")
        }

        // Fallback for non-image files or failed decodes: generate text embedding from file name and size
        return generateTextEmbedding("${file.name} ${file.length()}")
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0f
        for (v in vector) sumSq += v * v
        val norm = sqrt(sumSq.toDouble()).toFloat()
        if (norm > 0.0f) {
            for (i in vector.indices) vector[i] /= norm
        }
        return vector
    }
}

/**
 * Memory-safe Fallback Semantic Embedding Provider.
 * Uses lightweight on-device feature vector generation when TFLite model binary is absent.
 */
class FallbackSemanticEmbeddingProvider : SemanticEmbeddingProvider {
    /**
     * This provider is intentionally local and deterministic. It does not claim Mobile CLIP
     * quality, but it keeps semantic search and duplicate ranking available when a separately
     * licensed TFLite model is not bundled with the app.
     */
    override val embeddingVersion: Int = 2
    override fun isModelLoaded(): Boolean = true
    override suspend fun generateImageEmbedding(file: File): FloatArray? =
        LightweightEmbeddingEngine.generateImageEmbedding(file)

    override suspend fun generateTextEmbedding(text: String): FloatArray? =
        LightweightEmbeddingEngine.generateTextEmbedding(text)
}

/**
 * Real TFLite / On-Device AI Embedding Engine Implementation (Step 6).
 * Manages TFLite Interpreter inference pipeline with graceful fallback when model or vocab is missing.
 */
class TFLiteSemanticEmbeddingProvider(
    modelFile: File? = null
) : SemanticEmbeddingProvider {
    override val embeddingVersion: Int = 1

    private var interpreter: Interpreter? = null
    private var vocabMap: Map<String, Int>? = null
    private var vectorDimension: Int = 512

    init {
        if (modelFile != null && modelFile.exists() && modelFile.canRead()) {
            loadModelFromFile(modelFile)
        }
    }

    /**
     * Safely attempts to initialize the TFLite Interpreter from a model file.
     * Prevents any runtime crashes if the file is invalid or unsupported.
     * Uses memory-mapped FileChannel for efficient native memory access.
     */
    fun loadModelFromFile(modelFile: File): Boolean {
        return try {
            java.io.FileInputStream(modelFile).use { fis ->
                val fileChannel = fis.channel
                val buffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, modelFile.length())
                loadModelFromBuffer(buffer)
            }
        } catch (e: Throwable) {
            Log.w("TFLiteSemantic", "Failed to load TFLite model from file: ${e.message}")
            interpreter = null
            vocabMap = null
            false
        }
    }

    /**
     * Safely attempts to initialize the TFLite Interpreter and Vocab Tokenizer from Android Asset files.
     * Guarantees no crashes if assets are missing or corrupted.
     * Requires both TFLite model and vocabulary asset to consider model loaded.
     */
    fun loadModelFromAssets(
        context: Context,
        assetName: String = "mobile_clip_embedding.tflite",
        vocabAsset: String = "mobile_clip_vocab.txt"
    ): Boolean {
        return try {
            val vocabExists = try {
                context.assets.open(vocabAsset).use { true }
            } catch (e: Exception) {
                false
            }
            val modelExists = try {
                context.assets.open(assetName).use { true }
            } catch (e: Exception) {
                false
            }

            if (!vocabExists || !modelExists) {
                Log.i("TFLiteSemantic", "Required model asset '$assetName' or vocab asset '$vocabAsset' not found in assets.")
                interpreter = null
                vocabMap = null
                return false
            }

            // Load vocabulary mapping
            val map = mutableMapOf<String, Int>()
            context.assets.open(vocabAsset).bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val token = line.trim()
                    if (token.isNotEmpty()) {
                        map[token] = index
                    }
                }
            }

            if (map.isEmpty()) {
                Log.w("TFLiteSemantic", "Vocab asset '$vocabAsset' is empty.")
                interpreter = null
                vocabMap = null
                return false
            }

            val buffer: ByteBuffer = try {
                val bytes = context.assets.open(assetName).use { it.readBytes() }
                ByteBuffer.allocateDirect(bytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(bytes)
                    rewind()
                }
            } catch (e: Exception) {
                Log.e("TFLiteSemantic", "Failed to load model asset $assetName: ${e.message}")
                return false
            }

            if (loadModelFromBuffer(buffer)) {
                vocabMap = map
                true
            } else {
                vocabMap = null
                false
            }
        } catch (e: Throwable) {
            Log.i("TFLiteSemantic", "TFLite model or vocab asset not found in assets (optional feature): ${e.message}")
            interpreter = null
            vocabMap = null
            false
        }
    }

    fun loadModelFromBuffer(buffer: ByteBuffer): Boolean {
        return try {
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter?.close()
            interpreter = Interpreter(buffer, options)
            Log.i("TFLiteSemantic", "TFLite Model loaded successfully from buffer")
            true
        } catch (e: Throwable) {
            Log.w("TFLiteSemantic", "Failed to load TFLite model from buffer: ${e.message}")
            interpreter = null
            vocabMap = null
            false
        }
    }

    override fun isModelLoaded(): Boolean {
        return interpreter != null && !vocabMap.isNullOrEmpty()
    }

    private fun decodeSampledBitmapFromFile(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            Log.e("TFLiteSemantic", "Failed to decode sampled bitmap: ${e.message}")
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    override suspend fun generateImageEmbedding(file: File): FloatArray? {
        if (!file.exists() || !file.canRead()) return null
        if (file.length() > 50 * 1024 * 1024L) {
            Log.w("TFLiteSemantic", "Image file exceeds 50MB limit, skipping embedding to prevent OOM: ${file.name}")
            return null
        }
        val activeInterpreter = interpreter ?: return null

        return try {
            val bitmap = decodeSampledBitmapFromFile(file, 224, 224) ?: return null
            val resizedBitmap = bitmap.scale(224, 224, true)
            val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

            val outputArray = Array(1) { FloatArray(vectorDimension) }
            activeInterpreter.run(inputBuffer, outputArray)

            if (resizedBitmap != bitmap) {
                resizedBitmap.recycle()
            }
            bitmap.recycle()

            normalizeEmbedding(outputArray[0])
        } catch (e: OutOfMemoryError) {
            Log.e("TFLiteSemantic", "OutOfMemoryError during image embedding inference for ${file.name}: ${e.message}")
            System.gc()
            null
        } catch (e: Exception) {
            Log.w("TFLiteSemantic", "Error during image embedding inference: ${e.message}")
            null
        }
    }

    override suspend fun generateTextEmbedding(text: String): FloatArray? {
        if (text.isBlank()) return null
        if (!isModelLoaded()) return null
        val activeInterpreter = interpreter ?: return null

        return try {
            val inputBuffer = convertTextToByteBuffer(text) ?: return null
            val outputArray = Array(1) { FloatArray(vectorDimension) }

            activeInterpreter.run(inputBuffer, outputArray)
            normalizeEmbedding(outputArray[0])
        } catch (e: Exception) {
            Log.w("TFLiteSemantic", "Error during text embedding inference: ${e.message}")
            null
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * 224 * 224 * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(224 * 224)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        for (i in 0 until 224) {
            for (j in 0 until 224) {
                val valValue = intValues[pixel++]
                byteBuffer.putFloat(((valValue shr 16 and 0xFF) - 127.5f) / 127.5f)
                byteBuffer.putFloat(((valValue shr 8 and 0xFF) - 127.5f) / 127.5f)
                byteBuffer.putFloat(((valValue and 0xFF) - 127.5f) / 127.5f)
            }
        }
        return byteBuffer
    }

    private fun convertTextToByteBuffer(text: String): ByteBuffer? {
        val map = vocabMap ?: return null
        val maxTokens = 128
        val byteBuffer = ByteBuffer.allocateDirect(4 * maxTokens)
        byteBuffer.order(ByteOrder.nativeOrder())
        val words = SearchTextTokenizer.tokenize(text).take(maxTokens)
        for (i in 0 until maxTokens) {
            val tokenVal = if (i < words.size) map[words[i]] ?: 0 else 0
            byteBuffer.putFloat(tokenVal.toFloat())
        }
        return byteBuffer
    }

    private fun normalizeEmbedding(vector: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares.toDouble()).toFloat()
        if (norm > 0.0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
        } catch (e: Exception) {
            Log.w("TFLiteSemantic", "Error closing interpreter: ${e.message}")
        }
    }
}
