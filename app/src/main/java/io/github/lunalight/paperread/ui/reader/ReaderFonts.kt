package io.github.lunalight.paperread.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.reader.font.TtfMetadata
import java.io.File
import java.io.FileInputStream

/**
 * 正文字族的解析与降级（T-018 · AC-16.4 / AC-16.6）。
 *
 * 一条规则：**任何意外都回退内置字族，绝不把异常抛到渲染路径上**。
 * 「文件缺失 / 解析失败 / 字体容器不认识」三种情况在用户看来是同一件事——
 * 正文照常显示，只是没用上自定义字体。所以这里既不弹提示也不崩溃，
 * 只做一次廉价的判定（存在 + 前 4 字节魔数 + 构造 FontFamily 不抛）。
 *
 * 为什么在解析期再验一次魔数（导入时已经验过）：`filesDir/fonts/` 里的文件
 * 可能被外部因素破坏（磁盘损坏、调试时手工替换、系统清理），而
 * `FontFamily(Font(file))` 只是**登记**一个文件，错误要等到真正排版时才由
 * 字体引擎暴露——那时已经来不及优雅降级了。提前 4 字节探测把这类文件
 * 拦在组合期。
 *
 * @param fontFamily 内置字族标识（serif / sans）
 * @param customFontId 偏好里的自定义字体 id；null = 用内置
 * @param resolveFile id → 文件（不存在返回 null）
 * @return 实际用于正文的 [FontFamily]
 */
internal fun readerFontFamily(
    fontFamily: String,
    customFontId: String?,
    resolveFile: (String) -> File?,
): FontFamily {
    val fallback = builtInFontFamily(fontFamily)
    val id = customFontId?.takeIf { it.isNotBlank() } ?: return fallback
    val file = runCatching { resolveFile(id) }.getOrNull() ?: return fallback
    if (!file.isFile || file.length() <= 0L) return fallback // 文件被删 / 空文件 → 静默回退
    if (!looksLikeFont(file)) return fallback // 内容已损坏 / 被替换 → 静默回退
    return runCatching { FontFamily(Font(file)) }.getOrDefault(fallback)
}

/** 内置字族：与历史行为完全一致（serif → Serif，其余 → SansSerif）。 */
internal fun builtInFontFamily(fontFamily: String): FontFamily =
    if (fontFamily == "serif") FontFamily.Serif else FontFamily.SansSerif

/**
 * 组合期取当前正文字族。
 *
 * 只读 4 字节做魔数探测（不是整个文件），并按「id + 内置字族」记忆——
 * 设置变了才重新解析，滚动 / 翻页不会每帧碰磁盘。
 */
@Composable
internal fun rememberReaderFontFamily(settings: ReaderSettings): FontFamily {
    val context = LocalContext.current
    return remember(settings.customFontId, settings.fontFamily) {
        val store = (context.applicationContext as? PaperReadApp)?.container?.customFontStore
        readerFontFamily(
            fontFamily = settings.fontFamily,
            customFontId = settings.customFontId,
            resolveFile = { id -> store?.resolve(id) },
        )
    }
}

/** 前 4 字节是否 sfnt 魔数（读不出来一律当作「不是字体」）。 */
private fun looksLikeFont(file: File): Boolean = runCatching {
    val header = ByteArray(4)
    val read = FileInputStream(file).use { it.read(header) }
    read == header.size && TtfMetadata.sniff(header) != null
}.getOrDefault(false)
