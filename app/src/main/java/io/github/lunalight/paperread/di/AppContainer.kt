package io.github.lunalight.paperread.di

import android.content.Context
import io.github.lunalight.paperread.data.local.AppDatabase
import io.github.lunalight.paperread.data.local.BookDao
import io.github.lunalight.paperread.data.local.BookmarkDao
import io.github.lunalight.paperread.data.local.ChapterDao
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.BookRepositoryImpl
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore

/**
 * 手动依赖容器（服务定位器）。
 *
 * 本项目依赖点约 5 个，选择手动 DI 而非 Hilt：构建更快、贡献者上手成本更低。
 * 若未来依赖规模增长，可平滑替换为 Hilt 而不影响上层。
 *
 * M2：装配 Room 数据库、BookRepository、BookTextStore、BookImporter。
 */
class AppContainer(context: Context) {

    private val applicationContext = context.applicationContext

    private val database: AppDatabase by lazy { AppDatabase.build(applicationContext) }

    private val bookDao: BookDao by lazy { database.bookDao() }

    private val chapterDao: ChapterDao by lazy { database.chapterDao() }

    private val bookmarkDao: BookmarkDao by lazy { database.bookmarkDao() }

    val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(bookDao, chapterDao, bookmarkDao)
    }

    val bookTextStore: BookTextStore by lazy { BookTextStore(applicationContext) }

    val bookImporter: BookImporter by lazy {
        BookImporter(applicationContext, bookRepository, bookTextStore)
    }
}
