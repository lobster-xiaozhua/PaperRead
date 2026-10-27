package io.github.lunalight.paperread.data.font

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lunalight.paperread.data.settings.PreferencesDataStoreProvider
import io.github.lunalight.paperread.reader.font.SfntFormat
import io.github.lunalight.paperread.reader.font.TtfMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 一份已导入的自定义字体（元数据，文件本体在 `filesDir/fonts/<id>.<ext>`）。
 *
 * @param id 稳定标识（UUID）；阅读设置里的 [io.github.lunalight.paperread.data.settings.ReaderSettings.customFontId]
 *   存的正是它——文件名会随重命名/迁移变化，id 不会
 * @param displayName 展示名：取自字体 `name` 表（[TtfMetadata]），解析失败时回退文件名
 * @param sourceFileName 导入时的来源文件名（AC-16.3 要求列表里能看到「这份字体从哪来」）
 * @param addedAt 导入时间（列表按此倒序：最近导入的排前面）
 * @param extension 落盘扩展名（ttf / otf / ttc），由魔数决定而非用户文件名
 */
data class CustomFont(
    val id: String,
    val displayName: String,
    val sourceFileName: String,
    val addedAt: Long,
    val extension: String = "ttf",
)

/** 导入失败的结构化原因（文案由 UI 层映射到 strings.xml，本模块不持有文案）。 */
enum class FontImportFailure {
    /** 魔数不是 sfnt 容器（AC-16.2：仅支持 TTF / OTF）。 */
    UNSUPPORTED_FORMAT,

    /** 超过单文件大小上限（防用一个 200MB 的字体包把磁盘吃满）。 */
    TOO_LARGE,

    /** 打不开 / 读不全（SAF 权限失效、文件被删等）。 */
    UNREADABLE,
}

/** 导入结果。 */
sealed interface FontImportResult {
    data class Success(val font: CustomFont) : FontImportResult

    data class Failure(val reason: FontImportFailure) : FontImportResult
}

/**
 * 自定义字体库（T-018 / US-16）。
 *
 * 两处存储：
 * - **文件**：`filesDir/fonts/<id>.<ext>`——私有目录，不经 SAF 暴露；
 * - **元数据**：DataStore（`custom_fonts`），一个字符串键存全部条目
 *   （见 [CustomFontCodec]）。
 *
 * 为什么元数据不用 kotlinx.serialization：本模块没有应用序列化插件，
 * 为一个 5 字段的列表引入编译器插件与运行时依赖不划算；`\u001F` 分隔的
 * 转义文本同样可测（[encode] / [decode] 都是纯函数），且**不会因为将来
 * 新增字段而让旧版本读到「无法反序列化」**——未知字段被忽略，缺失字段取默认。
 *
 * 为什么导入走内存：[TtfMetadata] 需要随机访问 `name` 表，而 SAF 的流不可
 * 回退；上限 [DEFAULT_MAX_BYTES]（20MB）意味着最坏一次性持有 20MB 字节数组，
 * 对一次性用户操作可接受（真机上字体文件普遍 < 10MB），换来的是「校验 → 落盘」
 * 单次读取、无临时文件残留。
 *
 * 线程：所有挂起方法内部切 `Dispatchers.IO`；[resolve] 是同步的轻量文件探测，
 * 供 Compose 组合期使用（见 `ReaderFonts.kt`）。
 */
