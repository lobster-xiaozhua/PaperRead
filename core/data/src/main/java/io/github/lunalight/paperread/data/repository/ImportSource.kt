package io.github.lunalight.paperread.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * SAF 侧的文件接入（T-012 从 `DefaultBookImporter` 拆出）：把「一个 content:// Uri」
 * 变成「元数据 + 已落盘的本地文件 + SHA-256」。
 *
 * 拆出来的理由：这一层是**唯一**需要 `Context.contentResolver` 的部分，与
 * 「解析 / 切分 / 落库」正交——流水线因此只面对 [File]，Android 依赖被收拢在此
 * （也让流水线的其它部分在阅读时不必惦记 SAF 的怪异行为）。
 *
 * 线程语义：阻塞 IO，调用方负责放进 `Dispatchers.IO`；
 * [copyAndHash] 每拷贝一个缓冲区检查一次协程取消。
 */
class ImportSource(private val context: Context) {

    /** SAF 元数据（displayName + 声明大小）。 */
    data class FileMeta(val displayName: String, val sizeBytes: Long)

    /**
     * 查询显示名与大小。
     *
     * @return 永不返回 null：Provider 不支持 query（极少见）时退化为
     *   「路径末段做显示名 + 大小 0」，大小在拷贝时统计
     */
    fun queryMeta(uri: Uri): FileMeta? {
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "book.txt"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)
                } else {
                    fallbackName
                }
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                return FileMeta(name, size)
            }
        }
        // 某些 Provider 不支持 query（极少见）：退化用路径名，大小拷贝时统计
        return FileMeta(fallbackName, 0L)
    }

    /**
     * 磁盘预检：剩余空间是否够「book.txt + source 双份 + 20% 余量」。
     *
     * 用 filesDir（必然存在）评估空间：books/ 子目录首次导入时可能尚未创建。
     */
    fun precheckDisk(sizeBytes: Long): Boolean {
        if (sizeBytes <= 0) return true
        return context.filesDir.freeSpace > sizeBytes * 2.2
    }

    /**
     * 流式拷贝并计算 SHA-256，返回十六进制摘要；每个缓冲区响应协程取消。
     *
     * @param onProgress 拷贝进度（0..1；Provider 未声明大小时不回调中间值）
     */
    suspend fun copyAndHash(
        uri: Uri,
        target: File,
        onProgress: (Float) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
            } else 0L
        } ?: 0L
        var copied = 0L
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开文件流")
        input.use {
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    // 取消检查放在 IO 之后：取消时上层统一清理目录
                    currentCoroutineContext().ensureActive()
                }
                out.flush()
            }
        }
        onProgress(1f)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
