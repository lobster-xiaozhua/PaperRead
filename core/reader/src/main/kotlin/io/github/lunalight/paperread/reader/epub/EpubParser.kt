package io.github.lunalight.paperread.reader.epub

import io.github.lunalight.paperread.reader.txt.ChapterRef
import io.github.lunalight.paperread.reader.xml.JvmXmlParser
import io.github.lunalight.paperread.reader.xml.XmlEvent
import io.github.lunalight.paperread.reader.xml.XmlParser
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB 解析器（EPUB2 / EPUB3 OEBPS 结构）：
 *
 * 1. META-INF/container.xml → OPF 路径；
 * 2. OPF：metadata（标题/作者）、manifest（id→href）、spine（阅读顺序）；
 * 3. NCX（EPUB3 无 NCX 时退化为正文首行标题）；
 * 4. META-INF/encryption.xml DRM 检测（加密正文条目 → 拒绝导入）；
 * 5. ZIP 条目名编码兜底（GBK 条目名自动重开）；
 * 6. 按 spine 顺序提取 XHTML 正文，拼装全文 + 章节区间。
 *
 * 产出 [EpubContent] 与 TXT 路径对齐，交给 ChapterIndexWriter 生成双偏移索引。
 * 纯 JVM 可测（依赖 [XmlParser] 接口而非 Android API）。
 *
 * @param parserFactory XML 解析器工厂。**缺省为 JVM 端 SAX 实现** [JvmXmlParser]
 *   ——`:core:reader` 是纯 JVM 模块，不得反向依赖 Android 侧的 XmlPullParser 工厂；
 *   Android 运行时由 `:app` 的 `BookImporter` 显式传入 `XmlParsers::android`。
 */
