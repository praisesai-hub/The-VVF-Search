@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.example.ui

import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.example.VVFApplication
import com.example.data.FileCategory
import com.example.data.FileDao
import com.example.ui.components.PickableLocalFile
import com.example.data.SmartManagerRepository
import com.example.data.VaultPinLockoutStatus
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

class FakeSmartManagerRepository(context: Context) : SmartManagerRepository(
    context = context,
    dao = mockk<FileDao>(relaxed = true)
) {
    var verifyPinResult = true
    var changePinResult = true
    var unlockPinResult = true
    override fun hasVaultPin(): Boolean = true
    override fun getStoredVaultPinHash(): String = "test-stored-hash"
    var lastVerifiedPin: String? = null
    var lastChangedOldPin: String? = null
    var lastChangedNewPin: String? = null
    val insertedFiles = mutableListOf<com.example.data.FileItemEntity>()
    var backgroundIndexWorkEnqueued = false

    override fun verifyVaultPin(inputPin: String, storedHash: String): Boolean {
        lastVerifiedPin = inputPin
        return verifyPinResult
    }

    override fun changeVaultPin(oldPin: String, newPin: String): Boolean {
        lastChangedOldPin = oldPin
        lastChangedNewPin = newPin
        return changePinResult
    }

    var lastUnlockedPin: String? = null
    override fun unlockVaultWithPin(pin: String): Boolean {
        lastUnlockedPin = pin
        return unlockPinResult
    }

    override fun vaultPinLockoutStatus(): VaultPinLockoutStatus =
        VaultPinLockoutStatus(failedAttempts = 0, lockedUntilEpochMs = 0L, remainingMs = 0L)

    var lockVaultSessionCalls = 0
    override fun lockVaultSession() { lockVaultSessionCalls += 1 }

    override suspend fun insertFiles(files: List<com.example.data.FileItemEntity>) {
        insertedFiles.addAll(files)
    }

    override fun enqueueBackgroundIndexWork() {
        backgroundIndexWorkEnqueued = true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = VVFApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakeRepository: FakeSmartManagerRepository
    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>() as VVFApplication
        fakeRepository = FakeSmartManagerRepository(app)

        app.repository = fakeRepository
        viewModel = MainViewModel(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun verifyPin_success_unlocksVaultAndClearsError() {
        fakeRepository.verifyPinResult = true

        viewModel.appendPinDigit("1")
        viewModel.appendPinDigit("2")
        viewModel.appendPinDigit("3")
        viewModel.appendPinDigit("4")
        viewModel.appendPinDigit("5")
        viewModel.appendPinDigit("6")

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.isVaultUnlocked.value)
        assertNull(viewModel.pinError.value)
        assertEquals("123456", fakeRepository.lastUnlockedPin)
    }

    @Test
    fun verifyPin_failure_setsPinErrorAndResetsEnteredPin() {
        fakeRepository.verifyPinResult = false
        fakeRepository.unlockPinResult = false

        viewModel.appendPinDigit("9")
        viewModel.appendPinDigit("9")
        viewModel.appendPinDigit("9")
        viewModel.appendPinDigit("9")
        viewModel.appendPinDigit("9")
        viewModel.appendPinDigit("9")

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.isVaultUnlocked.value)
        assertEquals("Incorrect PIN. Try again.", viewModel.pinError.value)
        assertEquals("", viewModel.enteredPin.value)
        assertEquals("999999", fakeRepository.lastUnlockedPin)
    }

    @Test
    fun backgroundingApp_locksAuthenticatedVaultSessionImmediately() {
        viewModel.appendPinDigit("1")
        viewModel.appendPinDigit("2")
        viewModel.appendPinDigit("3")
        viewModel.appendPinDigit("4")
        viewModel.appendPinDigit("5")
        viewModel.appendPinDigit("6")
        assertTrue(viewModel.isVaultUnlocked.value)

        viewModel.lockVaultForBackground()

        assertFalse(viewModel.isVaultUnlocked.value)
        assertEquals(1, fakeRepository.lockVaultSessionCalls)
    }

    @Test
    fun vaultAutoLockTimeout_isBoundedPersistedAndResetsActivityGeneration() {
        val initialGeneration = viewModel.vaultActivityGeneration.value

        viewModel.setVaultAutoLockTimeout(1L)

        assertEquals(15_000L, viewModel.vaultAutoLockTimeoutMs.value)
        assertEquals(initialGeneration, viewModel.vaultActivityGeneration.value)

        viewModel.appendPinDigit("1")
        viewModel.appendPinDigit("2")
        viewModel.appendPinDigit("3")
        viewModel.appendPinDigit("4")
        viewModel.appendPinDigit("5")
        viewModel.appendPinDigit("6")
        val unlockedGeneration = viewModel.vaultActivityGeneration.value

        viewModel.recordVaultActivity()

        assertTrue(viewModel.vaultActivityGeneration.value > unlockedGeneration)
    }

    @Test
    fun changeVaultPin_success_returnsTrueAndClearsError() {
        fakeRepository.changePinResult = true

        val result = viewModel.changeVaultPin("123456", "567890")

        assertTrue(result)
        assertNull(viewModel.pinError.value)
        assertEquals("123456", fakeRepository.lastChangedOldPin)
        assertEquals("567890", fakeRepository.lastChangedNewPin)
    }

    @Test
    fun changeVaultPin_failure_returnsFalseAndSetsPinError() {
        fakeRepository.changePinResult = false

        val result = viewModel.changeVaultPin("123456", "000000")

        assertFalse(result)
        assertEquals("Failed to update PIN. Check current PIN.", viewModel.pinError.value)
        assertEquals("123456", fakeRepository.lastChangedOldPin)
        assertEquals("000000", fakeRepository.lastChangedNewPin)
    }

    @Test
    fun setSearchQuery_updatesSearchQueryState() {
        val query = "invoice"
        viewModel.setSearchQuery(query)

        assertEquals(query, viewModel.searchQuery.value)
    }

    @Test
    fun selectTab_updatesSelectedTabIndexState() {
        viewModel.selectTab(2)

        assertEquals(2, viewModel.selectedTabIndex.value)
    }

    @Test
    fun selectCategory_updatesCategoryAndSupportsClearing() {
        viewModel.selectCategory(FileCategory.IMAGES)

        assertEquals(FileCategory.IMAGES, viewModel.selectedCategory.value)

        viewModel.selectCategory(null)

        assertNull(viewModel.selectedCategory.value)
    }

    @Test
    fun semanticSettings_updateStateAndExposeUnavailableModelState() {
        viewModel.setSemanticQuery("receipt from last month")
        viewModel.setSimilarityThreshold(92.5f)

        assertEquals("receipt from last month", viewModel.semanticQuery.value)
        assertEquals(92.5f, viewModel.similarityThreshold.value)
        assertFalse(viewModel.isSemanticSearchAvailable)
    }

    @Test
    fun autoCleanDuplicatesSetting_updatesStateAndPersists() {
        viewModel.setAutoCleanDuplicatesBg(true)

        assertTrue(viewModel.autoCleanDuplicatesBg.value)
        assertTrue(
            viewModel.getApplication<Application>()
                .getSharedPreferences("vvf_app_settings", Context.MODE_PRIVATE)
                .getBoolean("auto_clean_duplicates_bg", false)
        )

        viewModel.setAutoCleanDuplicatesBg(false)

        assertFalse(viewModel.autoCleanDuplicatesBg.value)
    }

    @Test
    fun googleSignIn_isFailClosedAndGlobalErrorCanBeCleared() {
        viewModel.signInToGoogle("user@example.com", "User")

        assertEquals(
            "Google sign-in requires the real OAuth authorization flow; local/mock sign-in is disabled.",
            viewModel.globalError.value
        )

        viewModel.clearGlobalError()

        assertNull(viewModel.globalError.value)
    }

    @Test
    fun processPickedLocalFiles_importsPickedMetadataAndSchedulesIndexing() {
        val picked = PickableLocalFile(
            name = "photo.png",
            path = "/storage/emulated/0/Pictures/photo.png",
            sizeBytes = 42L,
            category = FileCategory.IMAGES,
            dateModifiedMs = 1234L
        )

        viewModel.processPickedLocalFiles(listOf(picked))
        testDispatcher.scheduler.advanceUntilIdle()

        val imported = fakeRepository.insertedFiles.single()
        assertEquals("photo.png", imported.name)
        assertEquals(picked.path, imported.path)
        assertEquals(FileCategory.IMAGES.name, imported.category)
        assertEquals(42L, imported.sizeBytes)
        assertEquals(1234L, imported.dateModifiedMs)
        assertEquals("Imported", imported.tags)
        assertTrue(fakeRepository.backgroundIndexWorkEnqueued)
    }

    @Test
    fun processPickedJavaFiles_infersCategoryAndPersistsActualSize() {
        val importedFile = File.createTempFile("vvf_main_vm_", ".pdf")
        try {
            importedFile.writeText("document import fixture")

            viewModel.processPickedLocalFiles(listOf(importedFile))
            testDispatcher.scheduler.advanceUntilIdle()

            val imported = fakeRepository.insertedFiles.single()
            assertEquals(importedFile.name, imported.name)
            assertEquals(importedFile.absolutePath, imported.path)
            assertEquals(FileCategory.DOCUMENTS.name, imported.category)
            assertEquals(importedFile.length(), imported.sizeBytes)
            assertEquals("Local_Import", imported.tags)
            assertTrue(fakeRepository.backgroundIndexWorkEnqueued)
        } finally {
            importedFile.delete()
        }
    }

    @Test
    fun processPickedUris_generatesSafeFallbackMetadataWhenResolverProvidesNoMetadata() {
        val uri = android.net.Uri.parse("content://unknown.vvf.provider/fallback.pdf")

        viewModel.processPickedUris(listOf(uri))
        testDispatcher.scheduler.advanceUntilIdle()

        val imported = fakeRepository.insertedFiles.single()
        assertTrue(imported.name.startsWith("Picked_File_"))
        assertTrue(imported.name.endsWith("_0.bin"))
        assertEquals(uri.toString(), imported.path)
        assertEquals(FileCategory.OTHER.name, imported.category)
        assertEquals(0L, imported.sizeBytes)
        assertEquals("SAF_Import", imported.tags)
        assertTrue(fakeRepository.backgroundIndexWorkEnqueued)
    }

    @Test
    fun persistedSafUri_saveAndLoad_works() {
        val testUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        viewModel.savePersistedFolderUri(testUri)

        val retrievedUris = viewModel.getPersistedFolderUris()
        assertTrue(retrievedUris.contains(testUri))
        assertTrue(viewModel.persistedFolderUris.value.contains(testUri))
    }

    @Test
    fun persistedSafUri_preventsDuplicates() {
        val testUri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"
        viewModel.savePersistedFolderUri(testUri)
        viewModel.savePersistedFolderUri(testUri) // attempt second registration

        val retrievedUris = viewModel.getPersistedFolderUris()
        assertEquals(1, retrievedUris.filter { it == testUri }.size)
    }

    @Test
    fun persistedSafUri_removeUpdatesStateAndDoesNotCrash() {
        val testUri = "content://com.android.externalstorage.documents/tree/primary%3APictures"
        viewModel.savePersistedFolderUri(testUri)
        assertTrue(viewModel.persistedFolderUris.value.contains(testUri))

        // This triggers releasePersistableUriPermission internally, which should be safely caught if system resolver is not fully mocked
        viewModel.removePersistedFolderUri(testUri)

        assertFalse(viewModel.persistedFolderUris.value.contains(testUri))
        assertFalse(viewModel.getPersistedFolderUris().contains(testUri))
    }

    @Test
    fun persistedSafUri_invalidUriHandledSafely() {
        val malformedUri = ":::invalid_uri_string:::"
        // Saving should work as raw string preference
        viewModel.savePersistedFolderUri(malformedUri)
        assertTrue(viewModel.persistedFolderUris.value.contains(malformedUri))

        // Removing should handle malformed/invalid parse errors cleanly without throwing/crashing
        viewModel.removePersistedFolderUri(malformedUri)
        assertFalse(viewModel.persistedFolderUris.value.contains(malformedUri))
    }

    @Test
    fun persistedSafUri_rescanAndProcessHandledSafely() {
        // Test that processing directory picked or rescanning does not crash on empty/invalid states
        val testUri = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        
        // This takes persistable permissions and attempts recursive scan. It should log errors safely if resolver fails, but not crash.
        viewModel.processPickedDirectoryUri(testUri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.rescanPersistedFolders()
        testDispatcher.scheduler.advanceUntilIdle()
    }
}
