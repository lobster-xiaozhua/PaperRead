package io.github.lunalight.paperread.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

/**
 * 阅读设置存储（DataStore Preferences）。
 *
 * 单例语义：同一进程内共享同一 DataStore 实例（by 委托绑定 Context）。
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<ReaderSettings> = dataStore.data.map { prefs ->
        val defaults = ReaderSettings()
        ReaderSettings(
            themeId = prefs[KEY_THEME] ?: defaults.themeId,
            fontFamily = prefs[KEY_FONT_FAMILY] ?: defaults.fontFamily,
            fontSizeSp = prefs[KEY_FONT_SIZE] ?: defaults.fontSizeSp,
            lineHeightRatio = prefs[KEY_LINE_HEIGHT] ?: defaults.lineHeightRatio,
            paragraphSpacingDp = prefs[KEY_PARAGRAPH_SPACING] ?: defaults.paragraphSpacingDp,
            horizontalMarginDp = prefs[KEY_MARGIN] ?: defaults.horizontalMarginDp,
            brightness = prefs[KEY_BRIGHTNESS] ?: defaults.brightness,
            keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            immersive = prefs[KEY_IMMERSIVE] ?: defaults.immersive,
            volumeKeyTurnPage = prefs[KEY_VOLUME_TURN] ?: defaults.volumeKeyTurnPage,
        )
    }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        dataStore.edit { prefs ->
            val current = ReaderSettings(
                themeId = prefs[KEY_THEME] ?: ReaderSettings().themeId,
                fontFamily = prefs[KEY_FONT_FAMILY] ?: ReaderSettings().fontFamily,
                fontSizeSp = prefs[KEY_FONT_SIZE] ?: ReaderSettings().fontSizeSp,
                lineHeightRatio = prefs[KEY_LINE_HEIGHT] ?: ReaderSettings().lineHeightRatio,
                paragraphSpacingDp = prefs[KEY_PARAGRAPH_SPACING] ?: ReaderSettings().paragraphSpacingDp,
                horizontalMarginDp = prefs[KEY_MARGIN] ?: ReaderSettings().horizontalMarginDp,
                brightness = prefs[KEY_BRIGHTNESS] ?: ReaderSettings().brightness,
                keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: ReaderSettings().keepScreenOn,
                immersive = prefs[KEY_IMMERSIVE] ?: ReaderSettings().immersive,
                volumeKeyTurnPage = prefs[KEY_VOLUME_TURN] ?: ReaderSettings().volumeKeyTurnPage,
            )
            val next = transform(current)
            prefs[KEY_THEME] = next.themeId
            prefs[KEY_FONT_FAMILY] = next.fontFamily
            prefs[KEY_FONT_SIZE] = next.fontSizeSp
            prefs[KEY_LINE_HEIGHT] = next.lineHeightRatio
            prefs[KEY_PARAGRAPH_SPACING] = next.paragraphSpacingDp
            prefs[KEY_MARGIN] = next.horizontalMarginDp
            prefs[KEY_BRIGHTNESS] = next.brightness
            prefs[KEY_KEEP_SCREEN_ON] = next.keepScreenOn
            prefs[KEY_IMMERSIVE] = next.immersive
            prefs[KEY_VOLUME_TURN] = next.volumeKeyTurnPage
        }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_id")
        val KEY_FONT_FAMILY = stringPreferencesKey("font_family")
        val KEY_FONT_SIZE = intPreferencesKey("font_size_sp")
        val KEY_LINE_HEIGHT = floatPreferencesKey("line_height_ratio")
        val KEY_PARAGRAPH_SPACING = intPreferencesKey("paragraph_spacing_dp")
        val KEY_MARGIN = intPreferencesKey("horizontal_margin_dp")
        val KEY_BRIGHTNESS = floatPreferencesKey("brightness")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_IMMERSIVE = booleanPreferencesKey("immersive")
        val KEY_VOLUME_TURN = booleanPreferencesKey("volume_key_turn_page")
    }
}
