package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Converts an existing plaintext Room database to SQLCipher before Room opens it. */
class DatabaseEncryptionMigrator(
    private val context: Context,
    databaseName: String,
    passphrase: ByteArray
) {
    private val source = context.getDatabasePath(databaseName)
    private val encryptedTemp = context.getDatabasePath("$databaseName.encrypted.new")
    private val plaintextBackup = context.getDatabasePath("$databaseName.plaintext.backup")
    private val journal = DatabaseConversionJournal(
        source = source,
        encryptedTemp = encryptedTemp,
        plaintextBackup = plaintextBackup,
        stateFile = File(context.noBackupFilesDir, "$databaseName.encryption-state")
    )
    private val plaintext = PlaintextDatabaseAccess()
    private val cipher = CipherDatabaseAccess(context, passphrase)

    fun ensureEncrypted() {
        recoverInterruptedConversion()
        if (!source.exists()) return
        if (cipher.isReadable(source)) return
        check(plaintext.isReadable(source)) { "Database cannot be opened with its encrypted key" }
        convertPlaintextDatabase()
    }

    private fun convertPlaintextDatabase() {
        journal.mark(DatabaseConversionState.PREPARED)
        journal.deleteDatabaseArtifacts(encryptedTemp)
        cipher.copyPlaintextInto(source, encryptedTemp, plaintext.readUserVersion(source))
        verifyEquivalent()
        journal.mark(DatabaseConversionState.TEMP_VALIDATED)
        journal.renameOrThrow(source, plaintextBackup)
        journal.mark(DatabaseConversionState.SOURCE_BACKED_UP)
        journal.renameOrThrow(encryptedTemp, source)
        journal.mark(DatabaseConversionState.ENCRYPTED_INSTALLED)
        check(cipher.isReadable(source)) { "Encrypted replacement verification failed" }
        journal.complete()
    }

    private fun recoverInterruptedConversion() {
        when (journal.readState() ?: return) {
            DatabaseConversionState.PREPARED,
            DatabaseConversionState.TEMP_VALIDATED -> journal.recoverBeforeSourceBackup()
            DatabaseConversionState.SOURCE_BACKED_UP -> recoverAfterSourceBackup()
            DatabaseConversionState.ENCRYPTED_INSTALLED -> recoverAfterEncryptedInstall()
        }
    }

    private fun recoverAfterSourceBackup() {
        if (source.exists() && cipher.isReadable(source)) {
            journal.complete()
        } else if (encryptedTemp.exists() && cipher.isReadable(encryptedTemp)) {
            journal.renameOrThrow(encryptedTemp, source)
            check(cipher.isReadable(source)) { "Recovered encrypted database verification failed" }
            journal.complete()
        } else {
            rollbackToPlaintext()
        }
    }

    private fun recoverAfterEncryptedInstall() {
        if (source.exists() && cipher.isReadable(source)) {
            journal.complete()
        } else {
            rollbackToPlaintext()
        }
    }

    private fun rollbackToPlaintext(): Nothing {
        check(plaintextBackup.exists()) { "Database conversion recovery source is unavailable" }
        journal.deleteDatabaseArtifacts(source)
        journal.renameOrThrow(plaintextBackup, source)
        journal.deleteDatabaseArtifacts(encryptedTemp)
        journal.clearState()
        error("Encrypted database conversion was rolled back; retry required")
    }

    private fun verifyEquivalent() {
        check(plaintext.readInventory(source) == cipher.readInventory(encryptedTemp)) {
            "Encrypted database content verification failed"
        }
        check(plaintext.readUserVersion(source) == cipher.readUserVersion(encryptedTemp)) {
            "Encrypted database version verification failed"
        }
    }
}

private enum class DatabaseConversionState { PREPARED, TEMP_VALIDATED, SOURCE_BACKED_UP, ENCRYPTED_INSTALLED }

