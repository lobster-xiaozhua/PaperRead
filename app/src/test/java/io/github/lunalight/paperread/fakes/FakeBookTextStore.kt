package io.github.lunalight.paperread.fakes

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.reader.BookTextStore
import java.io.File
import java.nio.charset.Charset

/**
 * [BookTextStore] 的内存 fake：readChapter 由 [chapterText] 提供，
 * 目录删除被记录；File 返回临时路径（测试不触盘）。
 */
class FakeBookTextStore : BookTextStore {

    /** 按 (dirName, chapter) 提供章节文本，默认空串。 */
    var chapterText: (String, Chapter) -> String = { _, _ -> "" }

    /** 读取调用记录（断言恢复/切章时读取了哪一章）。 */
    val readChapterCalls = mutableListOf<Pair<String, Chapter>>()

    val deletedDirs = mutableListOf<String>()

    /** 提供的 book.txt 内容（readSourceText 场景，默认空串）。 */
    var sourceText: String = ""

    /** 正文文件是否被认为存在（默认 true；置 false 模拟云备份恢复后的空壳书）。 */
    var textAvailable: Boolean = true

    override fun bookDir(dirName: String): File = File("/tmp/fake-books/$dirName")

    override fun textFile(dirName: String): File = File(bookDir(dirName), "book.txt")

    override fun sourceFile(book: Book): File = File(bookDir(book.dirName), "source.${book.format.extension}")

    override fun readChapter(dirName: String, chapter: Chapter): String {
        readChapterCalls += dirName to chapter
        return chapterText(dirName, chapter)
    }

    override fun hasText(dirName: String): Boolean = textAvailable

    override fun readSourceText(book: Book, charset: Charset): String = sourceText

    override fun deleteBookDir(dirName: String) {
        deletedDirs += dirName
    }
}
