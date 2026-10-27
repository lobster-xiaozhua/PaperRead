package io.github.lunalight.paperread.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.data.font.CustomFont
import io.github.lunalight.paperread.data.font.CustomFontStore
import io.github.lunalight.paperread.data.font.FontImportFailure
import io.github.lunalight.paperread.data.font.FontImportResult
import io.github.lunalight.paperread.data.settings.ReaderSettings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 字体区块（T-018 / US-16）：内置字族 + 自定义字体导入 / 选用 / 删除。
 *
 * 为什么整块自带数据源而不是由宿主注入：字体库与阅读设置是两条独立的数据流
 * （一个是文件 + 元数据，一个是偏好），而本区块被设置页与阅读器弹层**共用**
 * （AC-8.3：同一套控件、同一份数据）。若把 store 从宿主传进来，两个宿主都要
 * 各自装配一遍，正是「两套实现」的开始。故这里从 `AppContainer` 取
 * [CustomFontStore]——与 BookshelfScreen / SettingsScreen 取容器的口径一致。
 *
 * 文案全部走 strings.xml（AC-18.6 / 硬约束「文案进资源」），错误提示就地显示
 * 在区块下方：导入是低频操作，不值得为它占用整页的 Snackbar 通道。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomFontSection(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as? PaperReadApp)?.container?.customFontStore
    }
    val fonts by remember(store) {
        store?.fonts ?: flowOf(emptyList<CustomFont>())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    var notice by remember { mutableStateOf<FontNotice?>(null) }
    val scope = rememberCoroutineScope()

    // SAF 选择器：TTF / OTF 的 MIME 各厂商注册得五花八门（font 通配、
    // application/x-font-ttf、application/vnd.ms-opentype，很多文件管理器干脆
    // 报 application/octet-stream），故一次列全；真正的把关在魔数校验（AC-16.2）。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || store == null) return@rememberLauncherForActivityResult
        scope.launch {
            notice = when (val result = store.import(uri)) {
                is FontImportResult.Success -> {
                    // 导入即选用：用户点「导入字体」的意图就是马上用它
                    onUpdate { it.copy(customFontId = result.font.id) }
                    FontNotice.Imported(result.font.displayName)
                }

                is FontImportResult.Failure -> FontNotice.ImportFailed(result.reason)
            }
        }
    }

    // ---- 内置字族 ----
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = settings.customFontId == null && settings.fontFamily == "serif",
            onClick = { onUpdate { it.copy(fontFamily = "serif", customFontId = null) } },
            label = { Text(stringResource(R.string.settings_font_builtin_serif)) },
        )
        FilterChip(
            selected = settings.customFontId == null && settings.fontFamily == "sans",
            onClick = { onUpdate { it.copy(fontFamily = "sans", customFontId = null) } },
            label = { Text(stringResource(R.string.settings_font_builtin_sans)) },
        )
    }

    // ---- 自定义字体列表（显示名 + 来源文件名 + 选用 / 删除，AC-16.3） ----
    if (fonts.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_font_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    } else {
        fonts.forEach { font ->
            val selected = settings.customFontId == font.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = font.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.settings_font_source, font.sourceFileName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { onUpdate { it.copy(customFontId = font.id) } },
                    enabled = !selected,
                ) {
                    Text(
                        stringResource(
                            if (selected) R.string.settings_font_in_use else R.string.settings_font_apply,
                        ),
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            store?.delete(font.id)
                            // AC-16.5：删掉的正是当前字体 → 立刻清偏好回退，并明说回退了
                            if (settings.customFontId == font.id) {
                                onUpdate { it.copy(customFontId = null) }
                                notice = FontNotice.DeletedInUse(font.displayName)
                            } else {
                                notice = FontNotice.Deleted(font.displayName)
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.settings_font_delete, font.displayName),
                    )
                }
            }
        }
    }

    Spacer(Modifier.padding(top = 4.dp))
    TextButton(onClick = { picker.launch(FONT_MIME_TYPES) }) {
        Text(stringResource(R.string.settings_font_import))
    }
    Text(
        text = stringResource(R.string.settings_font_import_hint, CustomFontStore.DEFAULT_MAX_BYTES / MB),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    notice?.let { FontNoticeText(it) }
}

/** 区块内的一次性提示（导入 / 删除结果）。 */
private sealed interface FontNotice {
    data class Imported(val name: String) : FontNotice

    data class Deleted(val name: String) : FontNotice

    /** AC-16.5：删除的正是正在使用的字体，除「已删除」外还要交代已回退。 */
    data class DeletedInUse(val name: String) : FontNotice

    data class ImportFailed(val reason: FontImportFailure) : FontNotice
}

@Composable
private fun FontNoticeText(notice: FontNotice) {
    val (text, isError) = when (notice) {
        is FontNotice.Imported -> stringResource(R.string.settings_font_imported, notice.name) to false
        is FontNotice.Deleted -> stringResource(R.string.settings_font_deleted, notice.name) to false
        is FontNotice.DeletedInUse ->
            stringResource(R.string.settings_font_deleted_in_use, notice.name) to false

        is FontNotice.ImportFailed -> when (notice.reason) {
            FontImportFailure.UNSUPPORTED_FORMAT ->
                stringResource(R.string.settings_font_error_unsupported) to true

            FontImportFailure.TOO_LARGE ->
                stringResource(
                    R.string.settings_font_error_too_large,
                    CustomFontStore.DEFAULT_MAX_BYTES / MB,
                ) to true

            FontImportFailure.UNREADABLE ->
                stringResource(R.string.settings_font_error_unreadable) to true
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = Modifier.padding(top = 4.dp),
    )
}

private const val MB = 1024L * 1024L

/**
 * 打开系统文件选择器时的 MIME 过滤。
 *
 * `application/octet-stream` 是必需项：很多 ROM 的文件管理器不认 font 通配，
 * 只按「二进制文件」分类，不给它这个类型用户就选不到自己的 .ttf。
 */
private val FONT_MIME_TYPES = arrayOf(
    "font/*",
    "application/octet-stream",
    "application/x-font-ttf",
    "application/vnd.ms-opentype",
)
