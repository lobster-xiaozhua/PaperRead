package io.github.lunalight.paperread.ui.reader

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lunalight.paperread.speech.SpeechService

/**
 * 阅读器的系统级副作用（与渲染树无关的「对 Window / 前台服务动手」）。
 *
 * 归属：亮度覆盖 / 常亮 / 沉浸 / 系统栏图标明暗 / 朗读前台服务生命周期。
 *
 * 本文件刻意**不**登记为「零覆盖纯渲染类」白名单：这些函数虽是 `@Composable`，
 * 但内容是窗口与服务状态机（非布局指令），属于应当被单测/行为测试度量的逻辑，
 * 因此如实留在覆盖率分母内计账（见 `app/build.gradle.kts` 的白名单维护约定）。
 */

/** 亮度覆盖：-1 跟随系统；退出阅读器时恢复。 */
@Composable
internal fun BrightnessEffect(brightness: Float) {
    val view = LocalView.current
    DisposableEffect(brightness) {
        val activity = view.context as? Activity
        val window = activity?.window
        val layoutParams = window?.attributes
        val previous = layoutParams?.screenBrightness ?: Float.NaN
        if (window != null && layoutParams != null) {
            layoutParams.screenBrightness =
                if (brightness < 0f) {
                    android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                } else {
                    brightness
                }
            window.attributes = layoutParams
        }
        onDispose {
            if (window != null && layoutParams != null && !previous.isNaN()) {
                layoutParams.screenBrightness = previous
                window.attributes = layoutParams
            }
        }
    }
}

/** 阅读时常亮。 */
@Composable
internal fun KeepScreenOnEffect(keepScreenOn: Boolean) {
    val view = LocalView.current
    SideEffect { view.keepScreenOn = keepScreenOn }
}

/**
 * 系统栏图标明暗跟随阅读背景。
 *
 * `enableEdgeToEdge()` 只按系统深浅色决定图标颜色，而阅读区配色与 Material 主题
 * 完全解耦（5 套预设在任何系统主题下都一致）：系统浅色 + 「墨黑」阅读主题时，
 * 深色图标压在深色背景上等于消失。这里以阅读背景为唯一依据，
 * 离开阅读器时按系统深浅色还原，避免污染书架页。
 */
@Composable
internal fun SystemBarIconEffect(darkBackground: Boolean) {
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    DisposableEffect(darkBackground, systemDark) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = !darkBackground
            controller.isAppearanceLightNavigationBars = !darkBackground
        }
        onDispose {
            val w = (view.context as? Activity)?.window
            if (w != null) {
                val controller = WindowInsetsControllerCompat(w, view)
                controller.isAppearanceLightStatusBars = !systemDark
                controller.isAppearanceLightNavigationBars = !systemDark
            }
        }
    }
}

/** 沉浸模式：隐藏系统栏，工具栏显示时临时唤出。 */
@Composable
internal fun ImmersiveEffect(enabled: Boolean, chromeVisible: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, chromeVisible) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled && !chromeVisible) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val w = (view.context as? Activity)?.window
            if (w != null) {
                WindowInsetsControllerCompat(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/**
 * 朗读前台服务生命周期跟随朗读状态（后台常驻播放，用户 2026-10-19 拍板）：
 * 开始朗读即保活，停止 / 退出阅读器即收尾。
 *
 * 放在本文件而非渲染层：`SpeechService.start/stop` 是系统服务副作用，
 * 与正文排版无关；也便于后续以 Robolectric 单测直接断言 start/stop 被调用。
 */
@Composable
internal fun SpeechServiceLifecycleEffect(isActive: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(isActive) {
        if (isActive) {
            SpeechService.start(context)
        } else {
            SpeechService.stop(context)
        }
    }
}
