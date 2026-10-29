package io.github.lunalight.paperread.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 云同步设置存储（Cloudflare 后端接入，US-22）。
 *
 * 与 [SettingsStore] 共用同一个 DataStore 实例（由 [SettingsDataStoreProvider.instance]
 * 注入），**不得**自行 `preferencesDataStore(...)` 新建实例——同一文件在进程内只能有一个
 * 存活的 DataStore（否则首读抛 `IllegalStateException: activeFiles`），详见
 * [SettingsDataStoreProvider] 的类注释。
 *
 * 键（同一 `reader_settings.preferences_pb` 文件内的偏好键，避免与阅读设置冲突）：
 * - `sync_enabled`：是否开启云同步（默认 false，本地优先不变）；
 * - `sync_account_email`：当前登录邮箱（null = 未登录）；
 * - `sync_device_id`：本机设备标识（首次访问用 UUID 生成并写回，用于多设备区分）。
 */
interface SyncSettingsStore {

    /** 是否开启云同步。 */
    val syncEnabled: Flow<Boolean>

    /** 当前登录邮箱（null = 未登录）。 */
    val accountEmail: Flow<String?>

    /** 本机设备标识（持久化；未生成前为空串）。 */
    val deviceId: Flow<String>

    suspend fun setSyncEnabled(enabled: Boolean)

    suspend fun setAccountEmail(email: String?)

    /**
     * 取本机设备标识：已存在则直接返回，否则生成 UUID 写回后返回。
     *
     * 设备标识仅用于多设备区分（X-Device-Id 头 / 设备列表），非敏感、不绑定硬件。
     */
    suspend fun ensureDeviceId(): String
}

/**
 * [SyncSettingsStore] 的 DataStore Preferences 实现。
 *
 * 接收已装配好的 [DataStore]（由 [SettingsDataStoreProvider] 按 Application 托管），
 * 本类只负责「Preferences ↔ 同步标识」的映射。
 */
class PreferencesSyncSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SyncSettingsStore {

    override val syncEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SYNC_ENABLED] ?: false
    }

    override val accountEmail: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_SYNC_ACCOUNT_EMAIL]
    }

    override val deviceId: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: ""
    }

    override suspend fun setSyncEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_SYNC_ENABLED] = enabled }
    }

    override suspend fun setAccountEmail(email: String?) {
        dataStore.edit { prefs ->
            if (email != null) {
                prefs[KEY_SYNC_ACCOUNT_EMAIL] = email
            } else {
                prefs.remove(KEY_SYNC_ACCOUNT_EMAIL)
            }
        }
    }

    override suspend fun ensureDeviceId(): String {
        val existing = dataStore.data.first()[KEY_DEVICE_ID]
        if (!existing.isNullOrEmpty()) return existing
        val id = UUID.randomUUID().toString()
        dataStore.edit { prefs -> prefs[KEY_DEVICE_ID] = id }
        return id
    }

    private companion object {
        val KEY_SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val KEY_SYNC_ACCOUNT_EMAIL = stringPreferencesKey("sync_account_email")
        val KEY_DEVICE_ID = stringPreferencesKey("sync_device_id")
    }
}
