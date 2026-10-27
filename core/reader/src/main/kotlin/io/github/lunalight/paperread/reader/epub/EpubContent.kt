package io.github.lunalight.paperread.reader.epub

/**
 * EPUB 解析结果：与 TXT 路径对齐的「全文 + 章节区间」。
 *
 * [refs] 区间连续且覆盖 [0, fullText.length]，可直接交给
 * [io.github.lunalight.paperread.reader.txt.ChapterIndexWriter] 生成双偏移索引。
 */
data class EpubContent(
    val title: String,
    val author: String,
    val refs: List<io.github.lunalight.paperread.reader.txt.ChapterRef>,
    val fullText: String,
)

/** EPUB 含 DRM（加密正文），无法解密阅读。 */
class EpubEncryptedException(message: String) : Exception(message)

/** EPUB 结构非法（缺 container.xml / OPF / spine 等）。 */
class EpubFormatException(message: String) : Exception(message)
