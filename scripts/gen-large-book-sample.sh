#!/usr/bin/env bash
#
# 200 MB GBK 中文长篇样本生成器（T-007 · R-05 / G5）。
#
# 目的：真机「200MB GBK 大文件验收」（docs/DEVICE_CHECKLIST.md §7）与 JVM 侧
# 自动化验证（LargeBookImportTest）需要一个**可复现**的样本：同一 --seed /
# --size-mb 在任意机器上生成字节完全一致的 GBK 文本，真机核对到的章节数、
# 文件大小、首末章文本才能与 JVM 侧断言互相对照。
#
# 样本特征（与验收表的六项一一对应）：
#   1. GBK 编码、无 BOM —— CharsetDetector 走「严格 UTF-8 试解码失败 →
#      GB18030 回退」路径（真机上即用户最常见的 GBK 小说场景）；
#   2. 约 200 MB（--size-mb 可调；目标体积按 GBK 计，误差 ±2%，实际值见输出）；
#   3. 章节标题规整 `第N章 荒原第N节`，每章固定 40 行正文 —— 章节数可由
#      「行首正则扫描」独立复核，不依赖导入实现；
#   4. 文末**不以换行截断**（末行正文结尾即 EOF）—— 覆盖末章边界缺陷
#      （末章 charEnd / byteEnd 少算或多算一个换行是最常见的大文件回归）；
#   5. 全部字符落在 GBK 码表内（生成后用 iconv 严格转换，失败即报错）。
#
# 用法：
#   ./scripts/gen-large-book-sample.sh                       # 默认 200MB、seed=20261026
#   ./scripts/gen-large-book-sample.sh --size-mb 20          # CI 小样本
#   ./scripts/gen-large-book-sample.sh --out /tmp/book.txt    # 指定输出
#   ./scripts/gen-large-book-sample.sh --force                # 覆盖已存在文件
#
# 产物：
#   <out>               GBK 样本正文
#   <out>.meta          key=value 元数据（CHAPTERS / GBK_BYTES / UTF8_BYTES / SEED /
#                       FIRST_LINE / LAST_LINE / SHA256），供真机核对与
#                       LargeBookImportTest 的 PARITY 用例读取
#
# 依赖：bash 4+、awk（mawk / gawk / BSD awk 均可）、iconv、sha256sum（或 shasum）。
# 实现说明：正文用 awk 在 LC_ALL=C 下按**字节**生成（length() 即字节数，便于
# 精确控体积），一次性 iconv 转 GBK——避免逐章 subshell 调用 iconv 的进程开销
# （8 万章 × iconv 会跑十几分钟）。

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SIZE_MB=200
SEED=20261026
OUT=""
FORCE=0

usage() {
  sed -n '3,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --size-mb)
      [ "$#" -ge 2 ] || { echo "✗ --size-mb 缺少参数" >&2; exit 2; }
      SIZE_MB="$2"
      shift 2
      ;;
    --seed)
      [ "$#" -ge 2 ] || { echo "✗ --seed 缺少参数" >&2; exit 2; }
      SEED="$2"
      shift 2
      ;;
    --out)
      [ "$#" -ge 2 ] || { echo "✗ --out 缺少参数" >&2; exit 2; }
      OUT="$2"
      shift 2
      ;;
    --force)
      FORCE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "✗ 未知参数：$1（--help 查看用法）" >&2
      exit 2
      ;;
  esac
done

case "$SIZE_MB" in
  ''|*[!0-9]*) echo "✗ --size-mb 必须是正整数：$SIZE_MB" >&2; exit 2 ;;
esac
case "$SEED" in
  ''|*[!0-9]*) echo "✗ --seed 必须是非负整数：$SEED" >&2; exit 2 ;;
esac
if [ "$SIZE_MB" -lt 1 ]; then
  echo "✗ --size-mb 至少为 1" >&2
  exit 2
fi

if [ -z "$OUT" ]; then
  OUT="build/samples/large-book-${SIZE_MB}mb-gbk.txt"
fi

if [ -e "$OUT" ] && [ "$FORCE" -ne 1 ]; then
  echo "✗ 目标文件已存在：$OUT（加 --force 覆盖）" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"

for tool in awk iconv; do
  command -v "$tool" >/dev/null 2>&1 || { echo "✗ 缺少依赖：$tool" >&2; exit 1; }
