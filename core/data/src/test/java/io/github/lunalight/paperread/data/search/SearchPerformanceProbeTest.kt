package io.github.lunalight.paperread.data.search

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.search.SearchHit
import io.github.lunalight.paperread.reader.BookTextStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

/**
 * 搜索首批结果耗时的**沙箱探针**（AC-15.6 的 JVM 侧近似，不作门禁）。
 *
 * AC-15.6 的正式口径是「API 34 中端机：100MB TXT 首批 ≤ 5s / 结果点击到定位 ≤ 1s /
 * 取消 ≤ 500ms」，只能在真机测（见 docs/PERFORMANCE_BASELINE.md §1.2）。
 * 本用例提供同一实现的**可复现沙箱数值**：20MB 级正文、逐章流式扫描，
 * 记录「到首批结果的耗时」与「全量扫描耗时」，供改动前后横向自比。
 *
 * 刻意**不设时间阈值**（JVM 耗时受机器负载、GC 时机影响，设阈值只会制造假红）；
 * 唯一断言是确定性的：首批结果必须在全量扫描结束前就到达（AC-15.4 的流式语义），
 * 且首批结果来自第一个命中的章节。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchPerformanceProbeTest {

    private class MapTextStore(private val texts: Map<Int, String>) : BookTextStore {
        override fun bookDir(dirName: String): File = File("/tmp/probe/$dirName")

        override fun textFile(dirName: String): File = File(bookDir(dirName), "book.txt")

        override fun sourceFile(book: Book): File = File(bookDir(book.dirName), "source.txt")

        override fun readChapter(dirName: String, chapter: Chapter): String =
            texts[chapter.index] ?: ""

        override fun hasText(dirName: String): Boolean = true

        override fun readSourceText(book: Book, charset: Charset): String = ""

        override fun deleteBookDir(dirName: String) {}
    }

    @Test
    fun `20MB 正文的首批结果先于全量扫描到达`() = runTest {
        // 200 章 × 50,000 字 ≈ 1,000 万字（UTF-8 中文约 3 字节/字 → 30MB 量级）
        val chapterCount = 200
        val charsPerChapter = 50_000
        val filler = "山间的风把云推着走，少年握紧了手里的剑。".repeat(charsPerChapter / 22 + 1)
            .take(charsPerChapter)
        val texts = (0 until chapterCount).associateWith { index ->
            // 关键字只出现在第 0 章（首批应立刻到达），第 100 章再放一处
            when (index) {
                0 -> "灵剑出世。" + filler
                100 -> filler + "灵剑出鞘。"
                else -> filler
            }
        }
        val chapters = (0 until chapterCount).map { index ->
            Chapter(
                id = index.toLong(),
                bookId = 1L,
                index = index,
                title = "第 ${index + 1} 章",
                charStart = index.toLong() * charsPerChapter,
                charEnd = (index + 1).toLong() * charsPerChapter,
                byteStart = index.toLong() * charsPerChapter * 3,
                byteEnd = (index + 1).toLong() * charsPerChapter * 3,
                charCount = charsPerChapter.toLong(),
                cumulativeChars = index.toLong() * charsPerChapter,
            )
        }
        val source = CurrentBookSearchSource(1L, chapters, MapTextStore(texts), "probe")

        val startedAt = System.nanoTime()
        val first: SearchHit = source.search("灵剑").take(1).toList().single()
        val firstHitMs = (System.nanoTime() - startedAt) / 1_000_000

        val fullStartedAt = System.nanoTime()
        val all = source.search("灵剑").toList()
        val fullScanMs = (System.nanoTime() - fullStartedAt) / 1_000_000

        println(
            "[probe] chapters=$chapterCount chars=${chapterCount * charsPerChapter} " +
                "firstHit=${firstHitMs}ms fullScan=${fullScanMs}ms hits=${all.size}",
        )

        assertEquals("首批结果来自第一个命中章", 0, first.chapterIndex)
        assertEquals("全量扫描命中两处", 2, all.size)
        assertTrue(
            "流式：首批必须早于全量扫描结束（沙箱实测 ${firstHitMs}ms vs ${fullScanMs}ms）",
            firstHitMs <= fullScanMs,
        )
    }
}
