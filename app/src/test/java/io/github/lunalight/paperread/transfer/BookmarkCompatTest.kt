package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.backup.BackupBookState
import io.github.lunalight.paperread.backup.BackupBookmark
import io.github.lunalight.paperread.backup.BackupManifest
import io.github.lunalight.paperread.backup.backupJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * 书签备注的**新旧版本互通**单测（US-17 / AC-17.5 / AC-17.6 / AC-17.7 / AC-17.8）。
 *
 * 三个解析点逐一验证：
 * 1. 同步快照 `syncSnapshotJson`（`ProgressSync.kt`）；
 * 2. 迁移包清单 `bundleJson`（`MigrationBundle.kt`）；
 * 3. 备份清单 `backupJson`（`BackupManifest.kt`）。
 *
 * 「v1.2 的旧数据类」在测试里手写（[LegacySyncEnvelope] / [LegacyManifest]）——
 * 不能用当前的数据类去「假装旧版本」，否则新增字段时旧类也会一起长出新字段，
 * 测试就永远绿（这是兼容性测试最典型的自欺）。
 *
 * 「旧版本」的解析配置也照抄 v1.2 实况：`ignoreUnknownKeys = true`
 * （v1.2 就已开启，见 `TransferProtocol` / `MigrationBundleCodec` 的历史配置）。
 */
class BookmarkCompatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- v1.2 视角的数据类（无备注字段） ----

    @Serializable
    private data class LegacySyncBookmark(
        val chapterIndex: Int,
        val paragraphIndex: Int,
        val charOffset: Int,
        val excerpt: String,
        val createdAt: Long,
    )

    @Serializable
    private data class LegacyBookSyncState(
        val sha256: String,
        val title: String,
        val lastChapterIndex: Int,
        val lastParagraphIndex: Int,
        val lastScrollOffset: Int,
        val lastCharOffset: Int,
        val progressPercent: Float,
        val lastReadAt: Long?,
        val bookmarks: List<LegacySyncBookmark>,
    )

    @Serializable
    private data class LegacySyncEnvelope(
        val version: Int = 1,
        val deviceId: String,
        val books: List<LegacyBookSyncState>,
    )

    @Serializable
    private data class LegacyBookmarkSnapshot(
        val chapterIndex: Int,
        val paragraphIndex: Int,
        val charOffset: Int,
        val excerpt: String,
        val createdAt: Long,
    )

    @Serializable
    private data class LegacyBookStateSnapshot(
        val sha256: String,
        val title: String,
        val author: String,
        val format: String,
        val encoding: String,
        val fileSize: Long,
        val totalChars: Long,
        val chapterCount: Int,
        val lastChapterIndex: Int,
        val lastParagraphIndex: Int,
        val lastScrollOffset: Int,
        val lastCharOffset: Int,
        val progressPercent: Float,
        val lastReadAt: Long?,
        val addedAt: Long,
        val bookmarks: List<LegacyBookmarkSnapshot>,
    )

    @Serializable
    private data class LegacyManifest(
        val version: Int = 1,
        val books: List<LegacyBookStateSnapshot>,
    )

    /** v1.2 的 Json 配置（当时就是 ignoreUnknownKeys + encodeDefaults）。 */
    private val legacyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 负向对照用的「不忽略未知字段」配置（AC-17.8：漏配即炸）。 */
    private val strictJson = Json {
        ignoreUnknownKeys = false
    }

    // ---- 夹具 ----

    private fun bookmark(
        chapterIndex: Int = 1,
        paragraphIndex: Int = 2,
        charOffset: Int = 30,
        note: String? = "这一段写得好",
        updatedAt: Long? = 1_700_000_000_000L,
    ) = SyncBookmark(
        chapterIndex = chapterIndex,
        paragraphIndex = paragraphIndex,
        charOffset = charOffset,
        excerpt = "摘录",
        createdAt = 1_600_000_000_000L,
        note = note,
        updatedAt = updatedAt,
    )

    private fun syncState(
        bookmarks: List<SyncBookmark> = listOf(bookmark()),
        lastReadAt: Long? = 100L,
    ) = BookSyncState(
        sha256 = "sha-1",
        title = "书一",
        lastChapterIndex = 1,
        lastParagraphIndex = 2,
        lastScrollOffset = 0,
        lastCharOffset = 30,
        progressPercent = 12.5f,
        lastReadAt = lastReadAt,
        bookmarks = bookmarks,
    )

    private fun envelope(books: List<BookSyncState> = listOf(syncState()), deviceId: String = "me") =
        SyncEnvelope(version = SYNC_PROTOCOL_VERSION, deviceId = deviceId, books = books)

    // ---- AC-17.5：新版本 → 旧版本（未知字段被忽略，不失败） ----

    @Test
    fun `带备注的同步快照能被旧版本解析且书签不丢`() {
        val payload = syncSnapshotJson.encodeToString(SyncEnvelope.serializer(), envelope())

        val legacy = legacyJson.decodeFromString(LegacySyncEnvelope.serializer(), payload)

        assertEquals(1, legacy.books.size)
        assertEquals(1, legacy.books.single().bookmarks.size)
        assertEquals("摘录", legacy.books.single().bookmarks.single().excerpt)
        assertEquals(30, legacy.books.single().bookmarks.single().charOffset)
    }

    @Test
    fun `带备注的迁移清单能被旧版本解析且书签不丢`() {
        val manifest = MigrationManifest(
            books = listOf(
                BookStateSnapshot(
                    sha256 = "sha-1",
                    title = "书一",
                    author = "作者",
                    format = "TXT",
                    encoding = "UTF-8",
                    fileSize = 10L,
                    totalChars = 100L,
                    chapterCount = 2,
                    lastChapterIndex = 1,
                    lastParagraphIndex = 2,
                    lastScrollOffset = 0,
                    lastCharOffset = 30,
                    progressPercent = 12.5f,
                    lastReadAt = 100L,
                    addedAt = 1L,
                    bookmarks = listOf(
                        BookmarkSnapshot(
                            chapterIndex = 1,
                            paragraphIndex = 2,
                            charOffset = 30,
                            excerpt = "摘录",
                            createdAt = 100L,
                            note = "迁移包里也要带备注",
                            updatedAt = 1_700_000_000_000L,
                        ),
                    ),
                ),
            ),
        )
        val payload = bundleJson.encodeToString(MigrationManifest.serializer(), manifest)

        val legacy = legacyJson.decodeFromString(LegacyManifest.serializer(), payload)

        assertEquals(1, legacy.books.single().bookmarks.size)
        assertEquals("摘录", legacy.books.single().bookmarks.single().excerpt)
    }

    @Test
    fun `未知字段必须在忽略模式下解析——关掉就得抛`() {
        val payload =
            """{"chapterIndex":1,"paragraphIndex":2,"charOffset":30,"excerpt":"摘录","createdAt":1,"note":"备注","updatedAt":2}"""

        // 负向对照：AC-17.8 要求「显式启用忽略未知字段」——不开就一定炸，
        // 这条用例把「配置漏了」变成红灯，而不是等用户升级后才发现同步整体失败。
        assertThrows(Exception::class.java) {
            strictJson.decodeFromString(LegacySyncBookmark.serializer(), payload)
        }

        val decoded = legacyJson.decodeFromString(LegacySyncBookmark.serializer(), payload)
        assertEquals("摘录", decoded.excerpt)
    }

    @Test
    fun `迁移清单里的未知字段同样被忽略`() {
        val payload = """
            {"version":1,"futureField":"v2.1 才有的东西","books":[]}
        """.trimIndent()

        val manifest = bundleJson.decodeFromString(MigrationManifest.serializer(), payload)

        assertEquals(1, manifest.version)
        assertTrue(manifest.books.isEmpty())
    }

    @Test
    fun `备份清单里的未知字段同样被忽略`() {
        val payload = """{"version":1,"futureField":{"a":1},"books":[]}"""

        val manifest = backupJson.decodeFromString(BackupManifest.serializer(), payload)

        assertEquals(1, manifest.version)
        assertTrue(manifest.books.isEmpty())
    }

    // ---- AC-17.6：旧版本 → 新版本（默认值接纳，书签不丢） ----

    @Test
    fun `旧版同步快照在新版本按默认值接纳`() {
        // v1.2 生成的报文：完全没有 note / updatedAt 字段
        val legacyPayload = """
            {"version":1,"deviceId":"old-peer","books":[{
              "sha256":"sha-1","title":"书一","lastChapterIndex":1,"lastParagraphIndex":2,
              "lastScrollOffset":0,"lastCharOffset":30,"progressPercent":12.5,"lastReadAt":100,
              "bookmarks":[{"chapterIndex":1,"paragraphIndex":2,"charOffset":30,
                            "excerpt":"摘录","createdAt":1600000000000}]
            }]}
        """.trimIndent()

        val envelope = syncSnapshotJson.decodeFromString(SyncEnvelope.serializer(), legacyPayload)

        val mark = envelope.books.single().bookmarks.single()
        assertEquals("书签不得丢失", "摘录", mark.excerpt)
        assertNull("旧版无备注 → null", mark.note)
        assertNull("旧版无更新时间 → null", mark.updatedAt)
        assertEquals("null 备注时间按 0 处理", 0L, mark.noteTimestamp)
        assertEquals("", mark.noteOrEmpty)
    }

    @Test
    fun `旧版迁移清单在新版本按默认值接纳`() {
        val legacyPayload = """
            {"version":1,"books":[{
              "sha256":"sha-1","title":"书一","author":"作者","format":"TXT","encoding":"UTF-8",
              "fileSize":10,"totalChars":100,"chapterCount":2,"lastChapterIndex":1,
              "lastParagraphIndex":2,"lastScrollOffset":0,"lastCharOffset":30,
              "progressPercent":12.5,"lastReadAt":100,"addedAt":1,
              "bookmarks":[{"chapterIndex":1,"paragraphIndex":2,"charOffset":30,
                            "excerpt":"摘录","createdAt":1600000000000}]
            }]}
        """.trimIndent()

        val manifest = bundleJson.decodeFromString(MigrationManifest.serializer(), legacyPayload)

        val mark = manifest.books.single().bookmarks.single()
        assertEquals("摘录", mark.excerpt)
        assertNull(mark.note)
        assertNull(mark.updatedAt)
        assertEquals("回写到 Bookmark 时降级为空串", "", mark.note.orEmpty())
    }

    @Test
    fun `旧版备份清单的书签缺备注字段时按默认值接纳`() {
        val payload = """{"version":1,"books":[]}"""

        val manifest = backupJson.decodeFromString(BackupManifest.serializer(), payload)

        assertNotNull(manifest)
        val state = backupJson.decodeFromString(
            BackupBookState.serializer(),
            """{"title":"书一","bookmarks":[{"chapterIndex":0,"paragraphIndex":1,"charOffset":2,
               "excerpt":"摘录","createdAt":3}]}""".trimIndent(),
        )
        assertEquals(1, state.bookmarks.size)
        assertEquals("", state.bookmarks.single().note)
        assertEquals(0L, state.bookmarks.single().updatedAt)
        assertEquals(BackupBookmark::class.java, state.bookmarks.single()::class.java)
    }

    // ---- AC-17.7：合并去重键不变，备注较新者胜 ----

    @Test
    fun `去重键不受备注影响`() {
        val withNote = bookmark(note = "有备注", updatedAt = 10L)
        val withoutNote = bookmark(note = null, updatedAt = null)

        assertEquals("备注不同必须是同一条书签", withNote.positionKey, withoutNote.positionKey)
    }

    @Test
    fun `同键两书签合并时不产生重复且备注较新者胜`() {
        // 本地：同位置、无备注；对端：同位置、带较新备注
        val local = syncState(bookmarks = listOf(bookmark(note = null, updatedAt = null)))
        val remote = syncState(bookmarks = listOf(bookmark(note = "对端新备注", updatedAt = 500L)))

        val result = SyncMerger.mergeBook(local, remote)

        assertTrue("不得因备注差异判成新书签", result.addBookmarks.isEmpty())
        assertTrue("也不该把同一条回推回去", result.pushBookmarks.isEmpty())
        assertEquals(1, result.updateNotes.size)
        assertEquals("对端新备注", result.updateNotes.single().note)
        assertEquals(500L, result.updateNotes.single().updatedAt)
    }

    @Test
    fun `本地备注较新时回推对端且不改本地`() {
        val local = syncState(bookmarks = listOf(bookmark(note = "本地新备注", updatedAt = 900L)))
        val remote = syncState(bookmarks = listOf(bookmark(note = "对端旧备注", updatedAt = 500L)))

        val result = SyncMerger.mergeBook(local, remote)

        assertTrue(result.addBookmarks.isEmpty())
        assertTrue(result.updateNotes.isEmpty())
        assertEquals(1, result.pushBookmarks.size)
        assertEquals("本地新备注", result.pushBookmarks.single().note)
    }

    @Test
    fun `备注时间相同时双方都不动作`() {
        val local = syncState(bookmarks = listOf(bookmark(note = "甲", updatedAt = 700L)))
        val remote = syncState(bookmarks = listOf(bookmark(note = "乙", updatedAt = 700L)))

        val result = SyncMerger.mergeBook(local, remote)

        assertTrue("时间相同不互相覆盖", result.updateNotes.isEmpty())
        assertTrue(result.pushBookmarks.isEmpty())
        assertTrue(result.addBookmarks.isEmpty())
    }

    @Test
    fun `整库合并统计含备注更新数`() {
        val local = SyncEnvelope(
            deviceId = "me",
            books = listOf(syncState(bookmarks = listOf(bookmark(note = null, updatedAt = null)))),
        )
        val remote = SyncEnvelope(
            deviceId = "peer",
            books = listOf(syncState(bookmarks = listOf(bookmark(note = "新", updatedAt = 500L)))),
        )

        val report = SyncMerger.merge(local, remote)

        assertEquals(1, report.localNotesUpdated)
        assertEquals(0, report.localBookmarksAdded)
        assertEquals(1, report.totalChanges)
    }

    @Test
    fun `只增不删语义不变——对端独有书签仍然并入`() {
        val local = syncState(bookmarks = listOf(bookmark(chapterIndex = 0, note = "有备注", updatedAt = 10L)))
        val remote = syncState(
            bookmarks = listOf(
                bookmark(chapterIndex = 0, note = "有备注", updatedAt = 10L),
                bookmark(chapterIndex = 5, paragraphIndex = 0, charOffset = 1, note = null, updatedAt = null),
            ),
        )

        val result = SyncMerger.mergeBook(local, remote)

        assertEquals(1, result.addBookmarks.size)
        assertEquals(5, result.addBookmarks.single().chapterIndex)
        assertTrue(result.updateNotes.isEmpty())
    }

    // ---- 打包往返：备注真的进包、真的出包 ----

    @Test
    fun `迁移包往返保留备注`() {
        val manifest = MigrationManifest(
            books = listOf(
                BookStateSnapshot(
                    sha256 = "sha-1",
                    title = "书一",
                    author = "作者",
                    format = "TXT",
                    encoding = "UTF-8",
                    fileSize = 10L,
                    totalChars = 100L,
                    chapterCount = 2,
                    lastChapterIndex = 1,
                    lastParagraphIndex = 2,
                    lastScrollOffset = 0,
                    lastCharOffset = 30,
                    progressPercent = 12.5f,
                    lastReadAt = 100L,
                    addedAt = 1L,
                    bookmarks = listOf(
                        BookmarkSnapshot(
                            chapterIndex = 1,
                            paragraphIndex = 2,
                            charOffset = 30,
                            excerpt = "摘录",
                            createdAt = 100L,
                            note = "往返也不能丢",
                            updatedAt = 900L,
                        ),
                    ),
                ),
            ),
        )
        val zip = tmp.newFile("bundle.zip")
        MigrationBundleCodec.pack(manifest, emptyMap(), zip)

        val unpacked = MigrationBundleCodec.unpack(zip, tmp.newFolder("out"))

        val mark = unpacked!!.manifest.books.single().bookmarks.single()
        assertEquals("往返也不能丢", mark.note)
        assertEquals(900L, mark.updatedAt)
    }

    @Test
    fun `快照序列化会显式写出备注字段`() {
        val payload = bundleJson.encodeToString(
            BookmarkSnapshot.serializer(),
            BookmarkSnapshot(
                chapterIndex = 0,
                paragraphIndex = 1,
                charOffset = 2,
                excerpt = "摘录",
                createdAt = 3,
                note = "备注",
                updatedAt = 4,
            ),
        )

        assertTrue("encodeDefaults + 显式值：字段必须出现", payload.contains("\"note\""))
        assertTrue(payload.contains("\"updatedAt\""))
    }

    @Test
    fun `无备注的书签序列化后仍可被新版本解析`() {
        val payload = bundleJson.encodeToString(
            BookmarkSnapshot.serializer(),
            BookmarkSnapshot(chapterIndex = 0, paragraphIndex = 1, charOffset = 2, excerpt = "e", createdAt = 3),
        )

        val back = bundleJson.decodeFromString(BookmarkSnapshot.serializer(), payload)

        assertNull(back.note)
        assertNull(back.updatedAt)
    }

    @Test
    fun `SyncBookmark 从领域模型映射备注`() {
        val fromDomain = SyncBookmark.from(
            io.github.lunalight.paperread.domain.model.Bookmark(
                bookId = 1L,
                chapterIndex = 0,
                paragraphIndex = 1,
                charOffset = 2,
                excerpt = "e",
                createdAt = 3,
                note = "备注",
                updatedAt = 9L,
            ),
        )

        assertEquals("备注", fromDomain.note)
        assertEquals(9L, fromDomain.updatedAt)

        val blank = SyncBookmark.from(
            io.github.lunalight.paperread.domain.model.Bookmark(
                bookId = 1L,
                chapterIndex = 0,
                paragraphIndex = 1,
                charOffset = 2,
                excerpt = "e",
                createdAt = 3,
            ),
        )
        assertNull("空备注映射为 null（旧版本形态）", blank.note)
        assertNull("updatedAt = 0 映射为 null", blank.updatedAt)
    }

    @Test
    fun `迁移包文件不存在时解包返回 null`() {
        assertNull(MigrationBundleCodec.unpack(File(tmp.root, "nope.zip"), tmp.newFolder("x")))
    }
}
