@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.example.ui.screens
import com.example.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.CloudSyncItemEntity
import com.example.data.PluginEntity
import com.example.ui.MainViewModel
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.GoogleAuthState

import com.example.ui.theme.BhagwaOrange
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.SkyCyan
import com.example.ui.theme.SoftGold

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Composable
fun CloudPluginsScreen(
    viewModel: MainViewModel,
    cloudSyncItems: List<CloudSyncItemEntity>,
    plugins: List<PluginEntity>
) {
    val keepLocalLabel = stringResource(R.string.keep_local)
    val keepCloudLabel = stringResource(R.string.keep_cloud)
    var conflictResolutionMode by rememberSaveable(keepLocalLabel) { mutableStateOf(keepLocalLabel) }
    var selectedSection by rememberSaveable { mutableIntStateOf(0) } // 0: Cloud Sync, 1: Plugin Manager
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionChip(stringResource(R.string.cloud_providers), 0, selectedSection, SoftGold) { selectedSection = 0 }
            SectionChip(stringResource(R.string.plugin_manager), 1, selectedSection, SkyCyan) { selectedSection = 1 }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (selectedSection == 0) {
            CloudSyncSection(
                viewModel = viewModel,
                syncItems = cloudSyncItems,
                conflictMode = conflictResolutionMode,
                onConflictModeChange = { conflictResolutionMode = it }
            )
        } else {
            PluginManagerSection(
                viewModel = viewModel,
                plugins = plugins
            )
        }
    }
}
@Composable
fun CloudSyncSection(
    viewModel: MainViewModel,
    syncItems: List<CloudSyncItemEntity>,
    conflictMode: String,
    onConflictModeChange: (String) -> Unit
) {
    val googleAuthState by viewModel.googleAuthState.collectAsStateWithLifecycle()
    val keepLocalLabel = stringResource(R.string.keep_local)
    val keepCloudLabel = stringResource(R.string.keep_cloud)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Google Drive Core Banner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val auth = googleAuthState
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (auth is GoogleAuthState.SignedIn) {
                                    Icons.Default.CloudDone
                                } else {
                                    Icons.Default.CloudQueue
                                },
                                contentDescription = stringResource(
                                    if (auth is GoogleAuthState.SignedIn) {
                                        R.string.google_drive_connected
                                    } else {
                                        R.string.google_drive_not_connected
                                    }
                                ),
                                tint = if (auth is GoogleAuthState.SignedIn) EmeraldGreen else SoftGold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.google_drive_provider),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (auth is GoogleAuthState.SignedIn) {
                                EmeraldGreen.copy(alpha = 0.2f)
                            } else {
                                SoftGold.copy(alpha = 0.2f)
                            }
                        ) {
                            Text(
                                text = stringResource(
                                    if (auth is GoogleAuthState.SignedIn) R.string.connected else R.string.not_connected
                                ),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (auth is GoogleAuthState.SignedIn) EmeraldGreen else SoftGold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("google_drive_status_chip")
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    if (auth is GoogleAuthState.SignedIn) {
                        Text(
                            text = stringResource(R.string.cloud_transfer_disabled),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(
                            onClick = { viewModel.signOutFromGoogle() },
                            modifier = Modifier.testTag("google_drive_disconnect_btn")
                        ) {
                            Text(stringResource(R.string.disconnect), color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Image(
                            painter = painterResource(R.drawable.vvf_foundation_logo),
                            contentDescription = stringResource(R.string.vvf_foundation_logo),
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .testTag("vvf_login_brand_logo")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.cloud_sync_oauth_required),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (auth is GoogleAuthState.Error) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.error_prefix, auth.message),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {},
                            enabled = false,
                            colors = ButtonDefaults.buttonColors(containerColor = BhagwaOrange),
                            modifier = Modifier.testTag("google_drive_connect_btn")
                        ) {
                            Text(stringResource(R.string.cloud_sync_disabled))
                        }
                    }
                }
            }
        }
        // Conflict Resolution Settings
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = stringResource(R.string.sync_conflict_resolution), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = stringResource(R.string.strategy_mode), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            onConflictModeChange(
                                if (conflictMode == keepLocalLabel) keepCloudLabel else keepLocalLabel
                            )
                        }) {
                            Text(text = conflictMode, color = BhagwaOrange, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        // Multi-Cloud Sync History Queue
        item {
            Text(
                text = stringResource(R.string.cloud_sync_queue, syncItems.size),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
        items(syncItems, key = { it.id }) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sync_item_${item.id}"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = item.fileName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            text = stringResource(
                                R.string.provider_file_size,
                                item.provider,
                                formatFileSize(item.fileSize, stringResource(R.string.unknown_size))
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val statusLabel = when (item.status) {
                            "PENDING" -> stringResource(R.string.waiting)
                            "QUEUED" -> stringResource(R.string.queued)
                            "UPLOADING" -> stringResource(R.string.uploading)
                            "SYNCED" -> stringResource(R.string.synced)
                            "FAILED" -> stringResource(R.string.failed)
                            "NOT_SUPPORTED" -> stringResource(R.string.not_supported)
                            else -> item.status
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when (item.status) {
                                "SYNCED" -> EmeraldGreen.copy(alpha = 0.2f)
                                "PENDING", "QUEUED" -> SoftGold.copy(alpha = 0.2f)
                                "UPLOADING" -> SkyCyan.copy(alpha = 0.2f)
                                else -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                            }
                        ) {
                            Text(
                                text = statusLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (item.status) {
                                    "SYNCED" -> EmeraldGreen
                                    "PENDING", "QUEUED" -> SoftGold
                                    "UPLOADING" -> SkyCyan
                                    else -> MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        if (item.status == "FAILED") {
                            TextButton(
                                onClick = { viewModel.retryCloudSyncItem(item.id) },
                                modifier = Modifier.testTag("retry_sync_${item.id}")
                            ) {
                                Text(stringResource(R.string.retry), fontSize = 12.sp, color = BhagwaOrange, fontWeight = FontWeight.Bold)
                            }
                        } else if (item.status == "PENDING" || item.status == "QUEUED") {
                            TextButton(
                                onClick = { viewModel.cancelCloudSyncItem(item.id) },
                                modifier = Modifier.testTag("cancel_sync_${item.id}")
                            ) {
                                Text(stringResource(R.string.cancel), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun PluginManagerSection(
    viewModel: MainViewModel,
    plugins: List<PluginEntity>
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Extension, contentDescription = null, tint = SkyCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.plugin_architecture_system), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.plugin_architecture_description),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.registered_extensions, plugins.size),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
        items(plugins, key = { it.pluginId }) { plugin ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = plugin.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            if (plugin.isCore) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = BhagwaOrange.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = stringResource(R.string.core),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BhagwaOrange,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = plugin.description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = plugin.isEnabled,
                        onCheckedChange = { viewModel.togglePlugin(plugin.pluginId, plugin.isEnabled) },
                        colors = SwitchDefaults.colors(checkedThumbColor = BhagwaOrange),
                        modifier = Modifier.testTag("plugin_switch_${plugin.pluginId}")
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionChip(
    title: String,
    index: Int,
    selectedIndex: Int,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    val isSelected = index == selectedIndex
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) color else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.testTag("section_tab_$index")
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}


