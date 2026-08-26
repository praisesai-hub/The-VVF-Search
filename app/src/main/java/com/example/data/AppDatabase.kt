package com.example.data

import android.content.Context
import android.os.Build
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.security.RoomDatabaseEncryptionMigration
import com.example.security.RoomDatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

private const val LEGACY_VAULT_FORMAT_VERSION = 1
private const val DATABASE_VERSION_BEFORE_VAULT_FORMAT = 4
private const val DATABASE_VERSION_WITH_VAULT_FORMAT = 5
private const val DATABASE_VERSION_WITH_CLOUD_IDEMPOTENCY = 6
private const val DATABASE_VERSION_WITH_VIDEO_EVIDENCE = 7
private const val DATABASE_VERSION_WITH_DOCUMENT_CANDIDATE_FINGERPRINT = 8
private const val DATABASE_VERSION_WITH_CLOUD_TRANSFER_STATE = 9
private const val DATABASE_VERSION_WITH_FILE_OPERATIONS = 10
private const val DATABASE_VERSION_WITH_CONTENT_IDENTITY = 11
private const val DATABASE_NAME = "vvf_smart_manager_db"

@Database(
    entities = [
        FileItemEntity::class,
        VaultItemEntity::class,
        CloudSyncItemEntity::class,
        PluginEntity::class,
        WorkOperationEntity::class,
        FileOperationEntity::class
    ],
    version = DATABASE_VERSION_WITH_CONTENT_IDENTITY,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun cloudSyncOperationStore(): CloudSyncOperationStore
    abstract fun workOperationStore(): WorkOperationStore
    abstract fun fileOperationStore(): FileOperationStore

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `vault_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `originalName` TEXT NOT NULL, `encryptedName` TEXT NOT NULL, `encryptedFilePath` TEXT NOT NULL, `ivBase64` TEXT NOT NULL, `category` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `encryptedAtMs` INTEGER NOT NULL, `isBiometricProtected` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `cloud_sync` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `provider` TEXT NOT NULL, `fileName` TEXT NOT NULL, `filePath` TEXT NOT NULL DEFAULT '', `fileSize` INTEGER NOT NULL, `status` TEXT NOT NULL, `lastSyncedMs` INTEGER NOT NULL, `isCore` INTEGER NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `plugins` (`pluginId` TEXT NOT NULL PRIMARY KEY, `name` TEXT NOT NULL, `category` TEXT NOT NULL, `description` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, `isCore` INTEGER NOT NULL)")
                addColumnIfNotExists(db, "files", "visualSimilarityHash", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "files", "semanticEmbeddingVersion", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "files", "semanticIndexed", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "files", "semanticEmbeddingString", "TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_name` ON `files` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_tags` ON `files` (`tags`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_ocrText` ON `files` (`ocrText`)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) { addColumnIfNotExists(db, "cloud_sync", "filePath", "TEXT NOT NULL DEFAULT ''") } }
        val MIGRATION_4_5 = object : Migration(4, 5) { override fun migrate(db: SupportSQLiteDatabase) { addColumnIfNotExists(db, "vault_items", "vaultFormatVersion", "INTEGER NOT NULL DEFAULT $LEGACY_VAULT_FORMAT_VERSION") } }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "cloud_sync", "operationId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "cloud_sync", "leaseOwner", "TEXT")
                addColumnIfNotExists(db, "cloud_sync", "leaseExpiresAtMs", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "cloud_sync", "attemptCount", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "cloud_sync", "startedAtMs", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "cloud_sync", "heartbeatAtMs", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "cloud_sync", "completedAtMs", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "cloud_sync", "lastErrorCode", "TEXT")
                db.execSQL("UPDATE cloud_sync SET operationId = 'legacy-' || id WHERE operationId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cloud_sync_operationId` ON `cloud_sync` (`operationId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `work_operations` (`operationId` TEXT NOT NULL, `workName` TEXT NOT NULL, `status` TEXT NOT NULL, `leaseOwner` TEXT, `leaseExpiresAtMs` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `startedAtMs` INTEGER NOT NULL, `heartbeatAtMs` INTEGER NOT NULL, `completedAtMs` INTEGER NOT NULL, `lastErrorCode` TEXT, PRIMARY KEY(`operationId`))")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "files", "videoFingerprintVersion", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "files", "videoSampleHashes", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "files", "videoDurationMs", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "files", "videoWidth", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "files", "videoHeight", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "files", "videoAudioSignature", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "files", "videoChunkHash", "TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) { override fun migrate(db: SupportSQLiteDatabase) { addColumnIfNotExists(db, "files", "documentCandidateFingerprint", "TEXT NOT NULL DEFAULT ''"); db.execSQL("UPDATE files SET documentCandidateFingerprint = visualSimilarityHash WHERE category = 'DOCUMENTS' AND documentCandidateFingerprint = '' AND visualSimilarityHash IS NOT NULL AND visualSimilarityHash <> ''") } }
        val MIGRATION_8_9 = object : Migration(8, 9) { override fun migrate(db: SupportSQLiteDatabase) { addColumnIfNotExists(db, "cloud_sync", "remoteFileId", "TEXT NOT NULL DEFAULT ''"); addColumnIfNotExists(db, "cloud_sync", "resumableSessionUri", "TEXT NOT NULL DEFAULT ''"); addColumnIfNotExists(db, "cloud_sync", "resumableBytesCommitted", "INTEGER NOT NULL DEFAULT 0") } }
        val MIGRATION_9_10 = object : Migration(9, 10) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS `file_operations` (`operationId` TEXT NOT NULL, `operationType` TEXT NOT NULL, `fileId` INTEGER NOT NULL, `sourcePath` TEXT NOT NULL, `targetPath` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `lastErrorCode` TEXT, PRIMARY KEY(`operationId`))") } }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "files", "contentIdentityVersion", "INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_contentIdentityVersion` ON `files` (`contentIdentityVersion`)")
            }
        }

        private fun addColumnIfNotExists(db: SupportSQLiteDatabase, tableName: String, columnName: String, columnDefinition: String) {
            val cursor = db.query("PRAGMA table_info($tableName)")
            var exists = false
            try { while (cursor.moveToNext()) { val i = cursor.getColumnIndex("name"); if (i != -1 && cursor.getString(i).equals(columnName, true)) { exists = true; break } } } finally { cursor.close() }
            if (!exists) db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition")
        }
        private fun isRobolectricRuntime(): Boolean = Build.FINGERPRINT.contains("robolectric", true) || System.getProperty("robolectric") != null

        fun getDatabase(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: run {
                val appContext = context.applicationContext
                val builder = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                if (!isRobolectricRuntime()) {
                    System.loadLibrary("sqlcipher")
                    val databaseKey = RoomDatabaseKeyManager(appContext).getOrCreateKey()
                    RoomDatabaseEncryptionMigration.migrateIfNeeded(appContext, DATABASE_NAME, databaseKey)
                    builder.openHelperFactory(SupportOpenHelperFactory(databaseKey))
                }
                builder.build().also { INSTANCE = it }
            }
        }
    }
}
