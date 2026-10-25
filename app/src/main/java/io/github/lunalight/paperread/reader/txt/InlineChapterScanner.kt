package io.github.lunalight.paperread.reader.txt

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CoderResult
import kotlinx.coroutines.CancellationException

/**
 * 行内章节标记扫描器：单行 TXT 的补切兜底。
 *
 * 背景：全部强正则都是**按行锚定**（`^…$`）。若源文件整本只有一个换行
 * （转码工具常见产物），行内标题无法被任何行级规则识别，切分器只能退化成
 * 「每 3000 字一段」，目录等于失效。本类在**流式切分已退化**后做一次只读扫描，
 * 用行内特征重新求章节边界；不改写正文，故双偏移（char/byte）天然自洽。
 *
 * 两类行内特征（都要求全篇命中 ≥ [MIN_HEADINGS]，否则认为只是正文提及）：
 *  1. 序号标记 `第X章 / 第X回 / 第X节 …`：X 为中文数字、半角或全角数字；
 *  2. 重复书名 + 序号 `神秘园（一） … 神秘园（二十八）`：同一前缀重复 ≥ 3 次
 *     且序号单调不减才算一「系列」，避免把正文里的单个括号序号误判成标题。
 *
 * 标题口径：只有 1 的标记本身（`第12章`）与 2 的前缀 + 标记（`神秘园（一）`）作为标题。
 * 退化路径下「第X章」后面紧跟的正文没有可靠分隔符，硬取会有大半是正文，
 * 宁短勿错——目录里 `第12章` 也比 `第12章 他猛地回头，看` 可读。
 *
 * 误判防护（三道闸）：
 *  - 命中数 < [MIN_HEADINGS] → 丢弃；
 *  - 命中数 > 总字符 / [MIN_CHAPTER_CHARS]（平均章长过短）→ 丢弃；
 *  - 同一前缀的系列必须序号单调不减。
 *
 * 内存与文件大小无关（64KB 读缓冲 + 定长状态），可安全用于 200MB 级文件。
 */
object InlineChapterScanner {

    /** 生效所需的最少命中数（与 [ChapterSplitter.MIN_CHAPTER_MATCHES] 同口径）。 */
    const val MIN_HEADINGS = 3

    /** 平均章长下限：命中密度高于此值视为误判（正文到处是「第X章」的情况）。 */
    const val MIN_CHAPTER_CHARS = 200

    /** 单次读取字节数。 */
    private const val IO_CHUNK = 64 * 1024

    /** 解码器块尾残留上限（UTF-8 最长序列 4 字节，残留 ≤ 3）。 */
    private const val MAX_CARRY = 8

    /** 单条命中上限（防御性上限，超出即认为扫描失控）。 */
    private const val MAX_HEADINGS = 20_000

    /** 「书名（序号）」中书名部分的最大字符数。 */
    private const val MAX_SERIES_TITLE_CHARS = 12

    /** 序号部分最大字符数（「一百二十三」= 5）。 */
    private const val MAX_ORDINAL_CHARS = 5

    /**
     * 一条行内章节边界。
     *
     * @param charStart 边界在正文中的字符偏移（指向标题首字）
     * @param byteStart 边界在 book.txt 中的字节偏移（与 [ChapterIndexEntry.byteStart] 同坐标系）
     * @param title 识别出的标题文本
     */
    data class Heading(val charStart: Long, val byteStart: Long, val title: String)

    /**
     * 从字节流扫描（book.txt，固定 UTF-8）；命中不足时返回空列表。
     *
     * @param shouldContinue 取消钩子：每读一个数据块前检查，返回 false 抛 [CancellationException]
     */
    fun scan(input: InputStream, shouldContinue: () -> Boolean = { true }): List<Heading> {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val detector = Detector()
        val cursor = Cursor()
        val bytes = ByteArray(IO_CHUNK + MAX_CARRY)
        val chars = CharBuffer.allocate(IO_CHUNK + 16)
        var carry = 0
        while (true) {
            if (!shouldContinue()) throw CancellationException("补切已取消")
            val n = input.read(bytes, carry, bytes.size - carry)
            if (n < 0) break
            if (n == 0) continue
            val inBuf = ByteBuffer.wrap(bytes, 0, carry + n)
            while (inBuf.hasRemaining()) {
                val res = decoder.decode(inBuf, chars, false)
                drain(chars, cursor, detector)
                // UNDERFLOW：块内可解码部分已产出，剩余为不完整多字节序列，留给下一块
                if (res == CoderResult.UNDERFLOW) break
            }
            // 块尾不完整多字节序列搬到头部，与下一块拼接（与正文写出路径同策略）
            carry = inBuf.remaining()
            if (carry > 0) System.arraycopy(bytes, inBuf.position(), bytes, 0, carry)
        }
        if (carry > 0) { // EOF：残留按 endOfInput 解码（REPLACE 兜底）
            val inBuf = ByteBuffer.wrap(bytes, 0, carry)
            while (inBuf.hasRemaining()) {
                val res = decoder.decode(inBuf, chars, true)
                drain(chars, cursor, detector)
                if (res == CoderResult.UNDERFLOW) break
            }
        }
        decoder.decode(ByteBuffer.allocate(0), chars, true)
        drain(chars, cursor, detector)
        decoder.flush(chars)
        drain(chars, cursor, detector)
        return detector.finish(cursor.charPos)
    }

