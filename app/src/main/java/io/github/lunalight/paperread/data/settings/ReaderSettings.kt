package io.github.lunalight.paperread.data.settings

/**
 * 阅读设置（与 [io.github.lunalight.paperread.ui.theme.ReaderTheme] 的 themeId 对应）。
 *
 * 默认值即「开箱可读」：纸白主题、衬线 18sp、行距 1.7、段距 12dp、边距 20dp、
 * 亮度跟随系统、不常亮、非沉浸、音量键翻章关闭（防误触）、连续滚动（翻页模式关闭）。
 *
 * 朗读（TTS）默认值：正常语速音调、章尾自动续读开——「听书」从第一章开始
 * 不间断读到末章是默认预期（用户 2026-10-19 拍板）。
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
    /**
     * 翻页模式：true 用横向滑动翻页（[io.github.lunalight.paperread.ui.reader] 的
     * HorizontalPager 分页），false 用连续滚动（LazyColumn）。默认关闭，滚动为开箱体验。
     */
    val pageTurnMode: Boolean = false,
    /** 朗读语速：0.5 ~ 2.0，1.0 为系统默认。 */
    val ttsRate: Float = 1.0f,
    /** 朗读音调：0.5 ~ 2.0，1.0 为系统默认。 */
    val ttsPitch: Float = 1.0f,
    /** 一章读完后自动开始下一章。 */
    val ttsAutoNextChapter: Boolean = true,
) {
    val brightnessFollowsSystem: Boolean get() = brightness < 0f
}
