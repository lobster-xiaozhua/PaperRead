package io.github.lunalight.paperread.data.repository

import android.net.Uri
import io.github.lunalight.paperread.domain.model.Book
import java.io.File

/**
 * 单本导入结局（AC-10.3 / AC-14.5：失败记入清单，不中断整体流程）。
 *
 * 归属变更（T-015 / R-13）：本类型原住 `transfer/MigrationProcessor.kt`。
 * 备份恢复（AC-14.4）要求与迁移走**同一条**导入流水线，两条流程若各持一份
 * 结局类型，「共用」就只是口头约定——因此下沉到数据层，与 [BookImportGateway]
 * 同文件。字段与语义逐字未变，迁移侧只多了一行 import。
 */
sealed interface ImportOutcome {
    /** 导入成功（已入库）。 */
    data class Imported(val book: Book) : ImportOutcome

    /** 未能导入（携带可展示原因）。 */
    data class Skipped(val reason: String) : ImportOutcome
}

/**
 * 导入元数据提示（仅覆盖书名 / 作者，不参与解析）。
 *
 * 存在的理由：导入流水线从 **SAF 显示名**推导书名，`displayName` 在迁移 / 备份
 * 场景里是「<sha256>.txt」这类临时名。迁移保持既有行为（传 null，逐字节不变）；
 * 备份恢复需要还原原始书名 / 作者（AC-14.1），故在 `confirm` 前覆盖
 * [PendingImport] 的这两个字段——章节、编码、哈希仍来自真实解析结果。
 */
data class ImportHints(
    val title: String = "",
    val author: String = "",
)

/**
 * 单本书导入网关（AC-9.5 / AC-14.4 的落地点）。
 *
 * 迁移接收与备份恢复都必须经本接口导入（`prepare → confirm` 全流水线），
 * 不允许出现第二条导入逻辑：格式分发、编码探测、章节切分、SHA-256 去重、
 * `book.txt` 落盘全部只在 `PreparePipeline` 里发生一次。
 *
 * @see AndroidBookImportGateway Android 侧实现（SAF Uri / FileProvider）
 */
interface BookImportGateway {

    /**
     * 从 SAF Uri 导入并入库。
     *
     * @param hints 非空时覆盖入库书名 / 作者（空串表示沿用解析结果）
     * @return [ImportOutcome.Imported] 携带入库后的完整书籍；失败 / 重复为 [ImportOutcome.Skipped]
     */
    suspend fun importFromUri(uri: Uri, hints: ImportHints? = null): ImportOutcome

    /**
     * 从本机临时文件导入并入库（内部经 FileProvider 转 content Uri，
     * 与传书接收端走完全相同的接入路径）。
     */
    suspend fun importFromFile(file: File, hints: ImportHints? = null): ImportOutcome
}
