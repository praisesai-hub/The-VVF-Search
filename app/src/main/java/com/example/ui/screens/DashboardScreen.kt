@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.example.ui.screens
import com.example.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.testTag
import com.example.ui.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.FileCategory
import com.example.data.FileItemEntity
import com.example.data.CategoryStat
import com.example.ui.MainViewModel
import com.example.ui.theme.BhagwaOrange
import com.example.ui.theme.CosmicBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.SkyCyan
import com.example.ui.theme.SoftGold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.filled.FolderOpen
import com.example.ui.components.FilePickerSheet

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    categoryStats: List<CategoryStat>,
    recentFiles: List<FileItemEntity>,
    onNavigateTab: (Int) -> Unit
) {
    var showRoadmapModal by rememberSaveable { mutableStateOf(false) }
    var showFilePickerSheet by remember { mutableStateOf(false) }
    val totalSize = remember(categoryStats) { categoryStats.sumOf { it.totalSize } }
    val unknownSizeLabel = stringResource(R.string.unknown_size)
    val formattedTotalSize = remember(totalSize, unknownSizeLabel) { formatFileSize(totalSize, unknownSizeLabel) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Health Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_health_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(CosmicBlue, MaterialTheme.colorScheme.surfaceVariant)
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Verified,
                                        contentDescription = stringResource(R.string.vvf_verified),
                                        tint = BhagwaOrange,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(
                                            R.string.dashboard_title,
                                            stringResource(R.string.app_name)
                                        ),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.system_health),
                                    fontSize = 13.sp,
                                    color = EmeraldGreen,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            // Cold start benchmark badge (<10s)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SoftGold.copy(alpha = 0.2f),
                                modifier = Modifier.clickable { showRoadmapModal = !showRoadmapModal }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = stringResource(R.string.speed),
                                        tint = SoftGold,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.start_benchmark),
                                        fontSize = 11.sp,
                                        color = SoftGold,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        // Storage Usage Progress
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.storage_used, formattedTotalSize),
                                fontSize = 14.sp,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.free_storage),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { 0.72f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = BhagwaOrange,
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                    }
                }
            }
        }
        // Quick Actions Grid
        item {
            Text(
                text = stringResource(R.string.quick_actions),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButton(
                    title = stringResource(R.string.pick_files),
                    subtitle = stringResource(R.string.import_storage),
                    icon = Icons.Default.FolderOpen,
                    color = BhagwaOrange,
                    modifier = Modifier.weight(1f)
                ) { showFilePickerSheet = true }
                QuickActionButton(
                    title = stringResource(R.string.clean_dupes),
                    subtitle = stringResource(R.string.level_1_4_ai),
                    icon = Icons.Default.CleaningServices,
                    color = EmeraldGreen,
                    modifier = Modifier.weight(1f)
                ) { onNavigateTab(3) }
                QuickActionButton(
                    title = stringResource(R.string.secure_vault),
                    subtitle = stringResource(R.string.encrypted),
                    icon = Icons.Default.Lock,
                    color = SkyCyan,
                    modifier = Modifier.weight(1f)
                ) { onNavigateTab(2) }
                QuickActionButton(
                    title = stringResource(R.string.cloud_sync),
                    subtitle = stringResource(R.string.multi_cloud),
                    icon = Icons.Default.CloudSync,
                    color = SoftGold,
                    modifier = Modifier.weight(1f)
                ) { onNavigateTab(4) }
            }

            FilePickerSheet(
                isOpen = showFilePickerSheet,
                onDismiss = { showFilePickerSheet = false },
                onFilesSelected = { pickedFiles ->
                    viewModel.processPickedLocalFiles(pickedFiles)
                },
                onUrisSelected = { uris ->
                    viewModel.processPickedUris(uris)
                }
            )
        }
        // Roadmap Compliance Header
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRoadmapModal = !showRoadmapModal },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = stringResource(R.string.audit),
                            tint = BhagwaOrange,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.master_roadmap_compliance),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.roadmap_audited),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = if (showRoadmapModal) {
                            stringResource(R.string.hide)
                        } else {
                            stringResource(R.string.view_report)
                        },
                        fontSize = 12.sp,
                        color = BhagwaOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        // Expanded Roadmap Compliance Report
        item {
            AnimatedVisibility(visible = showRoadmapModal) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.golden_rule_audit_report),
                            fontWeight = FontWeight.Bold,
                            color = BhagwaOrange,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = stringResource(R.string.phase_3_kotlin_jetpack_compose), fontSize = 12.sp)
                        Text(text = stringResource(R.string.phase_4_encrypted_vault_struct), fontSize = 12.sp)
                        Text(text = stringResource(R.string.phase_5_core_file_manager_leve), fontSize = 12.sp)
                        Text(text = stringResource(R.string.phase_8_ocr_engine_text_extrac), fontSize = 12.sp)
                        Text(text = stringResource(R.string.phase_9_ai_semantic_search_on_), fontSize = 12.sp)
                        Text(text = stringResource(R.string.phase_10_duplicate_level_3_4_w), fontSize = 12.sp)
                        Text(text = stringResource(R.string.phase_11_multi_cloud_sync_goog), fontSize = 12.sp)
                        Text(text = stringResource(R.string.phase_13_launcher_icon_golden_), fontSize = 12.sp)
                        Text(text = stringResource(R.string.phase_14_plugin_system_archite), fontSize = 12.sp)
                        Text(text = stringResource(R.string.phase_15_cold_start_target_10s), fontSize = 12.sp)
                    }
                }
            }
        }
        // Storage Breakdown Categories
        item {
            Text(
                text = stringResource(R.string.storage_categories),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryRow(stringResource(R.string.category_images), FileCategory.IMAGES, categoryStats, Icons.Default.Image, BhagwaOrange) {
                    viewModel.selectCategory(FileCategory.IMAGES)
                    onNavigateTab(1)
                }
                CategoryRow(stringResource(R.string.category_documents), FileCategory.DOCUMENTS, categoryStats, Icons.Default.Description, SkyCyan) {
                    viewModel.selectCategory(FileCategory.DOCUMENTS)
                    onNavigateTab(1)
                }
                CategoryRow(stringResource(R.string.category_audio_files), FileCategory.AUDIO, categoryStats, Icons.Default.MusicNote, EmeraldGreen) {
                    viewModel.selectCategory(FileCategory.AUDIO)
                    onNavigateTab(1)
                }
                CategoryRow(stringResource(R.string.category_videos), FileCategory.VIDEO, categoryStats, Icons.Default.Movie, SoftGold) {
                    viewModel.selectCategory(FileCategory.VIDEO)
                    onNavigateTab(1)
                }
                CategoryRow(stringResource(R.string.category_archives_downloads), FileCategory.ARCHIVES, categoryStats, Icons.Default.Folder, CosmicBlue) {
                    viewModel.selectCategory(FileCategory.ARCHIVES)
                    onNavigateTab(1)
                }
            }
        }
        // Recent Files
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.recent_storage_files),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                TextButton(onClick = { onNavigateTab(1) }) {
                    Text(stringResource(R.string.view_all), color = BhagwaOrange, fontWeight = FontWeight.Bold)
                }
            }
        }
        items(recentFiles.take(5), key = { it.id }) { file ->
            DashboardFileCard(
                file = file,
                onEncrypt = { viewModel.encryptToVault(file) },
                onDelete = { viewModel.moveToRecycleBin(file) },
                onAddTag = { tag -> viewModel.addTagToFile(file, tag) }
            )
        }
    }
}
@Composable
fun QuickActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = title, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(text = subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
fun CategoryRow(
    title: String,
    category: FileCategory,
    categoryStats: List<CategoryStat>,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    val stat = categoryStats.find { it.category == category.name }
    val count = stat?.count ?: 0
    val size = stat?.totalSize ?: 0L
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = title, tint = color, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = pluralStringResource(R.plurals.file_count, count, count),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = formatFileSize(size, stringResource(R.string.unknown_size)),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = BhagwaOrange
            )
        }
    }
}
@Composable
fun DashboardFileCard(
    file: FileItemEntity,
    onEncrypt: () -> Unit,
    onDelete: () -> Unit,
    onAddTag: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (file.category) {
                            FileCategory.IMAGES.name -> Icons.Default.Image
                            FileCategory.DOCUMENTS.name -> Icons.Default.Description
                            FileCategory.AUDIO.name -> Icons.Default.MusicNote
                            FileCategory.VIDEO.name -> Icons.Default.Movie
                            else -> Icons.Default.Folder
                        },
                        contentDescription = file.name,
                        tint = BhagwaOrange
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = file.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatFileSize(file.sizeBytes, stringResource(R.string.unknown_size)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (file.tags.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.tag_prefix, file.tags),
                                fontSize = 11.sp,
                                color = SkyCyan,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.encrypt_to_vault)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onEncrypt()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_custom_tag)) },
                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            showTagDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_to_trash)) },
                        leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
    if (showTagDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTagDialog = false },
            title = { Text(stringResource(R.string.add_tag_to_file, file.name)) },
            text = {
                OutlinedTextField(
                    value = newTagText,
                    onValueChange = { newTagText = it },
                    label = { Text(stringResource(R.string.tag_name_e_g_tax_work)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTagText.isNotBlank()) {
                        onAddTag(newTagText.trim())
                    }
                    showTagDialog = false
                    newTagText = ""
                }) {
                    Text(stringResource(R.string.add_tag))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTagDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
fun formatFileSize(bytes: Long, unknownLabel: String): String {
    return when {
        bytes <= 0L -> unknownLabel
        bytes < 1024 -> "$bytes B"
        else -> {
            val exponent = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
            val prefix = "KMGTPE"[exponent - 1]
            String.format(
                Locale.getDefault(),
                "%.1f %cB",
                bytes / Math.pow(1024.0, exponent.toDouble()),
                prefix
            )
        }
    }
}
fun formatDate(ms: Long): String {
    val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    return formatter.format(Date(ms))
}
