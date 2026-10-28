package io.github.lunalight.paperread.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource

/**
 * 「待展示文案」的两种来源（T-022 · AC-21.4）。
 *
 * 为什么需要这一层：ViewModel 不能持有 `Context`（模块边界 + 单测不依赖
 * Robolectric 资源），又要发出**本机 UI 文案**——于是状态里携带
 * `@StringRes` id 与参数，由渲染层解析；数据层透传的话术（`ImportError.userMessage`
 * 之类，要参与传输 / 序列化）原样装进 [Plain]，不改一个字。
 *
 * 相等性是 data class 语义：`assertEquals(UiText.Res(R.string.x, "书"), state.message)`
 * 直接可用，单测据此断言「发的是哪条文案」而不必解析 Resources。
 * 文案本身（中文串）是否与资源化前一致，由 `UiCopyMigrationTest` 统一断言。
 */
@Immutable
sealed interface UiText {

    /** 已是成品字符串（数据层话术 / 书名等运行时拼装值）。 */
    data class Plain(val value: String) : UiText

    /** 字符串资源 + 格式化参数，渲染时经 `getString(id, *args)` 解析。 */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /**
     * 多段拼接：用于「可选片段」组成的整句（如迁移汇总）。
     *
     * 之所以不塞进单个 format 串：原实现按条件追加「，已存在 N 本」这类片段，
     * 用一条 format 串表达就必须给缺省片段留空占位符，反而把标点语义搅乱。
     * 每段仍是独立资源，拼出的整句与资源化前逐字一致。
     */
    data class Concat(val parts: List<UiText>) : UiText

    companion object {
        /** 构造 [Res] 的简写：`UiText.res(R.string.x, a, b)`。 */
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        /** 构造 [Plain] 的简写。 */
        fun plain(value: String): UiText = Plain(value)

        /** 按顺序拼接多段文案（空则返回 null，便于「没有文案」的语义）。 */
        fun concat(vararg parts: UiText?): UiText? {
            val present = parts.filterNotNull().filterNot { it is Concat && it.parts.isEmpty() }
            if (present.isEmpty()) return null
            return if (present.size == 1) present.first() else Concat(present)
        }
    }
}

/** 在非 Composable 上下文（Snackbar 协程、Toast）解析文案。 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Plain -> value
    is UiText.Res -> context.getString(id, *args.toTypedArray())
    is UiText.Concat -> parts.joinToString(separator = "") { it.resolve(context) }
}

/** 在 Composable 上下文解析文案（`stringResource` 参与组合，语言切换会重组）。 */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Plain -> value
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    // buildString 是 inline 函数，lambda 内可安全调用 @Composable
    is UiText.Concat -> buildString { parts.forEach { append(it.asString()) } }
}

/** 可空版本：null 保持 null（供 `state.message?.let` 之类的调用点使用）。 */
@Composable
fun UiText?.asStringOrNull(): String? = this?.asString()

/** 可空版本（非 Composable）：null 保持 null。 */
fun UiText?.resolveOrNull(context: Context): String? = this?.resolve(context)
