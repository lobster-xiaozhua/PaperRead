package io.github.lunalight.paperread.transfer

import android.content.Context
import io.github.lunalight.paperread.data.repository.BookImportGateway
import io.github.lunalight.paperread.data.repository.BookStateWriter
import io.github.lunalight.paperread.data.repository.ImportOutcome
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 换机迁移流程（AC-10.1 ~ AC-10.4，T-012 从 `DefaultLanTransferManager` 拆出，
 * 行为逐行搬迁）：整库打包上行 / 接收包解包 → 逐本导入 → 进度与书签回写。
 *
 * 与接收端（传书）共用一条状态流 [migrationState] 的两侧语义：
 * - SEND：本机打包上传（[start]）；
 * - RECEIVE：对端上传迁移包（[onBundle] → [processIncomingBundle]）。
 *
 * 并发守卫（语义与拆分前一致）：发送进行中的迁移占用同一状态通道，此时到达的
 * 接收请求让位（`busy`），落盘进度也不再改写状态——否则会把 SEND 踩成 RECEIVE，
 * 既错乱 UI，又让 busy 拒绝永远不生效。
 *
 * 导入与回写复用既有能力（T-015 起为**共享实现**）：导入走
 * [BookImportGateway]（`prepare → confirm` 全流水线，与备份恢复同一入口，
 * AC-10.2 / AC-14.4），回写走 [BookStateWriter]；本类不再内联任何导入逻辑。
 */
