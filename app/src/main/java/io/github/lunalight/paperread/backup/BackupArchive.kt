package io.github.lunalight.paperread.backup

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 备份包的 zip 层（T-015 / T-016，R-13）：条目命名、打开、读取、源文件解出。
 *
 * ## 包结构（`BackupManifest.kt` 的 KDoc 有完整说明）
 * ```
 * manifest.json                  明文，必须存在，第一个读取
 * crypto.json                    仅加密备份：KDF 参数 + salt + verifier
 * state/<dirName>.json           阅读状态（加密备份里是密文）
 * books/<dirName>/source.<ext>   源文件（加密备份里是密文）
 * ```
 *
 * ## 安全约束
 * - **不按条目名落盘**：解包时只会写到调用方给定的临时文件名，条目名仅用于
 *   在 zip 里查找（zip slip 无着力点）；
 * - `dirName` 必须是 `[A-Za-z0-9_-]+`（生产值是 UUID），杜绝路径穿越进 manifest；
 * - 加密条目 = `nonce(12) ‖ ciphertext ‖ tag`，每条目独立 nonce（防 nonce 复用）。
 */
internal object BackupEntries {

    const val MANIFEST = "manifest.json"
    const val CRYPTO = "crypto.json"
    const val STATE_PREFIX = "state/"
    const val BOOKS_PREFIX = "books/"

    fun stateEntry(dirName: String): String = "$STATE_PREFIX$dirName.json"

    fun sourceEntry(dirName: String, extension: String): String =
        "$BOOKS_PREFIX$dirName/source.$extension"

    /** 目录名白名单（生产值 = 导入时生成的 UUID）。 */
    fun isSafeDirName(dirName: String): Boolean =
        dirName.isNotEmpty() && dirName.length <= 64 &&
            dirName.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** 条目路径只允许出现在约定的两个前缀下，且不含穿越片段。 */
    fun isSafeEntryPath(path: String, prefix: String): Boolean =
        path.startsWith(prefix) && !path.contains("..") && !path.contains('\\') &&
            path.length <= 256
}

/** 打开备份的结果：成功 / 需要口令 / 口令错误 / 拒绝。 */
internal sealed interface BackupOpenResult {

    /** 已打开（调用方负责 [BackupPackage.close]）。 */
    data class Opened(val pkg: BackupPackage) : BackupOpenResult

    /** 加密备份但未提供口令（manifest 可读，能先展示摘要）。 */
    data class PasswordRequired(val manifest: BackupManifest) : BackupOpenResult

    /** 提供了口令但校验失败（AC-14.7：可重试，不泛化）。 */
    data class WrongPassword(val manifest: BackupManifest) : BackupOpenResult

    /** 不是备份 / 损坏 / 版本过新（AC-14.3）。 */
    data class Invalid(
        val reason: BackupInvalidReason,
        val detail: String? = null,
        val manifest: BackupManifest? = null,
    ) : BackupOpenResult
}

/** 备份包被拒绝的原因（UI 映射为 strings.xml 文案）。 */
enum class BackupInvalidReason {
    /** 不是 zip / 缺 manifest / JSON 解析失败。 */
    NOT_A_BACKUP,

    /** 结构自检失败（计数不符 / 内容摘要不符 / 条目路径非法）。 */
    CORRUPT,

    /** 备份版本高于本应用支持的版本（AC-14.3）。 */
    VERSION_TOO_NEW,
}

/** 源文件解出结果（明文大小 + SHA-256，用于与 manifest 比对）。 */
internal data class ExtractedSource(val sizeBytes: Long, val sha256: String)

/**
 * 已打开的备份包：manifest 已解析、加密器（如果有）已就绪。
 *
 * 所有读取都在调用方的 IO 线程内同步完成；[close] 会同时关闭 [ZipFile] 与
 * 加密器（密钥 zero-fill）。
 */
