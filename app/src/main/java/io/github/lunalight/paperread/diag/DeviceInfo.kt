package io.github.lunalight.paperread.diag

import android.os.Build

/**
 * 诊断包里的设备信息（AC-19.3「设备型号 / API」）。
 *
 * 只取厂商 + 型号 + API 级别三项：足以判断「是不是只有某个 ROM / 某个 API
 * 级别上崩」，又不足以定位到人（不含 IMEI / ANDROID_ID / 序列号 / 手机号）。
 *
 * [sanitize] 不是洁癖：部分定制 ROM 的 `Build.MODEL` 里带换行或超长字符串，
 * 直接拼进日志会把「一行一个字段」的格式打乱，让摘要不可读。
 */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val apiLevel: Int,
) {

    /** 单行摘要：`Xiaomi Mi 9 (API 29)`。 */
    val summary: String
        get() {
            val name = listOf(manufacturer, model)
                .map(::sanitize)
                .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                .distinct()
                .joinToString(" ")
            return "${name.ifBlank { "unknown" }} (API $apiLevel)"
        }

    companion object {

        /** 当前设备。Robolectric 下返回 `robolectric robolectric (API 34)`。 */
        fun current(): DeviceInfo = DeviceInfo(
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            apiLevel = Build.VERSION.SDK_INT,
        )

        /** 去掉换行 / 制表 / 连续空白，并截断超长型号（防脏 ROM 数据撑爆日志行）。 */
        internal fun sanitize(value: String): String =
            value.replace(Regex("\\s+"), " ").trim().take(MAX_FIELD_LENGTH)

        private const val MAX_FIELD_LENGTH = 48
    }
}
