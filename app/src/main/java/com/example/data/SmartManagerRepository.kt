// SmartManagerRepository - Production baseline
package com.example.data

import com.example.ai.SearchTextTokenizer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.ai.SemanticEmbeddingProvider
import com.example.ai.FallbackSemanticEmbeddingProvider
import com.example.ai.TFLiteSemanticEmbeddingProvider
import com.example.security.KeystoreVaultManager
import com.example.domain.WorkCoordinator
import com.example.domain.retry.RetryDecision
import com.example.domain.retry.RetryOperation
import com.example.domain.retry.RetryPolicy
import com.example.storage.PhysicalStorageManager
import com.example.storage.StorageScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

open class SmartManagerRepository(
    private val context: Context,
    private val dao: FileDao = AppDatabase.getDatabase(context).fileDao(),
    private val ocrEngine: OcrEngine? = null,
    // Production remains default-deny. Tests may inject an explicit authorized fixture
    // without changing build provisioning or device-owner consent.
    private val cloudTransferAllowed: (Context) -> Boolean = CloudSyncPolicy::canTransfer,
) {
    val keystoreVaultManager: KeystoreVaultManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { KeystoreVaultManager() }
    val storageScanner = StorageScanner(context)
    val workCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { WorkCoordinator(context) }
    val fileRepository by lazy { FileRepository(context, dao) }
    private val vaultManagerEngine: VaultManagerEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VaultManagerEngine(context, keystoreVaultManager)
    }
    val vaultRepository by lazy { VaultRepository(context, dao, keystoreVaultManager, vaultManagerEngine) }
    val pluginRepository by lazy { PluginRepository(dao) }
    val activeOcrEngine: OcrEngine by lazy { ocrEngine ?: MLKitOcrEngine(context) }

    private fun isAssetExists(fileName: String): Boolean = try {
        context.assets.open(fileName).use { }
        true
    } catch (_: Exception) { false }

    val tfliteProvider: SemanticEmbeddingProvider by lazy {
        if (isAssetExists("mobile_clip_embedding.tflite") && isAssetExists("mobile_clip_vocab.txt")) {
            try {
                TFLiteSemanticEmbeddingProvider().apply { loadModelFromAssets(context) }
            } catch (e: Throwable) {
                Log.e("SmartManagerRepository", "TFLite semantic model failed to load", e)
                FallbackSemanticEmbeddingProvider()
            }
        } else {
            Log.w("SmartManagerRepository", "Semantic model assets are unavailable")
            FallbackSemanticEmbeddingProvider()
        }
    }

    val isSemanticSearchAvailable: Boolean
        get() = tfliteProvider.isModelLoaded()

    private val duplicateDetectionEngine by lazy { DuplicateDetectionEngine(storageScanner, tfliteProvider) }
    private val repositoryScope = CoroutineScope(Dispatchers.IO + Job() + kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e("SmartManagerRepository", "Unhandled exception in background repositoryScope", throwable)
    })
    private var activeScanJob: Job? = null
    private val _scanProgress = MutableStateFlow(1.0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    val activeFiles: Flow<List<FileItemEntity>> = fileRepository.getAllActiveFiles()
    val recentFiles: Flow<List<FileItemEntity>> = dao.getRecentFiles()
    val categoryStats: Flow<List<CategoryStat>> = dao.getCategoryStats()
    val ocrScannedFiles: Flow<List<FileItemEntity>> = dao.getOcrScannedFiles()

    suspend fun getFileById(id: Long) = fileRepository.getFileById(id)
    suspend fun getFileByName(name: String) = dao.getFileByName(name)

    fun searchSemanticFiles(query: String): Flow<List<FileItemEntity>> {
        val normalizedQuery = SearchTextTokenizer.normalize(query)
        if (!isSemanticSearchAvailable) return kotlinx.coroutines.flow.flowOf(emptyList())
        if (normalizedQuery.isBlank()) return dao.getAllActiveFiles()
        return dao.getAllActiveFiles().map { files ->
            val queryVec = tfliteProvider.generateTextEmbedding(normalizedQuery)
            if (queryVec == null) {
                files.filter { file ->
                    SearchTextTokenizer.containsQuery(file.name, normalizedQuery) ||
                        SearchTextTokenizer.containsQuery(file.ocrText, normalizedQuery) ||
                        SearchTextTokenizer.containsQuery(file.tags, normalizedQuery)
                }
            } else {
                files.mapNotNull { file ->
                    val fileVec = tfliteProvider.stringToFloatArray(file.semanticEmbeddingString)
                        ?: tfliteProvider.generateTextEmbedding("${file.name} ${file.ocrText} ${file.tags}")
                    if (fileVec != null) {
                        val sim = tfliteProvider.calculateCosineSimilarity(queryVec, fileVec)
                        val isTextMatch = SearchTextTokenizer.containsQuery(file.name, normalizedQuery) ||
                            SearchTextTokenizer.containsQuery(file.ocrText, normalizedQuery) ||
                            SearchTextTokenizer.containsQuery(file.tags, normalizedQuery)
                        if (sim > 0.10f || isTextMatch) file to sim else null
                    } else null
                }.sortedByDescending { it.second }.map { it.first }
            }
        }
    }

    val recycleBinFiles: Flow<List<FileItemEntity>> = dao.getRecycleBinFiles()
    val vaultItems: Flow<List<VaultItemEntity>> = dao.getAllVaultItems()
    val cloudSyncItems: Flow<List<CloudSyncItemEntity>> = dao.getCloudSyncItems()
    val plugins: Flow<List<PluginEntity>> = pluginRepository.getAllPlugins()

    val exactDuplicates: Flow<List<DuplicateGroup>> = dao.getDuplicateFilesByHash().map { duplicateFiles ->
        duplicateFiles.groupBy { it.md5Hash }
            .filter { it.value.size > 1 && it.key.isNotBlank() }
            .map { (_, duplicateList) ->
                DuplicateGroup(
                    title = "Exact SHA-256 Hash Match: ${duplicateList.first().name}",
                    level = 1,
                    similarityScore = 100,
                    files = duplicateList
                )
            }
    }.flowOn(Dispatchers.Default)

    fun startIncrementalDuplicateScan() {
        activeScanJob?.cancel()
        activeScanJob = repositoryScope.launch {
            _isScanning.value = true
            _scanProgress.value = 0.0f
            try {
                val unhashed = dao.getUnhashedFiles()
                if (unhashed.isEmpty()) return@launch
                val isOcrEnabled = dao.getAllPlugins().first().find { it.pluginId == "ocr_engine" }?.isEnabled ?: true
                val totalCount = unhashed.size
                var processedCount = 0
                unhashed.chunked(50).forEach { chunk ->
                    ensureActive()
                    val updatedChunk = mutableListOf<FileItemEntity>()
                    chunk.forEach { file ->
                        ensureActive()
                        var updated = file
                        if (isOcrEnabled && updated.ocrText.isBlank() && (updated.category == FileCategory.IMAGES.name || updated.category == FileCategory.DOCUMENTS.name)) {
                            val realOcr = activeOcrEngine.extractRealOcrText(updated.path)
                            if (realOcr.isNotBlank()) updated = updated.copy(ocrText = realOcr)
                        }
                        if (updated.md5Hash.isBlank() && !updated.path.startsWith("content://")) {
                            val javaFile = File(updated.path)
                            if (javaFile.exists() && javaFile.canRead()) {
                                val hash = withContext(Dispatchers.IO) { storageScanner.computeFileHash(javaFile) }
                                if (hash.isNotBlank()) updated = updated.copy(md5Hash = hash)
                            }
                        }
                        if (updated.category == FileCategory.IMAGES.name && updated.visualSimilarityHash.isBlank() && !updated.path.startsWith("content://")) {
                            val javaFile = File(updated.path)
                            if (javaFile.exists() && javaFile.canRead()) {
                                val dHash = withContext(Dispatchers.IO) { storageScanner.computeDHash(javaFile) }
                                if (dHash.isNotBlank()) updated = updated.copy(visualSimilarityHash = dHash)
                            }
                        }
                        if (updated.category == FileCategory.VIDEO.name && updated.visualSimilarityHash.isBlank() && !updated.path.startsWith("content://")) {
                            val javaFile = File(updated.path)
                            if (javaFile.exists() && javaFile.canRead()) {
                                val vHash = withContext(Dispatchers.IO) { storageScanner.computeVideoDHash(javaFile) }
                                if (vHash.isNotBlank()) updated = updated.copy(visualSimilarityHash = vHash)
                            }
                        }
                        if (updated.category == FileCategory.DOCUMENTS.name && updated.visualSimilarityHash.isBlank() && !updated.path.startsWith("content://")) {
                            val javaFile = File(updated.path)
                            if (javaFile.exists() && javaFile.canRead()) {
                                val docFp = withContext(Dispatchers.IO) { storageScanner.computeDocumentFingerprint(javaFile) }
                                if (docFp.isNotBlank()) updated = updated.copy(visualSimilarityHash = docFp)
                            }
                        }
                        if (!updated.semanticIndexed) {
                            val textContent = "${updated.name} ${updated.ocrText} ${updated.tags}".trim()
                            val javaFile = if (!updated.path.startsWith("content://")) File(updated.path) else null
                            val embedding = if (javaFile != null && javaFile.exists() && javaFile.canRead()) {
                                tfliteProvider.generateImageEmbedding(javaFile) ?: tfliteProvider.generateTextEmbedding(textContent)
                            } else tfliteProvider.generateTextEmbedding(textContent)
                            if (embedding != null) {
                                updated = updated.copy(
                                    semanticEmbeddingVersion = tfliteProvider.embeddingVersion,
                                    semanticIndexed = true,
                                    semanticEmbeddingString = tfliteProvider.floatArrayToString(embedding)
                                )
                            }
                        }
                        if (updated != file) updatedChunk.add(updated)
                        processedCount++
                    }
                    if (updatedChunk.isNotEmpty()) dao.updateFiles(updatedChunk)
                    _scanProgress.value = processedCount.toFloat() / totalCount.toFloat()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SmartManagerRepository", "Incremental scan failed", e)
            } finally {
                _scanProgress.value = 1.0f
                _isScanning.value = false
            }
        }
    }

    fun getVisualDuplicates(similarityThresholdFlow: Flow<Float>): Flow<List<DuplicateGroup>> = duplicateDetectionEngine.getVisualDuplicates(dao.getAllActiveFiles(), similarityThresholdFlow)
    fun getVideoDuplicates(similarityThresholdFlow: Flow<Float>): Flow<List<DuplicateGroup>> = duplicateDetectionEngine.getVideoDuplicates(dao.getAllActiveFiles(), similarityThresholdFlow)
    fun getDocumentDuplicates(): Flow<List<DuplicateGroup>> = duplicateDetectionEngine.getDocumentDuplicates(dao.getAllActiveFiles())
    fun getSemanticDuplicates(similarityThresholdFlow: Flow<Float>): Flow<List<DuplicateGroup>> = duplicateDetectionEngine.getSemanticDuplicates(dao.getAllActiveFiles(), similarityThresholdFlow)

    val documentStats: Flow<Triple<Int, Int, Float>> = dao.getAllActiveFiles().map { files ->
        val docs = files.filter { it.category == FileCategory.DOCUMENTS.name && !it.isVault && !it.isRecycleBin }
        val total = docs.size
        val indexed = docs.count { it.visualSimilarityHash.isNotBlank() || it.md5Hash.isNotBlank() }
        Triple(indexed, total - indexed, if (total > 0) indexed.toFloat() / total.toFloat() else 1.0f)
    }.flowOn(Dispatchers.Default)

    suspend fun <T> withRetry(
        operation: RetryOperation,
        maxAttempts: Int = RetryDecision.DEFAULT_MAX_ATTEMPTS,
        initialDelayMs: Long = RetryPolicy.INITIAL_DELAY_MS,
        factor: Double = RetryPolicy.BACKOFF_FACTOR,
        block: suspend () -> T
    ): T {
        var lastException: Throwable? = null
        for (attempt in 0 until maxAttempts) {
            try {
                return block()
            } catch (error: Exception) {
                lastException = error
                val decision = RetryPolicy.classify(operation, error)
                Log.w(
                    "SmartManagerRepository",
                    "Retry decision operation=$operation attempt=${attempt + 1} reason=${decision.reasonCode} retryable=${decision.retryable}"
                )
                if (!RetryPolicy.shouldRetry(operation, error, attempt)) throw error
                kotlinx.coroutines.delay(
                    if (factor == RetryPolicy.BACKOFF_FACTOR && initialDelayMs == RetryPolicy.INITIAL_DELAY_MS) {
                        RetryPolicy.delayForAttempt(attempt)
                    } else {
                        (initialDelayMs * factor.pow(attempt)).toLong()
                    }
                )
            }
        }
        throw lastException ?: IllegalStateException("Operation failed without an exception")
    }

    private fun Double.pow(exponent: Int): Double {
        var result = 1.0
        repeat(exponent) { result *= this }
        return result
    }

    open suspend fun insertFiles(files: List<FileItemEntity>) = withContext(Dispatchers.IO) { withRetry(RetryOperation.DATABASE_WRITE) { dao.insertFiles(files) } }

    suspend fun rescanPhysicalStorage(): Int = withContext(Dispatchers.IO) {
        withRetry(RetryOperation.INDEXING) {
            var totalCount = 0
            storageScanner.scanDeviceStorageFlow(computeHashes = false).collect { batch ->
                if (batch.isNotEmpty()) { dao.insertFiles(batch); totalCount += batch.size }
            }
            startIncrementalDuplicateScan()
            totalCount
        }
    }

    /** Imports a user-granted SAF tree in scanner-managed batches without building a full list in the UI layer. */
    suspend fun scanSafTree(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        storageScanner.scanSafTree(treeUri, computeHashes = false) { batch ->
            if (batch.isNotEmpty()) dao.insertFiles(batch)
        }
    }

    suspend fun cleanSelectedDuplicates(selectedIds: Set<Long>) = withContext(Dispatchers.IO) {
        DuplicateManager(dao, context).cleanSelectedDuplicates(selectedIds)
    }

    suspend fun moveToRecycleBin(file: FileItemEntity) = withContext(Dispatchers.IO) {
        val currentFile = dao.getFileById(file.id) ?: return@withContext
        if (currentFile.isRecycleBin) return@withContext
        withRetry(RetryOperation.FILE_STORAGE) {
            val trashResult = PhysicalStorageManager.moveToTrash(context, currentFile.path)
            if (trashResult.isFailure) throw trashResult.exceptionOrNull() ?: java.io.IOException("Failed to move file to trash")
            val newPath = trashResult.getOrThrow()
            val originalPathToKeep = if (currentFile.originalPath.isNotBlank()) currentFile.originalPath else currentFile.path
            dao.updateFile(currentFile.copy(path = newPath, originalPath = originalPathToKeep, isRecycleBin = true, deletedTimestampMs = System.currentTimeMillis()))
        }
    }

    suspend fun restoreFromRecycleBin(file: FileItemEntity) = withContext(Dispatchers.IO) {
        val currentFile = dao.getFileById(file.id) ?: return@withContext
        if (!currentFile.isRecycleBin) return@withContext
        withRetry(RetryOperation.FILE_STORAGE) {
            val targetPath = if (currentFile.originalPath.isNotBlank()) currentFile.originalPath else currentFile.path
            val restoreResult = PhysicalStorageManager.restoreFromTrash(context, currentFile.path, targetPath)
            if (restoreResult.isFailure) throw restoreResult.exceptionOrNull() ?: java.io.IOException("Failed to restore file from trash")
            dao.updateFile(currentFile.copy(path = restoreResult.getOrThrow(), originalPath = "", isRecycleBin = false, deletedTimestampMs = 0L))
        }
    }

    suspend fun deletePermanently(file: FileItemEntity) = withContext(Dispatchers.IO) {
        val currentFile = dao.getFileById(file.id) ?: return@withContext
        withRetry(RetryOperation.FILE_STORAGE) {
            if (!PhysicalStorageManager.deleteFile(context, currentFile.path)) throw java.io.IOException("Failed to physically delete file at ${currentFile.path}")
            dao.deleteFileById(currentFile.id)
        }
    }

    suspend fun emptyRecycleBin() = withContext(Dispatchers.IO) {
        withRetry(RetryOperation.FILE_STORAGE) {
            val trashFiles = dao.getRecycleBinFiles().first()
            var failedCount = 0
            trashFiles.forEach { if (!PhysicalStorageManager.deleteFile(context, it.path)) failedCount++ }
            if (failedCount > 0) throw java.io.IOException("Failed to physically delete $failedCount trash files")
            dao.emptyRecycleBin()
        }
    }

    suspend fun encryptToVault(file: FileItemEntity) = vaultRepository.encryptToVault(file)
    suspend fun unlockFromVault(vaultItem: VaultItemEntity, file: FileItemEntity?): Boolean = vaultRepository.unlockFromVault(vaultItem, file)
    open fun hasVaultPin(): Boolean = vaultRepository.hasVaultPin()
    open fun getStoredVaultPinHash(): String = vaultRepository.getStoredVaultPinHash()
    open fun initializeVaultPin(pin: String): Boolean = vaultRepository.initializeVaultPin(pin)
    open fun verifyVaultPin(inputPin: String, storedHash: String = ""): Boolean = vaultRepository.verifyVaultPin(inputPin, storedHash)
    open fun changeVaultPin(oldPin: String, newPin: String): Boolean = vaultRepository.changeVaultPin(oldPin, newPin)
    open fun unlockVaultWithPin(pin: String): Boolean = vaultRepository.unlockWithPin(pin)
    open fun hasBiometricEnrollment(): Boolean = vaultRepository.hasBiometricEnrollment()
    open fun prepareBiometricEnrollmentCipher() = vaultRepository.prepareBiometricEnrollmentCipher()
    open fun completeBiometricEnrollment(result: androidx.biometric.BiometricPrompt.AuthenticationResult): Boolean =
        vaultRepository.completeBiometricEnrollment(result)
    open fun prepareBiometricUnlockCipher() = vaultRepository.prepareBiometricUnlockCipher()
    open fun completeBiometricUnlock(result: androidx.biometric.BiometricPrompt.AuthenticationResult): Boolean =
        vaultRepository.completeBiometricUnlock(result)
    open fun disableBiometricEnrollment(): Boolean = vaultRepository.disableBiometricEnrollment()
    open fun lockVaultSession() = vaultRepository.lockSession()

    suspend fun getFilteredFilesPaged(category: String?, query: String, limit: Int, offset: Int): List<FileItemEntity> = withContext(Dispatchers.IO) { withRetry(RetryOperation.DATABASE_READ) { fileRepository.getFilteredFilesPaged(category, query, limit, offset) } }
    suspend fun renameFile(file: FileItemEntity, newName: String) = withContext(Dispatchers.IO) { withRetry(RetryOperation.FILE_STORAGE) { fileRepository.renameFile(file, newName) } }
    suspend fun addTagToFile(file: FileItemEntity, tag: String) = withContext(Dispatchers.IO) { withRetry(RetryOperation.DATABASE_WRITE) { fileRepository.addTagToFile(file, tag) } }
    suspend fun togglePlugin(pluginId: String, currentEnabled: Boolean) = withContext(Dispatchers.IO) { withRetry(RetryOperation.DATABASE_WRITE) { pluginRepository.togglePlugin(pluginId, currentEnabled) } }
    fun observeCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = dao.getCloudSyncItems()

    private suspend fun isProviderEnabled(provider: String): Boolean {
        val pluginId = when (provider.uppercase()) {
            "GOOGLE_DRIVE" -> "gdrive_sync"
            "ONEDRIVE" -> "onedrive_sync"
            "DROPBOX" -> "dropbox_sync"
            else -> null
        } ?: return false
        return dao.getAllPlugins().first().find { it.pluginId == pluginId }?.isEnabled == true
    }

    suspend fun enqueueCloudSyncItem(provider: String, fileName: String, size: Long, filePath: String = "", isCore: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!cloudTransferAllowed(context) || !isProviderEnabled(provider)) return@withContext false
        val currentItems = dao.getCloudSyncItems().first()
        val keyPath = if (filePath.isNotBlank()) filePath else fileName
        val duplicate = currentItems.find { it.provider.equals(provider, true) && (if (it.filePath.isNotBlank()) it.filePath else it.fileName) == keyPath && it.status in listOf("PENDING", "QUEUED", "UPLOADING", "SYNCED") }
        if (duplicate != null) return@withContext false
        withRetry(RetryOperation.DATABASE_WRITE) { dao.insertCloudSyncItem(CloudSyncItemEntity(provider = provider, fileName = fileName, filePath = filePath, fileSize = size, status = "QUEUED", lastSyncedMs = System.currentTimeMillis(), isCore = isCore)) }
        workCoordinator.enqueueCloudSyncWork(cloudTransferAllowed(context))
        true
    }

    suspend fun retryCloudSyncItem(id: Long): Boolean = withContext(Dispatchers.IO) {
        val item = dao.getCloudSyncItems().first().find { it.id == id } ?: return@withContext false
        if (
            item.status == "SYNCED" ||
            !cloudTransferAllowed(context) ||
            !isProviderEnabled(item.provider)
        ) return@withContext false
        withRetry(RetryOperation.DATABASE_WRITE) { dao.insertCloudSyncItem(item.copy(status = "QUEUED", lastSyncedMs = System.currentTimeMillis())) }
        workCoordinator.enqueueCloudSyncWork(cloudTransferAllowed(context))
        true
    }

    suspend fun cancelCloudSyncItem(id: Long): Boolean = withContext(Dispatchers.IO) {
        val item = dao.getCloudSyncItems().first().find { it.id == id } ?: return@withContext false
        if (item.status == "SYNCED") return@withContext false
        withRetry(RetryOperation.DATABASE_WRITE) { dao.deleteCloudSyncItem(id) }
        true
    }

    suspend fun addSyncItem(provider: String, fileName: String, size: Long, filePath: String = "") = enqueueCloudSyncItem(provider, fileName, size, filePath)

    fun trimMemory() {
        try {
            if (tfliteProvider is TFLiteSemanticEmbeddingProvider) {
                (tfliteProvider as TFLiteSemanticEmbeddingProvider).close()
            }
        } catch (e: Exception) { Log.e("SmartManagerRepository", "Failed to trim memory", e) }
    }

    /** @deprecated Use [workCoordinator] from the application/domain boundary. */
    @Deprecated("Use WorkCoordinator instead of repository scheduling APIs")
    open fun enqueueDuplicateCleanupWork() = workCoordinator.enqueueDuplicateCleanupWork()

    /** @deprecated Use [workCoordinator] from the application/domain boundary. */
    @Deprecated("Use WorkCoordinator instead of repository scheduling APIs")
    fun enqueueCloudSyncWork() = workCoordinator.enqueueCloudSyncWork(cloudTransferAllowed(context))

    /** @deprecated Use [workCoordinator] from the application/domain boundary. */
    @Deprecated("Use WorkCoordinator instead of repository scheduling APIs")
    fun enqueueCacheCleanupWork() = workCoordinator.enqueueCacheCleanupWork()

    /** @deprecated Use [workCoordinator] from the application/domain boundary. */
    @Deprecated("Use WorkCoordinator instead of repository scheduling APIs")
    open fun enqueueBackgroundIndexWork() = workCoordinator.enqueueBackgroundIndexWork()
}
