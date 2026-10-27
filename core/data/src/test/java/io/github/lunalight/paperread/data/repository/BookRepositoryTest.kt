package io.github.lunalight.paperread.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.data.local.AppDatabase
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookRepositoryTest {

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

    private fun sampleBook(sha256: String = "a".repeat(64)) = Book(
        title = "荒原之始",
        author = "未知",
        format = BookFormat.TXT,
        encoding = "GB18030",
        sha256 = sha256,
        fileSize = 2048,
        dirName = "dir-1",
        totalChars = 1000,
        chapterCount = 3,
        addedAt = 1_000L,
    )

    private fun sampleChapters(bookId: Long) = listOf(
        Chapter(bookId = bookId, index = 0, title = "第一章", charStart = 0, charEnd = 10,
            byteStart = 0, byteEnd = 30, charCount = 10, cumulativeChars = 0),
        Chapter(bookId = bookId, index = 1, title = "第二章", charStart = 10, charEnd = 40,
            byteStart = 30, byteEnd = 120, charCount = 30, cumulativeChars = 10),
    )

    @Test
    fun `save then observe and get`() = runTest {
        val id = repository.saveImport(sampleBook(), sampleChapters(0L))
        assertTrue(id > 0)

        val books = repository.observeBooks().first()
        assertEquals(1, books.size)
        assertEquals("荒原之始", books[0].title)

        val fetched = repository.getBook(id)
        assertNotNull(fetched)
        assertEquals("GB18030", fetched!!.encoding)

        val chapters = repository.getChapters(id)
        assertEquals(2, chapters.size)
        assertEquals("第二章", chapters[1].title)
        // saveImport 回填 bookId
        assertTrue(chapters.all { it.bookId == id })
    }

    @Test
    fun `observeChapters emits chapter list`() = runTest {
        val id = repository.saveImport(sampleBook(), sampleChapters(0L))
        val chapters = repository.observeChapters(id).first()
        assertEquals(2, chapters.size)
    }

    @Test
    fun `deleteBook cascades chapters and bookmarks`() = runTest {
        val id = repository.saveImport(sampleBook(), sampleChapters(0L))
        val book = repository.getBook(id)!!

        repository.deleteBook(book)

        assertNull(repository.getBook(id))
        assertTrue(repository.getChapters(id).isEmpty())
        assertTrue(repository.observeBooks().first().isEmpty())
    }

    @Test
    fun `saveReaderPosition updates progress fields`() = runTest {
        val id = repository.saveImport(sampleBook(), sampleChapters(0L))
        repository.saveReaderPosition(id, ReaderPosition(2, 5, 120, 480), 42.5f)

        val book = repository.getBook(id)!!
        assertEquals(2, book.lastChapterIndex)
        assertEquals(5, book.lastParagraphIndex)
        assertEquals(120, book.lastScrollOffset)
        assertEquals(480, book.lastCharOffset)
        assertEquals(42.5f, book.progressPercent, 0.01f)
        assertNotNull(book.lastReadAt)
    }

    @Test
    fun `saveReaderPosition 显式 readAt 时原样落库（F-01 备份恢复口径）`() = runTest {
        val id = repository.saveImport(sampleBook(), sampleChapters(0L))
        val archived = 1_600_000_000_000L

        repository.saveReaderPosition(
            bookId = id,
            position = ReaderPosition(1, 0, 0, 3),
            progressPercent = 50f,
            readAt = archived,
        )

        val book = repository.getBook(id)!!
        assertEquals("归档时间戳必须原样还原，不能被改写成 now", archived, book.lastReadAt)
    }

    @Test
    fun `saveReaderPosition 不传 readAt 时写当前时间（既有语义）`() = runTest {
        val id = repository.saveImport(sampleBook(), sampleChapters(0L))
        val before = System.currentTimeMillis()

        repository.saveReaderPosition(id, ReaderPosition(0, 0, 0, 0), 10f)

        val readAt = repository.getBook(id)!!.lastReadAt!!
        assertTrue("缺省 readAt 应写 now，实际 $readAt", readAt >= before)
    }

    @Test
    fun `findBySha256 hits and misses`() = runTest {
        val sha = "b".repeat(64)
        val id = repository.saveImport(sampleBook(sha256 = sha), sampleChapters(0L))

        val hit = repository.findBySha256(sha)
        assertNotNull(hit)
        assertEquals(id, hit!!.id)
        assertNull(repository.findBySha256("c".repeat(64)))
    }

    @Test
    fun `replaceChapters swaps list and resets position`() = runTest {
        val id = repository.saveImport(sampleBook(), sampleChapters(0L))
        repository.saveReaderPosition(id, ReaderPosition(1, 0, 0, 10), 50f)

        val newChapters = listOf(
            Chapter(bookId = id, index = 0, title = "新第一章", charStart = 0, charEnd = 500,
                byteStart = 0, byteEnd = 1500, charCount = 500, cumulativeChars = 0),
        )
        repository.replaceChapters(id, newChapters, 500L)
        repository.resetReaderPosition(id)

        val book = repository.getBook(id)!!
        assertEquals(1, book.chapterCount)
        assertEquals(500L, book.totalChars)
        assertEquals(0, book.lastChapterIndex)
        assertEquals(0f, book.progressPercent, 0.01f)
        assertNull(book.lastReadAt)

        val chapters = repository.getChapters(id)
        assertEquals(1, chapters.size)
        assertEquals("新第一章", chapters[0].title)
        assertTrue(chapters.all { it.bookId == id })
    }
}