done

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

UTF8_FILE="$WORK/book.utf8"
STATS_FILE="$WORK/stats"

# 目标 UTF-8 体积：中文在 UTF-8 下 3 字节、在 GBK 下 2 字节，故 UTF-8 侧按 3/2 放大。
# 数字与换行为 ASCII（两侧同为 1 字节），占比极低（<5%），最终 GBK 体积误差 ±2%。
TARGET_UTF8=$(( SIZE_MB * 1024 * 1024 * 3 / 2 ))

echo "→ 生成中：目标 GBK ${SIZE_MB}MB（seed=${SEED}）…" >&2

LC_ALL=C awk -v target="$TARGET_UTF8" -v seed="$SEED" -v stats="$STATS_FILE" '
BEGIN {
  # 40 行正文 / 章：每行 20~34 汉字（GBK 40~68 字节），单章约 2.0~2.7KB，
  # 200MB 约 8 万章——与中文网文「短章多」的真实分布同量级。
  n_lines = 40
  n_sent = 32
  sent[1]  = "少年提灯独行，身后是沉睡的城池，风从北方来，带着沙与雪。"
  sent[2]  = "城池在夜里醒来，灯火次第亮起，像倒悬在长街上的一条星河。"
  sent[3]  = "他决定北上，穿过荒原，去赴一个十年之前就已许下的约定。"
  sent[4]  = "雪落下来的时候，荒原终于安静了，只剩下脚步压过枯草的声音。"
  sent[5]  = "石阶上积着薄霜，有人在阶前坐了很久，看远处的灯一盏盏灭去。"
  sent[6]  = "旧书页被翻动时发出细碎的响，像有人在很远的地方轻声说话。"
  sent[7]  = "他把长剑横在膝上，闭着眼睛听风，分辨其中不属于风的那一部分。"
  sent[8]  = "故人来时只带了一壶酒，两人对着暮色坐了半宿，谁也没有开口。"
  sent[9]  = "天亮之前，街市还没有醒，只有挑水的车从石桥上缓缓碾过。"
  sent[10] = "远处的山脊像一条伏着的兽，云从它的背上慢慢淌下来。"
  sent[11] = "她说起南方的雨，说起雨里的巷子，语气平静得像在说别人的事。"
  sent[12] = "刀锋映着火光，火光照着人脸，人脸上一片沉默的阴影。"
  sent[13] = "城门外那片荒地，三年前还是麦田，如今只剩翻倒的界石。"
  sent[14] = "灯油耗尽的那一刻，屋子里所有的影子同时向前挪了一步。"
  sent[15] = "他数着自己的呼吸，数到第一百次时，门外的脚步声停了。"
  sent[16] = "雨水顺着檐角落下来，在石阶上敲出一串不紧不慢的点子。"
  sent[17] = "地图的边缘已经磨破，那条通向北方的小路只剩下半截墨线。"
  sent[18] = "有人在城楼上吹笛，调子散在风里，听不真切，却叫人想起故乡。"
  sent[19] = "他忽然明白，所谓远行，不过是把熟悉的东西一件件放下去。"
  sent[20] = "炉火噼啪响了一声，火星升起来，在半空里亮了一下就灭了。"
  sent[21] = "巷子尽头的墙根下，一株老梅开得正紧，香气冷得有些刺人。"
  sent[22] = "她说，若有一日你回不来，我便把这盏灯一直点着，点到灯尽。"
  sent[23] = "马蹄声由远及近，又由近及远，最后只剩下风穿过空巷的声音。"
  sent[24] = "书里夹着一片干枯的叶子，脉络还清楚，像一张缩小的地图。"
  sent[25] = "他把最后一块干粮分给同行的少年，少年说了声谢，声音很轻。"
  sent[26] = "天色一点一点亮起来，先亮的是云，然后才是屋檐和街面。"
  sent[27] = "那些没能说出口的话，最后都变成了夜里翻来覆去的念头。"
  sent[28] = "荒原上有一口枯井，井壁上刻着许多名字，最上面的一个已经模糊。"
  sent[29] = "他伸手推门，门轴发出长长的呻吟，惊起檐下的一只宿鸟。"
  sent[30] = "冬夜很长，长得足够把一整个白日的疲惫慢慢熬成安静的睡意。"
  sent[31] = "多年以后他再回到这里，草木已经长过膝头，路也认不出来了。"
  sent[32] = "故事讲到这里，灯已经暗了，听故事的人却还坐着，迟迟不肯走。"

  bytes = 0
  n = 0
  while (bytes < target) {
    n++
    # 章间空行：放在**章首**（首章除外），保证文件末尾没有空行——
    # 末章最后一行正文直接结束于 EOF，只需一次 truncate 即可命中「不以换行截断」
    if (n > 1) {
      print ""
      bytes += 1
    }
    header = "第" n "章 荒原第" n "节"
    print header
    bytes += length(header) + 1
    for (i = 1; i <= n_lines; i++) {
      # 确定性轮转：同一 (seed, n, i) 永远取同一句，无随机数，跨机器可复现
      idx = ((n * 7 + i * 13 + seed) % n_sent) + 1
      print sent[idx]
      bytes += length(sent[idx]) + 1
    }
  }
  printf "CHAPTERS=%d\nUTF8_BYTES=%d\n", n, bytes > stats
}
' > "$UTF8_FILE"

