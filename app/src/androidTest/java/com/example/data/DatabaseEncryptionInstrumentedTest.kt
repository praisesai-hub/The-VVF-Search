package com.example.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseFile = context.getDatabasePath("vvf-encryption-migration-${System.nanoTime()}.db")
    private val passphrase = ByteArray(32) { (it + 1).toByte() }

    @After
    fun tearDown() {
        databaseFile.delete()
        File(databaseFile.path + ".sqlcipher-migration.tmp").delete()
        passphrase.fill(0)
    }

    @Test
    fun plaintextDatabaseMigratesToEncryptedFileAndPreservesRows() {
        createPlaintextFixture()
        assertTrue(DatabaseEncryptionMigrator.isPlaintextDatabaseForTesting(databaseFile))

        DatabaseEncryptionMigrator.migrateIfNeeded(context, databaseFile, passphrase)

        assertFalse(DatabaseEncryptionMigrator.isPlaintextDatabaseForTesting(databaseFile))
        assertFalse(databaseFile.readBytes().copyOfRange(0, 16).toString(Charsets.US_ASCII).startsWith("SQLite format 3"))
        DatabaseEncryptionMigrator.openEncryptedReadOnlyForTesting(databaseFile, passphrase).use { database ->
            assertEquals(1L, count(database, "files"))
            assertEquals(1L, count(database, "plugins"))
            assertEquals("migration-fixture.txt", database.rawQuery(
                "SELECT name FROM files WHERE id = 101",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            })
        }
    }

    @Test
    fun migratedDatabaseRejectsWrongKey() {
        createPlaintextFixture()
        DatabaseEncryptionMigrator.migrateIfNeeded(context, databaseFile, passphrase)

        val wrongKey = ByteArray(32) { 0x5A }
        var rejected = false
        try {
            DatabaseEncryptionMigrator.openEncryptedReadOnlyForTesting(databaseFile, wrongKey).use { database ->
                database.rawQuery("SELECT COUNT(*) FROM files", null).use { it.moveToFirst() }
            }
        } catch (_: Exception) {
            rejected = true
        } finally {
            wrongKey.fill(0)
        }
        assertTrue("An encrypted database must reject an incorrect key", rejected)
    }

    private fun createPlaintextFixture() {
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            database.execSQL("CREATE TABLE files (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL)")
            database.execSQL("CREATE TABLE plugins (pluginId TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
            database.execSQL("CREATE TABLE vault_items (id INTEGER PRIMARY KEY NOT NULL, originalName TEXT NOT NULL)")
            database.execSQL("CREATE TABLE cloud_sync (id INTEGER PRIMARY KEY NOT NULL, fileName TEXT NOT NULL)")
            database.execSQL("CREATE TABLE work_operations (operationId TEXT PRIMARY KEY NOT NULL, status TEXT NOT NULL)")
            database.execSQL("CREATE TABLE file_operations (operationId TEXT PRIMARY KEY NOT NULL, status TEXT NOT NULL)")
            database.insertOrThrow("files", null, ContentValues().apply {
                put("id", 101L)
                put("name", "migration-fixture.txt")
                put("path", "/data/user/0/com.example/files/migration-fixture.txt")
            })
            database.insertOrThrow("plugins", null, ContentValues().apply {
                put("pluginId", "ocr_engine")
                put("name", "OCR")
            })
            database.insertOrThrow("vault_items", null, ContentValues().apply {
                put("id", 201L)
                put("originalName", "secret.txt")
            })
            database.insertOrThrow("cloud_sync", null, ContentValues().apply {
                put("id", 301L)
                put("fileName", "cloud.txt")
            })
            database.insertOrThrow("work_operations", null, ContentValues().apply {
                put("operationId", "work-401")
                put("status", "COMMITTED")
            })
            database.insertOrThrow("file_operations", null, ContentValues().apply {
                put("operationId", "file-501")
                put("status", "COMMITTED")
            })
            database.version = 10
        }
    }

    private fun count(database: net.zetetic.database.sqlcipher.SQLiteDatabase, tableName: String): Long =
        database.rawQuery("SELECT COUNT(*) FROM `$tableName`", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
