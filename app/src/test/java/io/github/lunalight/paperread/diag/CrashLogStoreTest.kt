package io.github.lunalight.paperread.diag

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [CrashLogStore] 单测（T-021 · AC-19.3 / AC-19.4 / AC-19.7）。
 *
 * 纯 JVM（不引 Robolectric）：本类只碰 `java.io` 与 `java.time`，设备信息是
 * 构造参数。跑得快，也证明「崩溃记录」这条路径没有隐藏的 Android 依赖
 * （崩溃钩子必须在进程最早期可用，依赖越少越可靠）。
 *
 * 最要紧的一条是**链式 handler**：[CrashLogStoreTest.链式 handler 一定调用 previous]
 * 用「假 previous + 断言被调用」把「吞掉崩溃」这条路钉死——吞掉它，系统就
 * 不弹 FC 对话框了。
 */
class CrashLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private var now = 1_700_000_000_000L

    private fun store(maxFiles: Int = CrashLogStore.DEFAULT_MAX_FILES) = CrashLogStore(
        dir = dir,
        device = DeviceInfo(manufacturer = "Xiaomi", model = "Mi 9", apiLevel = 29),
        appVersion = "1.2.0",
        versionCode = 4,
        maxFiles = maxFiles,
        clock = { now },
    )

    @Before
    fun setUp() {
        dir = File(tmp.root, CrashLogStore.DIR_NAME)
    }

    @After
    fun tearDown() {
        // 任何用例都不许把全局 default handler 留在自己人手里
        Thread.setDefaultUncaughtExceptionHandler(null)
    }

    // ---- 内容 ----

    @Test
    fun `崩溃日志包含时间 版本 设备 线程与堆栈`() {
        val file = store().record(Thread.currentThread(), IllegalStateException("boom"))

        assertNotNull(file)
        val text = file!!.readText()
        assertTrue("缺少时间戳：$text", text.contains("time: 2023-11-14T22:13:20Z"))
        assertTrue("缺少应用版本：$text", text.contains("app: 1.2.0 (4)"))
        assertTrue("缺少设备型号：$text", text.contains("device: Xiaomi Mi 9 (API 29)"))
        assertTrue("缺少线程名：$text", text.contains("thread: ${Thread.currentThread().name}"))
        assertTrue(
            "缺少异常类型与消息：$text",
            text.contains("exception: java.lang.IllegalStateException: boom"),
        )
        assertTrue("缺少堆栈标记：$text", text.contains("--- stack ---"))
        assertTrue("缺少堆栈内容：$text", text.contains("CrashLogStoreTest"))
    }

    @Test
    fun `同一毫秒内多次记录不会互相覆盖`() {
        val store = store()
        val thread = Thread.currentThread()
        val first = store.record(thread, IllegalStateException("first"))
        val second = store.record(thread, IllegalStateException("second"))

        assertNotNull(first)
        assertNotNull(second)
        assertFalse(first!!.name == second!!.name)
        assertEquals(2, store.logFiles().size)
    }

    // ---- 环形淘汰（AC-19.4：10 份） ----

    @Test
    fun `超过上限时删最旧的 保留最新十份`() {
        val store = store()
        val thread = Thread.currentThread()
        repeat(12) { index ->
            now = 1_700_000_000_000L + index
            store.record(thread, IllegalStateException("boom-$index"))
        }

        val files = store.logFiles()
        assertEquals(CrashLogStore.DEFAULT_MAX_FILES, files.size)
        assertTrue("最旧的应被删除", files.none { it.name.contains("1700000000000") })
        assertTrue("最新的必须在", files.any { it.name.contains("1700000000011") })
    }

    @Test
    fun `上限可配置 便于小规模断言`() {
        val store = store(maxFiles = 2)
        repeat(3) { index ->
            now = 1_700_000_000_000L + index
            store.record(Thread.currentThread(), IllegalStateException("boom-$index"))
        }
        assertEquals(2, store.logFiles().size)
    }

    // ---- 链式 handler（AC-19.7 的关键约束） ----

    @Test
    fun `链式 handler 一定调用 previous`() {
        val store = store()
        val forwarded = mutableListOf<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, error -> forwarded += error }
        val error = IllegalStateException("fatal")

        store.handler(previous).uncaughtException(Thread.currentThread(), error)

        assertEquals("必须把崩溃原样转发给系统原处理器", listOf<Throwable>(error), forwarded)
        assertEquals("同时必须把日志落盘", 1, store.logFiles().size)
    }

    @Test
    fun `记录失败也不影响调用 previous`() {
        // 把「目录」指向一个已存在的普通文件：mkdirs 失败、写文件失败 → record 返回 null
        val notADirectory = File(tmp.root, "occupied")
        notADirectory.writeText("i am a file")
        val store = CrashLogStore(
            dir = notADirectory,
            device = DeviceInfo("A", "B", 1),
            appVersion = "1.2.0",
            versionCode = 4,
        )
        var forwarded = 0

        store.handler { _, _ -> forwarded++ }
            .uncaughtException(Thread.currentThread(), IllegalStateException("x"))

        assertEquals("日志写不出来也必须转发崩溃（否则系统不弹 FC）", 1, forwarded)
    }

    @Test
    fun `install 会替换默认处理器且仍转发`() {
        val store = store()
        var forwarded = 0
        val previous = Thread.UncaughtExceptionHandler { _, _ -> forwarded++ }

        try {
            store.install(previous)
            val current = Thread.getDefaultUncaughtExceptionHandler()
            assertNotNull(current)
            current!!.uncaughtException(Thread.currentThread(), IllegalStateException("x"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(null)
        }

        assertEquals(1, forwarded)
        assertEquals(1, store.logFiles().size)
    }

    @Test
    fun `没有 previous 时自己也能记录`() {
        val store = store()
        store.handler(null).uncaughtException(Thread.currentThread(), IllegalStateException("x"))
        assertEquals(1, store.logFiles().size)
    }

    @Test
    fun `空目录时日志列表为空`() {
        assertTrue(store().logFiles().isEmpty())
    }
}
