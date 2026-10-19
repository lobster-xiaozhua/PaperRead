package io.github.lunalight.paperread.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpeechText] 纯 JVM 单测：单元切分、空白段跳过、超长段软切分、
 * utteranceId 编解码往返、越界夹取。
 */
class SpeechTextTest {

    @Test
    fun `普通段落一段一单元`() {
        val units = SpeechText.buildUnits(listOf("第一段。", "第二段。", "第三段。"))
        assertEquals(3, units.size)
        assertEquals(listOf("p0-0", "p1-0", "p2-0"), units.map { it.utteranceId })
        assertEquals(listOf(0, 1, 2), units.map { it.paragraphIndex })
        assertEquals(listOf("第一段。", "第二段。", "第三段。"), units.map { it.text })
    }

    @Test
    fun `空白段被跳过不成单元`() {
        val units = SpeechText.buildUnits(listOf("一段", "", "   ", "\t\n", "四段"))
        assertEquals(2, units.size)
        assertEquals(listOf("一段", "四段"), units.map { it.text })
        // 段下标仍指向原始段落（第三、四段是空白，被跳过）
        assertEquals(listOf(0, 4), units.map { it.paragraphIndex })
    }

    @Test
    fun `超长段按句末标点软切分且不超上限`() {
        // 两个 3000 字段落 + 句号 → 总长 6001 > 3500
        val paragraph = "啊".repeat(3000) + "。" + "呀".repeat(3000) + "。"
        val units = SpeechText.buildUnits(listOf(paragraph))
        assertEquals(2, units.size)
        assertTrue("第一单元断在句号后", units[0].text.endsWith("。"))
        assertTrue(units.all { it.text.length <= SpeechText.MAX_UNIT_CHARS })
        assertEquals(0, units[0].paragraphIndex)
        assertEquals(0, units[1].paragraphIndex)
        assertEquals("p0-0", units[0].utteranceId)
        assertEquals("p0-1", units[1].utteranceId)
        // 拼回原文
        assertEquals(paragraph, units.joinToString("") { it.text })
    }

    @Test
    fun `超长段无句末标点时硬切且每片非空`() {
        val paragraph = "啊".repeat(SpeechText.MAX_UNIT_CHARS * 2 + 100)
        val units = SpeechText.buildUnits(listOf(paragraph))
        assertEquals(3, units.size)
        assertTrue(units.all { it.text.isNotEmpty() })
        assertTrue(units.all { it.text.length <= SpeechText.MAX_UNIT_CHARS })
    }

    @Test
    fun `首单元定位跳过不可读段`() {
        val units = SpeechText.buildUnits(listOf("一段", "  ", "三段"))
        assertEquals("目标段即可读段", 0, SpeechText.firstUnitAtOrAfter(units, 0))
        assertEquals("空白段落到下一可读段", 1, SpeechText.firstUnitAtOrAfter(units, 1))
        assertEquals("越界夹到末单元", 1, SpeechText.firstUnitAtOrAfter(units, 99))
    }

    @Test
    fun `空表的定位查询为负一`() {
        assertEquals(-1, SpeechText.firstUnitAtOrAfter(emptyList(), 0))
    }

    @Test
    fun `utteranceId 编解码往返`() {
        val units = SpeechText.buildUnits(listOf("甲", "乙", "丙"))
        units.forEach { unit ->
            assertEquals(unit.paragraphIndex, SpeechText.paragraphOf(unit.utteranceId))
        }
    }

    @Test
    fun `非法 utteranceId 解析为负一`() {
        assertEquals(-1, SpeechText.paragraphOf(""))
        assertEquals(-1, SpeechText.paragraphOf("x1-0"))
        assertEquals(-1, SpeechText.paragraphOf("p1"))
        assertEquals(-1, SpeechText.paragraphOf("pabc-0"))
    }

    @Test
    fun `自定义上限参数生效`() {
        val units = SpeechText.buildUnits(listOf("一二三四五六"), maxUnitChars = 3)
        assertEquals(2, units.size)
        assertEquals("一二三", units[0].text)
        assertEquals("四五六", units[1].text)
    }

    @Test
    fun `空输入得到空表`() {
        assertTrue(SpeechText.buildUnits(emptyList()).isEmpty())
        assertTrue(SpeechText.buildUnits(listOf("", "  ")).isEmpty())
        assertFalse(SpeechText.MAX_UNIT_CHARS > 4000)
    }
}
