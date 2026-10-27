package io.github.lunalight.paperread.transfer

/**
 * 文件名净化（HTTP 上传通道的路径穿越防线，与 [MigrationBundleCodec] 解包时的
 * zip slip 防护对称）：取末段 basename → 拒绝 "." / ".." → 剔除控制字符与
 * Windows 非法字符 → 限长 [SANITIZED_NAME_MAX_LENGTH]。
 *
 * 保留 Unicode 可打印字符：Android 文件系统无保留设备名（CON / PRN / COM1 等
 * 为 Windows 语义），中文书名等非 ASCII 名是正常输入，一并剔除会丢书名。
 *
 * 拆分说明（T-012）：原与 `DefaultLanTransferManager` 同文件；它是纯函数、
 * 无状态、被 `TransferReceiveController` 与单测直接调用，故独立成文件
 * （包级 internal 不变，调用方零改动）。
 *
 * @return 净化后的文件名；输入不合法（空 basename / 全非法字符）时返回 null，
 *   调用方应拒绝该上传
 */
internal fun sanitizeFileName(raw: String): String? {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    if (base.isBlank() || base == "." || base == "..") return null
    val cleaned = base.filter { it.code >= 32 && it !in "/\\:%*?\"<>|" }
        .take(SANITIZED_NAME_MAX_LENGTH)
    return cleaned.ifBlank { null }
}

/** 净化后文件名长度上限（超长名截断而非拒绝：对端展示名常带长书名）。 */
private const val SANITIZED_NAME_MAX_LENGTH = 64
