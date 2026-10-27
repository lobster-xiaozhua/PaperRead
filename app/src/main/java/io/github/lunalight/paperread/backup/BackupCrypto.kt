package io.github.lunalight.paperread.backup

import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

/**
 * 备份口令加密（T-016 / AC-14.2、AC-14.7，R-13）。
 *
 * ## 方案
 * ```
 * 口令（用户设置，≥ 8 位，不落盘）
 *   │ PBKDF2-HMAC-SHA256，iterations = 100_000，salt = 16B SecureRandom
 *   ▼
 * 256 bit 对称密钥（仅内存；close() 时 zero-fill）
 *   │ 逐条目 AES-256-GCM（每条目独立 12B nonce，SecureRandom）
 *   ▼
 * 条目密文 = nonce(12) ‖ ciphertext ‖ GCM tag(16)
 * ```
 *
 * ## 与传输密钥的区别（刻意不复用）
 * 传输的 secret 由**配对码**派生、salt 取双方设备 ID（可重现，用于两端配对）；
 * 备份密钥由**用户口令**派生、salt 随机存包（不可重现，只能由口令重算）。
 * 两者算法族相同但用途 / 生命周期 / 威胁模型不同，混用会让「配对码泄露 =
 * 备份可解」这种本不该存在的推论成立。
 *
 * ## 口令不落盘的三道保证
 * 1. `crypto.json` 只写 salt 与 verifier 密文，**没有任何口令字段**；
 * 2. 本包不引用任何日志 API（`android.util.Log` / `println`），口令没有出口；
 * 3. 调用方交来的 `CharArray` 在派生后由 [PBEKeySpec.clearPassword] 清空内部副本，
 *    [BackupCoordinator] 在流程结束时把调用方的数组 zero-fill。
 *
 * ## 口令校验（AC-14.7）
 * `crypto.json` 里的 `verifier` 是常量明文 `PaperReadBackup:v1` 的 GCM 密文。
 * 打开备份时先解 verifier：GCM tag 不匹配 → [BackupCryptoException.WrongPassword]
 * （可重试，而不是泛化成「文件损坏」）；verifier 通过后再解业务条目时失败，
 * 才是真正的数据损坏。
 */

/** PBKDF2 迭代次数（AC-14.2 要求 ≥ 100,000；调高需同步本注释与测试断言）。 */
const val BACKUP_PBKDF2_ITERATIONS: Int = 100_000

/** 密钥长度（bit）：AES-256。 */
const val BACKUP_KEY_BITS: Int = 256

/** PBKDF2 salt 长度（字节）。 */
const val BACKUP_SALT_BYTES: Int = 16

/** GCM nonce 长度（字节，96 bit 是 GCM 的标准长度）。 */
const val BACKUP_NONCE_BYTES: Int = 12

/** GCM 认证标签长度（bit）。 */
const val BACKUP_TAG_BITS: Int = 128

/** 口令最小长度（AC-14.2：领域层校验，UI 同步校验形成双保险）。 */
const val BACKUP_MIN_PASSWORD_LENGTH: Int = 8

/** KDF 算法（写入 `crypto.json`，打开时据此校验）。 */
const val BACKUP_KDF_ALGORITHM: String = "PBKDF2WithHmacSHA256"

/** 对称加密算法（写入 `crypto.json`）。 */
const val BACKUP_CIPHER: String = "AES/GCM/NoPadding"

/** 允许的迭代次数上限：防止恶意 / 损坏文件把打开流程拖成 DoS。 */
private const val MAX_ITERATIONS: Int = 2_000_000

/** verifier 明文（常量，用于口令校验；不是密钥材料）。 */
private const val VERIFIER_TEXT: String = "PaperReadBackup:v1"

/**
 * 备份加密参数（`crypto.json` 的内容，明文）。
 *
 * 这些字段本身不敏感：salt 与 KDF 参数是公开的密码学参数，verifier 是
 * 常量明文的一次加密，无法反推口令。
 */
@Serializable
data class BackupCryptoParams(
    val kdf: String = BACKUP_KDF_ALGORITHM,
    val iterations: Int = BACKUP_PBKDF2_ITERATIONS,
    val keyBits: Int = BACKUP_KEY_BITS,
    val cipher: String = BACKUP_CIPHER,
    val tagBits: Int = BACKUP_TAG_BITS,
    val nonceBytes: Int = BACKUP_NONCE_BYTES,
    /** Base64（标准字母表，带 padding）。 */
    val salt: String = "",
    /** Base64：常量明文的 `nonce(12) ‖ ciphertext ‖ tag`。 */
    val verifier: String = "",
)

