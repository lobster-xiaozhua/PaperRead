package io.github.lunalight.paperread.diag

import io.github.lunalight.paperread.data.repository.ImportError
import io.github.lunalight.paperread.data.repository.ImportErrorRecorder
import java.io.File
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 一条导入失败记录（诊断包 `import-errors.json` 的元素）。
 *
 * 字段直接复刻 [ImportError] 的三要素（stage / cause / userMessage）：
 * - [stage]：流水线阶段（定位卡在哪一步）；
 * - [cause]：底层原因（异常信息 / 触发值）；
 * - [userMessage]：当时给用户看的话术（复现用户视角）。
 *
 * 刻意**不**记录书名 / 文件路径：`ImportError` 本身就不携带这些，
 * 这一层也不额外补充（AC-19.5 的数据边界从模型层面就守住）。
 */
@Serializable
data class ImportErrorEntry(
    val timestamp: Long = 0L,
    val stage: String = "",
    val cause: String? = null,
    val userMessage: String = "",
) {
    /** 摘要里的一行：`2026-05-01T10:00:00Z [PARSING] 文件解析失败，请重试`。 */
    fun renderLine(): String {
        val time = runCatching { Instant.ofEpochMilli(timestamp).toString() }.getOrDefault("-$timestamp")
        val detail = cause?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        return "$time [$stage] $userMessage$detail"
    }
}

/**
 * 最近导入失败的环形记录（T-021 · AC-19.4：**5 条**）。
 *
 * 落盘单文件 `filesDir/diagnostics/import-errors.json`（JSON 数组，最新在前）。
 * 为什么用 JSON 而不是每条形一个文件：5 条的上限下，单文件更容易保持
 * 「要么整体可读、要么整体丢弃」的原子性；写盘用临时文件 + rename 达成这一点，
 * 进程在写一半被杀也不会留下半个 JSON（解析失败时 [entries] 返回空列表）。
 *
 * ## 实现 [ImportErrorRecorder]
 *
 * 这是 `:core:data` 与 `:app` 之间唯一的耦合点：数据层定义接口、produces
 * [ImportError]；本类实现之。`record` **必须不抛**——它跑在导入失败的路径上，
 * 再抛一个异常会让用户看到「导入失败」之外的第二层失败。
 *
 * 线程：导入在 IO 线程、导出在主线程旁的协程，故 [record] / [entries] 均加锁同步；
 * 数据量是 5 条，锁竞争可忽略。
 */
class ImportErrorLog(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ImportErrorRecorder {

    private val lock = Any()

    /** 记录一条失败（最新在前，超出上限丢最旧的）。 */
    override fun record(error: ImportError) {
        synchronized(lock) {
            runCatching {
                val entry = ImportErrorEntry(
                    timestamp = clock(),
                    stage = error.stage.name,
                    cause = error.cause?.take(MAX_CAUSE_CHARS),
                    userMessage = error.userMessage,
                )
                write((listOf(entry) + readUnlocked()).take(maxEntries))
            }
        }
    }

    /** 当前记录（最新在前）；文件缺失 / 损坏时返回空列表。 */
    fun entries(): List<ImportErrorEntry> = synchronized(lock) { readUnlocked() }

    /**
     * 诊断包里的 `import-errors.json` 正文。
     *
     * 由本类负责序列化（而不是让导出器手拼 JSON）：转义规则只在一个地方，
     * 不会出现「日志写入用序列化、打包用手拼」两套编码。
     */
    fun exportJson(): String = synchronized(lock) {
        runCatching { json.encodeToString(serializer, readUnlocked()) }.getOrDefault("[]")
    }

    /** 清空（诊断导出后不自动清——用户可能想连续导出两次）。 */
    fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
        }
    }

    // ---- 内部 ----

    private fun readUnlocked(): List<ImportErrorEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) emptyList() else json.decodeFromString(serializer, text)
        }.getOrDefault(emptyList())
    }

    private fun write(entries: List<ImportErrorEntry>) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        val temp = File(dir, "${file.name}.tmp")
        temp.writeText(json.encodeToString(serializer, entries))
        // 先写临时文件再 rename：崩在写一半时原文件仍完好
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {

        /** 保留条数（AC-19.4：环形 5 条）。 */
        const val DEFAULT_MAX_ENTRIES = 5

        /** 诊断目录下的文件名。 */
        const val FILE_NAME = "import-errors.json"

        /** cause 截断：异常信息可能很长（含整段 HTML / XML），保留开头即可定位。 */
        private const val MAX_CAUSE_CHARS = 500

        private val serializer = ListSerializer(ImportErrorEntry.serializer())

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
