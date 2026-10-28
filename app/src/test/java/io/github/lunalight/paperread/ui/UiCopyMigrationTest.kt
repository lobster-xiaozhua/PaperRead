package io.github.lunalight.paperread.ui

import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.transfer.MigrationReport
import io.github.lunalight.paperread.transfer.SyncReport
import io.github.lunalight.paperread.ui.bookshelf.SortOrder
import io.github.lunalight.paperread.ui.transfer.TransferViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-022 文案资源化的**逐字一致**断言（US-21 AC-21.5）。
 *
 * 各 ViewModel / Screen 的单测只断言「发的是哪条文案」（[UiText] 相等性），
 * 不解析 Resources；本文件在 Robolectric 下把资源真正解析出来，与**资源化前
 * 的原字面量**逐一比对——这是「纯搬运、内容零变更」的集中验收点：
 * 任何人在 strings.xml 里顺手改措辞，这里先红。
 *
 * 覆盖面：各屏代表性文案 + 全部拼接句（迁移 / 同步汇总）+ UiText 三形态
 * （Plain / Res / Concat）的解析语义。未逐条列出的文案由
 * `scripts/check-hardcoded-strings.sh`（UI 层清零）与各屏行为测试兜底。
 */
@Suppress("SpellCheckingInspection")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiCopyMigrationTest {

    // ---- UiText 解析语义 ----

    @Test
    fun `Plain 原样返回不经过资源表`() {
        val text: UiText = UiText.Plain("传输不完整，已丢弃")
        assertEquals("传输不完整，已丢弃", text.resolve(context()))

        @Suppress("USELESS_ELVIS")
        val nulled: UiText? = null
        assertNull(nulled.resolveOrNull(context()))
    }

    @Test
    fun `Res 按位置参数格式化`() {
        val text = UiText.res(R.string.bookshelf_import_done, "测试书")
        assertEquals("已导入《测试书》", text.resolve(context()))
    }

    @Test
    fun `百分号字面量在资源中转义为单百分号`() {
        // reader_progress_read = 已读 %1$s%%
        val text = UiText.res(R.string.reader_progress_read, "12.3")
        assertEquals("已读 12.3%", text.resolve(context()))
    }

    @Test
    fun `concat 过滤空段且单段退化为该段本身`() {
        assertNull(UiText.concat(null, UiText.Concat(emptyList())))
        assertEquals(
            "单段不包 Concat",
            UiText.res(R.string.common_cancel),
            UiText.concat(null, UiText.res(R.string.common_cancel)),
        )
        assertEquals(
            "多段顺序拼接",
            "AB",
            UiText.concat(UiText.Plain("A"), UiText.Plain("B"))!!.resolve(context()),
        )
    }

    // ---- AC-21.5：解析结果 == 资源化前原字面量（逐屏代表） ----

    @Test
    fun `书架屏文案逐字一致`() {
        val ctx = context()
        assertEquals("已选 3 本", ctx.getString(R.string.bookshelf_selection_count, 3))
        assertEquals(
            "书架中已有《旧书》，内容与所选文件完全相同。要替换为新导入的版本吗？（替换会清除旧书的进度与书签）",
            ctx.getString(R.string.bookshelf_duplicate_message, "旧书"),
        )
        assertEquals("已读 30.0% · 第 5 章", ctx.getString(R.string.bookshelf_continue_progress, "30.0%", 5))
        assertEquals("2.5 万字", ctx.getString(R.string.import_chars_wan, "2.5"))
        assertEquals("书", ctx.getString(R.string.bookshelf_cover_glyph_fallback))
    }

    @Test
    fun `排序项文案逐字一致`() {
        val ctx = context()
        assertEquals("最近阅读", ctx.getString(SortOrder.RECENTLY_READ.labelRes))
        assertEquals("最近导入", ctx.getString(SortOrder.ADDED.labelRes))
        assertEquals("书名", ctx.getString(SortOrder.TITLE.labelRes))
        assertEquals("进度", ctx.getString(SortOrder.PROGRESS.labelRes))
    }

    @Test
    fun `阅读器与朗读文案逐字一致`() {
        val ctx = context()
        assertEquals("返回", ctx.getString(R.string.common_back))
        assertEquals("添加书签", ctx.getString(R.string.reader_add_bookmark))
        assertEquals("上一页", ctx.getString(R.string.reader_prev_page))
        assertEquals("下一页", ctx.getString(R.string.reader_next_page))
        assertEquals("第 3/10 章", ctx.getString(R.string.reader_chapter_position, 3, 10))
        assertEquals("目录 · 12", ctx.getString(R.string.reader_toc_tab_chapters, 12))
        assertEquals("开始朗读", ctx.getString(R.string.speech_start))
        assertEquals("未获得通知权限，朗读控制通知不可用；朗读仍可正常播放。", ctx.getString(R.string.speech_notification_degraded))
    }

    @Test
    fun `设置与关于屏文案逐字一致`() {
        val ctx = context()
        assertEquals("字号", ctx.getString(R.string.settings_font_size))
        assertEquals("1.5 倍", ctx.getString(R.string.settings_line_height_value, "1.5"))
        assertEquals("音量下键下一章、上键上一章（防误触默认关闭）", ctx.getString(R.string.settings_volume_key_subtitle))
        assertEquals("纸白", ctx.getString(R.string.theme_paper))
        assertEquals("版本 1.2（34）", ctx.getString(R.string.about_version_full, "1.2", 34))
        assertEquals("没有可用的浏览器", ctx.getString(R.string.about_no_browser))
    }

    @Test
    fun `传书屏文案逐字一致`() {
        val ctx = context()
        assertEquals("正在发送《书一》（1/2）", ctx.getString(R.string.transfer_sending, "书一", 1, 2))
        assertEquals("共 3 本，选择下方设备开始发送：", ctx.getString(R.string.transfer_selected_count, 3))
        assertEquals(
            "未发现在线设备。请确认两台设备处于同一 WiFi，且对端已打开传书页（发现通常不超过 3 秒）。",
            ctx.getString(R.string.transfer_devices_empty),
        )
        assertEquals("（2/5）", ctx.getString(R.string.transfer_migration_progress, 2, 5))
        assertEquals("· 坏书：该 EPUB 含 DRM 加密，无法导入", ctx.getString(R.string.transfer_migration_failed_entry, "坏书", "该 EPUB 含 DRM 加密，无法导入"))
    }

    // ---- 拼接句整句逐字一致（条件片段的拼装结果） ----

    @Test
    fun `迁移汇总整句与资源化前逐字一致`() {
        val report = MigrationReport(
            imported = 3,
            duplicates = 1,
            progressWritten = 2,
            failed = listOf(io.github.lunalight.paperread.transfer.FailedEntry("坏书", "x")),
        )
        assertEquals(
            "迁移完成：新导入 3 本，已存在 1 本，回写进度 2 本，失败 1 本",
            TransferViewModel.migrationSummary(report).resolve(context()),
        )

        // 条件片段：只有新导入时不出「，已存在…」尾巴
        val minimal = MigrationReport(imported = 1)
        assertEquals(
            "迁移完成：新导入 1 本",
            TransferViewModel.migrationSummary(minimal).resolve(context()),
        )
    }

    @Test
    fun `同步汇总整句与资源化前逐字一致`() {
        val report = SyncReport(
            localOverwritten = 2,
            localBookmarksAdded = 3,
            localOnlyBooks = 1,
            remoteOnlyBooks = 4,
            pushBack = listOf(
                io.github.lunalight.paperread.transfer.BookSyncState(
                    sha256 = "sha-a",
                    title = "A",
                    lastChapterIndex = 0,
                    lastParagraphIndex = 0,
                    lastScrollOffset = 0,
                    lastCharOffset = 0,
                    progressPercent = 0f,
                    lastReadAt = null,
                    bookmarks = emptyList(),
                ),
            ),
        )
        assertEquals(
            "同步完成：覆盖本地进度 2 本，新增书签 3 条，回推 1 本，对端另有 4 本本地没有",
            TransferViewModel.syncSummary(report).resolve(context()),
        )
    }

    private fun context() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
}
