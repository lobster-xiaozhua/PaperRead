package io.github.lunalight.paperread.reader.xml

import java.io.InputStream

/**
 * XmlParser 工厂：Android 端走 XmlPullParser，JVM 端走 SAX。
 * EPUB 解析逻辑（EpubParser / XhtmlTextExtractor）只依赖 [XmlParser] 接口。
 *
 * **为什么本文件最终落在 `:core:data`（T-010 / R-12 拆分 + T-013 修正）**：
 * 它不是 expect/actual，而是一个普通的 object 工厂，其中 [android] 直接构造
 * [AndroidXmlParser]，后者的实现 `android.util.Xml` 来自 Android SDK——
 * `:core:reader` 无法承载。T-010 时曾与 [AndroidXmlParser] 一起留在 `:app`；
 * T-013 把数据层（含 EPUB 解析器 [io.github.lunalight.paperread.data.repository.EpubBookParser]，
 * 即 [android] 的**唯一生产消费方**）抽成 Android library `:core:data` 后，
 * 本文件随消费方同迁——留 `:app` 会造成 data 层反向依赖 app（不可编译），
 * 注入改造则属行为变更。拆分口径：
 * - [XmlParser] 接口与 [JvmXmlParser] 实现在 `:core:reader`（随引擎）；
 * - [android] / [AndroidXmlParser]（生产路径）随数据层落 `:core:data`；
 * - [jvm] 保留为对称的 JVM 入口（委托 `:core:reader` 的 [JvmXmlParser]），
 *   引擎内部缺省值为 `::JvmXmlParser`，不依赖本对象。
 */
object XmlParsers {

    /** Android 运行环境（EPUB 导入实际路径）。 */
    fun android(input: InputStream): XmlParser = AndroidXmlParser.create(input)

    /** JVM 环境（无 Android 依赖），委托 `:core:reader` 的 SAX 实现。 */
    fun jvm(input: InputStream): XmlParser = JvmXmlParser(input)
}
