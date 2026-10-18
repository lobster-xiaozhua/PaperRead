package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.domain.model.Book
import java.io.File

/**
 * 单本导入结局（AC-10.3：失败记入清单，不中断整体迁移）。
 */
sealed interface ImportOutcome {
    /** 导入成功（已入库）。 */
    data class Imported(val book: Book) : ImportOutcome

    /** 未能导入（携带可展示原因）。 */
    data class Skipped(val reason: String) : ImportOutcome
}

/** 迁移失败清单条目。 */
data class FailedEntry(val title: String, val reason: String)

/**
 * 迁移结果报告（AC-10.3：结束汇报成功 / 失败清单）。
 *
 * @param imported 新导入本数
 * @param duplicates 内容已存在（SHA-256 命中）未重复导入的本数
 * @param progressWritten 实际回写进度 / 书签的本数（AC-10.4 决策后）
 */
data class MigrationReport(
    val imported: Int = 0,
    val duplicates: Int = 0,
    val progressWritten: Int = 0,
    val failed: List<FailedEntry> = emptyList(),
) {
    val total: Int get() = imported + duplicates + failed.size
}

/**
 * 迁移导入网关（Android 侧实现：FileProvider → BookImporter → BookRepository）。
 *
 * 抽出接口使编排逻辑（[MigrationProcessor]）保持纯 JVM 可测。
 */
interface MigrationImportGateway {

    /** 从源文件走完整导入流水线（prepare → confirm）。 */
    suspend fun importFromSource(sourceFile: File, snapshot: BookStateSnapshot): ImportOutcome

    /** 按 SHA-256 查已存在书籍。 */
    suspend fun findBySha256(sha256: String): Book?

    /** 回写阅读进度（调用方已按 AC-10.4 决策）。 */
    suspend fun writeProgress(book: Book, snapshot: BookStateSnapshot)

    /** 替换式回写书签（与进度同一决策单元）。 */
    suspend fun writeBookmarks(book: Book, snapshot: BookStateSnapshot)
}

/**
 * 迁移包接收编排（AC-10.2 / AC-10.3 / AC-10.4，纯 JVM）。
 *
 * 逐本处理，任一本失败仅记清单继续；导入（或命中已存在）后按
 * 「最后阅读时间较新者胜」回写进度与书签。
 */
class MigrationProcessor(private val gateway: MigrationImportGateway) {

    /**
     * @param onProgress (已完成本数, 总本数, 当前书名)
     */
    suspend fun process(
        bundle: UnpackedBundle,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): MigrationReport {
        val books = bundle.manifest.books
        var imported = 0
        var duplicates = 0
        var progressWritten = 0
        val failed = mutableListOf<FailedEntry>()

        books.forEachIndexed { index, snapshot ->
            onProgress(index, books.size, snapshot.title)
            val target = resolveTarget(snapshot, bundle.sources)
            when (target) {
                is TargetResult.Failed -> failed += FailedEntry(snapshot.title, target.reason)

                is TargetResult.Found -> {
                    val book = target.book
                    if (target.newlyImported) imported++ else duplicates++
                    if (WriteBackDecider.shouldWriteBack(book.lastReadAt, snapshot.lastReadAt)) {
                        gateway.writeProgress(book, snapshot)
                        gateway.writeBookmarks(book, snapshot)
                        progressWritten++
                    }
                }
            }
            onProgress(index + 1, books.size, snapshot.title)
        }
        return MigrationReport(
            imported = imported,
            duplicates = duplicates,
            progressWritten = progressWritten,
            failed = failed,
        )
    }

    private sealed interface TargetResult {
        data class Found(val book: Book, val newlyImported: Boolean) : TargetResult
        data class Failed(val reason: String) : TargetResult
    }

    private suspend fun resolveTarget(
        snapshot: BookStateSnapshot,
        sources: Map<String, File>,
    ): TargetResult {
        val existing = gateway.findBySha256(snapshot.sha256)
        if (existing != null) return TargetResult.Found(existing, newlyImported = false)

        val source = sources[snapshot.sha256]
            ?: return TargetResult.Failed("源文件缺失（内容哈希不匹配或打包不完整）")
        return when (val outcome = gateway.importFromSource(source, snapshot)) {
            is ImportOutcome.Imported -> TargetResult.Found(outcome.book, newlyImported = true)
            is ImportOutcome.Skipped -> TargetResult.Failed(outcome.reason)
        }
    }
}
