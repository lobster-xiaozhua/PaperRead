package io.github.lunalight.paperread.speech

/**
 * 朗读单元：一段可直接交给系统 TTS 的文本，以及它在正文中的段下标
 * （高亮、定位、进度都以正文段为准，与 [io.github.lunalight.paperread.reader.ReaderProgress]
 * 的段落口径一致）。
 *
 * @param utteranceId 稳定标识，格式 `p{paragraphIndex}-{part}`；part 为长段软切分序号（0 起）。
 *   同时用作 TTS 的 utteranceId，用于把合成回调映射回正文段。
 */
data class SpeechUnit(
    val utteranceId: String,
    val text: String,
    val paragraphIndex: Int,
)

/**
 * 朗读文本准备（纯 Kotlin，零 Android 依赖，可 JVM 单测）。
 *
 * 两件事：
 * 1. 把阅读器的渲染段落切成 TTS 可消化的单元——系统 TextToSpeech 单次
 *    speak 输入有约 4000 字符上限，超出直接抛错/静默失败；
 * 2. 维护 utteranceId ↔ 正文段下标的双向映射——TTS 回调只给 utteranceId，
 *    高亮跟随要靠它还原段下标。
 */
object SpeechText {

    /**
     * 朗读单元字符上限。
     *
     * 系统 TTS 上限 4000（`getMaxSpeechInputLength`），留 ~12% 余量：
     * 软切分断点落在句末标点之后，最坏情况会略微越过 [MAX_UNIT_CHARS]。
     */
    const val MAX_UNIT_CHARS = 3500

    /** 软切分优先断点（句末/收尾标点），与 ReaderProgress.SOFT_BREAK_AFTER 同口径。 */
    private val SENTENCE_BREAK_AFTER = charArrayOf('。', '！', '？', '；', '!', '?', ';', '”', '」', '』')

    /** 软切分断点回退下限比例：至少切出 60% 才回退找句末标点。 */
    private const val SPLIT_FLOOR_RATIO = 0.6f

    /**
     * 构建朗读单元表。
     *
     * 空白段（trim 后为空）跳过——它们对听者无意义，还会产生空 utterance 回调；
     * 超长段按 [SENTENCE_BREAK_AFTER] 软切分为多个单元（同一 paragraphIndex、不同 part）。
     */
    fun buildUnits(
        paragraphs: List<String>,
        maxUnitChars: Int = MAX_UNIT_CHARS,
    ): List<SpeechUnit> {
        val units = ArrayList<SpeechUnit>()
        paragraphs.forEachIndexed { index, paragraph ->
            val text = paragraph.trim()
            if (text.isEmpty()) return@forEachIndexed
            if (text.length <= maxUnitChars) {
                units.add(SpeechUnit(utteranceId(index, 0), text, index))
            } else {
                var offset = 0
                var part = 0
                while (offset < text.length) {
                    val end = softSplitEnd(text, offset, maxUnitChars)
                    units.add(SpeechUnit(utteranceId(index, part), text.substring(offset, end), index))
                    offset = end
                    part++
                }
            }
        }
        return units
    }

    /**
     * 第一个「段下标 >= [paragraphIndex]」的朗读单元下标；越界/无内容时返回 -1。
     *
     * 从阅读位置开始朗读用：用户停在第 N 段，而第 N 段可能是被跳过的空白段，
     * 此时从其后第一个可读段开始。
     */
    fun firstUnitAtOrAfter(units: List<SpeechUnit>, paragraphIndex: Int): Int {
        if (units.isEmpty()) return -1
        val target = paragraphIndex.coerceAtLeast(0)
        val index = units.indexOfFirst { it.paragraphIndex >= target }
        return if (index >= 0) index else units.lastIndex
    }

    /** utteranceId 反解析正文段下标；格式非法返回 -1。 */
    fun paragraphOf(utteranceId: String): Int {
        val body = utteranceId
        if (!body.startsWith("p")) return -1
        val separator = body.indexOf('-')
        if (separator < 0) return -1
        return body.substring(1, separator).toIntOrNull() ?: -1
    }

    /** 生成第 [paragraphIndex] 段第 [part] 个单元的 utteranceId。 */
    fun utteranceId(paragraphIndex: Int, part: Int): String = "p$paragraphIndex-$part"

    /**
     * 求 [from] 起一个软切分单元的结束位置（exclusive）：
     * 以 `from + maxUnitChars` 为上界，在 [下限, 上界] 内自后向前找句末标点并断在其后；
     * 找不到则硬切在上界。返回值保证 > from。
     */
    private fun softSplitEnd(text: String, from: Int, maxUnitChars: Int): Int {
        val limit = minOf(text.length, from + maxUnitChars)
        if (limit >= text.length) return text.length
        val floor = from + (maxUnitChars * SPLIT_FLOOR_RATIO).toInt()
        var i = limit - 1
        while (i >= floor) {
            if (text[i] in SENTENCE_BREAK_AFTER) return i + 1
            i--
        }
        return limit
    }
}
