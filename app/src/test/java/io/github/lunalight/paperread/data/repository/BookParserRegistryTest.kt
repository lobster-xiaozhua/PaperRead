package io.github.lunalight.paperread.data.repository

import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.BookParser
import io.github.lunalight.paperread.reader.ParseContext
import io.github.lunalight.paperread.reader.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器注册表直测（T-012）：锁住「新增格式 = 实现 BookParser + 注册一行」的契约。
 *
 * 不依赖 Robolectric / 文件系统：注册表是纯数据结构 + 一次 Map 查询。
 */
class BookParserRegistryTest {

    /** 假解析器：证明任意新格式只需实现接口即可被注册表分发。 */
    private class StubParser(
        override val format: BookFormat,
    ) : BookParser {
        var parseCalls = 0
            private set

        override fun parse(context: ParseContext): ParseResult {
            parseCalls++
            return ParseResult("t", "a", "UTF-8", 0L, emptyList())
        }
    }

    @Test
    fun `defaults resolve every supported format`() {
        val registry = BookParserRegistry.defaults()

        assertEquals(BookFormat.TXT, registry.forFormat(BookFormat.TXT).format)
        assertEquals(BookFormat.EPUB, registry.forFormat(BookFormat.EPUB).format)
        assertEquals(
            "默认注册表应覆盖全部支持格式",
            BookFormat.entries.toSet(),
            registry.registeredFormats(),
        )
    }

    @Test
    fun `new format is dispatched by adding one entry`() {
        // 同格式后注册者覆盖前者（单键语义）：证明注册是「一行」的事
        val stub = StubParser(BookFormat.EPUB)
        val registry = BookParserRegistry(listOf(TxtBookParser(), stub))

        val parser = registry.forFormat(BookFormat.EPUB)
        assertSame(stub, parser)
        parser.parse(
            ParseContext(
                sourceFile = java.io.File("unused.epub"),
                targetFile = java.io.File("unused.txt"),
                dirName = "dir",
                strategy = io.github.lunalight.paperread.reader.txt.SplitStrategy.CONSERVATIVE,
            ),
        )
        assertEquals(1, stub.parseCalls)
    }

    @Test
    fun `missing format fails fast instead of silently falling back`() {
        val registry = BookParserRegistry(listOf(TxtBookParser()))

        val failure = runCatching { registry.forFormat(BookFormat.EPUB) }.exceptionOrNull()

        assertTrue("期望 IllegalStateException，实际 $failure", failure is IllegalStateException)
        assertTrue(
            "错误信息应提示注册方式，实际 ${failure?.message}",
            failure?.message?.contains("BookParserRegistry.defaults()") == true,
        )
    }
}
