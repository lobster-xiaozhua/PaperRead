package io.github.lunalight.paperread.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HttpSyncApi] 请求构造单测（纯函数，不联网）。
 *
 * 断言不变量：URL 路径、HTTP 方法、请求头（Content-Type / X-Device-Id /
 * Authorization）、请求体 JSON 字段名。构造与发送解耦，故可直接对
 * [HttpSyncApi.buildXxxRequest] 断言。
 */
class SyncApiRequestTest {

    private val api = HttpSyncApi(baseUrl = "https://example.test")

    private fun bodyFields(request: SyncRequest): Map<String, String> {
        val element = Json.parseToJsonElement(request.bodyText())
        return (element as JsonObject).mapValues { it.value.toString().trim('"') }
    }

    @Test
    fun `注册请求POST 且字段为email password`() {
        val req = api.buildRegisterRequest("a@b.com", "secret123")
        assertEquals("POST", req.method)
        assertEquals("https://example.test/api/auth/register", req.url)
        assertEquals("application/json", req.headers["Content-Type"])
        val fields = bodyFields(req)
        assertEquals("a@b.com", fields["email"])
        assertEquals("secret123", fields["password"])
        // 认证接口不带设备头 / 鉴权头
        assertFalse(req.headers.containsKey("X-Device-Id"))
        assertFalse(req.headers.containsKey("Authorization"))
    }

    @Test
    fun `登录请求字段为email password`() {
        val req = api.buildLoginRequest("u@x.com", "pw")
        assertEquals("POST", req.method)
        assertEquals("https://example.test/api/auth/login", req.url)
        val fields = bodyFields(req)
        assertEquals("u@x.com", fields["email"])
        assertEquals("pw", fields["password"])
    }

    @Test
    fun `刷新请求字段为refresh_token`() {
        val req = api.buildRefreshRequest("refresh-tok")
        assertEquals("POST", req.method)
        assertEquals("https://example.test/api/auth/refresh", req.url)
        val fields = bodyFields(req)
        assertEquals("refresh-tok", fields["refresh_token"])
    }

    @Test
    fun `登出请求POST 路径为api auth logout 且字段为refresh_token`() {
        val req = api.buildLogoutRequest("refresh-tok")
        assertEquals("POST", req.method)
        assertEquals("https://example.test/api/auth/logout", req.url)
        assertTrue("路径应以 /api/auth/logout 结尾", req.url.endsWith("/api/auth/logout"))
        val fields = bodyFields(req)
        assertEquals("refresh-tok", fields["refresh_token"])
        // 登出从请求体读 refresh_token，不依赖鉴权头
        assertEquals("application/json", req.headers["Content-Type"])
        assertFalse(req.headers.containsKey("Authorization"))
    }

    @Test
    fun `me 请求带Bearer 鉴权头且为GET`() {
        val req = api.buildMeRequest("access-abc")
        assertEquals("GET", req.method)
        assertEquals("https://example.test/api/auth/me", req.url)
        assertEquals("Bearer access-abc", req.headers["Authorization"])
        assertEquals(null, req.body)
    }

    @Test
    fun `上传快照请求PUT 带设备头与鉴权头且字段正确`() {
        val req = api.buildPutSnapshotRequest("dev-1", "access-xyz", "CIPHERTEXT", 1700000000000L)
        assertEquals("PUT", req.method)
        assertEquals("https://example.test/api/sync/snapshot", req.url)
        assertEquals("dev-1", req.headers["X-Device-Id"])
        assertEquals("Bearer access-xyz", req.headers["Authorization"])
        val fields = bodyFields(req)
        assertEquals("CIPHERTEXT", fields["ciphertext"])
        assertEquals("1700000000000", fields["lastModified"])
    }

    @Test
    fun `取快照请求GET 带设备头与鉴权头`() {
        val req = api.buildGetSnapshotRequest("dev-2", "access-tok")
        assertEquals("GET", req.method)
        assertEquals("https://example.test/api/sync/snapshot", req.url)
        assertEquals("dev-2", req.headers["X-Device-Id"])
        assertEquals("Bearer access-tok", req.headers["Authorization"])
        assertEquals(null, req.body)
    }

    @Test
    fun `设备列表请求GET 带设备头与鉴权头`() {
        val req = api.buildListDevicesRequest("dev-self", "access-tok")
        assertEquals("GET", req.method)
        assertEquals("https://example.test/api/sync/devices", req.url)
        assertEquals("dev-self", req.headers["X-Device-Id"])
        assertEquals("Bearer access-tok", req.headers["Authorization"])
    }

    @Test
    fun `删除快照请求DELETE 带设备头与鉴权头`() {
        val req = api.buildDeleteSnapshotRequest("dev-3", "access-tok")
        assertEquals("DELETE", req.method)
        assertEquals("https://example.test/api/sync/snapshot", req.url)
        assertEquals("dev-3", req.headers["X-Device-Id"])
        assertEquals("Bearer access-tok", req.headers["Authorization"])
    }

    @Test
    fun `请求体字节非空且可解析`() {
        val req = api.buildRegisterRequest("a@b.com", "secret123")
        assertNotNull(req.body)
        assertTrue(req.body!!.isNotEmpty())
        assertTrue(req.bodyText().contains("\"email\""))
    }
}
