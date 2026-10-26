package io.github.lunalight.paperread.quality

import io.github.lunalight.paperread.data.repository.ImportError
import io.github.lunalight.paperread.data.repository.ImportStage
import io.github.lunalight.paperread.transfer.BookStateSnapshot
import io.github.lunalight.paperread.transfer.BookmarkSnapshot
import io.github.lunalight.paperread.transfer.BookSyncState
import io.github.lunalight.paperread.transfer.MigrationManifest
import io.github.lunalight.paperread.transfer.SyncBookmark
import io.github.lunalight.paperread.transfer.SyncEnvelope
import io.github.lunalight.paperread.transfer.TRANSFER_MAGIC
import io.github.lunalight.paperread.transfer.TRANSFER_VERSION
import io.github.lunalight.paperread.transfer.TransferProtocol
import io.github.lunalight.paperread.ui.nav.AboutRoute
import io.github.lunalight.paperread.ui.nav.BookshelfRoute
import io.github.lunalight.paperread.ui.nav.LicenseRoute
import io.github.lunalight.paperread.ui.nav.PrivacyRoute
import io.github.lunalight.paperread.ui.nav.ReaderRoute
import io.github.lunalight.paperread.ui.nav.SettingsRoute
import io.github.lunalight.paperread.ui.nav.TransferRoute
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * release 变体序列化 round-trip（T-008 · R-07 / G7，架构设计 §6.2 **方案 B** 核心）。
 *
 * ## 为什么是"方案 B"
 *
 * G7 的原始口径是"R8 混淆后的运行时冒烟"——`install clientRelease` + 真机跑一遍
 * 传书 / 迁移 / 同步。本仓库 CI runner 无 KVM、跑不了模拟器，instrumented
 * 测试不可执行；为彻底规避 `androidx.test` 依赖，G7 退化为：
 *
 * | 手段 | 覆盖 | 不覆盖 |
 * |------|------|--------|
 * | `scripts/check-r8-keep.sh`（mapping 静态断言） | 契约类型与 `$$serializer` 未被 R8 裁掉 | 运行时行为 |
 * | **本测试**（clientRelease 变体单测） | release 变体下全部序列化对象的编解码契约、`ImportError` 话术常量 | **R8 混淆后**的产物行为 |
 * | 真机 install smoke（CI 注释预留） | 混淆后的真实运行时 | —— |
 *
 * 本测试跑在 `testClientReleaseUnitTest` 变体（`app/build.gradle.kts` 里把 release
 * 变体单测收窄到 `quality.*`）：同一份类路径、同一份 release 资源 / BuildConfig，
 * 编解码一旦有契约破损（字段缺失、默认值丢失、序列化器不可用）即红。
 * 它**不能**替代真机冒烟，也不声称能发现"仅混淆后出现"的问题。
 *
 * 放在 `quality` 包而非业务包：release 变体只跑本包的测试，业务测试仍走
 * clientDebug（覆盖率口径不变）。
 *
 * 与既有测试的分工：`TransferProtocolTest` / `MigrationBundleTest` / `SyncMergerTest`
 * 断言的是**业务语义**（魔数校验、合并策略）；本测试只断言**序列化契约完整性**
 * （全部字段 round-trip 后逐一相等），是 R8 语境下的最小充分集。
 */
class ReleaseSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---- 传书：在线报文 ----

    @Test
    fun `presence packet round-trips through production codec`() {
        val encoded = TransferProtocol.encodePresence(
            deviceId = "device-abc",
            deviceName = "测试设备",
            httpPort = 45456,
            isBroadcast = true,
            pairingRequired = true,
        )
        val decoded = TransferProtocol.decodePresence(encoded)
        assertNotNull("线上报文应可解码（序列化器在 release 变体下可用）", decoded)
        val packet = requireNotNull(decoded)
        assertEquals(TRANSFER_MAGIC, packet.magic)
        assertEquals(TRANSFER_VERSION, packet.version)
        assertEquals("device-abc", packet.deviceId)
        assertEquals("测试设备", packet.deviceName)
        assertEquals(45456, packet.httpPort)
        assertTrue("广播标记应保留", packet.isBroadcast)
        assertTrue("配对标记应随报文传输", packet.pairingRequired)
    }

    // ---- 进度同步：envelope / state / bookmark ----

    @Test
    fun `sync envelope round-trips with every field preserved`() {
        val bookmark = SyncBookmark(
            chapterIndex = 12,
            paragraphIndex = 3,
            charOffset = 4096,
            excerpt = "少年提灯独行，身后是沉睡的城池。",
            createdAt = 1_700_000_000_123L,
        )
        val state = BookSyncState(
            sha256 = "a".repeat(64),
            title = "荒原之始",
            lastChapterIndex = 12,
            lastParagraphIndex = 3,
            lastScrollOffset = 88,
            lastCharOffset = 4096,
            progressPercent = 42.5f,
            lastReadAt = 1_700_000_000_456L,
            bookmarks = listOf(bookmark),
        )
        val envelope = SyncEnvelope(deviceId = "device-abc", books = listOf(state))

        val encoded = json.encodeToString(SyncEnvelope.serializer(), envelope)
        val decoded = json.decodeFromString(SyncEnvelope.serializer(), encoded)

        assertEquals(envelope, decoded)
        // 逐字段复核（相等性断言之外，明确"R8 不可裁掉任何字段"的契约）
        assertEquals("device-abc", decoded.deviceId)
        assertEquals(1, decoded.version)
        val decodedState = decoded.books.single()
        assertEquals(state.sha256, decodedState.sha256)
        assertEquals(state.progressPercent, decodedState.progressPercent, 0.0f)
        assertEquals(state.lastReadAt, decodedState.lastReadAt)
        assertEquals(bookmark, decodedState.bookmarks.single())
        assertEquals("12:3:4096", decodedState.bookmarks.single().positionKey)
    }

    @Test
    fun `sync envelope keeps nullable last read time and empty bookmark list`() {
        val state = BookSyncState(
            sha256 = "b".repeat(64),
            title = "未读的书",
            lastChapterIndex = 0,
            lastParagraphIndex = 0,
            lastScrollOffset = 0,
            lastCharOffset = 0,
            progressPercent = 0f,
            lastReadAt = null,
            bookmarks = emptyList(),
        )
        val decoded = json.decodeFromString(
            SyncEnvelope.serializer(),
            json.encodeToString(
                SyncEnvelope.serializer(),
                SyncEnvelope(deviceId = "device-abc", books = listOf(state)),
            ),
        )
        assertEquals(state, decoded.books.single())
        assertNull(decoded.books.single().lastReadAt)
        assertTrue(decoded.books.single().bookmarks.isEmpty())
    }

    // ---- 换机迁移：manifest / snapshot ----

    @Test
    fun `migration manifest round-trips with every field preserved`() {
        val bookmark = BookmarkSnapshot(
            chapterIndex = 7,
            paragraphIndex = 1,
            charOffset = 512,
            excerpt = "雪落下来的时候，荒原终于安静了。",
            createdAt = 1_690_000_000_000L,
        )
        val book = BookStateSnapshot(
            sha256 = "c".repeat(64),
            title = "荒原之始",
            author = "未知",
            format = "TXT",
            encoding = "GB18030",
            fileSize = 211_358_656L,
            totalChars = 107_876_067L,
            chapterCount = 93_428,
            lastChapterIndex = 7,
            lastParagraphIndex = 1,
            lastScrollOffset = 24,
            lastCharOffset = 512,
            progressPercent = 88.25f,
            lastReadAt = 1_690_000_000_500L,
            addedAt = 1_600_000_000_000L,
            bookmarks = listOf(bookmark),
        )
        val manifest = MigrationManifest(books = listOf(book))

        val decoded = json.decodeFromString(
            MigrationManifest.serializer(),
            json.encodeToString(MigrationManifest.serializer(), manifest),
        )

        assertEquals(manifest, decoded)
        val decodedBook = decoded.books.single()
        assertEquals(1, decoded.version)
        assertEquals(book.sha256, decodedBook.sha256)
        assertEquals(book.fileSize, decodedBook.fileSize)
        assertEquals(book.totalChars, decodedBook.totalChars)
        assertEquals(book.chapterCount, decodedBook.chapterCount)
        assertEquals(book.progressPercent, decodedBook.progressPercent, 0.0f)
        assertEquals(bookmark, decodedBook.bookmarks.single())
    }

    @Test
    fun `migration manifest survives empty book list`() {
        val decoded = json.decodeFromString(
            MigrationManifest.serializer(),
            json.encodeToString(MigrationManifest.serializer(), MigrationManifest(books = emptyList())),
        )
        assertTrue("空清单应可解码（换机无书是合法状态）", decoded.books.isEmpty())
    }

    // ---- 类型安全导航路由（参数经 Bundle 序列化） ----

    @Test
    fun `navigation routes round-trip`() {
        assertEquals(
            ReaderRoute(bookId = 42L),
            json.decodeFromString(
                ReaderRoute.serializer(),
                json.encodeToString(ReaderRoute.serializer(), ReaderRoute(bookId = 42L)),
            ),
        )
        assertEquals(
            TransferRoute(bookIds = listOf(1L, 2L, 3L)),
            json.decodeFromString(
                TransferRoute.serializer(),
                json.encodeToString(TransferRoute.serializer(), TransferRoute(bookIds = listOf(1L, 2L, 3L))),
            ),
        )
        // data object 路由：序列化为空对象 `{}`
        assertEquals(BookshelfRoute, json.decodeFromString(BookshelfRoute.serializer(), "{}"))
        assertEquals(SettingsRoute, json.decodeFromString(SettingsRoute.serializer(), "{}"))
        assertEquals(AboutRoute, json.decodeFromString(AboutRoute.serializer(), "{}"))
        assertEquals(LicenseRoute, json.decodeFromString(LicenseRoute.serializer(), "{}"))
        assertEquals(PrivacyRoute, json.decodeFromString(PrivacyRoute.serializer(), "{}"))
    }

    // ---- 导入失败原因（sealed + 8 子类）：R8 不得改变类型身份与话术常量 ----

    @Test
    fun `import error subclasses keep type identity and user facing messages`() {
        val errors: List<ImportError> = listOf(
            ImportError.UnsupportedFormat("pdf"),
            ImportError.InsufficientSpace,
            ImportError.DrmEncrypted("encrypted"),
            ImportError.ReadFailed("denied"),
            ImportError.DirCreateFailed("mkdirs"),
            ImportError.DecodeFailed("UTF-8"),
            ImportError.ParseFailed("bad xml"),
            ImportError.SourceMissing("dir-1"),
        )

        // sealed 子类身份 + stage 映射（R8 若合并 / 裁掉子类，when 分支与 instanceof 立即破）
        assertEquals(ImportStage.PRECHECK, errors[0].stage)
        assertEquals(ImportStage.PRECHECK, errors[1].stage)
        assertEquals(ImportStage.PARSING, errors[2].stage)
        assertEquals(ImportStage.COPYING, errors[3].stage)
        assertEquals(ImportStage.COPYING, errors[4].stage)
        assertEquals(ImportStage.DETECTING, errors[5].stage)
        assertEquals(ImportStage.PARSING, errors[6].stage)
        assertEquals(ImportStage.PARSING, errors[7].stage)

        // 话术两两不同：这是产品验收口径（B7），字符串常量必须原样活到运行时
        val messages = errors.map { it.userMessage }
        assertEquals("8 类失败话术应两两不同", 8, messages.toSet().size)
        assertEquals("仅支持 TXT / EPUB 格式", errors[0].userMessage)
        assertEquals("存储空间不足，无法导入", errors[1].userMessage)
        assertTrue(errors[2].userMessage.contains("DRM"))
        assertEquals("文件编码异常，请转换编码后重试", errors[5].userMessage)

        // 结构体字段（cause）也要存活：日志与排查依赖它
        assertEquals("extension=pdf", errors[0].cause)
        assertEquals("encoding=UTF-8", errors[5].cause)
        assertEquals("dir-1", (errors[7] as ImportError.SourceMissing).dirName)
        assertEquals("dir=dir-1", errors[7].cause)
        assertNotEquals(errors[0].userMessage, errors[1].userMessage)
    }
}
