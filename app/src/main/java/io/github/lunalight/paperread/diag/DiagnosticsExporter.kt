package io.github.lunalight.paperread.diag

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 诊断包摘要（AC-19.2 的「先展示再导出」就是展示这份数据）。
 *
 * @param appVersion 应用版本名
 * @param versionCode 应用版本号
 * @param device 设备型号 + API
 * @param crashCount 现有崩溃日志份数（环形上限 10）
 * @param importErrorCount 现有导入失败条数（环形上限 5）
 * @param recentImportErrors 最近导入失败（最新在前）
 * @param createdAt 生成本摘要的时间
 */
data class DiagnosticsSummary(
    val appVersion: String,
    val versionCode: Int,
    val device: String,
    val crashCount: Int,
    val importErrorCount: Int,
    val recentImportErrors: List<ImportErrorEntry>,
    val createdAt: Long,
) {

    /** `summary.txt` 的正文（纯文本，用户拿自己的文本编辑器也能读）。 */
    fun render(): String = buildString {
        appendLine("纸阅诊断摘要 / PaperRead diagnostics")
        appendLine("生成时间: ${formatTime(createdAt)}")
        appendLine("应用版本: $appVersion ($versionCode)")
        appendLine("设备: $device")
        appendLine("崩溃记录: $crashCount 条（${DiagnosticsExporter.CRASH_DIR}*.log）")
        appendLine("导入失败: $importErrorCount 条（${DiagnosticsExporter.ENTRY_IMPORT_ERRORS}）")
        if (recentImportErrors.isNotEmpty()) {
            appendLine("最近导入失败（最新在前）:")
            recentImportErrors.forEach { appendLine("  - ${it.renderLine()}") }
        }
        appendLine()
        appendLine(DATA_BOUNDARY_NOTE)
    }

    private fun formatTime(value: Long): String =
        runCatching { Instant.ofEpochMilli(value).toString() }.getOrDefault("-$value")

    private companion object {
        /** AC-19.5 的数据边界声明：写进包里，收包的人一眼就知道不该期待什么。 */
        const val DATA_BOUNDARY_NOTE =
            "数据边界：本诊断包只含应用版本、设备型号、错误堆栈与导入失败摘要；" +
                "不含书籍正文、书源、书签内容、书名或任何个人信息。"
    }
}

/**
 * 诊断包导出（T-021 / US-19）。
 *
 * ## 条目白名单（AC-19.5：无正文）
 *
 * ```
 * summary.txt            应用版本 / 设备 / API / 崩溃条数 / 最近导入失败
 * crashes/<name>.log     崩溃日志（仅 diagnostics 目录下的 crash-*.log）
 * import-errors.json     最近 5 条导入失败（stage / cause / userMessage）
 * ```
 *
 * 除此之外**一个字节都不写**。这条不是靠「记得别加」，而是靠
 * [zipEntries] 只遍历这两个数据源实现：书籍正文在 `BookTextStore`
 * （filesDir/books/ 之下）、书签在 Room，两者都不在本类的可达范围内。
 *
 * ## 脱敏（AC-19.5「路径中的书名」）
 *
 * 崩溃堆栈里的绝对路径可能带出 `filesDir` 结构。导出时统一过
 * [DiagnosticsRedactor]：私有目录前缀 → `<files>`，任何 `books/<name>`
 * 段落 → `books/<book>`，UUID → `<id>`。即使某天日志里真的混进了书名，
 * 也会在打包这一刻被抹掉。
 *
 * ## 取消与失败
 *
 * [export] 只写调用方给的临时文件；异常 / 取消时删除临时文件（不留半成品）。
 * SAF 目标文档的清理由调用方负责（见 `DiagnosticsExportCoordinator`，
 * 与备份导出的 `targetCreatedByThisExport` 同口径）。
 */
