package io.github.lunalight.paperread.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * 应用数据库。
 *
 * 迁移策略（v1.2 起必须遵守，见 [MIGRATIONS]）：
 *  1. `exportSchema = true` + KSP `room.schemaLocation`：每个版本的 schema 以 JSON 入库，
 *     字段增删会在 PR diff 里显形，也让 Room 的编译期校验真正生效；
 *  2. 版本号 +1 时必须同时提交迁移并登记（[autoMigrations] 或 [MIGRATIONS]），
 *     由 `scripts/check-room-migration.sh` 在 CI 中断言三者一致；
 *  3. **不启用** `fallbackToDestructiveMigration()`：书架、阅读进度、书签是用户数据，
 *     清库重建不可接受。宁可升级时明确报错，也不要静默丢书。
 *
 * 背景：v1.1 及以前 `exportSchema = false` 且未配置任何迁移，
 * 只要动一次数据模型，升级用户就会崩在启动。
 *
 * schema 历史：
 * - v1：v1.2.0 发布形态（书架 / 章节 / 书签）；
 * - v2：书签增 [BookmarkEntity.note] 与 [BookmarkEntity.updatedAt]
 *   （R-18 前置，纯新增带默认值列，AutoMigration 无需 spec，
 *   存量行迁移后 note = "" / updatedAt = 0，见 BookmarkMigrationTest）。
 */
@Database(
    entities = [BookEntity::class, ChapterEntity::class, BookmarkEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        /**
         * 已登记的手写迁移（升版本时在此追加，顺序无关，Room 按 from/to 匹配）。
         *
         * 当前为空：v1→v2 为纯新增带默认值列，走 [autoMigrations] 的
         * AutoMigration；未来出现重命名 / 拆表 / 类型变更等 AutoMigration
         * 无法覆盖的改动时，在此补 `Migration(N, N+1)`。
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "paperread.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
