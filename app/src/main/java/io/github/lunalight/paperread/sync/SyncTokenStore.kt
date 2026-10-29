package io.github.lunalight.paperread.sync

/**
 * 同步令牌存储（access / refresh token 不落 DataStore 明文）。
 *
 * 令牌经 Android Keystore 信封加密后落私有文件（生产实现
 * [AndroidKeystoreTokenStore]），或仅存内存（[InMemoryTokenStore]，供单测）。
 */
interface SyncTokenStore {

    /** 保存最新令牌（expiresIn 仅供信息，实现可按需忽略）。 */
    suspend fun save(accessToken: String, refreshToken: String, expiresIn: Long)

    /** 取当前 access token（无则返回 null）。 */
    suspend fun getAccess(): String?

    /** 取当前 refresh token（无则返回 null）。 */
    suspend fun getRefresh(): String?

    /** 清空（登出 / 令牌吊销）。 */
    suspend fun clear()
}
