package io.github.lunalight.paperread.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.BuildConfig
import io.github.lunalight.paperread.R

/**
 * 关于 / 信息页（S1）：应用信息、隐私承诺、开源许可、项目主页与反馈、
 * 无第三方依赖声明、维护者。
 *
 * 对应 SPEC US-8（AC-8.4 ~ AC-8.9）：外链一律以系统浏览器 ACTION_VIEW 打开，
 * 不内置 WebView。权限口径（2026-10-18 用户拍板）：允许申请最小权限，
 * 唯一声明项为 INTERNET——局域网传书 / 迁移 / 同步使用，不访问公网；
 * 涉及权限的文案经字符串资源集中管理，保证页面陈述与实际合并 Manifest 一致。
 */
internal object AboutLinks {
    const val HOME = "https://github.com/lobster-xiaozhua/PaperRead"
    const val ISSUES = "https://github.com/lobster-xiaozhua/PaperRead/issues"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLicense: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
            // ---- 应用头：图标 + 应用名 + 版本 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_book),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.about_app_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 功能简介 ----
            AboutSectionLabel("功能简介")
            AboutBody(stringResource(R.string.about_intro))

            // ---- 隐私承诺 ----
            AboutSectionLabel("隐私承诺")
            AboutBody(stringResource(R.string.about_privacy_promise))

            // ---- 开源许可 ----
            AboutSectionLabel("开源许可")
            AboutEntryItem(
                title = "Apache License 2.0",
                subtitle = "查看许可全文",
                leadingIcon = Icons.Filled.Info,
                onClick = onOpenLicense,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 项目主页与反馈（系统浏览器打开） ----
            AboutSectionLabel("项目与反馈")
            AboutEntryItem(
                title = "GitHub 项目主页",
                subtitle = AboutLinks.HOME,
                leadingIcon = Icons.Filled.Home,
                onClick = { openUrl(context, AboutLinks.HOME) },
            )
            AboutEntryItem(
                title = "问题反馈",
                subtitle = "在 GitHub 提交 Issue",
                leadingIcon = Icons.Filled.Email,
                onClick = { openUrl(context, AboutLinks.ISSUES) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 第三方依赖声明 ----
            AboutSectionLabel("第三方依赖")
            AboutBody(
                "无第三方依赖：纸阅不集成任何第三方 SDK 与统计库，" +
                    "全部功能基于 AndroidX 与 Kotlin 官方库实现。",
            )

            // ---- 维护者 ----
            AboutSectionLabel("维护者")
            AboutBody("lobster-xiaozhua")

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AboutBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** 可点击入口行：标题 + 副标题 + 前导图标 + 尾随箭头。 */
@Composable
private fun AboutEntryItem(
    title: String,
    subtitle: String,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        leadingContent = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** 以系统浏览器打开外链；无浏览器可用时 Toast 兜底（不崩、不内置 WebView）。 */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}
