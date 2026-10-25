package io.github.lunalight.paperread.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.window.layout.WindowMetricsCalculator

/**
 * 当前窗口宽度档位（自定义枚举，按 Material 3 标准断点划分）：
 * Compact < 600dp ≤ Medium < 840dp ≤ Expanded。
 *
 * 使用自有枚举而非 [androidx.window] 的 WindowSizeClass：后者在
 * androidx.window:window 1.3.0 主 artifact 中不存在（位于 window-core），
 * 按「不新增依赖」铁律以三行断点映射等价实现，断点数值与官方一致。
 */
enum class WindowWidthClass {
    /** < 600dp：手机竖屏，单栏。 */
    COMPACT,

    /** 600dp ~ 840dp：手机横屏 / 小平板，仍默认单栏。 */
    MEDIUM,

    /** ≥ 840dp：平板 / 横屏大屏，允许双页跨页。 */
    EXPANDED,
}

/**
 * 当前窗口宽度档位 [WindowWidthClass] 的 CompositionLocal。
 *
 * 在导航根下发给各屏，避免逐层透传；未提供时回退 COMPACT（保守单栏）。
 */
val LocalWindowWidthClass = compositionLocalOf { WindowWidthClass.COMPACT }

/**
 * 计算并记忆当前窗口宽度档位。
 *
 * 以 [LocalConfiguration] 为 key：本应用主 Activity 未锁定方向（旋屏重建），
 * 重建时组合整体重启会自然重算；多窗口缩放等不重建场景也靠此 key 触发刷新。
 *
 * 用 [WindowMetricsCalculator] 取真实窗口宽度（覆盖多窗口、折叠屏、挖孔安全区），
 * 比直接读 [LocalConfiguration.screenWidthDp] 更准。
 */
@Composable
fun rememberWindowWidthClass(): WindowWidthClass {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    return remember(configuration, density) {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(context)
        val widthDp = with(density) { metrics.bounds.width().toFloat().toDp().value.toInt() }
        when {
            widthDp < 600 -> WindowWidthClass.COMPACT
            widthDp < 840 -> WindowWidthClass.MEDIUM
            else -> WindowWidthClass.EXPANDED
        }
    }
}