    /** 从字符串扫描（单测入口）；字节偏移按 UTF-8 现算。 */
    fun scan(text: CharSequence): List<Heading> {
        val detector = Detector()
        var bytePos = 0L
        for (i in text.indices) {
            val c = text[i]
            detector.feed(c, i.toLong(), bytePos)
            bytePos += utf8Len(c)
        }
        return detector.finish(text.length.toLong())
    }

    // ---- 内部 ----

    private class Cursor {
        var charPos = 0L
        var bytePos = 0L
    }

    private fun drain(chars: CharBuffer, cursor: Cursor, detector: Detector) {
        chars.flip()
        while (chars.hasRemaining()) {
            val c = chars.get()
            detector.feed(c, cursor.charPos, cursor.bytePos)
            cursor.charPos++
            cursor.bytePos += utf8Len(c)
        }
        chars.clear()
    }

    /** 单字符 UTF-8 字节数；代理对高位计 4、低位计 0，合计 4。 */
    private fun utf8Len(c: Char): Int = when {
        c.isHighSurrogate() -> 4
        c.isLowSurrogate() -> 0
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        else -> 3
    }

    /** 章节单位字。 */
    private val CHAPTER_UNITS = charArrayOf('章', '回', '节', '卷', '集', '部', '篇', '话')

    /** 段落/标题分隔符：遇到即视为「词边界」，用于切出书名前缀。 */
    private val BOUNDARY_CHARS = charArrayOf(
        '。', '，', '！', '？', '；', '、', '：',
        '“', '”', '‘', '’', '「', '」', '『', '』',
        '（', '）', '(', ')', '【', '】', '《', '》',
        '…', '—', '～', '·',
        '\n', '\r', '\t',
    )

    private val CN_DIGITS = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /** 是否可作为序号字符。 */
    private fun isOrdinalChar(c: Char): Boolean =
        c in '0'..'9' || c in '０'..'９' || c in CN_DIGITS || c == '十' || c == '百' ||
            c == '千' || c == '零' || c == '〇' || c == '两'

    /** 解析序号（中文数字 / 半角数字 / 全角数字）；失败返回 null。 */
    private fun parseOrdinal(text: String): Int? {
        if (text.isEmpty()) return null
        if (text.all { it in '0'..'9' }) return text.toIntOrNull()
        if (text.all { it in '０'..'９' }) {
            return text.map { '0' + (it - '０') }.joinToString("").toIntOrNull()
        }
        var total = 0
        var current = 0
        for (c in text) {
            when (c) {
                '零', '〇' -> current = 0
                '十' -> {
                    total += (if (current == 0) 1 else current) * 10
                    current = 0
                }

                '百' -> {
                    total += (if (current == 0) 1 else current) * 100
                    current = 0
                }

                '千' -> {
                    total += (if (current == 0) 1 else current) * 1000
                    current = 0
                }

                '两' -> current = 2
                else -> current = CN_DIGITS[c] ?: return null
            }
        }
        val value = total + current
        return if (value > 0) value else null
    }

    private class SeriesEntry(val charStart: Long, val byteStart: Long, val title: String, val ordinal: Int)

    /**
     * 逐字符状态机：序号前缀（`第X`）/ 括号序号（`（X）`）+ 词边界跟踪，线性扫一遍。
     */
    private class Detector {

        private val headings = ArrayList<Heading>()

        /** `第X` 的临时状态：0 空闲、1 已见「第」、2 已见序号。 */
        private var ordinalState = 0
        private var ordinalStart = 0L
        private var ordinalByteStart = 0L
        private val ordinalBuf = StringBuilder()

        /** `书名（序号）` 的当前词与括号态。 */
        private val run = StringBuilder()
        private var runStart = 0L
        private var runByteStart = 0L
        private var runOverflow = false

        /** 上一个完整词的快照（书名号包裹写法专用，见 [resetRun]）。 */
        private var lastRunTitle = ""
        private var lastRunStart = 0L
        private var lastRunByteStart = 0L

        private var bracketState = 0
        private var bracketStart = 0L
        private var bracketByteStart = 0L
        private var bracketTitle = ""
        private val bracketOrdinal = StringBuilder()
        private val series = HashMap<String, MutableList<SeriesEntry>>()

        fun feed(c: Char, charPos: Long, bytePos: Long) {
            when {
                bracketState != 0 && handleBracket(c) -> Unit
                ordinalState != 0 && handleOrdinal(c) -> Unit
                else -> stepPlain(c, charPos, bytePos)
            }
        }

