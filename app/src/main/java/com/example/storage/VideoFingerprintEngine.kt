package com.example.storage

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Owns the bounded, multi-sample video duplicate-candidate fingerprint workflow. */
internal class VideoFingerprintEngine(
    private val context: Context,
    private val dHashFromBitmap: (Bitmap) -> String,
    private val isVideoFile: (String) -> Boolean
) {
    suspend fun computeContentUriFingerprint(uri: Uri): VideoFingerprint? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val metadata = readMetadata(retriever)
            val sampleHashes = sampleHashes(retriever, metadata.durationMs)
            if (sampleHashes.size < MIN_VIDEO_FINGERPRINT_SAMPLES) return@withContext null
            VideoFingerprint(
                sampleHashes = sampleHashes,
                durationMs = metadata.durationMs,
                width = metadata.width,
                height = metadata.height,
                audioSignature = metadata.audioSignature,
                chunkHash = computeContentUriChunkHash(uri)
            )
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Content URI video fingerprint failed: ${error.message}")
            null
        } catch (error: SecurityException) {
            Log.w(TAG, "Content URI video fingerprint failed: ${error.message}")
            null
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Content URI video fingerprint failed: ${error.message}")
            null
        } finally {
            releaseRetriever(retriever)
        }
    }

    suspend fun computeFileFingerprint(file: File): VideoFingerprint? = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead() || !isVideoFile(file.name)) return@withContext null
        ensureActive()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val metadata = readMetadata(retriever)
            val sampleHashes = sampleHashes(retriever, metadata.durationMs)
            if (sampleHashes.size < MIN_VIDEO_FINGERPRINT_SAMPLES) return@withContext null
            VideoFingerprint(
                sampleHashes = sampleHashes,
                durationMs = metadata.durationMs,
                width = metadata.width,
                height = metadata.height,
                audioSignature = metadata.audioSignature,
                chunkHash = computeFileChunkHash(file)
            )
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Video multi-sample fingerprint failed for ${file.name}: ${error.message}")
            null
        } catch (error: SecurityException) {
            Log.e(TAG, "Video multi-sample fingerprint failed for ${file.name}: ${error.message}")
            null
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Video multi-sample fingerprint failed for ${file.name}: ${error.message}")
            null
        } finally {
            releaseRetriever(retriever)
        }
    }

    suspend fun computeKeyframeDHash(
        file: File,
        timeUs: Long = DEFAULT_VIDEO_KEYFRAME_TIME_US
    ): String = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead() || !isVideoFile(file.name)) return@withContext ""
        ensureActive()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            bitmap?.let {
                try {
                    dHashFromBitmap(it)
                } finally {
                    it.recycle()
                }
            }.orEmpty()
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Video keyframe dHash failed for ${file.name}: ${error.message}")
            ""
        } catch (error: SecurityException) {
            Log.e(TAG, "Video keyframe dHash failed for ${file.name}: ${error.message}")
            ""
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Video keyframe dHash failed for ${file.name}: ${error.message}")
            ""
        } finally {
            releaseRetriever(retriever)
        }
    }

    private suspend fun sampleHashes(
        retriever: MediaMetadataRetriever,
        durationMs: Long
    ): List<String> {
        val durationUs = durationMs.coerceAtLeast(MIN_VIDEO_DURATION_MS) * MICROSECONDS_PER_MILLISECOND
        return VIDEO_SAMPLE_RATIOS.mapNotNull { ratio ->
            currentCoroutineContext().ensureActive()
            val bitmap = retriever.getFrameAtTime(
                (durationUs * ratio).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.frameAtTime
            bitmap?.let {
                try {
                    dHashFromBitmap(it)
                } finally {
                    it.recycle()
                }
            }?.takeIf { it.length == DHASH_HEX_LENGTH }
        }
    }

    private fun readMetadata(retriever: MediaMetadataRetriever): VideoMetadata {
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO).orEmpty()
        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
        val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE).orEmpty()
        return VideoMetadata(durationMs, width, height, "$hasAudio|$mime|$bitrate")
    }

    private fun computeContentUriChunkHash(uri: Uri): String =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use(::computeChunkHash).orEmpty()
        }.onFailure { error ->
            Log.w(TAG, "Content URI video chunk fingerprint failed: ${error.message}")
        }.getOrDefault("")

    private fun computeFileChunkHash(file: File): String = runCatching {
        val length = file.length()
        if (length <= 0L) return@runCatching ""
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("VIDEO_CHUNKS:$length".toByteArray())
        java.io.RandomAccessFile(file, "r").use { reader ->
            val midpoint = (length / 2 - FINGERPRINT_CHUNK_BYTES / 2).coerceAtLeast(0L)
            listOf(0L, midpoint, (length - FINGERPRINT_CHUNK_BYTES).coerceAtLeast(0L))
                .distinct()
                .forEach { offset ->
                    reader.seek(offset)
                    val buffer = ByteArray(FINGERPRINT_CHUNK_BYTES)
                    val read = reader.read(buffer)
                    if (read > 0) digest.update(buffer, 0, read)
                }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.onFailure { error ->
        Log.w(TAG, "Video chunk fingerprint failed for ${file.name}: ${error.message}")
    }.getOrDefault("")

    private fun computeChunkHash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(FINGERPRINT_CHUNK_BYTES)
        var firstRead = 0
        while (firstRead < buffer.size) {
            val read = input.read(buffer, firstRead, buffer.size - firstRead)
            if (read <= 0) break
            firstRead += read
        }
        if (firstRead > 0) digest.update(buffer, 0, firstRead)
        var remainingBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            remainingBytes += read
        }
        digest.update("CONTENT_URI_VIDEO:$remainingBytes".toByteArray())
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun releaseRetriever(retriever: MediaMetadataRetriever) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
        } catch (_: Exception) {
            // Best-effort resource cleanup.
        }
    }

    private data class VideoMetadata(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val audioSignature: String
    )

    private companion object {
        const val TAG = "StorageScanner"
        const val MIN_VIDEO_DURATION_MS = 1L
        const val MICROSECONDS_PER_MILLISECOND = 1_000L
        const val DHASH_HEX_LENGTH = 16
        const val MIN_VIDEO_FINGERPRINT_SAMPLES = 3
        const val FINGERPRINT_CHUNK_BYTES = 64 * 1024
        const val DEFAULT_VIDEO_KEYFRAME_TIME_US = 1_000_000L
        val VIDEO_SAMPLE_RATIOS = listOf(0.10, 0.35, 0.60, 0.85)
    }
}
