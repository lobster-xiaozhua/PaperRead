package io.github.lunalight.paperread.reader.xml

import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * JVM 端 XmlParser 实现：SAX 推模型转拉模型。
 *
 * 零 Android 依赖，供单元测试驱动 EPUB 解析逻辑（T3.1 验收点）。
 * SAX 为同步全量解析，实现为「首次 next 时一次性解析入队、随后顺序流出」；
 * EPUB 的 OPF/NCX/XHTML 均为百 KB 级文档，内存无压力。
 */
class JvmXmlParser(private val input: InputStream) : XmlParser {

    private val events = ArrayDeque<XmlEvent>()
    private var parsed = false

    override fun next(): XmlEvent? {
        if (!parsed) {
            parseAll(input)
            parsed = true
        }
        return events.removeFirstOrNull()
    }

    private fun parseAll(input: InputStream) {
        val handler = object : DefaultHandler() {
            override fun startDocument() {
                events.add(XmlEvent.StartDocument)
            }

            override fun endDocument() {
                events.add(XmlEvent.EndDocument)
            }

            override fun startElement(uri: String?, localName: String?, qName: String, atts: Attributes) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName
                events.add(XmlEvent.StartElement(name, toMap(atts)))
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName
                events.add(XmlEvent.EndElement(name))
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                events.add(XmlEvent.Text(String(ch, start, length)))
            }
        }
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.newSAXParser().xmlReader.apply {
            contentHandler = handler
            parse(InputSource(input))
        }
    }

    private fun toMap(atts: Attributes): Map<String, String> = buildMap {
        for (i in 0 until atts.length) {
            put(atts.getLocalName(i).ifEmpty { atts.getQName(i) }, atts.getValue(i))
        }
    }
}
