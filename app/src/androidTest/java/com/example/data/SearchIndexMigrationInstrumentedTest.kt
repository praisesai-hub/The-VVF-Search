package com.example.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexMigrationInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val databaseName = "fts-migration-${System.nanoTime()}.db"
    private val passphrase = "fts-migration-test-passphrase".toByteArray()

    @Before
    fun loadSqlCipher() {
        System.loadLibrary("sqlcipher")
    }

    @After
    fun tearDown() {
        File(context.getDatabasePath(databaseName).path).delete()
        File(context.getDatabasePath("$databaseName-wal").path).delete()
        File(context.getDatabasePath("$databaseName-shm").path).delete()
    }

    @Test
    fun migration8To9_rebuildsHindiFtsIndexAndCreatesSynchronizationTriggers() {
        val helper = SupportOpenHelperFactory(passphrase.copyOf()).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        db.execSQL(
            """
            CREATE TABLE files (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                path TEXT NOT NULL,
                originalPath TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL,
                sizeBytes INTEGER NOT NULL,
                dateModifiedMs INTEGER NOT NULL DEFAULT 0,
                md5Hash TEXT NOT NULL DEFAULT '',
                ocrText TEXT NOT NULL DEFAULT '',
                tags TEXT NOT NULL DEFAULT '',
                isVault INTEGER NOT NULL DEFAULT 0,
                isRecycleBin INTEGER NOT NULL DEFAULT 0,
                deletedTimestampMs INTEGER NOT NULL DEFAULT 0,
                visualSimilarityHash TEXT NOT NULL DEFAULT '',
                semanticEmbeddingVersion INTEGER NOT NULL DEFAULT 0,
                semanticIndexed INTEGER NOT NULL DEFAULT 0,
                semanticEmbeddingString TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO files (id, name, path, category, sizeBytes, ocrText, tags)
            VALUES (9, 'बिजली-बिल.pdf', '/docs/electricity.pdf', 'DOCUMENTS', 42,
                'बिजली का बिल भुगतान', 'ऊर्जा')
            """.trimIndent()
        )

        AppDatabase.MIGRATION_8_9.migrate(db)

        db.query(
            """
            SELECT files.id FROM files
            JOIN file_search_fts ON file_search_fts.rowid = files.id
            WHERE file_search_fts MATCH ?
            """.trimIndent(),
            arrayOf("\"बिजली\"*")
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = 'files_search_fts_bu'"
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'semantic_ann_buckets'"
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        helper.close()
    }
}