internal class BackupPackage private constructor(
    private val zip: ZipFile,
    val manifest: BackupManifest,
    private val crypto: BackupCrypto?,
) : Closeable {

    /** manifest 声明的源文件条目是否存在（`sourceMissing` 的书不参与）。 */
    fun hasSourceEntry(entry: BackupBookEntry): Boolean =
        zip.getEntry(entry.sourceEntry) != null

    /** 读取阅读状态；条目缺失 / 解密失败 / JSON 非法一律返回 null（记单本失败）。 */
    fun readState(entry: BackupBookEntry): BackupBookState? {
        val bytes = readEntry(entry.stateEntry) ?: return null
        return runCatching {
            backupJson.decodeFromString(BackupBookState.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * 解出源文件到 [target]，同时计算明文 SHA-256 与大小。
     *
     * @return null 表示条目缺失
     * @throws BackupCryptoException 密文损坏（口令已通过 verifier 校验）
     */
    fun extractSource(entry: BackupBookEntry, target: File): ExtractedSource? {
        val zipEntry = zip.getEntry(entry.sourceEntry) ?: return null
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        target.outputStream().buffered().use { fileOut ->
            zip.getInputStream(zipEntry).use { raw ->
                if (crypto != null) {
                    val counting = CountingDigestOutputStream(fileOut, digest)
                    crypto.decryptStream(raw, counting)
                    size = counting.count
                } else {
                    size = copyWithDigest(raw, fileOut, digest)
                }
            }
        }
        return ExtractedSource(sizeBytes = size, sha256 = digest.digest().joinToString("") { "%02x".format(it) })
    }

    override fun close() {
        crypto?.close()
        zip.close()
    }

    private fun readEntry(name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        val bytes = try {
            zip.getInputStream(entry).use { it.readBytes() }
        } catch (e: IOException) {
            return null
        }
        val activeCrypto = crypto
        return if (activeCrypto != null) {
            runCatching { activeCrypto.decrypt(bytes) }.getOrNull()
        } else {
            bytes
        }
    }

    companion object {

        /**
         * 打开 [file]（[password] 为 null 表示未提供口令）。
         *
         * 打开顺序刻意固定：manifest → 版本 → 结构自检 → 加密参数 → 口令校验。
         * 口令错误在这里就与「文件损坏」区分开，是 AC-14.7 可重试提示的前提。
         */
        fun open(file: File, password: CharArray?): BackupOpenResult {
            if (!file.isFile) {
                return BackupOpenResult.Invalid(BackupInvalidReason.NOT_A_BACKUP, "文件不存在")
            }
            val zip = try {
                ZipFile(file)
            } catch (e: Exception) {
                return BackupOpenResult.Invalid(BackupInvalidReason.NOT_A_BACKUP, e.message)
            }
            var opened = false
            try {
                val manifestEntry = zip.getEntry(BackupEntries.MANIFEST)
                    ?: return BackupOpenResult.Invalid(BackupInvalidReason.NOT_A_BACKUP, "缺少 manifest.json")
                val manifest = try {
                    val text = zip.getInputStream(manifestEntry).use { it.readBytes() }
                        .toString(Charsets.UTF_8)
                    backupJson.decodeFromString(BackupManifest.serializer(), text)
                } catch (e: Exception) {
                    return BackupOpenResult.Invalid(BackupInvalidReason.NOT_A_BACKUP, e.message)
                }

                // AC-14.3：高版本备份明确拒绝，绝不「尽力解析」
                if (manifest.version > BACKUP_VERSION) {
                    return BackupOpenResult.Invalid(
                        BackupInvalidReason.VERSION_TOO_NEW,
                        "version=${manifest.version}",
                        manifest,
                    )
                }
                if (manifest.version < 1 || manifest.bookCount != manifest.books.size) {
                    return BackupOpenResult.Invalid(BackupInvalidReason.CORRUPT, "manifest 计数不一致", manifest)
                }
                if (manifest.contentDigest != backupContentDigest(manifest.books)) {
                    return BackupOpenResult.Invalid(BackupInvalidReason.CORRUPT, "内容摘要不匹配", manifest)
                }
                manifest.books.firstOrNull { !BackupEntries.isSafeDirName(it.dirName) }?.let { bad ->
                    return BackupOpenResult.Invalid(
                        BackupInvalidReason.CORRUPT,
                        "目录名非法：${bad.dirName}",
                        manifest,
                    )
                }
                manifest.books.firstOrNull {
                    !BackupEntries.isSafeEntryPath(it.stateEntry, BackupEntries.STATE_PREFIX) ||
                        (it.sourceEntry.isNotEmpty() &&
                            !BackupEntries.isSafeEntryPath(it.sourceEntry, BackupEntries.BOOKS_PREFIX))
                }?.let { bad ->
                    return BackupOpenResult.Invalid(
                        BackupInvalidReason.CORRUPT,
                        "条目路径非法：${bad.stateEntry}",
                        manifest,
                    )
                }

                val crypto = if (manifest.encrypted) {
                    if (password == null) {
                        return BackupOpenResult.PasswordRequired(manifest)
                    }
                    val paramsEntry = zip.getEntry(BackupEntries.CRYPTO)
                        ?: return BackupOpenResult.Invalid(
                            BackupInvalidReason.CORRUPT,
                            "缺少 crypto.json",
                            manifest,
                        )
                    val params = try {
                        val text = zip.getInputStream(paramsEntry).use { it.readBytes() }
                            .toString(Charsets.UTF_8)
                        backupJson.decodeFromString(BackupCryptoParams.serializer(), text)
                    } catch (e: Exception) {
                        return BackupOpenResult.Invalid(BackupInvalidReason.CORRUPT, e.message, manifest)
                    }
                    try {
                        BackupCrypto.open(password, params)
                    } catch (e: BackupCryptoException) {
                        when (e.kind) {
                            BackupCryptoException.Kind.WRONG_PASSWORD ->
                                return BackupOpenResult.WrongPassword(manifest)

                            else -> return BackupOpenResult.Invalid(
                                BackupInvalidReason.CORRUPT,
                                e.message,
                                manifest,
                            )
                        }
                    }
                } else {
                    null
                }

                opened = true
                return BackupOpenResult.Opened(BackupPackage(zip, manifest, crypto))
            } finally {
                if (!opened) zip.close()
            }
        }
    }
}

/** 写入助手：encrypted 时走 `nonce ‖ ciphertext`，明文时按正常 zip 压缩。 */
internal object BackupWriter {

    fun writeBytes(zip: java.util.zip.ZipOutputStream, name: String, bytes: ByteArray, crypto: BackupCrypto?, time: Long) {
        val entry = ZipEntry(name).apply { this.time = time }
        if (crypto != null) {
            // 密文不可压缩：STORED 省 CPU，也避免「压缩率泄漏明文熵」这类边信道讨论
            entry.method = ZipEntry.STORED
            val payload = crypto.encrypt(bytes)
            entry.size = payload.size.toLong()
            entry.compressedSize = payload.size.toLong()
            entry.crc = java.util.zip.CRC32().apply { update(payload) }.value
            zip.putNextEntry(entry)
            zip.write(payload)
        } else {
            zip.putNextEntry(entry)
            zip.write(bytes)
        }
        zip.closeEntry()
    }

    fun writeSource(
        zip: java.util.zip.ZipOutputStream,
        name: String,
        source: File,
        crypto: BackupCrypto?,
        time: Long,
    ) {
        val entry = ZipEntry(name).apply { this.time = time }
        if (crypto != null) {
            entry.method = ZipEntry.DEFLATED
            zip.putNextEntry(entry)
            source.inputStream().buffered().use { input -> crypto.encryptStream(input, zip) }
        } else {
            zip.putNextEntry(entry)
            source.inputStream().buffered().use { input -> input.copyTo(zip, DEFAULT_BUFFER_SIZE) }
        }
        zip.closeEntry()
    }
}

/** 计数 + 摘要输出流（加密源文件解密时统计明文大小）。 */
private class CountingDigestOutputStream(
    delegate: java.io.OutputStream,
    private val digest: MessageDigest,
) : java.io.FilterOutputStream(delegate) {

    var count: Long = 0L
        private set

    override fun write(b: ByteArray, off: Int, len: Int) {
        digest.update(b, off, len)
        count += len
        out.write(b, off, len)
    }

    override fun write(b: Int) {
        digest.update(b.toByte())
        count += 1
        out.write(b)
    }
}

/** 明文源文件拷贝 + 摘要，返回字节数。 */
private fun copyWithDigest(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    digest: MessageDigest,
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
        total += read
    }
    return total
}
