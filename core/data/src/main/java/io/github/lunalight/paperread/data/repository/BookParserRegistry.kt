package io.github.lunalight.paperread.data.repository

import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.BookParser

/**
 * 格式 → 解析器的注册表（T-012 / R-11）。
 *
 * **新增格式 = 实现 [BookParser] + 在 [defaults] 里加一行**，其余代码零改动：
 * 导入流水线只面对 [forFormat] 返回的抽象，不出现任何 `when (format)` 分发，
 * 也不认识具体格式的解析细节（对照拆分前 `prepare` 里的
 * `if (format == BookFormat.EPUB) ... else ...` 双分支——那是新增一种格式
 * 就要改动流水线主体的根源）。
 *
 * 注册表的键是 [BookFormat]：与 `BookFormat.fromExtension` 的判定共用一个枚举，
 * 因此「支持某扩展名」与「有对应解析器」不可能各说各话——漏注册会在
 * [forFormat] 处立刻暴露（fail fast，而不是静默走到错误分支）。
 *
 * 线程语义：不可变，构造后只读，可跨协程共享。
 *
 * @param parsers 全部解析器（每个 [BookParser.format] 至多一个，后者覆盖前者）
 */
class BookParserRegistry(parsers: List<BookParser>) {

    private val byFormat: Map<BookFormat, BookParser> = parsers.associateBy { it.format }

    /**
     * 取该格式的解析器。
     *
     * @throws IllegalStateException 该格式未注册（编程错误：新增枚举值却忘了注册）
     */
    fun forFormat(format: BookFormat): BookParser = checkNotNull(byFormat[format]) {
        "未注册的书籍格式：${format.extension}（新增格式需实现 BookParser 并在 BookParserRegistry.defaults() 注册）"
    }

    /** 已注册的格式（诊断 / 测试用）。 */
    fun registeredFormats(): Set<BookFormat> = byFormat.keys

    companion object {

        /**
         * 生产注册表：当前支持 TXT / EPUB。
         *
         * **扩展点**：新增一种格式时，在此列表里加一行即可（解析器实现放同包）。
         */
        fun defaults(): BookParserRegistry = BookParserRegistry(
            listOf(
                TxtBookParser(),
                EpubBookParser(),
            ),
        )
    }
}
