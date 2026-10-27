package io.github.lunalight.paperread.data.repository

import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.BookDecodeException
import io.github.lunalight.paperread.reader.BookParser
import io.github.lunalight.paperread.reader.ParseContext
import io.github.lunalight.paperread.reader.ParsePhase
import io.github.lunalight.paperread.reader.ParseResult
import io.github.lunalight.paperread.reader.txt.ChapterIndexEntry
import io.github.lunalight.paperread.reader.txt.CharsetDetector
import io.github.lunalight.paperread.reader.txt.InlineChapterScanner
import io.github.lunalight.paperread.reader.txt.TxtChapterStream
import io.github.lunalight.paperread.reader.txt.TxtStreamResult
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException

/**
 * TXT 解析器（T-012 从 `DefaultBookImporter` 拆出，逻辑逐行搬迁不改语义）：
 * 编码探测 → 单遍流式「解码 + 切分 + 直写 book.txt」→ 兜底行内补切。
 *
 * 全程流式：内存占用与文件大小无关（滚动缓冲 ≤ 256KB），200 MB 级文件无 OOM 风险
 * （引擎侧的实现与不变量见 [TxtChapterStream]）。
 *
 * 本章节结构的真相在 `:core:reader`；本类只负责「把引擎拼进导入流水线」：
 * 取样、探测、临时文件原子替换、以及退化到强制分段时的行内补切。
 */
class TxtBookParser : BookParser {

    override val format: BookFormat = BookFormat.TXT

    override fun parse(context: ParseContext): ParseResult {
        context.onPhase(ParsePhase.DETECTING)
        val sample = context.sourceFile.readSample()
        val override = context.charsetOverride
        val charset: Charset
        val bomLength: Int
        val charsetName: String
        if (override != null) {
            // 用户指定编码（结果页切换编码 / 书架重新解析）：不做探测，也不做
            // BOM 严格校验——编码是用户的选择，解不出来就按 REPLACE 兜底
            charset = CharsetDetector.byDisplayName(override) ?: Charsets.UTF_8
            bomLength = CharsetDetector.bomLengthOf(sample)
            charsetName = override
        } else {
            val detection = CharsetDetector.detect(sample)
            // BOM 声明了编码而样本无法按该编码严格解码 → 编码失败（转换编码后重试）；
            // 无 BOM 的探测结果（UTF-8 严格试解码失败 → GB18030 回退）是设计内容错路径，
            // 由流式解码 REPLACE 兜底，不在此拦截
            if (detection.bomLength > 0 && !CharsetDetector.decodesStrictly(sample, detection.charset)) {
                throw BookDecodeException(detection.displayName)
            }
            charset = detection.charset
            bomLength = detection.bomLength
            charsetName = detection.displayName
        }
        val result = stream(context, charset, bomLength)
        // 标题取原始文件名（source.txt 只是内部转存名）；重新解析时保留原书名
        val title = context.titleOverride
            ?: context.displayName?.substringBeforeLast('.')?.ifBlank { UNTITLED }
            ?: UNTITLED
        return ParseResult(
            title = title,
            author = UNKNOWN_AUTHOR,
            charsetName = charsetName,
            totalChars = result.totalChars,
            entries = result.entries,
        )
    }

    /**
     * 单遍解码 + 切分 + 写出 book.txt（经临时文件原子替换）。
     * 失败/取消时删除临时文件，原有 book.txt 不受影响。
     *
     * 流式切分若退化到「强制分段」（行级规则一条未命中：单行 TXT 常见），
     * 会在**替换前**对临时文件做一次只读的行内标记补切（[resplitFallback]），
     * 把「每 3000 字一段」还原成真实章节；正文不改写，双偏移保持自洽。
     * 补切取消/失败时临时文件照常丢弃，原有 book.txt 不受影响。
     */
    private fun stream(
        context: ParseContext,
        charset: Charset,
        bomLength: Int,
    ): TxtStreamResult {
        val textFile = context.targetFile
        val tmp = File(textFile.parentFile, TMP_FILE_NAME)
        val result = try {
            val streamed = FileOutputStream(tmp).use { out ->
                context.sourceFile.inputStream().use { input ->
                    TxtChapterStream(charset, context.strategy, bomLength).process(
                        input = input,
                        out = out,
                        totalBytes = context.sourceFile.length(),
                        shouldContinue = context.shouldContinue,
                        onProgress = context.onProgress,
                    )
                }
            }
            resplitFallback(tmp, streamed, context.shouldContinue)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(textFile)) {
            tmp.copyTo(textFile, overwrite = true)
            tmp.delete()
        }
        return result
    }

    /**
     * 兜底补切：仅当流式切分退化为强制分段时执行，且只读现有正文文件，
     * 不改写正文（行内标题不插入换行），因此 charStart/byteStart 与正文完全一致。
     *
     * 扫描失败（IO 异常 / 命中不足）时原样返回强制分段结果，导入流程不受影响。
     */
    private fun resplitFallback(
        bodyFile: File,
        result: TxtStreamResult,
        shouldContinue: () -> Boolean,
    ): TxtStreamResult {
        if (!result.usedFallback) return result
        val headings = try {
            bodyFile.inputStream().use { InlineChapterScanner.scan(it, shouldContinue) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return result
        }
        if (headings.isEmpty()) return result
        val entries = headings.toEntries(result.totalChars, bodyFile.length())
        if (entries.isEmpty()) return result // 防御：宁可保留强制分段，也不要空章节表
        return result.copy(entries = entries)
    }

    /**
     * 行内边界 → 章节索引。
     *
     * 首个边界不在 0 时保留一段「开头」章（与 [TxtChapterStream] 的 leading opening 语义一致），
     * 保证索引连续且覆盖 [0, totalChars]；末章 byteEnd 取文件实际长度。
     */
    private fun List<InlineChapterScanner.Heading>.toEntries(
        totalChars: Long,
        totalBytes: Long,
    ): List<ChapterIndexEntry> {
        val refs = ArrayList<InlineRef>(size + 1)
        if (isNotEmpty() && first().charStart > 0) {
            refs.add(InlineRef(0L, 0L, "开头"))
        }
        for (h in this) {
            if (h.charStart >= totalChars) break // 防御：边界不越界
            refs.add(InlineRef(h.charStart, h.byteStart, h.title))
        }
        val entries = ArrayList<ChapterIndexEntry>(refs.size)
        var cumulative = 0L
        for (i in refs.indices) {
            val ref = refs[i]
            val next = refs.getOrNull(i + 1)
            val charEnd = next?.charStart ?: totalChars
            val charCount = charEnd - ref.charStart
            entries.add(
                ChapterIndexEntry(
                    index = i,
                    title = ref.title,
                    charStart = ref.charStart,
                    charEnd = charEnd,
                    byteStart = ref.byteStart,
                    byteEnd = next?.byteStart ?: totalBytes,
                    charCount = charCount,
                    cumulativeChars = cumulative,
                ),
            )
            cumulative += charCount
        }
        return entries
    }

    /** 行内补切产生的章节边界（char/byte 同源于 book.txt）。 */
    private data class InlineRef(val charStart: Long, val byteStart: Long, val title: String)

    /** 取文件前 [CharsetDetector.SAMPLE_SIZE] 字节做编码探测样本。 */
    private fun File.readSample(): ByteArray = inputStream().use { input ->
        val buffer = ByteArray(CharsetDetector.SAMPLE_SIZE)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    private companion object {
        const val UNTITLED = "未命名书籍"
        const val UNKNOWN_AUTHOR = "未知"
        const val TMP_FILE_NAME = "book.txt.tmp"
    }
}
