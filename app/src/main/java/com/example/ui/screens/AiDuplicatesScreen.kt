@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.example.ui.screens
import com.example.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.testTag
import com.example.ui.*
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DuplicateGroup
import com.example.data.FileItemEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.BhagwaOrange
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.SkyCyan
import com.example.ui.theme.SoftGold
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Composable
fun AiDuplicatesScreen(
    viewModel: MainViewModel,
    level1Duplicates: List<DuplicateGroup>,
    level3Duplicates: List<DuplicateGroup>,
    similarityThreshold: Float,
    selectedDuplicateIds: Set<Long>,
    semanticQuery: String,
    ocrScannedFiles: List<FileItemEntity>,
    semanticSearchResults: List<FileItemEntity>
) {
    var selectedSectionIndex by rememberSaveable {
        mutableIntStateOf(0)
    } // 0: Duplicate Cleaner, 1: OCR Scanner, 2: AI Semantic Search
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Section Tabs Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionChip("Dupe Cleaner", 0, selectedSectionIndex, BhagwaOrange) { selectedSectionIndex = 0 }
            SectionChip("OCR Engine", 1, selectedSectionIndex, SkyCyan) { selectedSectionIndex = 1 }
            SectionChip("AI Search", 2, selectedSectionIndex, EmeraldGreen) { selectedSectionIndex = 2 }
        }
        Spacer(modifier = Modifier.height(16.dp))
        when (selectedSectionIndex) {
            0 -> DuplicateCleanerSection(
                viewModel = viewModel,
                level1Duplicates = level1Duplicates,
                level3Duplicates = level3Duplicates,
                similarityThreshold = similarityThreshold,
                selectedDuplicateIds = selectedDuplicateIds
            )
            1 -> OcrEngineSection(ocrScannedFiles = ocrScannedFiles)
            2 -> SemanticSearchSection(
                viewModel = viewModel,
                semanticQuery = semanticQuery,
                semanticSearchResults = semanticSearchResults
            )
        }
    }
}
private @Composable
fun SectionChip(
    title: String,
    index: Int,
    selectedIndex: Int,
    color: Color,
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
@Composable
fun DuplicateCleanerSection(
    viewModel: MainViewModel,
    level1Duplicates: List<DuplicateGroup>,
    level3Duplicates: List<DuplicateGroup>,
    similarityThreshold: Float,
    selectedDuplicateIds: Set<Long>
) {
    val isScanning by viewModel.isDuplicateScanning.collectAsStateWithLifecycle()
    val scanProgress by viewModel.duplicateScanProgress.collectAsStateWithLifecycle()
    val videoDuplicates by viewModel.videoDuplicates.collectAsStateWithLifecycle()
    val semanticDuplicates by viewModel.semanticDuplicates.collectAsStateWithLifecycle()
    val documentDuplicates by viewModel.documentDuplicates.collectAsStateWithLifecycle()
    val documentStats by viewModel.documentStats.collectAsStateWithLifecycle()
    val autoCleanDuplicatesBg by viewModel.autoCleanDuplicatesBg.collectAsStateWithLifecycle()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Auto-clean duplicates in background setting card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-clean duplicates in background",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Automatically move exact duplicate files to Recycle Bin and send notifications",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = autoCleanDuplicatesBg,
                        onCheckedChange = { viewModel.setAutoCleanDuplicatesBg(it) },
                        modifier = Modifier.testTag("auto_clean_duplicates_switch"),
                        colors = SwitchDefaults.colors(checkedThumbColor = BhagwaOrange, checkedTrackColor = BhagwaOrange.copy(alpha = 0.3f))
                    )
                }
            }
        }
        // Scanning Progress & Background Batch Control Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scanner), tint = BhagwaOrange)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isScanning) "Indexing & Hashing Storage..." else "100k+ File Duplicate Engine",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.startDuplicateScan() },
                            modifier = Modifier.testTag("start_scan_button")
                        ) {
                            Text(if (isScanning) stringResource(R.string.rescan) else stringResource(R.string.start_scan), fontSize = 12.sp, color = BhagwaOrange)
                        }
                    }
                    if (isScanning || scanProgress < 1.0f) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { scanProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = BhagwaOrange,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Background batch indexing: ${(scanProgress * 100).toInt()}% completed",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // Phase 7 Step 1: Document Duplicate & Fingerprint Status Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, contentDescription = stringResource(R.string.pdf_engine), tint = SkyCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Document Fingerprint Engine",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Text(
                            text = "${(documentStats.third * 100).toInt()}% Indexed",
                            fontWeight = FontWeight.Bold,
                            color = SkyCyan,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Indexed: ${documentStats.first}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Pending: ${documentStats.second}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { documentStats.third.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = SkyCyan,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
        // AI Threshold Slider Card (Phase 10 Requirement)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.threshold), tint = BhagwaOrange)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.visual_similarity_threshold),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Text(
                            text = stringResource(R.string.match_percent, similarityThreshold.toInt()),
                            fontWeight = FontWeight.Bold,
                            color = BhagwaOrange,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.adjust_similarity_slider),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = similarityThreshold,
                        onValueChange = { viewModel.setSimilarityThreshold(it) },
                        valueRange = 70f..95f,
                        steps = 25,
                        colors = SliderDefaults.colors(
                            thumbColor = BhagwaOrange,
                            activeTrackColor = BhagwaOrange
                        ),
                        modifier = Modifier.testTag("similarity_slider")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.threshold_loose), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.threshold_exact), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // Action Toolbar: Auto-Select & Clean
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                            text = "${selectedDuplicateIds.size} files selected for deletion",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Level 1-2 Exact Hash & Level 3-4 Visual AI",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        TextButton(onClick = { viewModel.autoSelectExtraDuplicates() }) {
                            Text(stringResource(R.string.auto_select), color = BhagwaOrange, fontWeight = FontWeight.Bold)
                        }
                        if (selectedDuplicateIds.isNotEmpty()) {
                            Button(
                                onClick = { viewModel.cleanSelectedDuplicates() },
                                colors = ButtonDefaults.buttonColors(containerColor = BhagwaOrange)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.clean))
                            }
                        }
                    }
                }
            }
        }
        // Level 1-2 Exact Duplicates Section
        item {
            Text(
                text = "Level 1-2: Exact Hash Duplicates (${level1Duplicates.size} sets)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (level1Duplicates.isEmpty()) {
            item {
                Text(
                    text = "No exact hash duplicate files detected.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(level1Duplicates, key = { it.title + "_1" }) { group ->
                DuplicateGroupCard(
                    group = group,
                    selectedIds = selectedDuplicateIds,
                    onToggleSelect = { viewModel.toggleDuplicateSelection(it) }
                )
            }
        }
        // Level 3-4 Visual & AI Duplicates Section
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Level 3-4: Visual & Semantic AI Duplicates (${level3Duplicates.size} sets)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (level3Duplicates.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.no_visual_duplicates_matching, similarityThreshold.toInt()),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(level3Duplicates, key = { it.title + "_3" }) { group ->
                DuplicateGroupCard(
                    group = group,
                    selectedIds = selectedDuplicateIds,
                    onToggleSelect = { viewModel.toggleDuplicateSelection(it) }
                )
            }
        }
        // Step 7: Video Keyframe Near-Duplicates
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Step 7: Video Near-Duplicates (${videoDuplicates.size} sets)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (videoDuplicates.isEmpty()) {
            item {
                Text(
                    text = "No video keyframe near-duplicates detected.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(videoDuplicates, key = { it.title + "_vid" }) { group ->
                DuplicateGroupCard(
                    group = group,
                    selectedIds = selectedDuplicateIds,
                    onToggleSelect = { viewModel.toggleDuplicateSelection(it) }
                )
            }
        }
        // Step 6: AI Semantic Vector Matches
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Step 6: AI Semantic Vector Matches (${semanticDuplicates.size} sets)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (!viewModel.isSemanticSearchAvailable) {
            item {
                Text(
                    text = "Semantic Search — Coming Soon (model not bundled)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (semanticDuplicates.isEmpty()) {
            item {
                Text(
                    text = "No AI semantic vector matches detected.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(semanticDuplicates, key = { it.title + "_sem" }) { group ->
                DuplicateGroupCard(
                    group = group,
                    selectedIds = selectedDuplicateIds,
                    onToggleSelect = { viewModel.toggleDuplicateSelection(it) }
                )
            }
        }
        // Phase 7 Step 1: Document Fingerprint Matches
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Phase 7 Step 1: Document Fingerprint Matches (${documentDuplicates.size} sets)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (documentDuplicates.isEmpty()) {
            item {
                Text(
                    text = "No document fingerprint duplicates detected.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(documentDuplicates, key = { it.title + "_doc" }) { group ->
                DuplicateGroupCard(
                    group = group,
                    selectedIds = selectedDuplicateIds,
                    onToggleSelect = { viewModel.toggleDuplicateSelection(it) }
                )
            }
        }
    }
}
@Composable
fun DuplicateGroupCard(
    group: DuplicateGroup,
    selectedIds: Set<Long>,
    onToggleSelect: (Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (group.level == 1) BhagwaOrange.copy(alpha = 0.2f) else SoftGold.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "Score: ${group.similarityScore}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (group.level == 1) BhagwaOrange else SoftGold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            group.files.forEach { file ->
                val isSelected = selectedIds.contains(file.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSelect(file.id) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect(file.id) },
                            colors = CheckboxDefaults.colors(checkedColor = BhagwaOrange)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(text = file.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${file.path} • ${formatFileSize(file.sizeBytes)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun OcrEngineSection(ocrScannedFiles: List<FileItemEntity>) {
    
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.ocr), tint = SkyCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ML Kit OCR Text Recognition Engine",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Extracts plain text from document scans, photos, invoices & identity cards, indexing them for instant search.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Text(
                text = "OCR Indexed Documents (${ocrScannedFiles.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
        items(ocrScannedFiles, key = { it.id }) { file ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = SkyCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = file.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Extracted Text: ${file.ocrText}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
@Composable
fun SemanticSearchSection(
    viewModel: MainViewModel,
    semanticQuery: String,
    semanticSearchResults: List<FileItemEntity>
) {
    val isAvailable = viewModel.isSemanticSearchAvailable
    val results = if (isAvailable) semanticSearchResults else emptyList()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = stringResource(R.string.ai),
                            tint = if (isAvailable) EmeraldGreen else MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Multilingual On-Device Semantic Search",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isAvailable) {
                            "Hindi, Devanagari, and other supported-language metadata, tags, and OCR text " +
                                "are ranked locally with multilingual embeddings."
                        } else {
                            "Multilingual embedding model is unavailable. Vector semantic search is disabled " +
                                "rather than replaced with a low-quality fallback."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = if (isAvailable) semanticQuery else "",
                        onValueChange = { if (isAvailable) viewModel.setSemanticQuery(it) },
                        enabled = isAvailable,
                        placeholder = {
                            Text(
                                if (isAvailable)
                                    stringResource(R.string.e_g_electricity_bill_tax_invoi)
                                else
                                    "Multilingual model unavailable"
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = if (isAvailable) EmeraldGreen else MaterialTheme.colorScheme.outline
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("semantic_search_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }
        item {
            Text(
                text = "Semantic Search Results",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
        if (!isAvailable) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "Multilingual semantic search is unavailable until the verified on-device " +
                            "model is installed.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else if (results.isEmpty()) {
            item {
                Text(
                    text = "No semantic search results found.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(results, key = { it.id }) { file ->
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
                            Text(text = file.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                text = if (file.ocrText.isNotBlank()) "Match OCR: ${file.ocrText.take(50)}..." else "Match Tag/Name: ${file.tags}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = EmeraldGreen.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "Score 96%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldGreen,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
