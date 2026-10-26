package io.github.lunalight.paperread.reader

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * GBK 长篇小说样本生成器（T-007 · R-05 / G5，测试侧）。
 *
 * 与 `scripts/gen-large-book-sample.sh` **结构同构**（章节标题 `第N章 荒原第N节`、
 * 每章 40 行正文、章间空行、文末不以换行截断），但两者各自独立实现：
 * 脚本用于真机验收（产出真实 200MB 文件 + `.meta` 元数据供核对），本类用于
 * JVM 侧自动化（CI 跑中等样本，200MB 由系统属性门控）。
 *
 * 为什么不用「测试里调脚本」：单测不应依赖宿主机的 awk / iconv；反过来脚本也
 * 不应依赖 JDK 编译。两者的一致性由结构约束 + 元数据 PARITY 用例
 * （[LargeBookImportTest] 中读取脚本产物的那条）保证。
 *
 * 关键设计：
 * - **字节级直写**：按 GBK 编码逐段写字节，字节数即实际落盘大小，无需二次编码
 *   统计，20MB 样本不必在堆里驻留整本正文；
 * - **确定性**：同一 `(targetBytes, seed)` 生成字节一致的样本（正文句按
 *   `(chapter * 7 + line * 13 + seed) % 句子数` 轮转，无随机数）；
 * - **末行无换行**：写完最后一章后把文件截短 1 字节，去掉末尾 `\n`——这正是
 *   「末章边界」回归要覆盖的形态。
 */
internal object GbkSampleBookGenerator {

    /** 每章正文行数（脚本侧同名常量保持一致）。 */
    const val BODY_LINES_PER_CHAPTER: Int = 40

    /** GBK（与脚本 `iconv -t GBK` 同一码表；汉字字节与 GB18030 一致）。 */
    val GBK: Charset = Charset.forName("GBK")

    /**
     * 生成结果。
     *
     * @param chars 解码后的字符数（= 导入流水线的 totalChars 期望值）
     * @param bytes GBK 字节数（= 源文件大小期望值）
     * @param utf8Bytes 同一正文按 UTF-8 编码的字节数（= book.txt 大小期望值，
     *   因为导入层把正文标准化为 UTF-8 存储且不改写内容）
     */
    data class Sample(
        val file: File,
        val chapters: Int,
        val chars: Long,
        val bytes: Long,
        val utf8Bytes: Long,
    )

    /** 章节标题：`第{n}章 荒原第{n}节`（命中强正则 `第…章`，且各章互不相同）。 */
    fun chapterTitle(n: Int): String = "第${n}章 荒原第${n}节"

    /** 第 [chapter] 章第 [line] 行（0 基）正文，确定性轮转。 */
    fun bodyLine(chapter: Int, line: Int, seed: Long): String {
        val idx = ((chapter.toLong() * 7L + line.toLong() * 13L + seed) % SENTENCES.size).toInt()
        return SENTENCES[idx]
    }

    /**
     * 在 [dir] 下生成 [targetBytes]（GBK 字节）规模的样本。
     *
     * @param fileName 输出文件名（须以 .txt 结尾，供导入层识别格式）
     */
    fun generate(
        dir: File,
        targetBytes: Long,
        seed: Long = DEFAULT_SEED,
        fileName: String = "large-book-gbk.txt",
    ): Sample {
        require(targetBytes > 0) { "targetBytes 必须为正：$targetBytes" }
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建样本目录：${dir.absolutePath}")
        }
        val file = File(dir, fileName)
        var bytes = 0L
        var chars = 0L
        var utf8Bytes = 0L
        var chapters = 0

        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            fun write(text: String) {
                val encoded = text.toByteArray(GBK)
                out.write(encoded)
                bytes += encoded.size
                chars += text.length
                utf8Bytes += utf8Length(text)
            }

