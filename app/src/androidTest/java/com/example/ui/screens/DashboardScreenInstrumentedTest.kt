package com.example.ui.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.VVFApplication
import com.example.data.AppDatabase
import com.example.data.CategoryStat
import com.example.data.FileCategory
import com.example.data.FileItemEntity
import com.example.storage.PhysicalStorageManager
import com.example.ui.MainViewModel
import com.example.ui.theme.VVFSmartManagerTheme
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VVFApplication
    private lateinit var dao: com.example.data.FileDao
    private lateinit var fixturePrefix: String

    @Before
    fun setUp(): Unit {
        app = ApplicationProvider.getApplicationContext<VVFApplication>()
        dao = AppDatabase.getDatabase(app).fileDao()
        app.deleteSharedPreferences("vvf_vault_prefs")
        File(app.noBackupFilesDir, "vvf_vault_prefs.secure").delete()
        File(app.noBackupFilesDir, "vvf_vault_prefs.secure.tmp").delete()
        app.repository = com.example.data.SmartManagerRepository(app, dao)
        fixturePrefix = "dashboard-fixture-${System.nanoTime()}-"
    }

    @After
    fun tearDown(): Unit = runBlocking {
        val rows = dao.getAllOrdinaryFilesDirect() + dao.getVaultFiles().first() + dao.getRecycleBinFiles().first()
        val rowIds = rows.filter { it.name.startsWith(fixturePrefix) }.map { it.id }
        if (rowIds.isNotEmpty()) {
            dao.deleteFilesByIds(rowIds)
        }

        dao.getAllVaultItems().first()
            .filter { it.originalName.startsWith(fixturePrefix) }
            .forEach { vaultItem ->
                PhysicalStorageManager.deleteFile(app, vaultItem.encryptedFilePath)
                dao.deleteVaultItemById(vaultItem.id)
            }

        app.cacheDir.listFiles()
            ?.filter { it.name.startsWith(fixturePrefix) }
            ?.forEach { it.delete() }
        PhysicalStorageManager.getRecycleBinDir(app).listFiles()
            ?.filter { it.name.startsWith(fixturePrefix) }
            ?.forEach { it.delete() }
    }

    @Test
    fun dashboardRendersHealthRoadmapQuickActionsAndCategoryNavigation(): Unit {
        val viewModel = MainViewModel(app)
        val navigatedTabs = mutableListOf<Int>()
        val categoryStats = listOf(
            CategoryStat(FileCategory.IMAGES.name, count = 2, totalSize = 2048L),
            CategoryStat(FileCategory.DOCUMENTS.name, count = 1, totalSize = 1024L)
        )
        val recentFile = FileItemEntity(
            name = "$fixturePrefix-photo.jpg",
            path = "/data/local/tmp/$fixturePrefix-photo.jpg",
            category = FileCategory.IMAGES.name,
            sizeBytes = 1024L,
            tags = "fixture"
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    categoryStats = categoryStats,
                    recentFiles = listOf(recentFile),
                    onNavigateTab = { navigatedTabs += it }
                )
            }
        }

        composeTestRule.onNodeWithTag("dashboard_health_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("VVF Smart Manager v2.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("System Health: 94% Excellent").assertIsDisplayed()
        val expectedStorageSize = formatFileSize(
            categoryStats.sumOf { it.totalSize },
            app.getString(com.example.R.string.unknown_size),
        )
        composeTestRule.onNodeWithText("Storage Used: $expectedStorageSize").assertIsDisplayed()
        val dashboardList = composeTestRule.onNode(hasScrollToIndexAction())
        dashboardList.performScrollToNode(hasText("Recent Storage Files"))
        composeTestRule.onNodeWithText("Recent Storage Files").assertIsDisplayed()
        composeTestRule.onNodeWithText(recentFile.name).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("1.0 KB").assertCountEquals(2)
        // Compose semantics trim layout-only leading whitespace from this inline tag Text.
        composeTestRule.onNodeWithText("• fixture").assertIsDisplayed()

        dashboardList.performScrollToNode(hasText("View Report"))
        composeTestRule.onNodeWithText("View Report").performClick()
        composeTestRule.onNodeWithText("Golden Rule Audit Report").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hide").performClick()
        composeTestRule.onAllNodesWithText("Golden Rule Audit Report").assertCountEquals(0)

        dashboardList.performScrollToNode(hasText("Pick Files"))
        composeTestRule.onNodeWithText("Pick Files").performClick()
        composeTestRule.onNodeWithTag("picker_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("file_picker_close_btn").performClick()

        dashboardList.performScrollToNode(hasText("Clean Dupes"))
        composeTestRule.onNodeWithText("Clean Dupes").performClick()
        dashboardList.performScrollToNode(hasText("Secure Vault"))
        composeTestRule.onNodeWithText("Secure Vault").performClick()
        dashboardList.performScrollToNode(hasText("Cloud Sync"))
        composeTestRule.onNodeWithText("Cloud Sync").performClick()
        listOf("Images", "Documents", "Audio Files", "Videos", "Archives & Downloads").forEach { label ->
            dashboardList.performScrollToNode(hasText(label))
            composeTestRule.onNodeWithText(label).performClick()
        }
        dashboardList.performScrollToNode(hasText("View All"))
        composeTestRule.onNodeWithText("View All").performClick()

        assertTrue(navigatedTabs.contains(1))
        assertTrue(navigatedTabs.contains(2))
        assertTrue(navigatedTabs.contains(3))
        assertTrue(navigatedTabs.contains(4))
    }

    @Test
    fun dashboardTagAndMoveToTrashUseRealRepositoryCallbacks(): Unit {
        val viewModel = MainViewModel(app)
        val sourceFile = File(app.cacheDir, "$fixturePrefix-tag.txt").apply {
            writeText("dashboard tag fixture")
        }
        val insertedId = runBlocking {
            dao.insertFileDirect(
                FileItemEntity(
                    name = sourceFile.name,
                    path = sourceFile.absolutePath,
                    category = FileCategory.DOCUMENTS.name,
                    sizeBytes = sourceFile.length(),
                    tags = "existing"
                )
            )
        }
        val file = FileItemEntity(
            id = insertedId,
            name = sourceFile.name,
            path = sourceFile.absolutePath,
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = sourceFile.length(),
            tags = "existing"
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    categoryStats = emptyList(),
                    recentFiles = listOf(file),
                    onNavigateTab = {}
                )
            }
        }

        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(file.name))
        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Add Custom Tag").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("dashboard")
        composeTestRule.onNodeWithText("Add Tag").performClick()

        runBlocking {
            withTimeout(10_000) {
                while (dao.getFileById(insertedId)?.tags != "existing, dashboard") {
                    delay(50)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Move to Trash").performClick()
        val trashedFile = runBlocking {
            withTimeout(10_000) {
                var current: FileItemEntity? = null
                while (current?.isRecycleBin != true) {
                    current = dao.getFileById(insertedId)
                    if (current?.isRecycleBin != true) {
                        delay(50)
                    }
                }
                current
            }
        }
        val persistedTrashedFile = requireNotNull(trashedFile)
        assertTrue(persistedTrashedFile.isRecycleBin)
        assertFalse(sourceFile.exists())
        assertTrue(File(persistedTrashedFile.path).exists())
        assertTrue(File(persistedTrashedFile.path).name.endsWith(sourceFile.name))
    }

    @Test
    fun dashboardEncryptActionCreatesVaultItemAndWipesSource(): Unit {
        val viewModel = authenticatedViewModel()
        val sourceFile = File(app.cacheDir, "$fixturePrefix-vault.txt").apply {
            writeText("dashboard vault fixture")
        }
        val insertedId = runBlocking {
            dao.insertFileDirect(
                FileItemEntity(
                    name = sourceFile.name,
                    path = sourceFile.absolutePath,
                    category = FileCategory.DOCUMENTS.name,
                    sizeBytes = sourceFile.length()
                )
            )
        }
        val file = FileItemEntity(
            id = insertedId,
            name = sourceFile.name,
            path = sourceFile.absolutePath,
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = sourceFile.length()
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    categoryStats = emptyList(),
                    recentFiles = listOf(file),
                    onNavigateTab = {}
                )
            }
        }

        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(file.name))
        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Encrypt to Vault").performClick()

        runBlocking {
            withTimeout(10_000) {
                while (dao.getAllVaultItems().first().none { it.originalName == file.name }) {
                    delay(50)
                }
            }
        }
        assertFalse(sourceFile.exists())
    }

    private fun authenticatedViewModel(): MainViewModel {
        val viewModel = MainViewModel(app)
        check(viewModel.repository.initializeVaultPin("24682468"))
        check(viewModel.repository.unlockVaultWithPin("24682468"))
        return viewModel
    }
}
