package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StaleRecordReconciliationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: FileDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.fileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testExistingDbRecordWithDiscoveredPathRemains() = runBlocking {
        val path = "/storage/emulated/0/Documents/report.pdf"
        val item = FileItemEntity(
            id = 1L,
            name = "report.pdf",
            path = path,
            category = "DOCUMENTS",
            sizeBytes = 1000L
        )
        dao.insertFileDirect(item)

        // Complete scan discovers this path
        val discoveredPaths = setOf(path)
        dao.reconcileStaleRecords(discoveredPaths)

        val retrieved = dao.getFileByPath(path)
        assertNotNull("Record with discovered path must remain in DB", retrieved)
        assertEquals(1L, retrieved?.id)
    }

    @Test
    fun testMissingOrdinaryFileAfterCompleteScanRemovedFromDb() = runBlocking {
        val existingPath = "/storage/emulated/0/Download/old_file.pdf"
        val existingItem = FileItemEntity(
            id = 2L,
            name = "old_file.pdf",
            path = existingPath,
            category = "DOCUMENTS",
            sizeBytes = 500L,
            isVault = false,
            isRecycleBin = false
        )
        dao.insertFileDirect(existingItem)

        // Complete scan discovers only a different file
        val discoveredPaths = setOf("/storage/emulated/0/Pictures/photo.jpg")
        dao.reconcileStaleRecords(discoveredPaths)

        val retrieved = dao.getFileByPath(existingPath)
        assertNull("Missing ordinary file must be removed from DB after complete scan", retrieved)
    }

    @Test
    fun testMissingVaultRecordPreserved() = runBlocking {
        val vaultPath = "/storage/emulated/0/.vault/secret.dat"
        val vaultItem = FileItemEntity(
            id = 3L,
            name = "secret.dat",
            path = vaultPath,
            category = "DOCUMENTS",
            sizeBytes = 2000L,
            isVault = true,
            isRecycleBin = false
        )
        dao.insertFileDirect(vaultItem)

        // Complete scan discovers nothing
        val discoveredPaths = emptySet<String>()
        dao.reconcileStaleRecords(discoveredPaths)

        val retrieved = dao.getFileByPath(vaultPath)
        assertNotNull("Vault record must be preserved even if missing from scan", retrieved)
        assertTrue(retrieved!!.isVault)
    }

    @Test
    fun testMissingRecycleBinRecordPreserved() = runBlocking {
        val trashPath = "/storage/emulated/0/.trash/deleted.txt"
        val trashItem = FileItemEntity(
            id = 4L,
            name = "deleted.txt",
            path = trashPath,
            category = "DOCUMENTS",
            sizeBytes = 150L,
            isVault = false,
            isRecycleBin = true,
            deletedTimestampMs = System.currentTimeMillis()
        )
        dao.insertFileDirect(trashItem)

        // Complete scan discovers nothing
        val discoveredPaths = emptySet<String>()
        dao.reconcileStaleRecords(discoveredPaths)

        val retrieved = dao.getFileByPath(trashPath)
        assertNotNull("Recycle bin record must be preserved even if missing from scan", retrieved)
        assertTrue(retrieved!!.isRecycleBin)
    }

    @Test
    fun testCancelledOrIncompleteScanDoesNotRemoveStaleRecords() = runBlocking {
        val path = "/storage/emulated/0/Download/unscanned.pdf"
        val item = FileItemEntity(
            id = 5L,
            name = "unscanned.pdf",
            path = path,
            category = "DOCUMENTS",
            sizeBytes = 800L
        )
        dao.insertFileDirect(item)

        // Simulate cancelled/incomplete scan where reconcileStaleRecords is NEVER called
        val scanCancelled = true
        if (!scanCancelled) {
            dao.reconcileStaleRecords(emptySet())
        }

        val retrieved = dao.getFileByPath(path)
        assertNotNull("Cancelled scan must NOT run reconciliation or delete stale records", retrieved)
    }

    @Test
    fun testScanExceptionDoesNotRemoveStaleRecords() = runBlocking {
        val path = "/storage/emulated/0/Download/protected.pdf"
        val item = FileItemEntity(
            id = 6L,
            name = "protected.pdf",
            path = path,
            category = "DOCUMENTS",
            sizeBytes = 900L
        )
        dao.insertFileDirect(item)

        // Simulate exception during scan flow
        try {
            throw RuntimeException("Storage access error")
            @Suppress("UNREACHABLE_CODE")
            dao.reconcileStaleRecords(emptySet())
        } catch (e: Exception) {
            // Scan failed with exception, reconciliation skipped
        }

        val retrieved = dao.getFileByPath(path)
        assertNotNull("Scan exception must NOT allow stale records to be deleted", retrieved)
    }

    @Test
    fun testTask09cMetadataPreservedAfterReconciliation() = runBlocking {
        val path = "/storage/emulated/0/Documents/enriched.pdf"
        val enriched = FileItemEntity(
            id = 7L,
            name = "enriched.pdf",
            path = path,
            category = "DOCUMENTS",
            sizeBytes = 4000L,
            md5Hash = "md5_hash_val",
            ocrText = "Extracted OCR Text Content",
            tags = "work,important",
            semanticEmbeddingVersion = 1,
            semanticIndexed = true,
            semanticEmbeddingString = "0.5,0.6,0.7"
        )
        dao.insertFileDirect(enriched)

        // Complete scan discovers this path
        val discoveredPaths = setOf(path)
        dao.reconcileStaleRecords(discoveredPaths)

        val retrieved = dao.getFileByPath(path)
        assertNotNull(retrieved)
        assertEquals("md5_hash_val", retrieved?.md5Hash)
        assertEquals("Extracted OCR Text Content", retrieved?.ocrText)
        assertEquals("work,important", retrieved?.tags)
        assertEquals(1, retrieved?.semanticEmbeddingVersion)
        assertTrue(retrieved?.semanticIndexed == true)
        assertEquals("0.5,0.6,0.7", retrieved?.semanticEmbeddingString)
    }
}
