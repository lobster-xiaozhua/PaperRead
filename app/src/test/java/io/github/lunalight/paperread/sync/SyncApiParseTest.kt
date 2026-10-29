package io.github.lunalight.paperread.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HttpSyncApi] 响应解析单测。
 *
 * 两类：
 * 1. **纯解析**：直接对 [HttpSyncApi.parseAuthResult] 等内部函数喂
 *    [SyncRawResponse]，断言成功 / 错误码 / 网络异常分支；
 * 2. **发送 + 解析**：用注入的 [SyncConnectionFactory] fake（返回 canned 响应）
 *    验证 `send → parse` 全链路（含 404 视为无快照、错误体解析出 code / message）。
 */
class SyncApiParseTest {

    private val api = HttpSyncApi(baseUrl = "https://example.test")

    // ===== 1. 纯解析 =====

    @Test
    fun `认证成功解析出令牌`() {
        val resp = SyncRawResponse(
            200,
            """{"access_token":"a","refresh_token":"r","expires_in":3600}""",
        )
        val result = api.parseAuthResult(resp)
        assertTrue(result is SyncAuthResult.Success)
        result as SyncAuthResult.Success
        assertEquals("a", result.accessToken)
        assertEquals("r", result.refreshToken)
        assertEquals(3600L, result.expiresIn)
    }

    @Test
    fun `认证失败解析出状态码与错误码`() {
        val resp = SyncRawResponse(409, """{"error":"email_taken","message":"邮箱已被注册"}""")
        val result = api.parseAuthResult(resp)
        assertTrue(result is SyncAuthResult.Failure)
        result as SyncAuthResult.Failure
        assertEquals(409, result.httpStatus)
        assertEquals("email_taken", result.code)
        assertEquals("邮箱已被注册", result.message)
    }

    @Test
    fun `网络异常封装为Error且不抛`() {
        val resp = SyncRawResponse(-1, "", IllegalStateException("DNS 失败"))
        val result = api.parseAuthResult(resp)
        assertTrue(result is SyncAuthResult.Error)
        assertEquals("DNS 失败", (result as SyncAuthResult.Error).exception.message)
    }

    // ===== 2. 发送 + 解析（fake 连接） =====

    private fun apiWith(body: String, status: Int, error: Boolean = status !in 200..299): HttpSyncApi {
        val connection = FakeConnection(status, body, error)
        return HttpSyncApi(
            baseUrl = "https://example.test",
            factory = SyncConnectionFactory { connection },
        )
    }

    @Test
    fun `putSnapshot 2xx 为Success`() = runBlocking {
        val result = apiWith("{}", 200).putSnapshot("dev", "tok", "CIPHER", 1L)
        assertTrue(result is SyncUnitResult.Success)
    }

    @Test
    fun `getSnapshot 404 视为无快照Success null`() = runBlocking {
        val result = apiWith("", 404, error = true).getSnapshot("dev", "tok")
        assertTrue(result is SyncSnapshotResult.Success)
        assertNull((result as SyncSnapshotResult.Success).snapshot)
    }

    @Test
    fun `getSnapshot 200 解析出密文与lastModified`() = runBlocking {
        val result = apiWith(
            """{"ciphertext":"BASE64==","lastModified":1700000000000}""",
            200,
        ).getSnapshot("dev", "tok")
        assertTrue(result is SyncSnapshotResult.Success)
        result as SyncSnapshotResult.Success
        assertEquals("BASE64==", result.snapshot?.ciphertext)
        assertEquals(1700000000000L, result.snapshot?.lastModified)
    }

    @Test
    fun `listDevices 200 解析设备列表`() = runBlocking {
        val result = apiWith(
            """{"devices":[{"deviceId":"d1","lastModified":1},{"deviceId":"d2","lastModified":2}]}""",
            200,
        ).listDevices("self", "tok")
        assertTrue(result is SyncDevicesResult.Success)
        result as SyncDevicesResult.Success
        assertEquals(listOf("d1", "d2"), result.devices.map { it.deviceId })
        assertEquals(2L, result.devices[1].lastModified)
    }

    @Test
    fun `me 200 解析邮箱与id`() = runBlocking {
        val result = apiWith("""{"email":"me@x.com","id":"u1"}""", 200).me("tok")
        assertTrue(result is SyncUserResult.Success)
        result as SyncUserResult.Success
        assertEquals("me@x.com", result.email)
        assertEquals("u1", result.id)
    }

    @Test
    fun `连接异常时getSnapshot 返回Error`() = runBlocking {
        val failing = HttpSyncApi(
            baseUrl = "https://example.test",
            factory = SyncConnectionFactory { throw java.io.IOException("无法连接") },
        )
        val result = failing.getSnapshot("dev", "tok")
        assertTrue(result is SyncSnapshotResult.Error)
    }

    /** 极简 [HttpURLConnection] fake：按构造时给定的状态码 / 响应体返回。 */
    private class FakeConnection(
        private val code: Int,
        private val payload: String,
        private val isError: Boolean,
    ) : HttpURLConnection(URL("https://example.test")) {

        var written: ByteArray? = null
            private set

        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getResponseCode(): Int = code
        override fun getInputStream(): InputStream =
            if (isError) ByteArrayInputStream(ByteArray(0)) else ByteArrayInputStream(payload.toByteArray())
        override fun getErrorStream(): InputStream =
            if (isError) ByteArrayInputStream(payload.toByteArray()) else ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): java.io.OutputStream =
            object : ByteArrayOutputStream() {
                override fun close() {
                    written = toByteArray()
                }
            }

        private val props = mutableMapOf<String, String>()
        override fun setRequestProperty(key: String, value: String) {
            props[key] = value
        }
        override fun getRequestProperty(key: String): String? = props[key]
        override fun setRequestMethod(method: String) = Unit
    }
}
