@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.FlowPreview::class,
)

package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.R
import com.example.data.FileCategory
import com.example.data.FileItemEntity
import com.example.ui.*
import com.example.ui.MainViewModel
import com.example.ui.components.FilePickerSheet
import com.example.ui.theme.BhagwaOrange
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.SkyCyan
import com.example.utils.formatDate
import com.example.utils.formatFileSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Composable
// Legacy screen coordinates picker, rename, OCR, vault, and recycle-bin state in one boundary.
@Suppress(
    "detekt.LongMethod",
    "detekt.CyclomaticComplexMethod",
)
fun FileManagerScreen(
    viewModel: MainViewModel,
    files: List<FileItemEntity>,
    recycleBinFiles: List<FileItemEntity>,
    selectedCategory: FileCategory?,
    searchQuery: String,
) {
    var showRecycleBin by rememberSaveable { mutableStateOf(false) }
    var showFilePickerSheet by remember { mutableStateOf(false) }
    var renameTargetFile by remember { mutableStateOf<FileItemEntity?>(null) }
    var encryptTargetFile by remember { mutableStateOf<FileItemEntity?>(null) }
    var ocrOverlayFile by remember { mutableStateOf<FileItemEntity?>(null) }
    var ocrOverlayBlocks by remember {
        mutableStateOf<List<com.example.data.OcrTextBlock>>(emptyList())
    }
    var isOcrOverlayLoading by remember { mutableStateOf(false) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var newFileNameText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isPageLoading by viewModel.isPageLoading.collectAsState()
    val persistedFolderUris by viewModel.persistedFolderUris.collectAsState()
    val shouldLoadMore = remember {
        derivedStateOf {
            if (files.isEmpty() && !isPageLoading) return@derivedStateOf true
            val lastVisibleItem =
                listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            viewModel.loadNextPage()
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Action Row & Search Header Bar
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier.weight(1f).testTag("file_search_input"),
                placeholder = { Text(stringResource(R.string.search_by_filename_tag_or_ocr_)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.clear),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { showFilePickerSheet = true },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = BhagwaOrange,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(56.dp).testTag("open_file_picker_btn"),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.pick), fontWeight = FontWeight.Bold)
            }
        }

        // File Picker Bottom Sheet
        FilePickerSheet(
            isOpen = showFilePickerSheet,
            onDismiss = { showFilePickerSheet = false },
            onFilesSelected = { pickedFiles -> viewModel.processPickedLocalFiles(pickedFiles) },
            onUrisSelected = { uris -> viewModel.processPickedUris(uris) },
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { viewModel.selectCategory(null) },
                    label = { Text(stringResource(R.string.all_files)) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BhagwaOrange,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                )
            }
            items(FileCategory.values()) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { viewModel.selectCategory(category) },
                    label = { Text(localizedFileCategoryLabel(category)) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BhagwaOrange,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Recycle Bin Bar Indicator
        Card(
            modifier = Modifier.fillMaxWidth().clickable { showRecycleBin = !showRecycleBin },
            shape = RoundedCornerShape(12.dp),
            colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.recycle_bin),
                        tint = BhagwaOrange,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.recycle_bin_count, recycleBinFiles.size),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                Text(
                    text =
                        if (showRecycleBin) stringResource(R.string.hide_trash)
                        else stringResource(R.string.view_trash),
                    color = BhagwaOrange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Trash Section View
        AnimatedVisibility(visible = showRecycleBin) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.deleted_files_auto_purge),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (recycleBinFiles.isNotEmpty()) {
                        TextButton(onClick = { viewModel.emptyRecycleBin() }) {
                            Text(
                                stringResource(R.string.empty_trash),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (recycleBinFiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.recycle_bin_empty),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    recycleBinFiles.forEach { file ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        text =
                                            formatFileSize(
                                                file.sizeBytes,
                                                stringResource(R.string.unknown_size),
                                            ),
                                        fontSize = 11.sp,
                                    )
                                }
                                Row {
                                    IconButton(
                                        onClick = { viewModel.restoreFromRecycleBin(file) }
                                    ) {
                                        Icon(
                                            Icons.Default.Restore,
                                            contentDescription = stringResource(R.string.restore),
                                            tint = EmeraldGreen,
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deletePermanently(file) }) {
                                        Icon(
                                            Icons.Default.DeleteForever,
                                            contentDescription =
                                                stringResource(R.string.delete_forever),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Local File Picker Component
        LocalFilePickerCard(
            onFilesPicked = { uris -> viewModel.processPickedUris(uris) },
            modifier = Modifier.padding(bottom = 12.dp),
        )
        // SAF Directory Picker Component
        SafDirectoryPickerCard(
            onDirectoryPicked = { uri -> viewModel.processPickedDirectoryUri(uri) },
            persistedFolders = persistedFolderUris,
            onRemoveFolder = { uriStr -> viewModel.removePersistedFolderUri(uriStr) },
            modifier = Modifier.padding(bottom = 12.dp),
        )
        // Active Files List
        Text(
            text = stringResource(R.string.active_storage_files, files.size),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = stringResource(R.string.no_files),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.no_files_category),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.id }) { file ->
                    FileManagerItemRow(
                        modifier = Modifier.animateItem(),
                        file = file,
                        onRename = {
                            renameTargetFile = file
                            newFileNameText = file.name
                        },
                        onEncrypt = { encryptTargetFile = file },
                        onDelete = { viewModel.moveToRecycleBin(file) },
                        onAddTag = { tag -> viewModel.addTagToFile(file, tag) },
                        onOcrOverlay = {
                            ocrOverlayFile = file
                            isOcrOverlayLoading = true
                            coroutineScope.launch {
                                ocrOverlayBlocks = viewModel.extractOcrBlocks(file.path)
                                isOcrOverlayLoading = false
                            }
                        },
                    )
                }
                if (isPageLoading) {
                    item {
                        Box(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = androidx.compose.ui.Modifier.size(24.dp),
                                color = BhagwaOrange,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        }
    }
    // Rename Dialog
    if (renameTargetFile != null) {
        AlertDialog(
            onDismissRequest = { renameTargetFile = null },
            title = { Text(stringResource(R.string.rename_file)) },
            text = {
                OutlinedTextField(
                    value = newFileNameText,
                    onValueChange = { newFileNameText = it },
                    label = { Text(stringResource(R.string.file_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = renameTargetFile
                        if (file != null && newFileNameText.isNotBlank()) {
                            viewModel.renameFile(file, newFileNameText.trim())
                        }
                        renameTargetFile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BhagwaOrange),
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetFile = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    // Encrypt confirmation and Best-Effort Wipe disclaimer dialog
    if (encryptTargetFile != null) {
        AlertDialog(
            onDismissRequest = { encryptTargetFile = null },
            title = { Text(stringResource(R.string.encrypt_best_effort_wipe)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text =
                            stringResource(
                                R.string.encrypt_to_vault_description,
                                encryptTargetFile?.name.orEmpty(),
                            ),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.best_effort_wipe_disclaimer),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        encryptTargetFile?.let { file -> viewModel.encryptToVault(file) }
                        encryptTargetFile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BhagwaOrange),
                ) {
                    Text(stringResource(R.string.encrypt_wipe))
                }
            },
            dismissButton = {
                TextButton(onClick = { encryptTargetFile = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // OCR Overlay Preview Dialog
    if (ocrOverlayFile != null) {
        AlertDialog(
            onDismissRequest = {
                ocrOverlayFile = null
                ocrOverlayBlocks = emptyList()
            },
            title = {
                Text(
                    text = stringResource(R.string.ocr_overlay, ocrOverlayFile?.name.orEmpty()),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                if (isOcrOverlayLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = BhagwaOrange)
                    }
                } else {
                    Column {
                        Text(
                            text =
                                stringResource(
                                    R.string.detected_text_blocks,
                                    ocrOverlayBlocks.size,
                                ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        com.example.ui.components.OcrOverlayImage(
                            filePath = ocrOverlayFile!!.path,
                            ocrBlocks = ocrOverlayBlocks,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ocrOverlayFile = null
                        ocrOverlayBlocks = emptyList()
                    }
                ) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun localizedFileCategoryLabel(category: FileCategory): String =
    when (category) {
        FileCategory.IMAGES -> stringResource(R.string.category_label_images)
        FileCategory.DOCUMENTS -> stringResource(R.string.category_label_documents)
        FileCategory.AUDIO -> stringResource(R.string.category_label_audio)
        FileCategory.VIDEO -> stringResource(R.string.category_label_video)
        FileCategory.DOWNLOADS -> stringResource(R.string.category_label_downloads)
        FileCategory.ARCHIVES -> stringResource(R.string.category_label_archives)
        FileCategory.APKS -> stringResource(R.string.category_label_apks)
        FileCategory.OTHER -> stringResource(R.string.category_label_other)
    }

@Composable
// Keep the row callback surface stable for compatibility callers.
@Suppress("detekt.LongMethod", "detekt.LongParameterList")
fun FileManagerItemRow(
    modifier: Modifier = Modifier,
    file: FileItemEntity,
    onRename: () -> Unit,
    onEncrypt: () -> Unit,
    onDelete: () -> Unit,
    onAddTag: (String) -> Unit,
    onOcrOverlay: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var tagText by remember { mutableStateOf("") }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier.size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector =
                                when (file.category) {
                                    FileCategory.IMAGES.name -> Icons.Default.Image
                                    FileCategory.DOCUMENTS.name -> Icons.Default.Description
                                    FileCategory.AUDIO.name -> Icons.Default.MusicNote
                                    FileCategory.VIDEO.name -> Icons.Default.Movie
                                    else -> Icons.Default.Folder
                                },
                            contentDescription = file.name,
                            tint = BhagwaOrange,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = file.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.format_file_size_category,
                                        formatFileSize(
                                            file.sizeBytes,
                                            stringResource(R.string.unknown_size),
                                        ),
                                        formatDate(file.dateModifiedMs),
                                    ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.menu),
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename_file)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.encrypt_to_vault)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = BhagwaOrange,
                                )
                            },
                            onClick = {
                                showMenu = false
                                onEncrypt()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ocr_overlay_preview)) },
                            leadingIcon = {
                                Icon(Icons.Default.Image, contentDescription = null, tint = SkyCyan)
                            },
                            onClick = {
                                showMenu = false
                                onOcrOverlay()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_tag)) },
                            leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                showTagDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_to_trash)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            // OCR Content or Tag Badge
            if (file.tags.isNotBlank() || file.ocrText.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (file.tags.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = BhagwaOrange.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = stringResource(R.string.tags, file.tags),
                                fontSize = 11.sp,
                                color = BhagwaOrange,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (file.ocrText.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SkyCyan.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = stringResource(R.string.ocr_scanned),
                                fontSize = 11.sp,
                                color = SkyCyan,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (showTagDialog) {
        AlertDialog(
            onDismissRequest = { showTagDialog = false },
            title = { Text(stringResource(R.string.add_tag)) },
            text = {
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    label = { Text(stringResource(R.string.tag_name_e_g_urgent_personal)) },
                    modifier = Modifier.testTag("file_tag_input"),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tagText.isNotBlank()) {
                            onAddTag(tagText.trim())
                        }
                        showTagDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BhagwaOrange),
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTagDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun LocalFilePickerCard(onFilesPicked: (List<Uri>) -> Unit, modifier: Modifier = Modifier) {
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) {
                onFilesPicked(uris)
            }
        }

    Card(
        colors = CardDefaults.cardColors(containerColor = BhagwaOrange.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(16.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { launcher.launch(arrayOf("*/*")) }
                .testTag("local_file_picker_card"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = null,
                    tint = BhagwaOrange,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.pick_local_storage_files),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.select_documents_media),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = BhagwaOrange,
            )
        }
    }
}

@Composable
@Suppress("detekt.LongMethod")
fun SafDirectoryPickerCard(
    onDirectoryPicked: (Uri) -> Unit,
    persistedFolders: Set<String>,
    onRemoveFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launcher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) {
            uri ->
            if (uri != null) {
                onDirectoryPicked(uri)
            }
        }

    Column(modifier = modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = EmeraldGreen.copy(alpha = 0.12f)),
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier.fillMaxWidth()
                    .clickable { launcher.launch(null) }
                    .testTag("saf_directory_picker_card"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = EmeraldGreen,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.link_saf_directory_tree),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.grant_persistable_access),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = EmeraldGreen,
                )
            }
        }

        if (persistedFolders.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.linked_directories, persistedFolders.size),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            persistedFolders.forEach { uriStr ->
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = EmeraldGreen,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val folderName =
                                try {
                                    uriStr.toUri().lastPathSegment ?: uriStr
                                } catch (e: Exception) {
                                    uriStr
                                }
                            Text(
                                text = folderName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { onRemoveFolder(uriStr) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.unlink_folder),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
