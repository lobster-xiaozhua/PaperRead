package io.github.lunalight.paperread.reader.txt

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 编码探测结果。[bomLength] 为需跳过的 BOM 字节数。 */
data class CharsetDetection(val charset: Charset, val displayName: String, val bomLength: Int)

/**
 * 编码探测：BOM（UTF-8 / UTF-16 LE / UTF-16 BE）→ 严格 UTF-8 试解码（REPORT）→ GB18030 回退。
 *
 * 纯 JVM，无 Android 依赖。已知限制：无 BOM 的 UTF-16 文本会被误判为 UTF-8
 * （中文小说场景罕见），用户可在导入结果页手动切换编码。
 */
object CharsetDetector {

    const val SAMPLE_SIZE = 64 * 1024

    val SUPPORTED_ENCODINGS = listOf("UTF-8", "GB18030", "UTF-16LE", "UTF-16BE")

    fun detect(bytes: ByteArray): CharsetDetection {
        if (hasBom(bytes, 0xEF, 0xBB, 0xBF)) {
            return CharsetDetection(Charsets.UTF_8, "UTF-8", 3)
        }
        if (hasBom(bytes, 0xFF, 0xFE)) {
            return CharsetDetection(utf16("LE"), "UTF-16LE", 2)
        }
        if (hasBom(bytes, 0xFE, 0xFF)) {
            return CharsetDetection(utf16("BE"), "UTF-16BE", 2)
        }
        val sample = if (bytes.size > SAMPLE_SIZE) bytes.copyOf(SAMPLE_SIZE) else bytes
        if (decodesStrictly(sample, Charsets.UTF_8)) {
            return CharsetDetection(Charsets.UTF_8, "UTF-8", 0)
        }
        return CharsetDetection(Charset.forName("GB18030"), "GB18030", 0)
    }

    /** 仅探测 BOM 长度（0/2/3），不判断编码——供切换编码后重新解析时跳过 BOM。 */
    fun bomLengthOf(bytes: ByteArray): Int = when {
        hasBom(bytes, 0xEF, 0xBB, 0xBF) -> 3
        hasBom(bytes, 0xFF, 0xFE) -> 2
        hasBom(bytes, 0xFE, 0xFF) -> 2
        else -> 0
    }

    fun byDisplayName(name: String): Charset? = when (name) {
        "UTF-8" -> Charsets.UTF_8
        "UTF-16LE" -> utf16("LE")
        "UTF-16BE" -> utf16("BE")
        "GB18030" -> Charset.forName("GB18030")
        else -> null
    }

    private fun utf16(endian: String): Charset = Charset.forName("UTF-16$endian")

    private fun hasBom(bytes: ByteArray, vararg bom: Int): Boolean {
        if (bytes.size < bom.size) return false
        return bom.indices.all { bytes[it] == bom[it].toByte() }
    }

    /**
     * 严格解码校验（REPORT 动作）：样本可解码返回 true。
     * 供 BOM 驱动的编码判定使用——BOM 声明了编码而内容无法按该编码解码，
     * 属于真实的编码失败；相对地，无 BOM 时回退 GB18030 是设计内的容错路径，
     * 不走本校验。样本外的数据不校验（调用方传前 64KB 样本即可）。
     */
    fun decodesStrictly(sample: ByteArray, charset: Charset): Boolean = try {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // 样本只是文件前缀：endOfInput=false 时，末尾被截断的多字节序列表现为
        // UNDERFLOW（待续解码）而非格式错误；若用 endOfInput=true，截断会被误报为
        // 格式错误，把合法 UTF-8 文件误判成 GB18030（64KB 边界约 2/3 概率落于字符中间）。
        // 注意：三参 decode 不抛异常，REPORT 动作的错误以 CoderResult 返回，
        // 必须显式检查，否则任何输入都会被判为合法。
        val result = decoder.decode(
            ByteBuffer.wrap(sample), CharBuffer.allocate(sample.size + 8), false
        )
        !result.isMalformed && !result.isUnmappable
    } catch (e: java.nio.charset.CharacterCodingException) {
        false
    }
}
