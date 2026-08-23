package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("test_migration_db")
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    private fun createVersion1Database(helper: SupportSQLiteOpenHelper) {
        val db = helper.writableDatabase
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `files` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `name` TEXT NOT NULL, 
                `path` TEXT NOT NULL, 
                `originalPath` TEXT NOT NULL DEFAULT '', 
                `category` TEXT NOT NULL, 
                `sizeBytes` INTEGER NOT NULL, 
                `dateModifiedMs` INTEGER NOT NULL DEFAULT 0, 
                `md5Hash` TEXT NOT NULL DEFAULT '', 
                `ocrText` TEXT NOT NULL DEFAULT '', 
                `tags` TEXT NOT NULL DEFAULT '', 
                `isVault` INTEGER NOT NULL DEFAULT 0, 
                `isRecycleBin` INTEGER NOT NULL DEFAULT 0, 
                `deletedTimestampMs` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        
        db.execSQL("""
            INSERT INTO `files` (id, name, path, category, sizeBytes) 
            VALUES (505, 'v1_doc.pdf', '/storage/emulated/0/Documents/v1_doc.pdf', 'DOCUMENTS', 1024)
        """.trimIndent())
        
        db.close()
    }

    private fun createVersion2Database(helper: SupportSQLiteOpenHelper) {
        val db = helper.writableDatabase
        
        // 1. Create files table at version 2 (prior to adding MIGRATION_2_3 columns)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `files` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `name` TEXT NOT NULL, 
                `path` TEXT NOT NULL, 
                `originalPath` TEXT NOT NULL DEFAULT '', 
                `category` TEXT NOT NULL, 
                `sizeBytes` INTEGER NOT NULL, 
                `dateModifiedMs` INTEGER NOT NULL DEFAULT 0, 
                `md5Hash` TEXT NOT NULL DEFAULT '', 
                `ocrText` TEXT NOT NULL DEFAULT '', 
                `tags` TEXT NOT NULL DEFAULT '', 
                `isVault` INTEGER NOT NULL DEFAULT 0, 
                `isRecycleBin` INTEGER NOT NULL DEFAULT 0, 
                `deletedTimestampMs` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // 2. Create vault_items table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `vault_items` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `originalName` TEXT NOT NULL, 
                `encryptedName` TEXT NOT NULL, 
                `encryptedFilePath` TEXT NOT NULL, 
                `ivBase64` TEXT NOT NULL, 
                `category` TEXT NOT NULL, 
                `sizeBytes` INTEGER NOT NULL, 
                `encryptedAtMs` INTEGER NOT NULL, 
                `isBiometricProtected` INTEGER NOT NULL
            )
        """.trimIndent())

        // 3. Create cloud_sync table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `cloud_sync` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `provider` TEXT NOT NULL, 
                `fileName` TEXT NOT NULL, 
                `fileSize` INTEGER NOT NULL, 
                `status` TEXT NOT NULL, 
                `lastSyncedMs` INTEGER NOT NULL, 
                `isCore` INTEGER NOT NULL
            )
        """.trimIndent())

        // Insert a sample file entry
        db.execSQL("""
            INSERT INTO `files` (id, name, path, category, sizeBytes) 
            VALUES (101, 'existing_photo.jpg', '/storage/emulated/0/DCIM/existing_photo.jpg', 'IMAGES', 2048)
        """.trimIndent())

        db.close()
    }

    @Test
    fun testMigrationFrom2To3PreservesDataAndAddsColumns() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        
        // 1. Setup version 2 schema and insert initial row
        createVersion2Database(helper)

        // 2. Open DB and apply migration 2 -> 3
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_2_3.migrate(db)

        // 3. Verify that the previous data is completely intact (Correctness requirement A)
        val cursor = db.query("SELECT * FROM files WHERE id = 101")
        assertTrue("Migrated database should contain the pre-existing record", cursor.moveToFirst())
        assertEquals("existing_photo.jpg", cursor.getString(cursor.getColumnIndexOrThrow("name")))
        assertEquals("/storage/emulated/0/DCIM/existing_photo.jpg", cursor.getString(cursor.getColumnIndexOrThrow("path")))
        assertEquals(2048L, cursor.getLong(cursor.getColumnIndexOrThrow("sizeBytes")))

        // 4. Verify that new columns were successfully added with default values (Correctness requirement B)
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("visualSimilarityHash")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("semanticEmbeddingVersion")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("semanticIndexed")))
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("semanticEmbeddingString")))
        cursor.close()

        // 5. Verify that new plugins table was created successfully
        val pluginsCursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='plugins'")
        assertTrue("Plugins table should be created as part of v3 migration", pluginsCursor.moveToFirst())
        pluginsCursor.close()

        db.close()
    }

    @Test
    fun testMissingMigrationWithoutFallbackThrowsException() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        
        // Setup version 2 database on disk
        createVersion2Database(helper)

        // Now, try to open the database using Room at version 3 without providing migration paths
        // and with fallbackToDestructiveMigration disabled. This should throw IllegalStateException.
        try {
            val roomDb = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "test_migration_db"
            )
            // Explicitly do not add migration MIGRATION_2_3, and do not call fallbackToDestructiveMigration.
            // Room builder has fallbackToDestructiveMigration disabled by default.
            .build()

            // Trigger database opening by accessing any DAO or query
            kotlinx.coroutines.runBlocking {
                roomDb.fileDao().getFileById(101)
            }
            fail("Expected IllegalStateException due to missing migration path without fallback fallbackToDestructiveMigration")
        } catch (e: IllegalStateException) {
            // Success: expected exception thrown
            assertTrue(e.message?.contains("migration") == true || e.message?.contains("Migration") == true)
        }
    }

    @Test
    fun testMigrationFrom1To2CreatesNewTables() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        
        createVersion1Database(helper)
        
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_1_2.migrate(db)
        
        // Verify previous file data preserved
        val cursor = db.query("SELECT * FROM files WHERE id = 505")
        assertTrue(cursor.moveToFirst())
        assertEquals("v1_doc.pdf", cursor.getString(cursor.getColumnIndexOrThrow("name")))
        cursor.close()
        
        // Verify new tables exist
        val vaultCursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='vault_items'")
        assertTrue(vaultCursor.moveToFirst())
        vaultCursor.close()
        
        val cloudCursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='cloud_sync'")
        assertTrue(cloudCursor.moveToFirst())
        cloudCursor.close()
        
        db.close()
    }

    @Test
    fun testMigrationFrom3To4AddsFilePathColumn() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        
        val db = helper.writableDatabase
        // Setup schema corresponding to version 3
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `cloud_sync` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `provider` TEXT NOT NULL, 
                `fileName` TEXT NOT NULL, 
                `fileSize` INTEGER NOT NULL, 
                `status` TEXT NOT NULL, 
                `lastSyncedMs` INTEGER NOT NULL, 
                `isCore` INTEGER NOT NULL
            )
        """.trimIndent())
        
        db.execSQL("""
            INSERT INTO `cloud_sync` (id, provider, fileName, fileSize, status, lastSyncedMs, isCore)
            VALUES (301, 'GOOGLE_DRIVE', 'cloud_file.txt', 500, 'SYNCED', 10000, 0)
        """.trimIndent())
        
        // Migrate 3 to 4
        AppDatabase.MIGRATION_3_4.migrate(db)
        
        // Verify column added and old data preserved
        val cursor = db.query("SELECT * FROM cloud_sync WHERE id = 301")
        assertTrue(cursor.moveToFirst())
        assertEquals("cloud_file.txt", cursor.getString(cursor.getColumnIndexOrThrow("fileName")))
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("filePath"))) // default value
        cursor.close()
        
        db.close()
    }

    @Test
    fun testMigrationFrom5To6AddsCloudOperationState() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `cloud_sync` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `provider` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `filePath` TEXT NOT NULL DEFAULT '',
                `fileSize` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `lastSyncedMs` INTEGER NOT NULL,
                `isCore` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO `cloud_sync` (id, provider, fileName, filePath, fileSize, status, lastSyncedMs, isCore)
            VALUES (601, 'GOOGLE_DRIVE', 'cloud_file.txt', '/tmp/cloud_file.txt', 500, 'QUEUED', 10000, 0)
        """.trimIndent())

        AppDatabase.MIGRATION_5_6.migrate(db)

        val cursor = db.query("SELECT * FROM cloud_sync WHERE id = 601")
        assertTrue(cursor.moveToFirst())
        assertEquals("legacy-601", cursor.getString(cursor.getColumnIndexOrThrow("operationId")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("attemptCount")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("leaseExpiresAtMs")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("startedAtMs")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("heartbeatAtMs")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("completedAtMs")))
        cursor.close()
        val indexCursor = db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_cloud_sync_operationId'")
        assertTrue(indexCursor.moveToFirst())
        indexCursor.close()
        val workTableCursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='work_operations'")
        assertTrue(workTableCursor.moveToFirst())
        workTableCursor.close()
        db.close()
    }

    @Test
    fun testMigrationFrom6To7AddsVideoEvidenceColumns() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        db.execSQL("CREATE TABLE files (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL, category TEXT NOT NULL, sizeBytes INTEGER NOT NULL)")
        db.execSQL("INSERT INTO files (id, name, path, category, sizeBytes) VALUES (701, 'clip.mp4', '/videos/clip.mp4', 'VIDEO', 100)")

        AppDatabase.MIGRATION_6_7.migrate(db)

        val cursor = db.query("SELECT * FROM files WHERE id = 701")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("videoFingerprintVersion")))
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("videoSampleHashes")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("videoDurationMs")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("videoWidth")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("videoHeight")))
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("videoAudioSignature")))
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("videoChunkHash")))
        cursor.close()
        db.close()
    }

    @Test
    fun testMigrationFrom7To8AddsDocumentCandidateFingerprintColumn() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        db.execSQL("CREATE TABLE files (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL, category TEXT NOT NULL, sizeBytes INTEGER NOT NULL, visualSimilarityHash TEXT NOT NULL DEFAULT '')")
        db.execSQL("INSERT INTO files (id, name, path, category, sizeBytes, visualSimilarityHash) VALUES (801, 'report.pdf', '/docs/report.pdf', 'DOCUMENTS', 100, 'legacy-candidate')")

        AppDatabase.MIGRATION_7_8.migrate(db)

        val cursor = db.query("SELECT documentCandidateFingerprint, visualSimilarityHash FROM files WHERE id = 801")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("documentCandidateFingerprint")))
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("visualSimilarityHash")))
        cursor.close()
        db.close()
    }

    @Test
    fun testMigrationFrom10To11ClearsHistoricalDocumentFingerprintCopy() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        db.execSQL("CREATE TABLE files (id INTEGER PRIMARY KEY NOT NULL, category TEXT NOT NULL, documentCandidateFingerprint TEXT NOT NULL DEFAULT '', visualSimilarityHash TEXT NOT NULL DEFAULT '')")
        db.execSQL("INSERT INTO files (id, category, documentCandidateFingerprint, visualSimilarityHash) VALUES (851, 'DOCUMENTS', 'copied-image-hash', '')")
        db.execSQL("INSERT INTO files (id, category, documentCandidateFingerprint, visualSimilarityHash) VALUES (852, 'IMAGES', 'image-document-value', 'real-image-hash')")

        AppDatabase.MIGRATION_10_11.migrate(db)

        val cursor = db.query("SELECT id, documentCandidateFingerprint, visualSimilarityHash FROM files ORDER BY id")
        assertTrue(cursor.moveToFirst())
        assertEquals(851L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("documentCandidateFingerprint")))
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("visualSimilarityHash")))
        assertTrue(cursor.moveToNext())
        assertEquals(852L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals("image-document-value", cursor.getString(cursor.getColumnIndexOrThrow("documentCandidateFingerprint")))
        assertEquals("real-image-hash", cursor.getString(cursor.getColumnIndexOrThrow("visualSimilarityHash")))
        cursor.close()
        db.close()
    }

    @Test
    fun testMigrationFrom8To9AddsCloudTransferStateColumns() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        db.execSQL("CREATE TABLE cloud_sync (id INTEGER PRIMARY KEY NOT NULL, operationId TEXT NOT NULL, status TEXT NOT NULL)")
        db.execSQL("INSERT INTO cloud_sync (id, operationId, status) VALUES (901, 'op-901', 'QUEUED')")

        AppDatabase.MIGRATION_8_9.migrate(db)

        val cursor = db.query("SELECT remoteFileId, resumableSessionUri, resumableBytesCommitted FROM cloud_sync WHERE id = 901")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("remoteFileId")))
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("resumableSessionUri")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("resumableBytesCommitted")))
        cursor.close()
        db.close()
    }

    @Test
    fun testMigrationFrom9To10CreatesFileOperationTable() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase

        AppDatabase.MIGRATION_9_10.migrate(db)
        db.execSQL("INSERT INTO file_operations (operationId, operationType, fileId, sourcePath, targetPath, status, createdAtMs, updatedAtMs, lastErrorCode) VALUES ('file-DELETE-1', 'DELETE', 1, '/source', '', 'PREPARED', 10, 10, NULL)")

        val cursor = db.query("SELECT operationType, status FROM file_operations WHERE operationId = 'file-DELETE-1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("DELETE", cursor.getString(cursor.getColumnIndexOrThrow("operationType")))
        assertEquals("PREPARED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
        cursor.close()
        db.close()
    }

    @Test
    fun testFullMigrationPathFrom1To4PreservesData() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        
        createVersion1Database(helper)
        
        val db = helper.writableDatabase
        
        // Apply all migrations in order
        AppDatabase.MIGRATION_1_2.migrate(db)
        AppDatabase.MIGRATION_2_3.migrate(db)
        AppDatabase.MIGRATION_3_4.migrate(db)
        
        // Verify files data
        val filesCursor = db.query("SELECT * FROM files WHERE id = 505")
        assertTrue(filesCursor.moveToFirst())
        assertEquals("v1_doc.pdf", filesCursor.getString(filesCursor.getColumnIndexOrThrow("name")))
        assertEquals("", filesCursor.getString(filesCursor.getColumnIndexOrThrow("visualSimilarityHash")))
        filesCursor.close()
        
        // Verify tables exist
        val tables = listOf("files", "vault_items", "cloud_sync", "plugins")
        for (table in tables) {
            val tc = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'")
            assertTrue("Table $table should exist", tc.moveToFirst())
            tc.close()
        }
        
        db.close()
    }
}
