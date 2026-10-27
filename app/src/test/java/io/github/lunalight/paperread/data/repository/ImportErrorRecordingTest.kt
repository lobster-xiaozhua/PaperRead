package io.github.lunalight.paperread.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.data.local.AppDatabase
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.FileBookTextStore
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 导入失败外报（T-021 · AC-19.4）——`PreparePipeline` ↔ `ImportErrorRecorder` 的接线验证。
 *
 * 为什么单测这一层：`ImportErrorLogTest` 只证明「记下来之后存得对」，
 * 而「失败时到底有没有调用 record」只能靠流水线验证——漏接一个失败出口，
 * 诊断包里就少一条线索，而这恰恰是用户报「导不进来」时最需要的信息。
 *
 * 用真实 `DefaultBookImporter`（Robolectric + 内存 Room），与 BookImporterTest 同范式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportErrorRecordingTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: BookRepository
    private lateinit var textStore: BookTextStore
    private val recorded = mutableListOf<ImportError>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = BookRepositoryImpl(
            database.bookDao(),
            database.chapterDao(),
            database.bookmarkDao(),
        )
        textStore = FileBookTextStore(context)
        recorded.clear()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun importer() = DefaultBookImporter(
        context = context,
        bookRepository = repository,
        textStore = textStore,
        importErrorRecorder = ImportErrorRecorder { recorded += it },
    )

    private fun uriFor(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://io.github.lunalight.paperread.test/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    @Test
    fun `不支持的扩展名会被记录`() = runTest {
        val result = importer().prepare(uriFor("book.pdf", "%PDF-1.7".toByteArray()))

        assertTrue(result is PrepareResult.Unsupported)
        assertEquals(1, recorded.size)
        assertTrue(recorded.single() is ImportError.UnsupportedFormat)
        assertEquals("extension=pdf", recorded.single().cause)
    }

    @Test
    fun `解析失败会被记录`() = runTest {
        val result = importer().prepare(uriFor("broken.epub", "not a zip at all".toByteArray()))

        assertTrue("期望 Failure，实际 $result", result is PrepareResult.Failure)
        assertEquals(1, recorded.size)
        assertTrue("期望 ParseFailed，实际 ${recorded.single()}", recorded.single() is ImportError.ParseFailed)
    }

    @Test
    fun `成功导入不产生失败记录`() = runTest {
        val content = "第一章 起\n正文一\n第二章 承\n正文二".toByteArray(Charsets.UTF_8)

        val result = importer().prepare(uriFor("ok.txt", content))

        assertTrue(result is PrepareResult.Success)
        assertTrue("成功路径不得写入诊断", recorded.isEmpty())
    }

    @Test
    fun `未注入记录器时行为不变`() = runTest {
        val plain = DefaultBookImporter(context, repository, textStore)

        val result = plain.prepare(uriFor("book.pdf", "x".toByteArray()))

        assertTrue(result is PrepareResult.Unsupported)
    }
}
