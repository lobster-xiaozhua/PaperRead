package io.github.lunalight.paperread.ui.reader

import io.github.lunalight.paperread.domain.model.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.SortedMap

/**
 * [bookmarkGroups] / [formatBookmarkTime] 纯函数单测（T-022 顺手项 · QA G-2）。
 *
 * 这两个函数原先住在 `ReaderChrome.kt`（覆盖率白名单文件，AC-17.2 的「时间」列
 * 因此零覆盖）；抽到 [BookmarkPresentation] 后进分母，这里补齐行为断言：
 * 分组顺序 = 章节顺序、组内保持入参顺序、时间格式为 `MM-dd HH:mm` 且 locale 无关。
 */
class BookmarkPresentationTest {

    private fun bookmark(
        id: Long,
        chapterIndex: Int,
        createdAt: Long,
    ) = Bookmark(
        id = id,
        bookId = 1L,
        chapterIndex = chapterIndex,
        paragraphIndex = 0,
        charOffset = 0,
        excerpt = "摘录 $id",
        createdAt = createdAt,
    )

    @Test
    fun `分组键为章节且组顺序随章节号升序`() {
        // 入参故意乱序：分组结果必须按章节排，不按创建时间或入参顺序
        val bookmarks = listOf(
            bookmark(1, chapterIndex = 3, createdAt = 100L),
            bookmark(2, chapterIndex = 1, createdAt = 200L),
            bookmark(3, chapterIndex = 2, createdAt = 300L),
            bookmark(4, chapterIndex = 1, createdAt = 400L),
        )

        val groups: SortedMap<Int, List<Bookmark>> = bookmarkGroups(bookmarks)

        assertEquals("组键 = 章节序号升序", listOf(1, 2, 3), groups.keys.toList())
        // 同章书签保持仓储给定的创建时间倒序（入参顺序），分组不重排
        assertEquals(
            "组内保持入参顺序",
            listOf(2L, 4L),
            groups.getValue(1).map { it.id },
        )
        assertEquals(listOf(3L), groups.getValue(2).map { it.id })
        assertEquals(listOf(1L), groups.getValue(3).map { it.id })
    }

    @Test
    fun `空书签列表得到空分组`() {
        assertTrue(bookmarkGroups(emptyList()).isEmpty())
    }

    @Test
    fun `时间格式化为 月-日 时分 且与 locale 无关`() {
        // 默认时区构造（formatBookmarkTime 的 SimpleDateFormat 同样走默认时区，
        // 两侧口径一致）：2026-03-05 07:08
        val calendar = GregorianCalendar().apply {
            clear()
            set(2026, Calendar.MARCH, 5, 7, 8, 0)
        }
        val epochMillis = calendar.timeInMillis

        val zh = formatBookmarkTime(epochMillis, Locale.CHINA)
        val en = formatBookmarkTime(epochMillis, Locale.US)

        assertEquals("03-05 07:08", zh)
        assertEquals("locale 只影响符号兜底，数字格式稳定", zh, en)
    }

    @Test
    fun `月与日时分均两位补零`() {
        val calendar = GregorianCalendar().apply {
            clear()
            set(2026, Calendar.JANUARY, 9, 3, 5, 0)
        }

        assertEquals("01-09 03:05", formatBookmarkTime(calendar.timeInMillis, Locale.US))
    }
}
