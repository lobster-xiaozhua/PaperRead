package io.github.lunalight.paperread.data.repository

import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.BookParser
import io.github.lunalight.paperread.reader.ParseContext
import io.github.lunalight.paperread.reader.ParsePhase
import io.github.lunalight.paperread.reader.ParseResult
import io.github.lunalight.paperread.reader.epub.EpubParser
import io.github.lunalight.paperread.reader.txt.ChapterIndexWriter
import io.github.lunalight.paperread.reader.xml.XmlParsers
import java.io.FileOutputStream

/**
 * EPUB 解析器（T-012 从 `DefaultBookImporter` 拆出，逻辑逐行搬迁不改语义）：
 * OPF/spine/NCX 结构解析 + 正文提取 → 索引落盘。
 *
 * EPUB 无法像 TXT 那样纯流式（需先解出 spine 顺序再逐条提取），故走
 * 「全文驻留 → 写索引」两步；正文内部统一转存 UTF-8。
 *
 * 与 TXT 的契约差异（有意）：忽略 [ParseContext.strategy] 与
 * [ParseContext.charsetOverride]——EPUB 的章节结构与编码都由包内元数据决定。
 *
 * XML 解析器工厂 [XmlParsers.android]（`android.util.Xml` + XmlPullParser）
 * 与本解析器同住 `:core:data`（T-013）：`:core:reader` 的 [EpubParser] 缺省用
 * 纯 JVM 的 SAX 实现，契约层不得反向依赖 Android（T-010 的循环依赖教训）。
 */
class EpubBookParser : BookParser {

    override val format: BookFormat = BookFormat.EPUB

    override fun parse(context: ParseContext): ParseResult {
        context.onPhase(ParsePhase.PARSING)
        val content = EpubParser(XmlParsers::android).parse(context.sourceFile)
        context.onPhase(ParsePhase.WRITING_INDEX)
        val entries = FileOutputStream(context.targetFile).use { out ->
            ChapterIndexWriter(out).write(content.fullText, content.refs)
        }
        return ParseResult(
            title = content.title,
            author = content.author,
            charsetName = UTF8_NAME, // 内部统一转存 UTF-8
            totalChars = content.fullText.length.toLong(),
            entries = entries,
        )
    }

    private companion object {
        const val UTF8_NAME = "UTF-8"
    }
}