/** 备份加密 / 解密失败。 */
class BackupCryptoException private constructor(
    message: String,
    val kind: Kind,
) : Exception(message) {

    enum class Kind {
        /** 口令错误（可重试，AC-14.7）。 */
        WRONG_PASSWORD,

        /** 密文损坏 / 认证标签不匹配（口令已通过 verifier 校验）。 */
        CORRUPTED,

        /** 参数不受支持（迭代次数过低 / 算法不符 / salt 非法）。 */
        UNSUPPORTED,
    }

    companion object {
        internal fun wrongPassword(): BackupCryptoException =
            BackupCryptoException("备份口令错误", Kind.WRONG_PASSWORD)

        internal fun corrupted(detail: String): BackupCryptoException =
            BackupCryptoException("备份数据损坏：$detail", Kind.CORRUPTED)

        internal fun unsupported(detail: String): BackupCryptoException =
            BackupCryptoException("不支持的加密参数：$detail", Kind.UNSUPPORTED)
    }
}

/**
 * 备份口令加密器：持有派生后的对称密钥，提供条目级 GCM 加解密。
 *
 * 生命周期：`open` / `create` 得到实例 → 用完必须 [close]（密钥 zero-fill）。
 * 实例不持有口令，只持有 32 字节密钥。
 */
