package io.github.lunalight.paperread.reader.epub

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubParserTest {

    private val parser = EpubParser() // JVM SAX 驱动

    // ---- fixture 构建 ----

    private fun buildEpub(files: Map<String, String>): File {
        val file = File.createTempFile("epub-test", ".epub")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }

    /** 手工构造 STORED 方式 ZIP：条目名按 GB18030 编码（模拟国产 EPUB）。 */
    private fun buildRawZipGbkNames(entries: List<Pair<String, String>>): File {
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        fun le32(v: Long): ByteArray {
            val l = v.toInt()
            return byteArrayOf(
                (l and 0xFF).toByte(), ((l shr 8) and 0xFF).toByte(),
                ((l shr 16) and 0xFF).toByte(), ((l shr 24) and 0xFF).toByte(),
            )
        }
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var offset = 0
        for ((name, content) in entries) {
            val nameBytes = name.toByteArray(Charset.forName("GB18030"))
            val data = content.toByteArray(Charsets.UTF_8)
            val crc = CRC32().also { it.update(data) }.value
            // local header
            out.write("PK\u0003\u0004".toByteArray())
            out.write(le16(20)); out.write(le16(0)) // 版本 / 标志（无 UTF-8 标志位）
            out.write(le16(0)); out.write(le16(0)); out.write(le16(0)) // stored / time / date
            out.write(le32(crc)); out.write(le32(data.size.toLong())); out.write(le32(data.size.toLong()))
            out.write(le16(nameBytes.size)); out.write(le16(0))
            out.write(nameBytes); out.write(data)
            // central directory
            central.write("PK\u0001\u0002".toByteArray())
            central.write(le16(20)); central.write(le16(20)); central.write(le16(0))
            central.write(le16(0)); central.write(le16(0)); central.write(le16(0))
            central.write(le32(crc)); central.write(le32(data.size.toLong())); central.write(le32(data.size.toLong()))
            central.write(le16(nameBytes.size)); central.write(le16(0)); central.write(le16(0))
            central.write(le16(0)); central.write(le16(0)); central.write(le32(0))
            central.write(le32(offset.toLong()))
            central.write(nameBytes)
            offset += 30 + nameBytes.size + data.size
        }
        val cd = central.toByteArray()
        out.write(cd)
        out.write("PK\u0005\u0006".toByteArray())
        out.write(le16(0)); out.write(le16(0))
        out.write(le16(entries.size)); out.write(le16(entries.size))
        out.write(le32(cd.size.toLong())); out.write(le32(offset.toLong()))
        out.write(le16(0))
        val file = File.createTempFile("epub-gbk", ".epub")
        file.writeBytes(out.toByteArray())
        file.deleteOnExit()
        return file
    }

    private val containerXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private fun opf(
        title: String = "荒原之始",
        creator: String = "测试作者",
        withNcx: Boolean = true,
        chapterHrefs: List<String> = listOf("Text/ch1.xhtml", "Text/ch2.xhtml", "Text/ch3.xhtml"),
        spineExtra: String = "",
    ): String {
        val items = StringBuilder()
        if (withNcx) items.append("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
        chapterHrefs.forEachIndexed { i, href ->
            items.append("""<item id="ch${i + 1}" href="$href" media-type="application/xhtml+xml"/>""")
        }
        val refs = chapterHrefs.indices.joinToString("") { """<itemref idref="ch${it + 1}"/>""" }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>$title</dc:title>
                <dc:creator>$creator</dc:creator>
                <dc:language>zh-CN</dc:language>
                <dc:identifier id="bookid">urn:uuid:test</dc:identifier>
              </metadata>
              <manifest>$items</manifest>
              <spine ${if (withNcx) "toc=\"ncx\"" else ""}>$refs$spineExtra</spine>
            </package>
        """.trimIndent()
    }

    private fun ncx(titles: List<Pair<String, String>>): String {
        val points = titles.mapIndexed { i, (src, label) ->
            """
            <navPoint id="np${i + 1}" playOrder="${i + 1}">
              <navLabel><text>$label</text></navLabel>
              <content src="$src"/>
            </navPoint>
            """
        }.joinToString("")
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="urn:uuid:test"/></head>
              <docTitle><text>荒原之始</text></docTitle>
              <navMap>$points</navMap>
            </ncx>
        """.trimIndent()
    }

    private fun chapter(title: String, vararg paragraphs: String): String {
        val ps = paragraphs.joinToString("") { "<p>$it</p>" }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
            <body><h1>$title</h1>$ps</body></html>
        """.trimIndent()
    }

    private fun standardEpub(withNcx: Boolean = true): File = buildEpub(
        mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to containerXml,
            "OEBPS/content.opf" to opf(withNcx = withNcx),
            "OEBPS/toc.ncx" to ncx(
                listOf(
                    "Text/ch1.xhtml" to "第一章 荒原",
                    "Text/ch2.xhtml" to "第二章 古城",
                    "Text/ch3.xhtml" to "第三章 远行",
                ),
            ),
            "OEBPS/Text/ch1.xhtml" to chapter("第一章 荒原", "少年提灯独行。", "风从北方来。"),
            "OEBPS/Text/ch2.xhtml" to chapter("第二章 古城", "城池在夜里醒来。"),
            "OEBPS/Text/ch3.xhtml" to chapter("第三章 远行", "他决定北上。"),
        ),
    )

    private fun assertCoverage(content: EpubContent) {
        val refs = content.refs
        assertTrue("区间必须连续覆盖全文", refs.zipWithNext().all { (a, b) -> a.charEnd == b.charStart })
        assertEquals(0, refs.first().charStart)
        assertEquals(content.fullText.length, refs.last().charEnd)
        assertEquals(refs.indices.toList(), refs.map { it.index })
    }

    // ---- 用例 ----

    @Test
    fun `parses standard epub2 metadata spine and ncx titles`() {
        val content = parser.parse(standardEpub())
        assertEquals("荒原之始", content.title)
        assertEquals("测试作者", content.author)
        assertEquals(3, content.refs.size)
        assertEquals(listOf("第一章 荒原", "第二章 古城", "第三章 远行"), content.refs.map { it.title })
        assertCoverage(content)
        assertTrue(content.fullText.contains("少年提灯独行。"))
        assertTrue(content.fullText.contains("他决定北上。"))
    }

    @Test
    fun `falls back to first line title without ncx`() {
        val file = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to opf(withNcx = false),
                "OEBPS/Text/ch1.xhtml" to chapter("第一章 荒原", "少年提灯独行。"),
                "OEBPS/Text/ch2.xhtml" to chapter("第二章 古城", "城池在夜里醒来。"),
                "OEBPS/Text/ch3.xhtml" to chapter("第三章 远行", "他决定北上。"),
            ),
        )
        val content = parser.parse(file)
        assertEquals(3, content.refs.size)
        // 无 NCX：标题退化为正文首行（XHTML 的 h1）
        assertEquals(listOf("第一章 荒原", "第二章 古城", "第三章 远行"), content.refs.map { it.title })
    }

    @Test
    fun `rejects epub with encrypted content`() {
        val encryptionXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                <EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes256-cbc"/>
                <CipherData>
                  <CipherReference URI="OEBPS/Text/ch1.xhtml"/>
                </CipherData>
              </EncryptedData>
            </encryption>
        """.trimIndent()
        val file = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "META-INF/encryption.xml" to encryptionXml,
                "OEBPS/content.opf" to opf(),
                "OEBPS/Text/ch1.xhtml" to chapter("第一章 荒原", "少年提灯独行。"),
                "OEBPS/Text/ch2.xhtml" to chapter("第二章 古城", "城池在夜里醒来。"),
                "OEBPS/Text/ch3.xhtml" to chapter("第三章 远行", "他决定北上。"),
            ),
        )
        val ex = assertThrows(EpubEncryptedException::class.java) { parser.parse(file) }
        assertTrue(ex.message!!.contains("DRM"))
    }

    @Test
    fun `parses gbk entry names via charset fallback`() {
        val gbkNovel = "OEBPS/Text/第一章.xhtml"
        val file = buildRawZipGbkNames(
            listOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to opf(
                    chapterHrefs = listOf("Text/第一章.xhtml", "Text/第二章.xhtml", "Text/第三章.xhtml"),
                ),
                "OEBPS/Text/第一章.xhtml" to chapter("第一章 荒原", "少年提灯独行。"),
                "OEBPS/Text/第二章.xhtml" to chapter("第二章 古城", "城池在夜里醒来。"),
                "OEBPS/Text/第三章.xhtml" to chapter("第三章 远行", "他决定北上。"),
            ),
        )
        // JDK 行为：ZipFile 以 UTF-8 打开时，任一条目名无法严格解码即整体抛错
        assertThrows(java.util.zip.ZipException::class.java) {
            java.util.zip.ZipFile(file, Charsets.UTF_8)
        }
        val content = parser.parse(file)
        assertEquals(3, content.refs.size)
        assertTrue(content.fullText.contains("少年提灯独行。"))
    }

    @Test
    fun `handles multi level directories and fragments`() {
        val file = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to opf(
                    chapterHrefs = listOf(
                        "Text/part1/ch1.xhtml#sec1",
                        "Text/part1/ch2.xhtml",
                        "Text/part2/ch3.xhtml",
                    ),
                ),
                "OEBPS/Text/part1/ch1.xhtml" to chapter("第一章 荒原", "少年提灯独行。"),
                "OEBPS/Text/part1/ch2.xhtml" to chapter("第二章 古城", "城池在夜里醒来。"),
                "OEBPS/Text/part2/ch3.xhtml" to chapter("第三章 远行", "他决定北上。"),
            ),
        )
        val content = parser.parse(file)
        assertEquals(3, content.refs.size)
        assertCoverage(content)
    }

    @Test
    fun `skips non xhtml spine items`() {
        val opfWithImage = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>图文混排</dc:title>
                <dc:creator>作者</dc:creator>
              </metadata>
              <manifest>
                <item id="img" href="Images/cover.jpg" media-type="image/jpeg"/>
                <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch3" href="Text/ch3.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="img"/>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
                <itemref idref="ch3"/>
              </spine>
            </package>
        """.trimIndent()
        val file = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to opfWithImage,
                "OEBPS/Images/cover.jpg" to "fake-jpeg-bytes",
                "OEBPS/Text/ch1.xhtml" to chapter("第一章 荒原", "少年提灯独行。"),
                "OEBPS/Text/ch2.xhtml" to chapter("第二章 古城", "城池在夜里醒来。"),
                "OEBPS/Text/ch3.xhtml" to chapter("第三章 远行", "他决定北上。"),
            ),
        )
        val content = parser.parse(file)
        assertEquals(3, content.refs.size)
        assertEquals("图文混排", content.title)
    }

    @Test
    fun `throws when container xml missing`() {
        val file = buildEpub(mapOf("OEBPS/content.opf" to opf()))
        assertThrows(EpubFormatException::class.java) { parser.parse(file) }
    }

    @Test
    fun `throws when no readable content`() {
        val file = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to opf(
                    chapterHrefs = listOf("Text/empty.xhtml"),
                ),
                "OEBPS/Text/empty.xhtml" to "<html><body></body></html>",
            ),
        )
        assertThrows(EpubFormatException::class.java) { parser.parse(file) }
    }
}
