package io.github.lunalight.paperread.data.settings

/**
 * 阅读设置（与 [io.github.lunalight.paperread.ui.theme.ReaderTheme] 的 themeId 对应）。
 *
 * 默认值即「开箱可读」：纸白主题、衬线 18sp、行距 1.7、段距 12dp、边距 20dp、
 * 亮度跟随系统、不常亮、非沉浸、音量键翻章关闭（防误触）。
 */
data class ReaderSettings(
    val themeId: String = "paper",
    val fontFamily: String = "serif",
    val fontSizeSp: Int = 18,
    val lineHeightRatio: Float = 1.7f,
    val paragraphSpacingDp: Int = 12,
    val horizontalMarginDp: Int = 20,
    /** 屏幕亮度：-1 = 跟随系统；否则 0.05 ~ 1.0。 */
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = false,
    val immersive: Boolean = false,
    val volumeKeyTurnPage: Boolean = false,
) {
    val brightnessFollowsSystem: Boolean get() = brightness < 0f
}
