package io.github.lunalight.paperread.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.ui.settings.ReadingPreferencesPanel

/**
 * 阅读栈内的阅读设置弹层。
 *
 * 目录 / 书签弹层（[TocSheet]）随 T-011 的 God File 拆分移至 `ReaderChrome.kt`
 * （阅读器外壳部件：顶栏 / 底栏 / 目录 / 书签），本文件只保留设置弹层。
 */

/**
 * 阅读设置弹层：主题 / 字体 / 排版 / 亮度 / 行为开关。
 *
 * S2 起控件本体抽取为 [ReadingPreferencesPanel]，与设置页共用同一套控件与
 * 数据源（AC-8.3）；本弹层仅负责 ModalBottomSheet 外壳与滚动容器。
 * 所有修改经 [onUpdate] 立即写入 DataStore（VM 内 Flow 回流刷新正文）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            ReadingPreferencesPanel(settings = settings, onUpdate = onUpdate)
        }
    }
}
