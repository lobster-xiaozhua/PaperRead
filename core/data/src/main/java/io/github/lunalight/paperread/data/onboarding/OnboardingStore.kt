package io.github.lunalight.paperread.data.onboarding

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import io.github.lunalight.paperread.data.settings.PreferencesDataStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 新手引导的「是否已看过」标记（T-020 / US-18，AC-18.1 / AC-18.2 / AC-18.4）。
 *
 * 落盘在**独立的** DataStore（`app_prefs`）而不是 reader_settings：
 * 前者是「应用级一次性状态」，后者是「用户可调偏好」。混在一起会让
 * [isLegacyUser] 的老用户判据失真（见下）。
 *
 * ## legacy 判据（AC-18.1「升级安装不属于首次」）
 *
 * 「标记不存在」与「首次安装」在 DataStore 里长得一模一样——都是没有这个键。
 * 但升级用户同样没有这个键（旧版本根本没写过它），如果只按键判断，每个升级
 * 用户升到本版后都会被塞一遍引导，这正是 AC-18.1 要避免的事。
 *
 * 因此增加一条**行为痕迹**判据：只要用户已经留下过任何数据，就不是新用户。
 * 具体痕迹由宿主注入（[isLegacyUser]），装配处（`AppContainer`）给的是
 * 「reader_settings 有非默认值，或书架非空」。放在构造参数里而不是写死，
 * 是为了让单测能精确控制「有痕迹 / 无痕迹」两条分支，而不是靠造两份数据库。
 *
 * 判据优先级：
 * 1. 已看过 → 不弹（谁调用过 [markSeen]，就永远不弹，直到用户主动重看）；
 * 2. 未看过但有痕迹 → 不弹，**并顺手补写标记**：升级用户首次启动就被判定为
 *    「已引导」，此后即使清空书架也不会突然弹出引导（否则删完书重启就弹一次，
 *    观感是「应用坏了」）；
 * 3. 未看过且无痕迹 → 弹（真正的新装用户）。
 */
class OnboardingStore(
    private val dataStore: DataStore<Preferences>,
    private val isLegacyUser: suspend () -> Boolean = { false },
) {

    /** 是否已经看过引导（Flow 版，UI 可观察）。 */
    fun hasSeen(): Flow<Boolean> = dataStore.data.map { it[KEY_SEEN] == true }

    /** 是否应该展示引导（见类注释的三级判据）。 */
    suspend fun shouldShowGuide(): Boolean {
        if (hasSeen().first()) return false
        if (isLegacyUser()) {
            // 升级用户：补写标记，避免「清空数据后突然弹引导」
            markSeen()
            return false
        }
        return true
    }

    /** 记录「已看过」（完成 / 跳过 / 设置页重看后返回都算）。 */
    suspend fun markSeen() {
        dataStore.edit { prefs -> prefs[KEY_SEEN] = true }
    }

    companion object {

        /** DataStore 落盘名（`filesDir/datastore/app_prefs.preferences_pb`）。 */
        const val DATA_STORE_NAME = "app_prefs"

        private val KEY_SEEN = booleanPreferencesKey("has_seen_onboarding")

        /**
         * 生产装配：独立 DataStore。
         *
         * 每个进程只能调用一次（同一文件不允许两个存活实例，见
         * [PreferencesDataStoreProvider]）——由 `AppContainer` 的 lazy 保证。
         */
        fun create(context: Context, isLegacyUser: suspend () -> Boolean): OnboardingStore =
            OnboardingStore(
                dataStore = PreferencesDataStoreProvider(context, DATA_STORE_NAME).instance,
                isLegacyUser = isLegacyUser,
            )
    }
}
