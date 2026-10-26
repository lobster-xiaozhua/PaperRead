package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.data.repository.ImportError
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输服务编排流程单测（T-004 / R-04 / G4，AC-9.5 / AC-9.6 / AC-9.7）。
 *
 * 经 [DefaultLanTransferManager] 真实路径覆盖服务启动错误分支（未接入 WiFi /
 * 发现端口占用）与接收处理分支（传输不完整 / 对端处理失败 / 重复导入 /
 * 文件名穿越）。鉴权矩阵与 HTTP 语义属协议层领地（TransferHttpServerTest），
 * 此处不重复。
 */
class TransferServerFlowTest : TransferFlowTestBase() {

    override val discoveryPort = 45470

    private fun pendingImport(sha256: String = "sha-up") = PendingImport(
        dirName = "dir-$sha256",
        displayName = "书.txt",
        title = "收到的书",
        author = "未知",
        format = io.github.lunalight.paperread.domain.model.BookFormat.TXT,
        charsetName = "UTF-8",
        strategy = SplitStrategy.CONSERVATIVE,
        sha256 = sha256,
        fileSize = 16L,
        totalChars = 16L,
        chapters = emptyList(),
    )

    // ---- 服务启动错误分支（AC-9.7） ----

    @Test
    fun `未接入 WiFi 时启动失败并提示`() {
        val manager = createManager(wifiAddressProvider = { null })

        manager.startService()

        waitUntil { manager.serviceState.value.error != null }
        assertEquals("未接入 WiFi，无法启动传书服务", manager.serviceState.value.error)
        assertFalse(manager.serviceState.value.running)
    }

    @Test
    fun `发现端口被占用时启动失败且不残留服务状态`() {
        // 先占住发现端口（不设 reuseAddress：Linux 下后到者 bind 必失败）
        val occupied = DatagramSocket(null).apply {
            bind(InetSocketAddress(discoveryPort))
        }
        try {
            val manager = createManager()

            manager.startService()

            waitUntil { manager.serviceState.value.error != null }
            assertTrue(
                "应提示端口占用：${manager.serviceState.value.error}",
                manager.serviceState.value.error!!.startsWith("发现端口被占用"),
            )
            assertFalse(manager.serviceState.value.running)
        } finally {
            occupied.close()
        }
    }

    // ---- 接收处理分支（AC-9.5 / AC-9.6） ----

    @Test
    fun `传输不完整时接收状态报错且不落库`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        val port = manager.httpPort()

        // 声明 64KB 只发 1KB 后断链：服务端 spill 读到 EOF（与协议层同手法）
        val digest = sha256Hex(ByteArray(64 * 1024))
        val token = issueTokenDigest(secret, "POST", "/upload", digest)
        Socket(loopbackHost, port).use { socket ->
            val out: OutputStream = socket.getOutputStream()
            out.write(
                buildString {
                    append("POST /upload HTTP/1.1\r\nHost: $loopbackHost\r\n")
                    append("Authorization: Bearer $token\r\n")
                    append("X-Device-Id: peer-a\r\n")
                    append("X-Book-Name: 断链书.txt\r\n")
                    append("X-Content-Sha256: $digest\r\n")
                    append("Content-Length: ${64 * 1024}\r\nConnection: close\r\n\r\n")
                }.toByteArray(),
            )
            out.write(ByteArray(1024))
            out.flush()
        }

        waitUntil { manager.receiveState.value.error != null }
        assertEquals("传输不完整，已丢弃", manager.receiveState.value.error)
        assertNull(manager.receiveState.value.pending)
        assertFalse(managerSpillDir(manager).listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun `对端处理失败（DRM）时错误上浮且临时文件清理`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        importer.prepareResult = PrepareResult.Failure(ImportError.DrmEncrypted("encrypted"))

        val result = clientFor(secret).upload(
            loopbackHost,
            manager.httpPort(),
            tempFile("drm", "drm-content"),
            "drm.epub",
        )

        // 接收侧话术来自结构化 ImportError（AC-9.6：提示失败原因）
        waitUntil { manager.receiveState.value.error != null }
        assertEquals("该 EPUB 含 DRM 加密，无法导入", manager.receiveState.value.error)
        assertNull(manager.receiveState.value.pending)
        assertFalse("半成品应清理", File(context.cacheDir, "transfer/drm.epub").exists())
        // DRM 书不弹导入确认：上传语义成功（字节已送达），失败在接收侧呈现
        assertTrue("$result", result is UploadResult.Success)
    }

    @Test
    fun `重复导入（SHA-256 命中）时跳过并提示已存在`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        val existing = book(id = 7, title = "旧书架", sha256 = "dup-sha", lastReadAt = 10L)
        importer.prepareResult = PrepareResult.Duplicate(existing)

        val result = clientFor(secret).upload(
            loopbackHost,
            manager.httpPort(),
            tempFile("dup", "dup-content"),
            "重复书.txt",
        )

