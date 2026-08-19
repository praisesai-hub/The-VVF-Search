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

    @Test
    fun migration5To6_addsCloudRemoteIdAndIdempotencyColumnsWithoutDataLoss() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_cloud_idempotency_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        db.execSQL(
            """
            CREATE TABLE `cloud_sync` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `provider` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `filePath` TEXT NOT NULL DEFAULT '',
                `fileSize` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `lastSyncedMs` INTEGER NOT NULL,
                `isCore` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO cloud_sync (id, provider, fileName, filePath, fileSize, status, lastSyncedMs, isCore)
            VALUES (901, 'GOOGLE_DRIVE', 'saf-document.pdf', 'content://documents/901', 42, 'QUEUED', 12345, 0)
            """.trimIndent()
        )

        AppDatabase.MIGRATION_5_6.migrate(db)

        db.query("SELECT * FROM cloud_sync WHERE id = 901").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("content://documents/901", cursor.getString(cursor.getColumnIndexOrThrow("filePath")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("remoteFileId")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("idempotencyKey")))
        }
        db.close()
    }

    @Test
    fun migration6To7_addsDistributedRecoveryFieldsWithSafeDefaults() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_cloud_recovery_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        db.execSQL(
            """
            CREATE TABLE `cloud_sync` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `provider` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `filePath` TEXT NOT NULL DEFAULT '',
                `fileSize` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `lastSyncedMs` INTEGER NOT NULL,
                `isCore` INTEGER NOT NULL,
                `remoteFileId` TEXT NOT NULL DEFAULT '',
                `idempotencyKey` TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO cloud_sync
            (id, provider, fileName, filePath, fileSize, status, lastSyncedMs, isCore, remoteFileId, idempotencyKey)
            VALUES (902, 'GOOGLE_DRIVE', 'contract.pdf', 'content://documents/902', 84, 'FAILED', 12346, 0, 'drive-902', 'legacy-key')
            """.trimIndent()
        )

        AppDatabase.MIGRATION_6_7.migrate(db)

        db.query("SELECT * FROM cloud_sync WHERE id = 902").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("drive-902", cursor.getString(cursor.getColumnIndexOrThrow("remoteFileId")))
            assertEquals("legacy-key", cursor.getString(cursor.getColumnIndexOrThrow("idempotencyKey")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("remoteRevisionId")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("localFileStableId")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("contentHash")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("uploadSessionUri")))
            assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("lastAttemptAtMs")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("attemptCount")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("etag")))
        }
        db.query("PRAGMA index_list(`cloud_sync`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            assertTrue(
                generateSequence { if (cursor.moveToNext()) cursor.getString(nameColumn) else null }
                    .any { it == "index_cloud_sync_provider_localFileStableId_contentHash" }
            )
        }
        db.close()
    }

    @Test
    fun migration7To8_createsDurableVaultOperationLedgerAndIndexes() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_vault_operation_recovery_migration_db")
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase

        AppDatabase.MIGRATION_7_8.migrate(db)

        db.execSQL(
            """
            INSERT INTO vault_operations
            (id, operationType, state, sourceFileId, vaultItemId, sourcePath, encryptedFilePath,
             encryptedFileName, restoreDestinationPath, originalName, category, sizeBytes,
             ivBase64, isBiometricProtected, createdAtMs, updatedAtMs, recoveryError)
            VALUES
            ('op-1', 'ENCRYPT', 'SOURCE_REMOVAL_PENDING', 7, 0, '/files/source.pdf',
             '/vault/ENC_op-1.vvf', 'ENC_op-1.vvf', '', 'source.pdf', 'DOCUMENTS', 64,
             'AQIDBA==', 0, 123, 124, '')
            """.trimIndent()
        )
        db.query("SELECT * FROM vault_operations WHERE id = 'op-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ENCRYPT", cursor.getString(cursor.getColumnIndexOrThrow("operationType")))
            assertEquals(
                "SOURCE_REMOVAL_PENDING",
                cursor.getString(cursor.getColumnIndexOrThrow("state"))
            )
            assertEquals(7L, cursor.getLong(cursor.getColumnIndexOrThrow("sourceFileId")))
        }
        db.query("PRAGMA index_list(`vault_operations`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val indexes = generateSequence {
                if (cursor.moveToNext()) cursor.getString(nameColumn) else null
            }.toSet()
            assertTrue("state index missing", "index_vault_operations_state" in indexes)
            assertTrue("operation type index missing", "index_vault_operations_operationType" in indexes)
        }
        db.close()
    }
}
