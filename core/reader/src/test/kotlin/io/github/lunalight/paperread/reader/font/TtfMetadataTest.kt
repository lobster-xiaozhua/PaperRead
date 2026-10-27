package io.github.lunalight.paperread.reader.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TtfMetadata] 单测（T-018 · AC-16.2 / AC-16.3 / AC-16.6）。
 *
 * 全部用例用 [SfntBuilder] 手工构造最小 sfnt 字节——仓库不放真实字体文件，
 * 每个分支的字节布局都在测试里显式可见（见 SfntBuilder 的类注释）。
 */
class TtfMetadataTest {

    // ---- 合法容器 ----

    @Test
    fun `TrueType 头解析出完整名`() {
        val bytes = SfntBuilder().build()
        assertEquals(SfntFormat.TRUE_TYPE, TtfMetadata.sniff(bytes))
        assertEquals("Fixture Serif Regular", TtfMetadata.displayName(bytes))
    }

    @Test
    fun `OTTO 头同样可解析`() {
        val bytes = SfntBuilder().apply { magic = SfntBuilder.OPEN_TYPE }.build()
        assertEquals(SfntFormat.OPEN_TYPE, TtfMetadata.sniff(bytes))
        assertEquals("Fixture Serif Regular", TtfMetadata.displayName(bytes))
    }

    @Test
    fun `true 标记按 Apple TrueType 处理`() {
        val bytes = SfntBuilder().apply { magic = SfntBuilder.TRUE_TYPE_APPLE }.build()
        assertEquals(SfntFormat.TRUE_TYPE_APPLE, TtfMetadata.sniff(bytes))
        assertEquals("Fixture Serif Regular", TtfMetadata.displayName(bytes))
    }

    @Test
    fun `ttcf 集合取第一个字体的名字`() {
        val bytes = SfntBuilder().ttc()
        assertEquals(SfntFormat.COLLECTION, TtfMetadata.sniff(bytes))
        assertEquals("Fixture Serif Regular", TtfMetadata.displayName(bytes))
    }

    // ---- 名字选择 ----

    @Test
    fun `缺少 full name 时回退 family name`() {
        val bytes = SfntBuilder().apply {
            fullName = null
            familyName = "Fixture Serif"
        }.build()
        val names = TtfMetadata.parse(bytes)
        assertEquals("Fixture Serif", names?.familyName)
        assertNull(names?.fullName)
        assertEquals("Fixture Serif", names?.displayName)
    }

    @Test
    fun `只有 Mac 记录时按单字节编码回退`() {
        val bytes = SfntBuilder().apply {
            fullName = null
            familyName = null
            macFamilyName = "Mac Serif"
        }.build()
        assertEquals("Mac Serif", TtfMetadata.displayName(bytes))
    }

    @Test
    fun `Windows 记录优先于 Mac 记录`() {
        val bytes = SfntBuilder().apply {
            fullName = null
            familyName = "Windows Name"
            macFamilyName = "Mac Name"
        }.build()
        assertEquals("Windows Name", TtfMetadata.displayName(bytes))
    }

    @Test
    fun `中文名字按 UTF-16BE 还原`() {
        val bytes = SfntBuilder().apply {
            fullName = "霞鹜文楷 Regular"
            familyName = "霞鹜文楷"
        }.build()
        assertEquals("霞鹜文楷 Regular", TtfMetadata.displayName(bytes))
    }

    // ---- 失败路径：一律 null，绝不抛 ----

    @Test
    fun `name 表缺失返回 null`() {
        val bytes = SfntBuilder().apply { includeNameTable = false }.build()
        assertEquals(SfntFormat.TRUE_TYPE, TtfMetadata.sniff(bytes))
        assertNull(TtfMetadata.parse(bytes))
        assertNull(TtfMetadata.displayName(bytes))
    }

    @Test
    fun `非法魔数不是 sfnt`() {
        val bytes = "PK\u0003\u0004this-is-a-zip".toByteArray(Charsets.US_ASCII)
        assertNull(TtfMetadata.sniff(bytes))
        assertNull(TtfMetadata.parse(bytes))
    }

    @Test
    fun `空字节与不足四字节都不抛`() {
        assertNull(TtfMetadata.sniff(ByteArray(0)))
        assertNull(TtfMetadata.parse(ByteArray(0)))
        assertNull(TtfMetadata.parse(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun `截断的 sfnt 头返回 null`() {
        val full = SfntBuilder().build()
        for (size in 1 until 12) {
            assertNull("截断到 $size 字节应判死", TtfMetadata.parse(full.copyOf(size)))
        }
    }

    @Test
    fun `截断的表目录返回 null`() {
        val full = SfntBuilder().build()
        // 12 字节头声明 1 张表，但记录区只有一半
        assertNull(TtfMetadata.parse(full.copyOf(20)))
    }

    @Test
    fun `截断的 name 表返回 null`() {
        val full = SfntBuilder().build()
        for (size in 30 until full.size - 1) {
            assertNull("name 表截断到 $size 字节应判死", TtfMetadata.parse(full.copyOf(size)))
        }
    }

    @Test
    fun `表记录声明长度越界返回 null`() {
        val bytes = SfntBuilder().apply { declaredNameLength = 1_000_000 }.build()
        assertNull(TtfMetadata.parse(bytes))
    }

    @Test
    fun `名字为空白时视为缺失`() {
        val bytes = SfntBuilder().apply {
            fullName = "   "
            familyName = null
        }.build()
        assertNull(TtfMetadata.displayName(bytes))
    }

    @Test
    fun `名字带 NUL 填充会被裁掉`() {
        val bytes = SfntBuilder().apply {
            fullName = "Padded\u0000\u0000"
            familyName = null
        }.build()
        assertEquals("Padded", TtfMetadata.displayName(bytes))
    }

    @Test
    fun `numTables 为零返回 null`() {
        val bytes = SfntBuilder().build()
        // numTables 位于第 4..5 字节
        bytes[4] = 0
        bytes[5] = 0
        assertNull(TtfMetadata.parse(bytes))
    }
}
