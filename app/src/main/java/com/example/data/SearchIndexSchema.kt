package com.example.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** Raw FTS5 schema because Room 2.8.4 exposes FTS3/FTS4 annotations, not FTS5. */
internal object SearchIndexSchema {
    fun createFtsIndex(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `file_search_fts` USING fts5(
                `name`, `tags`, `ocrText`,
                content = 'files', content_rowid = 'id',
                tokenize = 'unicode61 categories ''L* N* Co Mn Mc Me''',
                prefix = '2 3'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `files_search_fts_ai` AFTER INSERT ON `files` BEGIN
                INSERT INTO `file_search_fts`(`rowid`, `name`, `tags`, `ocrText`)
                VALUES (new.`id`, new.`name`, new.`tags`, new.`ocrText`);
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `files_search_fts_bu`
            BEFORE UPDATE OF `name`, `tags`, `ocrText` ON `files` BEGIN
                INSERT INTO `file_search_fts`(`file_search_fts`, `rowid`, `name`, `tags`, `ocrText`)
                VALUES ('delete', old.`id`, old.`name`, old.`tags`, old.`ocrText`);
                INSERT INTO `file_search_fts`(`rowid`, `name`, `tags`, `ocrText`)
                VALUES (new.`id`, new.`name`, new.`tags`, new.`ocrText`);
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `files_search_fts_bd` BEFORE DELETE ON `files` BEGIN
                INSERT INTO `file_search_fts`(`file_search_fts`, `rowid`, `name`, `tags`, `ocrText`)
                VALUES ('delete', old.`id`, old.`name`, old.`tags`, old.`ocrText`);
            END
            """.trimIndent()
        )
    }

    fun rebuildFtsIndex(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO `file_search_fts`(`file_search_fts`) VALUES ('rebuild')")
    }

    fun createAnnCleanupTrigger(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `files_ann_ad` AFTER DELETE ON `files` BEGIN
                DELETE FROM `semantic_ann_buckets` WHERE `fileId` = old.`id`;
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `files_ann_au`
            AFTER UPDATE OF `semanticIndexed`, `semanticEmbeddingVersion`, `semanticEmbeddingString` ON `files`
            BEGIN
                DELETE FROM `semantic_ann_buckets` WHERE `fileId` = new.`id`;
            END
            """.trimIndent()
        )
    }
}