class BackupCrypto private constructor(
    private val key: ByteArray,
    val params: BackupCryptoParams,
) : AutoCloseable {

    /** 加密小条目（JSON）：返回 `nonce ‖ ciphertext ‖ tag`。 */
    fun encrypt(plain: ByteArray): ByteArray {
        val nonce = randomBytes(BACKUP_NONCE_BYTES)
        val cipher = Cipher.getInstance(BACKUP_CIPHER).apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(BACKUP_TAG_BITS, nonce),
            )
        }
        return nonce + cipher.doFinal(plain)
    }

    /** 解密小条目；认证失败抛 [BackupCryptoException.Kind.CORRUPTED]。 */
    fun decrypt(payload: ByteArray): ByteArray {
        if (payload.size < BACKUP_NONCE_BYTES) {
            throw BackupCryptoException.corrupted("条目长度不足")
        }
        val nonce = payload.copyOfRange(0, BACKUP_NONCE_BYTES)
        val body = payload.copyOfRange(BACKUP_NONCE_BYTES, payload.size)
        return try {
            val cipher = Cipher.getInstance(BACKUP_CIPHER).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(BACKUP_TAG_BITS, nonce),
                )
            }
            cipher.doFinal(body)
        } catch (e: AEADBadTagException) {
            throw BackupCryptoException.corrupted("认证标签不匹配")
        } catch (e: GeneralSecurityException) {
            throw BackupCryptoException.corrupted(e.message ?: "解密失败")
        }
    }

    /**
     * 流式加密（源文件可能上百 MB，不整文件进内存）。
     *
     * 先写 nonce，再经 `CipherOutputStream` 写密文；`close()` 触发 GCM tag 落盘。
     */
    fun encryptStream(input: InputStream, output: OutputStream) {
        val nonce = randomBytes(BACKUP_NONCE_BYTES)
        output.write(nonce)
        val cipher = Cipher.getInstance(BACKUP_CIPHER).apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(BACKUP_TAG_BITS, nonce),
            )
        }
        val cipherOut = CipherOutputStream(NonClosingOutputStream(output), cipher)
        input.copyTo(cipherOut, DEFAULT_BUFFER_SIZE)
        // doFinal + flush；NonClosingOutputStream 保证不关闭 zip 条目流
        cipherOut.close()
    }

    /**
     * 流式解密：读 nonce → 余下字节过 `CipherInputStream`。
     *
     * GCM 的 tag 校验发生在流末尾；`CipherInputStream` 在部分实现上会把
     * `AEADBadTagException` 包装成 `IOException`，这里统一翻译为
     * [BackupCryptoException.Kind.CORRUPTED]。即便某实现静默截断，
     * 调用方仍会比对 manifest 声明的 SHA-256——双保险。
     */
    fun decryptStream(input: InputStream, output: OutputStream) {
        val nonce = readNonce(input)
        val cipher = try {
            Cipher.getInstance(BACKUP_CIPHER).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(BACKUP_TAG_BITS, nonce),
                )
            }
        } catch (e: GeneralSecurityException) {
            throw BackupCryptoException.corrupted(e.message ?: "初始化解密失败")
        }
        try {
            CipherInputStream(input, cipher).use { cipherIn ->
                cipherIn.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        } catch (e: IOException) {
            throw BackupCryptoException.corrupted(e.message ?: "认证标签不匹配")
        } catch (e: IllegalStateException) {
            throw BackupCryptoException.corrupted(e.message ?: "解密状态异常")
        }
    }

    /** 密钥 zero-fill（口令不驻留，密钥同样不驻留）。 */
    override fun close() {
        key.fill(0)
    }

    companion object {

        private val random = SecureRandom()
        private val base64: Base64.Encoder = Base64.getEncoder()
        private val base64Decoder: Base64.Decoder = Base64.getDecoder()

        /**
         * 口令强度校验（AC-14.2）：长度 ≥ [BACKUP_MIN_PASSWORD_LENGTH]，
         * 且不能全为空白。UI 与领域层都调用它（双保险）。
         */
        fun isValidPassword(password: CharArray): Boolean =
            password.size >= BACKUP_MIN_PASSWORD_LENGTH && password.any { !it.isWhitespace() }

        /** 新备份：随机 salt + 口令派生 + 生成 verifier。 */
        fun create(password: CharArray): BackupCrypto {
            require(isValidPassword(password)) { "备份口令至少 $BACKUP_MIN_PASSWORD_LENGTH 位" }
            val salt = randomBytes(BACKUP_SALT_BYTES)
            val key = deriveKey(password, salt, BACKUP_PBKDF2_ITERATIONS)
            // 先用占位 params 生成 verifier，再构造最终 params（verifier 参与自身定义）
            val draft = BackupCrypto(
                key = key,
                params = BackupCryptoParams(
                    salt = base64.encodeToString(salt),
                    verifier = "",
                ),
            )
            val verifier = draft.encrypt(VERIFIER_TEXT.toByteArray(Charsets.UTF_8))
            return BackupCrypto(
                key = key,
                params = BackupCryptoParams(
                    salt = base64.encodeToString(salt),
                    verifier = base64.encodeToString(verifier),
                ),
            )
        }

        /**
         * 打开既有备份：校验参数 → 派生 → 解 verifier。
         *
         * @throws BackupCryptoException [BackupCryptoException.Kind.WRONG_PASSWORD]
         *   口令错误（AC-14.7，可重试）
         * @throws BackupCryptoException [BackupCryptoException.Kind.UNSUPPORTED]
         *   迭代次数 / 算法不符（拒绝降级攻击）
         */
        fun open(password: CharArray, params: BackupCryptoParams): BackupCrypto {
            if (params.kdf != BACKUP_KDF_ALGORITHM || params.cipher != BACKUP_CIPHER) {
                throw BackupCryptoException.unsupported("kdf=${params.kdf} cipher=${params.cipher}")
            }
            if (params.keyBits != BACKUP_KEY_BITS) {
                throw BackupCryptoException.unsupported("keyBits=${params.keyBits}")
            }
            // 迭代次数下限 = 本版本参数（防降级）；上限防 DoS
            if (params.iterations < BACKUP_PBKDF2_ITERATIONS || params.iterations > MAX_ITERATIONS) {
                throw BackupCryptoException.unsupported("iterations=${params.iterations}")
            }
            if (params.tagBits != BACKUP_TAG_BITS || params.nonceBytes != BACKUP_NONCE_BYTES) {
                throw BackupCryptoException.unsupported(
                    "tagBits=${params.tagBits} nonceBytes=${params.nonceBytes}",
                )
            }
            val salt = decode(params.salt)
                ?: throw BackupCryptoException.unsupported("salt 不是合法 Base64")
            if (salt.size != BACKUP_SALT_BYTES) {
                throw BackupCryptoException.unsupported("salt 长度 ${salt.size}")
            }
            val verifier = decode(params.verifier)
                ?: throw BackupCryptoException.unsupported("verifier 不是合法 Base64")
            val key = deriveKey(password, salt, params.iterations)
            val crypto = BackupCrypto(key, params)
            val plain = try {
                crypto.decrypt(verifier)
            } catch (e: BackupCryptoException) {
                crypto.close()
                throw BackupCryptoException.wrongPassword()
            }
            if (!MessageDigest.isEqual(plain, VERIFIER_TEXT.toByteArray(Charsets.UTF_8))) {
                crypto.close()
                throw BackupCryptoException.wrongPassword()
            }
            return crypto
        }

        /** PBKDF2-HMAC-SHA256；`PBEKeySpec` 的 char 副本用完即清。 */
        private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password, salt, iterations, BACKUP_KEY_BITS)
            return try {
                SecretKeyFactory.getInstance(BACKUP_KDF_ALGORITHM)
                    .generateSecret(spec)
                    .encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun randomBytes(size: Int): ByteArray =
            ByteArray(size).also { random.nextBytes(it) }

        private fun decode(value: String): ByteArray? =
            runCatching { base64Decoder.decode(value) }.getOrNull()

        private fun readNonce(input: InputStream): ByteArray {
            val nonce = ByteArray(BACKUP_NONCE_BYTES)
            var read = 0
            while (read < nonce.size) {
                val n = input.read(nonce, read, nonce.size - read)
                if (n <= 0) throw BackupCryptoException.corrupted("条目长度不足")
                read += n
            }
            return nonce
        }
    }
}

/**
 * 关闭时只 flush 不关闭底层的输出流包装。
 *
 * `CipherOutputStream.close()` 会写 GCM tag 后关闭其底层流——若直接包住
 * `ZipOutputStream`，一个条目的结束会把整个 zip 关掉。
 */
private class NonClosingOutputStream(private val delegate: OutputStream) : FilterOutputStream(delegate) {

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
    }

    override fun write(b: Int) {
        delegate.write(b)
    }

    override fun close() {
        flush()
    }
}
