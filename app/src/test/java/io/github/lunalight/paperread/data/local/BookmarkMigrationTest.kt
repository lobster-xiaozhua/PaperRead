package io.github.lunalight.paperread.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lunalight.paperread.data.repository.BookRepositoryImpl
import io.github.lunalight.paperread.domain.repository.BookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 书签 schema v1 → v2 迁移测试（T-006 · R-06 / G6④）。
 *
 * 演练对象是 v1.2.0 真实发布 schema（[helper] 从测试 assets 加载的
 * `1.json`）：建 v1 库 → 灌存量书签（含外键所属书籍）→ 走
 * AutoMigration(1→2) 升级到 v2 → 断言行数不变、既有字段不丢、
 * 新增列回填默认值（note = ""、updatedAt = 0），且升级后的库经
 * DAO / 仓储可正常读写。
 *
 * schema JSON 由 KSP 导出至 `app/schemas/`，测试 assets 内的副本
 * （`app/src/test/assets/<数据库类全名>/`）与之一致性由
 * `scripts/check-room-migration.sh` 在 CI 中断言，防止只改 schema
 * 忘了同步测试夹具、迁移测试空跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarkMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private val dbName = "bookmark-migration-test.db"

    /** v1 存量书籍行（书签外键指向它；FK CASCADE 语义在 v2 不变）。 */
    private fun seedLegacyBook(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO books (
                id, title, author, format, encoding, sha256, fileSize, dirName,
                totalChars, chapterCount, lastChapterIndex, lastParagraphIndex,
                lastScrollOffset, lastCharOffset, progressPercent, lastReadAt, addedAt
            ) VALUES (
                1, '荒原之始', '未知', 'TXT', 'UTF-8', 'legacy-sha-1', 2048, 'dir-1',
                1000, 2, 0, 0, 0, 0, 0.0, NULL, 1000
            )
            """.trimIndent(),
        )
    }

    /** v1 存量书签行（v1 schema 无 note / updatedAt 列，INSERT 不含它们）。 */
    private fun seedLegacyBookmark(
        db: SupportSQLiteDatabase,
        id: Long,
        excerpt: String,
        createdAt: Long,
    ) {
        db.execSQL(
            """
            INSERT INTO bookmarks (
                id, bookId, chapterIndex, paragraphIndex, charOffset, excerpt, createdAt
            ) VALUES ($id, 1, 2, 3, 42, '$excerpt', $createdAt)
            """.trimIndent(),
        )
    }

    /** 打开已迁移完成的 v2 库（文件级句柄由 [helper] 持有，Room 侧只读同文件）。 */
    private fun openMigratedRoom(): AppDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            dbName,
        )
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()

    @Test
    fun `v1 到 v2 迁移保留存量书签并回填 note 与 updatedAt 默认值`() {
        helper.createDatabase(dbName, 1).use { db ->
            seedLegacyBook(db)
            seedLegacyBookmark(db, id = 7L, excerpt = "风把沙丘吹成另一种形状", createdAt = 1_000L)
            seedLegacyBookmark(db, id = 9L, excerpt = "第二十三条书签", createdAt = 2_000L)
        }

        // runMigrationsAndValidate：应用已登记迁移（含 AutoMigration）后，
        // 按导出的 2.json 校验升级结果 schema 一致（字段 / 默认值 / 哈希）。
        helper.runMigrationsAndValidate(dbName, 2, true).use { db ->
            db.query(
                "SELECT id, bookId, chapterIndex, paragraphIndex, charOffset, excerpt, " +
                    "createdAt, note, updatedAt FROM bookmarks ORDER BY id",
            ).use { cursor ->
                assertEquals("存量书签行数不得丢失", 2, cursor.count)
                assertTrue(cursor.moveToFirst())

                // 既有字段原样保留
                assertEquals(7L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("bookId")))
                assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow("chapterIndex")))
                assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("paragraphIndex")))
                assertEquals(42, cursor.getInt(cursor.getColumnIndexOrThrow("charOffset")))
                assertEquals(
                    "风把沙丘吹成另一种形状",
                    cursor.getString(cursor.getColumnIndexOrThrow("excerpt")),
                )
                assertEquals(1_000L, cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")))

                // 新增列回填默认值（@ColumnInfo defaultValue）
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("note")))
                assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")))

                assertTrue(cursor.moveToNext())
                assertEquals(9L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("note")))
                assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")))
            }
        }
    }

    @Test
    fun `迁移后的库经 DAO 可正常读写书签`() {
        helper.createDatabase(dbName, 1).use { db ->
            seedLegacyBook(db)
            seedLegacyBookmark(db, id = 7L, excerpt = "迁移前的老书签", createdAt = 1_000L)
        }
        helper.runMigrationsAndValidate(dbName, 2, true)

        val room = openMigratedRoom()
        try {
            val dao = room.bookmarkDao()
            // 迁移产物就是真实 AppDatabase：DAO 查询走 Room 生成的实现，
            // 覆盖「列新增后既有 SQL / 实体映射仍工作」这一回归面。
            val loaded = runBlocking { dao.getByBook(1L) }
            assertEquals(1, loaded.size)
            assertEquals(7L, loaded.single().id)
            assertEquals("迁移前的老书签", loaded.single().excerpt)
            assertEquals("", loaded.single().note)
            assertEquals(0L, loaded.single().updatedAt)

            // v2 新链路：备注更新落库
            runBlocking { dao.updateNote(7L, "读完想来一杯", 1_700_000_000_000L) }
            val updated = runBlocking { dao.getByBook(1L).single() }
            assertEquals("读完想来一杯", updated.note)
            assertEquals(1_700_000_000_000L, updated.updatedAt)
        } finally {
            room.close()
        }
    }

    @Test
    fun `仓储层对迁移后存量书签的映射保持兼容`() {
        helper.createDatabase(dbName, 1).use { db ->
            seedLegacyBook(db)
            seedLegacyBookmark(db, id = 7L, excerpt = "经仓储读出的书签", createdAt = 1_000L)
        }
        helper.runMigrationsAndValidate(dbName, 2, true)

        val room = openMigratedRoom()
        try {
            val repository: BookRepository = BookRepositoryImpl(
                room.bookDao(),
                room.chapterDao(),
                room.bookmarkDao(),
            )
            val bookmarks = runBlocking { repository.observeBookmarks(1L).first() }
            assertEquals(1, bookmarks.size)
            assertEquals("经仓储读出的书签", bookmarks.single().excerpt)
            assertEquals("", bookmarks.single().note)
            assertEquals(0L, bookmarks.single().updatedAt)
        } finally {
            room.close()
        }
    }
}
