package io.github.lunalight.paperread.reader.xml

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

/**
 * Android 端 XmlParser 实现：包装平台 XmlPullParser。
 *
 * 由 [newXmlParser] 工厂创建，调用方无需直接接触 XmlPullParser。
 */
class AndroidXmlParser private constructor(private val parser: XmlPullParser) : XmlParser {

    override fun next(): XmlEvent? {
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_DOCUMENT -> return XmlEvent.StartDocument
                XmlPullParser.START_TAG -> return XmlEvent.StartElement(
                    name = parser.name,
                    attributes = buildMap {
                        for (i in 0 until parser.attributeCount) {
                            put(parser.getAttributeName(i), parser.getAttributeValue(i))
                        }
                    },
                )
                XmlPullParser.END_TAG -> return XmlEvent.EndElement(parser.name)
                XmlPullParser.TEXT -> return XmlEvent.Text(parser.text)
                XmlPullParser.END_DOCUMENT -> return null
                // CDATA / 注释 / 处理指令等对 EPUB 解析无意义，跳过
            }
        }
    }

    companion object {
        /** 从输入流创建（编码由 XML 声明或 UTF-8 默认决定）。 */
        @Throws(XmlPullParserException::class, IOException::class)
        fun create(input: InputStream): AndroidXmlParser {
            val parser = Xml.newPullParser()
            // 必须开启命名空间处理：OPF 的 dc:title 等前缀元素才能返回本地名（与 SAX localName 一致）
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(input, null)
            return AndroidXmlParser(parser)
        }
    }
}
