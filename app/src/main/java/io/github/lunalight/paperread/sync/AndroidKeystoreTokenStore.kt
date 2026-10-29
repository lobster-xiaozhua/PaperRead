package io.github.lunalight.paperread.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 基于 Android Keystore 的 [SyncTokenStore]（信封加密，API 23+）。
 *
 * 令牌 JSON（`{access, refresh}`）用一枚 Keystore 内的 AES 密钥加密后落私有文件
 * `sync_tokens.bin`；密钥**不导出**、由系统保管（[setUserAuthenticationRequired]
 * 设 false，无需解锁即可在后台同步使用）。明文 DataStore 里因此看不到任何令牌。
 *
 * 密钥别名固定（[KEYSTORE_ALIAS]），首次 `save` 时按需生成；读取时若密钥缺失
 * （如卸载重装后文件残留）则安全返回 null，由上层走重新登录。
 */
class AndroidKeystoreTokenStore(context: Context) : SyncTokenStore {

    private val file: File = context.getFileStreamPath("sync_tokens.bin")
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override suspend fun save(accessToken: String, refreshToken: String, expiresIn: Long) {
        val key = ensureKey()
        val plaintext = TOKEN_JSON.encodeToString(
            TokenBlob.serializer(),
            TokenBlob(access = accessToken, refresh = refreshToken),
        ).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext)
        file.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(encrypted)
        }
    }

    override suspend fun getAccess(): String? = decode()?.access

    override suspend fun getRefresh(): String? = decode()?.refresh

    override suspend fun clear() {
        runCatching { file.delete() }
    }

    // ---- 内部 ----

    private fun ensureKey(): SecretKey {
        val existing = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun decode(): TokenBlob? {
        if (!file.isFile) return null
        val secretEntry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: return null
        return runCatching {
            val bytes = file.readBytes()
            val ivLen = bytes[0].toInt() and 0xFF
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val encrypted = bytes.copyOfRange(1 + ivLen, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretEntry.secretKey, IvParameterSpec(iv))
            }
            val plain = cipher.doFinal(encrypted)
            TOKEN_JSON.decodeFromString(TokenBlob.serializer(), plain.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    private companion object {
        const val KEYSTORE_ALIAS = "paperread_sync_tokens"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/CBC/PKCS7Padding"
        val TOKEN_JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** 待加密的令牌载荷。 */
@Serializable
private data class TokenBlob(
    val access: String = "",
    val refresh: String = "",
)