private class DatabaseConversionJournal(
    private val source: File,
    private val encryptedTemp: File,
    private val plaintextBackup: File,
    private val stateFile: File
) {
    fun mark(state: DatabaseConversionState) {
        val temporary = File(stateFile.parentFile, "${stateFile.name}.tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(state.name.toByteArray(Charsets.US_ASCII))
                output.flush()
                output.fd.sync()
            }
            renameOrThrow(temporary, stateFile)
        } catch (error: IOException) {
            temporary.delete()
            throw IllegalStateException("Unable to persist database conversion state", error)
        }
    }

    fun readState(): DatabaseConversionState? {
        if (!stateFile.exists()) return null
        val value = stateFile.readText(Charsets.US_ASCII).trim()
        return runCatching { DatabaseConversionState.valueOf(value) }.getOrElse {
            throw IllegalStateException("Invalid database conversion state")
        }
    }

    fun recoverBeforeSourceBackup() {
        if (!source.exists() && plaintextBackup.exists()) renameOrThrow(plaintextBackup, source)
        deleteDatabaseArtifacts(encryptedTemp)
        if (source.exists()) clearState()
    }

    fun complete() {
        deleteDatabaseArtifacts(plaintextBackup)
        deleteDatabaseArtifacts(encryptedTemp)
        clearState()
    }

    fun clearState() = deleteIfExists(stateFile)

    fun renameOrThrow(from: File, to: File) {
        check(from.exists()) { "Required database file is missing" }
        deleteIfExists(to)
        if (!from.renameTo(to)) throw IOException("Atomic database file replacement failed")
    }

    fun deleteDatabaseArtifacts(file: File) {
        deleteIfExists(file)
        deleteIfExists(File(file.parentFile, "${file.name}-journal"))
        deleteIfExists(File(file.parentFile, "${file.name}-wal"))
        deleteIfExists(File(file.parentFile, "${file.name}-shm"))
    }

    private fun deleteIfExists(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Unable to remove database artifact")
    }
}

private class PlaintextDatabaseAccess {
    fun isReadable(file: File): Boolean = try {
        open(file) { database ->
            database.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
        true
    } catch (_: Exception) {
        false
    }

    fun readUserVersion(file: File): Int = open(file) { database ->
        database.rawQuery("PRAGMA user_version", null).use { cursor ->
            check(cursor.moveToFirst()) { "Missing plaintext database version" }
            cursor.getInt(0)
        }
    }

    fun readInventory(file: File): Map<String, Long> = open(file, ::readInventory)

    private fun <T> open(file: File, action: (FrameworkSQLiteDatabase) -> T): T =
        FrameworkSQLiteDatabase.openDatabase(file.absolutePath, null, FrameworkSQLiteDatabase.OPEN_READONLY).use(action)

    private fun readInventory(database: FrameworkSQLiteDatabase): Map<String, Long> =
        DatabaseInventory.readTables { sql -> database.rawQuery(sql, null) }
}

private class CipherDatabaseAccess(
    private val context: Context,
    private val passphrase: ByteArray
) {
    fun copyPlaintextInto(source: File, destination: File, version: Int) = withDatabase(destination) { encrypted ->
        encrypted.execSQL("ATTACH DATABASE ? AS plaintext KEY ''", arrayOf(source.absolutePath))
        try {
            encrypted.query("SELECT sqlcipher_export('main', 'plaintext')").use { cursor ->
                check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "SQLCipher export failed" }
            }
        } finally {
            encrypted.execSQL("DETACH DATABASE plaintext")
        }
        encrypted.execSQL("PRAGMA user_version = $version")
    }

    fun isReadable(file: File): Boolean = try {
        withDatabase(file) { database ->
            database.query("SELECT count(*) FROM sqlite_master").use { it.moveToFirst() }
        }
        true
    } catch (_: Exception) {
        false
    }

    fun readUserVersion(file: File): Int = withDatabase(file) { database ->
        database.query("PRAGMA user_version").use { cursor ->
            check(cursor.moveToFirst()) { "Missing encrypted database version" }
            cursor.getInt(0)
        }
    }

    fun readInventory(file: File): Map<String, Long> = withDatabase(file) { database ->
        DatabaseInventory.readTables(database::query)
    }

    private fun <T> withDatabase(file: File, action: (SupportSQLiteDatabase) -> T): T {
        check(file.parentFile?.exists() == true || file.parentFile?.mkdirs() == true) {
            "Unable to create database directory"
        }
        val helper = SupportOpenHelperFactory(passphrase.copyOf()).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.name)
                .callback(object : SupportSQLiteOpenHelper.Callback(DATABASE_SCHEMA_VERSION) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        return try {
            action(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }
}

private object DatabaseInventory {
    fun readTables(query: (String) -> android.database.Cursor): Map<String, Long> {
        val tables = mutableListOf<String>()
        query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name"
        ).use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        return buildMap {
            tables.forEach { table ->
                val safeTable = table.replace("`", "``")
                query("SELECT COUNT(*) FROM `$safeTable`").use { cursor ->
                    check(cursor.moveToFirst()) { "Unable to count database table" }
                    put(table, cursor.getLong(0))
                }
            }
        }
    }
}