            while (bytes < targetBytes) {
                chapters++
                if (chapters > 1) write("\n")
                write(chapterTitle(chapters))
                write("\n")
                for (line in 0 until BODY_LINES_PER_CHAPTER) {
                    write(bodyLine(chapters, line, seed))
                    write("\n")
                }
            }
        }

        // 去掉末尾换行：末章最后一行正文直接结束于 EOF
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(raf.length() - 1)
        }
        bytes -= 1
        chars -= 1
        utf8Bytes -= 1

        return Sample(file = file, chapters = chapters, chars = chars, bytes = bytes, utf8Bytes = utf8Bytes)
    }

    /** UTF-8 编码字节数（逐 char 计算，避免为大样本再分配一份字节数组）。 */
    private fun utf8Length(text: String): Int {
        var n = 0
        for (c in text) {
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
        }
        return n
    }

    /**
     * 独立参考实现：逐行 GBK 解码，数「行首命中章节标题」的行数。
     *
     * 刻意不复用生产侧的 `TxtChapterStream` / `ChapterSplitter`——用一份"笨"实现
     * 做交叉验算，才能证明导入流水线在超大文件上没多切 / 少切。
     */
    fun referenceChapterCount(file: File): Int {
        var count = 0
        file.inputStream().let { input ->
            BufferedReader(InputStreamReader(input, GBK), 1 shl 16).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (CHAPTER_REGEX.matches(line)) count++
                }
            }
        }
        return count
    }

    private val CHAPTER_REGEX = Regex("""^第\d+章 荒原第\d+节$""")

    const val DEFAULT_SEED: Long = 20261026L

    /**
     * 正文句子池：全部为 GBK 常用汉字 + 中文标点（不含 GBK 码表外字符），
     * 长度 20~34 字，40 行/章约 2.0~2.7 KB（GBK）。
     */
    private val SENTENCES: List<String> = listOf(
        "少年提灯独行，身后是沉睡的城池，风从北方来，带着沙与雪。",
        "城池在夜里醒来，灯火次第亮起，像倒悬在长街上的一条星河。",
        "他决定北上，穿过荒原，去赴一个十年之前就已许下的约定。",
        "雪落下来的时候，荒原终于安静了，只剩下脚步压过枯草的声音。",
        "石阶上积着薄霜，有人在阶前坐了很久，看远处的灯一盏盏灭去。",
        "旧书页被翻动时发出细碎的响，像有人在很远的地方轻声说话。",
        "他把长剑横在膝上，闭着眼睛听风，分辨其中不属于风的那一部分。",
        "故人来时只带了一壶酒，两人对着暮色坐了半宿，谁也没有开口。",
        "天亮之前，街市还没有醒，只有挑水的车从石桥上缓缓碾过。",
        "远处的山脊像一条伏着的兽，云从它的背上慢慢淌下来。",
        "她说起南方的雨，说起雨里的巷子，语气平静得像在说别人的事。",
        "刀锋映着火光，火光照着人脸，人脸上一片沉默的阴影。",
        "城门外那片荒地，三年前还是麦田，如今只剩翻倒的界石。",
        "灯油耗尽的那一刻，屋子里所有的影子同时向前挪了一步。",
        "他数着自己的呼吸，数到第一百次时，门外的脚步声停了。",
        "雨水顺着檐角落下来，在石阶上敲出一串不紧不慢的点子。",
        "地图的边缘已经磨破，那条通向北方的小路只剩下半截墨线。",
        "有人在城楼上吹笛，调子散在风里，听不真切，却叫人想起故乡。",
        "他忽然明白，所谓远行，不过是把熟悉的东西一件件放下去。",
        "炉火噼啪响了一声，火星升起来，在半空里亮了一下就灭了。",
        "巷子尽头的墙根下，一株老梅开得正紧，香气冷得有些刺人。",
        "她说，若有一日你回不来，我便把这盏灯一直点着，点到灯尽。",
        "马蹄声由远及近，又由近及远，最后只剩下风穿过空巷的声音。",
        "书里夹着一片干枯的叶子，脉络还清楚，像一张缩小的地图。",
        "他把最后一块干粮分给同行的少年，少年说了声谢，声音很轻。",
        "天色一点一点亮起来，先亮的是云，然后才是屋檐和街面。",
        "那些没能说出口的话，最后都变成了夜里翻来覆去的念头。",
        "荒原上有一口枯井，井壁上刻着许多名字，最上面的一个已经模糊。",
        "他伸手推门，门轴发出长长的呻吟，惊起檐下的一只宿鸟。",
        "冬夜很长，长得足够把一整个白日的疲惫慢慢熬成安静的睡意。",
        "多年以后他再回到这里，草木已经长过膝头，路也认不出来了。",
        "故事讲到这里，灯已经暗了，听故事的人却还坐着，迟迟不肯走。",
    )
}
