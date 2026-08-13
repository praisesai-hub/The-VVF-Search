package com.example

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.data.SmartManagerRepository
import com.example.ui.MainViewModel
import com.example.ui.changeVaultPin
import com.example.ui.pinError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultPinInstrumentedTest {
    private lateinit var app: VVFApplication

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VVFApplication
        app.deleteSharedPreferences("vvf_vault_prefs")
    }

    @Test
    fun vaultPin_verificationAndChange_persistsCorrectly() {
        val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = SmartManagerRepository(app, database.fileDao())

            assertFalse(repository.hasVaultPin())
            assertFalse(repository.verifyVaultPin("1234"))
            assertTrue(repository.initializeVaultPin("2468"))
            assertTrue(repository.hasVaultPin())
            assertFalse(repository.verifyVaultPin("0000"))

            assertTrue(repository.changeVaultPin("2468", "9876"))
            assertFalse(repository.verifyVaultPin("2468"))
            assertTrue(repository.verifyVaultPin("9876"))
        } finally {
            database.close()
        }
    }

    @Test
    fun mainViewModel_changeVaultPin_handlesUpdates() {
        val viewModel = MainViewModel(app)

        assertFalse(viewModel.changeVaultPin("1111", "5555"))
        assertEquals("Failed to update PIN. Check current PIN.", viewModel.pinError.value)

        assertTrue(viewModel.repository.initializeVaultPin("1234"))
        assertTrue(viewModel.changeVaultPin("1234", "5555"))
        assertNull(viewModel.pinError.value)
    }
}
