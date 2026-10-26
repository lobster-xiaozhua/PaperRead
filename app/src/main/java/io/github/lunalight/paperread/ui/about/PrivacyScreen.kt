package io.github.lunalight.paperread.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.R

/**
 * 隐私说明一页（T-009 · R-02 / AC-20.3 / AC-20.4）。
 *
 * 内容三块，与架构设计 E-5 对齐：
 *  1. 权限清单：逐项权限名 + 用途。权限清单数据是 [PrivacyPermissionItems]
 *     这一代码单一来源——AboutScreenTest 把它与合并 Manifest 的
 *     requestedPermissions 比对（AC-20.4：页面陈述与实际一致），
 *      Manifest 增删权限而不同步本清单会直接测试失败；
 *  2. 网络行为：「不访问公网」承诺（CI 侧由
 *     scripts/check-network-behavior.sh 静态断言兜底）；
 *  3. 备份加密与诊断导出的数据边界。
 */
internal object PrivacyPermissionItems {

    /** 一项权限的展示数据：[name] 为合并 Manifest 中的权限全名。 */
    data class Item(val name: String, val purposeRes: Int)

    /**
     * 页面展示的权限清单（顺序即展示顺序）。
     *
     * 与合并 Manifest 的一致性由 AboutScreenTest 断言；新增权限必须
     * 先过隐私评审再进这里，防止「先斩后奏」式扩权。
     */
    val items: List<Item> = listOf(
        Item(
            name = "android.permission.INTERNET",
            purposeRes = R.string.privacy_permission_internet_purpose,
        ),
        Item(
            name = "android.permission.FOREGROUND_SERVICE",
            purposeRes = R.string.privacy_permission_foreground_service_purpose,
        ),
        Item(
            name = "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            purposeRes = R.string.privacy_permission_foreground_service_media_playback_purpose,
        ),
        Item(
            name = "android.permission.POST_NOTIFICATIONS",
            purposeRes = R.string.privacy_permission_post_notifications_purpose,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.privacy_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )

            // ---- 权限清单（逐项用途；数据源 = PrivacyPermissionItems） ----
            PrivacySectionLabel(stringResource(R.string.privacy_section_permissions))
            PrivacyPermissionItems.items.forEach { item ->
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(item.purposeRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 网络行为 ----
            PrivacySectionLabel(stringResource(R.string.privacy_section_no_public_network))
            Text(
                text = stringResource(R.string.privacy_no_public_network_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 备份加密与诊断导出的数据边界 ----
            PrivacySectionLabel(stringResource(R.string.privacy_section_data_boundary))
            Text(
                text = stringResource(R.string.privacy_data_boundary_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PrivacySectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