internal class TransferMigrationController(
    private val context: Context,
    private val bookImportGateway: BookImportGateway,
    private val bookRepository: BookRepository,
    private val bookTextStore: BookTextStore,
    private val transferClient: TransferClient,
    private val scope: CoroutineScope,
    private val migrationState: MutableStateFlow<TransferMigrationState>,
) {

    private var migrationJob: Job? = null

    /** 阅读状态回写（迁移与备份共用，AC-10.4）。 */
    private val stateWriter = BookStateWriter(bookRepository)

    /**
     * 迁移导入网关（FileProvider → 导入流水线 → 仓储回写）。
     *
     * T-015 重构：导入一步委托共享的 [BookImportGateway]，回写一步委托
     * [BookStateWriter]——两条流程（迁移 / 备份）不再各持一份实现。
     * `hints` 传 null，保持抽取前「书名由显示名推导」的行为逐字节不变。
     */
    private val migrationGateway = object : MigrationImportGateway {

        override suspend fun importFromSource(
            sourceFile: File,
            snapshot: BookStateSnapshot,
        ): ImportOutcome = bookImportGateway.importFromFile(sourceFile)

        override suspend fun findBySha256(sha256: String): Book? =
            bookRepository.findBySha256(sha256)

        override suspend fun writeProgress(book: Book, snapshot: BookStateSnapshot) {
            stateWriter.writeProgress(
                bookId = book.id,
                chapterIndex = snapshot.lastChapterIndex,
                paragraphIndex = snapshot.lastParagraphIndex,
                scrollOffset = snapshot.lastScrollOffset,
                charOffset = snapshot.lastCharOffset,
                progressPercent = snapshot.progressPercent,
                // AC-10.4 语义不变：迁移落地即「本机此刻接受了这份进度」，时间戳
                // 走 now（不传 readAt，取默认 null）。F-01 只修备份恢复——迁移是
                // 一次性搬迁，没有「先恢复旧包、再恢复新包」这条时间戳比较路径。
            )
        }

        override suspend fun writeBookmarks(book: Book, snapshot: BookStateSnapshot) {
            stateWriter.writeBookmarks(
                bookId = book.id,
                bookmarks = snapshot.bookmarks.map { mark ->
                    Bookmark(
                        bookId = book.id,
                        chapterIndex = mark.chapterIndex,
                        paragraphIndex = mark.paragraphIndex,
                        charOffset = mark.charOffset,
                        excerpt = mark.excerpt,
                        createdAt = mark.createdAt,
                    )
                },
            )
        }
    }

    private val migrationProcessor = MigrationProcessor(migrationGateway)

    /** 本机是否正在**发送**迁移包（接收侧让位判据）。 */
    private fun sending(): Boolean =
        migrationState.value.active && migrationState.value.direction == MigrationDirection.SEND

    /**
     * 服务端 `POST /bundle` 的落盘进度。
     *
     * 发送中的迁移不接收进度反馈：progress 回调先于 `onBundle` 的 busy 拒绝触发，
     * 若无条件改写会把 SEND 状态踩成 RECEIVE（既错乱 UI，又让 busy 拒绝永远不生效）。
     */
    fun onReceiveProgress(fraction: Float) {
        if (sending()) return
        migrationState.value = TransferMigrationState(
            active = true,
            direction = MigrationDirection.RECEIVE,
            phaseLabel = "接收迁移包",
            fraction = fraction,
        )
    }

    /** 请求体接收中止（同上：发送进行中不接收中止反馈）。 */
    fun onReceiveAborted(reason: String) {
        if (sending()) return
        migrationState.value = TransferMigrationState(error = reason)
    }

    /**
     * 处理 `POST /bundle`：改名到位 + 起后台解包，立即返回。
     *
     * @return null（成功）；发送中返回 "busy"（对端收到 500）
     */
    suspend fun onBundle(bodyFile: File): String? {
        if (sending()) {
            bodyFile.delete()
            return "busy"
        }
        val tempDir = File(context.cacheDir, "transfer").apply { mkdirs() }
        val target = File(tempDir, INCOMING_BUNDLE_NAME)
        if (!bodyFile.renameTo(target)) {
            bodyFile.copyTo(target, overwrite = true)
            bodyFile.delete()
        }
        // 字节已完整落盘（服务端已鉴权 + 摘要核对）：解包与逐本导入转为后台，
        // HTTP 尽早应答（避免对端超时）
        migrationJob = scope.launch {
            processIncomingBundle(target)
        }
        return null
    }

    /**
     * 换机迁移（AC-10.1）：把整库（书籍源文件 + 进度 / 书签）打包为单一 zip
     * 发送至对端。已在迁移中时幂等忽略。
     */
    fun start(target: DiscoveredDevice) {
        if (migrationState.value.active) return
        migrationState.value = TransferMigrationState(
            active = true,
            direction = MigrationDirection.SEND,
            phaseLabel = "收集书库",
        )
        migrationJob = scope.launch {
            var bundleFile: File? = null
            try {
                val books = bookRepository.observeBooks().first()
                if (books.isEmpty()) {
                    migrationState.value = TransferMigrationState(error = "书架为空，没有可迁移的书籍")
                    return@launch
                }
                migrationState.value = migrationState.value.copy(total = books.size, phaseLabel = "打包书库")
                val snapshots = mutableListOf<BookStateSnapshot>()
                val sources = mutableMapOf<String, File>()
                books.forEachIndexed { index, book ->
                    migrationState.value = migrationState.value.copy(
                        index = index + 1,
                        currentTitle = book.title,
                        fraction = (index.toFloat() / books.size) * 0.2f,
                    )
                    val bookmarks = bookRepository.observeBookmarks(book.id).first()
                    snapshots += book.toSnapshot(bookmarks)
                    val source = bookTextStore.sourceFile(book)
                    if (source.isFile) sources[book.sha256] = source
                }
                val tempDir = File(context.cacheDir, "transfer").apply { mkdirs() }
                val zip = File(tempDir, OUTGOING_BUNDLE_NAME)
                MigrationBundleCodec.pack(MigrationManifest(books = snapshots), sources, zip)
                bundleFile = zip
                migrationState.value = migrationState.value.copy(
                    phaseLabel = "发送迁移包",
                    fraction = 0.2f,
                    currentTitle = "${books.size} 本书",
                )
                val result = transferClient.uploadBundle(
                    host = target.host,
                    port = target.httpPort,
                    file = zip,
                    displayName = OUTGOING_BUNDLE_NAME,
                ) { fraction ->
                    migrationState.value = migrationState.value.copy(fraction = 0.2f + fraction * 0.8f)
                }
                when (result) {
                    is UploadResult.Success -> migrationState.value = migrationState.value.copy(
                        active = false,
                        fraction = 1f,
                        phaseLabel = "已发送",
                    )

                    is UploadResult.Failure -> migrationState.value = migrationState.value.copy(
                        active = false,
                        error = if (result.unauthorized) {
                            result.message
                        } else {
                            "迁移包发送失败：${result.message}"
                        },
                    )
                }
            } catch (e: CancellationException) {
                migrationState.value = TransferMigrationState(error = "已取消迁移")
                throw e
            } catch (e: Exception) {
                migrationState.value = migrationState.value.copy(
                    active = false,
                    error = e.message ?: "迁移异常",
                )
            } finally {
                bundleFile?.delete()
            }
        }
    }

    /**
     * 取消进行中的迁移（发送或接收）。
     *
     * 状态复位由协程的取消分支负责（"已取消迁移"提示，见 [start] /
     * [processIncomingBundle] 的 CancellationException 分支）：此处若再无条件复位，
     * 与协程的写入存在竞态，取消反馈可能被吞掉。
     */
    fun cancel() {
        migrationJob?.cancel()
        migrationJob = null
    }

    /** 关闭迁移结果报告（AC-10.3 清单展示后）。 */
    fun dismissReport() {
        migrationState.value = TransferMigrationState()
    }

    /** 解包 → 逐本导入 → 按 AC-10.4 回写（在 [scope] 的后台上下文中执行）。 */
    private suspend fun processIncomingBundle(zip: File) {
        val workDir = File(context.cacheDir, "transfer/unpack").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            migrationState.value = migrationState.value.copy(phaseLabel = "解包迁移包", fraction = 1f)
            val unpacked = MigrationBundleCodec.unpack(zip, workDir)
            if (unpacked == null) {
                migrationState.value = migrationState.value.copy(
                    active = false,
                    error = "迁移包损坏或版本不兼容",
                )
                return
            }
            migrationState.value = migrationState.value.copy(
                total = unpacked.manifest.books.size,
                phaseLabel = "导入书籍",
            )
            val report = migrationProcessor.process(unpacked) { done, total, title ->
                migrationState.value = migrationState.value.copy(
                    index = done,
                    total = total,
                    currentTitle = title,
                    fraction = if (total == 0) 1f else done.toFloat() / total,
                )
            }
            migrationState.value = migrationState.value.copy(
                active = false,
                phaseLabel = "迁移完成",
                fraction = 1f,
                report = report,
            )
        } catch (e: CancellationException) {
            migrationState.value = TransferMigrationState(error = "已取消迁移")
            throw e
        } catch (e: Exception) {
            migrationState.value = migrationState.value.copy(
                active = false,
                error = e.message ?: "迁移失败",
            )
        } finally {
            zip.delete()
            workDir.deleteRecursively()
        }
    }

    private companion object {
        /** 接收到的迁移包落盘名（单槽：上一包仍在处理时会被覆盖，与拆分前一致）。 */
        const val INCOMING_BUNDLE_NAME = "incoming.bundle.zip"

        /** 发送的迁移包落盘名（对端展示名同此）。 */
        const val OUTGOING_BUNDLE_NAME = "paperread-migrate.zip"
    }
}
