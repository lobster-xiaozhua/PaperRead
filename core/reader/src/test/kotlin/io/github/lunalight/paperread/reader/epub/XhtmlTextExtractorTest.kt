package io.github.lunalight.paperread.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XhtmlTextExtractorTest {

    private val extractor = XhtmlTextExtractor()

    private fun extract(xhtml: String): String = extractor.extract(xhtml.byteInputStream())

    @Test
    fun `extracts paragraphs from block elements`() {
        val html = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
            <h1>第一章 荒原</h1>
            <p>少年提灯独行。</p>
            <p>风从北方来，带着沙。</p>
            </body></html>
        """.trimIndent()
        assertEquals("第一章 荒原\n少年提灯独行。\n风从北方来，带着沙。", extract(html))
    }

    @Test
    fun `skips script and style`() {
        val html = """
            <html><head><style>p{color:red}</style><script>var x=1;</script></head>
            <body><p>正文</p></body></html>
        """.trimIndent()
        assertEquals("正文", extract(html))
    }

    @Test
    fun `decodes entities and collapses whitespace`() {
        val html = "<p>a &amp;   b\n\t&lt;tag&gt; &#20013;</p>"
        assertEquals("a & b <tag> 中", extract(html))
    }

    @Test
    fun `inline tags do not split paragraphs`() {
        val html = "<p>少年<span>提灯</span>独行，<em>身后</em>是沉睡的城池。</p>"
        assertEquals("少年提灯独行，身后是沉睡的城池。", extract(html))
    }

    @Test
    fun `br forces paragraph break`() {
        val html = "<p>第一行<br/>第二行</p>"
        assertEquals("第一行\n第二行", extract(html))
    }

    @Test
    fun `skips comments`() {
        val html = "<body><!-- 注释 --><p>正文<!-- 内嵌 --></p></body>"
        assertEquals("正文", extract(html))
    }

    @Test
    fun `empty document yields empty text`() {
        assertTrue(extract("<html><body></body></html>").isEmpty())
    }

    @Test
    fun `list items become separate paragraphs`() {
        val html = "<ul><li>其一</li><li>其二</li></ul>"
        assertEquals("其一\n其二", extract(html))
    }
}
