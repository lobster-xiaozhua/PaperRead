package io.github.lunalight.paperread.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.lunalight.paperread.backup.BackupFontReference
import io.github.lunalight.paperread.backup.BackupImportPreview
import io.github.lunalight.paperread.backup.BackupManifest
import io.github.lunalight.paperread.backup.BackupPreview
import io.github.lunalight.paperread.backup.BackupUiState
import io.github.lunalight.paperread.ui.theme.PaperReadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页「备份与恢复」区块的弹层行为（P3-1 / P3-3）。
 *
 * 只渲染 [BackupSection] 本身（不拉起 `SettingsScreen` 的整页 VM），状态由
 * 用例直接构造——目的是把「用户看见什么 / 按钮能不能点」钉死在 UI 层：
 *
 * 刻意**不开** `@GraphicsMode(NATIVE)`：本类只断言语义树（文本存在 / 按钮
 * 可用性），不涉及像素或视口尺寸——NATIVE 图形栈每个测试类要拷一次 native 字体，
 * 在内存受限环境里会加剧 `LicenseScreenTest` 这类渲染用例的超时抖动（G9）。
 *
 * 1. **P3-1**：恢复预览副标题必须交代「备份格式版本 + 字体引用清单」，而不是
 *    只在字体缺失时露一行警告（AC-14.8 明确要求清单记录版本与字体引用）；
 * 2. **P3-3**：口令判据与领域层一致（`BackupCrypto.isValidPassword`）——
 *    「8 位全空格」必须在这里就被拦下并给出明确文案，不能放行到导出阶段
 *    抛 `IllegalArgumentException`、让用户只看到泛化的「导出失败」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class BackupSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `恢复预览副标题展示备份格式版本与字体引用`() {
        val manifest = BackupManifest(
            version = 1,
            bookCount = 1,
            progressCount = 1,
            bookmarkCount = 0,
            fontReferences = listOf(
                BackupFontReference(name = "serif", displayName = "serif", builtIn = true),
                BackupFontReference(name = "sans", displayName = "sans", builtIn = true),
            ),
        )
        showSection(
            state = BackupUiState(
                importPreview = BackupImportPreview(
                    manifest = manifest,
                    bookTitles = listOf("书一"),
                    fontFallbacks = emptyList(),
                    missingEntries = 0,
                    passwordRequired = false,
                ),
            ),
        )

        composeRule.onNodeWithText("备份格式 v1 · 字体：serif、sans").assertExists()
    }

    @Test
    fun `恢复预览在字体缺失时于同一行标注缺失项`() {
        val manifest = BackupManifest(
            version = 1,
            bookCount = 1,
            fontReferences = listOf(
                BackupFontReference(name = "serif", displayName = "serif", builtIn = true),
                BackupFontReference(name = "custom-kai", displayName = "霞鹜文楷"),
            ),
        )
        showSection(
            state = BackupUiState(
                importPreview = BackupImportPreview(
                    manifest = manifest,
                    bookTitles = listOf("书一"),
                    fontFallbacks = listOf("霞鹜文楷"),
                    missingEntries = 0,
                    passwordRequired = false,
                ),
            ),
        )

        composeRule.onNodeWithText(
            "备份格式 v1 · 字体：serif、霞鹜文楷（缺失：霞鹜文楷，将回退默认字体）",
        ).assertExists()
    }

    @Test
    fun `八位全空格口令在UI层被拦并提示判据`() {
        showSection(
            state = BackupUiState(
                exportPreview = BackupPreview(
                    bookCount = 1,
                    progressCount = 1,
                    bookmarkCount = 0,
                    fontReferences = emptyList(),
                    totalSourceBytes = 128L,
                ),
            ),
        )

        enrollEncryption()

        // 8 个半角空格：长度达标、但全为空白——领域层判定非法，UI 必须一致
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("        ")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("口令至少 8 位且不能全为空格").assertExists()
        composeRule.onNodeWithText("选择保存位置").assertIsNotEnabled()
    }

    @Test
    fun `合法口令与确认一致时允许选择保存位置`() {
        showSection(
            state = BackupUiState(
                exportPreview = BackupPreview(
                    bookCount = 1,
                    progressCount = 1,
                    bookmarkCount = 0,
                    fontReferences = emptyList(),
                    totalSourceBytes = 128L,
                ),
            ),
        )

        enrollEncryption()

        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("correct-horse")
        fields[1].performTextInput("correct-horse")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("口令至少 8 位且不能全为空格").assertDoesNotExist()
        composeRule.onNodeWithText("选择保存位置").assertIsEnabled()
    }

    @Test
    fun `长度不足的口令沿用同一条判据文案`() {
        showSection(
            state = BackupUiState(
                exportPreview = BackupPreview(
                    bookCount = 1,
                    progressCount = 0,
                    bookmarkCount = 0,
                    fontReferences = emptyList(),
                    totalSourceBytes = 0L,
                ),
            ),
        )

        enrollEncryption()

        val field = composeRule.onAllNodes(hasSetTextAction())[0]
        field.performTextInput("1234567")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("口令至少 8 位且不能全为空格").assertExists()

        // 补到 8 位后错误消失（同一判据，不分「太短」与「全空白」两套文案）
        field.performTextClearance()
        field.performTextInput("12345678")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("口令至少 8 位且不能全为空格").assertDoesNotExist()
    }

    /** 勾选「使用口令加密」——文本本身不可点，可点的是旁边的 Checkbox。 */
    private fun enrollEncryption() {
        composeRule.onNode(isToggleable()).performClick()
        composeRule.waitForIdle()
    }

    private fun showSection(state: BackupUiState) {
        composeRule.setContent {
            PaperReadTheme {
                BackupSection(
                    state = state,
                    onLoadExportPreview = {},
                    onExport = { _, _ -> },
                    onOpenBackup = {},
                    onRestore = {},
                    onCancel = {},
                    onDismissExportPreview = {},
                    onDismissImportPreview = {},
                    onDismissReport = {},
                    onDismissError = {},
                )
            }
        }
    }
}
