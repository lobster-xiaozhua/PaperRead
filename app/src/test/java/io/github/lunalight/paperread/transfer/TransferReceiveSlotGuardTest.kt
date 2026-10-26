package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File
import java.io.OutputStream
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接收槽位守卫回归补测（QA 独立验证补充，T-004 / T-005 交叉点）。
 *
 * 针对审查发现的两处「有实现无断言」的保护补测（不重写既有用例）：
 * 1. **单槽语义的进度分支清理**：顶替上传中途断链时（onUpload 永不执行），
 *    旧 pending 的元数据作废与旧临时文件清理只可能来自落盘进度分支——既有
 *    单槽用例（[TransferConcurrencyTest]）走完整顶替路径，onUpload 自带的
 *    deleteReceivedTemp 亦可完成清理，无法区分被测分支；断链路径是唯一
 *    能 isolate 该分支的场景；
 * 2. **onReceiveAborted 的 BUNDLE 分支 SEND 守卫**：发送迁移期间对端 bundle
 *    上传中途断链时，中止回调不得把 SEND 状态踩成 error（[MigrationFlowTest]
 *    busy 用例只覆盖「完整上传被拒」路径，未覆盖中断路径——若守卫退化，
 *    该场景下 busy 拒绝同样会被中止反馈破坏）。
 *
 * 经 [DefaultLanTransferManager] 真实路径驱动（真实 HTTP socket + 真实编排），
 * 与 T-004 / T-005 既有用例同一夹具（[TransferFlowTestBase]）。
 */
class TransferReceiveSlotGuardTest : TransferFlowTestBase() {

    override val discoveryPort = 45477

    private fun pendingImport(sha256: String) = PendingImport(
        dirName = "dir-$sha256",
        displayName = "书.txt",
        title = "收到的书",
        author = "未知",
        format = BookFormat.TXT,
        charsetName = "UTF-8",
        strategy = SplitStrategy.CONSERVATIVE,
        sha256 = sha256,
        fileSize = 16L,
        totalChars = 16L,
        chapters = emptyList(),
    )

    private fun managerSpillDir(manager: DefaultLanTransferManager): File =
        File(context.cacheDir, "transfer/spill")

    /**
     * 顶替上传**中途断链**时的单槽语义（ isolate onReceiveProgress 的 UPLOAD
     * 作废分支）：第二本只发 1KB 即断链，onUpload 永不执行——旧 pending 的
     * 元数据作废与旧临时文件清理只可能来自落盘进度分支，此处是唯一能观测
     * 它的路径（正常顶替路径下 onUpload 自带的 deleteReceivedTemp 亦可清理，
     * 无法区分二者，故不断言）。
     */
    @Test
    fun `顶替上传中途断链时旧 pending 元数据作废且旧临时文件清理`() {
        val identity = DeviceIdentityStore(context)
        val manager = createManager(identityStore = identity)
        manager.startAndAwaitRunning()
        val secret = manager.pairAsSender(identity)

        importer.prepareResult = PrepareResult.Success(pendingImport("slot-1"))
        assertEquals(
            UploadResult.Success,
            clientFor(secret).upload(
                loopbackHost,
                manager.httpPort(),
                tempFile("slot_one", "x".repeat(512)),
                "第一本.txt",
            ),
        )
        waitUntil { manager.receiveState.value.pending?.sha256 == "slot-1" }
        assertTrue("旧书临时文件应已落盘", File(context.cacheDir, "transfer/第一本.txt").isFile)

        // 第二本交叠到达但中途断链：声明 64KB 只发 1KB 后关闭（与
        // TransferServerFlowTest 传输不完整同手法）。落盘进度分支先触发
        // （作废旧 pending），onUpload 因断链永不执行
        val digest = sha256Hex(ByteArray(64 * 1024))
        val token = issueTokenDigest(secret, "POST", "/upload", digest)
        Socket(loopbackHost, manager.httpPort()).use { socket ->
            val out: OutputStream = socket.getOutputStream()
            out.write(
                buildString {
                    append("POST /upload HTTP/1.1\r\nHost: $loopbackHost\r\n")
                    append("Authorization: Bearer $token\r\n")
                    append("X-Device-Id: peer-a\r\n")
                    append("X-Book-Name: 第二本.txt\r\n")
                    append("X-Content-Sha256: $digest\r\n")
                    append("Content-Length: ${64 * 1024}\r\nConnection: close\r\n\r\n")
                }.toByteArray(),
            )
            out.write(ByteArray(1024))
            out.flush()
            waitUntil { managerSpillDir(manager).listFiles()?.isNotEmpty() == true }
        }
        waitUntil { manager.receiveState.value.error != null }
        assertEquals("传输不完整，已丢弃", manager.receiveState.value.error)

        // 单槽语义：旧 pending 元数据作废 + 旧临时文件清理 + 不产生新 pending
        assertEquals(
            "第一本的 pending 应被作废",
            listOf("slot-1"),
            importer.cancelledPendings.map { it.sha256 },
        )
        assertFalse(
            "旧临时文件应被清理（不得残留半成品）",
            File(context.cacheDir, "transfer/第一本.txt").exists(),
        )
        assertFalse(
            "断链的第二本不得落盘",
            File(context.cacheDir, "transfer/第二本.txt").exists(),
        )
        assertNull(manager.receiveState.value.pending)
    }

