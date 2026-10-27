package io.github.lunalight.paperread.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 本地备份的数据契约（T-015 / US-14，R-13）。
 *
 * 备份包 = 单一 zip，结构见 [BackupArchive]：
 * ```
 * manifest.json                  明文（可读：版本 / 计数 / 书目结构 / 字体引用 / 内容摘要）
 * crypto.json                    仅加密备份：KDF 参数 + salt + 口令校验值（不含口令）
 * state/<dirName>.json           每本书的阅读状态（书名 / 作者 / 进度 / 书签）
 * books/<dirName>/source.<ext>   每本书的源文件（AC-14.1）
 * ```
 *
 * 为什么 `title` 在 [BackupBookEntry] 里是可空的：加密备份的 manifest 必须可读
 * （AC-14.8 恢复前预览），但书名属于书目敏感信息，故加密时置空、改由加密的
 * `state` 条目承载；解密后预览仍能拿到书名。
 *
 * 为什么不导出 `book.txt`（AC-14.1 的口径确认）：`book.txt` 是 `source.<ext>`
 * 经导入流水线派生出的 UTF-8 正文 + 章节索引，属于**可重建的派生文件**。
 * 打包它会让备份体积近似翻倍（TXT 源文件与正文同等量级），而恢复走
 * `PreparePipeline` 时本来就会重新生成（AC-14.4）——所以「全部书籍源文件」
 * 按「恢复所需的原始输入」理解，不含派生文件。迁移包（AC-10.1）同口径。
 */

/** 备份格式版本。高版本包在旧版应用上必须明确拒绝（AC-14.3）。 */
const val BACKUP_VERSION: Int = 1

/**
 * 统一 JSON 策略：
 * - `ignoreUnknownKeys = true`：v2.0 的备份将来在 v2.1 打开时，新增字段不阻塞；
 * - `encodeDefaults = true`：默认值也写盘，manifest 自解释（缺失字段 ≡ 默认值）。
 */
internal val backupJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 备份内引用的字体（AC-14.9：只记引用，不打包字体文件）。 */
@Serializable
data class BackupFontReference(
    /** 设置里的字体标识（`serif` / `sans` / 未来的自定义字体名）。 */
    val name: String,
    /** 展示名（缺失提示用）。 */
    val displayName: String = name,
    /** 是否内置字体（内置永不缺失）。 */
    val builtIn: Boolean = false,
)

/** 书签快照（id / bookId 不入包：恢复时按目标书籍重新分配）。 */
@Serializable
data class BackupBookmark(
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val charOffset: Int = 0,
    val excerpt: String = "",
    val createdAt: Long = 0L,
    /** 备注正文（R-18 / US-17 预留字段，随备份保真）。 */
    val note: String = "",
    /** 备注最后修改时间。 */
    val updatedAt: Long = 0L,
)

/**
 * 单本书的阅读状态（恢复时回写依据）。
 *
 * 这份数据在加密备份里是**密文条目**：书名、作者、阅读位置与书签都属于
 * 「恢复前不必暴露给旁观者」的内容。字段与 `Book` / `Bookmark` 一一对应，
 * 但不引用领域类型——备份文件是持久化契约，必须能独立演进。
 */
@Serializable
data class BackupBookState(
    val title: String = "",
    val author: String = "",
    val encoding: String = "UTF-8",
    val fileSize: Long = 0L,
    val totalChars: Long = 0L,
    val chapterCount: Int = 0,
    val lastChapterIndex: Int = 0,
    val lastParagraphIndex: Int = 0,
    val lastScrollOffset: Int = 0,
    val lastCharOffset: Int = 0,
    val progressPercent: Float = 0f,
    val lastReadAt: Long? = null,
    val addedAt: Long = 0L,
    val bookmarks: List<BackupBookmark> = emptyList(),
)

/**
 * 书目结构条目（manifest 内的「非敏感摘要」）。
 *
 * 恢复需要的寻址信息全在这里：目录名决定落盘位置，源文件条目名 + 大小 + 哈希
 * 决定内容完整性校验（AC-14.3 的包完整性），`title` 仅明文备份填写。
 */
@Serializable
data class BackupBookEntry(
    val dirName: String,
    /** [io.github.lunalight.paperread.domain.model.BookFormat] 的 name。 */
    val format: String,
    val sourceEntry: String,
    val sourceSha256: String,
    val sourceSize: Long,
    /**
     * 导出时源文件已丢失（云备份恢复后的空壳书等）。
     *
     * 这类书仍保留条目（恢复报告要如实列出），但**不写**源文件条目；
     * 与「条目缺失」区分开：后者是包损坏，前者是原本就没有。
     */
    val sourceMissing: Boolean = false,
    val stateEntry: String,
    /** 明文备份才有值；加密备份为 null（书名在加密的 state 条目里）。 */
    val title: String? = null,
    val author: String? = null,
)

/**
 * 备份清单（明文，AC-14.8 恢复前预览的唯一依据）。
 *
 * @param contentDigest 内容摘要：由 [backupContentDigest] 对书目结构计算，
 *   打开备份时重算比对——manifest 被截断 / 手改会被这一项检出。
 */
@Serializable
data class BackupManifest(
    val version: Int = BACKUP_VERSION,
    val encrypted: Boolean = false,
    val createdAt: Long = 0L,
    val appVersion: String = "",
    val bookCount: Int = 0,
    val progressCount: Int = 0,
    val bookmarkCount: Int = 0,
    val fontReferences: List<BackupFontReference> = emptyList(),
    val books: List<BackupBookEntry> = emptyList(),
    val contentDigest: String = "",
)

/**
 * 内容摘要（AC-14.8）：把书目结构规范化后取 SHA-256。
 *
 * 规范化 = 按 dirName 排序，逐行 `dirName|format|sha256|size`。排序让摘要与
 * 打包顺序无关；把哈希与大小纳入摘要，使「manifest 声称的书目」和「实际条目」
 * 的偏差在打开阶段就暴露，而不是等到恢复中途。
 */
fun backupContentDigest(books: List<BackupBookEntry>): String =
    sha256Hex(
        books.sortedBy { it.dirName }
            .joinToString(separator = "\n") { entry ->
                "${entry.dirName}|${entry.format}|${entry.sourceSha256}|${entry.sourceSize}"
            }
            .toByteArray(Charsets.UTF_8),
    )

/** 导出前 / 恢复前的摘要（UI 预览用）。 */
data class BackupPreview(
    val bookCount: Int,
    val progressCount: Int,
    val bookmarkCount: Int,
    val fontReferences: List<BackupFontReference>,
    val totalSourceBytes: Long,
)

/** 备份 / 恢复阶段（进度展示）。 */
enum class BackupStage {
    COLLECTING,
    MANIFEST,
    BOOKS,
    DONE,
}

/** 备份 / 恢复进度（单本粒度 + 总进度）。 */
data class BackupProgress(
    val stage: BackupStage,
    val done: Int = 0,
    val total: Int = 0,
    val fraction: Float = 0f,
    val currentTitle: String = "",
)

/** 十六进制 SHA-256（小写，与导入流水线的 `sha256` 字段同格式）。 */
internal fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
