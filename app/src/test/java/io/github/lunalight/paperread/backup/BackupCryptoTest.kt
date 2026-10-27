package io.github.lunalight.paperread.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * 备份加密单测（T-016 / AC-14.2、AC-14.7）。
 *
 * 固化三件事：KDF 参数（迭代数不得低于 100,000）、条目级 GCM 往返、
 * 口令不出现在参数 / 密文 / 导出字节里；以及「错误口令」是与「文件损坏」
 * 区分开的**特定异常**（AC-14.7 可重试的前提）。
 */
class BackupCryptoTest {

    private val password = "correct-horse-battery".toCharArray()

    @Test
    fun `派生参数满足AC要求的下限`() {
        BackupCrypto.create(password).use { crypto ->
            assertEquals(100_000, crypto.params.iterations)
            assertTrue("迭代数不得低于 100,000", crypto.params.iterations >= 100_000)
            assertEquals(256, crypto.params.keyBits)
            assertEquals("PBKDF2WithHmacSHA256", crypto.params.kdf)
            assertEquals("AES/GCM/NoPadding", crypto.params.cipher)
            assertEquals(12, crypto.params.nonceBytes)
            assertEquals(16, java.util.Base64.getDecoder().decode(crypto.params.salt).size)
        }
    }

    @Test
    fun `同口令与salt可重开并互通`() {
        val plain = "备份内容".toByteArray(Charsets.UTF_8)
        val creator = BackupCrypto.create(password)
        val params = creator.params
        val payload = creator.encrypt(plain)
        creator.close()

        BackupCrypto.open(password, params).use { reopened ->
            assertTrue(reopened.decrypt(payload).contentEquals(plain))
        }
    }

    @Test
    fun `条目级GCM字节往返`() {
        BackupCrypto.create(password).use { crypto ->
            val plain = ByteArray(4096).also { Random(7).nextBytes(it) }
            val payload = crypto.encrypt(plain)

            assertNotEquals("密文不得等于明文", plain.size, payload.size)
            assertTrue("密文应含 nonce(12) + tag(16)", payload.size >= plain.size + 12 + 16)
            assertTrue(crypto.decrypt(payload).contentEquals(plain))
        }
    }

    @Test
    fun `流式加解密往返大文件`() {
        BackupCrypto.create(password).use { crypto ->
            val plain = ByteArray(2 * 1024 * 1024).also { Random(11).nextBytes(it) }
            val encrypted = ByteArrayOutputStream().also { out ->
                crypto.encryptStream(ByteArrayInputStream(plain), out)
            }.toByteArray()
            val decrypted = ByteArrayOutputStream().also { out ->
                crypto.decryptStream(ByteArrayInputStream(encrypted), out)
            }.toByteArray()

            assertTrue(decrypted.contentEquals(plain))
        }
    }

    @Test
    fun `相同明文两次加密nonce不同`() {
        BackupCrypto.create(password).use { crypto ->
            val first = crypto.encrypt("same".toByteArray())
            val second = crypto.encrypt("same".toByteArray())
            assertFalse(first.copyOf(12).contentEquals(second.copyOf(12)))
            assertFalse(first.contentEquals(second))
        }
    }

    @Test
    fun `错误口令抛特定异常而非泛化失败`() {
        val params = BackupCrypto.create(password).use { it.params }

        try {
            BackupCrypto.open("wrong-password".toCharArray(), params).use { }
            fail("错误口令应抛异常")
        } catch (e: BackupCryptoException) {
            assertEquals(BackupCryptoException.Kind.WRONG_PASSWORD, e.kind)
        }
    }

    @Test
    fun `错误口令后可立即用正确口令重试`() {
        val params = BackupCrypto.create(password).use { it.params }
        runCatching { BackupCrypto.open("wrong-password".toCharArray(), params).use { } }
            .onSuccess { fail("错误口令不应成功") }

        BackupCrypto.open(password, params).use { crypto ->
            assertEquals("ok", crypto.decrypt(crypto.encrypt("ok".toByteArray())).toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `迭代数被降级时拒绝打开`() {
        val params = BackupCrypto.create(password).use { it.params }

        try {
            BackupCrypto.open(password, params.copy(iterations = 1_000)).use { }
            fail("降级迭代数应被拒绝")
        } catch (e: BackupCryptoException) {
            assertEquals(BackupCryptoException.Kind.UNSUPPORTED, e.kind)
        }
    }

    @Test
    fun `加密参数与密文里都不含口令`() {
        BackupCrypto.create(password).use { crypto ->
            val serialized = backupJson.encodeToString(BackupCryptoParams.serializer(), crypto.params)
            assertFalse("crypto.json 不得出现口令", serialized.contains("correct-horse-battery"))

            val payload = crypto.encrypt("内容".toByteArray())
            assertFalse(
                "密文不得包含口令字节",
                payload.toString(Charsets.ISO_8859_1).contains("correct-horse-battery"),
            )
        }
    }

    @Test
    fun `口令强度校验`() {
        assertFalse(BackupCrypto.isValidPassword("1234567".toCharArray()))
        assertFalse("全空白不算合法口令", BackupCrypto.isValidPassword("        ".toCharArray()))
        assertTrue(BackupCrypto.isValidPassword("12345678".toCharArray()))
        assertTrue(BackupCrypto.isValidPassword("口令至少八位字符".toCharArray()))
    }

    @Test
    fun `口令强度校验的CharSequence重载与CharArray判据完全一致`() {
        // P3-3：UI 持 String、领域层持 CharArray，两条重载必须逐例同判——
        // 否则「8 位全空格」又会出现「UI 放过、领域层拒绝」的口径分裂。
        val cases = listOf(
            "",
            "       ",
            "1234567",
            "        ",
            "12345678",
            "correct-horse-battery",
            "口令至少八位字符",
        )
        cases.forEach { sample ->
            assertEquals(
                "「$sample」的两种重载判定必须一致",
                BackupCrypto.isValidPassword(sample.toCharArray()),
                BackupCrypto.isValidPassword(sample),
            )
        }
        assertFalse("8 位全空格非法", BackupCrypto.isValidPassword("        "))
        assertTrue("8 位含一个非空白字符即合法", BackupCrypto.isValidPassword("       1"))
    }

    @Test
    fun `密文被篡改时抛损坏而非口令错误`() {
        BackupCrypto.create(password).use { crypto ->
            val payload = crypto.encrypt("tamper-me".toByteArray())
            payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0xFF).toByte()

            try {
                crypto.decrypt(payload)
                fail("篡改后的密文必须解密失败")
            } catch (e: BackupCryptoException) {
                assertEquals(BackupCryptoException.Kind.CORRUPTED, e.kind)
            }
        }
    }

    @Test
    fun `关闭后密钥被清零`() {
        val crypto = BackupCrypto.create(password)
        val field = BackupCrypto::class.java.getDeclaredField("key").apply { isAccessible = true }
        val before = field.get(crypto) as ByteArray
        assertTrue(before.any { it != 0.toByte() })

        crypto.close()

        assertTrue("close 后密钥必须 zero-fill", (field.get(crypto) as ByteArray).all { it == 0.toByte() })
    }
}
