package com.example.security

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.util.Locale

/** Converts the legacy plaintext Room database to SQLCipher before Room opens it. */
object RoomDatabaseEncryptionMigration {
    fun migrateIfNeeded(context: Context, databaseName: String, key: ByteArray) {
        System.loadLibrary("sqlcipher")
        val databaseFile = context.getDatabasePath(databaseName)
        if (!databaseFile.exists()) return

        if (isAlreadyEncrypted(databaseFile, key)) return
        requirePlaintextDatabase(databaseFile)

        val temporaryFile = File(databaseFile.parentFile, "$databaseName.encrypted.tmp")
        val backupFile = File(databaseFile.parentFile, "$databaseName.plaintext.backup")
        SQLiteDatabase.deleteDatabase(temporaryFile)
        SQLiteDatabase.deleteDatabase(backupFile)

        val plaintextDatabase = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null
        )
        val databaseVersion = try {
            plaintextDatabase.version
        } finally {
            plaintextDatabase.close()
        }

        try {
            val encryptedDatabase = SQLiteDatabase.openDatabase(
                temporaryFile.absolutePath,
                key,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
                null,
                null
            )
            try {
                val attach = encryptedDatabase.compileStatement("ATTACH DATABASE ? AS plaintext KEY ''")
                try {
                    attach.bindString(1, databaseFile.absolutePath)
                    attach.execute()
                    encryptedDatabase.rawQuery("SELECT sqlcipher_export('main', 'plaintext')", null).use {
                        check(it.moveToFirst() || it.count == 0) { "SQLCipher export returned no result" }
                    }
                    encryptedDatabase.rawExecSQL("DETACH DATABASE plaintext")
                    encryptedDatabase.version = databaseVersion
                } finally {
                    attach.close()
                }
            } finally {
                encryptedDatabase.close()
            }

            deleteSidecars(databaseFile)
            check(databaseFile.renameTo(backupFile)) { "Unable to stage legacy plaintext Room database" }
            try {
                check(temporaryFile.renameTo(databaseFile)) {
                    "Unable to install encrypted Room database"
                }
                deleteSidecars(backupFile)
                check(backupFile.delete()) { "Unable to remove legacy plaintext Room database" }
            } catch (error: Throwable) {
                databaseFile.delete()
                check(backupFile.renameTo(databaseFile)) {
                    "Unable to restore legacy Room database after encryption failure"
                }
                throw error
            }
        } catch (error: Throwable) {
            SQLiteDatabase.deleteDatabase(temporaryFile)
            throw IllegalStateException("Failed to encrypt the existing Room database", error)
        } finally {
            SQLiteDatabase.deleteDatabase(temporaryFile)
            SQLiteDatabase.deleteDatabase(backupFile)
        }
    }

    private fun deleteSidecars(databaseFile: File) {
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
    }

    private fun isAlreadyEncrypted(databaseFile: File, key: ByteArray): Boolean = runCatching {
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            key,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
            null
        ).use { database ->
            database.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
        true
    }.getOrDefault(false)

    private fun requirePlaintextDatabase(databaseFile: File) {
        runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                ByteArray(0),
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
                null
            ).use { database ->
                database.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            }
        }.getOrElse {
            throw IllegalStateException(
                String.format(Locale.US, "Room database is neither valid encrypted nor valid plaintext: %s", databaseFile),
                it
            )
        }
    }
}