    @Test
    fun `发送迁移期间 bundle 上传中断不踩踏 SEND 状态`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        repository.emitBooks(listOf(book(id = 1, title = "书一", sha256 = "sha-1")))
        // 把发送侧协程稳定挂在「收集书库」相位（与 MigrationFlowTest busy 用例同法），
        // 使 SEND 状态窗口不再依赖竞态
        repository.booksGate = CompletableDeferred()

        manager.startMigration(peerDevice(manager, "peer-x", "对端X"))
        waitUntil {
            manager.migration.value.active &&
                manager.migration.value.direction == MigrationDirection.SEND
        }

        // 对端发起 bundle 上传但中途断链：声明 64KB 只发 1KB 后关闭连接。
        // 服务端 spill 读到 EOF → 清理 spill + onReceiveAborted(BUNDLE)——
        // 该回调先于 onBundle 的 busy 拒绝触发，是 SEND 状态被踩踏的入口
        val digest = sha256Hex(ByteArray(64 * 1024))
        val token = issueTokenDigest(secret, "POST", "/bundle", digest)
        val port = manager.httpPort()
        Socket(loopbackHost, port).use { socket ->
            val out: OutputStream = socket.getOutputStream()
            out.write(
                buildString {
                    append("POST /bundle HTTP/1.1\r\nHost: $loopbackHost\r\n")
                    append("Authorization: Bearer $token\r\n")
                    append("X-Device-Id: peer-a\r\n")
                    append("X-Bundle-Name: m.zip\r\n")
                    append("X-Content-Sha256: $digest\r\n")
                    append("Content-Length: ${64 * 1024}\r\nConnection: close\r\n\r\n")
                }.toByteArray(),
            )
            out.write(ByteArray(1024))
            out.flush()
            // spill 文件已创建：确保中止路径被真正走到（而非连接尚未被处理）
            waitUntil { managerSpillDir(manager).listFiles()?.isNotEmpty() == true }
        }
        // 断链 → 服务端读 EOF：实现内先删 spill 再触发中止回调，
        // spill 清空即中止回调已执行
        waitUntil { managerSpillDir(manager).listFiles()?.isNotEmpty() != true }

        // 守卫回归：SEND 状态在整个中止窗口内保持原状——
        // 活跃中、方向仍为 SEND、无错误、无报告、收到的字节不落盘
        val deadline = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < deadline) {
            val migration = manager.migration.value
            assertTrue("SEND 状态应保持活跃：$migration", migration.active)
            assertEquals(MigrationDirection.SEND, migration.direction)
            assertNull("中止反馈不得写入迁移状态：$migration", migration.error)
            assertNull(migration.report)
            assertFalse(
                "busy / 中断的 bundle 不得落盘",
                File(context.cacheDir, "transfer/incoming.bundle.zip").exists(),
            )
            Thread.sleep(20)
        }

        // 收尾：释放挂起的门使发送协程走取消分支，再取消迁移任务
        repository.booksGate?.cancel()
        manager.cancelMigration()
        waitUntil { manager.migration.value.error != null }
        assertEquals("已取消迁移", manager.migration.value.error)
    }
}
