package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.domain.model.Book
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 换机迁移包（AC-10.1）：单一 zip = manifest.json（每本书的元数据 + 进度 + 书签）
 * + sources/<sha256>.<ext>（书籍源文件，按 SHA-256 去重）。
 *
 * 纯 JVM：不持有 Android 类型，打包 / 解包全部单测覆盖。
 */

/** 迁移包内协议版本（与传输协议版本独立演进）。 */
const val MIGRATION_BUNDLE_VERSION: Int = 1

private const val MANIFEST_ENTRY = "manifest.json"
private const val SOURCES_PREFIX = "sources/"

/** 书签快照（不含 id / bookId：回写时按目标书籍重新分配）。 */
@Serializable
data class BookmarkSnapshot(
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val charOffset: Int,
    val excerpt: String,
    val createdAt: Long,
)

/**
 * 单本书的迁移快照：导入所需元数据 + 阅读状态（AC-10.2 回写依据）。
 *
 * @param sha256 源文件内容哈希：既用于源文件寻址（sources/<sha256>.<ext>），
 *   也用于接收端匹配已存在书籍
 */
@Serializable
data class BookStateSnapshot(
    val sha256: String,
    val title: String,
    val author: String,
    /** [io.github.lunalight.paperread.domain.model.BookFormat] 的 name（TXT / EPUB）。 */
    val format: String,
    val encoding: String,
    val fileSize: Long,
    val totalChars: Long,
    val chapterCount: Int,
    val lastChapterIndex: Int,
    val lastParagraphIndex: Int,
    val lastScrollOffset: Int,
    val lastCharOffset: Int,
    val progressPercent: Float,
    val lastReadAt: Long?,
    val addedAt: Long,
    val bookmarks: List<BookmarkSnapshot>,
)

/** 迁移包清单。 */
@Serializable
data class MigrationManifest(
    val version: Int = MIGRATION_BUNDLE_VERSION,
    val books: List<BookStateSnapshot>,
)

/** 解包产物：清单 + 已落盘的源文件（sha256 → 临时文件）。 */
data class UnpackedBundle(
    val manifest: MigrationManifest,
    val sources: Map<String, File>,
)

/** 迁移包编解码（zip + JSON，零第三方依赖）。 */
object MigrationBundleCodec {

    private val json = Json {
        ignoreUnknownKeys = true // 前向兼容：对端高版本清单不阻塞旧版解析
        encodeDefaults = true
    }

    /**
     * 打包写入 [out]。
     *
     * @param sources sha256 → 源文件（缺失 / 不可读的书目在清单中保留——
     *   接收端按「源文件缺失」记失败，不静默丢条目，AC-10.3）
     */
    fun pack(manifest: MigrationManifest, sources: Map<String, File>, out: File) {
        ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
            zip.setLevel(6)
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            json.encodeToString(MigrationManifest.serializer(), manifest)
                .toByteArray(Charsets.UTF_8)
                .let { zip.write(it) }
            zip.closeEntry()
            sources.forEach { (sha256, file) ->
                if (!file.isFile) return@forEach
                val ext = file.name.substringAfterLast('.', "bin")
                zip.putNextEntry(ZipEntry("$SOURCES_PREFIX$sha256.$ext"))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * 解包 [zipFile] 到 [destDir]（调用方负责清理）。
     *
     * 安全约束：条目名必须位于 `manifest.json` 或 `sources/<安全名>` 之内，
     * 拒绝绝对路径 / `..` 穿越（zip slip）。
     *
     * @return 解包产物；zip 损坏 / 清单缺失或版本不兼容时返回 null
     */
    fun unpack(zipFile: File, destDir: File): UnpackedBundle? {
        if (!zipFile.isFile) return null
        destDir.mkdirs()
        val sources = mutableMapOf<String, File>()
        var manifest: MigrationManifest? = null
        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (entry.isDirectory) continue
                    val target = when {
                        name == MANIFEST_ENTRY -> {
                            val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                            manifest = runCatching {
                                json.decodeFromString(MigrationManifest.serializer(), text)
                            }.getOrNull() ?: return null
                            continue
                        }

                        name.startsWith(SOURCES_PREFIX) -> {
                            val base = name.removePrefix(SOURCES_PREFIX)
                            if (base.isBlank() || base.contains('/') || base.contains('\\') ||
                                base.contains("..") || base.startsWith("/")
                            ) {
                                continue // 跳过可疑条目，不让整个迁移失败
                            }
                            File(destDir, base)
                        }

                        else -> continue // 未知条目忽略（前向兼容）
                    }
                    if (!target.canonicalPath.startsWith(destDir.canonicalPath)) continue
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    val sha256 = base_name_sha(target)
                    if (sha256 != null) sources[sha256] = target
                }
            }
        } catch (e: Exception) {
            return null
        }
        val parsed = manifest ?: return null
        if (parsed.version > MIGRATION_BUNDLE_VERSION) return null // 高版本不盲目解析
        return UnpackedBundle(parsed, sources)
    }

    /** 从 `sources/<sha256>.<ext>` 解出的文件名取 sha256 键。 */
    private fun base_name_sha(file: File): String? {
        val name = file.name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else null
    }
}

/**
 * 回写决策（AC-10.4：最后阅读时间较新者胜，纯函数）。
 *
 * 规则：
 * - 对端无阅读记录（lastReadAt = null）→ 不回写（本地有记录时保留本地）
 * - 本地无记录 → 回写对端
 * - 双方都有 → 较新者胜（严格大于才覆盖，相等不动作）
 */
object WriteBackDecider {

    fun shouldWriteBack(localLastReadAt: Long?, remoteLastReadAt: Long?): Boolean = when {
        remoteLastReadAt == null -> false
        localLastReadAt == null -> true
        else -> remoteLastReadAt > localLastReadAt
    }
}

/** 书籍 + 书签 → 迁移快照（纯函数：域模型 → 可序列化快照）。 */
fun Book.toSnapshot(bookmarks: List<io.github.lunalight.paperread.domain.model.Bookmark>): BookStateSnapshot =
    BookStateSnapshot(
        sha256 = sha256,
        title = title,
        author = author,
        format = format.name,
        encoding = encoding,
        fileSize = fileSize,
        totalChars = totalChars,
        chapterCount = chapterCount,
        lastChapterIndex = lastChapterIndex,
        lastParagraphIndex = lastParagraphIndex,
        lastScrollOffset = lastScrollOffset,
        lastCharOffset = lastCharOffset,
        progressPercent = progressPercent,
        lastReadAt = lastReadAt,
        addedAt = addedAt,
        bookmarks = bookmarks.map { bookmark ->
            BookmarkSnapshot(
                chapterIndex = bookmark.chapterIndex,
                paragraphIndex = bookmark.paragraphIndex,
                charOffset = bookmark.charOffset,
                excerpt = bookmark.excerpt,
                createdAt = bookmark.createdAt,
            )
        },
    )
