package io.github.lunalight.paperread.domain.model

/** 书籍格式。v1 支持 TXT；EPUB 于 M3 接入。 */
enum class BookFormat(val extension: String) {
    TXT("txt"),
    EPUB("epub"),
    ;

    companion object {
        fun fromExtension(ext: String?): BookFormat? = when (ext?.lowercase()) {
            "txt" -> TXT
            "epub" -> EPUB
            else -> null
        }
    }
}
