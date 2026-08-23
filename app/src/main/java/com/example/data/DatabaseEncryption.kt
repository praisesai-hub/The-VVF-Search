package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import androidx.annotation.VisibleForTesting
import com.example.security.SecureKeyValueStore
import com.example.security.StringKeyValueStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import net.zetetic.database.sqlcipher.SQLiteDatabase as SqlCipherDatabase

/** Supplies a database passphrase protected at rest by an Android Keystore-backed store. */
class DatabasePassphraseProvider(
    context: Context,
    private val store: StringKeyValueStore = SecureKeyValueStore(
        context = context.applicationContext,
        fileName = "vvf_database_key.secure",
        keyAlias = DATABASE_KEY_ALIAS,
    ),
) {
    fun getPassphrase(): ByteArray {
        val stored = store.getString(KEY_DATABASE_PASSPHRASE)
        if (stored == null) {
            return ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes).also { generated ->
                val encoded = android.util.Base64.encodeToString(generated, android.util.Base64.NO_WRAP)
                check(store.commit(mapOf(KEY_DATABASE_PASSPHRASE to encoded))) {
                    "Unable to durably persist database passphrase"
                }
            }
        }
        val decoded = try {
            android.util.Base64.decode(stored, android.util.Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Stored database passphrase is malformed", error)
        }
        require(decoded.size == PASSPHRASE_BYTES) { "Stored database passphrase has invalid length" }
        return decoded
    }

    private companion object {
        const val DATABASE_KEY_ALIAS = "VVF_SMART_MANAGER_DATABASE_KEY"
        const val KEY_DATABASE_PASSPHRASE = "database_passphrase_v1"
        const val PASSPHRASE_BYTES = 32
    }
}

object SqlCipherSupport {
    private val loaded = AtomicBoolean(false)

    fun ensureLoaded() {
        if (loaded.compareAndSet(false, true)) {
            try {
                System.loadLibrary("sqlcipher")
            } catch (error: LinkageError) {
                loaded.set(false)
                throw IllegalStateException("SQLCipher native library could not be loaded", error)
            }
        }
    }
}

/** Converts an existing plaintext Room database before the encrypted Room builder opens it. */
object DatabaseEncryptionMigrator {
    private const val TEMP_SUFFIX = ".sqlcipher-migration.tmp"
    private val migrationLock = Any()

    fun migrateIfNeeded(context: Context, databaseFile: File, passphrase: ByteArray) {
        if (!databaseFile.isFile) return
        synchronized(migrationLock) {
            if (!databaseFile.isFile) return
            SqlCipherSupport.ensureLoaded()
            if (!isPlaintextDatabase(databaseFile)) {
                verifyEncryptedDatabase(databaseFile, passphrase)
                return
            }
            migratePlaintextDatabase(databaseFile, passphrase)
        }
    }

    @VisibleForTesting
    fun openEncryptedReadOnlyForTesting(
        databaseFile: File,
        passphrase: ByteArray,
    ): SqlCipherDatabase {
        SqlCipherSupport.ensureLoaded()
        return openSqlCipherReadOnly(databaseFile, passphrase)
    }

