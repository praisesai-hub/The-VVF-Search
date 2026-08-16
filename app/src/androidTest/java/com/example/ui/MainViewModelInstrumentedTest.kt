package com.example.ui

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.VVFApplication
import com.example.data.FileCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainViewModelInstrumentedTest {
    private val application: VVFApplication
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun stateMutationsAndSignInGuard_arePersistedInMemory() {
        val viewModel = MainViewModel(application)

        viewModel.selectTab(3)
        viewModel.selectCategory(FileCategory.DOCUMENTS)
        viewModel.setSearchQuery("report")
        viewModel.setSemanticQuery("invoice")
        viewModel.setSimilarityThreshold(42.5f)
        viewModel.setAutoCleanDuplicatesBg(true)
        viewModel.signInToGoogle("user@example.com", "User")

        assertEquals(3, viewModel.selectedTabIndex.value)
        assertEquals(FileCategory.DOCUMENTS, viewModel.selectedCategory.value)
        assertEquals("report", viewModel.searchQuery.value)
        assertEquals("invoice", viewModel.semanticQuery.value)
        assertEquals(42.5f, viewModel.similarityThreshold.value)
        assertTrue(viewModel.autoCleanDuplicatesBg.value)
        assertTrue(viewModel.globalError.value?.contains("real OAuth") == true)

        viewModel.clearGlobalError()
        viewModel.selectCategory(null)
        viewModel.setAutoCleanDuplicatesBg(false)
        assertFalse(viewModel.globalError.value != null)
        assertEquals(null, viewModel.selectedCategory.value)
    }

    @Test
    fun localFileImport_infersAllCategoriesAndPersistsEntities(): Unit = runBlocking {
        val viewModel = MainViewModel(application)
        val files = listOf("photo.jpg", "report.pdf", "track.mp3", "movie.mp4", "archive.bin")
            .map { File(application.cacheDir, "vm_$it").apply { writeText("fixture") } }
        try {
            viewModel.processPickedLocalFiles(files)
            withTimeout(10_000L) {
                viewModel.repository.activeFiles.first { rows ->
                    files.all { file -> rows.any { it.name == file.name && it.tags == "Local_Import" } }
                }
            }
            val rows = viewModel.repository.activeFiles.first { listed ->
                files.all { file -> listed.any { it.name == file.name } }
            }
            assertEquals(FileCategory.IMAGES.name, rows.first { it.name == "vm_photo.jpg" }.category)
            assertEquals(FileCategory.DOCUMENTS.name, rows.first { it.name == "vm_report.pdf" }.category)
            assertEquals(FileCategory.AUDIO.name, rows.first { it.name == "vm_track.mp3" }.category)
            assertEquals(FileCategory.VIDEO.name, rows.first { it.name == "vm_movie.mp4" }.category)
            assertEquals(FileCategory.OTHER.name, rows.first { it.name == "vm_archive.bin" }.category)
        } finally {
            files.forEach(File::delete)
        }
    }

    @Test
    fun uriImport_withoutMetadata_usesDeterministicDefaultNameAndCategory(): Unit = runBlocking {
        val viewModel = MainViewModel(application)
        val uri = Uri.parse("content:///not-found.txt")

        viewModel.processPickedUris(listOf(uri))
        withTimeout(10_000L) {
            viewModel.repository.activeFiles.first { rows -> rows.any { it.path == uri.toString() } }
        }
        val imported = viewModel.repository.activeFiles.first { rows -> rows.any { it.path == uri.toString() } }
            .first { it.path == uri.toString() }
        assertTrue(imported.name.startsWith("Picked_File_"))
        assertTrue(imported.name.endsWith(".bin"))
        assertEquals(FileCategory.OTHER.name, imported.category)
        assertEquals("SAF_Import", imported.tags)
    }

    @Test
    fun persistedFolderUri_canBeAddedAndRemovedWithoutLeakingState() {
        val viewModel = MainViewModel(application)
        val uri = "content://missing.provider/tree/test-folder"

        viewModel.savePersistedFolderUri(uri)
        assertTrue(viewModel.getPersistedFolderUris().contains(uri))
        viewModel.removePersistedFolderUri(uri)
        assertFalse(viewModel.getPersistedFolderUris().contains(uri))
    }
}
