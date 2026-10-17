package io.github.lunalight.paperread.data.local

import android.content.Context
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
 *  2. 版本号 +1 时必须同时提交 `MIGRATION_N_(N+1)` 并登记到 [MIGRATIONS]；
 *  3. **不启用** `fallbackToDestructiveMigration()`：书架、阅读进度、书签是用户数据，
 *     清库重建不可接受。宁可升级时明确报错，也不要静默丢书。
 *
 * 背景：v1.1 及以前 `exportSchema = false` 且未配置任何迁移，
 * 只要动一次数据模型，升级用户就会崩在启动。
 */
@Database(
    entities = [BookEntity::class, ChapterEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        /** 已登记的迁移（升版本时在此追加，顺序无关，Room 按 from/to 匹配）。 */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "paperread.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