        waitUntil { manager.receiveState.value.error != null }
        assertEquals("《旧书架》已存在于书架，已跳过", manager.receiveState.value.error)
        assertNull(manager.receiveState.value.pending)
        assertFalse("重复内容不得留半成品", File(context.cacheDir, "transfer/重复书.txt").exists())
        assertTrue("$result", result is UploadResult.Success)
    }

    @Test
    fun `格式不支持时错误上浮`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        importer.prepareResult = PrepareResult.Unsupported(ImportError.UnsupportedFormat("pdf"))

        clientFor(secret).upload(
            loopbackHost,
            manager.httpPort(),
            tempFile("pdf", "pdf-content"),
            "文档.pdf",
        )

        waitUntil { manager.receiveState.value.error != null }
        assertEquals("仅支持 TXT / EPUB 格式", manager.receiveState.value.error)
    }

    @Test
    fun `文件名穿越被净化为 basename 且不写出目标目录`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        importer.prepareResult = PrepareResult.Success(pendingImport())

        val result = clientFor(secret).upload(
            loopbackHost,
            manager.httpPort(),
            tempFile("evil", "evil-content"),
            "../../evil.txt",
        )

        // 目录成分被剥离：落点在 cacheDir/transfer/ 内，而非穿越位置
        waitUntil { manager.receiveState.value.pending != null }
        assertTrue("$result", result is UploadResult.Success)
        assertTrue(File(context.cacheDir, "transfer/evil.txt").isFile)
        assertFalse(File(context.cacheDir, "evil.txt").exists())
        assertFalse(File(context.cacheDir.parentFile!!, "evil.txt").exists())
    }

    @Test
    fun `非法文件名（全非法字符）被拒且不落盘`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)

        val result = clientFor(secret).upload(
            loopbackHost,
            manager.httpPort(),
            tempFile("bad", "bad-content"),
            ":*?\"<>|",
        )

        assertTrue("非法名应判失败：$result", result is UploadResult.Failure)
        assertNull(manager.receiveState.value.pending)
        assertNull(manager.receiveState.value.error)
        val transferDir = File(context.cacheDir, "transfer")
        // spill 目录由服务端创建（落盘前置），此处只断言无「收到的文件」残留
        val files = transferDir.listFiles()?.filter { it.isFile }?.map { it.name }
        assertTrue("不得写入任何文件：$files", files.isNullOrEmpty())
    }

    @Test
    fun `正常上传进入待确认并保留临时文件`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        importer.prepareResult = PrepareResult.Success(pendingImport())

        val result = clientFor(secret).upload(
            loopbackHost,
            manager.httpPort(),
            tempFile("ok", "ok-content"),
            "正常书.txt",
        )

        waitUntil { manager.receiveState.value.pending != null }
        assertTrue("$result", result is UploadResult.Success)
        val pending = manager.receiveState.value.pending!!
        assertEquals("收到的书", pending.title)
        assertTrue(File(context.cacheDir, "transfer/正常书.txt").isFile)

        // 确认导入：临时文件删除 + 一次性成功提示
        importer.confirmResult = 42L
        manager.confirmReceived()
        waitUntil { manager.receiveState.value.importedTitle != null }
        assertEquals("收到的书", manager.receiveState.value.importedTitle)
        assertFalse(File(context.cacheDir, "transfer/正常书.txt").exists())
    }

    @Test
    fun `确认入库失败时保留待确认并提示错误`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        importer.prepareResult = PrepareResult.Success(pendingImport())
        clientFor(secret).upload(
            loopbackHost,
            manager.httpPort(),
            tempFile("cf", "cf-content"),
            "入库书.txt",
        )
        waitUntil { manager.receiveState.value.pending != null }

        importer.failNextConfirm = true
        manager.confirmReceived()

        waitUntil { manager.receiveState.value.error != null }
        assertTrue(manager.receiveState.value.error!!.startsWith("导入失败"))
        // 保留 pending 与临时文件：用户可重试确认
        assertNotNullPending(manager)
        assertTrue(File(context.cacheDir, "transfer/入库书.txt").isFile)
    }

    @Test
    fun `取消接收清理半成品并复位状态`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        importer.prepareResult = PrepareResult.Success(pendingImport())
        clientFor(secret).upload(
            loopbackHost,
            manager.httpPort(),
            tempFile("cx", "cx-content"),
            "放弃书.txt",
        )
        waitUntil { manager.receiveState.value.pending != null }

        manager.cancelReceived()

        assertNull(manager.receiveState.value.pending)
        assertFalse(File(context.cacheDir, "transfer/放弃书.txt").exists())
    }

    private fun assertNotNullPending(manager: DefaultLanTransferManager) {
        assertTrue("pending 应保留", manager.receiveState.value.pending != null)
    }

    private fun managerSpillDir(manager: DefaultLanTransferManager): File =
        File(context.cacheDir, "transfer/spill")

    @Test
    fun `重新解析收到的书可切换编码与策略`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        importer.prepareResult = PrepareResult.Success(pendingImport())
        clientFor(secret).upload(
            loopbackHost,
            manager.httpPort(),
            tempFile("rp", "rp-content"),
            "重解书.txt",
        )
        waitUntil { manager.receiveState.value.pending != null }

        val reparsed = pendingImport().copy(title = "重解后", charsetName = "GBK")
        importer.reparseResult = reparsed
        manager.reparseReceived("GBK", SplitStrategy.AGGRESSIVE)

        waitUntil { manager.receiveState.value.pending?.title == "重解后" }
        assertEquals("GBK", importer.reparseCalls.single().second)
    }
}
