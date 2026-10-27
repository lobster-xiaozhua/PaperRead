package io.github.lunalight.paperread.domain.model

/**
 * 书籍元数据（不含章节列表）。
 *
 * 偏移约定：
 * - 字符偏移（charStart/charEnd/charOffset）为 UTF-16 code unit，用于进度计算；
 * - 字节偏移（byteStart/byteEnd）为 book.txt 的 UTF-8 字节位置，用于文件读取。
 * 两者由 [io.github.lunalight.paperread.reader.txt.ChapterIndexWriter] 同步记录。
 */
data class Book(
    val id: Long = 0,
    val title: String,
    val author: String,
    val format: BookFormat,
    val encoding: String,
    val sha256: String,
    val fileSize: Long,
    /** 书籍私有目录名（filesDir/books/<dirName>）。 */
    val dirName: String,
    val totalChars: Long,
    val chapterCount: Int,
    val lastChapterIndex: Int = 0,
    val lastParagraphIndex: Int = 0,
    val lastScrollOffset: Int = 0,
    val lastCharOffset: Int = 0,
    val progressPercent: Float = 0f,
    val lastReadAt: Long? = null,
    val addedAt: Long,
) {
    val readerPosition: ReaderPosition
        get() = ReaderPosition(lastChapterIndex, lastParagraphIndex, lastScrollOffset, lastCharOffset)
}