class CustomFontStore(
    context: Context,
    private val dataStore: DataStore<Preferences>,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val applicationContext: Context = context.applicationContext

    /** 字体文件目录（`filesDir/fonts`）。 */
    fun fontDir(): File = File(applicationContext.filesDir, FONT_DIR_NAME)

    /** 已导入字体（按导入时间倒序）。 */
    val fonts: Flow<List<CustomFont>> = dataStore.data.map { prefs ->
        CustomFontCodec.decode(prefs[KEY_FONTS]).sortedByDescending { it.addedAt }
    }

    /** 一次性读取当前列表（非 Flow 场景 / 测试断言用）。 */
    suspend fun list(): List<CustomFont> = fonts.first()

    /**
     * 导入字体（SAF `OpenDocument` 拿到的 [uri]）。
     *
     * 校验顺序：可读 → 大小 → 魔数 → 取名字 → 落盘 → 写元数据。
     * 任何一步失败都不留文件、不写元数据。
     */
    suspend fun import(uri: Uri): FontImportResult = withContext(Dispatchers.IO) {
        val sourceFileName = queryDisplayName(uri)
        val bytes = when (val read = readAtMost(uri, maxBytes)) {
            is ReadOutcome.Ok -> read.bytes
            ReadOutcome.TooLarge -> return@withContext FontImportResult.Failure(FontImportFailure.TOO_LARGE)
            ReadOutcome.Unreadable -> return@withContext FontImportResult.Failure(FontImportFailure.UNREADABLE)
        }
        // AC-16.2：魔数校验。改过后缀的压缩包 / 图片在这里被拦下
        val format = TtfMetadata.sniff(bytes)
            ?: return@withContext FontImportResult.Failure(FontImportFailure.UNSUPPORTED_FORMAT)

        val id = UUID.randomUUID().toString()
        val extension = format.extension()
        val file = File(fontDir().apply { mkdirs() }, "$id.$extension")
        val font = CustomFont(
            id = id,
            displayName = TtfMetadata.displayName(bytes) ?: fallbackName(sourceFileName),
            sourceFileName = sourceFileName,
            addedAt = clock(),
            extension = extension,
        )
        try {
            file.writeBytes(bytes)
            dataStore.edit { prefs ->
                prefs[KEY_FONTS] = CustomFontCodec.encode(
                    CustomFontCodec.decode(prefs[KEY_FONTS]) + font,
                )
            }
        } catch (e: IOException) {
            file.delete()
            return@withContext FontImportResult.Failure(FontImportFailure.UNREADABLE)
        }
        FontImportResult.Success(font)
    }

    /**
     * 删除字体：先删文件再删元数据。
     *
     * 顺序上刻意「先文件后元数据」：如果第二步失败，最坏结果是列表里留一条
     * 指向已删文件的记录，[resolve] 返回 null → 正文静默回退默认字体
     * （AC-16.6 的降级路径），而不是「文件还在但用户以为删掉了」。
     */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        filesFor(id).forEach { it.delete() }
        dataStore.edit { prefs ->
            val remaining = CustomFontCodec.decode(prefs[KEY_FONTS]).filterNot { it.id == id }
            if (remaining.isEmpty()) {
                prefs.remove(KEY_FONTS)
            } else {
                prefs[KEY_FONTS] = CustomFontCodec.encode(remaining)
            }
        }
    }

    /**
     * 把 id 解析成实际文件（AC-16.6 的入口）。
     *
     * @return 文件不存在 / 已被删除时返回 null——调用方据此回退默认字体
     */
    fun resolve(id: String): File? = filesFor(id).firstOrNull { it.isFile && it.length() > 0L }

    /** 某 id 名下的所有候选文件（正常情况下只有一个）。 */
    private fun filesFor(id: String): List<File> {
        if (id.isBlank()) return emptyList()
        val dir = fontDir()
        val all = dir.listFiles() ?: return emptyList()
        return all.filter { it.isFile && it.name.substringBefore('.') == id }
    }

    // ---- 内部 ----

    private sealed interface ReadOutcome {
        class Ok(val bytes: ByteArray) : ReadOutcome

        data object TooLarge : ReadOutcome

        data object Unreadable : ReadOutcome
    }

    /**
     * 读到内存，但**不**超过 [limit]。
     *
     * 超限时不读完整个文件（避免用一个超大文件把内存打爆），读到 `limit + 1`
     * 字节即可判定。
     *
     * 异常口径：SAF 的 provider 是第三方代码，能抛的东西远超 `IOException`——
     * 权限失效抛 `SecurityException`、文件被删抛 `FileNotFoundException`、
     * 网盘 provider 干脆抛 `UnsupportedOperationException`。对用户而言这些都是
     * 「这份文件读不了」，故此处一并以 [ReadOutcome.Unreadable] 收敛，
     * 让上层只处理一个分支。
     */
    private fun readAtMost(uri: Uri, limit: Long): ReadOutcome = try {
        val input: InputStream = applicationContext.contentResolver.openInputStream(uri)
            ?: return ReadOutcome.Unreadable
        input.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > limit) return ReadOutcome.TooLarge
                buffer.write(chunk, 0, read)
            }
            ReadOutcome.Ok(buffer.toByteArray())
        }
    } catch (e: Exception) {
        ReadOutcome.Unreadable
    }

    /** 来源文件名：优先 SAF 的 DISPLAY_NAME，退到 Uri 末段。 */
    private fun queryDisplayName(uri: Uri): String {
        val queried = runCatching {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: FALLBACK_FILE_NAME
    }

    /** 拿不到 name 表时的展示名：来源文件名去扩展名。 */
    private fun fallbackName(sourceFileName: String): String =
        sourceFileName.substringBeforeLast('.').ifBlank { FALLBACK_FILE_NAME }

    private fun SfntFormat.extension(): String = when (this) {
        SfntFormat.OPEN_TYPE -> "otf"
        SfntFormat.COLLECTION -> "ttc"
        SfntFormat.TRUE_TYPE, SfntFormat.TRUE_TYPE_APPLE -> "ttf"
    }

    companion object {

        /** DataStore 落盘名（`filesLib/datastore/custom_fonts.preferences_pb`）。 */
        const val DATA_STORE_NAME = "custom_fonts"

        /** 字体文件子目录（`filesDir/fonts`）。 */
        const val FONT_DIR_NAME = "fonts"

        /** 单文件上限：20MB。系统字体最大也就十几 MB，超出基本是选错了文件。 */
        const val DEFAULT_MAX_BYTES: Long = 20L * 1024 * 1024

        private const val FALLBACK_FILE_NAME = "自定义字体"
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024

        private val KEY_FONTS = stringPreferencesKey("font_entries")

        /**
         * 生产装配：为 [DATA_STORE_NAME] 建一份独立的 DataStore。
         *
         * 每个进程只能调用一次（同一文件不允许两个存活实例，见
         * [PreferencesDataStoreProvider]）——由 `AppContainer` 的 lazy 保证。
         */
        fun create(context: Context): CustomFontStore = CustomFontStore(
            context = context,
            dataStore = PreferencesDataStoreProvider(context, DATA_STORE_NAME).instance,
        )
    }
}

