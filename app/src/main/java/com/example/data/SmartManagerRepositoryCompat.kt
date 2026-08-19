package com.example.data

import com.example.ai.SearchTextTokenizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Compatibility facade for UI consumers while the repository is being
 * incrementally migrated toward the consolidated repository API.
 */
@Deprecated("Compatibility façade; migrate to canonical repository/use-case APIs")
fun SmartManagerRepository.searchFiles(
    query: String,
    category: String?
): Flow<List<FileItemEntity>> = activeFiles.map { files ->
    val normalizedQuery = SearchTextTokenizer.normalize(query)
    files.filter { file ->
        (category == null || file.category == category) &&
            (normalizedQuery.isBlank() ||
                SearchTextTokenizer.containsQuery(file.name, normalizedQuery) ||
                SearchTextTokenizer.containsQuery(file.ocrText, normalizedQuery) ||
                SearchTextTokenizer.containsQuery(file.tags, normalizedQuery))
    }
}

@Deprecated("Compatibility façade; migrate to canonical repository/use-case APIs")
fun SmartManagerRepository.getCategoryStats(): Flow<List<CategoryStat>> = categoryStats

@Deprecated("Compatibility façade; migrate to canonical repository/use-case APIs")
fun SmartManagerRepository.getDuplicateGroups(): Flow<List<DuplicateGroup>> = exactDuplicates

@Deprecated("Compatibility façade; migrate to canonical repository/use-case APIs")
fun SmartManagerRepository.getPlugins(): Flow<List<PluginEntity>> = plugins

@Deprecated("Compatibility façade; migrate to canonical repository/use-case APIs")
fun SmartManagerRepository.getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = cloudSyncItems

@Deprecated("Compatibility façade; migrate to canonical repository/use-case APIs")
fun SmartManagerRepository.getVaultItems(): Flow<List<VaultItemEntity>> = vaultItems
