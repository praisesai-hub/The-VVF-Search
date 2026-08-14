package com.example.ui

import com.example.R
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.core.net.toUri
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.ui.components.PickableLocalFile
import com.example.storage.PhysicalStorageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.work.WorkManager

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as com.example.VVFApplication).repository
    private val _globalError = MutableStateFlow<String?>(null)
    val globalError: StateFlow<String?> = _globalError.asStateFlow()
    fun clearGlobalError() { _globalError.value = null }
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("MainViewModel", "Unhandled coroutine exception", throwable)
        _globalError.value = throwable.localizedMessage ?: "A background operation failed. Please try again."
    }

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()
    fun selectTab(index: Int) { _selectedTabIndex.value = index }
    private val _selectedCategory = MutableStateFlow<FileCategory?>(null)
    val selectedCategory: StateFlow<FileCategory?> = _selectedCategory.asStateFlow()
    fun selectCategory(category: FileCategory?) { _selectedCategory.value = category }
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    fun setSearchQuery(query: String) { _searchQuery.value = query }
    val isSemanticSearchAvailable: Boolean = repository.isSemanticSearchAvailable
    private val _semanticQuery = MutableStateFlow("")
    val semanticQuery: StateFlow<String> = _semanticQuery.asStateFlow()
    fun setSemanticQuery(query: String) { _semanticQuery.value = query }
    private val _similarityThreshold = MutableStateFlow(80.0f)
    val similarityThreshold: StateFlow<Float> = _similarityThreshold.asStateFlow()
    fun setSimilarityThreshold(value: Float) { _similarityThreshold.value = value }
    private val appPrefs by lazy { getApplication<Application>().getSharedPreferences("vvf_app_settings", android.content.Context.MODE_PRIVATE) }
    private val _autoCleanDuplicatesBg = MutableStateFlow(appPrefs.getBoolean("auto_clean_duplicates_bg", false))
    val autoCleanDuplicatesBg: StateFlow<Boolean> = _autoCleanDuplicatesBg.asStateFlow()
    fun setAutoCleanDuplicatesBg(enabled: Boolean) {
        _autoCleanDuplicatesBg.value = enabled
        appPrefs.edit { putBoolean("auto_clean_duplicates_bg", enabled) }
    }

    val files: StateFlow<List<FileItemEntity>> = combine(
        searchQuery.debounce(250), selectedCategory
    ) { query, category -> query to category }.flatMapLatest { (query, category) ->
        repository.searchFiles(query, category?.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dashboardStats: StateFlow<List<CategoryStat>> = repository.getCategoryStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val duplicateGroups: StateFlow<List<DuplicateGroup>> = repository.getDuplicateGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val plugins: StateFlow<List<PluginEntity>> = repository.getPlugins()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val cloudSyncItems: StateFlow<List<CloudSyncItemEntity>> = repository.getCloudSyncItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val vaultItems: StateFlow<List<VaultItemEntity>> = repository.getVaultItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun unlockFromVault(vaultItem: VaultItemEntity) {
        viewModelScope.launch(coroutineExceptionHandler) {
            val originalFile = repository.getFileByName(vaultItem.originalName)
            repository.unlockFromVault(vaultItem, originalFile)
        }
    }
    fun renameFile(file: FileItemEntity, newName: String) {
        viewModelScope.launch(coroutineExceptionHandler) { repository.renameFile(file, newName) }
    }
    fun addTagToFile(file: FileItemEntity, tag: String) {
        viewModelScope.launch(coroutineExceptionHandler) { repository.addTagToFile(file, tag) }
    }
    fun togglePlugin(pluginId: String, currentEnabled: Boolean) {
        viewModelScope.launch(coroutineExceptionHandler) { repository.togglePlugin(pluginId, currentEnabled) }
    }

    // Google Auth Foundation: real OAuth integration must call saveSession only after
    // Google's authorization flow returns verified credentials. No local token generation.
    private val googleAuthManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.example.data.GoogleAuthManagerFactory.getInstance(getApplication())
    }
    val googleAuthState: StateFlow<com.example.data.GoogleAuthState> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        googleAuthManager.authState
    }

    fun signInToGoogle(email: String, displayName: String?) {
        _globalError.value = "Google sign-in requires the real OAuth authorization flow; local/mock sign-in is disabled."
    }

    fun signOutFromGoogle() {
        viewModelScope.launch(coroutineExceptionHandler) { googleAuthManager.clearSession() }
    }

    fun syncCloudProvider(provider: String) {
        viewModelScope.launch(coroutineExceptionHandler) {
            repository.addSyncItem(provider, "Sync_Batch_${System.currentTimeMillis() / 1000}.zip", 12_500_000L)
        }
    }

    fun retryCloudSyncItem(id: Long) { viewModelScope.launch(coroutineExceptionHandler) { repository.retryCloudSyncItem(id) } }
    fun cancelCloudSyncItem(id: Long) { viewModelScope.launch(coroutineExceptionHandler) { repository.cancelCloudSyncItem(id) } }
    fun triggerDuplicateCleanupWorker() { repository.enqueueDuplicateCleanupWork() }
    fun triggerCloudSyncWorker() { repository.enqueueCloudSyncWork() }
    fun triggerCacheCleanupWorker() { repository.enqueueCacheCleanupWork() }

    fun processPickedLocalFiles(pickedFiles: List<PickableLocalFile>) {
        viewModelScope.launch(coroutineExceptionHandler) {
            val entities = pickedFiles.map { picked ->
                FileItemEntity(name = picked.name, path = picked.path, category = picked.category.name,
                    sizeBytes = picked.sizeBytes, dateModifiedMs = picked.dateModifiedMs, tags = "Imported")
            }
            repository.insertFiles(entities); resetPagination(); repository.enqueueBackgroundIndexWork()
        }
    }

    @JvmName("processPickedJavaFiles")
    fun processPickedLocalFiles(files: List<java.io.File>) {
        viewModelScope.launch(coroutineExceptionHandler) {
            val entities = files.map { file ->
                FileItemEntity(name = file.name, path = file.absolutePath, category = inferCategoryFromFilename(file.name),
                    sizeBytes = if (file.exists()) file.length() else 0L, tags = "Local_Import")
            }
            repository.insertFiles(entities); resetPagination(); repository.enqueueBackgroundIndexWork()
        }
    }

    fun processPickedUris(uris: List<android.net.Uri>) {
        viewModelScope.launch(coroutineExceptionHandler) {
            val context = getApplication<Application>().applicationContext
            val entities = uris.mapIndexed { index, uri ->
                var fileName = "Picked_File_${System.currentTimeMillis()}_$index.bin"
                var sizeBytes = 0L
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex != -1) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { fileName = PhysicalStorageManager.safeTrashFileName(it) }
                            if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
                        }
                    }
                } catch (_: Exception) {
                    uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { fileName = PhysicalStorageManager.safeTrashFileName(it) }
                }
                if (sizeBytes < 0L) sizeBytes = 0L
                FileItemEntity(name = fileName, path = uri.toString(), category = inferCategoryFromFilename(fileName), sizeBytes = sizeBytes, tags = "SAF_Import")
            }
            repository.insertFiles(entities); resetPagination(); repository.enqueueBackgroundIndexWork()
        }
    }

    suspend fun extractOcrBlocks(filePath: String): List<com.example.data.OcrTextBlock> = repository.activeOcrEngine.extractOcrBlocks(filePath)

    private fun inferCategoryFromFilename(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif" -> FileCategory.IMAGES.name
            "pdf", "doc", "docx", "txt", "csv", "xlsx" -> FileCategory.DOCUMENTS.name
            "mp3", "wav", "m4a", "aac" -> FileCategory.AUDIO.name
            "mp4", "mkv", "webm", "mov" -> FileCategory.VIDEO.name
            else -> FileCategory.OTHER.name
        }
    }

    private val _persistedFolderUris = MutableStateFlow<Set<String>>(appPrefs.getStringSet("persisted_saf_folders", emptySet()) ?: emptySet())
    val persistedFolderUris: StateFlow<Set<String>> = _persistedFolderUris.asStateFlow()
    init { rescanPersistedFolders() }
    fun savePersistedFolderUri(uri: String) {
        val newSet = (appPrefs.getStringSet("persisted_saf_folders", emptySet()) ?: emptySet()).toMutableSet().apply { add(uri) }
        appPrefs.edit { putStringSet("persisted_saf_folders", newSet) }
        _persistedFolderUris.value = newSet
    }
    fun getPersistedFolderUris(): Set<String> = _persistedFolderUris.value
    fun removePersistedFolderUri(uri: String) {
        val context = getApplication<Application>().applicationContext
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { android.util.Log.e("MainViewModel", "Error releasing persistable permission for $uri: ${e.message}", e) }
        val newSet = (appPrefs.getStringSet("persisted_saf_folders", emptySet()) ?: emptySet()).toMutableSet().apply { remove(uri) }
        appPrefs.edit { putStringSet("persisted_saf_folders", newSet) }
        _persistedFolderUris.value = newSet
    }
    fun rescanPersistedFolders() {
        viewModelScope.launch(coroutineExceptionHandler) {
            val context = getApplication<Application>().applicationContext
            val entities = mutableListOf<FileItemEntity>()
            for (uriStr in getPersistedFolderUris()) {
                try {
                    val treeFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uriStr.toUri())
                    if (treeFile != null && treeFile.isDirectory) scanDocumentFileRecursively(context, treeFile, entities)
                } catch (e: Exception) { android.util.Log.e("MainViewModel", "Error scanning persisted SAF folder $uriStr: ${e.message}", e) }
            }
            if (entities.isNotEmpty()) { repository.insertFiles(entities); resetPagination(); repository.enqueueBackgroundIndexWork() }
        }
    }
    fun processPickedDirectoryUri(uri: android.net.Uri) {
        viewModelScope.launch(coroutineExceptionHandler) {
            val context = getApplication<Application>().applicationContext
            try {
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags); savePersistedFolderUri(uri.toString())
                val treeFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                if (treeFile != null && treeFile.isDirectory) {
                    val entities = mutableListOf<FileItemEntity>(); scanDocumentFileRecursively(context, treeFile, entities)
                    if (entities.isNotEmpty()) { repository.insertFiles(entities); resetPagination(); repository.enqueueBackgroundIndexWork() }
                }
            } catch (e: Exception) { android.util.Log.e("MainViewModel", "Error taking persistable permission/scanning SAF tree: ${e.message}", e) }
        }
    }
    private fun scanDocumentFileRecursively(context: android.content.Context, dir: androidx.documentfile.provider.DocumentFile, outList: MutableList<FileItemEntity>) {
        for (file in dir.listFiles()) {
            if (file.isDirectory) scanDocumentFileRecursively(context, file, outList)
            else if (file.isFile && file.length() > 0) outList.add(FileItemEntity(name = file.name ?: "Unknown", path = file.uri.toString(), category = inferCategoryFromFilename(file.name ?: ""), sizeBytes = file.length(), dateModifiedMs = file.lastModified(), tags = "SAF_Directory_Import"))
        }
    }

    private var currentPage = 0
    private fun resetPagination() { currentPage = 0 }
}
