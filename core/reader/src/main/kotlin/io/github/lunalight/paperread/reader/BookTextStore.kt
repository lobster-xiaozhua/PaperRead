package io.github.lunalight.paperread.reader

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Chapter
import java.io.File
import java.nio.charset.Charset

/**
 * 书籍文本存取：按字节偏移懒加载章节。
 *
 * 目录约定：filesDir/books/<dirName>/ 下
 * - book.txt：标准化 UTF-8 正文（章节按 byteStart/byteEnd 定位）
 * - source.<ext>：导入时的原始文件（保留用于重新解析 / 切换编码）
 *
 * 本接口**零 Android 依赖**（只用 java.io.File + 领域模型），住在 `:core:reader`
 * 纯 JVM 模块里，故阅读引擎可在无 Robolectric 的环境下测它的全部消费方。
 *
 * UI 层只依赖本接口（单测以内存 fake 替代）；文件系统实现 `FileBookTextStore`
 * 需要 `Context.filesDir`，因此留在 `:app` 模块（T-010 拆分时按依赖边界切出，
 * 包名保持一致，调用方无感）。
 */
interface BookTextStore {

    fun bookDir(dirName: String): File

    fun textFile(dirName: String): File

    fun sourceFile(book: Book): File

    /** 读取单章文本（字节偏移定位 + UTF-8 解码）。 */
    fun readChapter(dirName: String, chapter: Chapter): String

    /**
     * 正文文件是否存在且非空。
     *
     * [readChapter] 在「文件丢失」与「该章本身为空」两种情况下都返回空串，
     * 调用方据此区分错误性质：前者只能删条目重导，后者可重新解析章节。
     */
    fun hasText(dirName: String): Boolean

    /** 读取原始文件全文（重新解析场景）。 */
    fun readSourceText(book: Book, charset: Charset): String

    fun deleteBookDir(dirName: String)
}
