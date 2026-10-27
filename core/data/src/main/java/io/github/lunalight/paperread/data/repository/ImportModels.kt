package io.github.lunalight.paperread.data.repository

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.reader.txt.SplitStrategy

/**
 * 导入域的数据契约（T-012 从 `BookImporter.kt` 拆出，类名与包名不变，
 * 调用方 import 无感）。
 *
 * 这里只放**纯数据**：阶段枚举、进度、待确认快照、结构化错误、准备结果。
 * 行为（流水线 / 注册表 / 门面）在各自文件里，本文件不依赖 Android SDK。
 */

/** 导入阶段（UI 进度条用）。 */
enum class ImportStage(val label: String) {
    PRECHECK("检查文件"),
    COPYING("复制文件"),
    DETECTING("探测编码"),
    CHECKING_DUPLICATE("检查重复"),
    PARSING("解析章节"),
    WRITING_INDEX("写入索引"),
    SAVING("保存到书架"),
    DONE("完成"),
}

/** 导入进度：阶段 + 该阶段内的进度（0..1）。 */
data class ImportProgress(val stage: ImportStage, val fraction: Float)

/**
 * 待确认导入：文件已落盘并完成解析，等待用户在结果页确认
 * （TXT 可切换编码 / 切分策略后重新解析，再入库）。
 *
 * @param chapters 章节列表（bookId 暂为 0，confirm 入库时回填）
 */
data class PendingImport(
    val dirName: String,
    val displayName: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val charsetName: String,
    val strategy: SplitStrategy,
    val sha256: String,
    val fileSize: Long,
    val totalChars: Long,
    val chapters: List<Chapter>,
)

/**
 * 结构化导入 / 解析失败原因（B7）。
 *
 * 每个子类携带三要素：
 * - [stage]：失败发生在流水线哪个阶段（定位断点）
 * - [cause]：底层原因（异常信息 / 触发值），供日志与排查
 * - [userMessage]：面向用户的提示话术，UI 层直接取用，不再自行拼文案
 *
 * 文案区分度即产品验收：DRM / 空间不足 / 编码失败等场景的话术两两不同。
 */
sealed class ImportError {

    abstract val stage: ImportStage
    abstract val cause: String?
    abstract val userMessage: String

    /** 扩展名不在 txt / epub 内（[ImportError.cause] 为扩展名）。 */
    data class UnsupportedFormat(val extension: String) : ImportError() {
        override val stage: ImportStage = ImportStage.PRECHECK
        override val cause: String get() = "extension=$extension"
        override val userMessage: String = "仅支持 TXT / EPUB 格式"
    }

    /** 磁盘预检不通过（剩余空间不足文件双份 + 20% 余量）。 */
    data object InsufficientSpace : ImportError() {
        override val stage: ImportStage = ImportStage.PRECHECK
        override val cause: String? = null
        override val userMessage: String = "存储空间不足，无法导入"
    }

    /** EPUB 含 DRM 加密（[ImportError.cause] 为底层异常信息）。 */
    data class DrmEncrypted(val detail: String?) : ImportError() {
        override val stage: ImportStage = ImportStage.PARSING
        override val cause: String? get() = detail
        override val userMessage: String = "该 EPUB 含 DRM 加密，无法导入"
    }

    /**
     * 文件读取失败：打开文件流 / 拷贝读取异常（SAF 权限失效、文件被移动删除等，
     * [ImportError.cause] 为异常信息）。仅覆盖拷贝期；解析期的 IO 异常归 [ParseFailed]。
     */
    data class ReadFailed(val detail: String?) : ImportError() {
        override val stage: ImportStage = ImportStage.COPYING
        override val cause: String? get() = detail
        override val userMessage: String = "无法读取文件，请重试"
    }

    /** 书籍目录创建失败（[ImportError.cause] 为异常信息）。 */
    data class DirCreateFailed(val detail: String?) : ImportError() {
        override val stage: ImportStage = ImportStage.COPYING
        override val cause: String? get() = detail
        override val userMessage: String = "无法创建书籍目录，请重试"
    }

    /** BOM 声明的编码无法严格解码（[ImportError.cause] 为编码名）。 */
    data class DecodeFailed(val encoding: String) : ImportError() {
        override val stage: ImportStage = ImportStage.DETECTING
        override val cause: String get() = "encoding=$encoding"
        override val userMessage: String = "文件编码异常，请转换编码后重试"
    }

    /** 章节解析失败：结构非法 / 内容异常 / 拷贝读取异常（[ImportError.cause] 为异常信息）。 */
    data class ParseFailed(val detail: String?) : ImportError() {
        override val stage: ImportStage = ImportStage.PARSING
        override val cause: String? get() = detail
        override val userMessage: String = "文件解析失败，请重试"
    }

    /** 重新解析时源文件丢失（仅 reparseBook 产出，[ImportError.cause] 为书籍目录名）。 */
    data class SourceMissing(val dirName: String) : ImportError() {
        override val stage: ImportStage = ImportStage.PARSING
        override val cause: String get() = "dir=$dirName"
        override val userMessage: String = "源文件已丢失，请删除后重新导入"
    }
}

/** 导入准备结果。 */
sealed interface PrepareResult {
    /** 解析完成，等待确认。 */
    data class Success(val pending: PendingImport) : PrepareResult

    /** SHA-256 命中已存在书籍。 */
    data class Duplicate(val existing: Book) : PrepareResult

    /** 格式不支持（扩展名不在 txt/epub 内）。 */
    data class Unsupported(val error: ImportError.UnsupportedFormat) : PrepareResult

    /** 失败（空间不足 / DRM / 编码异常 / 解析异常等），临时目录已清理。 */
    data class Failure(val error: ImportError) : PrepareResult
}
