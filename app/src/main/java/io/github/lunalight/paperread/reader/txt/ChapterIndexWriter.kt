package io.github.lunalight.paperread.reader.txt

import java.io.OutputStream

/**
 * 双偏移索引条目：写入时同步产生。
 */
data class ChapterIndexEntry(
    val index: Int,
    val title: String,
    val charStart: Long,
    val charEnd: Long,
    val byteStart: Long,
    val byteEnd: Long,
    val charCount: Long,
    val cumulativeChars: Long,
)

/**
 * 双偏移索引写入器：把章节文本顺序写入 UTF-8 流，
 * 同步记录每章的字符偏移（进度计算）与字节偏移（文件读取）。
 *
 * 纯 JVM，可单测（round-trip 验证 byte ↔ char 映射一致）。
 */
class ChapterIndexWriter(private val out: OutputStream) {

    var totalBytes: Long = 0
        private set

    private var cumulativeChars = 0L

    /**
     * 按 refs 顺序写入 text。
     * refs 区间必须连续且覆盖 [0, text.length]，否则抛 IllegalArgumentException。
     */
    fun write(text: String, refs: List<ChapterRef>): List<ChapterIndexEntry> {
        require(
            refs.isEmpty() || (
                refs.first().charStart == 0 &&
                    refs.last().charEnd == text.length &&
                    refs.zipWithNext().all { (a, b) -> a.charEnd == b.charStart }
                ),
        ) { "章节区间必须连续且覆盖全文" }

        val entries = ArrayList<ChapterIndexEntry>(refs.size)
        for (ref in refs) {
            val chunk = text.substring(ref.charStart, ref.charEnd)
            val bytes = chunk.toByteArray(Charsets.UTF_8)
            val byteStart = totalBytes
            out.write(bytes)
            totalBytes += bytes.size
            val charCount = (ref.charEnd - ref.charStart).toLong()
            entries.add(
                ChapterIndexEntry(
                    index = ref.index,
                    title = ref.title,
                    charStart = ref.charStart.toLong(),
                    charEnd = ref.charEnd.toLong(),
                    byteStart = byteStart,
                    byteEnd = totalBytes,
                    charCount = charCount,
                    cumulativeChars = cumulativeChars,
                ),
            )
            cumulativeChars += charCount
        }
        out.flush()
        return entries
    }
}
