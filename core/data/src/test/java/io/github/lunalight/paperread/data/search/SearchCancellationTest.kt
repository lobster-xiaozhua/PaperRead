package io.github.lunalight.paperread.data.search

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.domain.search.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

/**
 * 真·协程取消下的扫描停止验证（AC-15.5 / AC-15.10）。
 *
 * 为什么单独成文件：既有 `CurrentBookSearchSourceTest` 的取消用例用 `take(n)`，
 * 而 `take` 是在**下游 emit 处**抛 `AbortFlowException` 中止上游。它证明的是
 * 「流不会多产出」，**不能**证明 `CurrentBookSearchSource` 里的 `ensureActive()`
 * 检查点有效——QA 变异实测：把两处 `ensureActive()` 全部删掉，
 * `CurrentBookSearchSourceTest` 25 例仍然全绿。
 *
 * 本文件用另一种造法把检查点真正压到临界位置：让 `readChapter` 在**无命中**的
 * 章节内取消收集方 Job。此时没有 emit 可供 `take` / 收集方中止，循环能否停下
 * 只取决于「进入下一章前的 `ensureActive()`」——删掉它，就会读到第 1 章。
 *
 * 两个用例分工：
 * - [取消发生在无命中章节内时不再打开后续章节]：唯一能区分「有无检查点」的一条；
 * - [取消发生在有命中章节内时立即停止输出]：AC-15.5 的黑盒行为保证
 *   （实测即使删掉 emit 前的 `ensureActive()`，向已取消的收集方 emit 也会抛
 *   CancellationException——这一层是冗余保险，不是唯一防线）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchCancellationTest {

    /** 在第 [cancelOnChapter] 章被读取时取消 [target]（模拟扫描中被外部取消）。 */
    private class CancellingTextStore(
        private val target: Job,
        private val cancelOnChapter: Int,
        private val texts: Map<Int, String>,
    ) : BookTextStore {

        val readChapterIndexes = mutableListOf<Int>()

        override fun bookDir(dirName: String): File = File("/tmp/cancel-books/$dirName")

        override fun textFile(dirName: String): File = File(bookDir(dirName), "book.txt")

        override fun sourceFile(book: Book): File = File(bookDir(book.dirName), "source.txt")

        override fun readChapter(dirName: String, chapter: Chapter): String {
            readChapterIndexes += chapter.index
            if (chapter.index == cancelOnChapter) target.cancel()
            return texts[chapter.index] ?: ""
        }

        override fun hasText(dirName: String): Boolean = true

        override fun readSourceText(book: Book, charset: Charset): String = ""

        override fun deleteBookDir(dirName: String) {}
    }

    private fun chapter(index: Int) = Chapter(
        id = index.toLong(),
        bookId = 1L,
        index = index,
        title = "第 ${index + 1} 章",
        charStart = index * 1000L,
        charEnd = index * 1000L + 1000,
        byteStart = index * 1000L,
        byteEnd = index * 1000L + 1000,
        charCount = 1000,
        cumulativeChars = index * 1000L,
    )

    /**
     * 在「无命中的章节」里取消：没有 emit 可以中止流程，唯一的检查点是
     * 进入下一章前的 `ensureActive()`。
     */
    @Test
    fun `取消发生在无命中章节内时不再打开后续章节`() = runTest {
        val chapters = (0..3).map { chapter(it) }
        val job = Job()
        val store = CancellingTextStore(
            target = job,
            cancelOnChapter = 0,
            // 全部章节都不含关键词：本章一条 emit 都不会发生
            texts = chapters.associate { it.index to "整章与关键词无关的内容" },
        )
        val source = CurrentBookSearchSource(1L, chapters, store, "dir")
        val scope = CoroutineScope(coroutineContext + job + Dispatchers.Unconfined)

        val collector = scope.launch {
            runCatching { source.search("灵剑").collect { /* 不应到达 */ } }
        }
        collector.join()

        assertEquals(
            "取消后不得再读第 1 章（删掉章首 ensureActive 时这里会是 [0, 1, 2, 3]）",
            listOf(0),
            store.readChapterIndexes,
        )
    }

    /** 章内取消后不再继续 emit 本章剩余命中（黑盒行为保证）。 */
    @Test
    fun `取消发生在有命中章节内时立即停止输出`() = runTest {
        val chapters = listOf(chapter(0), chapter(1))
        val job = Job()
        val store = CancellingTextStore(
            target = job,
            cancelOnChapter = 0,
            texts = mapOf(0 to "灵剑一\n灵剑二\n灵剑三", 1 to "灵剑四"),
        )
        val source = CurrentBookSearchSource(1L, chapters, store, "dir")
        val scope = CoroutineScope(coroutineContext + job + Dispatchers.Unconfined)
        val received = mutableListOf<SearchHit>()
        val collector = scope.launch {
            runCatching { source.search("灵剑").collect { received += it } }
        }
        collector.join()

        assertTrue("取消后不得继续输出（收到 ${received.size} 条）", received.size <= 1)
        assertEquals("本章取消后不读下一章", listOf(0), store.readChapterIndexes)
    }
}
