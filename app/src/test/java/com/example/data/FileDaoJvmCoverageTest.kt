package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM counterpart to the device DAO contract suite. It deliberately uses the real Room schema so
 * generated query implementations and transaction paths count toward the JVM coverage gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileDaoJvmCoverageTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: FileDao
    private lateinit var fixturePrefix: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.fileDao()
        fixturePrefix = "jvm-dao-${System.nanoTime()}-"
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun file(
        name: String,
        path: String = "/data/$name",
        category: String = FileCategory.DOCUMENTS.name,
        modified: Long = 100L,
        hash: String = "",
        ocr: String = "",
        tags: String = "",
        vault: Boolean = false,
        recycleBin: Boolean = false,
        deletedAt: Long = 0L,
        visualHash: String = "",
        semanticVersion: Int = 0,
        semanticIndexed: Boolean = false,
        embedding: String = "",
    ) = FileItemEntity(
        name = name,
        path = path,
        category = category,
        sizeBytes = name.length.toLong(),
        dateModifiedMs = modified,
        md5Hash = hash,
        ocrText = ocr,
        tags = tags,
        isVault = vault,
        isRecycleBin = recycleBin,
        deletedTimestampMs = deletedAt,
        visualSimilarityHash = visualHash,
        semanticEmbeddingVersion = semanticVersion,
        semanticIndexed = semanticIndexed,
        semanticEmbeddingString = embedding,
    )

    @Test
    fun queryFlows_filterActiveVaultRecycleSearchAndIntegrityRows() = runBlocking {
        val document = file(
            name = "${fixturePrefix}invoice.pdf",
            modified = 500L,
            hash = "invoice-hash",
            ocr = "receipt text",
            tags = "finance,work",
            visualHash = "document-candidate",
            semanticVersion = 1,
            semanticIndexed = true,
            embedding = "0.1,0.2",
        )
        val image = file(
            name = "${fixturePrefix}photo.jpg",
            category = FileCategory.IMAGES.name,
            modified = 400L,
            hash = "photo-hash",
            ocr = "caption",
            visualHash = "image-dhash",
        )
        val video = file(
            name = "${fixturePrefix}clip.mp4",
            category = FileCategory.VIDEO.name,
            modified = 300L,
            hash = "video-hash",
            semanticIndexed = true,
        ).copy(videoSampleHashes = "a;b;c")
        val vault = file(
            name = "${fixturePrefix}secret.txt",
            path = "/vault/${fixturePrefix}secret.txt",
            modified = 600L,
            ocr = "secret receipt",
            vault = true,
        )
        val recycled = file(
            name = "${fixturePrefix}deleted.txt",
            path = "/trash/${fixturePrefix}deleted.txt",
            modified = 200L,
            hash = "deleted-hash",
            ocr = "deleted receipt",
            recycleBin = true,
            deletedAt = 700L,
        )
        dao.insertFileDirect(document)
        dao.insertFileDirect(image)
        dao.insertFileDirect(video)
        dao.insertFileDirect(vault)
        dao.insertFileDirect(recycled)

        assertEquals(document.name, dao.getFileByName(document.name)?.name)
        assertNotNull(dao.getFileByPath(document.path))
        val documentId = dao.getFileByPath(document.path)?.id ?: error("document fixture missing")
        assertEquals(document.name, dao.getFileById(documentId)?.name)
        assertEquals(
            listOf(document.name, image.name, video.name),
            dao.getAllActiveFiles().first().map { it.name },
        )
        assertEquals(
            listOf(document.name, image.name, video.name),
            dao.getRecentFiles().first().map { it.name },
        )
        assertEquals(listOf(document.name, image.name), dao.getOcrScannedFiles().first().map { it.name })
        assertEquals(listOf(document.name), dao.searchSemanticFiles("invoice").first().map { it.name })
        assertEquals(listOf(document.name), dao.searchFiles("finance").first().map { it.name })
        assertEquals(
            listOf(document.name),
            dao.getFilteredFilesPaged(FileCategory.DOCUMENTS.name, "invoice", 10, 0).map { it.name },
        )
        assertEquals(listOf(image.name), dao.getFilesByCategory(FileCategory.IMAGES.name).first().map { it.name })
        assertEquals(listOf(recycled.name), dao.getRecycleBinFiles().first().map { it.name })
        assertEquals(listOf(vault.name), dao.getVaultFiles().first().map { it.name })
        assertEquals(3, dao.getAllOrdinaryFilesDirect().size)
        assertTrue(dao.getCategoryStats().first().any { it.category == FileCategory.DOCUMENTS.name && it.count == 1 })
        assertFalse(dao.getUnhashedFiles().map { it.name }.contains(video.name))
    }

    @Test
    fun metadataTransactionsRecycleBinAndStaleReconciliationPreserveContract() = runBlocking {
        val original = file(
            name = "${fixturePrefix}original.txt",
            path = "/data/${fixturePrefix}shared.txt",
            hash = "original-hash",
            ocr = "original-ocr",
            tags = "original-tags",
            visualHash = "original-visual",
            semanticVersion = 2,
            semanticIndexed = true,
            embedding = "1.0,2.0",
        )
        val originalId = dao.insertFile(original)
        val partial = original.copy(
            name = "${fixturePrefix}updated.txt",
            sizeBytes = 99L,
            md5Hash = "",
            ocrText = "",
            tags = "",
            visualSimilarityHash = "",
            semanticEmbeddingVersion = 0,
            semanticIndexed = false,
            semanticEmbeddingString = "",
        )

        assertEquals(originalId, dao.insertFile(partial))
        val merged = dao.getFileById(originalId) ?: error("merged fixture missing")
        assertEquals("original-hash", merged.md5Hash)
        assertEquals("original-ocr", merged.ocrText)
        assertEquals("original-tags", merged.tags)
        assertEquals("original-visual", merged.visualSimilarityHash)
        assertTrue(merged.semanticIndexed)

        val additional = file(
            name = "${fixturePrefix}additional.txt",
            path = "/data/${fixturePrefix}additional.txt",
            hash = "duplicate-hash",
        )
        val duplicate = additional.copy(id = 0L, name = "${fixturePrefix}duplicate.txt", path = "/data/${fixturePrefix}duplicate.txt")
        dao.upsertFilesPreservingMetadata(listOf(additional, duplicate))
        assertEquals(2, dao.getDuplicateFilesByHash().first().size)

        dao.moveFilesToRecycleBinAtomic(listOf(merged.copy(isRecycleBin = true, deletedTimestampMs = 1000L)))
        assertEquals(merged.id, dao.findInRecycleBinByHash("original-hash")?.id)
        dao.emptyRecycleBin()
        assertTrue(dao.getRecycleBinFiles().first().isEmpty())

        dao.reconcileStaleRecords(setOf(additional.path))
        assertEquals(listOf(additional.path), dao.getAllOrdinaryFilesDirect().map { it.path })
        dao.deleteFilesByIds(dao.getAllOrdinaryFilesDirect().map { it.id })
        assertTrue(dao.getAllOrdinaryFilesDirect().isEmpty())
    }

    @Test
    fun vaultCloudAndPluginCrudRoundTripsRows() = runBlocking {
        val vault = VaultItemEntity(
            originalName = "${fixturePrefix}secret.txt",
            encryptedName = "encrypted.bin",
            encryptedFilePath = "/vault/encrypted.bin",
            ivBase64 = "iv",
            category = FileCategory.DOCUMENTS.name,
            sizeBytes = 42L,
            isBiometricProtected = false,
        )
        val vaultId = dao.insertVaultItem(vault)
        assertEquals(vault.originalName, dao.getAllVaultItems().first().single().originalName)
        dao.deleteVaultItemById(vaultId)
        assertTrue(dao.getAllVaultItems().first().isEmpty())

        val cloud = CloudSyncItemEntity(
            provider = "GOOGLE_DRIVE",
            fileName = "${fixturePrefix}cloud.txt",
            filePath = "/sync/${fixturePrefix}cloud.txt",
            fileSize = 5L,
            status = "QUEUED",
            lastSyncedMs = 12L,
            isCore = true,
        )
        val cloudId = dao.insertCloudSyncItem(cloud)
        assertEquals("QUEUED", dao.getCloudSyncItems().first().single().status)
        dao.deleteCloudSyncItem(cloudId)
        assertTrue(dao.getCloudSyncItems().first().isEmpty())

        dao.insertPlugins(listOf(PluginEntity("${fixturePrefix}plugin", "Fixture", "TEST", "Fixture", false, false)))
        assertFalse(dao.getAllPlugins().first().single().isEnabled)
        dao.setPluginEnabled("${fixturePrefix}plugin", true)
        assertTrue(dao.getAllPlugins().first().single().isEnabled)
    }
}
