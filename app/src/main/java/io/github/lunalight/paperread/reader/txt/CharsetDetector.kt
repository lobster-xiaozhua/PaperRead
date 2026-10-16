package io.github.lunalight.paperread.reader.txt

import java.nio.ByteBuffer
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

    private fun decodesStrictly(sample: ByteArray, charset: Charset): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(sample))
        true
    } catch (e: java.nio.charset.CharacterCodingException) {
        false
    }
}
