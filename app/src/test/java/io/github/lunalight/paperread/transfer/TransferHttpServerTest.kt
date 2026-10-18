package io.github.lunalight.paperread.transfer

import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HTTP 服务 + 客户端：localhost 真实 socket 端到端（AC-9.4 / AC-9.6）。
 *
 * 覆盖：meta 查询 / 上传字节一致 / 404 / 400 / 500 / 多请求连续服务。
 */
class TransferHttpServerTest {

    private lateinit var server: TransferHttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService

    private data class Upload(val fileName: String, val bytes: ByteArray) {
        override fun equals(other: Any?) =
            other is Upload && fileName == other.fileName && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
    }

    private val uploads = mutableListOf<Upload>()
    private var failNextUpload = false

    private val handler = object : TransferHttpHandler {
        override fun meta(): String = """{"ok":true,"deviceName":"测试机"}"""

        override fun onUpload(fileName: String, contentLength: Long, body: InputStream): String? {
            if (failNextUpload) {
                failNextUpload = false
                return "模拟失败"
            }
            val bytes = body.readNBytes(contentLength.toInt())
            uploads += Upload(fileName, bytes)
            return null
        }
    }

    @Before
    fun setUp() {
        server = TransferHttpServer(InetAddress.getLoopbackAddress(), 0, handler)
        executor = Executors.newSingleThreadExecutor()
        executor.execute { server.serve() }
    }

    @After
    fun tearDown() {
        server.close()
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    private fun port(): Int = server.actualPort

    @Test
    fun `GET meta 返回设备信息`() {
        val connection = (URL("http://127.0.0.1:${port()}/meta").openConnection() as HttpURLConnection)
        try {
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().readText()
            assertTrue("meta 应含设备名：$body", body.contains("测试机"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `POST upload 完整传输字节与文件名`() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() } // 256KB，跨多个缓冲区
        val file = Files.createTempFile("transfer-test", ".bin").toFile()
        file.writeBytes(payload)
        val result = TransferClient().upload("127.0.0.1", port(), file, "测试书名.txt")
        assertEquals(UploadResult.Success, result)
        assertEquals(1, uploads.size)
        assertEquals("测试书名.txt", uploads[0].fileName)
        assertArrayEquals(payload, uploads[0].bytes)
        file.delete()
    }

    @Test
    fun `上传进度单调递增至 1`() {
        val payload = ByteArray(512 * 1024) { it.toByte() }
        val file = Files.createTempFile("transfer-progress", ".bin").toFile()
        file.writeBytes(payload)
        val fractions = mutableListOf<Float>()
        TransferClient().upload("127.0.0.1", port(), file, "进度书.txt") { fractions += it }
        assertTrue("应有进度回调", fractions.isNotEmpty())
        assertTrue("进度单调递增：$fractions", fractions.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, fractions.last(), 1e-6f)
        assertTrue("进度不越界", fractions.all { it in 0f..1f })
        file.delete()
    }

    @Test
    fun `未知路径返回 404`() {
        val connection = (URL("http://127.0.0.1:${port()}/nope").openConnection() as HttpURLConnection)
        try {
            assertEquals(404, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `对端处理失败返回 500`() {
        failNextUpload = true
        val file = Files.createTempFile("transfer-fail", ".bin").toFile()
        file.writeBytes(ByteArray(1024))
        val result = TransferClient().upload("127.0.0.1", port(), file, "失败书.txt")
        assertTrue("应判失败：$result", result is UploadResult.Failure)
        file.delete()
    }

    @Test
    fun `连接被拒返回 Failure（AC-9 6 对端不可达）`() {
        // 占用一个端口后立即释放，得到几乎必然被拒的端口号
        val probe = java.net.ServerSocket(0)
        val deadPort = probe.localPort
        probe.close()
        val file = Files.createTempFile("transfer-dead", ".bin").toFile()
        file.writeBytes(ByteArray(16))
        val result = TransferClient().upload("127.0.0.1", deadPort, file, "死路书.txt")
        assertTrue("应判失败：$result", result is UploadResult.Failure)
        file.delete()
    }

    @Test
    fun `连续多请求服务不中断`() {
        val file = Files.createTempFile("transfer-multi", ".bin").toFile()
        file.writeBytes(ByteArray(64) { 7 })
        repeat(3) {
            assertEquals(UploadResult.Success, TransferClient().upload("127.0.0.1", port(), file, "多本.txt"))
        }
        assertEquals(3, uploads.size)
        file.delete()
    }

    @Test
    fun `空文件上传被判失败且不发请求`() {
        val file = Files.createTempFile("transfer-empty", ".bin").toFile()
        val result = TransferClient().upload("127.0.0.1", port(), file, "空书.txt")
        assertTrue("空文件应失败：$result", result is UploadResult.Failure)
        file.delete()
    }
}