# 去掉文件末尾的换行：末章最后一行正文直接结束于 EOF（覆盖末章边界）
if [ ! -s "$UTF8_FILE" ]; then
  echo "✗ 生成失败：UTF-8 中间文件为空" >&2
  exit 1
fi
truncate -s -1 "$UTF8_FILE"

# 严格转换：任一字符不在 GBK 码表内即失败（保证「无乱码」的可验证性）
iconv -f UTF-8 -t GBK "$UTF8_FILE" > "$OUT"

CHAPTERS="$(sed -n 's/^CHAPTERS=//p' "$STATS_FILE")"
UTF8_BYTES="$(sed -n 's/^UTF8_BYTES=//p' "$STATS_FILE")"
GBK_BYTES="$(wc -c < "$OUT" | tr -d ' ')"
FIRST_LINE="$(iconv -f GBK -t UTF-8 "$OUT" | head -1 || true)"
LAST_LINE="$(iconv -f GBK -t UTF-8 "$OUT" | tail -1 || true)"
if command -v sha256sum >/dev/null 2>&1; then
  SHA256="$(sha256sum "$OUT" | awk '{print $1}')"
else
  SHA256="$(shasum -a 256 "$OUT" | awk '{print $1}')"
fi

# 末字节必须是正文而非换行（0x0A）——验收表的「末章边界」前提
LAST_BYTE="$(tail -c 1 "$OUT" | od -An -tu1 | tr -d ' ')"
if [ "$LAST_BYTE" = "10" ]; then
  echo "✗ 生成缺陷：文件以换行结尾，末章边界用例失去意义" >&2
  exit 1
fi

{
  echo "SIZE_MB_TARGET=$SIZE_MB"
  echo "SEED=$SEED"
  echo "CHAPTERS=$CHAPTERS"
  echo "BODY_LINES_PER_CHAPTER=40"
  echo "GBK_BYTES=$GBK_BYTES"
  echo "UTF8_BYTES=$UTF8_BYTES"
  echo "ENCODING=GBK"
  echo "FIRST_LINE=$FIRST_LINE"
  echo "LAST_LINE=$LAST_LINE"
  echo "LAST_BYTE=$LAST_BYTE"
  echo "SHA256=$SHA256"
  echo "FILE=$OUT"
} > "$OUT.meta"

DEVIATION_PCT="$(awk -v a="$GBK_BYTES" -v b="$(( SIZE_MB * 1024 * 1024 ))" \
  'BEGIN { printf "%+.2f", (a - b) * 100 / b }')"

echo "✓ 样本已生成：$OUT"
echo "  章节数 CHAPTERS=$CHAPTERS"
echo "  GBK 体积  = $GBK_BYTES 字节（目标 ${SIZE_MB}MB，偏差 ${DEVIATION_PCT}%）"
echo "  UTF-8 体积 = $UTF8_BYTES 字节（供对照 book.txt 大小）"
echo "  首行      = $FIRST_LINE"
echo "  末行      = $LAST_LINE"
echo "  末字节    = $LAST_BYTE（非 0x0A：文末不以换行截断）"
echo "  SHA-256   = $SHA256"
echo "  元数据    = $OUT.meta"
