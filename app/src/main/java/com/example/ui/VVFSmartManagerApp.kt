@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.example.ui
import com.example.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.AiDuplicatesScreen
import com.example.ui.screens.CloudPluginsScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.FileManagerScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.theme.BhagwaOrange
import com.example.ui.theme.CosmicBlue
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VVFSmartManagerApp(viewModel: MainViewModel) {
    val selectedTabIndex by viewModel.selectedTabIndex.collectAsStateWithLifecycle()
    val allFiles by viewModel.filteredFiles.collectAsStateWithLifecycle()
        val categoryStats by viewModel.categoryStats.collectAsStateWithLifecycle()
    val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle()
    val recycleBinFiles by viewModel.recycleBinFiles.collectAsStateWithLifecycle()
    val vaultItems by viewModel.vaultItems.collectAsStateWithLifecycle()
    val isVaultUnlocked by viewModel.isVaultUnlocked.collectAsStateWithLifecycle()
    val vaultAutoLockTimeoutMs by viewModel.vaultAutoLockTimeoutMs.collectAsStateWithLifecycle()
    val vaultActivityGeneration by viewModel.vaultActivityGeneration.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.lockVaultForBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isVaultUnlocked, vaultAutoLockTimeoutMs, vaultActivityGeneration) {
        if (isVaultUnlocked) {
            delay(vaultAutoLockTimeoutMs)
            if (viewModel.isVaultUnlocked.value) viewModel.lockVault()
        }
    }
    val enteredPin by viewModel.enteredPin.collectAsStateWithLifecycle()
    val pinError by viewModel.pinError.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val semanticQuery by viewModel.semanticQuery.collectAsStateWithLifecycle()
    val similarityThreshold by viewModel.similarityThreshold.collectAsStateWithLifecycle()
    val level1Duplicates by viewModel.level1ExactDuplicates.collectAsStateWithLifecycle()
    val level3Duplicates by viewModel.level3VisualDuplicates.collectAsStateWithLifecycle()
    val selectedDuplicateIds by viewModel.selectedDuplicateIds.collectAsStateWithLifecycle()
    val cloudSyncItems by viewModel.cloudSyncItems.collectAsStateWithLifecycle()
    val ocrScannedFiles by viewModel.ocrScannedFiles.collectAsStateWithLifecycle()
    val semanticSearchResults by viewModel.semanticSearchResults.collectAsStateWithLifecycle()
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val globalError by viewModel.globalError.collectAsStateWithLifecycle()
    val snackbarHostState = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(globalError) {
        globalError?.let { errorMsg ->
            val result = snackbarHostState.showSnackbar(
                message = errorMsg,
                actionLabel = "Retry",
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                viewModel.rescanPhysicalStorage()
            }
            viewModel.clearGlobalError()
        }
    }
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(BhagwaOrange),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.vvf_foundation_logo),
                                contentDescription = stringResource(R.string.vvf_logo),
                                tint = androidx.compose.ui.graphics.Color.Unspecified,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "VVF Smart Manager",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CosmicBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White
                ),
                actions = {
                    androidx.compose.material3.IconButton(
                        onClick = { viewModel.selectTab(5) },
                        modifier = Modifier.testTag("about_menu_item")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.about),
                            tint = androidx.compose.ui.graphics.Color.White
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CosmicBlue,
                contentColor = androidx.compose.ui.graphics.Color.White
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { viewModel.selectTab(0) },
                    icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.home)) },
                    label = { Text(stringResource(R.string.home)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = BhagwaOrange, selectedTextColor = BhagwaOrange),
                    modifier = Modifier.testTag("nav_tab_0")
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { viewModel.selectTab(1) },
                    icon = { Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.files)) },
                    label = { Text(stringResource(R.string.files)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = BhagwaOrange, selectedTextColor = BhagwaOrange),
                    modifier = Modifier.testTag("nav_tab_1")
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 2,
                    onClick = { viewModel.selectTab(2) },
                    icon = { Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.vault)) },
                    label = { Text(stringResource(R.string.vault)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = BhagwaOrange, selectedTextColor = BhagwaOrange),
                    modifier = Modifier.testTag("nav_tab_2")
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 3,
                    onClick = { viewModel.selectTab(3) },
                    icon = { Icon(Icons.Default.CleaningServices, contentDescription = stringResource(R.string.ai_dupes)) },
                    label = { Text(stringResource(R.string.ai_dupes)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = BhagwaOrange, selectedTextColor = BhagwaOrange),
                    modifier = Modifier.testTag("nav_tab_3")
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 4,
                    onClick = { viewModel.selectTab(4) },
                    icon = { Icon(Icons.Default.CloudSync, contentDescription = stringResource(R.string.cloud)) },
                    label = { Text(stringResource(R.string.cloud)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = BhagwaOrange, selectedTextColor = BhagwaOrange),
                    modifier = Modifier.testTag("nav_tab_4")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = selectedTabIndex,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally(animationSpec = tween(300)) { width -> width } + fadeIn(animationSpec = tween(300))).togetherWith(slideOutHorizontally(animationSpec = tween(300)) { width -> -width } + fadeOut(animationSpec = tween(300)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(300)) { width -> -width } + fadeIn(animationSpec = tween(300))).togetherWith(slideOutHorizontally(animationSpec = tween(300)) { width -> width } + fadeOut(animationSpec = tween(300)))
                    }
                },
                label = "Tab Transition"
            ) { targetTab ->
                when (targetTab) {
                0 -> DashboardScreen(
                    viewModel = viewModel,
                    categoryStats = categoryStats,
                    recentFiles = recentFiles,
                    onNavigateTab = { viewModel.selectTab(it) }
                )
                1 -> FileManagerScreen(
                    viewModel = viewModel,
                    files = allFiles,
                    recycleBinFiles = recycleBinFiles,
                    selectedCategory = selectedCategory,
                    searchQuery = searchQuery
                )
                2 -> VaultScreen(
                    viewModel = viewModel,
                    isUnlocked = isVaultUnlocked,
                    enteredPin = enteredPin,
                    pinError = pinError,
                    vaultItems = vaultItems
                )
                3 -> AiDuplicatesScreen(
                    viewModel = viewModel,
                    level1Duplicates = level1Duplicates,
                    level3Duplicates = level3Duplicates,
                    similarityThreshold = similarityThreshold,
                    selectedDuplicateIds = selectedDuplicateIds,
                    semanticQuery = semanticQuery,
                    ocrScannedFiles = ocrScannedFiles,
                    semanticSearchResults = semanticSearchResults
                )
                4 -> CloudPluginsScreen(
                    viewModel = viewModel,
                    cloudSyncItems = cloudSyncItems,
                    plugins = plugins
                )
                5 -> AboutScreen(
                    onBackClick = { viewModel.selectTab(0) }
                )
            }
            }
        }
    }
}
