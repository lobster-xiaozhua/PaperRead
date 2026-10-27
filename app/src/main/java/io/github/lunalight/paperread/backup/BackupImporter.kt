package io.github.lunalight.paperread.backup

import io.github.lunalight.paperread.data.repository.BookImportGateway
import io.github.lunalight.paperread.data.repository.BookStateWriter
import io.github.lunalight.paperread.data.repository.ImportHints
import io.github.lunalight.paperread.data.repository.ImportOutcome
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.transfer.FailedEntry
import io.github.lunalight.paperread.transfer.MigrationReport
import io.github.lunalight.paperread.transfer.WriteBackDecider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 字体可用性（AC-14.9：备份不含字体文件，恢复时缺失的字体回退默认并提示）。
 *
 * 生产实现只需认内置字体（`serif` / `sans` / `monospace`）；自定义字体上线后
 * 由同一接口接入「已安装字体清单」，调用方（本类）不用改。
 */
fun interface FontAvailability {

    fun isAvailable(fontName: String): Boolean

    companion object {
        /** 内置字体（阅读器 Typography 永远可用）。 */
        val BUILT_IN_FONT_NAMES: Set<String> = setOf("serif", "sans", "monospace")

        /** 生产用：仅内置字体可用（自定义字体尚未实现，一律视为缺失）。 */
        val BuiltIn: FontAvailability = FontAvailability { name -> name in BUILT_IN_FONT_NAMES }
    }
}

/** 打开备份后的预览信息（AC-14.8：恢复前可预览）。 */
sealed interface BackupInspection {

    /** 可恢复：manifest + 书目（加密包在口令正确后才能拿到书名）+ 缺失字体。 */
    data class Ready(
        val manifest: BackupManifest,
        val bookTitles: List<String>,
        val fontFallbacks: List<String>,
        /** manifest 声明但包里不存在的条目数（包完整性自检）。 */
        val missingEntries: Int,
    ) : BackupInspection

    /** 加密备份，尚未提供口令。 */
    data class PasswordRequired(val manifest: BackupManifest) : BackupInspection

    /** 口令错误（AC-14.7：可重试）。 */
    data class WrongPassword(val manifest: BackupManifest) : BackupInspection

    /** 不是备份 / 损坏 / 版本过新（AC-14.3）。 */
    data class Invalid(
        val reason: BackupInvalidReason,
        val detail: String? = null,
    ) : BackupInspection
}

/**
 * 恢复结果报告（AC-14.5）。
 *
 * 核心计数**复用迁移的报告结构** [MigrationReport]（导入 / 重复 / 回写 / 失败清单），
 * 备份只多一项字体回退清单——两条流程对用户的表述必须一致，否则「迁移成功 2 本、
 * 恢复成功 2 本」会变成两套口径。
 */
data class BackupRestoreReport(
    val migration: MigrationReport,
    val fontFallbacks: List<String> = emptyList(),
) {
    val imported: Int get() = migration.imported
    val duplicates: Int get() = migration.duplicates
    val progressWritten: Int get() = migration.progressWritten
    val failed: List<FailedEntry> get() = migration.failed
    val total: Int get() = migration.total
}

/** 恢复操作结局。 */
sealed interface BackupRestoreResult {

    /** 完成（含「部分失败」：失败明细在报告里，AC-14.5）。 */
    data class Done(val report: BackupRestoreReport) : BackupRestoreResult

    /** 加密备份但未给口令。 */
    data object PasswordRequired : BackupRestoreResult

    /** 口令错误（AC-14.7：UI 停留对话框可重试）。 */
    data class WrongPassword(val manifest: BackupManifest) : BackupRestoreResult

    /** 包不可用（版本过新 / 损坏 / 不是备份）。 */
    data class Rejected(
        val reason: BackupInvalidReason,
        val detail: String? = null,
    ) : BackupRestoreResult
}