    private fun isPlaintextDatabase(databaseFile: File): Boolean = try {
        AndroidSQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            AndroidSQLiteDatabase.OPEN_READONLY,
        ).use { source ->
            source.rawQuery("SELECT name FROM sqlite_master LIMIT 1", null).use { cursor ->
                cursor.moveToFirst()
            }
        }
    } catch (_: RuntimeException) {
        false
    }

    private fun verifyEncryptedDatabase(databaseFile: File, passphrase: ByteArray) {
        try {
            openSqlCipherReadOnly(databaseFile, passphrase).use { database ->
                database.rawQuery("SELECT name FROM sqlite_master LIMIT 1", null).use { }
                check(database.isDatabaseIntegrityOk) { "Encrypted database integrity check failed" }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Unable to open existing encrypted database", error)
        }
    }

    private fun migratePlaintextDatabase(databaseFile: File, passphrase: ByteArray) {
        val temporaryFile = File(databaseFile.parentFile, databaseFile.name + TEMP_SUFFIX)
        if (temporaryFile.exists() && !temporaryFile.delete()) {
            throw IOException("Unable to remove incomplete database migration file")
        }

        val source = try {
            AndroidSQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                AndroidSQLiteDatabase.OPEN_READONLY,
            )
        } catch (error: RuntimeException) {
            throw IllegalStateException("Plaintext database could not be opened read-only", error)
        }

        try {
            val sourceCounts = copyPlaintextToEncrypted(source, temporaryFile, passphrase)
            source.close()
            check(!source.isOpen) { "Plaintext database remained open during replacement" }
            verifyEncryptedDatabase(temporaryFile, passphrase)
            syncFile(temporaryFile)
            replaceDatabase(databaseFile, temporaryFile)
            check(!isPlaintextDatabase(databaseFile)) {
                "Plaintext database remained readable after encryption migration"
            }
        } catch (error: Exception) {
            try {
                source.close()
            } catch (_: RuntimeException) {
                // Preserve the migration failure; the plaintext source remains available for retry.
            }
            temporaryFile.delete()
            throw error
        }
    }

    private fun copyPlaintextToEncrypted(
        source: AndroidSQLiteDatabase,
        temporaryFile: File,
        passphrase: ByteArray,
    ): Map<String, Long> {
        val destination = openSqlCipherWritable(temporaryFile, passphrase)
        try {
            val tables = sourceObjectDefinitions(source, "table")
                .filterKeys { !it.startsWith("sqlite_", ignoreCase = true) }
            val indexes = sourceObjectDefinitions(source, "index")
            val triggers = sourceObjectDefinitions(source, "trigger")
            val views = sourceObjectDefinitions(source, "view")
            destination.beginTransaction()
            try {
                tables.values.forEach(destination::execSQL)
                val sourceCounts = linkedMapOf<String, Long>()
                tables.keys.forEach { tableName ->
                    copyTableRows(source, destination, tableName)
                    val sourceCount = countRows(source, tableName)
                    val destinationCount = countRows(destination, tableName)
                    check(sourceCount == destinationCount) {
                        "Row-count mismatch while migrating table $tableName: $sourceCount != $destinationCount"
                    }
                    sourceCounts[tableName] = sourceCount
                }
                indexes.values.forEach(destination::execSQL)
                triggers.values.forEach(destination::execSQL)
                views.values.forEach(destination::execSQL)
                destination.version = source.version
                destination.setTransactionSuccessful()
                return sourceCounts
            } finally {
                destination.endTransaction()
            }
        } finally {
            destination.close()
        }
    }

    private fun sourceObjectDefinitions(
        source: AndroidSQLiteDatabase,
        type: String,
    ): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        source.rawQuery(
            "SELECT name, sql FROM sqlite_master WHERE type = ? AND sql IS NOT NULL ORDER BY name",
            arrayOf(type),
        ).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val sqlIndex = cursor.getColumnIndexOrThrow("sql")
            while (cursor.moveToNext()) {
                result[cursor.getString(nameIndex)] = cursor.getString(sqlIndex)
            }
        }
        return result
    }

    private fun copyTableRows(
        source: AndroidSQLiteDatabase,
        destination: SqlCipherDatabase,
        tableName: String,
    ) {
        val columns = mutableListOf<String>()
        source.rawQuery("PRAGMA table_info(${quoteIdentifier(tableName)})", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        if (columns.isEmpty()) return

        source.query(
            tableName,
            columns.toTypedArray(),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val values = ContentValues(columns.size)
                columns.forEachIndexed { index, column ->
                    putCursorValue(values, column, cursor, index)
                }
                check(destination.insertOrThrow(tableName, null, values) != -1L) {
                    "Unable to copy row into $tableName"
                }
            }
        }
    }

    private fun putCursorValue(values: ContentValues, column: String, cursor: Cursor, index: Int) {
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> values.putNull(column)
            Cursor.FIELD_TYPE_INTEGER -> values.put(column, cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> values.put(column, cursor.getDouble(index))
            Cursor.FIELD_TYPE_STRING -> values.put(column, cursor.getString(index))
            Cursor.FIELD_TYPE_BLOB -> values.put(column, cursor.getBlob(index))
            else -> throw IllegalStateException("Unsupported SQLite value type in $column")
        }
    }

    private fun countRows(database: AndroidSQLiteDatabase, tableName: String): Long =
        database.rawQuery("SELECT COUNT(*) FROM ${quoteIdentifier(tableName)}", null).use {
            check(it.moveToFirst()) { "Unable to count rows in $tableName" }
            it.getLong(0)
        }

    private fun countRows(database: SqlCipherDatabase, tableName: String): Long =
        database.rawQuery("SELECT COUNT(*) FROM ${quoteIdentifier(tableName)}", null).use {
            check(it.moveToFirst()) { "Unable to count rows in $tableName" }
            it.getLong(0)
        }

    private fun openSqlCipherWritable(file: File, passphrase: ByteArray): SqlCipherDatabase =
        SqlCipherDatabase.openOrCreateDatabase(file, passphrase, null, null)

    private fun openSqlCipherReadOnly(file: File, passphrase: ByteArray): SqlCipherDatabase =
        SqlCipherDatabase.openDatabase(
            file.path,
            passphrase,
            null,
            AndroidSQLiteDatabase.OPEN_READONLY,
            null,
            null,
        )

    private fun syncFile(file: File) {
        RandomAccessFile(file, "r").use { it.fd.sync() }
    }

    private fun replaceDatabase(databaseFile: File, temporaryFile: File) {
        check(temporaryFile.renameTo(databaseFile)) {
            "Unable to atomically replace plaintext database"
        }
        syncFile(databaseFile)
    }

    private fun quoteIdentifier(identifier: String): String =
        "`" + identifier.replace("`", "``") + "`"
}
