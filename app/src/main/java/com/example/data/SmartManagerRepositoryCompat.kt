package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Compatibility facade for UI consumers while the repository is being
 * incrementally migrated toward the consolidated repository API.
 */
fun SmartManagerRepository.searchFiles(
    query: String,
    category: String?
): Flow<List<FileItemEntity>> = activeFiles.map { files ->
    files.filter { file ->
        (category == null || file.category == category) &&
            (query.isBlank() ||
                file.name.contains(query, ignoreCase = true) ||
                file.ocrText.contains(query, ignoreCase = true) ||
                file.tags.contains(query, ignoreCase = true))
    }
}

fun SmartManagerRepository.getCategoryStats(): Flow<List<CategoryStat>> = categoryStats

fun SmartManagerRepository.getDuplicateGroups(): Flow<List<DuplicateGroup>> = exactDuplicates

fun SmartManagerRepository.getPlugins(): Flow<List<PluginEntity>> = plugins

fun SmartManagerRepository.getCloudSyncItems(): Flow<List<CloudSyncItemEntity>> = cloudSyncItems

fun SmartManagerRepository.getVaultItems(): Flow<List<VaultItemEntity>> = vaultItems
