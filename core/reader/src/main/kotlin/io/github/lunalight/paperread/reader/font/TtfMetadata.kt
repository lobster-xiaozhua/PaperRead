package io.github.lunalight.paperread.reader.font

/**
 * TTF / OTF 的 `name` 表解析（T-018 · AC-16.3「显示字体名」）。
 *
 * ## 为什么自己解析
 *
 * 取显示名只需要 sfnt 头 + table directory + `name` 表三段固定布局，都是
 * 「按字节读偏移」的活。引一个字体解析库（fontbox / sfntly）会为了一屏设置页
 * 拖进几十万行第三方代码，与项目「零第三方依赖」的口径冲突，故这里**纯字节
 * 解析、零依赖**。
 *
 * ## 解析范围（只做取名字所需的最小集合）
 *
 * ```
 * sfnt 头（12 字节）           magic(4) numTables(2) searchRange(2) entrySelector(2) rangeShift(2)
 * table directory（16×N）      tag(4) checksum(4) offset(4) length(4)   ← 只认 'name'
 * name 表                      format(2) count(2) stringOffset(2) + 记录(12×count) + 字符串池
 * name 记录（12 字节）          platformID(2) encodingID(2) languageID(2) nameID(2) length(2) offset(2)
 * ```
 *
 * - 容器：`0x00010000`（TrueType）/ `OTTO`（OpenType/CFF）/ `true`（Apple 旧版）
 *   / `ttcf`（字体集合，取第一个字体）；
 * - 名字：nameID 4（full name）优先，回退 nameID 1（family）；
 * - 编码：Windows platform (3, 1) / (3, 10) 与 Unicode platform (0) 按 UTF-16BE，
 *   Macintosh (1, 0) 按 ISO-8859-1 单字节；
 * - 打分：平台分 × 10 + nameID 分（full > family），同 nameID 取最高分——即
 *   「Windows 优先、Mac 兜底」，同时兼容只有 Mac 记录的老字体。
 *
 * ## 容错策略：**失败一律返回 null，绝不抛**
 *
 * 字体文件的现实是「用户从各种渠道拿到的东西」：截断的下载、改了后缀的压缩包、
 * 甚至根本不是字体。任何一处偏移越界 / 长度不合理都直接判「这不是我能读的字体」，
 * 由调用方回退到文件名或默认字体（AC-16.6 的「不得崩」从这一层就守住）。
 * 首次校验用 [sniff]（只看 4 字节魔数），完整取名字用 [parse] / [displayName]。
 */
enum class SfntFormat {
    /** `0x00010000`：TrueType 轮廓。 */
    TRUE_TYPE,

    /** `OTTO`：OpenType（CFF 轮廓）。 */
    OPEN_TYPE,

    /** `true`：Apple 早期 TrueType 标记（等价于 `0x00010000`）。 */
    TRUE_TYPE_APPLE,

    /** `ttcf`：字体集合（.ttc），一个文件装多套字体。 */
    COLLECTION,
}

/**
 * `name` 表里解析出的两个名字。
 *
 * @param familyName nameID 1（字族名，如「霞鹜文楷」）
 * @param fullName nameID 4（完整名，如「霞鹜文楷 Regular」）
 */
data class TtfNames(val familyName: String?, val fullName: String?) {

    /** 展示名：优先完整名，缺失时回退字族名；两者皆空时为 null。 */
    val displayName: String?
        get() = fullName?.takeIf { it.isNotBlank() } ?: familyName?.takeIf { it.isNotBlank() }
}

/** 无状态解析器，可直接作为单例使用（[object] 声明）。 */
object TtfMetadata {

    /** sfnt 头长度（magic 4 + 四个 uint16）。 */
    private const val SFNT_HEADER = 12

    /** table directory 单条记录长度。 */
    private const val TABLE_RECORD = 16

    /** name 表记录长度。 */
    private const val NAME_RECORD = 12

