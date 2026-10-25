package io.github.lunalight.paperread.reader.xml

/**
 * XML 解析事件（拉取模型）。
 *
 * 屏蔽 Android（XmlPullParser）与 JVM（SAX）两套 API 差异：
 * EPUB 解析逻辑只依赖本接口，可在纯 JVM 环境单测。
 */
sealed interface XmlEvent {
    data object StartDocument : XmlEvent
    data class StartElement(val name: String, val attributes: Map<String, String> = emptyMap()) : XmlEvent
    data class EndElement(val name: String) : XmlEvent
    data class Text(val content: String) : XmlEvent
    data object EndDocument : XmlEvent
}

/**
 * 拉取式 XML 解析器。
 *
 * 约定：[next] 返回 null 表示文档结束；实现方需容忍 [next] 在返回 null 后被再次调用。
 * 相邻文本可能被拆分为多个 [XmlEvent.Text]，调用方应自行合并。
 */
interface XmlParser {
    fun next(): XmlEvent?
}
