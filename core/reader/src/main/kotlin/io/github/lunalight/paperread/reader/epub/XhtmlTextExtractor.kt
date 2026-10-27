package io.github.lunalight.paperread.reader.epub

import io.github.lunalight.paperread.reader.xml.JvmXmlParser
import io.github.lunalight.paperread.reader.xml.XmlEvent
import io.github.lunalight.paperread.reader.xml.XmlParser
import java.io.InputStream

/**
 * 解析器工厂：Android 运行端传 `:core:data` 的 `XmlParsers::android`（XmlPullParser），
 * JVM 端缺省用 [JvmXmlParser]（JDK SAX）。
 */
typealias ParserFactory = (InputStream) -> XmlParser

/**
 * XHTML 正文提取：抽取块级元素文本（p / h1-h6 / blockquote / li / td / br），
 * 以 \n 连接为段落；跳过 script / style / 注释。
 *
 * 只依赖 [XmlParser] 接口，JVM 测试端用 SAX 驱动（T3.1 验收点）。
 */
class XhtmlTextExtractor {

    fun extract(input: InputStream, parserFactory: ParserFactory = ::JvmXmlParser): String {
        val parser = parserFactory(input)
        val paragraphs = ArrayList<String>()
        val buffer = StringBuilder()
        var skipDepth = 0 // script/style 嵌套深度

        fun flush() {
            val text = collapseWhitespace(buffer.toString())
            if (text.isNotEmpty()) paragraphs.add(text)
            buffer.clear()
        }

        while (true) {
            when (val event = parser.next()) {
                null -> break
                is XmlEvent.StartElement -> when {
                    event.name in SKIP_TAGS -> skipDepth++
                    skipDepth > 0 -> Unit
                    event.name in BLOCK_TAGS || event.name == "br" -> flush()
                }

                is XmlEvent.EndElement -> when {
                    event.name in SKIP_TAGS && skipDepth > 0 -> skipDepth--
                    skipDepth > 0 -> Unit
                    event.name in BLOCK_TAGS -> flush()
                }

                is XmlEvent.Text -> if (skipDepth == 0) buffer.append(event.content)
                else -> Unit
            }
        }
        flush()
        return paragraphs.joinToString("\n")
    }

    /** 折叠连续空白（含换行）为单个空格；段落结构由返回值的 \n 表达。 */
    private fun collapseWhitespace(raw: String): String =
        raw.replace(WHITESPACE, " ").trim()

    private companion object {
        val BLOCK_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "td")
        val SKIP_TAGS = setOf("script", "style")
        val WHITESPACE = Regex("""\s+""")
    }
}