/**
 * 本地备份恢复（T-016 / US-14，R-13）。
 *
 * ## 恢复语义（逐条对应 AC）
 * - **AC-14.3**：manifest 版本高于 [BACKUP_VERSION] → 明确拒绝「备份版本过新」；
 * - **AC-14.4**：逐本走 [BookImportGateway]（`prepare → confirm` 全流水线，
 *   与传书 / 迁移同一条）；SHA-256 命中书架则跳过导入（计 duplicate）；
 * - **AC-14.5**：单本失败只记 [FailedEntry] 并继续，结束汇报成功 / 失败明细；
 * - **AC-14.6**：进度 / 书签回写前用 `WriteBackDecider` 裁决（较新者胜），
 *   与 AC-10.4 同一条规则、同一份实现；
 * - **AC-14.7**：口令错误由 [BackupCrypto] 的 verifier 检出 → [BackupRestoreResult.WrongPassword]；
 * - **AC-14.9**：字体缺失不回写、不报错，收集为 `fontFallbacks` 供 UI 提示
 *   （阅读器对未知字体名回退 serif，即「回退默认」的落点）。
 *
 * ## 完整性校验
 * 逐本比对 manifest 声明的 `sourceSize` / `sourceSha256`（解出时流式计算），
 * 任一不符 → 该书记失败，不污染书架；条目缺失同样记失败（不中断整体）。
 */
