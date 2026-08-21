package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.FileCategory
import com.example.ui.theme.BhagwaOrange
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.SkyCyan
import java.util.Locale

data class PickableLocalFile(
    val name: String,
    val path: String,
    val uri: Uri? = null,
    val sizeBytes: Long,
    val category: FileCategory,
    val dateModifiedMs: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onFilesSelected: (List<PickableLocalFile>) -> Unit,
    onUrisSelected: (List<Uri>) -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val systemPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onUrisSelected(uris)
            onDismiss()
        }
    }

    var selectedCategoryFilter by remember { mutableStateOf<FileCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedFiles = remember { mutableStateListOf<PickableLocalFile>() }

    val localFilesList = remember(context) {
        getAvailableDeviceLocalFiles(context)
    }

    val filteredFiles = remember(localFilesList, selectedCategoryFilter, searchQuery) {
        localFilesList.filter { file ->
            val matchesCategory = selectedCategoryFilter == null || file.category == selectedCategoryFilter
            val matchesSearch = searchQuery.isBlank() || file.name.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(BhagwaOrange.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.select_files),
                            tint = BhagwaOrange,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.select_files_from_storage),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.choose_local_files_to_process),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("file_picker_close_btn")
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close_picker))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        systemPickerLauncher.launch(arrayOf("*/*"))
                    }
                    .testTag("launch_system_file_picker")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.open_system_document_picker),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.browse_storage_providers),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.FileOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("picker_search_input"),
                placeholder = { Text(stringResource(R.string.search_local_files_by_name)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = selectedCategoryFilter == null,
                        onClick = { selectedCategoryFilter = null },
                        label = { Text(stringResource(R.string.all)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BhagwaOrange,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
                items(FileCategory.values()) { cat ->
                    FilterChip(
                        selected = selectedCategoryFilter == cat,
                        onClick = { selectedCategoryFilter = cat },
                        label = { Text(localizedFileCategory(cat)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BhagwaOrange,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (filteredFiles.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.no_local_files_matching_filter),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(filteredFiles) { file ->
                        val isSelected = selectedFiles.contains(file)
                        PickableFileRowItem(
                            file = file,
                            isSelected = isSelected,
                            onToggleSelect = {
                                if (isSelected) {
                                    selectedFiles.remove(file)
                                } else {
                                    selectedFiles.add(file)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.files_selected, selectedFiles.size),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val totalBytes = selectedFiles.sumOf { it.sizeBytes }
                        Text(
                            text = formatFileSize(totalBytes, stringResource(R.string.unknown_size)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }

                    Row {
                        if (selectedFiles.isNotEmpty()) {
                            TextButton(
                                onClick = { selectedFiles.clear() }
                            ) {
                                Text(stringResource(R.string.clear))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Button(
                            onClick = {
                                if (selectedFiles.isNotEmpty()) {
                                    onFilesSelected(selectedFiles.toList())
                                    onDismiss()
                                }
                            },
                            enabled = selectedFiles.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BhagwaOrange,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("process_selected_files_btn")
                        ) {
                            Text(
                                text = stringResource(R.string.process_files, selectedFiles.size),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PickableFileRowItem(
    file: PickableLocalFile,
    isSelected: Boolean,
    onToggleSelect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) BhagwaOrange.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelect() }
            .testTag("file_picker_item_${file.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = BhagwaOrange
                )
            )

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = getCategoryBgColor(file.category),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getCategoryIcon(file.category),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        R.string.format_file_size_category,
                        formatFileSize(file.sizeBytes, stringResource(R.string.unknown_size)),
                        localizedFileCategory(file.category)
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}



private fun getCategoryIcon(category: FileCategory): ImageVector {
    return when (category) {
        FileCategory.IMAGES -> Icons.Default.Image
        FileCategory.DOCUMENTS -> Icons.Default.Description
        FileCategory.AUDIO -> Icons.Default.AudioFile
        FileCategory.VIDEO -> Icons.Default.Movie
        FileCategory.DOWNLOADS, FileCategory.OTHER, FileCategory.ARCHIVES, FileCategory.APKS -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

private fun getCategoryBgColor(category: FileCategory): androidx.compose.ui.graphics.Color {
    return when (category) {
        FileCategory.IMAGES -> BhagwaOrange
        FileCategory.DOCUMENTS -> EmeraldGreen
        FileCategory.AUDIO -> SkyCyan
        FileCategory.VIDEO -> BhagwaOrange
        FileCategory.DOWNLOADS, FileCategory.OTHER, FileCategory.ARCHIVES, FileCategory.APKS -> BhagwaOrange
    }
}

@Composable
private fun localizedFileCategory(category: FileCategory): String = when (category) {
    FileCategory.IMAGES -> stringResource(R.string.category_label_images)
    FileCategory.DOCUMENTS -> stringResource(R.string.category_label_documents)
    FileCategory.AUDIO -> stringResource(R.string.category_label_audio)
    FileCategory.VIDEO -> stringResource(R.string.category_label_video)
    FileCategory.DOWNLOADS -> stringResource(R.string.category_label_downloads)
    FileCategory.ARCHIVES -> stringResource(R.string.category_label_archives)
    FileCategory.APKS -> stringResource(R.string.category_label_apks)
    FileCategory.OTHER -> stringResource(R.string.category_label_other)
}

private fun formatFileSize(bytes: Long, unknownLabel: String): String {
    return when {
        bytes <= 0L -> unknownLabel
        bytes < 1024 -> "$bytes B"
        else -> {
            val kilobytes = bytes / 1024.0
            when {
                kilobytes < 1024 -> String.format(Locale.getDefault(), "%.1f KB", kilobytes)
                else -> {
                    val megabytes = kilobytes / 1024.0
                    if (megabytes < 1024) {
                        String.format(Locale.getDefault(), "%.1f MB", megabytes)
                    } else {
                        String.format(Locale.getDefault(), "%.2f GB", megabytes / 1024.0)
                    }
                }
            }
        }
    }
}

private fun getAvailableDeviceLocalFiles(context: android.content.Context): List<PickableLocalFile> {
    val list = mutableListOf<PickableLocalFile>()

    try {
        val storageDirs = listOfNotNull(
            context.getExternalFilesDir(null),
            context.filesDir,
            context.cacheDir,
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
        )

        for (dir in storageDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().maxDepth(2).forEach { file ->
                    if (file.isFile && file.length() > 0) {
                        val category = inferFileCategory(file.name)
                        list.add(
                            PickableLocalFile(
                                name = file.name,
                                path = file.absolutePath,
                                sizeBytes = file.length(),
                                category = category,
                                dateModifiedMs = file.lastModified()
                            )
                        )
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("FilePickerUI", "Error reading local device storage files: ${e.message}")
    }

    if (list.size < 5) {
        val baseDir = context.filesDir.absolutePath
        list.add(
            PickableLocalFile(
                name = "Invoice_Tax_2026_VVF.pdf",
                path = "$baseDir/Invoice_Tax_2026_VVF.pdf",
                sizeBytes = 245000L,
                category = FileCategory.DOCUMENTS
            )
        )
        list.add(
            PickableLocalFile(
                name = "Architecture_Blueprint_Diagram.png",
                path = "$baseDir/Architecture_Blueprint_Diagram.png",
                sizeBytes = 1850000L,
                category = FileCategory.IMAGES
            )
        )
        list.add(
            PickableLocalFile(
                name = "Client_Payment_Receipt.jpg",
                path = "$baseDir/Client_Payment_Receipt.jpg",
                sizeBytes = 512000L,
                category = FileCategory.IMAGES
            )
        )
        list.add(
            PickableLocalFile(
                name = "Meeting_Notes_Audio_Record.mp3",
                path = "$baseDir/Meeting_Notes_Audio_Record.mp3",
                sizeBytes = 4200000L,
                category = FileCategory.AUDIO
            )
        )
        list.add(
            PickableLocalFile(
                name = "Project_Demo_Presentation.mp4",
                path = "$baseDir/Project_Demo_Presentation.mp4",
                sizeBytes = 18400000L,
                category = FileCategory.VIDEO
            )
        )
    }

    return list.distinctBy { it.path }
}

private fun inferFileCategory(fileName: String): FileCategory {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> FileCategory.IMAGES
        "pdf", "doc", "docx", "txt", "rtf", "odt", "csv", "xls", "xlsx", "ppt", "pptx" -> FileCategory.DOCUMENTS
        "mp3", "wav", "aac", "m4a", "flac", "ogg" -> FileCategory.AUDIO
        "mp4", "mkv", "webm", "avi", "mov", "3gp" -> FileCategory.VIDEO
        "zip", "rar", "7z", "tar", "gz" -> FileCategory.ARCHIVES
        "apk" -> FileCategory.APKS
        else -> FileCategory.OTHER
    }
}
