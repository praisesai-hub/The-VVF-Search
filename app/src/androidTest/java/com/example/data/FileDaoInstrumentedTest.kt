package com.example.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileDaoInstrumentedTest {
    private lateinit var context: Context
    private lateinit var dao: FileDao
    private lateinit var fixturePrefix: String

    @Before
    fun setUp(): Unit {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        dao = AppDatabase.getDatabase(context).fileDao()
        fixturePrefix = "dao-fixture-${System.nanoTime()}-"
    }

    @After
    fun tearDown(): Unit = runBlocking {
        val fileRows = dao.getAllOrdinaryFilesDirect() + dao.getVaultFiles().first() + dao.getRecycleBinFiles().first()
        val fileIds = fileRows.filter { it.name.startsWith(fixturePrefix) }.map { it.id }
        if (fileIds.isNotEmpty()) dao.deleteFilesByIds(fileIds)

        val vaultIds = dao.getAllVaultItems().first()
            .filter { it.originalName.startsWith(fixturePrefix) }
            .map { it.id }
        vaultIds.forEach { dao.deleteVaultItemById(it) }

        val cloudIds = dao.getCloudSyncItems().first()
            .filter { it.fileName.startsWith(fixturePrefix) }
            .map { it.id }
        cloudIds.forEach { dao.deleteCloudSyncItem(it) }
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
    ): FileItemEntity = FileItemEntity(
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
    fun queryFlows_filterActiveVaultRecycleAndSearchResults(): Unit = runBlocking {
        val document = file(
            name = "${fixturePrefix}invoice.pdf",
            modified = 500L,
            hash = "invoice-hash",
            ocr = "receipt text",
            tags = "finance,work",
            visualHash = "doc-fingerprint",
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
        )
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

        val active = dao.getAllActiveFiles().first().filter { it.name.startsWith(fixturePrefix) }
        assertEquals(listOf(document.name, image.name, video.name), active.map { it.name })
        assertEquals(listOf(document.name, image.name, video.name), dao.getRecentFiles().first().filter { it.name.startsWith(fixturePrefix) }.map { it.name })
        assertEquals(listOf(document.name, image.name), dao.getOcrScannedFiles().first().filter { it.name.startsWith(fixturePrefix) }.map { it.name })
        assertEquals(listOf(document.name), dao.searchSemanticFiles("invoice").first().filter { it.name.startsWith(fixturePrefix) }.map { it.name })
        assertEquals(listOf(document.name), dao.searchFiles("finance").first().filter { it.name.startsWith(fixturePrefix) }.map { it.name })
        assertEquals(listOf(document.name), dao.getFilteredFilesPaged(FileCategory.DOCUMENTS.name, "invoice", 10, 0).filter { it.name.startsWith(fixturePrefix) }.map { it.name })
        assertEquals(listOf(document.name), dao.getFilteredFilesPaged(FileCategory.DOCUMENTS.name, "", 1, 0).filter { it.name.startsWith(fixturePrefix) }.map { it.name })
        assertEquals(listOf(image.name), dao.getFilesByCategory(FileCategory.IMAGES.name).first().filter { it.name.startsWith(fixturePrefix) }.map { it.name })
        assertEquals(listOf(recycled.name), dao.getRecycleBinFiles().first().filter { it.name.startsWith(fixturePrefix) }.map { it.name })
        assertEquals(listOf(vault.name), dao.getVaultFiles().first().filter { it.name.startsWith(fixturePrefix) }.map { it.name })
        assertEquals(3, dao.getAllOrdinaryFilesDirect().count { it.name.startsWith(fixturePrefix) })

        val stats = dao.getCategoryStats().first().first { it.category == FileCategory.DOCUMENTS.name }
        assertTrue(stats.count >= 1)
        assertTrue(stats.totalSize >= document.sizeBytes)
    }

    @Test
    fun ftsSearch_indexesHindiAndTracksMetadataUpdatesAndDeletes(): Unit = runBlocking {
        val document = file(
            name = "${fixturePrefix}बिजली-बिल.pdf",
            path = "/data/${fixturePrefix}electricity.pdf",
            ocr = "बिजली का बिल भुगतान विवरण",
            tags = "ऊर्जा"
        )
        dao.insertFileDirect(document)
        val stored = dao.getFileByPath(document.path) ?: error("FTS fixture missing")

        assertTrue(
            dao.searchFiles("बिजली").first().any { it.id == stored.id }
        )
        assertTrue(
            dao.getFilteredFilesPaged(null, "भुगतान", 10, 0).any { it.id == stored.id }
        )

        dao.updateFile(stored.copy(tags = "utility-renewal"))
        assertTrue(
            dao.searchFiles("utility").first().any { it.id == stored.id }
        )

        dao.deleteFileById(stored.id)
        assertTrue(
            dao.searchFiles("utility").first().none { it.id == stored.id }
        )
    }

    @Test
    fun metadataTransactions_preserveExistingFieldsAndUpdateRows(): Unit = runBlocking {
        val path = "/data/${fixturePrefix}merge.txt"
        val initial = file(
            name = "${fixturePrefix}initial.txt",
            path = path,
            hash = "original-hash",
            ocr = "original-ocr",
            tags = "original-tags",
            visualHash = "original-visual",
            semanticVersion = 2,
            semanticIndexed = true,
            embedding = "1.0,2.0",
        )
        val insertedId = dao.insertFile(initial)
        val partial = initial.copy(
            name = "${fixturePrefix}updated.txt",
            sizeBytes = 99L,
            dateModifiedMs = 900L,
            md5Hash = "",
            ocrText = "",
            tags = "",
            visualSimilarityHash = "",
            semanticEmbeddingVersion = 0,
            semanticIndexed = false,
            semanticEmbeddingString = "",
        )

        assertEquals(insertedId, dao.insertFile(partial))
        val merged = dao.getFileById(insertedId) ?: error("merged fixture missing")
        assertEquals(partial.name, merged.name)
        assertEquals(99L, merged.sizeBytes)
        assertEquals("original-hash", merged.md5Hash)
        assertEquals("original-ocr", merged.ocrText)
        assertEquals("original-tags", merged.tags)
        assertEquals("original-visual", merged.visualSimilarityHash)
        assertEquals(2, merged.semanticEmbeddingVersion)
        assertTrue(merged.semanticIndexed)
        assertEquals("1.0,2.0", merged.semanticEmbeddingString)

        val newPath = "/data/${fixturePrefix}new.txt"
        val newFile = file("${fixturePrefix}new.txt", path = newPath, hash = "new-hash")
        dao.upsertFilesPreservingMetadata(listOf(newFile))
        assertNotNull(dao.getFileByPath(newPath))

        val updated = merged.copy(
            name = "${fixturePrefix}batch.txt",
            path = newPath,
            md5Hash = "batch-hash",
            ocrText = "batch-ocr",
            semanticIndexed = true,
        )
        dao.insertFiles(listOf(updated))
        val batchResult = dao.getFileByPath(newPath) ?: error("batch fixture missing")
        assertEquals(updated.name, batchResult.name)
        assertEquals("batch-hash", batchResult.md5Hash)
        assertEquals("batch-ocr", batchResult.ocrText)
        assertTrue(batchResult.semanticIndexed)

        dao.moveFilesToRecycleBinAtomic(listOf(batchResult.copy(isRecycleBin = true, deletedTimestampMs = 1000L)))
        assertTrue(dao.getRecycleBinFiles().first().any { it.path == newPath })
        assertEquals(batchResult.id, dao.findInRecycleBinByHash("batch-hash")?.id)
        dao.emptyRecycleBin()
        assertTrue(dao.getRecycleBinFiles().first().none { it.path == newPath })
    }

    @Test
    fun duplicateUnhashedAndReconcileQueries_applyIntegrityRules(): Unit = runBlocking {
        val keep = file(
            name = "${fixturePrefix}keep.txt",
            path = "/data/${fixturePrefix}keep.txt",
            hash = "same-hash",
            modified = 300L,
        )
        val duplicate = keep.copy(
            id = 0L,
            name = "${fixturePrefix}duplicate.txt",
            path = "/data/${fixturePrefix}duplicate.txt",
            dateModifiedMs = 200L,
        )
        val blankHash = file(
            name = "${fixturePrefix}blank.txt",
            path = "/data/${fixturePrefix}blank.txt",
            modified = 100L,
        )
        val imageMissingVisual = file(
            name = "${fixturePrefix}image-missing-visual.jpg",
            path = "/data/${fixturePrefix}image-missing-visual.jpg",
            category = FileCategory.IMAGES.name,
            hash = "image-hash",
            visualHash = "",
            semanticIndexed = true,
        )
        val indexedVideo = file(
            name = "${fixturePrefix}video-indexed.mp4",
            path = "/data/${fixturePrefix}video-indexed.mp4",
            category = FileCategory.VIDEO.name,
            hash = "video-hash",
            visualHash = "video-visual",
            semanticIndexed = true,
        )
        dao.insertFileDirect(keep)
        dao.insertFileDirect(duplicate)
        dao.insertFileDirect(blankHash)
        dao.insertFileDirect(imageMissingVisual)
        dao.insertFileDirect(indexedVideo)

        val unhashedNames = dao.getUnhashedFiles().filter { it.name.startsWith(fixturePrefix) }.map { it.name }
        assertTrue(unhashedNames.contains(blankHash.name))
        assertTrue(unhashedNames.contains(imageMissingVisual.name))
        assertFalse(unhashedNames.contains(indexedVideo.name))

        val duplicateRows = dao.getDuplicateFilesByHash().first().filter { it.name.startsWith(fixturePrefix) }
        assertEquals(listOf(keep.name, duplicate.name), duplicateRows.map { it.name })

        dao.reconcileStaleRecords(setOf(keep.path, duplicate.path, blankHash.path, imageMissingVisual.path, indexedVideo.path))
        assertEquals(5, dao.getAllOrdinaryFilesDirect().count { it.name.startsWith(fixturePrefix) })
        dao.reconcileStaleRecords(setOf(keep.path, duplicate.path, blankHash.path, imageMissingVisual.path))
        assertTrue(dao.getFileByPath(indexedVideo.path) == null)
    }

    @Test
    fun vaultCloudAndPluginCrud_roundTripsRows(): Unit = runBlocking {
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
        assertEquals(vault.originalName, dao.getAllVaultItems().first().first { it.id == vaultId }.originalName)
        dao.deleteVaultItemById(vaultId)
        assertTrue(dao.getAllVaultItems().first().none { it.id == vaultId })

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
        assertEquals("QUEUED", dao.getCloudSyncItems().first().first { it.id == cloudId }.status)
        dao.deleteCloudSyncItem(cloudId)
        assertTrue(dao.getCloudSyncItems().first().none { it.id == cloudId })

        val pluginId = "${fixturePrefix}plugin"
        dao.insertPlugins(listOf(PluginEntity(pluginId, "Fixture Plugin", "TEST", "Fixture", false, false)))
        assertFalse(dao.getAllPlugins().first().first { it.pluginId == pluginId }.isEnabled)
        dao.setPluginEnabled(pluginId, true)
        assertTrue(dao.getAllPlugins().first().first { it.pluginId == pluginId }.isEnabled)
    }
}
