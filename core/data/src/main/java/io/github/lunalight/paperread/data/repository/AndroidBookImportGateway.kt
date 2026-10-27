package io.github.lunalight.paperread.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.lunalight.paperread.domain.repository.BookRepository
import java.io.File

/**
 * [BookImportGateway] 的 Android 实现（T-015 / R-13）。
 *
 * 逻辑自 `transfer/TransferMigrationController` 的匿名 `migrationGateway`
 * **原样抽出**（含每条 outcome 的话术），迁移侧改为委托本类：
 * ```
 * FileProvider → BookImporter.prepare → (可选) 元数据提示 → confirm → Repository.getBook
 * ```
 * 抽出的动机是 AC-14.4 / AC-9.5 的「共用同一条导入流水线」需要**代码层面**
 * 的唯一实现：迁移与备份各自内联一份 `when (prepare(...))`，迟早会分叉。
 *
 * `hints` 为 null 时行为与抽取前逐字节一致（迁移路径就传 null）。
 */
class AndroidBookImportGateway(
    private val context: Context,
    private val bookImporter: BookImporter,
    private val bookRepository: BookRepository,
) : BookImportGateway {

    override suspend fun importFromUri(uri: Uri, hints: ImportHints?): ImportOutcome =
        when (val result = bookImporter.prepare(uri)) {
            is PrepareResult.Success -> {
                // 元数据提示只覆盖书名 / 作者：章节与编码来自真实解析，不可伪造
                val pending = if (hints == null) {
                    result.pending
                } else {
                    result.pending.copy(
                        title = hints.title.ifBlank { result.pending.title },
                        author = hints.author.ifBlank { result.pending.author },
                    )
                }
                val bookId = bookImporter.confirm(pending)
                val book = bookRepository.getBook(bookId)
                if (book != null) {
                    ImportOutcome.Imported(book)
                } else {
                    ImportOutcome.Skipped("入库成功但读取失败")
                }
            }

            is PrepareResult.Duplicate -> ImportOutcome.Skipped("内容已存在于书架")

            is PrepareResult.Unsupported -> ImportOutcome.Skipped(result.error.userMessage)

            is PrepareResult.Failure -> ImportOutcome.Skipped(result.error.userMessage)
        }

    override suspend fun importFromFile(file: File, hints: ImportHints?): ImportOutcome =
        importFromUri(
            uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            ),
            hints = hints,
        )
}
