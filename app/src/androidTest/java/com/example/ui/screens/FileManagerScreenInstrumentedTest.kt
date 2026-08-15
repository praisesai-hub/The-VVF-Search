package com.example.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.VVFApplication
import com.example.data.AppDatabase
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileManagerScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VVFApplication
    private lateinit var dao: com.example.data.FileDao
    private lateinit var fixturePrefix: String

    @Before
    fun setUp(): Unit {
        app = ApplicationProvider.getApplicationContext<VVFApplication>()
        dao = AppDatabase.getDatabase(app).fileDao()
        fixturePrefix = "file-manager-fixture-${System.nanoTime()}-"
    }

    @After
    fun tearDown(): Unit = runBlocking {
        val rows = dao.getAllOrdinaryFilesDirect() + dao.getVaultFiles().first() + dao.getRecycleBinFiles().first()
        val rowIds = rows.filter { it.name.startsWith(fixturePrefix) }.map { it.id }
        if (rowIds.isNotEmpty()) dao.deleteFilesByIds(rowIds)

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
    fun emptyStateSearchFiltersPickerTrashAndPersistedFolderAreRendered(): Unit {
        val viewModel = realViewModel()
        val persistedFolder = "content://test/$fixturePrefix-folder"
        viewModel.savePersistedFolderUri(persistedFolder)

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                FileManagerScreen(
                    viewModel = viewModel,
                    files = emptyList(),
                    recycleBinFiles = emptyList(),
                    selectedCategory = null,
                    searchQuery = ""
                )
            }
        }

        composeTestRule.onNodeWithTag("file_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_file_picker_btn").performClick()
        composeTestRule.onNodeWithTag("picker_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("file_picker_close_btn").performClick()

        composeTestRule.onNodeWithText("No files found in this category.").assertIsDisplayed()
        listOf("All Files", "Images", "Documents", "Audio", "Video").forEach { label ->
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeTestRule.onNodeWithText("Images").performClick()
        composeTestRule.onNodeWithText("Other").assertExists()
        composeTestRule.onNodeWithTag("file_search_input").performTextInput("photo")
        composeTestRule.onNodeWithContentDescription("Clear").performClick()

        composeTestRule.onNodeWithText("View Trash").performClick()
        composeTestRule.onNodeWithText("Deleted Files (Auto-purge after 30 days)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recycle Bin is empty.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hide Trash").performClick()
        composeTestRule.onAllNodesWithText("Recycle Bin is empty.").assertCountEquals(0)

        composeTestRule.onNodeWithText("Linked Directories (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("$fixturePrefix-folder").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Unlink folder").performClick()
        composeTestRule.onAllNodesWithText("Linked Directories (1)").assertCountEquals(0)
    }

    @Test
    fun activeFileRenameEncryptAndOcrDialogsUseRealViewModelCallbacks(): Unit {
        val viewModel = realViewModel()
        val sourceFile = File(app.cacheDir, "$fixturePrefix-report.txt").apply {
            writeText("deterministic FileManagerScreen fixture")
        }
        val file = FileItemEntity(
            id = 0L,
            name = "$fixturePrefix-report.txt",
            path = sourceFile.absolutePath,
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = sourceFile.length(),
            tags = "fixture",
            ocrText = "fixture OCR text"
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                FileManagerScreen(
                    viewModel = viewModel,
                    files = listOf(file),
                    recycleBinFiles = emptyList(),
                    selectedCategory = null,
                    searchQuery = ""
                )
            }
        }

        composeTestRule.onNodeWithText(file.name).assertIsDisplayed()
        composeTestRule.onNodeWithText("Tags: fixture").assertIsDisplayed()
        composeTestRule.onNodeWithText("OCR Scanned").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Rename File").performClick()
        composeTestRule.onNodeWithText("Rename File").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rename").performClick()

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("OCR Overlay Preview").performClick()
        composeTestRule.onNodeWithText("OCR Overlay: ${file.name}").assertIsDisplayed()
        composeTestRule.onNodeWithText("Close").performClick()

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Encrypt to Vault").performClick()
        composeTestRule.onNodeWithText("Encrypt & Best-Effort Wipe").assertIsDisplayed()
        composeTestRule.onNodeWithText("Disclaimer: Modern flash/SSD storage utilizes Wear-Leveling. Software-level overwriting is performed on a best-effort basis and does not guarantee absolute block-level physical erasure.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Encrypt & Wipe").performClick()

        runBlocking {
            withTimeout(10_000) {
                while (dao.getAllVaultItems().first().none { it.originalName == file.name }) {
                    delay(50)
                }
            }
        }
        assertFalse(sourceFile.exists())
    }

    @Test
    fun recycleBinActionsInvokeRealRepositoryAndPreserveDataIntegrity(): Unit {
        val viewModel = realViewModel()
        val restoreTarget = File(app.cacheDir, "$fixturePrefix-restore-target.txt")
        val restoreTrash = File(PhysicalStorageManager.getRecycleBinDir(app), "$fixturePrefix-restore-trash.txt")
        val deleteTrash = File(PhysicalStorageManager.getRecycleBinDir(app), "$fixturePrefix-delete-trash.txt")
        restoreTrash.writeText("restore fixture")
        deleteTrash.writeText("delete fixture")

        val restoreId = runBlocking {
            dao.insertFileDirect(
                FileItemEntity(
                    name = "$fixturePrefix-restore.txt",
                    path = restoreTrash.absolutePath,
                    originalPath = restoreTarget.absolutePath,
                    category = FileCategory.DOCUMENTS.name,
                    sizeBytes = restoreTrash.length(),
                    isRecycleBin = true,
                    deletedTimestampMs = System.currentTimeMillis()
                )
            )
        }
        val deleteId = runBlocking {
            dao.insertFileDirect(
                FileItemEntity(
                    name = "$fixturePrefix-delete.txt",
                    path = deleteTrash.absolutePath,
                    category = FileCategory.DOCUMENTS.name,
                    sizeBytes = deleteTrash.length(),
                    isRecycleBin = true,
                    deletedTimestampMs = System.currentTimeMillis()
                )
            )
        }
        val recycleItems = listOf(
            FileItemEntity(
                id = restoreId,
                name = "$fixturePrefix-restore.txt",
                path = restoreTrash.absolutePath,
                originalPath = restoreTarget.absolutePath,
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = restoreTrash.length(),
                isRecycleBin = true
            ),
            FileItemEntity(
                id = deleteId,
                name = "$fixturePrefix-delete.txt",
                path = deleteTrash.absolutePath,
                category = FileCategory.DOCUMENTS.name,
                sizeBytes = deleteTrash.length(),
                isRecycleBin = true
            )
        )

        composeTestRule.setContent {
            VVFSmartManagerTheme {
                FileManagerScreen(
                    viewModel = viewModel,
                    files = emptyList(),
                    recycleBinFiles = recycleItems,
                    selectedCategory = null,
                    searchQuery = ""
                )
            }
        }

        composeTestRule.onNodeWithText("View Trash").performClick()
        composeTestRule.onNodeWithText("Deleted Files (Auto-purge after 30 days)").assertIsDisplayed()
        composeTestRule.onNodeWithText("$fixturePrefix-restore.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("$fixturePrefix-delete.txt").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Restore")[0].performClick()
        runBlocking {
            withTimeout(10_000) {
                while (dao.getFileById(restoreId)?.isRecycleBin == true) {
                    delay(50)
                }
            }
        }
        assertTrue(restoreTarget.exists())
        assertFalse(restoreTrash.exists())
        val restoredRow = runBlocking { dao.getFileById(restoreId) }
        assertFalse(restoredRow?.isRecycleBin ?: true)

        composeTestRule.onAllNodesWithContentDescription("Delete Forever")[1].performClick()
        runBlocking {
            withTimeout(10_000) {
                while (dao.getFileById(deleteId) != null) {
                    delay(50)
                }
            }
        }
        assertFalse(deleteTrash.exists())
        val deletedRow = runBlocking { dao.getFileById(deleteId) }
        assertNull(deletedRow)

        // The list is deterministic screen input; this invokes the real empty-trash callback.
        composeTestRule.onNodeWithText("Empty Trash").performClick()
    }

    private fun realViewModel(): MainViewModel = MainViewModel(app)
}
