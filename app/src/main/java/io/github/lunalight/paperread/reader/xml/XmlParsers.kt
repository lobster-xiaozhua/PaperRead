package io.github.lunalight.paperread.reader.xml

import java.io.InputStream

/**
 * XmlParser 工厂：Android 端走 XmlPullParser，JVM 端走 SAX。
 * EPUB 解析逻辑（EpubParser / XhtmlTextExtractor）只依赖 [XmlParser] 接口。
 *
 * **为什么本文件留在 `:app`（T-010 / R-12 模块拆分）**：它不是 expect/actual，
 * 而是一个普通的 object 工厂，其中 [android] 直接构造 [AndroidXmlParser]，
 * 后者的实现 `android.util.Xml` 来自 Android SDK——本文件因此成为
 * `:core:reader` 无法承载的边界。拆分口径：
 * - [XmlParser] 接口、[JvmXmlParser] 实现与阅读引擎一起去 `:core:reader`；
 * - [android]（生产路径，`BookImporter` 用它解析真实 EPUB）与
 *   [AndroidXmlParser] 留在 `:app`；
 * - [jvm] 保留为对称的 JVM 入口（委托 `:core:reader` 的 [JvmXmlParser]），
 *   供 app 侧测试与调用方在「不关心模块边界」时使用；
 *   引擎内部缺省值已改为 `::JvmXmlParser`，不再反向依赖本对象。
 */
object XmlParsers {

    /** Android 运行环境（EPUB 导入实际路径）。 */
    fun android(input: InputStream): XmlParser = AndroidXmlParser.create(input)

    /** JVM 环境（无 Android 依赖），委托 `:core:reader` 的 SAX 实现。 */
    fun jvm(input: InputStream): XmlParser = JvmXmlParser(input)
}
