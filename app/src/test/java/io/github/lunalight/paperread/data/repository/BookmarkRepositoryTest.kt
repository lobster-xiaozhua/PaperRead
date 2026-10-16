package io.github.lunalight.paperread.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.data.local.AppDatabase
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.repository.BookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 书签仓储单测（Robolectric，覆盖 AC-6.2 / AC-6.3 数据层）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarkRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = BookRepositoryImpl(
            database.bookDao(),
            database.chapterDao(),
            database.bookmarkDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedBook(): Long = repository.saveImport(
        book = Book(
            title = "荒原之始",
            author = "未知",
            format = BookFormat.TXT,
            encoding = "UTF-8",
            sha256 = "a".repeat(64),
            fileSize = 2048,
            dirName = "dir-1",
            totalChars = 1000,
            chapterCount = 2,
            addedAt = 1_000L,
        ),
        chapters = listOf(
            Chapter(bookId = 0L, index = 0, title = "第一章", charStart = 0, charEnd = 10,
                byteStart = 0, byteEnd = 30, charCount = 10, cumulativeChars = 0),
            Chapter(bookId = 0L, index = 1, title = "第二章", charStart = 10, charEnd = 40,
                byteStart = 30, byteEnd = 120, charCount = 30, cumulativeChars = 10),
        ),
    )

    private fun bookmark(
        bookId: Long,
        chapterIndex: Int = 0,
        paragraphIndex: Int = 0,
        charOffset: Int = 0,
        excerpt: String = "摘录",
        createdAt: Long = 1_000L,
    ) = Bookmark(
        bookId = bookId,
        chapterIndex = chapterIndex,
        paragraphIndex = paragraphIndex,
        charOffset = charOffset,
        excerpt = excerpt,
        createdAt = createdAt,
    )

    @Test
    fun `addBookmark then observe emits in reverse chronological order`() = runTest {
        val bookId = seedBook()
        repository.addBookmark(bookmark(bookId, excerpt = "旧", createdAt = 1_000L))
        repository.addBookmark(bookmark(bookId, excerpt = "新", createdAt = 2_000L))

        val bookmarks = repository.observeBookmarks(bookId).first()
        assertEquals(2, bookmarks.size)
        assertEquals("新", bookmarks[0].excerpt)
        assertEquals("旧", bookmarks[1].excerpt)
        assertTrue(bookmarks.all { it.id > 0 })
    }

    @Test
    fun `addBookmark keeps position and excerpt`() = runTest {
        val bookId = seedBook()
        val id = repository.addBookmark(
            bookmark(bookId, chapterIndex = 1, paragraphIndex = 3, charOffset = 128, excerpt = "风把沙丘吹成另一种形状"),
        )

        val saved = repository.observeBookmarks(bookId).first().single()
        assertEquals(id, saved.id)
        assertEquals(1, saved.chapterIndex)
        assertEquals(3, saved.paragraphIndex)
        assertEquals(128, saved.charOffset)
        assertEquals("风把沙丘吹成另一种形状", saved.excerpt)
    }

    @Test
    fun `deleteBookmark removes only that bookmark`() = runTest {
        val bookId = seedBook()
        val first = repository.addBookmark(bookmark(bookId, excerpt = "甲"))
        val second = repository.addBookmark(bookmark(bookId, excerpt = "乙"))

        repository.deleteBookmark(first)

        val remaining = repository.observeBookmarks(bookId).first()
        assertEquals(1, remaining.size)
        assertEquals(second, remaining[0].id)
    }

    @Test
    fun `deleteBook cascades bookmarks`() = runTest {
        val bookId = seedBook()
        repository.addBookmark(bookmark(bookId))

        repository.deleteBook(repository.getBook(bookId)!!)

        assertTrue(repository.observeBookmarks(bookId).first().isEmpty())
    }

    @Test
    fun `bookmarks of different books are isolated`() = runTest {
        val bookId = seedBook()
        repository.saveImport(
            book = Book(
                title = "另一本",
                author = "未知",
                format = BookFormat.TXT,
                encoding = "UTF-8",
                sha256 = "b".repeat(64),
                fileSize = 512,
                dirName = "dir-2",
                totalChars = 100,
                chapterCount = 1,
                addedAt = 2_000L,
            ),
            chapters = emptyList(),
        )
        repository.addBookmark(bookmark(bookId, excerpt = "属于第一本"))

        assertEquals(1, repository.observeBookmarks(bookId).first().size)
    }
}
