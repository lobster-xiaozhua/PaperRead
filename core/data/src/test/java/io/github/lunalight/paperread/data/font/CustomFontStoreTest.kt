package io.github.lunalight.paperread.data.font

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.data.settings.PreferencesDataStoreProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [CustomFontStore] 单测（T-018 · AC-16.2 / AC-16.3 / AC-16.5 / AC-16.6 / AC-16.7）。
 *
 * 走真实文件系统（Robolectric 的临时 `filesDir`）与真实 SAF 影子：
 * 导入 → 落盘 → 元数据 → 解析 → 删除，整条链路都在被测范围内，
 * 这样「删了文件但元数据还在」「元数据没了但文件还在」这类错位能被直接抓到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomFontStoreTest {

    private lateinit var context: Context
    private lateinit var store: CustomFontStore
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 每个测试类全新 Application（新 filesDir / 新 DataStore），但同一类内
        // 用例共享 DataStore 文件，故先清空字体目录与元数据
        store = CustomFontStore(
            context = context,
            dataStore = PreferencesDataStoreProvider(context, DATA_STORE).instance,
            clock = { now },
        )
        runTest { store.list().forEach { store.delete(it.id) } }
        store.fontDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        store.fontDir().deleteRecursively()
    }

    // ---- 导入 ----

    @Test
    fun `导入合法 TTF 后落盘并记录元数据`() = runTest {
        val uri = registerFontUri("Source-Kai.ttf", sfnt(family = "Kai Ti", full = "Kai Ti Regular"))

        val result = store.import(uri)

        assertTrue("期望导入成功，实际 $result", result is FontImportResult.Success)
        val font = (result as FontImportResult.Success).font
        assertEquals("Kai Ti Regular", font.displayName)
        assertEquals("Source-Kai.ttf", font.sourceFileName)
        assertEquals(now, font.addedAt)
        assertEquals("ttf", font.extension)

        val file = File(store.fontDir(), "${font.id}.ttf")
        assertTrue("字体文件应落盘：${file.absolutePath}", file.isFile)
        assertEquals(sfnt(family = "Kai Ti", full = "Kai Ti Regular").size, file.length().toInt())

        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals(font.id, listed.single().id)
    }

    @Test
    fun `OTF 魔数按 otf 落盘`() = runTest {
        val bytes = sfnt(magic = OTTO, family = "Song", full = "Song Regular")
        val result = store.import(registerFontUri("song.otf", bytes))

        val font = (result as FontImportResult.Success).font
        assertEquals("otf", font.extension)
        assertTrue(File(store.fontDir(), "${font.id}.otf").isFile)
    }

    @Test
    fun `取不到 name 表时回退文件名做显示名`() = runTest {
        // 只有 12 字节 sfnt 头、表目录声明 0 张表 → TtfMetadata 解析失败
        val headerOnly = byteArrayOf(0x00, 0x01, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0)
        val result = store.import(registerFontUri("My Handwriting.ttf", headerOnly))

        val font = (result as FontImportResult.Success).font
        assertEquals("My Handwriting", font.displayName)
    }

    @Test
    fun `非法魔数被拒绝且不留文件`() = runTest {
        val bytes = "PK\u0003\u0004 not a font".toByteArray(Charsets.US_ASCII)
        val result = store.import(registerFontUri("fake.ttf", bytes))

        assertEquals(
            FontImportResult.Failure(FontImportFailure.UNSUPPORTED_FORMAT),
            result,
        )
        assertEquals("拒绝的文件不得落盘", 0, store.fontDir().listFiles()?.size ?: 0)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `超过大小上限被拒绝`() = runTest {
        val small = CustomFontStore(
            context = context,
            dataStore = PreferencesDataStoreProvider(context, "$DATA_STORE-small").instance,
            maxBytes = 32,
        )
        val bytes = sfnt(family = "Big", full = "Big Regular") + ByteArray(64)
        val result = small.import(registerFontUri("big.ttf", bytes))

        assertEquals(FontImportResult.Failure(FontImportFailure.TOO_LARGE), result)
        assertFalse(File(small.fontDir(), "big.ttf").exists())
    }

    @Test
    fun `打不开的 Uri 报不可读`() = runTest {
        val result = store.import(Uri.parse("content://paperread.test/missing.ttf"))
        assertEquals(FontImportResult.Failure(FontImportFailure.UNREADABLE), result)
    }

    // ---- 列举 / 排序 ----

    @Test
    fun `列表按导入时间倒序`() = runTest {
        now = 1_000L
        val first = (store.import(registerFontUri("a.ttf", sfnt(full = "A"))) as FontImportResult.Success).font
        now = 2_000L
        val second = (store.import(registerFontUri("b.ttf", sfnt(full = "B"))) as FontImportResult.Success).font

        assertEquals(listOf(second.id, first.id), store.list().map { it.id })
    }

    @Test
    fun `流式接口与一次性读取一致`() = runTest {
        store.import(registerFontUri("a.ttf", sfnt(full = "A")))
        assertEquals(store.list(), store.fonts.first())
    }

    // ---- 解析 / 删除（AC-16.5 / AC-16.6） ----

    @Test
    fun `resolve 命中已导入的字体`() = runTest {
        val font = (store.import(registerFontUri("a.ttf", sfnt(full = "A"))) as FontImportResult.Success).font
        val resolved = store.resolve(font.id)
        assertNotNull(resolved)
        assertTrue(resolved!!.isFile)
    }

    @Test
    fun `resolve 对不存在的 id 返回 null`() = runTest {
        assertNull(store.resolve("not-a-font-id"))
        assertNull(store.resolve(""))
    }

    @Test
    fun `删除后文件与元数据一起消失`() = runTest {
        val font = (store.import(registerFontUri("a.ttf", sfnt(full = "A"))) as FontImportResult.Success).font
        val file = File(store.fontDir(), "${font.id}.ttf")
        assertTrue(file.isFile)

        store.delete(font.id)

        assertFalse("文件应被删除", file.exists())
        assertTrue("元数据应被删除", store.list().isEmpty())
        assertNull("resolve 必须回 null（AC-16.6 回退入口）", store.resolve(font.id))
    }

    @Test
    fun `文件被外部删除后 resolve 仍回 null 而不是抛`() = runTest {
        val font = (store.import(registerFontUri("a.ttf", sfnt(full = "A"))) as FontImportResult.Success).font
        assertTrue(File(store.fontDir(), "${font.id}.ttf").delete())

        assertNull(store.resolve(font.id))
        // 元数据仍在（用户还能在设置里看到它并删除），但正文会回退默认字体
        assertEquals(1, store.list().size)
    }

    @Test
    fun `删除其一时不影响另一份`() = runTest {
        val first = (store.import(registerFontUri("a.ttf", sfnt(full = "A"))) as FontImportResult.Success).font
        val second = (store.import(registerFontUri("b.ttf", sfnt(full = "B"))) as FontImportResult.Success).font

        store.delete(first.id)

        assertEquals(listOf(second.id), store.list().map { it.id })
        assertNotNull(store.resolve(second.id))
    }

    // ---- 元数据编码（内部 codec 的健壮性） ----

    @Test
    fun `元数据编码能处理分隔符与反斜杠`() {
        val tricky = CustomFont(
            id = "id-1",
            displayName = "Weird\u001FName\u001Ewith\\separators",
            sourceFileName = "weird\u001Ffile.ttf",
            addedAt = 42L,
            extension = "ttf",
        )
        val decoded = CustomFontCodec.decode(CustomFontCodec.encode(listOf(tricky)))
        assertEquals(listOf(tricky), decoded)
    }

    @Test
    fun `脏元数据被跳过而不是整体丢失`() {
        val good = CustomFont("ok", "Good", "good.ttf", 1L, "ttf")
        val raw = "${CustomFontCodec.encode(listOf(good))}\u001Ebroken-record\u001Eanother"
        assertEquals(listOf(good), CustomFontCodec.decode(raw))
    }

    @Test
    fun `空元数据解码为空列表`() {
        assertTrue(CustomFontCodec.decode(null).isEmpty())
        assertTrue(CustomFontCodec.decode("").isEmpty())
    }

    // ---- 夹具 ----

    private fun registerFontUri(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://paperread.test/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    /**
     * 最小 sfnt：12 字节头 + 1 条 name 表记录 + name 表（format/count/记录/字符串池）。
     *
     * 与 :core:reader 测试里的 SfntBuilder 同构，但这里只需覆盖
     * 「合法魔数 + 能取到名字」与「合法魔数 + 取不到名字」两种情况，
     * 故就地内联 30 行，避免为了一个夹具把测试依赖串到别的模块。
     */
    private fun sfnt(
        magic: ByteArray = byteArrayOf(0x00, 0x01, 0x00, 0x00),
        family: String? = null,
        full: String? = null,
    ): ByteArray {
        val records = buildList {
            full?.let { add(Triple(4, it, 3)) }
            family?.let { add(Triple(1, it, 3)) }
        }
        val strings = ByteArrayOutputStream()
        val placed = records.map { (nameId, text, platform) ->
            val encoded = text.toByteArray(if (platform == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE)
            val offset = strings.size()
            strings.write(encoded)
            Triple(nameId, platform, offset to encoded.size)
        }
        val nameTable = ByteArrayOutputStream()
        nameTable.writeU16(0)
        nameTable.writeU16(placed.size)
        nameTable.writeU16(6 + placed.size * 12)
        placed.forEach { (nameId, platform, pos) ->
            nameTable.writeU16(platform)
            nameTable.writeU16(1)
            nameTable.writeU16(0x0409)
            nameTable.writeU16(nameId)
            nameTable.writeU16(pos.second)
            nameTable.writeU16(pos.first)
        }
        nameTable.write(strings.toByteArray())
        val nameBytes = nameTable.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(magic)
        out.writeU16(1)
        out.writeU16(0)
        out.writeU16(0)
        out.writeU16(0)
        out.write("name".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0))
        out.writeU32(28L)
        out.writeU32(nameBytes.size.toLong())
        out.write(nameBytes)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value shr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        write((value shr 24 and 0xFF).toInt())
        write((value shr 16 and 0xFF).toInt())
        write((value shr 8 and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }

    private companion object {
        const val DATA_STORE = "custom_fonts_test"
        val OTTO = "OTTO".toByteArray(Charsets.US_ASCII)
    }
}
