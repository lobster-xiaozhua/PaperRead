package io.github.lunalight.paperread.sync

/**
 * 内存版 [SyncTokenStore]（单测用 fake，不碰 Android Keystore / 文件）。
 *
 * 仅记录最新令牌，进程内有效；`expiresIn` 是信息字段、此处不保留。
 */
class InMemoryTokenStore : SyncTokenStore {

    @Volatile private var access: String? = null

    @Volatile private var refresh: String? = null

    override suspend fun save(accessToken: String, refreshToken: String, expiresIn: Long) {
        access = accessToken
        refresh = refreshToken
    }

    override suspend fun getAccess(): String? = access

    override suspend fun getRefresh(): String? = refresh

    override suspend fun clear() {
        access = null
        refresh = null
    }
}
