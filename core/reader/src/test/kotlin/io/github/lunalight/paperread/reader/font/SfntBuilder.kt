package io.github.lunalight.paperread.reader.font

import java.io.ByteArrayOutputStream

/**
 * 最小合法 sfnt 字节构造器（**只**供测试用）。
 *
 * 仓库里刻意不放真实字体文件：字体动辄数 MB，二进制入库既污染 diff 也说不清
 * 授权。取名字所需的布局只有三段（sfnt 头 / table directory / name 表），
 * 手工拼 40 余行字节即可覆盖全部解析分支，且每个字节都可见、可解释。
 *
 * ```
 * 0        12        28                 28+len(name)
 * +--------+---------+------------------+
 * | sfnt 头 | 表记录   | name 表           |
 * +--------+---------+------------------+
 * ```
 */
internal class SfntBuilder {

    /** 容器魔数（决定 SfntFormat）。 */
    var magic: ByteArray = TRUE_TYPE

    /** name 表里的完整名（nameID 4）；null = 不写该记录。 */
    var fullName: String? = "Fixture Serif Regular"

    /** name 表里的字族名（nameID 1）；null = 不写该记录。 */
    var familyName: String? = "Fixture Serif"

    /** 额外追加一条 Macintosh (1, 0) 字族名，用于验证平台回退。 */
    var macFamilyName: String? = null

    /** false = 目录里只放一张 `cmap` 表（模拟「name 表缺失」）。 */
    var includeNameTable: Boolean = true

    /**
     * name 表在**整个文件**里的绝对偏移。
     *
     * 普通 sfnt 为 28（12 头 + 16 单条表记录）；`ttcf` 需把首字体目录前移，
     * 由 [ttc] 传入 44（16 集合头 + 28）。
     */
    var nameTableOffset: Int = DEFAULT_NAME_OFFSET

    /** 覆盖表记录里声明的 name 表长度（造越界用例）。 */
    var declaredNameLength: Int? = null

    fun build(): ByteArray {
        val nameTable = if (includeNameTable) nameTableBytes() else ByteArray(0)
        val length = declaredNameLength ?: nameTable.size

        val out = ByteArrayOutputStream()
        out.write(magic)
        out.writeU16(1) // numTables
        out.writeU16(0) // searchRange
        out.writeU16(0) // entrySelector
        out.writeU16(0) // rangeShift
        // 单条表记录：tag + checksum + offset + length
        out.write(if (includeNameTable) NAME_TAG else CMAP_TAG)
        out.write(byteArrayOf(0, 0, 0, 0))
        out.writeU32(nameTableOffset.toLong())
        out.writeU32(length.toLong())
        // 头部与 name 表之间可能被 ttc 头占位，这里用零填充到 nameTableOffset
        while (out.size() < nameTableOffset) out.write(0)
        out.write(nameTable)
        return out.toByteArray()
    }

    /**
     * 把 [build] 的 sfnt 包成 `ttcf` 集合（单字体）。
     *
     * 集合头占 [TTC_FIRST_DIRECTORY] 字节，因此 sfnt 体内偏移要整体后移：
     * 表记录里声明的 name 表偏移（相对**文件**起点）改为 16 + 28 = 44。
     */
    fun ttc(): ByteArray {
        val sfnt = build()
        writeU32At(sfnt, SFNT_HEADER + 8, (TTC_FIRST_DIRECTORY + DEFAULT_NAME_OFFSET).toLong())
        val out = ByteArrayOutputStream()
        out.write(TTCF)
        out.writeU32(0x00010000L) // version 1.0
        out.writeU32(1L) // numFonts
        out.writeU32(TTC_FIRST_DIRECTORY.toLong()) // offset[0]
        out.write(sfnt)
        return out.toByteArray()
    }

    private fun nameTableBytes(): ByteArray {
        data class Entry(val platform: Int, val encoding: Int, val nameId: Int, val text: String)

        val entries = buildList {
            fullName?.let { add(Entry(3, 1, 4, it)) }
            familyName?.let { add(Entry(3, 1, 1, it)) }
            macFamilyName?.let { add(Entry(1, 0, 1, it)) }
        }
        val strings = ByteArrayOutputStream()
        val placed = entries.map { entry ->
            val bytes = if (entry.platform == 1) {
                entry.text.toByteArray(Charsets.ISO_8859_1)
            } else {
                entry.text.toByteArray(Charsets.UTF_16BE)
            }
            val offset = strings.size()
            strings.write(bytes)
            Triple(entry, offset, bytes.size)
        }

        val out = ByteArrayOutputStream()
        out.writeU16(0) // format
        out.writeU16(placed.size) // count
        out.writeU16(6 + placed.size * 12) // stringOffset
        placed.forEach { (entry, offset, size) ->
            out.writeU16(entry.platform)
            out.writeU16(entry.encoding)
            out.writeU16(0x0409) // languageID
            out.writeU16(entry.nameId)
            out.writeU16(size)
            out.writeU16(offset)
        }
        out.write(strings.toByteArray())
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value shr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        write((value shr 24 and 0xFF).toInt())
        write((value shr 16 and 0xFF).toInt())
        write((value shr 8 and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }

    /** 直接改写字节数组里的大端 uint32（造 ttcf 偏移用）。 */
    private fun writeU32At(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            bytes[offset + index] = ((value shr (24 - index * 8)) and 0xFF).toByte()
        }
    }

    companion object {
        val TRUE_TYPE = byteArrayOf(0x00, 0x01, 0x00, 0x00)
        val OPEN_TYPE = "OTTO".toByteArray(Charsets.US_ASCII)
        val TRUE_TYPE_APPLE = "true".toByteArray(Charsets.US_ASCII)
        val TTCF = "ttcf".toByteArray(Charsets.US_ASCII)
        private val NAME_TAG = "name".toByteArray(Charsets.US_ASCII)
        private val CMAP_TAG = "cmap".toByteArray(Charsets.US_ASCII)

        /** 12 字节 sfnt 头 + 16 字节单条表记录。 */
        const val DEFAULT_NAME_OFFSET = 28

        /** sfnt 头长度（magic 4 + 四个 uint16）。 */
        const val SFNT_HEADER = 12

        /** ttcf 集合头长度（tag + version + numFonts + 1 个偏移）。 */
        const val TTC_FIRST_DIRECTORY = 16
    }
}
