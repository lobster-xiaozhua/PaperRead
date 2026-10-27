package io.github.lunalight.paperread.diag

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 崩溃日志环形存储（T-021 · AC-19.3 / AC-19.4 / AC-19.7）。
 *
 * 落盘 `filesDir/diagnostics/crash-<ts>-<seq>.log`，**最多 10 份**，写第 11 份时
 * 删最旧的。为什么是环形而不是单文件：单文件要么无限增长（用户没清过就吃满
 * 存储），要么每次覆盖（只剩最后一次崩溃，看不出「同一个崩溃连崩三次」）。
 *
 * 每份日志的内容（AC-19.3）：
 * ```
 * time: 2026-05-01T10:00:00Z
 * app: 1.2.0 (4)
 * device: Xiaomi Mi 9 (API 29)
 * thread: main (id=2)
 * exception: java.lang.IllegalStateException: boom
 * --- stack ---
 * <printStackTrace 全文>
 * ```
 *
 * ## 链式 handler（关键）
 *
 * [install] 会把**原来的** default handler 接在自己后面，并且**一定**调用它：
 * 系统正是靠这个 handler 弹「应用已停止」对话框并回收进程。把它吞掉，崩溃就
 * 从「系统提示 FC」变成「应用静默消失」——比不记录日志更糟。所以本类的
 * handler 结构是「先尽力记录，再无条件转发」。
 *
 * 记录本身也不许抛：磁盘满 / 目录被删 / 权限异常都不该改变崩溃路径的行为，
 * 故 [record] 内部全部 `runCatching`，失败返回 null。
 */
class CrashLogStore(
    private val dir: File,
    private val device: DeviceInfo = DeviceInfo.current(),
    private val appVersion: String,
    private val versionCode: Int,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 同一毫秒内多次记录时的序号（文件名仍可排序，见 [logFiles]）。 */
    private val sequence = AtomicInteger(0)

    /**
     * 现有崩溃日志，**按时间升序**（最旧在前）。
     *
     * 文件名以 13 位时间戳打头，等宽数字下字典序即时间序；末尾的序号只在
     * 同毫秒内生效，不影响跨时间戳的排序。
     */
    fun logFiles(): List<File> =
        (dir.listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) } ?: emptyArray())
            .sortedBy { it.name }

    /** 记录一次崩溃，返回落盘文件（写失败返回 null，绝不抛）。 */
    fun record(thread: Thread, error: Throwable): File? = runCatching {
        val timestamp = clock()
        val payload = buildPayload(timestamp, thread, error)
        dir.mkdirs()
        val file = File(dir, "$FILE_PREFIX$timestamp-${sequence.incrementAndGet()}.log")
        file.writeText(payload)
        trim()
        file
    }.getOrNull()

    /**
     * 把本类接成默认崩溃处理器。
     *
     * @param previous 原来是默认处理器（系统的 FC 对话框就靠它）；默认取当前值。
     *   **显式参数**是为了让测试能注入一个假 handler 并断言它被调用。
     */
    fun install(previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()) {
        Thread.setDefaultUncaughtExceptionHandler(handler(previous))
    }

    /** 构造链式处理器（不安装，便于单测直接调用）。 */
    fun handler(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            // 1. 尽力记录（写不出来也继续往下走）
            record(thread, error)
            // 2. 无条件转发给原处理器：吞掉它等于让系统收不到崩溃，
            //    用户看到的是「应用直接消失」而不是「应用已停止」
            previous?.uncaughtException(thread, error)
        }

    // ---- 内部 ----

    private fun buildPayload(timestamp: Long, thread: Thread, error: Throwable): String {
        val stack = runCatching {
            val writer = StringWriter()
            PrintWriter(writer).use { error.printStackTrace(it) }
            writer.toString()
        }.getOrElse { "${error.javaClass.name}: ${error.message}" }

        return buildString {
            appendLine("time: ${Instant.ofEpochMilli(timestamp)}")
            appendLine("app: $appVersion ($versionCode)")
            appendLine("device: ${device.summary}")
            appendLine("thread: ${thread.name} (id=${thread.id})")
            appendLine("exception: ${error.javaClass.name}: ${error.message}")
            appendLine("--- stack ---")
            append(stack.take(MAX_STACK_CHARS))
        }
    }

    /** 超出上限时删最旧的若干份（AC-19.4 的「环形」）。 */
    private fun trim() {
        val files = logFiles()
        if (files.size <= maxFiles) return
        files.dropLast(maxFiles).forEach { runCatching { it.delete() } }
    }

    companion object {

        /** 保留份数（AC-19.4：环形 10 份）。 */
        const val DEFAULT_MAX_FILES = 10

        /** 诊断目录名（`filesDir/diagnostics`）。 */
        const val DIR_NAME = "diagnostics"

        private const val FILE_PREFIX = "crash-"

        /** 单份日志的堆栈上限：64KB，防一个超长 cause 链把文件写到几 MB。 */
        private const val MAX_STACK_CHARS = 64 * 1024
    }
}
