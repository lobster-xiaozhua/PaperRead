package io.github.lunalight.paperread.fakes

import android.net.Uri
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.ImportError
import io.github.lunalight.paperread.data.repository.ImportProgress
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import kotlinx.coroutines.CompletableDeferred

/**
 * [BookImporter] 的内存 fake：结果可配置，[prepareGate] 可挂起 prepare
 * （配合取消测试），全部调用被记录。
 *
 * 失败注入（G4 对端处理失败 / 导入失败路径）：
 * - [failNextImport]：下一次 prepare 抛 [java.io.IOException]（导入流水线崩溃）；
 * - [failNextConfirm]：下一次 confirm 抛 [java.io.IOException]（入库失败）。
 */
class FakeBookImporter : BookImporter {

    /** prepare 的返回结果。 */
    var prepareResult: PrepareResult =
        PrepareResult.Failure(ImportError.ParseFailed("未配置 prepareResult"))

    /** 非空时 prepare 先等待该门（测试取消 / 观察运行态）。 */
    var prepareGate: CompletableDeferred<Unit>? = null

    /**
     * 非空时 prepare 按调用顺序依次消费（模拟迁移多本书不同的导入结局：
     * 第一本成功、第二本 DRM……）。消费完回落到 [prepareResult]。
     */
    var prepareResultQueue: ArrayDeque<PrepareResult>? = null

    /** prepare 期间经 onProgress 回调的阶段序列（放行门前/后各一轮）。 */
    var progressSteps: List<ImportProgress> = emptyList()

    /** 为 true 时下一次 prepare 抛 IOException（模拟导入流水线崩溃，用后自复位）。 */
    var failNextImport: Boolean = false

    /** 为 true 时下一次 confirm 抛 IOException（模拟入库失败，用后自复位）。 */
    var failNextConfirm: Boolean = false

    var reparseResult: PendingImport? = null
    var reparseBookResult: ImportError? = null
    var confirmResult: Long = 1L

    data class PrepareCall(val uri: Uri, val strategy: SplitStrategy)

    val prepareCalls = mutableListOf<PrepareCall>()
    val reparseCalls = mutableListOf<Triple<PendingImport, String, SplitStrategy>>()
    val confirmed = mutableListOf<PendingImport>()
    val cancelledPendings = mutableListOf<PendingImport>()
    val reparsedBooks = mutableListOf<Pair<Book, SplitStrategy>>()

    override suspend fun prepare(
        uri: Uri,
        strategy: SplitStrategy,
        onProgress: (ImportProgress) -> Unit,
    ): PrepareResult {
        prepareCalls += PrepareCall(uri, strategy)
        prepareGate?.await()
        if (failNextImport) {
            failNextImport = false
            throw java.io.IOException("模拟导入流水线崩溃")
        }
        progressSteps.forEach(onProgress)
        prepareResultQueue?.removeFirstOrNull()?.let { return it }
        return prepareResult
    }

    override suspend fun reparse(
        pending: PendingImport,
        charsetName: String,
        strategy: SplitStrategy,
    ): PendingImport? {
        reparseCalls += Triple(pending, charsetName, strategy)
        return reparseResult
    }

    override suspend fun confirm(pending: PendingImport): Long {
        confirmed += pending
        if (failNextConfirm) {
            failNextConfirm = false
            throw java.io.IOException("模拟入库失败")
        }
        return confirmResult
    }

    override fun cancel(pending: PendingImport) {
        cancelledPendings += pending
    }

    override suspend fun reparseBook(book: Book, strategy: SplitStrategy): ImportError? {
        reparsedBooks += book to strategy
        return reparseBookResult
    }
}
