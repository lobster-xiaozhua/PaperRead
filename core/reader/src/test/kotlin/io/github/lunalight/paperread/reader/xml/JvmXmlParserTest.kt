package io.github.lunalight.paperread.reader.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JvmXmlParserTest {

    private fun parse(xml: String): List<XmlEvent> {
        val parser = JvmXmlParser(xml.byteInputStream())
        return generateSequence { parser.next() }.toList()
    }

    @Test
    fun `parses elements attributes and text`() {
        val events = parse("""<?xml version="1.0"?><root id="r1"><child name="a">文本</child></root>""")
        assertEquals(XmlEvent.StartDocument, events[0])
        val root = events[1] as XmlEvent.StartElement
        assertEquals("root", root.name)
        assertEquals("r1", root.attributes["id"])
        val child = events[2] as XmlEvent.StartElement
        assertEquals("child", child.name)
        assertEquals("a", child.attributes["name"])
        val text = events[3] as XmlEvent.Text
        assertEquals("文本", text.content)
        assertEquals(XmlEvent.EndElement("child"), events[4])
        assertEquals(XmlEvent.EndElement("root"), events[5])
    }

    @Test
    fun `decodes entities`() {
        val events = parse("<p>a &amp; b &lt;tag&gt; &#20013;</p>")
        val text = events.filterIsInstance<XmlEvent.Text>().joinToString("") { it.content }
        assertEquals("a & b <tag> 中", text)
    }

    @Test
    fun `namespace aware parsing keeps local names`() {
        val events = parse("""<x:root xmlns:x="urn:test"><x:child>c</x:child></x:root>""")
        val start = events.filterIsInstance<XmlEvent.StartElement>().map { it.name }
        assertEquals(listOf("root", "child"), start)
    }

    @Test
    fun `next returns null after end document and tolerates repeat calls`() {
        val parser = JvmXmlParser("<a>t</a>".byteInputStream())
        var count = 0
        while (parser.next() != null) count++
        assertTrue(count > 0)
        assertNull(parser.next())
        assertNull(parser.next())
    }

    @Test
    fun `parses xhtml with mixed content and comments`() {
        val xml = """
            <html xmlns="http://www.w3.org/1999/xhtml">
            <!-- 注释 -->
            <body><p>第一段<!-- 内嵌 --></p><p>第二段</p></body>
            </html>
        """.trimIndent()
        val events = parse(xml)
        val texts = events.filterIsInstance<XmlEvent.Text>().joinToString("|") { it.content }
        assertTrue(texts.contains("第一段"))
        assertTrue(texts.contains("第二段"))
        assertTrue(events.none { it is XmlEvent.Text && it.content.contains("注释") })
    }
}
