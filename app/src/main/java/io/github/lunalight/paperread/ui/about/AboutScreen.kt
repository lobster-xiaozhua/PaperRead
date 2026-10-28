package io.github.lunalight.paperread.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lunalight.paperread.BuildConfig
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.diag.DiagnosticsExportCoordinator
import io.github.lunalight.paperread.diag.DiagnosticsSummary
import io.github.lunalight.paperread.diag.DiagnosticsUiState

/**
 * 关于 / 信息页（S1）：应用信息、隐私承诺、开源许可、项目主页与反馈、
 * 无第三方依赖声明、维护者。
 *
 * 对应 SPEC US-8（AC-8.4 ~ AC-8.9）：外链一律以系统浏览器 ACTION_VIEW 打开，
 * 不内置 WebView。权限口径（2026-10-18 / 2026-10-19 用户拍板）：允许申请最小权限，
 * 声明项为 INTERNET + 朗读前台服务三件套——INTERNET 供局域网传书 / 迁移 / 同步
 * （不访问公网），前台服务三件套供朗读后台保活与通知控制（系统 TTS 离线合成）；
 * 涉及权限的文案经字符串资源集中管理，保证页面陈述与实际合并 Manifest 一致。
 *
 * 隐私说明一页（T-009 · AC-20.3）经 [onOpenPrivacy] 进入：权限清单逐项用途、
 * 网络行为承诺、备份加密与诊断导出的数据边界。
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
    onOpenPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val container = (context.applicationContext as? PaperReadApp)?.container
    val diagnostics = remember(container) {
        container?.let {
            DiagnosticsExportCoordinator(context, it.diagnosticsExporter, it.appScope)
        }
    }
    val diagnosticsState = diagnostics?.state?.collectAsStateWithLifecycle()?.value
        ?: DiagnosticsUiState()

    // 摘要对话框（AC-19.2）→ CreateDocument（AC-19.1）→ 导出
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            // CreateDocument 返回的 Uri 恒为「本次新建」→ 失败 / 取消可清理（AC-19.6）
            diagnostics?.export(uri, targetCreatedByThisExport = true)
        }
    }

    LaunchedEffect(diagnosticsState.message) {
        val message = diagnosticsState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        diagnostics?.clearMessage()
    }
    LaunchedEffect(diagnosticsState.error) {
        val error = diagnosticsState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        diagnostics?.clearError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        text = stringResource(
                            R.string.about_version_full,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 功能简介 ----
            AboutSectionLabel(stringResource(R.string.about_section_features))
            AboutBody(stringResource(R.string.about_intro))

            // ---- 隐私承诺 ----
            AboutSectionLabel(stringResource(R.string.about_section_privacy))
            AboutBody(stringResource(R.string.about_privacy_promise))

            // ---- 隐私说明一页入口（AC-20.3：权限清单 / 网络行为 / 数据边界） ----
            AboutEntryItem(
                title = stringResource(R.string.about_privacy_entry_title),
                subtitle = stringResource(R.string.about_privacy_entry_subtitle),
                leadingIcon = Icons.Filled.Info,
                onClick = onOpenPrivacy,
            )

            // ---- 开源许可 ----
            AboutSectionLabel(stringResource(R.string.about_section_license))
            AboutEntryItem(
                title = "Apache License 2.0",
                subtitle = stringResource(R.string.about_license_view_full),
                leadingIcon = Icons.Filled.Info,
                onClick = onOpenLicense,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 项目主页与反馈（系统浏览器打开） ----
            AboutSectionLabel(stringResource(R.string.about_section_project))
            AboutEntryItem(
                title = stringResource(R.string.about_github_home),
                subtitle = AboutLinks.HOME,
                leadingIcon = Icons.Filled.Home,
                onClick = { openUrl(context, AboutLinks.HOME) },
            )
            AboutEntryItem(
                title = stringResource(R.string.about_feedback),
                subtitle = stringResource(R.string.about_feedback_subtitle),
                leadingIcon = Icons.Filled.Email,
                onClick = { openUrl(context, AboutLinks.ISSUES) },
            )

            // ---- 诊断信息导出（T-021 · AC-19.1：与 Issue 入口并列） ----
            AboutEntryItem(
                title = stringResource(R.string.about_diagnostics_title),
                subtitle = stringResource(R.string.about_diagnostics_subtitle),
                leadingIcon = Icons.Filled.Info,
                onClick = { diagnostics?.loadSummary() },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 第三方依赖声明 ----
            AboutSectionLabel(stringResource(R.string.about_section_third_party))
            AboutBody(stringResource(R.string.about_third_party_body))

            // ---- 维护者 ----
            AboutSectionLabel(stringResource(R.string.about_section_maintainer))
            AboutBody("lobster-xiaozhua")

            Spacer(Modifier.height(24.dp))
        }
    }

    // AC-19.2：先展示摘要（条数 + 数据边界），用户确认后才选保存位置
    diagnosticsState.summary?.let { summary ->
        DiagnosticsSummaryDialog(
            summary = summary,
            onConfirm = {
                createDocument.launch(defaultDiagnosticsFileName())
                diagnostics?.dismissSummary()
            },
            onDismiss = { diagnostics?.dismissSummary() },
        )
    }
}

/**
 * 诊断摘要对话框（AC-19.2）。
 *
 * 只展示「包里有什么」——版本 / 设备 / 崩溃条数 / 导入失败条数 + 数据边界声明；
 * 不在这一步读日志正文（用户还没决定要不要导出）。
 */
@Composable
private fun DiagnosticsSummaryDialog(
    summary: DiagnosticsSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostics_summary_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.diagnostics_summary_body,
                        summary.appVersion,
                        summary.versionCode,
                        summary.device,
                        summary.crashCount,
                        summary.importErrorCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.diagnostics_summary_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.diagnostics_summary_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.diagnostics_summary_cancel))
            }
        },
    )
}

/** 建议文件名：`paperread-diagnostics-20260501-1030.zip`（时间戳便于用户区分多次导出）。 */
private fun defaultDiagnosticsFileName(): String {
    val stamp = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
    return "paperread-diagnostics-$stamp.zip"
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
        Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
    }
}
