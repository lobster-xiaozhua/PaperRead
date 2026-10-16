package io.github.lunalight.paperread.reader

import android.content.Context
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Chapter
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * 书籍文本存取：按字节偏移懒加载章节。
 *
 * 目录约定：filesDir/books/<dirName>/ 下
 * - book.txt：标准化 UTF-8 正文（章节按 byteStart/byteEnd 定位）
 * - source.<ext>：导入时的原始文件（保留用于重新解析 / 切换编码）
 */
class BookTextStore(context: Context) {

    private val booksRoot: File = File(context.filesDir, "books")

    fun bookDir(dirName: String): File = File(booksRoot, dirName)

    fun textFile(dirName: String): File = File(bookDir(dirName), "book.txt")

    fun sourceFile(book: Book): File =
        File(bookDir(book.dirName), "source.${book.format.extension}")

    /** 读取单章文本（字节偏移定位 + UTF-8 解码）。 */
    fun readChapter(dirName: String, chapter: Chapter): String {
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

    /** 读取原始文件全文（重新解析场景）。 */
    fun readSourceText(book: Book, charset: Charset): String =
        sourceFile(book).readText(charset)

    fun deleteBookDir(dirName: String) {
        bookDir(dirName).deleteRecursively()
    }
}
