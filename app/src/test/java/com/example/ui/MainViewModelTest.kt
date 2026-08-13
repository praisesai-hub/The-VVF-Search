@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.example.ui

import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.example.VVFApplication
import com.example.data.SmartManagerRepository
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

class FakeSmartManagerRepository(context: Context) : SmartManagerRepository(context) {
    var verifyPinResult = true
    var changePinResult = true
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

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.isVaultUnlocked.value)
        assertNull(viewModel.pinError.value)
        assertEquals("1234", fakeRepository.lastVerifiedPin)
    }

    @Test
    fun verifyPin_failure_setsPinErrorAndResetsEnteredPin() {
        fakeRepository.verifyPinResult = false

        viewModel.appendPinDigit("9")
        viewModel.appendPinDigit("9")
        viewModel.appendPinDigit("9")
        viewModel.appendPinDigit("9")

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.isVaultUnlocked.value)
        assertEquals("Incorrect PIN. Try again.", viewModel.pinError.value)
        assertEquals("", viewModel.enteredPin.value)
        assertEquals("9999", fakeRepository.lastVerifiedPin)
    }

    @Test
    fun changeVaultPin_success_returnsTrueAndClearsError() {
        fakeRepository.changePinResult = true

        val result = viewModel.changeVaultPin("1234", "5678")

        assertTrue(result)
        assertNull(viewModel.pinError.value)
        assertEquals("1234", fakeRepository.lastChangedOldPin)
        assertEquals("5678", fakeRepository.lastChangedNewPin)
    }

    @Test
    fun changeVaultPin_failure_returnsFalseAndSetsPinError() {
        fakeRepository.changePinResult = false

        val result = viewModel.changeVaultPin("1234", "0000")

        assertFalse(result)
        assertEquals("Failed to update PIN. Check current PIN.", viewModel.pinError.value)
        assertEquals("1234", fakeRepository.lastChangedOldPin)
        assertEquals("0000", fakeRepository.lastChangedNewPin)
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
