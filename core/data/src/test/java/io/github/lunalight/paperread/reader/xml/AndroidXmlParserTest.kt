package io.github.lunalight.paperread.reader.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AndroidXmlParser 与 JvmXmlParser 的一致性验证：
 * 同一份 XML 两端必须产出相同事件序列（EPUB 解析在两端行为一致的前提）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidXmlParserTest {

    private fun parse(xml: String): List<XmlEvent> {
        // 走与生产相同的工厂路径（内部 Xml.newPullParser + 命名空间处理）
        val parser = XmlParsers.android(java.io.ByteArrayInputStream(xml.toByteArray()))
        return generateSequence { parser.next() }.toList()
    }

    @Test
    fun `matches jvm parser events for opf-like xml`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>荒原之始</dc:title>
                <dc:creator>测试作者</dc:creator>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="ch1"/></spine>
            </package>
        """.trimIndent()
        val events = parse(xml)
        val jvmEvents = run {
            val parser = JvmXmlParser(java.io.ByteArrayInputStream(xml.toByteArray()))
            generateSequence { parser.next() }.toList()
        }
        // 事件序列一致（Text 分片可能不同，比较元素名与文本拼接）
        val names = events.filterIsInstance<XmlEvent.StartElement>().map { it.name }
        val jvmNames = jvmEvents.filterIsInstance<XmlEvent.StartElement>().map { it.name }
        assertEquals(jvmNames, names)
        val text = events.filterIsInstance<XmlEvent.Text>().joinToString("") { it.content }
        val jvmText = jvmEvents.filterIsInstance<XmlEvent.Text>().joinToString("") { it.content }
        assertEquals(jvmText, text)
        // 命名空间前缀元素返回本地名
        assertTrue(names.containsAll(listOf("package", "metadata", "title", "creator", "item", "spine", "itemref")))
        // 属性可读
        val item = events.filterIsInstance<XmlEvent.StartElement>().first { it.name == "item" }
        assertEquals("ncx", item.attributes["id"])
        assertEquals("toc.ncx", item.attributes["href"])
    }

    @Test
    fun `next returns null at end and tolerates repeat calls`() {
        val parser = XmlParsers.android("<a>t</a>".byteInputStream())
        var count = 0
        while (parser.next() != null) count++
        assertTrue(count > 0)
        assertNull(parser.next())
        assertNull(parser.next())
    }

    @Test
    fun `parses xhtml with namespaces`() {
        val events = parse(
            """
            <html xmlns="http://www.w3.org/1999/xhtml"><body><p>少年提灯独行。</p></body></html>
            """.trimIndent(),
        )
        val names = events.filterIsInstance<XmlEvent.StartElement>().map { it.name }
        assertEquals(listOf("html", "body", "p"), names)
        val text = events.filterIsInstance<XmlEvent.Text>().joinToString("") { it.content }
        assertEquals("少年提灯独行。", text)
    }
}
