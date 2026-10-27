package io.github.lunalight.paperread.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 阅读设置存储（UI 层只依赖本接口，单测以 fake 替代）。
 */
interface SettingsStore {

    val settings: Flow<ReaderSettings>

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings)
}

/**
 * [SettingsStore] 的 DataStore Preferences 实现。
 *
 * 接收已装配好的 [DataStore]（由 [SettingsDataStoreProvider] 按 Application 托管），
 * 本类只负责「Preferences ↔ [ReaderSettings]」的映射。
 *
 * 曾经这里是 `PreferencesSettingsStore(context: Context)` + 文件级
 * `by preferencesDataStore(...)` 委托：委托对象把实例缓存为进程级单例并忽略后续
 * Context，导致 Robolectric 每个测试类的独立 Application 共用第一个测试类的
 * 已销毁目录 / 作用域，`settings` 流不再发射。改为构造注入后，实例生命周期
 * 由持有者（`AppContainer`，随 Application 创建）决定，真机行为等价单例。
 */
class PreferencesSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    override val settings: Flow<ReaderSettings> = dataStore.data.map { prefs ->
        val defaults = ReaderSettings()
        ReaderSettings(
            themeId = prefs[KEY_THEME] ?: defaults.themeId,
            fontFamily = prefs[KEY_FONT_FAMILY] ?: defaults.fontFamily,
            customFontId = prefs[KEY_CUSTOM_FONT],
            fontSizeSp = prefs[KEY_FONT_SIZE] ?: defaults.fontSizeSp,
            lineHeightRatio = prefs[KEY_LINE_HEIGHT] ?: defaults.lineHeightRatio,
            paragraphSpacingDp = prefs[KEY_PARAGRAPH_SPACING] ?: defaults.paragraphSpacingDp,
            horizontalMarginDp = prefs[KEY_MARGIN] ?: defaults.horizontalMarginDp,
            brightness = prefs[KEY_BRIGHTNESS] ?: defaults.brightness,
            keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            immersive = prefs[KEY_IMMERSIVE] ?: defaults.immersive,
            volumeKeyTurnPage = prefs[KEY_VOLUME_TURN] ?: defaults.volumeKeyTurnPage,
            pageTurnMode = prefs[KEY_PAGE_TURN] ?: defaults.pageTurnMode,
            twoPageSpread = prefs[KEY_TWO_PAGE] ?: defaults.twoPageSpread,
            ttsRate = prefs[KEY_TTS_RATE] ?: defaults.ttsRate,
            ttsPitch = prefs[KEY_TTS_PITCH] ?: defaults.ttsPitch,
            ttsAutoNextChapter = prefs[KEY_TTS_AUTO_NEXT] ?: defaults.ttsAutoNextChapter,
        )
    }

    override suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        dataStore.edit { prefs ->
            val current = ReaderSettings(
                themeId = prefs[KEY_THEME] ?: ReaderSettings().themeId,
                fontFamily = prefs[KEY_FONT_FAMILY] ?: ReaderSettings().fontFamily,
                customFontId = prefs[KEY_CUSTOM_FONT],
                fontSizeSp = prefs[KEY_FONT_SIZE] ?: ReaderSettings().fontSizeSp,
                lineHeightRatio = prefs[KEY_LINE_HEIGHT] ?: ReaderSettings().lineHeightRatio,
                paragraphSpacingDp = prefs[KEY_PARAGRAPH_SPACING] ?: ReaderSettings().paragraphSpacingDp,
                horizontalMarginDp = prefs[KEY_MARGIN] ?: ReaderSettings().horizontalMarginDp,
                brightness = prefs[KEY_BRIGHTNESS] ?: ReaderSettings().brightness,
                keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: ReaderSettings().keepScreenOn,
                immersive = prefs[KEY_IMMERSIVE] ?: ReaderSettings().immersive,
                volumeKeyTurnPage = prefs[KEY_VOLUME_TURN] ?: ReaderSettings().volumeKeyTurnPage,
                pageTurnMode = prefs[KEY_PAGE_TURN] ?: ReaderSettings().pageTurnMode,
                twoPageSpread = prefs[KEY_TWO_PAGE] ?: ReaderSettings().twoPageSpread,
                ttsRate = prefs[KEY_TTS_RATE] ?: ReaderSettings().ttsRate,
                ttsPitch = prefs[KEY_TTS_PITCH] ?: ReaderSettings().ttsPitch,
                ttsAutoNextChapter = prefs[KEY_TTS_AUTO_NEXT] ?: ReaderSettings().ttsAutoNextChapter,
            )
            val next = transform(current)
            prefs[KEY_THEME] = next.themeId
            prefs[KEY_FONT_FAMILY] = next.fontFamily
            // 自定义字体 id 为 null 时必须**移除键**而不是写空串：
            // 「没有这个键」是 AC-16.6 回退内置字族的唯一判据
            if (next.customFontId != null) {
                prefs[KEY_CUSTOM_FONT] = next.customFontId
            } else {
                prefs.remove(KEY_CUSTOM_FONT)
            }
            prefs[KEY_FONT_SIZE] = next.fontSizeSp
            prefs[KEY_LINE_HEIGHT] = next.lineHeightRatio
            prefs[KEY_PARAGRAPH_SPACING] = next.paragraphSpacingDp
            prefs[KEY_MARGIN] = next.horizontalMarginDp
            prefs[KEY_BRIGHTNESS] = next.brightness
            prefs[KEY_KEEP_SCREEN_ON] = next.keepScreenOn
            prefs[KEY_IMMERSIVE] = next.immersive
            prefs[KEY_VOLUME_TURN] = next.volumeKeyTurnPage
            prefs[KEY_PAGE_TURN] = next.pageTurnMode
            prefs[KEY_TWO_PAGE] = next.twoPageSpread
            prefs[KEY_TTS_RATE] = next.ttsRate
            prefs[KEY_TTS_PITCH] = next.ttsPitch
            prefs[KEY_TTS_AUTO_NEXT] = next.ttsAutoNextChapter
        }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_id")
        val KEY_FONT_FAMILY = stringPreferencesKey("font_family")
        val KEY_CUSTOM_FONT = stringPreferencesKey("custom_font_id")
        val KEY_FONT_SIZE = intPreferencesKey("font_size_sp")
        val KEY_LINE_HEIGHT = floatPreferencesKey("line_height_ratio")
        val KEY_PARAGRAPH_SPACING = intPreferencesKey("paragraph_spacing_dp")
        val KEY_MARGIN = intPreferencesKey("horizontal_margin_dp")
        val KEY_BRIGHTNESS = floatPreferencesKey("brightness")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_IMMERSIVE = booleanPreferencesKey("immersive")
        val KEY_VOLUME_TURN = booleanPreferencesKey("volume_key_turn_page")
        val KEY_PAGE_TURN = booleanPreferencesKey("page_turn_mode")
        val KEY_TWO_PAGE = booleanPreferencesKey("two_page_spread")
        val KEY_TTS_RATE = floatPreferencesKey("tts_rate")
        val KEY_TTS_PITCH = floatPreferencesKey("tts_pitch")
        val KEY_TTS_AUTO_NEXT = booleanPreferencesKey("tts_auto_next_chapter")
    }
}
