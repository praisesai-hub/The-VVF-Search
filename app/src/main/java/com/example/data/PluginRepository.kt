package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class PluginRepository(
    private val dao: FileDao
) {
    fun getAllPlugins(): Flow<List<PluginEntity>> = dao.getAllPlugins()

    suspend fun togglePlugin(pluginId: String, currentEnabled: Boolean) = withContext(Dispatchers.IO) {
        if (!CloudProviderCapabilities.isImplementedPlugin(pluginId)) return@withContext
        dao.setPluginEnabled(pluginId, !currentEnabled)
    }
}