    /** name 表固定头长度（format + count + stringOffset）。 */
    private const val NAME_HEADER = 6

    private const val TAG_NAME = 0x6E616D65L // 'name'
    private const val TAG_OTTO = 0x4F54544FL // 'OTTO'
    private const val TAG_TRUE = 0x74727565L // 'true'
    private const val TAG_TTCF = 0x74746366L // 'ttcf'
    private const val VERSION_1_0 = 0x00010000L

    private const val NAME_ID_FAMILY = 1
    private const val NAME_ID_FULL = 4

    /**
     * 只读前 4 字节判定容器格式（AC-16.2 的魔数校验入口）。
     *
     * @param header 文件头（至少 4 字节；传整个文件也可以）
     * @return 识别出的格式；不是 sfnt 容器时返回 null
     */
    fun sniff(header: ByteArray): SfntFormat? {
        if (header.size < 4) return null
        return when (u32(header, 0)) {
            VERSION_1_0 -> SfntFormat.TRUE_TYPE
            TAG_OTTO -> SfntFormat.OPEN_TYPE
            TAG_TRUE -> SfntFormat.TRUE_TYPE_APPLE
            TAG_TTCF -> SfntFormat.COLLECTION
            else -> null
        }
    }

    /**
     * 解析 `name` 表。
     *
     * @return 解析结果；任何结构异常（截断 / 越界 / 缺 name 表 / 无可用名字）返回 null
     */
    fun parse(bytes: ByteArray): TtfNames? = runCatching { parseOrNull(bytes) }.getOrNull()

    /** 便捷入口：只要展示名（缺失返回 null）。 */
    fun displayName(bytes: ByteArray): String? = parse(bytes)?.displayName

    // ---- 内部 ----

    /** table directory 的定位结果：记录区起点 + 记录条数。 */
    private data class Directory(val recordsStart: Int, val count: Int)

    /** 带打分的候选名字。 */
    private data class Scored(val text: String, val score: Int)

    private fun parseOrNull(bytes: ByteArray): TtfNames? {
        val format = sniff(bytes) ?: return null
        val directory = tableDirectory(bytes, format) ?: return null
        val (offset, length) = findTable(bytes, directory, TAG_NAME) ?: return null
        return readNames(bytes, offset.toInt(), length)
    }

    /**
     * 定位 table directory。
     *
     * 普通 sfnt 的记录区从 12 字节头之后开始；`ttcf` 的每个字体目录各带一份
     * 完整 sfnt 头，首字体目录偏移记在集合头的第 12..15 字节。两种情况统一
     * 归成「记录区起点 + 条数」，后续逻辑零分支。
     */
    private fun tableDirectory(bytes: ByteArray, format: SfntFormat): Directory? {
        val directoryOffset = if (format == SfntFormat.COLLECTION) {
            if (bytes.size < SFNT_HEADER + 4) return null
            if (u32(bytes, 8) < 1L) return null // numFonts
            u32(bytes, 12).toInt() // 第一个字体的目录偏移
        } else {
            0
        }
        if (directoryOffset < 0) return null
        if (directoryOffset.toLong() + SFNT_HEADER > bytes.size) return null

        val count = u16(bytes, directoryOffset + 4)
        if (count <= 0) return null
        val recordsStart = directoryOffset + SFNT_HEADER
        // 表目录必须完整：截断的文件一律判死（不能让后续读取踩到数组越界）
        if (recordsStart.toLong() + count.toLong() * TABLE_RECORD > bytes.size) return null
        return Directory(recordsStart, count)
    }

    /**
     * 在目录里找指定 tag 的表。
     *
     * @return `offset to length`；表缺失或偏移越界返回 null
     */
    private fun findTable(bytes: ByteArray, directory: Directory, tag: Long): Pair<Long, Long>? {
        for (index in 0 until directory.count) {
            val record = directory.recordsStart + index * TABLE_RECORD
            if (u32(bytes, record) != tag) continue
            val offset = u32(bytes, record + 8)
            val length = u32(bytes, record + 12)
            if (offset < 0L || length < NAME_HEADER) return null
            if (offset + length > bytes.size) return null // 声明长度越界 → 文件不可信
            return offset to length
        }
        return null
    }

