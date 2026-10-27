package io.github.lunalight.paperread.reader

import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.txt.ChapterIndexEntry
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File

/**
 * 格式解析器契约（T-012 / R-11）。
 *
 * 把「某个格式的源文件 → 标准化正文 + 章节索引」这一步从导入编排里切出来：
 * 编排层（`:app` 的 `PreparePipeline`）只负责预检 / 拷贝 / 去重 / 落库，
 * 各格式的解析细节下沉到 [BookParser] 实现，由 `BookParserRegistry` 按
 * [BookFormat] 分发——**新增格式 = 实现本接口 + 在注册表工厂里加一行**。
 *
 * 本文件在 `:core:reader` 内，**零 Android 依赖**（只用 `java.io.File` 与
 * `:core:domain` 模型）：T-010 的教训是 `XmlParsers::jvm` 依赖
 * `app` 侧实现导致的循环引用——因此契约层只声明「读什么文件、写什么文件」，
 * 需要 Android API 的实现（EPUB 的 XmlPullParser 工厂）由 `:core:data` 的
 * `EpubBookParser` 显式传入，**不得**在本模块反向依赖 Android。
 *
 * 线程语义：实现是**阻塞**函数（大文件流式 IO），调用方负责放进
 * `Dispatchers.IO`；取消经 [ParseContext.shouldContinue] 逐块检查，
 * 返回 false 时实现抛 [kotlinx.coroutines.CancellationException]。
 */
interface BookParser {

    /** 本解析器负责的格式（注册表的键，一对一）。 */
    val format: BookFormat

    /**
     * 解析 [ParseContext.sourceFile]，把标准化正文写入 [ParseContext.targetFile]。
     *
     * @return 解析产物（标题 / 作者 / 编码名 / 字数 / 章节索引）
     * @throws BookDecodeException BOM 声明的编码无法严格解码（仅编码探测路径）
     * @throws io.github.lunalight.paperread.reader.epub.EpubEncryptedException
     *   EPUB 含 DRM 加密正文
     */
    fun parse(context: ParseContext): ParseResult
}

/**
 * 解析阶段提示（供 UI 进度条显示「探测编码 / 解析章节 / 写入索引」）。
 *
 * 刻意不是 `:app` 的 `ImportStage`：契约层不感知 UI 文案，由编排层把
 * [ParsePhase] 映射成具体的阶段 + 进度值。
 */
enum class ParsePhase {
    /** 编码探测（TXT）。 */
    DETECTING,

    /** 正文解析 / 章节切分。 */
    PARSING,

    /** 索引写入（EPUB：先解出全文再落盘，故与解析分离）。 */
    WRITING_INDEX,
}

/**
 * 解析输入。
 *
 * @param sourceFile 已落盘的源文件（`source.txt` / `source.epub`）
 * @param targetFile 正文输出文件（`book.txt`，UTF-8 标准化）
 * @param dirName 书籍目录名（仅用于诊断 / 日志定位）
 * @param strategy TXT 章节切分策略（EPUB 忽略：章节结构来自 OPF/spine）
 * @param displayName 原始文件名（含扩展名）。TXT 的缺省标题取自它；未知时 null
 * @param charsetOverride 非空 = 用户显式指定的编码（结果页切换编码 / 书架重新解析）：
 *   跳过编码探测与 BOM 严格校验，按该编码解码（无法识别时回落 UTF-8）。
 *   为 null 时走「BOM → 严格 UTF-8 → GB18030」探测。
 * @param titleOverride 非空 = 保留该书既有书名（重新解析不因文件名变化而改名）
 * @param onPhase 阶段回调（映射到 UI 进度阶段）
 * @param onProgress 细粒度进度（0..1，仅 TXT 流式解码有意义）
 * @param shouldContinue 取消钩子：返回 false 时解析抛 CancellationException
 */
data class ParseContext(
    val sourceFile: File,
    val targetFile: File,
    val dirName: String,
    val strategy: SplitStrategy,
    val displayName: String? = null,
    val charsetOverride: String? = null,
    val titleOverride: String? = null,
    val onPhase: (ParsePhase) -> Unit = {},
    val onProgress: (Float) -> Unit = {},
    val shouldContinue: () -> Boolean = { true },
)

/**
 * 解析产物：与「导入待确认」一一对应的最小信息集。
 *
 * @param title 解析出的书名（EPUB 取 OPF，TXT 取文件名或 [ParseContext.titleOverride]）
 * @param author 作者（EPUB 取 OPF，TXT 无元数据时为 "未知"）
 * @param charsetName 编码展示名（EPUB 恒为 "UTF-8"：内部统一转存）
 * @param totalChars 正文字符数（进度百分比的分母）
 * @param entries 章节索引（区间连续、覆盖全文，可直接映射为领域 `Chapter`）
 */
data class ParseResult(
    val title: String,
    val author: String,
    val charsetName: String,
    val totalChars: Long,
    val entries: List<ChapterIndexEntry>,
)

/**
 * 编码失败：BOM 声明了编码，而样本内容无法按该编码严格解码。
 *
 * 携带 BOM 声明的编码名，供编排层翻译成面向用户的 [String] 话术
 * （"文件编码异常，请转换编码后重试"）。无 BOM 时的 GB18030 回退是设计内的
 * 容错路径，不抛本异常。
 */
class BookDecodeException(val encoding: String) : Exception("encoding=$encoding")