        /** 返回 true 表示该字符已被消费、无需再走普通路径。 */
        private fun handleOrdinal(c: Char): Boolean {
            if (isOrdinalChar(c) && ordinalBuf.length <= MAX_ORDINAL_CHARS) {
                ordinalBuf.append(c)
                ordinalState = 2
                return true
            }
            val isUnit = ordinalState == 2 && CHAPTER_UNITS.contains(c)
            ordinalState = 0
            if (isUnit) {
                ordinalBuf.append(c)
                if (headings.size < MAX_HEADINGS) {
                    headings.add(Heading(ordinalStart, ordinalByteStart, ordinalBuf.toString()))
                }
                ordinalBuf.setLength(0)
                resetRun()
                return true
            }
            ordinalBuf.setLength(0) // 「第X」后面不是章节单位字：当作正文
            return false
        }

        /** 返回 true 表示该字符已被消费。 */
        private fun handleBracket(c: Char): Boolean {
            if (isOrdinalChar(c) && bracketOrdinal.length < MAX_ORDINAL_CHARS) {
                bracketOrdinal.append(c)
                bracketState = 2
                return true
            }
            if (bracketState == 2 && (c == '）' || c == ')')) {
                commitSeries()
                bracketState = 0
                return true
            }
            bracketState = 0 // 不是序号括号：退回普通路径
            return false
        }

        private fun stepPlain(c: Char, charPos: Long, bytePos: Long) {
            if (c == '第') {
                ordinalState = 1
                ordinalStart = charPos
                ordinalByteStart = bytePos
                ordinalBuf.setLength(0)
                ordinalBuf.append(c)
                resetRun()
                return
            }
            if (c == '（' || c == '(') {
                // 书名来源优先级：当前词（`神秘园（一）`）→ 上一个完整词（`《神秘园》（一）`）。
                // 后者是更常见的排版，「》」属于边界字符，会先把当前词清空，
                // 必须靠 resetRun 留下的快照才能认出书名。
                val title = when {
                    runValid() -> run.toString()
                    lastRunTitle.isNotEmpty() -> lastRunTitle
                    else -> null
                }
                if (title != null) {
                    bracketState = 1
                    bracketStart = if (runValid()) runStart else lastRunStart
                    bracketByteStart = if (runValid()) runByteStart else lastRunByteStart
                    bracketTitle = title
                    bracketOrdinal.setLength(0)
                    resetRun()
                    return
                }
            }
            updateRun(c, charPos, bytePos)
        }

        private fun commitSeries() {
            val ordinal = parseOrdinal(bracketOrdinal.toString()) ?: return
            val list = series.getOrPut(bracketTitle) { ArrayList() }
            if (list.size >= MAX_HEADINGS) return
            list.add(
                SeriesEntry(
                    charStart = bracketStart,
                    byteStart = bracketByteStart,
                    title = bracketTitle + "（${bracketOrdinal}）",
                    ordinal = ordinal,
                ),
            )
        }

        private fun runValid(): Boolean =
            !runOverflow && run.isNotEmpty() && run.length <= MAX_SERIES_TITLE_CHARS

        private fun updateRun(c: Char, charPos: Long, bytePos: Long) {
            val usable = !c.isWhitespace() && c !in BOUNDARY_CHARS
            if (!usable) {
                resetRun()
                return
            }
            if (run.isEmpty()) {
                runStart = charPos
                runByteStart = bytePos
            }
            if (run.length <= MAX_SERIES_TITLE_CHARS) run.append(c) else runOverflow = true
        }

        private fun resetRun() {
            // 结束当前词时留一份快照：`《神秘园》（一）` 的「》」会把当前词清空，
            // 紧随其后的「（」只能靠这份快照认出书名。
            if (run.isNotEmpty() && !runOverflow) {
                lastRunTitle = run.toString()
                lastRunStart = runStart
                lastRunByteStart = runByteStart
            }
            run.setLength(0)
            runOverflow = false
        }

        /** 收尾并做三道闸过滤。 */
        fun finish(totalChars: Long): List<Heading> {
            val merged = ArrayList<Heading>(headings.size + 16)
            merged.addAll(headings)
            for ((_, entries) in series) {
                if (entries.size < MIN_HEADINGS) continue
                if (!isMonotonic(entries)) continue
                for (e in entries) merged.add(Heading(e.charStart, e.byteStart, e.title))
            }
            if (merged.isEmpty()) return emptyList()
            merged.sortBy { it.charStart }
            val deduped = ArrayList<Heading>(merged.size)
            for (h in merged) {
                if (deduped.isEmpty() || h.charStart - deduped.last().charStart > 2) deduped.add(h)
            }
            if (deduped.size < MIN_HEADINGS) return emptyList()
            if (deduped.size > MAX_HEADINGS) return emptyList()
            // 平均章长过短 → 命中来自正文提及，不是目录
            if (totalChars > 0 && deduped.size.toLong() > totalChars / MIN_CHAPTER_CHARS) {
                return emptyList()
            }
            return deduped
        }

        private fun isMonotonic(entries: List<SeriesEntry>): Boolean {
            for (i in 1 until entries.size) {
                if (entries[i].ordinal < entries[i - 1].ordinal) return false
            }
            return true
        }
    }
}