class BackupImporter(
    private val bookRepository: BookRepository,
    private val gateway: BookImportGateway,
    private val stateWriter: BookStateWriter,
    /** 恢复过程的临时落盘目录（调用方负责保证可写；本类用后自清）。 */
    private val workDir: File,
    private val fontAvailability: FontAvailability = FontAvailability.BuiltIn,
) {

    /**
     * 打开并预览（AC-14.8）：不写任何用户数据，不修改书架。
     *
     * @param password 加密备份的口令；明文备份忽略
     */
    suspend fun inspect(file: File, password: CharArray? = null): BackupInspection =
        withContext(Dispatchers.IO) {
            when (val open = BackupPackage.open(file, password)) {
                is BackupOpenResult.Invalid -> BackupInspection.Invalid(open.reason, open.detail)

                is BackupOpenResult.PasswordRequired ->
                    BackupInspection.PasswordRequired(open.manifest)

                is BackupOpenResult.WrongPassword ->
                    BackupInspection.WrongPassword(open.manifest)

                is BackupOpenResult.Opened -> open.pkg.use { pkg ->
                    val manifest = pkg.manifest
                    val titles = manifest.books.mapIndexed { index, entry ->
                        currentCoroutineContext().ensureActive()
                        pkg.readState(entry)?.title
                            ?: entry.title
                            ?: "书籍 ${index + 1}"
                    }
                    BackupInspection.Ready(
                        manifest = manifest,
                        bookTitles = titles,
                        fontFallbacks = missingFonts(manifest),
                        missingEntries = manifest.books.count { entry ->
                            !entry.sourceMissing && !pkg.hasSourceEntry(entry)
                        },
                    )
                }
            }
        }

    /**
     * 执行恢复。
     *
     * @param password 加密备份的口令（明文备份可传 null）
     * @param onProgress 逐本进度（IO 线程）
     * @param shouldContinue 取消探针（与协程取消双通道）
     */
    suspend fun restore(
        file: File,
        password: CharArray? = null,
        onProgress: (BackupProgress) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        when (val open = BackupPackage.open(file, password)) {
            is BackupOpenResult.Invalid ->
                BackupRestoreResult.Rejected(open.reason, open.detail)

            is BackupOpenResult.PasswordRequired -> BackupRestoreResult.PasswordRequired

            is BackupOpenResult.WrongPassword ->
                BackupRestoreResult.WrongPassword(open.manifest)

            is BackupOpenResult.Opened -> open.pkg.use { pkg ->
                restoreOpened(pkg, onProgress, shouldContinue)
            }
        }
    }

    // ---- 内部 ----

    private suspend fun restoreOpened(
        pkg: BackupPackage,
        onProgress: (BackupProgress) -> Unit,
        shouldContinue: () -> Boolean,
    ): BackupRestoreResult {
        val manifest = pkg.manifest
        val books = manifest.books
        val sessionDir = File(workDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        var imported = 0
        var duplicates = 0
        var progressWritten = 0
        val failed = mutableListOf<FailedEntry>()

        try {
            books.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                if (!shouldContinue()) throw CancellationException("已取消恢复")
                val fallbackTitle = entry.title ?: "书籍 ${index + 1}"
                onProgress(
                    BackupProgress(
                        stage = BackupStage.BOOKS,
                        done = index,
                        total = books.size,
                        fraction = if (books.isEmpty()) 0f else index.toFloat() / books.size,
                        currentTitle = fallbackTitle,
                    ),
                )

                var tempFile: File? = null
                try {
                    val state = pkg.readState(entry)
                    if (state == null) {
                        failed += FailedEntry(fallbackTitle, "备份条目缺失或损坏（阅读记录）")
                        return@forEachIndexed
                    }
                    val displayTitle = state.title.ifBlank { fallbackTitle }
                    if (entry.sourceMissing) {
                        failed += FailedEntry(displayTitle, "源文件缺失（备份时已丢失）")
                        return@forEachIndexed
                    }

                    tempFile = File(sessionDir, "$index.${safeExtension(entry.format)}")
                    val extracted = try {
                        pkg.extractSource(entry, tempFile)
                    } catch (e: BackupCryptoException) {
                        failed += FailedEntry(displayTitle, "源文件解密失败（备份数据损坏）")
                        return@forEachIndexed
                    }
                    if (extracted == null) {
                        failed += FailedEntry(displayTitle, "备份包缺少源文件条目")
                        return@forEachIndexed
                    }
                    if (extracted.sizeBytes != entry.sourceSize ||
                        !extracted.sha256.equals(entry.sourceSha256, ignoreCase = true)
                    ) {
                        failed += FailedEntry(displayTitle, "源文件校验失败（内容哈希不匹配）")
                        return@forEachIndexed
                    }

                    val existing = bookRepository.findBySha256(entry.sourceSha256)
                    val book = if (existing != null) {
                        duplicates++
                        existing
                    } else {
                        val hints = ImportHints(title = displayTitle, author = state.author)
                        when (val outcome = gateway.importFromFile(tempFile, hints)) {
                            is ImportOutcome.Imported -> {
                                imported++
                                outcome.book
                            }

                            is ImportOutcome.Skipped -> {
                                failed += FailedEntry(displayTitle, outcome.reason)
                                return@forEachIndexed
                            }
                        }
                    }

                    // AC-14.6：与迁移同一条裁决（旧进度不覆盖新进度）
                    if (WriteBackDecider.shouldWriteBack(book.lastReadAt, state.lastReadAt)) {
                        stateWriter.writeProgress(
                            bookId = book.id,
                            chapterIndex = state.lastChapterIndex,
                            paragraphIndex = state.lastParagraphIndex,
                            scrollOffset = state.lastScrollOffset,
                            charOffset = state.lastCharOffset,
                            progressPercent = state.progressPercent,
                            // F-01：恢复必须还原归档里的「上次阅读时间」，不能写 now——
                            // 写 now 会让下一次恢复（同一本书、更新的备份）被
                            // WriteBackDecider 判成旧进度而静默跳过回写。
                            readAt = state.lastReadAt,
                        )
                        stateWriter.writeBookmarks(
                            bookId = book.id,
                            bookmarks = state.bookmarks.map { mark ->
                                Bookmark(
                                    bookId = book.id,
                                    chapterIndex = mark.chapterIndex,
                                    paragraphIndex = mark.paragraphIndex,
                                    charOffset = mark.charOffset,
                                    excerpt = mark.excerpt,
                                    createdAt = mark.createdAt,
                                    note = mark.note,
                                    updatedAt = mark.updatedAt,
                                )
                            },
                        )
                        progressWritten++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // AC-14.5：单本异常不中断整体
                    failed += FailedEntry(fallbackTitle, e.message ?: "恢复失败")
                } finally {
                    tempFile?.delete()
                }
            }

            onProgress(
                BackupProgress(
                    stage = BackupStage.DONE,
                    done = books.size,
                    total = books.size,
                    fraction = 1f,
                ),
            )
            return BackupRestoreResult.Done(
                BackupRestoreReport(
                    migration = MigrationReport(
                        imported = imported,
                        duplicates = duplicates,
                        progressWritten = progressWritten,
                        failed = failed,
                    ),
                    fontFallbacks = missingFonts(manifest),
                ),
            )
        } finally {
            sessionDir.deleteRecursively()
        }
    }

    /**
     * 备份引用但本机不可用的字体（AC-14.9 的缺失清单）。
     *
     * 公开的理由：字体可用性只取决于 manifest（明文）与本机的 [FontAvailability]，
     * **不需要解密**。加密包在「口令未提供」阶段（[BackupInspection.PasswordRequired]）
     * 也能如实报缺失字体，预览就不必分两种情况给用户两套口径（P3-1）。
     */
    fun missingFonts(manifest: BackupManifest): List<String> =
        manifest.fontReferences
            .filterNot { fontAvailability.isAvailable(it.name) }
            .map { it.displayName }

    /** 临时文件扩展名：只保留字母数字（格式名来自 manifest，不信任任意字符）。 */
    private fun safeExtension(format: String): String =
        format.lowercase().filter { it.isLetterOrDigit() }.take(8).ifBlank { "bin" }
}