class EpubParser(
    private val parserFactory: ParserFactory = ::JvmXmlParser,
) {

    /** @param epubFile 已落盘的 EPUB 文件（ZipFile 随机访问，不全量进内存）。 */
    fun parse(epubFile: File): EpubContent {
        val zip = openZip(epubFile)
        zip.use {
            checkEncryption(it)
            val opfPath = readOpfPath(it)
            val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""
            val opf = parseOpf(readEntry(it, opfPath), parserFactory)
            val ncxTitles = readNcxTitles(it, opf, opfDir)

            val chapters = ArrayList<ParsedChapter>()
            for ((order, idref) in opf.spine.withIndex()) {
                val item = opf.manifest[idref] ?: continue
                if (item.mediaType != null && !item.mediaType.contains("xhtml") && !item.mediaType.contains("html")) {
                    continue // 跳过图片等非文档条目
                }
                val path = normalizePath(opfDir, item.href) ?: continue
                val entry = it.getEntry(path) ?: continue
                if (entry.isDirectory) continue
                val text = XhtmlTextExtractor().extract(it.getInputStream(entry), parserFactory)
                if (text.isBlank()) continue
                val title = ncxTitles[path.substringAfterLast('/')]
                    ?: ncxTitles[item.href.substringAfterLast('/')]
                    ?: text.lineSequence().first().take(MAX_TITLE_CHARS)
                    ?: "第 ${order + 1} 章"
                chapters.add(ParsedChapter(title.ifBlank { "第 ${order + 1} 章" }, text))
            }
            if (chapters.isEmpty()) throw EpubFormatException("EPUB 中未找到可阅读的正文")
            return assemble(chapters, opf)
        }
    }

    // ---- 内部结构 ----

    private data class ManifestItem(val href: String, val mediaType: String?)

    private data class ParsedChapter(val title: String, val text: String)

    private data class Opf(
        val title: String,
        val author: String,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val ncxHref: String?,
    )

    private fun openZip(file: File): ZipFile = try {
        ZipFile(file, Charsets.UTF_8)
    } catch (e: java.util.zip.ZipException) {
        // 条目名含非 UTF-8 字节（国产 EPUB 常见 GBK 条目名）：
        // ZipFile 以 UTF-8 打开时会因任一条目名无法严格解码而整体失败，
        // 此时按 GB18030 重开；带 UTF-8 标志位的条目 JDK 仍按 UTF-8 解释，混合 ZIP 亦正确。
        ZipFile(file, Charset.forName("GB18030"))
    }

    private fun readEntry(zip: ZipFile, path: String): ByteArray {
        val entry = zip.getEntry(path) ?: throw EpubFormatException("EPUB 缺少 $path")
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun checkEncryption(zip: ZipFile) {
        val entry: ZipEntry = zip.getEntry("META-INF/encryption.xml") ?: return
        val encrypted = HashSet<String>()
        val parser = parserFactory(zip.getInputStream(entry))
        var inEncryptedData = false
        var currentUri: String? = null
        while (true) {
            when (val event = parser.next()) {
                null -> break
                is XmlEvent.StartElement -> when (event.name) {
                    "EncryptedData" -> {
                        inEncryptedData = true
                        currentUri = null
                    }

                    "CipherReference" -> if (inEncryptedData) {
                        currentUri = event.attributes["URI"]
                    }
                }

                is XmlEvent.EndElement -> if (event.name == "EncryptedData") {
                    currentUri?.let { encrypted.add(it) }
                    inEncryptedData = false
                }

                else -> Unit
            }
        }
        if (encrypted.isNotEmpty()) {
            throw EpubEncryptedException(
                "该 EPUB 含 DRM 加密内容（${encrypted.size} 个条目），无法解密阅读",
            )
        }
    }

    private fun readOpfPath(zip: ZipFile): String {
        val bytes = readEntry(zip, "META-INF/container.xml")
        val parser = parserFactory(bytes.inputStream())
        while (true) {
            when (val event = parser.next()) {
                null -> throw EpubFormatException("container.xml 无 rootfile")
                is XmlEvent.StartElement -> if (event.name == "rootfile") {
                    val path = event.attributes["full-path"]
                    if (!path.isNullOrBlank()) return path
                }

                else -> Unit
            }
        }
        throw EpubFormatException("container.xml 无 rootfile")
    }

    private fun parseOpf(bytes: ByteArray, factory: ParserFactory): Opf {
        val parser = factory(bytes.inputStream())
        val manifest = HashMap<String, ManifestItem>()
        val spine = ArrayList<String>()
        var title = ""
        var author = ""
        var ncxHref: String? = null
        var inMetadata = false
        var currentTag: String? = null

        while (true) {
            when (val event = parser.next()) {
                null -> break
                is XmlEvent.StartElement -> when (event.name) {
                    "metadata" -> inMetadata = true
                    "title", "creator" -> if (inMetadata) currentTag = event.name
                    "item" -> {
                        val id = event.attributes["id"]
                        val href = event.attributes["href"]
                        if (id != null && href != null) {
                            manifest[id] = ManifestItem(href, event.attributes["media-type"])
                        }
                    }

                    "itemref" -> event.attributes["idref"]?.let { spine.add(it) }
                    "spine" -> event.attributes["toc"]?.let { ncxHref = manifest[it]?.href }
                }

                is XmlEvent.Text -> if (inMetadata) {
                    when (currentTag) {
                        "title" -> if (title.isEmpty()) title = event.content.trim()
                        "creator" -> if (author.isEmpty()) author = event.content.trim()
                    }
                }

                is XmlEvent.EndElement -> when (event.name) {
                    "metadata" -> inMetadata = false
                    "title", "creator" -> currentTag = null
                }

                else -> Unit
            }
        }
        if (manifest.isEmpty()) throw EpubFormatException("OPF 无 manifest")
        return Opf(
            title = title.ifBlank { "未命名书籍" },
            author = author.ifBlank { "未知" },
            manifest = manifest,
            spine = spine,
            ncxHref = ncxHref,
        )
    }

    /** 解析 NCX，返回「条目名 → 章节标题」。 */
    private fun readNcxTitles(zip: ZipFile, opf: Opf, opfDir: String): Map<String, String> {
        val ncxPath = opf.ncxHref?.let { normalizePath(opfDir, it) } ?: return emptyMap()
        val entry = zip.getEntry(ncxPath) ?: return emptyMap()
        val parser = parserFactory(zip.getInputStream(entry))
        val titles = HashMap<String, String>()
        var inNavLabel = false
        var label = StringBuilder()
        var currentLabel = ""

        while (true) {
            when (val event = parser.next()) {
                null -> break
                is XmlEvent.StartElement -> when (event.name) {
                    "navLabel" -> {
                        inNavLabel = true
                        label.clear()
                    }

                    // NCX 结构：<navLabel><text>标题</text></navLabel><content src="..."/>
                    // content 出现在 navLabel 之后，此时 currentLabel 已就绪
                    "content" -> {
                        val src = event.attributes["src"]
                        if (src != null && currentLabel.isNotEmpty()) {
                            titles[stripFragment(src).substringAfterLast('/')] = currentLabel
                        }
                    }
                }

                is XmlEvent.Text -> if (inNavLabel) label.append(event.content)

                is XmlEvent.EndElement -> when (event.name) {
                    "navLabel" -> {
                        inNavLabel = false
                        currentLabel = label.toString().trim()
                    }

                    "navPoint" -> currentLabel = ""
                }

                else -> Unit
            }
        }
        return titles
    }

    /** 拼装全文与章节区间（区间连续覆盖全文，可直写 ChapterIndexWriter）。 */
    private fun assemble(chapters: List<ParsedChapter>, opf: Opf): EpubContent {
        val sb = StringBuilder()
        val refs = ArrayList<ChapterRef>()
        chapters.forEachIndexed { index, chapter ->
            val start = sb.length
            sb.append(chapter.text)
            // 章间分隔符归入前一章区间，保证 refs 连续覆盖全文
            if (index != chapters.lastIndex) sb.append('\n')
            refs.add(ChapterRef(index, chapter.title, start, sb.length))
        }
        return EpubContent(
            title = opf.title,
            author = opf.author,
            refs = refs,
            fullText = sb.toString(),
        )
    }

    private fun stripFragment(href: String): String = href.substringBefore('#')

    /** 把相对 OPF 目录的 href 规范化为 ZIP 条目路径；绝对 URL 返回 null。 */
    private fun normalizePath(baseDir: String, href: String): String? {
        val clean = stripFragment(href.trim())
        if (clean.isEmpty()) return null
        if (clean.startsWith("http://") || clean.startsWith("https://")) return null
        val combined = if (baseDir.isEmpty()) clean else "$baseDir/$clean"
        val out = ArrayList<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        return out.joinToString("/")
    }

    private companion object {
        const val MAX_TITLE_CHARS = 30
    }
}
