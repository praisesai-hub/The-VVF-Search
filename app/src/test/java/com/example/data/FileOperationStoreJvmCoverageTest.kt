package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileOperationStoreJvmCoverageTest {
    private lateinit var database: AppDatabase
    private lateinit var store: FileOperationStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = database.fileOperationStore()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun durableFileOperationLifecycleTracksRecoveryCandidatesAndFinalStates() = runBlocking {
        val prefix = "file-operation-${System.nanoTime()}"
        val prepared = operation("$prefix-prepared", 1L, FileOperationStatus.PREPARED, 10L)
        val completed = operation("$prefix-physical", 2L, FileOperationStatus.PHYSICAL_COMPLETED, 20L)
        val committed = operation("$prefix-committed", 3L, FileOperationStatus.COMMITTED, 30L)
        store.insert(prepared)
        store.insert(completed)
        store.insert(committed)

        assertEquals(
            listOf(prepared.operationId, completed.operationId),
            store.getOpenOperations().map { it.operationId }
        )
        assertEquals(prepared.operationId, store.findOpenOperation(1L, "MOVE")?.operationId)
        assertNull(store.findOpenOperation(3L, "MOVE"))

        assertEquals(
            1,
            store.update(
                prepared.copy(
                    status = FileOperationStatus.PHYSICAL_COMPLETED,
                    targetPath = "/target/after",
                    updatedAtMs = 40L,
                    lastErrorCode = null
                )
            )
        )
        assertEquals("/target/after", store.findOpenOperation(1L, "MOVE")?.targetPath)

        assertEquals(
            1,
            store.update(
                completed.copy(
                    status = FileOperationStatus.FAILED,
                    updatedAtMs = 50L,
                    lastErrorCode = "NO_SPACE"
                )
            )
        )
        assertEquals(listOf(prepared.operationId), store.getOpenOperations().map { it.operationId })

        assertEquals(1, store.delete(prepared.operationId))
        assertTrue(store.getOpenOperations().isEmpty())
        assertEquals(0, store.delete(prepared.operationId))
    }

    private fun operation(
        operationId: String,
        fileId: Long,
        status: String,
        createdAtMs: Long
    ) = FileOperationEntity(
        operationId = operationId,
        operationType = "MOVE",
        fileId = fileId,
        sourcePath = "/source/$fileId",
        targetPath = "/target/$fileId",
        status = status,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs
    )
}
