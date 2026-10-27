package io.github.lunalight.paperread.fakes

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat

/**
 * 测试用书籍构造器（跨包共享的单一实现）。
 *
 * 导入网关测试与备份测试都需要「字段齐全、可直接喂给导出 / 恢复」的 Book；
 * 两处各写一份必然漂移（新增字段时漏改一处 → 断言看似通过、契约已变）。
 */
fun testBook(
    id: Long,
    title: String = "书$id",
    author: String = "作者$id",
    sha256: String = "sha-$id",
    dirName: String = "dir-$id",
    format: BookFormat = BookFormat.TXT,
    lastReadAt: Long? = 100L,
    lastChapterIndex: Int = 2,
    lastParagraphIndex: Int = 3,
    lastCharOffset: Int = 0,
    progressPercent: Float = 0.5f,
    fileSize: Long = 100L,
    totalChars: Long = 1000L,
    chapterCount: Int = 2,
) = Book(
    id = id,
    title = title,
    author = author,
    format = format,
    encoding = "UTF-8",
    sha256 = sha256,
    fileSize = fileSize,
    dirName = dirName,
    totalChars = totalChars,
    chapterCount = chapterCount,
    lastChapterIndex = lastChapterIndex,
    lastParagraphIndex = lastParagraphIndex,
    lastCharOffset = lastCharOffset,
    progressPercent = progressPercent,
    lastReadAt = lastReadAt,
    addedAt = 1L,
)
