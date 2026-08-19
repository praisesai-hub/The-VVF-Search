package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.security.DatabasePassphraseProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

private const val LEGACY_VAULT_FORMAT_VERSION = 1
private const val DATABASE_VERSION_BEFORE_VAULT_FORMAT = 4
private const val DATABASE_VERSION_WITH_VAULT_FORMAT = 5
private const val DATABASE_VERSION_WITH_CLOUD_IDEMPOTENCY = 6
private const val DATABASE_VERSION_WITH_CLOUD_RECOVERY = 7
internal const val DATABASE_SCHEMA_VERSION = 8

@Database(
    entities = [
        FileItemEntity::class,
        VaultItemEntity::class,
        VaultOperationEntity::class,
        CloudSyncItemEntity::class,
        PluginEntity::class
    ],
    version = DATABASE_SCHEMA_VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `plugins` (
                        `pluginId` TEXT NOT NULL PRIMARY KEY, 
                        `name` TEXT NOT NULL, 
                        `category` TEXT NOT NULL, 
                        `description` TEXT NOT NULL, 
                        `isEnabled` INTEGER NOT NULL, 
                        `isCore` INTEGER NOT NULL
                    )
                """.trimIndent())

                addColumnIfNotExists(db, "files", "visualSimilarityHash", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "files", "semanticEmbeddingVersion", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "files", "semanticIndexed", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "files", "semanticEmbeddingString", "TEXT NOT NULL DEFAULT ''")

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_name` ON `files` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_tags` ON `files` (`tags`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_ocrText` ON `files` (`ocrText`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "cloud_sync", "filePath", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(
            DATABASE_VERSION_BEFORE_VAULT_FORMAT,
            DATABASE_VERSION_WITH_VAULT_FORMAT
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(
                    db,
                    "vault_items",
                    "vaultFormatVersion",
                    "INTEGER NOT NULL DEFAULT $LEGACY_VAULT_FORMAT_VERSION"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(
            DATABASE_VERSION_WITH_VAULT_FORMAT,
            DATABASE_VERSION_WITH_CLOUD_IDEMPOTENCY
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "cloud_sync", "remoteFileId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "cloud_sync", "idempotencyKey", "TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cloud_sync_idempotencyKey` " +
                        "ON `cloud_sync` (`idempotencyKey`)"
                )
            }
        }
        val MIGRATION_6_7 = object : Migration(
            DATABASE_VERSION_WITH_CLOUD_IDEMPOTENCY,
            DATABASE_VERSION_WITH_CLOUD_RECOVERY
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "cloud_sync", "remoteRevisionId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "cloud_sync", "localFileStableId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "cloud_sync", "contentHash", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "cloud_sync", "uploadSessionUri", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "cloud_sync", "lastAttemptAtMs", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "cloud_sync", "attemptCount", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "cloud_sync", "etag", "TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cloud_sync_provider_localFileStableId_contentHash` " +
                        "ON `cloud_sync` (`provider`, `localFileStableId`, `contentHash`)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(
            DATABASE_VERSION_WITH_CLOUD_RECOVERY,
            DATABASE_SCHEMA_VERSION
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vault_operations` (
                        `id` TEXT NOT NULL,
                        `operationType` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `sourceFileId` INTEGER NOT NULL,
                        `vaultItemId` INTEGER NOT NULL,
                        `sourcePath` TEXT NOT NULL,
                        `encryptedFilePath` TEXT NOT NULL,
                        `encryptedFileName` TEXT NOT NULL,
                        `restoreDestinationPath` TEXT NOT NULL,
                        `originalName` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `ivBase64` TEXT NOT NULL,
                        `isBiometricProtected` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        `recoveryError` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_vault_operations_state` " +
                        "ON `vault_operations` (`state`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_vault_operations_operationType` " +
                        "ON `vault_operations` (`operationType`)"
                )
            }
        }


        private fun addColumnIfNotExists(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            columnDefinition: String
        ) {
            val cursor = db.query("PRAGMA table_info($tableName)")
            var exists = false
            try {
                while (cursor.moveToNext()) {
                    val nameIndex = cursor.getColumnIndex("name")
                    if (nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        if (name.equals(columnName, ignoreCase = true)) {
                            exists = true
                            break
                        }
                    }
                }
            } finally {
                cursor.close()
            }
            if (!exists) {
                db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val passphrase = DatabasePassphraseProvider(context.applicationContext).getOrCreate()
                try {
                    loadSqlCipherOrThrow()
                    DatabaseEncryptionMigrator(
                        context = context.applicationContext,
                        databaseName = DATABASE_NAME,
                        passphrase = passphrase
                    ).ensureEncrypted()
                    val builder = Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DATABASE_NAME
                    )
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )

                    builder.build().also { INSTANCE = it }
                } finally {
                    passphrase.fill(0)
                }
            }
        }

        private fun loadSqlCipherOrThrow() {
            try {
                System.loadLibrary("sqlcipher")
            } catch (error: UnsatisfiedLinkError) {
                throw IllegalStateException("SQLCipher native library is unavailable", error)
            }
        }

        private const val DATABASE_NAME = "vvf_smart_manager_db"
    }
}
