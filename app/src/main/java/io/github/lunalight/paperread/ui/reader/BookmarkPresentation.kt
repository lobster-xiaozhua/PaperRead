package io.github.lunalight.paperread.ui.reader

import io.github.lunalight.paperread.domain.model.Bookmark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SortedMap

/**
 * 书签列表的**展示期纯逻辑**（T-022 顺手项 / QA 上轮建议 G-2）。
 *
 * 这两件事原先住在 `ReaderChrome.kt`（纯 Compose 渲染文件、覆盖率白名单类）里：
 * 分组与时间格式化都是纯函数，却因为「文件被白名单整个划出分母」而长期零覆盖
 * ——口径不诚实的那种零覆盖。抽到独立文件后它们进分母、可被 JVM 单测直接断言，
 * `ReaderChromeKt` 只剩渲染指令（继续留在白名单，理由不变）。
 */

/** 书签时间显示格式（月-日 时:分，不显示年份：书签通常只在近期有意义）。 */
internal const val BOOKMARK_TIME_PATTERN = "MM-dd HH:mm"

/**
 * 按章分组（AC-17.2）。
 *
 * 用 [SortedMap] 保证**组顺序 = 章节顺序**（第 1 章在前），不随书签添加时间跳动；
 * 组内保持仓储返回的创建时间倒序（最近添加的排前面），与改动前一致，
 * 不引入第二套排序心理模型。
 */
internal fun bookmarkGroups(bookmarks: List<Bookmark>): SortedMap<Int, List<Bookmark>> =
    bookmarks.groupBy { it.chapterIndex }.toSortedMap()

/**
 * 书签时间格式化。
 *
 * [locale] 显式可注入：单测据此得到与运行环境无关的稳定输出；
 * 生产调用不传，走系统默认 locale（与资源化前行为一致）。
 */
internal fun formatBookmarkTime(
    epochMillis: Long,
    locale: Locale = Locale.getDefault(),
): String = SimpleDateFormat(BOOKMARK_TIME_PATTERN, locale).format(Date(epochMillis))
