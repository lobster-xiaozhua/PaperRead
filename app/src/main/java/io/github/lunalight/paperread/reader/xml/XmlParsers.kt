package io.github.lunalight.paperread.reader.xml

import java.io.InputStream

/**
 * XmlParser 工厂：Android 端走 XmlPullParser，JVM 测试端走 SAX。
 * EPUB 解析逻辑（EpubParser / XhtmlTextExtractor）只依赖 [XmlParser] 接口。
 */
object XmlParsers {

    /** Android 运行环境（EPUB 导入实际路径）。 */
    fun android(input: InputStream): XmlParser = AndroidXmlParser.create(input)

    /** JVM 测试环境（无 Android 依赖）。 */
    fun jvm(input: InputStream): XmlParser = JvmXmlParser(input)
}
