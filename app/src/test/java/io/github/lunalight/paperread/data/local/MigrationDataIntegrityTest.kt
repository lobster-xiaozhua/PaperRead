package io.github.lunalight.paperread.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1 → v2 迁移的数据完整性演练（QA 补测 · T-006 / G6④ 的第二道防线）。
 *
 * 与 [BookmarkMigrationTest] 的分工：那边聚焦「书签表在迁移后仍可读写、
 * 新增列回填默认值」；本类聚焦 PRD 风险表 #4「首次 Room 迁移失败 = 升级
 * 崩溃或**静默丢书**」的另一半——迁移是整库操作，books / chapters 两张
 * 与本次 schema 变更无关的表**必须原样保留**。既有用例只灌了 books 一行，
 * 迁移后从未断言 books / chapters 的行数与字段，若 AutoMigration 意外
 * 重建了某张表（例如 Room 判定需要 drop-recreate），既有用例仍会全绿，
 * 而用户会丢书架与章节进度。本类把这一面钉死：
 *
 *  1. books 两行（含 lastReadAt = NULL 与 = 值的两种语义）逐字段不变；
 *  2. chapters 两行逐字段不变；
 *  3. bookmarks 行数不变（与既有用例一致，此处作为整体口径复述）；
 *  4. schema 形态：`PRAGMA user_version` = 2、bookmarks 的 DDL 含
 *     note / updatedAt 的 NOT NULL + DEFAULT、bookId 索引与
 *     `ON DELETE CASCADE` 外键均保留；
 *  5. 迁移后经 Room 重新打开（身份哈希校验通过、无 destructive 重建），
 *     数据仍在——覆盖「升级后第二次启动」这一真实用户路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationDataIntegrityTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private val dbName = "migration-data-integrity-test.db"

    /** v1 形态的两本书：一本读过（lastReadAt 有值），一本未读（NULL）。 */
    private fun seedLegacyBooks(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO books (
                id, title, author, format, encoding, sha256, fileSize, dirName,
                totalChars, chapterCount, lastChapterIndex, lastParagraphIndex,
                lastScrollOffset, lastCharOffset, progressPercent, lastReadAt, addedAt
            ) VALUES (
                1, '读过一半的书', '作者甲', 'TXT', 'UTF-8', 'sha-read', 4096, 'dir-read',
                5000, 2, 1, 7, 320, 4321, 0.61, 1700000000000, 1000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO books (
                id, title, author, format, encoding, sha256, fileSize, dirName,
                totalChars, chapterCount, lastChapterIndex, lastParagraphIndex,
                lastScrollOffset, lastCharOffset, progressPercent, lastReadAt, addedAt
            ) VALUES (
                2, '刚导入的书', '作者乙', 'EPUB', 'GBK', 'sha-new', 8192, 'dir-new',
                0, 0, 0, 0, 0, 0, 0.0, NULL, 2000
            )
            """.trimIndent(),
        )
    }

    /** v1 形态的章节目录（第二本书尚未解析章节，故只有第一本的两行）。 */
    private fun seedLegacyChapters(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO chapters (
                id, bookId, `index`, title, charStart, charEnd,
                byteStart, byteEnd, charCount, cumulativeChars
            ) VALUES (1, 1, 0, '第一章 起', 0, 2000, 0, 4000, 2000, 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO chapters (
                id, bookId, `index`, title, charStart, charEnd,
                byteStart, byteEnd, charCount, cumulativeChars
            ) VALUES (2, 1, 1, '第二章 承', 2000, 5000, 4000, 9000, 3000, 2000)
            """.trimIndent(),
        )
    }

    private fun seedLegacyBookmarks(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO bookmarks (
                id, bookId, chapterIndex, paragraphIndex, charOffset, excerpt, createdAt
            ) VALUES (11, 1, 1, 7, 4321, '存量书签甲', 1500)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO bookmarks (
                id, bookId, chapterIndex, paragraphIndex, charOffset, excerpt, createdAt
            ) VALUES (12, 1, 0, 2, 900, '存量书签乙', 1800)
            """.trimIndent(),
        )
    }

    private fun longOf(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            assertTrue("查询应有结果：$sql", cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun textOf(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { cursor ->
            assertTrue("查询应有结果：$sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun countOf(db: SupportSQLiteDatabase, table: String): Long =
        longOf(db, "SELECT COUNT(*) FROM $table")

    @Test
    fun `迁移不得丢书架与章节数据且库版本推进到 2`() {
        helper.createDatabase(dbName, 1).use { db ->
            seedLegacyBooks(db)
            seedLegacyChapters(db)
            seedLegacyBookmarks(db)
        }

        helper.runMigrationsAndValidate(dbName, 2, true).use { db ->
            // ---- 1) 行数不变（静默丢书的第一道体检） ----
            assertEquals("迁移不得丢书架行", 2L, countOf(db, "books"))
            assertEquals("迁移不得丢章节目录", 2L, countOf(db, "chapters"))
            assertEquals("迁移不得丢书签", 2L, countOf(db, "bookmarks"))

            // ---- 2) books 逐字段不变（含 lastReadAt 的 NULL / 有值两种语义） ----
            assertEquals("读过一半的书", textOf(db, "SELECT title FROM books WHERE id = 1"))
            assertEquals("作者甲", textOf(db, "SELECT author FROM books WHERE id = 1"))
            assertEquals("sha-read", textOf(db, "SELECT sha256 FROM books WHERE id = 1"))
            assertEquals(1L, longOf(db, "SELECT lastChapterIndex FROM books WHERE id = 1"))
            assertEquals(7L, longOf(db, "SELECT lastParagraphIndex FROM books WHERE id = 1"))
            assertEquals(4321L, longOf(db, "SELECT lastCharOffset FROM books WHERE id = 1"))
            assertEquals(
                "阅读进度不得重置（0.61 → 61）",
                61L,
                longOf(db, "SELECT CAST(progressPercent * 100 AS INTEGER) FROM books WHERE id = 1"),
            )
            assertEquals(
                1_700_000_000_000L,
                longOf(db, "SELECT lastReadAt FROM books WHERE id = 1"),
            )
            // lastReadAt 为 NULL 的行不得被迁移「填成 0」（0 会被 UI 当成 1970 年读过）
            assertNull(
                "未读书目的 lastReadAt 必须保持 NULL",
                textOf(db, "SELECT lastReadAt FROM books WHERE id = 2"),
            )
            assertEquals(0L, longOf(db, "SELECT chapterCount FROM books WHERE id = 2"))

            // ---- 3) chapters 逐字段不变 ----
            assertEquals(
                "第二章 承",
                textOf(db, "SELECT title FROM chapters WHERE bookId = 1 AND `index` = 1"),
            )
            assertEquals(
                9000L,
                longOf(db, "SELECT byteEnd FROM chapters WHERE bookId = 1 AND `index` = 1"),
            )
            assertEquals(
                2000L,
                longOf(db, "SELECT cumulativeChars FROM chapters WHERE bookId = 1 AND `index` = 1"),
            )

            // ---- 4) schema 形态与约束 ----
            assertEquals("迁移后 user_version 必须为 2", 2L, longOf(db, "PRAGMA user_version"))

            val bookmarksDdl = textOf(
                db,
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'bookmarks'",
            ).orEmpty()
            assertTrue(
                "bookmarks DDL 应包含新增列 note：$bookmarksDdl",
                bookmarksDdl.contains("note", ignoreCase = true),
            )
            assertTrue(
                "bookmarks DDL 应包含新增列 updatedAt：$bookmarksDdl",
                bookmarksDdl.contains("updatedAt", ignoreCase = true),
            )
            assertTrue(
                "外键 CASCADE 语义必须保留（删书即删书签）：$bookmarksDdl",
                bookmarksDdl.replace(" ", "").contains("ONDELETECASCADE", ignoreCase = true),
            )
            assertEquals(
                "bookId 索引必须保留（书签列表按书过滤）",
                1L,
                longOf(
                    db,
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'index' AND tbl_name = 'bookmarks' AND name = 'index_bookmarks_bookId'",
                ),
            )
        }
    }

    @Test
    fun `迁移后删书仍级联删除书签`() {
        helper.createDatabase(dbName, 1).use { db ->
            seedLegacyBooks(db)
            seedLegacyBookmarks(db)
        }
        helper.runMigrationsAndValidate(dbName, 2, true).use { db ->
            // 显式开启外键约束：断言的是 schema 里的 ON DELETE CASCADE 子句，
            // 而不是运行环境是否默认开启（与生产 Room 行为解耦）。
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM books WHERE id = 1")
            assertEquals("删书必须级联删书签（否则遗留孤儿行）", 0L, countOf(db, "bookmarks"))
        }
    }

    @Test
    fun `迁移后经 Room 重新打开数据仍在且不触发清库`() {
        helper.createDatabase(dbName, 1).use { db ->
            seedLegacyBooks(db)
            seedLegacyChapters(db)
            seedLegacyBookmarks(db)
        }
        helper.runMigrationsAndValidate(dbName, 2, true)

        // 再次经 Room 打开同一文件：身份哈希校验若不通过会抛
        // IllegalStateException（「Room cannot verify the data integrity」），
        // 库被 destructive 清空则下面的行数断言失败——覆盖升级后第二次启动。
        val room = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            dbName,
        )
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()
        try {
            val books = runBlocking { room.bookDao().getById(1L) }
            assertEquals("升级后书架仍在", "读过一半的书", books?.title)
            val chapters = runBlocking { room.chapterDao().getByBook(1L) }
            assertEquals("升级后章节目录仍在", 2, chapters.size)
            val bookmarks = runBlocking { room.bookmarkDao().getByBook(1L) }
            assertEquals("升级后书签仍在", 2, bookmarks.size)
            assertEquals("新增列默认值仍是空串", "", bookmarks.first().note)
        } finally {
            room.close()
        }
    }
}
