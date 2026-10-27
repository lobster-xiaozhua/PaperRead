package io.github.lunalight.paperread.reader

import android.content.Context
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Chapter
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * [BookTextStore] 的文件系统实现（filesDir/books/&lt;dirName&gt;/）。
 *
 * 为什么在 `:app` 而不在 `:core:reader`（T-010 / R-12）：实现需要
 * `Context.filesDir` 来定位应用私有目录，这是唯一的 Android 依赖。
 * 接口本身零 Android 依赖，已随阅读引擎迁入 `:core:reader`；
 * 拆分时**两个文件保持同一包名** `io.github.lunalight.paperread.reader`，
 * 因此所有调用方（`di/AppContainer`、各单测）无需改动 import。
 *
 * 存储行为（与拆分前逐字一致）：
 * - 章节读取按字节偏移 seek，不整本进内存；
 * - [textFile] 不存在或区间长度 &lt;= 0 时返回空串（调用方据 [hasText] 区分错误性质）。
 */
class FileBookTextStore(context: Context) : BookTextStore {

    private val booksRoot: File = File(context.filesDir, "books")

    override fun bookDir(dirName: String): File = File(booksRoot, dirName)

    override fun textFile(dirName: String): File = File(bookDir(dirName), "book.txt")

    override fun sourceFile(book: Book): File =
        File(bookDir(book.dirName), "source.${book.format.extension}")

    override fun readChapter(dirName: String, chapter: Chapter): String {
        val file = textFile(dirName)
        if (!file.exists()) return ""
        val length = (chapter.byteEnd - chapter.byteStart).toInt()
        if (length <= 0) return ""
        return RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(length)
            raf.seek(chapter.byteStart)
            raf.readFully(buffer)
            String(buffer, Charsets.UTF_8)
        }
    }

    override fun hasText(dirName: String): Boolean =
        textFile(dirName).let { it.isFile && it.length() > 0L }

    override fun readSourceText(book: Book, charset: Charset): String =
        sourceFile(book).readText(charset)

    override fun deleteBookDir(dirName: String) {
        bookDir(dirName).deleteRecursively()
    }
}
