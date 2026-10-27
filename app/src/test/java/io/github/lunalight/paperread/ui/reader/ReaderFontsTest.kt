package io.github.lunalight.paperread.ui.reader

import androidx.compose.ui.text.font.FontFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 正文字族解析与降级（T-018 · AC-16.4 / AC-16.6）。
 *
 * AC-16.6 的判据是「**静默**回退」：文件缺失、内容损坏、读取抛异常三种情况
 * 都必须回到内置字族且不向调用方抛——所以这里逐条断言「返回的是 Serif /
 * SansSerif 这两个内置对象」，而不是只断言「没崩」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderFontsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `没有自定义字体时返回内置字族`() {
        assertEquals(
            FontFamily.Serif,
            readerFontFamily("serif", null) { error("不应被调用") },
        )
        assertEquals(
            FontFamily.SansSerif,
            readerFontFamily("sans", null) { error("不应被调用") },
        )
    }

    @Test
    fun `空 id 视同未设置`() {
        assertEquals(
            FontFamily.Serif,
            readerFontFamily("serif", "   ") { error("不应被调用") },
        )
    }

    @Test
    fun `字体文件缺失时静默回退`() {
        val missing = File(tmp.root, "gone.ttf")
        val family = readerFontFamily("serif", "font-1") { missing }
        assertEquals("AC-16.6：文件缺失必须回退默认字族", FontFamily.Serif, family)
    }

    @Test
    fun `字体文件为空时静默回退`() {
        val empty = tmp.newFile("empty.ttf")
        assertEquals(FontFamily.SansSerif, readerFontFamily("sans", "font-1") { empty })
    }

    @Test
    fun `字体文件内容不是 sfnt 时静默回退`() {
        val fake = tmp.newFile("fake.ttf")
        fake.writeText("这不是字体，只是改了后缀的文本")

        assertEquals(
            "AC-16.6：解析失败不得崩，必须回退默认字族",
            FontFamily.Serif,
            readerFontFamily("serif", "font-1") { fake },
        )
    }

    @Test
    fun `字体文件被截断成不足四字节时静默回退`() {
        val truncated = tmp.newFile("truncated.ttf")
        truncated.writeBytes(byteArrayOf(0x00, 0x01))

        assertEquals(FontFamily.Serif, readerFontFamily("serif", "font-1") { truncated })
    }

    @Test
    fun `解析器本身抛异常时静默回退`() {
        val family = readerFontFamily("serif", "font-1") { error("存储层炸了") }
        assertEquals(FontFamily.Serif, family)
    }

    @Test
    fun `合法 sfnt 文件会被真的用上`() {
        val font = tmp.newFile("custom.ttf")
        font.writeBytes(minimalSfnt())

        val family = readerFontFamily("serif", "font-1") { font }

        assertNotEquals("命中自定义字体时不应回退内置 Serif", FontFamily.Serif, family)
        assertNotEquals(FontFamily.SansSerif, family)
    }

    @Test
    fun `内置字族映射保持历史口径`() {
        assertEquals(FontFamily.Serif, builtInFontFamily("serif"))
        assertEquals(FontFamily.SansSerif, builtInFontFamily("sans"))
        // 未知值按无衬线处理（历史行为，不因新增字段而变）
        assertEquals(FontFamily.SansSerif, builtInFontFamily("whatever"))
    }

    /** 12 字节 sfnt 头：magic 合法即足以通过「是不是字体」的探测。 */
    private fun minimalSfnt(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0)
}