/**
 * 字体元数据的文本编码（DataStore 单键存列表）。
 *
 * 格式：`记录(\u001E记录)*`，每条记录 `字段(\u001F字段)*`，
 * 字段值把 `\` / `\u001E` / `\u001F` 转义成 `\\` / `\R` / `\U`。
 * 坏记录（字段数不对 / 时间戳非数字）整条跳过——用户不该因为一条脏数据
 * 丢掉整个字体列表。
 */
internal object CustomFontCodec {

    private const val FIELD_SEP = '\u001F'
    private const val RECORD_SEP = '\u001E'
    private const val FIELD_COUNT = 5

    fun encode(fonts: List<CustomFont>): String = fonts.joinToString(RECORD_SEP.toString()) { font ->
        listOf(
            font.id,
            font.displayName,
            font.sourceFileName,
            font.addedAt.toString(),
            font.extension,
        ).joinToString(FIELD_SEP.toString()) { escape(it) }
    }

    fun decode(raw: String?): List<CustomFont> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val fields = record.split(FIELD_SEP)
            if (fields.size != FIELD_COUNT) return@mapNotNull null
            val addedAt = fields[3].toLongOrNull() ?: return@mapNotNull null
            val id = unescape(fields[0])
            if (id.isBlank()) return@mapNotNull null
            CustomFont(
                id = id,
                displayName = unescape(fields[1]).ifBlank { FALLBACK_DISPLAY_NAME },
                sourceFileName = unescape(fields[2]),
                addedAt = addedAt,
                extension = unescape(fields[4]).ifBlank { "ttf" },
            )
        }
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                RECORD_SEP -> append("\\R")
                FIELD_SEP -> append("\\U")
                else -> append(char)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    '\\' -> append('\\')
                    'R' -> append(RECORD_SEP)
                    'U' -> append(FIELD_SEP)
                    else -> append(value[index + 1])
                }
                index += 2
            } else {
                append(char)
                index += 1
            }
        }
    }

    private const val FALLBACK_DISPLAY_NAME = "自定义字体"
}