class DiagnosticsExporter(
    private val crashLogs: CrashLogStore,
    private val importErrors: ImportErrorLog,
    private val device: DeviceInfo,
    private val appVersion: String,
    private val versionCode: Int,
    /** 应用私有目录（脱敏用）；null = 不做前缀替换（测试可省略）。 */
    private val filesDir: File? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 导出前的摘要（AC-19.2）。只读文件系统，不落盘。 */
    fun summary(): DiagnosticsSummary {
        val errors = importErrors.entries()
        return DiagnosticsSummary(
            appVersion = appVersion,
            versionCode = versionCode,
            device = device.summary,
            crashCount = crashLogs.logFiles().size,
            importErrorCount = errors.size,
            recentImportErrors = errors,
            createdAt = clock(),
        )
    }

    /**
     * 生成诊断包到 [target]（调用方给临时文件路径）。
     *
     * @return 实际写入的摘要（调用方可用于提示 / 日志）
     */
    suspend fun export(target: File): DiagnosticsSummary = withContext(Dispatchers.IO) {
        val summary = summary()
        target.parentFile?.mkdirs()
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
                writeText(zip, ENTRY_SUMMARY, summary.render())
                zipEntries().forEach { (entryName, content) -> writeText(zip, entryName, content) }
            }
        } catch (e: Throwable) {
            // 半成品删除后再抛：调用方只处理「失败」，不必记得清理
            target.delete()
            throw e
        }
        summary
    }

    // ---- 内部 ----

    /**
     * 白名单条目的 (包内名 → 内容)。
     *
     * 崩溃日志在这里逐份读入并脱敏；读取失败的某一份直接跳过（其余照常导出）——
     * 一份坏日志不该让整个诊断包导不出来。
     */
    private fun zipEntries(): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        crashLogs.logFiles().forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            entries += "$CRASH_DIR${file.name}" to redact(text)
        }
        // 有记录才带这个条目：空文件对排障没价值，反而让人以为导出漏了东西
        if (importErrors.entries().isNotEmpty()) {
            entries += ENTRY_IMPORT_ERRORS to importErrors.exportJson()
        }
        return entries
    }

    private fun redact(text: String): String =
        DiagnosticsRedactor.redact(text, filesDir?.absolutePath)

    private fun writeText(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    companion object {

        const val ENTRY_SUMMARY = "summary.txt"

        const val ENTRY_IMPORT_ERRORS = ImportErrorLog.FILE_NAME

        /** 崩溃日志在包内的目录（带结尾斜杠）。 */
        const val CRASH_DIR = "crashes/"

        /**
         * 包内条目白名单的**声明式**副本（导出实现只写这些名字）。
         *
         * 保留成常量而不是从实现里推导：测试用它断言「包内没有任何白名单外的
         * 条目」，将来有人新增条目时，这条断言会先红一次，逼着更新文档与测试。
         */
        val STATIC_ENTRIES: Set<String> = setOf(ENTRY_SUMMARY, ENTRY_IMPORT_ERRORS)
    }
}

/**
 * 诊断文本的脱敏规则（AC-19.5）。
 *
 * 三条替换，全部是「保留结构、抹掉身份」：
 * 1. 应用私有目录前缀 → `<files>`；
 * 2. `/books/<任意段>` → `/books/<book>`（书名 / 书籍目录名一律不落地）；
 * 3. UUID → `<id>`（书籍 dirName 是 UUID，属于可关联标识）。
 *
 * 为什么不直接删掉整行含路径的内容：堆栈里的路径是排障的主要线索
 * （哪个模块、哪一步），整行删掉等于把诊断价值一起删了。
 */
internal object DiagnosticsRedactor {

    private val BOOK_DIR = Regex("""/books/[^/\s"']+""")
    private val UUID = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
    )

    fun redact(text: String, filesDir: String?): String {
        var result = text
        if (!filesDir.isNullOrBlank()) {
            result = result.replace(filesDir, PLACEHOLDER_FILES)
        }
        result = BOOK_DIR.replace(result, "/books/$PLACEHOLDER_BOOK")
        result = UUID.replace(result, PLACEHOLDER_ID)
        return result
    }

    private const val PLACEHOLDER_FILES = "<files>"
    private const val PLACEHOLDER_BOOK = "<book>"
    private const val PLACEHOLDER_ID = "<id>"
}
