package io.github.lunalight.paperread.reader.txt

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharsetDetectorTest {

    private val chinese = "第一章 荒原之始\n少年提灯独行，身后是沉睡的城池。\n"

    @Test
    fun `detects UTF-8 with BOM`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + chinese.toByteArray(Charsets.UTF_8)
        val result = CharsetDetector.detect(bytes)
        assertEquals("UTF-8", result.displayName)
        assertEquals(Charsets.UTF_8, result.charset)
        assertEquals(3, result.bomLength)
    }

    @Test
    fun `detects UTF-16LE with BOM`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + chinese.toByteArray(utf16("LE"))
        val result = CharsetDetector.detect(bytes)
        assertEquals("UTF-16LE", result.displayName)
        assertEquals(2, result.bomLength)
    }

    @Test
    fun `detects UTF-16BE with BOM`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + chinese.toByteArray(utf16("BE"))
        val result = CharsetDetector.detect(bytes)
        assertEquals("UTF-16BE", result.displayName)
        assertEquals(2, result.bomLength)
    }

    @Test
    fun `plain UTF-8 Chinese without BOM`() {
        val bytes = chinese.toByteArray(Charsets.UTF_8)
        val result = CharsetDetector.detect(bytes)
        assertEquals("UTF-8", result.displayName)
        assertEquals(0, result.bomLength)
    }

    @Test
    fun `UTF-8 sample truncated mid-character is still detected as UTF-8`() {
        // 样本是文件前缀，64KB 边界约 2/3 概率截断多字节字符：
        // 末尾残留的不完整序列不得让合法 UTF-8 被误判成 GB18030
        val text = "第一章 荒原之始\n少年提灯独行。"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (cut in 1..2) {
            val sample = bytes.copyOf(bytes.size - cut) // 去掉末尾 1/2 字节，必落在字符中部
            assertEquals("截断 $cut 字节仍应识别为 UTF-8", "UTF-8", CharsetDetector.detect(sample).displayName)
        }
    }

    @Test
    fun `falls back to GB18030 for GBK text without BOM`() {
        val bytes = chinese.toByteArray(Charset.forName("GB18030"))
        val result = CharsetDetector.detect(bytes)
        assertEquals("GB18030", result.displayName)
        assertEquals(0, result.bomLength)
    }

    @Test
    fun `GBK Chinese bytes are not valid strict UTF-8`() {
        // 反证：GB18030 中文若按 UTF-8 严格解码必然抛错，否则上面的回退无从触发
        val bytes = chinese.toByteArray(Charset.forName("GB18030"))
        var threw = false
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
        } catch (e: java.nio.charset.CharacterCodingException) {
            threw = true
        }
        assertTrue("GBK 字节不应通过严格 UTF-8 解码", threw)
    }

    @Test
    fun `empty input defaults to UTF-8`() {
        val result = CharsetDetector.detect(ByteArray(0))
        assertEquals("UTF-8", result.displayName)
        assertEquals(0, result.bomLength)
    }

    @Test
    fun `byDisplayName resolves supported encodings and rejects unknown`() {
        assertEquals(Charsets.UTF_8, CharsetDetector.byDisplayName("UTF-8"))
        assertEquals(utf16("LE"), CharsetDetector.byDisplayName("UTF-16LE"))
        assertEquals(utf16("BE"), CharsetDetector.byDisplayName("UTF-16BE"))
        assertEquals(Charset.forName("GB18030"), CharsetDetector.byDisplayName("GB18030"))
        assertNull(CharsetDetector.byDisplayName("Shift-JIS"))
        assertFalse(CharsetDetector.SUPPORTED_ENCODINGS.contains("Shift-JIS"))
    }

    @Test
    fun `detection decodes GBK sample to original text`() {
        val bytes = chinese.toByteArray(Charset.forName("GB18030"))
        val result = CharsetDetector.detect(bytes)
        assertEquals(chinese, String(bytes, result.charset))
    }

    private fun utf16(endian: String): Charset = Charset.forName("UTF-16$endian")
}
