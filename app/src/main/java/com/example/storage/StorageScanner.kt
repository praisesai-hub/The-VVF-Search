package com.example.storage

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.data.FileCategory
import com.example.data.FileItemEntity
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

class StorageScanner(private val context: Context) : HammingDistanceCalculator {
    companion object {
        private const val TAG = "StorageScanner"
        private const val BATCH_SIZE = 100
    }

    fun scanDeviceStorageFlow(computeHashes: Boolean = false): Flow<List<FileItemEntity>> = channelFlow {
        scanDeviceStorage(computeHashes) { send(it) }
    }.flowOn(Dispatchers.IO)

    suspend fun scanDeviceStorage(computeHashes: Boolean = false): List<FileItemEntity> {
        val discovered = mutableListOf<FileItemEntity>()
        scanDeviceStorage(computeHashes) { discovered.addAll(it) }
        return discovered
    }

    suspend fun scanDeviceStorage(
        computeHashes: Boolean = false,
        onBatchDiscovered: suspend (List<FileItemEntity>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val processedPaths = mutableSetOf<String>()
        val currentBatch = mutableListOf<FileItemEntity>()
        var totalDiscovered = 0

        val emitItem: suspend (FileItemEntity) -> Unit = { item ->
            currentCoroutineContext().ensureActive()
            currentBatch.add(item)
            if (currentBatch.size >= BATCH_SIZE) {
                onBatchDiscovered(currentBatch.toList())
                totalDiscovered += currentBatch.size
                currentBatch.clear()
            }
        }

        // Shared storage is represented by stable content:// URIs. MediaStore.DATA
        // is intentionally not used because it is restricted on modern Android.
        try {
            scanMediaStore(processedPaths, computeHashes, emitItem)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning MediaStore: ${e.message}", e)
        }

        // App-private directories remain directly accessible with java.io.File.
        try {
            val appDirs = listOfNotNull(context.getExternalFilesDir(null), context.filesDir, context.cacheDir)
            for (appDir in appDirs) {
                if (appDir.exists() && appDir.canRead()) {
                    scanDirectoryRecursively(appDir, processedPaths, 0, 4, computeHashes, emitItem)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning app-private directory: ${e.message}", e)
        }

        if (currentBatch.isNotEmpty()) {
            totalDiscovered += currentBatch.size
            onBatchDiscovered(currentBatch.toList())
        }
        Log.i(TAG, "Storage scan completed. Total real files discovered: $totalDiscovered")
        totalDiscovered
    }

    /** Scans a user-granted SAF tree. The caller must persist the tree permission. */
    suspend fun scanSafTree(
        treeUri: Uri,
        computeHashes: Boolean = false,
        onBatchDiscovered: suspend (List<FileItemEntity>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw java.io.FileNotFoundException("Unable to open SAF tree: $treeUri")
        if (!root.exists() || !root.isDirectory) {
            throw java.io.IOException("SAF tree is unavailable or is not a directory: $treeUri")
        }
        val processedUris = mutableSetOf<String>()
        val batch = mutableListOf<FileItemEntity>()
        var count = 0

        suspend fun emit(item: FileItemEntity) {
            currentCoroutineContext().ensureActive()
            batch.add(item)
            if (batch.size >= BATCH_SIZE) {
                onBatchDiscovered(batch.toList())
                count += batch.size
                batch.clear()
            }
        }

        suspend fun walk(directory: DocumentFile) {
            currentCoroutineContext().ensureActive()
            for (child in directory.listFiles()) {
                currentCoroutineContext().ensureActive()
                val uriString = child.uri.toString()
                if (!processedUris.add(uriString)) continue
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile && child.length() > 0L) {
                    val name = child.name ?: continue
                    val category = determineCategory(name)
                    val hash = if (computeHashes) computeContentUriHash(child.uri) else ""
                    val visualHash = if (computeHashes && category == FileCategory.IMAGES) computeContentUriDHash(child.uri) else ""
                    emit(FileItemEntity(
                        name = name,
                        path = uriString,
                        category = category.name,
                        sizeBytes = child.length(),
                        dateModifiedMs = child.lastModified(),
                        md5Hash = hash,
                        visualSimilarityHash = visualHash
                    ))
                }
            }
        }

        walk(root)
        if (batch.isNotEmpty()) {
            count += batch.size
            onBatchDiscovered(batch.toList())
        }
        count
    }

    private suspend fun scanDirectoryRecursively(
        dir: File,
        processedPaths: MutableSet<String>,
        depth: Int,
        maxDepth: Int,
        computeHashes: Boolean,
        onItemDiscovered: suspend (FileItemEntity) -> Unit
    ) {
        currentCoroutineContext().ensureActive()
        if (depth > maxDepth) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            currentCoroutineContext().ensureActive()
            if (file.name.startsWith(".")) continue
            if (file.isDirectory) {
                if (file.name.equals("Android", ignoreCase = true) && depth == 0) continue
                scanDirectoryRecursively(file, processedPaths, depth + 1, maxDepth, computeHashes, onItemDiscovered)
            } else if (file.isFile && file.length() > 0L) {
                val path = file.absolutePath
                if (processedPaths.add(path)) {
                    val category = determineCategory(file.name)
                    val hash = if (computeHashes) computeFileHashQuietly(file) else ""
                    val visualHash = if (computeHashes && category == FileCategory.IMAGES) computeDHashQuietly(file) else ""
                    onItemDiscovered(FileItemEntity(
                        name = file.name,
                        path = path,
                        category = category.name,
                        sizeBytes = file.length(),
                        dateModifiedMs = file.lastModified(),
                        md5Hash = hash,
                        visualSimilarityHash = visualHash
                    ))
                }
            }
        }
    }

    private suspend fun scanMediaStore(
        processedPaths: MutableSet<String>,
        computeHashes: Boolean,
        onItemDiscovered: suspend (FileItemEntity) -> Unit
    ) {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        context.contentResolver.query(
            collectionUri,
            projection,
            null,
            null,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (idColumn == -1 || nameColumn == -1) continue
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val size = if (sizeColumn != -1) cursor.getLong(sizeColumn) else 0L
                val dateSec = if (dateColumn != -1) cursor.getLong(dateColumn) else 0L
                if (id <= 0L || name.isNullOrBlank() || size <= 0L) continue
                val itemUri = ContentUris.withAppendedId(collectionUri, id)
                val path = itemUri.toString()
                if (!processedPaths.add(path)) continue
                val category = determineCategory(name)
                val hash = if (computeHashes) computeContentUriHash(itemUri) else ""
                val visualHash = if (computeHashes && category == FileCategory.IMAGES) computeContentUriDHash(itemUri) else ""
                onItemDiscovered(FileItemEntity(
                    name = name,
                    path = path,
                    category = category.name,
                    sizeBytes = size,
                    dateModifiedMs = if (dateSec > 0) dateSec * 1000L else System.currentTimeMillis(),
                    md5Hash = hash,
                    visualSimilarityHash = visualHash
                ))
            }
        }
    }

    suspend fun computeFileHash(file: File): String = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) return@withContext ""
        file.inputStream().use { computeStreamHash(it) }
    }

    suspend fun computeContentUriHash(uri: Uri): String = withContext(Dispatchers.IO) {
        val input = try { context.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
            ?: return@withContext ""
        input.use { computeStreamHash(it) }
    }

    private suspend fun computeStreamHash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            if (bytesRead > 0) digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun computeFileHashQuietly(file: File): String = try { computeFileHash(file) } catch (_: Exception) { "" }

    private suspend fun computeContentUriDHash(uri: Uri): String = try {
        val bitmap = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } ?: return ""
        val hash = computeDHashFromBitmap(bitmap)
        bitmap.recycle()
        hash
    } catch (e: Exception) {
        Log.w(TAG, "Content URI dHash failed for $uri: ${e.message}")
        ""
    }

    fun determineCategory(fileName: String): FileCategory {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> FileCategory.IMAGES
            "pdf", "doc", "docx", "txt", "rtf", "xls", "xlsx", "ppt", "pptx", "csv" -> FileCategory.DOCUMENTS
            "mp3", "m4a", "wav", "flac", "aac", "ogg" -> FileCategory.AUDIO
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv" -> FileCategory.VIDEO
            "zip", "rar", "7z", "tar", "gz" -> FileCategory.ARCHIVES
            "apk", "xapk", "apks" -> FileCategory.APKS
            else -> FileCategory.OTHER
        }
    }

    fun computeDHashFromBitmap(bitmap: Bitmap): String {
        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
            var hashBits = 0L
            var bitIndex = 0
            for (y in 0 until 8) for (x in 0 until 8) {
                val pixelLeft = scaledBitmap.getPixel(x, y)
                val pixelRight = scaledBitmap.getPixel(x + 1, y)
                val grayLeft = (Color.red(pixelLeft) * 299 + Color.green(pixelLeft) * 587 + Color.blue(pixelLeft) * 114) / 1000
                val grayRight = (Color.red(pixelRight) * 299 + Color.green(pixelRight) * 587 + Color.blue(pixelRight) * 114) / 1000
                if (grayLeft > grayRight) hashBits = hashBits or (1L shl (63 - bitIndex))
                bitIndex++
            }
            if (scaledBitmap != bitmap) scaledBitmap.recycle()
            String.format(Locale.ROOT, "%016x", hashBits)
        } catch (e: Exception) {
            Log.e(TAG, "dHash computation from bitmap failed: ${e.message}")
            ""
        }
    }

    fun decodeSampledBitmapFromFile(file: File, reqWidth: Int, reqHeight: Int): Bitmap? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        BitmapFactory.decodeFile(file.absolutePath, options)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode sampled bitmap: ${e.message}")
        null
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }

    suspend fun computeDHash(file: File): String = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead() || !isImageFile(file.name)) return@withContext ""
        try {
            val bitmap = decodeSampledBitmapFromFile(file, 64, 64) ?: return@withContext ""
            ensureActive()
            val hash = computeDHashFromBitmap(bitmap)
            bitmap.recycle()
            hash
        } catch (e: Exception) {
            Log.e(TAG, "dHash computation failed for ${file.name}: ${e.message}")
            ""
        }
    }

    suspend fun computeVideoDHash(file: File, timeUs: Long = 1_000_000L): String = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead() || !isVideoFile(file.name)) return@withContext ""
        ensureActive()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val keyframeBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
            if (keyframeBitmap != null) {
                val hash = computeDHashFromBitmap(keyframeBitmap)
                keyframeBitmap.recycle()
                hash
            } else ""
        } catch (e: Exception) {
            Log.e(TAG, "Video keyframe dHash failed for ${file.name}: ${e.message}")
            ""
        } finally {
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release() } catch (_: Exception) {}
        }
    }

    fun isVideoFile(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in listOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv")
    fun isPdfFile(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() == "pdf"
    fun isDocumentFile(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in listOf("pdf", "doc", "docx", "txt", "rtf", "xls", "xlsx", "ppt", "pptx", "csv")

    suspend fun computeDocumentFingerprint(file: File): String = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead() || !isDocumentFile(file.name)) return@withContext ""
        try {
            ensureActive()
            val length = file.length()
            if (length == 0L) return@withContext ""
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("DOC_SIZE:$length:".toByteArray())
            file.inputStream().use { input ->
                if (length <= 8192) {
                    val buffer = ByteArray(8192)
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) digest.update(buffer, 0, bytesRead)
                } else {
                    val header = ByteArray(4096)
                    val headerRead = input.read(header)
                    if (headerRead > 0) digest.update(header, 0, headerRead)
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        raf.seek(length - 4096)
                        val tail = ByteArray(4096)
                        val tailRead = raf.read(tail)
                        if (tailRead > 0) digest.update(tail, 0, tailRead)
                    }
                }
            }
            ensureActive()
            digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            Log.w(TAG, "Document fingerprint calculation failed for ${file.name}: ${e.message}")
            ""
        }
    }

    suspend fun computeDHashQuietly(file: File): String = try { computeDHash(file) } catch (_: Exception) { "" }
    fun isImageFile(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in listOf("jpg", "jpeg", "png", "webp", "heic", "bmp", "gif")

    override fun calculateHammingDistance(hash1: String, hash2: String): Int {
        if (hash1.length != 16 || hash2.length != 16) return -1
        return try {
            val val1 = hash1.toULong(16)
            val val2 = hash2.toULong(16)
            java.lang.Long.bitCount((val1 xor val2).toLong())
        } catch (_: Exception) { -1 }
    }
}
