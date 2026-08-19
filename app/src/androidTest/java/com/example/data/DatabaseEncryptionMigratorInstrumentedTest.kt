package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionMigratorInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var passphrase: ByteArray

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "cipher_migration_${System.nanoTime()}.db"
        passphrase = ByteArray(32) { (it + 1).toByte() }
        System.loadLibrary("sqlcipher")
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
        context.deleteDatabase("$databaseName.encrypted.new")
        context.deleteDatabase("$databaseName.plaintext.backup")
        File(context.noBackupFilesDir, "$databaseName.encryption-state").delete()
    }

    @Test
    fun ensureEncrypted_convertsPlaintextDatabaseAndPreservesRows() {
        createPlaintextDatabase()

        DatabaseEncryptionMigrator(context, databaseName, passphrase).ensureEncrypted()

        assertPlaintextOpenFails()
        val helper = SupportOpenHelperFactory(passphrase.copyOf()).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(emptyCallback(DATABASE_SCHEMA_VERSION))
                .build()
        )
        try {
            helper.readableDatabase.query("SELECT secret FROM private_metadata WHERE id = 7").use { cursor ->
                require(cursor.moveToFirst())
                assertEquals("sensitive OCR text", cursor.getString(0))
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun ensureEncrypted_preservesRoomRowsAndReopensWithProductionSchema() = runBlocking {
        val plaintextRoom = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()
        plaintextRoom.fileDao().insertFileDirect(
            FileItemEntity(
                name = "private-invoice.pdf",
                path = "content://documents/private-invoice",
                category = "DOCUMENTS",
                sizeBytes = 2048,
                ocrText = "sensitive invoice amount",
                tags = "finance,private",
                semanticEmbeddingString = "0.1,0.2"
            )
        )
        plaintextRoom.close()

        DatabaseEncryptionMigrator(context, databaseName, passphrase).ensureEncrypted()

        val encryptedRoom = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
            .build()
        try {
            val row = encryptedRoom.fileDao().getFileByPath("content://documents/private-invoice")
            requireNotNull(row)
            assertEquals("private-invoice.pdf", row.name)
            assertEquals("sensitive invoice amount", row.ocrText)
            assertEquals("finance,private", row.tags)
            assertEquals("0.1,0.2", row.semanticEmbeddingString)
        } finally {
            encryptedRoom.close()
        }
    }

    private fun createPlaintextDatabase() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(emptyCallback(1))
                .build()
        )
        helper.writableDatabase.use { database ->
            database.execSQL("CREATE TABLE private_metadata (id INTEGER PRIMARY KEY, secret TEXT NOT NULL)")
            database.execSQL("INSERT INTO private_metadata (id, secret) VALUES (7, 'sensitive OCR text')")
            database.execSQL("PRAGMA user_version = 8")
        }
        helper.close()
    }

    private fun assertPlaintextOpenFails() {
        try {
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { database ->
                database.rawQuery("SELECT secret FROM private_metadata", null).use { it.moveToFirst() }
            }
            fail("Plain SQLite must not read the SQLCipher database")
        } catch (_: SQLiteException) {
            // Expected: the encrypted database header is not readable by framework SQLite.
        }
    }

    private fun emptyCallback(version: Int) = object : SupportSQLiteOpenHelper.Callback(version) {
        override fun onCreate(db: SupportSQLiteDatabase) = Unit
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
