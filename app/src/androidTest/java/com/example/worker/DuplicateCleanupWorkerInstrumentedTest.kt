package com.example.worker

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.AppDatabase
import com.example.data.FileDao
import com.example.data.FileItemEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DuplicateCleanupWorkerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var dao: FileDao
    private lateinit var fixtureRoot: File

    @Before
    fun setUp(): Unit {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        dao = AppDatabase.getDatabase(context).fileDao()
        runBlocking {
            val activeIds = dao.getAllOrdinaryFilesDirect().map { it.id }
            if (activeIds.isNotEmpty()) dao.deleteFilesByIds(activeIds)
            dao.emptyRecycleBin()
        }
        fixtureRoot = File(context.cacheDir, "duplicate-cleanup-worker-${System.nanoTime()}")
        assertTrue(fixtureRoot.mkdirs())
        com.example.storage.PhysicalStorageManager.getRecycleBinDir(context).deleteRecursively()
    }

    @After
    fun tearDown(): Unit {
        runBlocking {
            val activeIds = dao.getAllOrdinaryFilesDirect().map { it.id }
            if (activeIds.isNotEmpty()) dao.deleteFilesByIds(activeIds)
            dao.emptyRecycleBin()
        }
        fixtureRoot.deleteRecursively()
        com.example.storage.PhysicalStorageManager.getRecycleBinDir(context).deleteRecursively()
        context.getSharedPreferences("vvf_app_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun createWorker(): DuplicateCleanupWorker =
        TestListenableWorkerBuilder<DuplicateCleanupWorker>(context).build()

    private fun createSource(name: String, content: String = "duplicate payload"): File =
        File(fixtureRoot, name).also { it.writeText(content) }

    private fun fileItem(
        id: Long,
        source: File,
        hash: String,
        modifiedMs: Long,
        originalPath: String = "",
        isRecycleBin: Boolean = false,
    ): FileItemEntity = FileItemEntity(
        id = id,
        name = source.name,
        path = source.absolutePath,
        originalPath = originalPath,
        category = "DOCUMENTS",
        sizeBytes = source.length(),
        dateModifiedMs = modifiedMs,
        md5Hash = hash,
        isRecycleBin = isRecycleBin,
    )

    private fun enableAutoCleanup(): Unit {
        context.getSharedPreferences("vvf_app_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_clean_duplicates_bg", true)
            .commit()
    }

    @Test
    fun disabledSetting_returnsSuccessWithoutMovingFiles(): Unit = runBlocking {
        val source = createSource("disabled.txt")
        dao.insertFileDirect(fileItem(101L, source, "disabled-hash", 100L))

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(source.exists())
        assertEquals(1, dao.getAllActiveFiles().first().size)
    }

    @Test
    fun exactDuplicates_moveNewestFileToRecycleBinAndPersistMetadata(): Unit = runBlocking {
        enableAutoCleanup()
        val oldest = createSource("oldest.txt", "old content")
        val newest = createSource("newest.txt", "new content")
        dao.insertFileDirect(fileItem(102L, oldest, "same-hash", 100L))
        dao.insertFileDirect(fileItem(103L, newest, "same-hash", 200L))

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(oldest.exists())
        assertFalse(newest.exists())
        val activeFiles = dao.getAllActiveFiles().first()
        assertEquals(listOf(102L), activeFiles.map { it.id })
        val recycled = dao.getRecycleBinFiles().first().single()
        assertEquals(103L, recycled.id)
        assertTrue(recycled.isRecycleBin)
        assertEquals(newest.absolutePath, recycled.originalPath)
        assertNotEquals(newest.absolutePath, recycled.path)
        assertTrue(recycled.path.contains(".recycle_bin"))
        assertEquals("new content", File(recycled.path).readText())
        assertTrue(recycled.deletedTimestampMs > 0L)
    }

    @Test
    fun existingRecycleBinHash_skipsPhysicalMoveAndKeepsActiveDuplicate(): Unit = runBlocking {
        enableAutoCleanup()
        val activeDuplicate = createSource("active-duplicate.txt")
        val existingTrash = File(com.example.storage.PhysicalStorageManager.getRecycleBinDir(context), "existing.txt")
            .also { it.parentFile?.mkdirs(); it.writeText("already recycled") }
        dao.insertFileDirect(fileItem(104L, activeDuplicate, "idempotent-hash", 200L))
        dao.insertFileDirect(fileItem(105L, existingTrash, "idempotent-hash", 100L, isRecycleBin = true))

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(activeDuplicate.exists())
        assertEquals(1, dao.getAllActiveFiles().first().size)
        assertEquals(1, dao.getRecycleBinFiles().first().size)
    }

    @Test
    fun blankHashes_areNotTreatedAsExactDuplicates(): Unit = runBlocking {
        enableAutoCleanup()
        val first = createSource("blank-one.txt")
        val second = createSource("blank-two.txt")
        dao.insertFileDirect(fileItem(106L, first, "", 100L))
        dao.insertFileDirect(fileItem(107L, second, "", 200L))

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertEquals(2, dao.getAllActiveFiles().first().size)
        assertTrue(dao.getRecycleBinFiles().first().isEmpty())
    }

    @Test
    fun failedPhysicalMove_leavesDuplicateActiveAndReturnsSuccess(): Unit = runBlocking {
        enableAutoCleanup()
        val oldest = createSource("existing-oldest.txt")
        val missingNewest = File(fixtureRoot, "missing-newest.txt")
        dao.insertFileDirect(fileItem(108L, oldest, "failed-move-hash", 100L))
        dao.insertFileDirect(fileItem(109L, missingNewest, "failed-move-hash", 200L))

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(oldest.exists())
        assertEquals(2, dao.getAllActiveFiles().first().size)
        assertTrue(dao.getRecycleBinFiles().first().isEmpty())
    }
}
