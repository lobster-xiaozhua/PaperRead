package io.github.lunalight.paperread.reader.txt

import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction

/**
 * 流式导入产物：章节索引 + 标准化后的全文字符数。
 *
 * @param usedFallback 是否为「强制分段」兜底（行级规则一条未命中）。
 *   为 true 时调用方可用 [InlineChapterScanner] 做一次行内补切，
 *   把「每 3000 字一段」恢复成真实章节。
 */
data class TxtStreamResult(
    val entries: List<ChapterIndexEntry>,
    val totalChars: Long,
    val usedFallback: Boolean = false,
)

/**
 * TXT 流式章节状态机：单遍完成
 *   分块读取 → [CharsetDecoder] 增量解码（块尾不完整多字节序列由调用方搬到下一块续解码）→
 *   \r\n / \r 行规整（跨块 \r 挂起）→ 行状态机（目录链 / 强正则 / 激进短行 / 强制分段）→
 *   UTF-8 顺序写出，同步记录每个候选边界的字符/字节双偏移。
 *
 * 内存占用与文件大小无关：滚动缓冲默认 ≤ 256KB（超长无换行文本走紧急冲刷）。
 * 与 [ChapterSplitter.split] + [ChapterIndexWriter] 的批量结果保持全字段一致
 * （parity 由 [ChapterSplitter.split] 委托本类保证，见 TxtChapterStreamTest）。
 *
 * 已知有意差异（相对旧的全量 readText 路径）：
 *  - 跳过 BOM（[bomLength] 字节），正文不再携带 BOM；
 *  - 单行超过 [maxBufferChars] 时不再可能被识别为章节标题（按正文处理，文本不丢失）。
 *
 * @param shouldContinue 取消钩子：每读取一个数据块前检查，返回 false 抛 [CancellationException]
 * @param onProgress 进度回调，参数为已读取字节占比（需要 [totalBytes] > 0 才有意义）
 */