    /**
     * 读 name 表并按打分挑出 family / full。
     *
     * 单条记录坏掉只跳过该条（用户可能从聚合字体里拿到残缺数据），
     * 全部记录都不可用时才整体返回 null。
     */
    private fun readNames(bytes: ByteArray, offset: Int, length: Long): TtfNames? {
        val end = (offset.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt()
        if (offset < 0 || offset + NAME_HEADER > end) return null

        val count = u16(bytes, offset + 2)
        if (count <= 0) return null
        // 记录区越界 → 结构不可信
        if (offset.toLong() + NAME_HEADER + count.toLong() * NAME_RECORD > end) return null

        val stringBase = offset + u16(bytes, offset + 4)
        var family: Scored? = null
        var full: Scored? = null

        for (index in 0 until count) {
            val record = offset + NAME_HEADER + index * NAME_RECORD
            val platformId = u16(bytes, record)
            val encodingId = u16(bytes, record + 2)
            val nameId = u16(bytes, record + 6)
            if (nameId != NAME_ID_FAMILY && nameId != NAME_ID_FULL) continue
            val score = score(platformId, encodingId, nameId) ?: continue

            val textLength = u16(bytes, record + 8)
            if (textLength <= 0) continue
            val textOffset = stringBase + u16(bytes, record + 10)
            if (textOffset < 0 || textOffset.toLong() + textLength > end) continue

            val text = decode(bytes, textOffset, textLength, platformId)?.clean() ?: continue
            if (text.isEmpty()) continue
            val candidate = Scored(text, score)
            if (nameId == NAME_ID_FAMILY) {
                if (family == null || candidate.score > family.score) family = candidate
            } else {
                if (full == null || candidate.score > full.score) full = candidate
            }
        }

        if (family == null && full == null) return null
        return TtfNames(familyName = family?.text, fullName = full?.text)
    }

    /**
     * 平台 / 编码 / nameID 的综合分：平台分 × 10 + nameID 分。
     *
     * 不认识的平台返回 null（跳过该记录），而不是猜编码——猜错会得到乱码名字，
     * 比没有名字更糟（乱码会被直接写进设置页）。
     */
    private fun score(platformId: Int, encodingId: Int, nameId: Int): Int? {
        val platformScore = when {
            platformId == 3 && encodingId == 1 -> 4 // Windows Unicode BMP（UTF-16BE）
            platformId == 3 && encodingId == 10 -> 4 // Windows UCS-4（UTF-16BE）
            platformId == 0 -> 3 // Unicode platform（UTF-16BE）
            platformId == 3 -> 2 // Windows 其他编码（仍按 UTF-16BE 读）
            platformId == 1 && encodingId == 0 -> 1 // Macintosh Roman（单字节）
            else -> return null
        }
        return platformScore * 10 + if (nameId == NAME_ID_FULL) 2 else 1
    }

    /** 按平台解码：Mac (1,*) 为单字节 Latin-1，其余按 UTF-16BE。 */
    private fun decode(bytes: ByteArray, offset: Int, length: Int, platformId: Int): String? = try {
        if (platformId == 1) {
            String(bytes, offset, length, Charsets.ISO_8859_1)
        } else {
            // 奇数长度尾部字节会被丢弃（某些字体确实这么写），不视为错误
            String(bytes, offset, length, Charsets.UTF_16BE)
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /** 去掉 NUL 填充与首尾空白（name 表字符串常见 `\u0000` 填充）。 */
    private fun String.clean(): String = trim { it == '\u0000' || it.isWhitespace() }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return -1L
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }
}
