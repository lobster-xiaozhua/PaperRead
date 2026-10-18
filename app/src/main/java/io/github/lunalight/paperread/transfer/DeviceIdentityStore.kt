package io.github.lunalight.paperread.transfer

import android.content.Context
import android.os.Build
import java.io.File
import java.util.UUID

/**
 * 设备标识存储（AC-11.4：随机生成并持久化的 UUID，禁用硬件标识）。
 *
 * - 身份（冲突解决 / 去重）用 [deviceId]：首次生成随机 UUID，落盘
 *   `filesDir/transfer/device_id.txt`（换机迁移不随云备份走，见备份规则排除项）；
 * - 展示名（AC-9.1 设备名）用 [deviceName]：取机型型号，便于人在设备列表里辨认。
 *   型号仅作人类可读标签，不参与身份判定。
 */
class DeviceIdentityStore(private val context: Context) {

    private val idFile: File
        get() = File(File(context.filesDir, "transfer"), "device_id.txt")

    /** 随机 UUID（首次调用时生成并持久化）。 */
    fun deviceId(): String {
        idFile.parentFile?.mkdirs()
        val existing = runCatching { idFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        return try {
            // 先写临时文件再原子改名：避免写入中途被杀留下半截 UUID
            val tmp = File(idFile.parentFile, "device_id.txt.tmp")
            tmp.writeText(generated)
            if (!tmp.renameTo(idFile)) {
                tmp.copyTo(idFile, overwrite = true)
                tmp.delete()
            }
            generated
        } catch (e: Exception) {
            generated // 落盘失败不阻塞传书：本次会话内保持一致即可
        }
    }

    /** 人类可读设备名（机型型号）。 */
    fun deviceName(): String {
        val model = runCatching { Build.MODEL }.getOrNull()
        val manufacturer = runCatching { Build.MANUFACTURER }.getOrNull()
        return when {
            !model.isNullOrBlank() && !manufacturer.isNullOrBlank() &&
                !model.startsWith(manufacturer, ignoreCase = true) -> "$manufacturer $model"

            !model.isNullOrBlank() -> model
            else -> "Android 设备"
        }
    }
}
