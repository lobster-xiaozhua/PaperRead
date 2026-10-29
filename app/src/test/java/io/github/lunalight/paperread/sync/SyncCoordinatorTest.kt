package io.github.lunalight.paperread.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.backup.BackupExporter
import io.github.lunalight.paperread.backup.BackupImporter
import io.github.lunalight.paperread.backup.FakeImportGateway
import io.github.lunalight.paperread.backup.MemoryBookRepository
import io.github.lunalight.paperread.backup.seedBook
import io.github.lunalight.paperread.data.repository.BookStateWriter
import io.github.lunalight.paperread.data.settings.SyncSettingsStore
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.fakes.testBook
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 同步编排层单测（US-22）：认证留存、登出清理、push（复用 BackupExporter 且仅状态）、
 * pull（复用 BackupImporter 且 progressOnly）、syncNow 先 pull 后 push、401 自动刷新重试。
 *
 * 全部用 fake：[FakeSyncApi] 记录调用并返回 canned 结果、[InMemoryTokenStore]（不碰
 * Keystore）、[FakeSyncSettingsStore]、真实 [BackupExporter]/[BackupImporter]（空仓或按
 * 需播种，验证零知识快照复用既有的备份流水线）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        File(context.cacheDir, "sync").deleteRecursively()
    }

    // ---- 认证 ----

    @Test
    fun `注册成功留存令牌与邮箱且已登录`() {
        val api = FakeSyncApi()
        val settings = FakeSyncSettingsStore()
        val tokenStore = InMemoryTokenStore()
        val coordinator = buildCoordinator(api, settings, tokenStore)

        coordinator.register("me@x.com", "password123".toCharArray())
        waitUntil { coordinator.state.value.loggedIn }

        assertEquals("access-1", runBlocking { tokenStore.getAccess() })
        assertEquals("refresh-1", runBlocking { tokenStore.getRefresh() })
        assertEquals("me@x.com", settings.accountEmail.value)
        assertTrue(coordinator.state.value.loggedIn)
        assertNull(coordinator.state.value.error)
    }

    @Test
    fun `登录失败给出错误且不改变登录态`() {
        val api = FakeSyncApi()
        api.authResult = SyncAuthResult.Failure(401, "bad_credentials", "邮箱或密码错误")
        val settings = FakeSyncSettingsStore()
        val tokenStore = InMemoryTokenStore()
        val coordinator = buildCoordinator(api, settings, tokenStore)

        coordinator.login("me@x.com", "password123".toCharArray())
        waitUntil { coordinator.state.value.error != null }

        assertFalse(coordinator.state.value.loggedIn)
        assertNull(runBlocking { tokenStore.getAccess() })
        assertTrue(coordinator.state.value.error!!.contains("邮箱或密码错误"))
    }

    @Test
    fun `登出清空令牌邮箱与口令`() {
        val api = FakeSyncApi()
        val settings = FakeSyncSettingsStore()
        val tokenStore = InMemoryTokenStore()
        val coordinator = buildCoordinator(api, settings, tokenStore)

        coordinator.login("me@x.com", "password123".toCharArray())
        waitUntil { coordinator.state.value.loggedIn }
        coordinator.logout()
        waitUntil { !coordinator.state.value.loggedIn }

        assertNull(runBlocking { tokenStore.getAccess() })
        assertNull(settings.accountEmail.value)
        assertFalse(coordinator.state.value.loggedIn)
    }

    // ---- push（仅状态快照） ----

    @Test
    fun `syncNow 先导出仅状态包再上传且无源文件条目`() {
        val api = FakeSyncApi()
        val settings = FakeSyncSettingsStore()
        val tokenStore = InMemoryTokenStore()
        val coordinator = buildCoordinator(api, settings, tokenStore)

        coordinator.login("me@x.com", "password123".toCharArray())
        waitUntil { coordinator.state.value.loggedIn }
        runBlocking { settings.ensureDeviceId() }
        coordinator.syncNow()
        waitUntil { coordinator.state.value.lastSyncAt != null }

        assertTrue("应调用上传", api.calls.contains("putSnapshot"))
        val cipher = api.lastPut!!.third
        val zipFile = File(tmp.root, "pushed.zip").apply {
            writeBytes(Base64.getDecoder().decode(cipher))
        }
        ZipFile(zipFile).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            assertTrue("应有 manifest：$names", names.contains("manifest.json"))
            assertFalse("仅状态快照不得含源文件条目：$names", names.any { it.startsWith("books/") })
        }
        assertEquals("device-self", api.lastPut!!.first)
        assertNull(coordinator.state.value.error)
    }

    // ---- pull（progressOnly 合并） ----

    @Test
    fun `syncNow 先pull 再push 且pull 按sha256 合并进度`() {
        val api = FakeSyncApi()
        val settings = FakeSyncSettingsStore()
        val tokenStore = InMemoryTokenStore()
        val (coordinator, repoA, repoB, textStore) = buildCoordinatorWithBooks(api, settings, tokenStore)

        // 预构建「仅状态」快照（来自 repoA，sourceMissing=true）
        val snapshotFile = File(tmp.root, "snap.zip")
        runBlocking {
            BackupExporter(repoA, textStore).export(
                snapshotFile,
                "password123".toCharArray(),
                includeSources = false,
            )
        }
        val cipher = Base64.getEncoder().encodeToString(snapshotFile.readBytes())
        api.getSnapshotResult = SyncSnapshotResult.Success(SyncSnapshot(cipher, 123L))
        api.devicesResult = SyncDevicesResult.Success(listOf(SyncDevice("device-other", 123L)))

        coordinator.login("me@x.com", "password123".toCharArray())
        waitUntil { coordinator.state.value.loggedIn }
        runBlocking { settings.ensureDeviceId() }
        coordinator.syncNow()
        waitUntil { coordinator.state.value.lastSyncAt != null }

        // 远端书（device-other）进度已按 sha256 合并进本机 repoB
        assertEquals(1, repoB.savedPositions.size)
        assertEquals("device-other", api.lastGetDevice)
        // 调用顺序：先 listDevices 再 putSnapshot
        assertTrue(
            "应先 pull 后 push：${api.calls}",
            api.calls.indexOf("listDevices") < api.calls.indexOf("putSnapshot"),
        )
    }

    @Test
    fun `本机无此书时pull 记失败而非中断`() {
        val api = FakeSyncApi()
        val settings = FakeSyncSettingsStore()
        val tokenStore = InMemoryTokenStore()
        val (coordinator, _, _, _) = buildCoordinatorWithBooks(api, settings, tokenStore)

        // 快照里是一本 repoB 没有的书：用一份随机密文（解密后 repoB 找不到 sha）
        val orphanFile = File(tmp.root, "orphan.zip")
        runBlocking {
            BackupExporter(MemoryBookRepository(), FakeBookTextStore().apply { booksRoot = tmp.newFolder("b") })
                .export(orphanFile, "password123".toCharArray(), includeSources = false)
        }
        val cipher = Base64.getEncoder().encodeToString(orphanFile.readBytes())
        api.getSnapshotResult = SyncSnapshotResult.Success(SyncSnapshot(cipher, 1L))
        // 设备列表为空 → pull 无设备可拉，这里改为直接验证 restore 失败分支：放一台设备
        api.devicesResult = SyncDevicesResult.Success(listOf(SyncDevice("device-other", 1L)))

        coordinator.login("me@x.com", "password123".toCharArray())
        waitUntil { coordinator.state.value.loggedIn }
        runBlocking { settings.ensureDeviceId() }
        coordinator.syncNow()
        waitUntil { coordinator.state.value.lastSyncAt != null }

        // 尽管某设备合并失败，syncNow 仍完成（push 照常）
        assertTrue(api.calls.contains("putSnapshot"))
    }

    // ---- 401 自动刷新重试 ----

    @Test
    fun `上传遇401 先刷新令牌再重试一次`() {
        val api = FakeSyncApi()
        api.authResult = SyncAuthResult.Success("access-1", "refresh-1", 3600)
        api.putSnapshotResults = mutableListOf(
            SyncUnitResult.Failure(401, "expired", null),
            SyncUnitResult.Success,
        )
        val settings = FakeSyncSettingsStore()
        val tokenStore = InMemoryTokenStore()
        val coordinator = buildCoordinator(api, settings, tokenStore)

        coordinator.login("me@x.com", "password123".toCharArray())
        waitUntil { coordinator.state.value.loggedIn }
        // 刷新返回新令牌
        api.authResult = SyncAuthResult.Success("access-2", "refresh-2", 3600)
        runBlocking { settings.ensureDeviceId() }
        coordinator.syncNow()
        waitUntil { coordinator.state.value.lastSyncAt != null }

        assertEquals(1, api.calls.count { it == "refresh" })
        assertEquals(2, api.calls.count { it == "putSnapshot" })
        assertEquals("access-2", api.lastPut!!.second)
        assertNull(coordinator.state.value.error)
    }

    // ---- 工具 ----

    private fun buildCoordinator(
        api: SyncApi,
        settings: SyncSettingsStore,
        tokenStore: SyncTokenStore,
    ): SyncCoordinator {
        val textStore = FakeBookTextStore().apply { booksRoot = tmp.newFolder("books") }
        val exporter = BackupExporter(MemoryBookRepository(), textStore)
        val importer = BackupImporter(
            bookRepository = MemoryBookRepository(),
            gateway = FakeImportGateway(),
            stateWriter = BookStateWriter(MemoryBookRepository()),
            workDir = File(context.cacheDir, "sync/work"),
        )
        return SyncCoordinator(context, api, tokenStore, settings, exporter, importer, scope)
    }

    /** 构造带书的 coordinator：repoA 有书 X（lastReadAt=100），repoB 有同 sha 的较旧书（50）。 */
    private fun buildCoordinatorWithBooks(
        api: SyncApi,
        settings: SyncSettingsStore,
        tokenStore: SyncTokenStore,
    ): Quad {
        val textStore = FakeBookTextStore().apply { booksRoot = tmp.newFolder("books") }
        val repoA = MemoryBookRepository()
        val repoB = MemoryBookRepository()
        val bookX = seedBook(repoA, textStore, dirName = "dir-x", title = "书X", content = "内容X", lastReadAt = 100L)
        repoB.seed(
            testBook(
                id = 99,
                title = "书X本地",
                sha256 = bookX.sha256,
                dirName = "dir-local",
                lastReadAt = 50L,
                progressPercent = 0.1f,
            ),
        )
        val exporter = BackupExporter(repoA, textStore)
        val importer = BackupImporter(
            bookRepository = repoB,
            gateway = FakeImportGateway(),
            stateWriter = BookStateWriter(repoB),
            workDir = File(context.cacheDir, "sync/work"),
        )
        val coordinator = SyncCoordinator(context, api, tokenStore, settings, exporter, importer, scope)
        return Quad(coordinator, repoA, repoB, textStore)
    }

    private data class Quad(
        val coordinator: SyncCoordinator,
        val repoA: MemoryBookRepository,
        val repoB: MemoryBookRepository,
        val textStore: FakeBookTextStore,
    )

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        error("等待条件超时（${timeoutMs}ms），当前状态：${buildCoordinatorStateProbe()}")
    }

    private fun buildCoordinatorStateProbe(): String = "n/a"

    /** 记录调用的 fake [SyncApi]。 */
    class FakeSyncApi : SyncApi {
        val calls = mutableListOf<String>()
        var authResult: SyncAuthResult = SyncAuthResult.Success("access-1", "refresh-1", 3600)
        var meResult: SyncUserResult = SyncUserResult.Success("me@x.com", null)
        var putSnapshotResults: MutableList<SyncUnitResult> = mutableListOf(SyncUnitResult.Success)
        var getSnapshotResult: SyncSnapshotResult = SyncSnapshotResult.Success(null)
        var devicesResult: SyncDevicesResult = SyncDevicesResult.Success(emptyList())
        var deleteResult: SyncUnitResult = SyncUnitResult.Success

        var lastPut: Triple<String, String, String>? = null
        var lastGetDevice: String? = null
        var lastListDevice: String? = null
        private var putIndex = 0

        override suspend fun register(email: String, password: String): SyncAuthResult {
            calls += "register"
            return authResult
        }

        override suspend fun login(email: String, password: String): SyncAuthResult {
            calls += "login"
            return authResult
        }

        override suspend fun refresh(refreshToken: String): SyncAuthResult {
            calls += "refresh"
            return authResult
        }

        override suspend fun me(accessToken: String): SyncUserResult {
            calls += "me"
            return meResult
        }

        override suspend fun putSnapshot(
            deviceId: String,
            accessToken: String,
            ciphertext: String,
            lastModified: Long,
        ): SyncUnitResult {
            calls += "putSnapshot"
            lastPut = Triple(deviceId, accessToken, ciphertext)
            return putSnapshotResults[putIndex++]
        }

        override suspend fun getSnapshot(deviceId: String, accessToken: String): SyncSnapshotResult {
            calls += "getSnapshot"
            lastGetDevice = deviceId
            return getSnapshotResult
        }

        override suspend fun listDevices(deviceId: String, accessToken: String): SyncDevicesResult {
            calls += "listDevices"
            lastListDevice = deviceId
            return devicesResult
        }

        override suspend fun deleteSnapshot(deviceId: String, accessToken: String): SyncUnitResult {
            calls += "deleteSnapshot"
            return deleteResult
        }
    }

    /** 内存版 [SyncSettingsStore]（不碰 DataStore）。 */
    class FakeSyncSettingsStore : SyncSettingsStore {
        val enabled = kotlinx.coroutines.flow.MutableStateFlow(false)
        val email = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val device = kotlinx.coroutines.flow.MutableStateFlow("")

        override val syncEnabled = enabled
        override val accountEmail = email
        override val deviceId = device

        override suspend fun setSyncEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }

        override suspend fun setAccountEmail(email: String?) {
            this.email.value = email
        }

        override suspend fun ensureDeviceId(): String {
            if (device.value.isEmpty()) device.value = "device-self"
            return device.value
        }
    }
}