class TxtChapterStream(
    private val charset: Charset,
    private val strategy: SplitStrategy,
    private val bomLength: Int = 0,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val maxBufferChars: Int = DEFAULT_MAX_BUFFER_CHARS,
) {

    // ---- 双偏移游标 ----
    private var charsWritten = 0L // 已写出内容的字符数（绝对偏移）
    private var bytesWritten = 0L // 已写出内容的字节数（绝对偏移）
    private var writtenEnd = 0L   // 已产出内容末端（含缓冲）= charsWritten + buf.length

    // ---- 滚动缓冲：持有 [charsWritten, writtenEnd) ----
    private val buf = StringBuilder()

    // ---- 行状态 ----
    private var curLineStart = 0L       // 当前未完成行的起始（绝对偏移）
    private var midLineFlushed = false  // 当前行已被紧急冲刷截断
    private var pendingCr = false       // 跨块挂起的 '\r'
    private var lastNlInBuf = -1L       // 缓冲内最后一个 '\n'（绝对偏移）
    private var lastNewlineFlushed = -1L // 已冲刷内容中最后一个 '\n'（绝对偏移，供强制分段向后吸附）
    private var prevLineBlank = true    // 首行的 prevBlank 为 true（批量语义 i == 0）

    // ---- 目录状态机 ----
    private val toc = TocMachine()

    // ---- 候选边界（字节偏移在冲刷时解析）----
    private val strongCandidates = ArrayList<Candidate>()
    private val shortCandidates = ArrayList<Candidate>() // 仅 AGGRESSIVE 收集
    private val forcedBoundaries = ArrayList<ForcedBoundary>()
    private var pendingShortLine: LineInfo? = null       // 激进策略的延迟一跳评估

    // ---- 强制分段状态 ----
    private var fSegStart = 0L

    /** 是否走到强制分段兜底（行级规则未命中任何章节）。 */
    private var usedFallback = false

    // ---- 候选指针（flush 时按序解析字节偏移）----
    private var strongPtr = 0
    private var shortPtr = 0
    private var forcedPtr = 0

    private lateinit var out: OutputStream

    /**
     * 执行流式处理。
     *
     * @param input 源文件字节流（调用方负责关闭）
     * @param out 标准化 UTF-8 正文输出流（由本类 flush，调用方负责关闭）
     */
    fun process(
        input: InputStream,
        out: OutputStream,
        totalBytes: Long = 0L,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ): TxtStreamResult {
        this.out = out
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        // 读缓冲 = 数据块 + 残留拼接区：解码器对块尾的不完整多字节序列不消费，
        // 由调用方搬到下一块头部续解码（UTF-8/GB18030 最多 3 字节，UTF-16 1 字节）
        val inBytes = ByteArray(chunkSize + MAX_CARRY)
        val outChars = CharBuffer.allocate(chunkSize * 4 + 16)
        var copied = 0L
        var carry = 0
        var toSkip = bomLength.toLong()
        while (true) {
            if (!shouldContinue()) throw CancellationException("导入已取消")
            val n = input.read(inBytes, carry, inBytes.size - carry)
            if (n < 0) break
            if (n == 0) continue
            var start = 0
            if (toSkip > 0) {
                val skip = minOf(toSkip, (carry + n).toLong()).toInt()
                start = skip
                toSkip -= skip
            }
            copied += n
            val inBuf = ByteBuffer.wrap(inBytes, start, carry + n - start)
            while (inBuf.hasRemaining()) {
                val res = decoder.decode(inBuf, outChars, false)
                if (res == CoderResult.OVERFLOW) {
                    drainOut(outChars)
                    continue
                }
                if (res == CoderResult.UNDERFLOW) break
            }
            drainOut(outChars)
            afterAppend()
            // 不完整多字节序列搬到头部，与下一块拼接
            carry = inBuf.remaining()
            check(carry <= MAX_CARRY) { "解码残留异常：$carry 字节" }
            if (carry > 0) {
                System.arraycopy(inBytes, inBuf.position(), inBytes, 0, carry)
            }
            if (totalBytes > 0) onProgress((copied.toFloat() / totalBytes).coerceIn(0f, 1f))
        }
        // EOF：残留字节为不完整序列，按 endOfInput 解码（REPLACE → 替换字符，与 readText 一致）
        if (carry > 0) {
            val inBuf = ByteBuffer.wrap(inBytes, 0, carry)
            while (inBuf.hasRemaining()) {
                val res = decoder.decode(inBuf, outChars, true)
                if (res == CoderResult.OVERFLOW) {
                    drainOut(outChars)
                    continue
                }
                if (res == CoderResult.UNDERFLOW) break
            }
            drainOut(outChars)
            afterAppend()
        }
        // 解码器收尾：冲刷内部残留
        decoder.decode(ByteBuffer.allocate(0), outChars, true)
        decoder.flush(outChars)
        drainOut(outChars)
        afterAppend()
        return finalize()
    }

    // ---- 解码输出 ----

    private fun drainOut(outChars: CharBuffer) {
        outChars.flip()
        if (outChars.hasRemaining()) {
            val len = outChars.remaining()
            appendChars(outChars.array(), outChars.position(), outChars.position() + len)
        }
        outChars.clear()
    }

    // ---- 行规整（\r\n / \r → \n，跨块 \r 挂起）----

    private fun appendChars(src: CharArray, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val c = src[i]
            if (pendingCr) {
                pendingCr = false
                emit('\n') // 结算挂起的 '\r'
                if (c == '\n') { // '\r\n' 的 '\n' 已被代表，跳过
                    i++
                    continue
                }
            }
            if (c == '\r') {
                if (i + 1 < to) {
                    emit('\n')
                    i += if (src[i + 1] == '\n') 2 else 1
                } else {
                    pendingCr = true // 可能是 '\r\n' 的前半，挂起等下一块
                    i++
                }
                continue
            }
            emit(c)
            i++
        }
    }

    private fun emit(c: Char) {
        val p = writtenEnd
        buf.append(c)
        writtenEnd = p + 1
        if (c == '\n') {
            lastNlInBuf = p
            completeLine(curLineStart, p)
            curLineStart = p + 1
            midLineFlushed = false
        }
    }

    // ---- 行完成：目录 / 强正则 / 激进短行 ----

    private fun completeLine(start: Long, end: Long) {
        val synthetic = midLineFlushed || start < charsWritten
        val info: LineInfo
        if (synthetic) {
            // 超长行（被紧急冲刷截断）：按非空、非章节、非短行处理
            info = LineInfo(start, end, "", "", blank = false, chapterLike = false, synthetic = true, prevBlank = prevLineBlank)
        } else {
            val raw = buf.substring((start - charsWritten).toInt(), (end - charsWritten).toInt())
            val trimmed = raw.trim()
            info = LineInfo(
                start = start,
                end = end,
                contentTrimmed = trimmed,
                stripped = strippedOf(raw),
                blank = trimmed.isEmpty(),
                chapterLike = ChapterSplitter.isChapterLine(trimmed),
                synthetic = false,
                prevBlank = prevLineBlank,
            )
        }
        toc.feed(info)
        if (info.chapterLike) {
            strongCandidates.add(Candidate(start, info.contentTrimmed.ifEmpty { "未命名章节" }))
        }
        if (strategy == SplitStrategy.AGGRESSIVE) {
            pendingShortLine?.let { prev ->
                if (isShortLineCandidate(prev, nextBlank = info.blank) && shortCandidates.size < SHORT_CANDIDATE_CAP) {
                    shortCandidates.add(Candidate(prev.start, prev.contentTrimmed))
                }
            }
            pendingShortLine = info
        }
        prevLineBlank = info.blank
    }

    private fun isShortLineCandidate(line: LineInfo, nextBlank: Boolean): Boolean {
        if (line.synthetic) return false
        val len = line.contentTrimmed.length
        if (len == 0 || len > ChapterSplitter.SHORT_LINE_MAX) return false
        return line.prevBlank && nextBlank
    }

    private fun strippedOf(raw: String): String =
        raw.trim().replace(" ", "").replace("\t", "").replace("\u3000", "")

    // ---- 强制分段（复刻批量 splitForced：目标位前/后换行双向吸附，均无则硬切）----

    /**
     * 推进强制分段状态机，边界语义与批量 splitForced 逐段一致：
     * 每段自 fSegStart 累计 [ChapterSplitter.FORCED_SEGMENT_CHARS] 个字符得目标位 target，
     *   1. target 起 [FORCED_WINDOW] 个字符内命中换行 → 切在换行后一位（向前吸附）；
     *   2. 否则回退到 target 之前最后一个换行（向后吸附，需 > fSegStart）；
     *   3. 再否则在 target 处硬切。
     * 末段不足一段不设边界（由 chooseRefs 以 [0] + 已有边界成章）。
     *
     * 窗口未完整（已见内容不足 target+200）时继续等待后续数据；EOF 时由 [eof] 直接决断。
     * 向后吸附可能指向已冲刷内容，由 [lastNewlineFlushed] 兜底（冲刷只发生在行边界/
     * 超长行尾部，且 advanceForced 先于每次冲刷执行，保证不会漏掉目标位前的换行）。
     *
     * @param eof 是否已到文末
     */
    private fun advanceForced(end: Long, eof: Boolean = false) {
        while (true) {
            if (fSegStart >= end) return
            val target = fSegStart + ChapterSplitter.FORCED_SEGMENT_CHARS
            if (end <= target) return // 段长未满：等后续数据（EOF 时即自然的末段）
            val from = maxOf(charsWritten, target)
            if (from >= end) return
            val limit = (end - charsWritten).toInt()
            var p = -1L
            var i = (from - charsWritten).toInt()
            while (i < limit) {
                if (buf[i] == '\n') {
                    p = charsWritten + i.toLong()
                    break
                }
                i++
            }
            if (p in 0..(target + FORCED_WINDOW)) { // 目标位后窗口内命中换行：向前吸附
                emitForced(p + 1)
                continue
            }
            if (!eof && end < target + FORCED_WINDOW + 1) return // 窗口未完整：等后续数据
            // 窗口内无换行（或 EOF）：向后吸附到目标位前最后一个换行，否则硬切
            val prev = lastNewlineAtOrBefore(target)
            emitForced(if (prev > fSegStart) prev + 1 else target)
        }
    }

    /** 目标位之前（含）最后一个换行的绝对偏移；缓冲内没有则回退到已冲刷内容中的最后一个。 */
    private fun lastNewlineAtOrBefore(target: Long): Long {
        val to = minOf(buf.length - 1, (target - charsWritten).toInt())
        var i = to
        while (i >= 0) {
            if (buf[i] == '\n') return charsWritten + i.toLong()
            i--
        }
        return lastNewlineFlushed
    }

    private fun emitForced(b: Long) {
        forcedBoundaries.add(ForcedBoundary(b))
        fSegStart = b
    }

    // ---- 冲刷：行边界优先，超限紧急冲刷；同步解析边界字节偏移 ----

    private fun afterAppend() {
        advanceForced(writtenEnd)
        val lineCeil = if (lastNlInBuf >= charsWritten) lastNlInBuf + 1 else charsWritten
        var ceil = lineCeil
        // 激进策略：待判短行的候选身份要等下一行完成才可知（延迟一行评估），
        // 冲刷不得越过其起点，否则候选添加时位置已被冲刷，字节偏移永久无法解析
        if (strategy == SplitStrategy.AGGRESSIVE) {
            pendingShortLine?.let { ceil = minOf(ceil, it.start) }
        }
        if (writtenEnd - ceil > maxBufferChars) {
            // 无换行的超长行：保留尾部，其余冲刷（当前行按超长行处理）
            ceil = maxOf(charsWritten, writtenEnd - KEEP_TAIL_CHARS)
            if (ceil > curLineStart) midLineFlushed = true
        }
        flushTo(ceil)
    }

    private fun flushTo(ceil: Long) {
        if (ceil <= charsWritten) return
        val localEnd = (ceil - charsWritten).toInt()
        var byteCursor = 0
        var ci = 0
        var lastNlInFlush = -1
        while (ci < localEnd) {
            val abs = charsWritten + ci
            while (strongPtr < strongCandidates.size && strongCandidates[strongPtr].charPos == abs) {
                strongCandidates[strongPtr].bytePos = bytesWritten + byteCursor
                strongPtr++
            }
            while (shortPtr < shortCandidates.size && shortCandidates[shortPtr].charPos == abs) {
                shortCandidates[shortPtr].bytePos = bytesWritten + byteCursor
                shortPtr++
            }
            while (forcedPtr < forcedBoundaries.size && forcedBoundaries[forcedPtr].charPos == abs) {
                forcedBoundaries[forcedPtr].bytePos = bytesWritten + byteCursor
                forcedPtr++
            }
            val c = buf[ci]
            if (c == '\n') lastNlInFlush = ci
            byteCursor += if (c.isHighSurrogate() && ci + 1 < localEnd && buf[ci + 1].isLowSurrogate()) {
                ci += 2 // 代理对：2 字符 → 4 字节
                4
            } else {
                ci++
                utf8Len(c)
            }
        }
        // 冲刷末端恰好落定的边界（如强制分段的收尾边界）
        val endAbs = charsWritten + localEnd
        while (strongPtr < strongCandidates.size && strongCandidates[strongPtr].charPos == endAbs) {
            strongCandidates[strongPtr].bytePos = bytesWritten + byteCursor
            strongPtr++
        }
        while (shortPtr < shortCandidates.size && shortCandidates[shortPtr].charPos == endAbs) {
            shortCandidates[shortPtr].bytePos = bytesWritten + byteCursor
            shortPtr++
        }
        while (forcedPtr < forcedBoundaries.size && forcedBoundaries[forcedPtr].charPos == endAbs) {
            forcedBoundaries[forcedPtr].bytePos = bytesWritten + byteCursor
            forcedPtr++
        }
        val bytes = buf.substring(0, localEnd).toByteArray(Charsets.UTF_8)
        check(byteCursor == bytes.size) { "UTF-8 字节游标与标准编码不一致" }
        out.write(bytes)
        bytesWritten += bytes.size
        if (lastNlInFlush >= 0) lastNewlineFlushed = charsWritten + lastNlInFlush
        charsWritten = ceil
        buf.delete(0, localEnd)
    }

    private fun utf8Len(c: Char): Int = when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        else -> 3 // 代理对由调用方按 4 字节 / 2 字符处理
    }

    // ---- 收尾与结果选择 ----

    private fun finalize(): TxtStreamResult {
        if (pendingCr) { // 文件以孤立 '\r' 结尾
            emit('\n')
            pendingCr = false
        }
        if (writtenEnd > curLineStart) { // 最后一个不完整行（EOF 即行尾）
            completeLine(curLineStart, writtenEnd)
            curLineStart = writtenEnd
        }
        advanceForced(writtenEnd, eof = true)
        toc.finalize()
        if (strategy == SplitStrategy.AGGRESSIVE) {
            pendingShortLine?.let { p ->
                if (isShortLineCandidate(p, nextBlank = true) && shortCandidates.size < SHORT_CANDIDATE_CAP) {
                    shortCandidates.add(Candidate(p.start, p.contentTrimmed))
                }
            }
        }
        flushTo(writtenEnd)
        out.flush()
        val refs = chooseRefs()
        return TxtStreamResult(buildEntries(refs, writtenEnd), writtenEnd, usedFallback)
    }

    private fun chooseRefs(): List<Ref> {
        val tocEnd = toc.tocEnd
        val strong = strongCandidates.filter { it.charPos >= tocEnd }
        if (strong.size >= ChapterSplitter.MIN_CHAPTER_MATCHES) {
            return withLeadingOpening(strong.map { Ref(it.charPos, it.bytePos, it.title) })
        }
        if (strategy == SplitStrategy.AGGRESSIVE) {
            val short = shortCandidates.filter { it.charPos >= tocEnd }
            if (short.size >= ChapterSplitter.MIN_CHAPTER_MATCHES) {
                return withLeadingOpening(short.map { Ref(it.charPos, it.bytePos, it.title) })
            }
        }
        usedFallback = true
        // 强制分段：章起点 = [0] + 各完整段边界；末尾不足一段的内容并入末章，不产生空段
        val starts = ArrayList<Ref>(forcedBoundaries.size + 1)
        if (writtenEnd > 0) starts.add(Ref(0L, 0L, "第 1 段"))
        for (i in forcedBoundaries.indices) {
            val b = forcedBoundaries[i]
            if (b.charPos >= writtenEnd) break // 防御：边界不越界，不会产生空段
            starts.add(Ref(b.charPos, b.bytePos, "第 ${i + 2} 段"))
        }
        return starts
    }

    private fun withLeadingOpening(boundaries: List<Ref>): List<Ref> =
        if (boundaries.isNotEmpty() && boundaries[0].charPos > 0) {
            listOf(Ref(0L, 0L, "开头")) + boundaries
        } else {
            boundaries
        }

    private fun buildEntries(refs: List<Ref>, totalChars: Long): List<ChapterIndexEntry> {
        val entries = ArrayList<ChapterIndexEntry>(refs.size)
        var cumulative = 0L
        for (i in refs.indices) {
            val r = refs[i]
            val charEnd = refs.getOrNull(i + 1)?.charPos ?: totalChars
            val byteEnd = refs.getOrNull(i + 1)?.bytePos ?: bytesWritten
            val charCount = charEnd - r.charPos
            entries.add(
                ChapterIndexEntry(
                    index = i,
                    title = r.title,
                    charStart = r.charPos,
                    charEnd = charEnd,
                    byteStart = r.bytePos,
                    byteEnd = byteEnd,
                    charCount = charCount,
                    cumulativeChars = cumulative,
                ),
            )
            cumulative += charCount
        }
        return entries
    }

    // ---- 内部类型 ----

    private class Candidate(val charPos: Long, val title: String) {
        var bytePos: Long = -1L
    }

    private class ForcedBoundary(val charPos: Long) {
        var bytePos: Long = -1L
    }

    private class Ref(val charPos: Long, val bytePos: Long, val title: String)

    private data class LineInfo(
        val start: Long,
        val end: Long,
        val contentTrimmed: String,
        val stripped: String,
        val blank: Boolean,
        val chapterLike: Boolean,
        val synthetic: Boolean,
        val prevBlank: Boolean,
    )

    /**
     * 目录页识别状态机：复刻 [ChapterSplitter] 的目录链语义
     * （前 200 行内找「目录」行 → 链式扫描章节条目 → 重复标题或非章非空行终止；
     * 链上章节条目 ≥ 3 才认定目录页，否则继续向后找）。
     */
    private class TocMachine {
        var tocEnd: Long = 0L
            private set

        private var lineIdx = -1
        private var searchFrom = 0
        private var done = false
        private var chaining = false
        private var tocLineIdx = -1
        private var chainChapterLike = 0
        private var chainLastEnd = 0L
        private val seenTitles = HashSet<String>()

        fun feed(line: LineInfo) {
            lineIdx++
            if (done) return
            var current = line
            while (true) {
                if (!chaining) {
                    if (lineIdx < searchFrom) return
                    if (lineIdx >= ChapterSplitter.TOC_SEARCH_LINES) {
                        done = true
                        return
                    }
                    if (current.stripped == ChapterSplitter.TOC_KEYWORD) {
                        tocLineIdx = lineIdx
                        chaining = true
                        chainChapterLike = 0
                        chainLastEnd = current.end
                        seenTitles.clear()
                    }
                    return
                }
                // 链式扫描中
                if (current.blank) return // 空行跳过
                if (current.chapterLike) {
                    if (chainChapterLike >= ChapterSplitter.MIN_CHAPTER_MATCHES &&
                        current.contentTrimmed in seenTitles
                    ) {
                        // 重复标题：已进入正文首章，目录链终止（该行不计入）
                        finishChain()
                        return
                    }
                    seenTitles.add(current.contentTrimmed)
                    chainChapterLike++
                    chainLastEnd = current.end
                    return
                }
                // 非章非空：目录链终止
                finishChain()
                if (done) return
                searchFrom = tocLineIdx + 1
                // 以非链状态重新审视当前行（可能是另一个「目录」行）
                continue
            }
        }

        private fun finishChain() {
            if (chainChapterLike >= ChapterSplitter.MIN_CHAPTER_MATCHES) {
                tocEnd = chainLastEnd
                done = true
            }
            chaining = false
        }

        fun finalize() {
            if (!done && chaining) finishChain()
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val DEFAULT_MAX_BUFFER_CHARS = 256 * 1024
        private const val KEEP_TAIL_CHARS = 4096
        private const val FORCED_WINDOW = 200
        private const val SHORT_CANDIDATE_CAP = 100_000

        /** 解码器块尾残留上界：UTF-8/GB18030 序列最多 4 字节（残留 ≤3），UTF-16 残留 1 字节。 */
        private const val MAX_CARRY = 16
    }
}
