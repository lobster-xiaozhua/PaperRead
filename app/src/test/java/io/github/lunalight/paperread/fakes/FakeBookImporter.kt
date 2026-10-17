package io.github.lunalight.paperread.fakes

import android.net.Uri
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.ImportProgress
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import kotlinx.coroutines.CompletableDeferred

/**
 * [BookImporter] 的内存 fake：结果可配置，[prepareGate] 可挂起 prepare
 * （配合取消测试），全部调用被记录。
 */
class FakeBookImporter : BookImporter {

    /** prepare 的返回结果。 */
    var prepareResult: PrepareResult =
        PrepareResult.Failure("未配置 prepareResult")

    /** 非空时 prepare 先等待该门（测试取消 / 观察运行态）。 */
    var prepareGate: CompletableDeferred<Unit>? = null

    /** prepare 期间经 onProgress 回调的阶段序列（放行门前/后各一轮）。 */
    var progressSteps: List<ImportProgress> = emptyList()

    var reparseResult: PendingImport? = null
    var reparseBookResult: String? = null
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
        progressSteps.forEach(onProgress)
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
        return confirmResult
    }

    override fun cancel(pending: PendingImport) {
        cancelledPendings += pending
    }

    override suspend fun reparseBook(book: Book, strategy: SplitStrategy): String? {
        reparsedBooks += book to strategy
        return reparseBookResult
    }
}
