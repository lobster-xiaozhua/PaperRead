#!/usr/bin/env bash
#
# 硬编码中文文案门禁（T-022 · US-21 AC-21.4）。
#
# 断言两件事：
#   1. **UI 层零容忍**：`app/src/main/java/**/ui/**` 的 Kotlin 源码里不得出现
#      带中文的字符串字面量（注释不算）。命中即失败，输出 文件:行:内容。
#   2. **数据层登记制**：UI 之外（transfer / backup / speech / diag / …）允许存在
#      中文话术，但必须逐个文件登记在 `scripts/strings-baseline.txt`，且**条数完全
#      相等**——多了（新增硬编码）或少了（登记过期）都失败，逼着评审看一眼 diff。
#
# 为什么数据层可以留：那些字符串是 `userMessage` 一类要参与**序列化 / 跨设备传输**
# 的话术（迁移包、同步、备份 manifest、通知栏按钮），`core` 侧模块不依赖 Android
# resources；把它们塞进 strings.xml 等于让协议依赖 UI 资源表。判据写在 baseline 每条
# 的「理由」列里，评审可逐条质疑。
#
# 为什么不做 `--update-baseline`：那等于给「顺手放行」修了一条快车道。
# 基线只能手工改，且改动会进 code review 的 diff——这是本门禁唯一的执法方式。
#
# 用法：
#   ./scripts/check-hardcoded-strings.sh          # 门禁模式（CI 与本地一致）
#   ./scripts/check-hardcoded-strings.sh --list   # 只打印当前命中，不做判定
#
# 依赖：GNU grep -P（ubuntu-latest 自带）。缺 -P 时本脚本以 2 退出并说明原因，
# 不会静默「什么都没扫到」。
#
# 中文范围：CJK 统一表意文字 + CJK 标点 + 全角形式。后者是刻意的——「，」「（」
# 「：」这些也是文案的一部分，只查汉字会漏掉「拼接出来的中文句子」。

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SCAN_ROOT="app/src/main/java"
UI_PREFIX="app/src/main/java/io/github/lunalight/paperread/ui/"
BASELINE="scripts/strings-baseline.txt"
MODE="${1:-check}"

# ---- 0) 预检：grep -P 与中文匹配能力 ----
#
# 少了这道预检，macOS 自带 BSD grep 会因不支持 -P 直接报 usage 错误，
# 而 `set -e` + 管道会让错误看起来像「没有命中」——门禁静默失效比红更危险。
if ! printf '"中"\n' | grep -qP '[\x{4e00}-\x{9fff}]' 2>/dev/null; then
  echo "✗ 本脚本需要支持 -P 与 \\x{...} 的 GNU grep（ubuntu-latest 自带）。" >&2
  echo "  当前 grep：$(grep --version 2>/dev/null | head -1 || echo unknown)" >&2
  exit 2
fi

CJK_CLASS='\x{4e00}-\x{9fff}\x{3000}-\x{303f}\x{ff00}-\x{ffef}'
LITERAL_RE="\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""

# ---- 1) 去注释 → 提取中文串字面量 ----
#
# 先剥注释（块注释跨行、行注释、字符串内的 // 不误判），再在剥离结果上
# 逐行 grep 中文串字面量。剥注释用 awk 保留行数（每行输出一行），
# 因此 grep -n 的行号与源文件一致——报错能直接点到那一行。
strip_comments() {
  awk '
    BEGIN { inblock = 0 }
    {
      out = ""; i = 1; n = length($0); instr = 0
      while (i <= n) {
        two = substr($0, i, 2)
        one = substr($0, i, 1)
        if (inblock) {
          if (two == "*/") { inblock = 0; i += 2 } else { i++ }
          continue
        }
        if (instr) {
          if (one == "\\") { out = out substr($0, i, 2); i += 2; continue }
          if (one == "\"") { instr = 0 }
          out = out one; i++; continue
        }
        if (two == "/*") { inblock = 1; i += 2; continue }
        if (two == "//") { break }
        if (one == "\"") { instr = 1; out = out one; i++; continue }
        out = out one; i++
      }
      print out
    }
  ' "$1"
}

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

HITS_FILE="$TMP_DIR/hits.txt"
: > "$HITS_FILE"

# 只扫 .kt（本仓库无 Java 源码）。用 while read 而非 for，避免路径含空格时被拆词。
while IFS= read -r -d '' kt; do
  rel="${kt#./}"
  stripped="$TMP_DIR/stripped.kt"
  strip_comments "$kt" > "$stripped"
  # -o 只取字面量本身；-n 给行号；空结果时 grep 退出码 1，用 || true 吞掉
  grep -noP "$LITERAL_RE" "$stripped" 2>/dev/null | while IFS=: read -r lineno literal; do
    if printf '%s' "$literal" | grep -qP "[$CJK_CLASS]"; then
      printf '%s\t%s\t%s\n' "$rel" "$lineno" "$literal"
    fi
  done >> "$HITS_FILE" || true
done < <(find "$SCAN_ROOT" -name '*.kt' -print0 | sort -z)

total_hits="$(wc -l < "$HITS_FILE" | tr -d ' ')"

# ---- 2) 拆分 UI / 非 UI ----
ui_hits="$(awk -v p="$UI_PREFIX" -F'\t' 'index($1, p) == 1' "$HITS_FILE" || true)"
ui_count="$(printf '%s' "$ui_hits" | grep -c . || true)"

if [ "$MODE" = "--list" ]; then
  echo "命中总数：$total_hits（UI $ui_count）"
  awk -F'\t' '{ printf "  %s:%s: %s\n", $1, $2, $3 }' "$HITS_FILE"
  exit 0
fi

fail=0

# ---- 3) 断言一：UI 层清零 ----
if [ "${ui_count:-0}" -gt 0 ]; then
  echo "✗ UI 层仍有 $ui_count 处硬编码中文文案（必须进 app/src/main/res/values/strings.xml）：" >&2
  printf '%s\n' "$ui_hits" | awk -F'\t' '{ printf "  %s:%s: %s\n", $1, $2, $3 }' >&2
  echo "  → 改法：新增 <string name=\"...\">，代码里用 stringResource() / context.getString()；" >&2
  echo "    多条文案复用同一个 key，不要复制粘贴。" >&2
  fail=1
else
  echo "✓ UI 层零硬编码中文文案（app/src/main/java/**/ui/**）"
fi

# ---- 4) 断言二：非 UI 层与 baseline 逐文件对账 ----
if [ ! -f "$BASELINE" ]; then
  echo "✗ 缺少基线文件 $BASELINE" >&2
  exit 1
fi

baseline_hits=0
while IFS= read -r line; do
  case "$line" in
    '' | '#'*) continue ;;
  esac
  path="${line%%|*}"
  rest="${line#*|}"
  expected="${rest%%|*}"
  reason="${rest#*|}"

  if [ -z "$path" ] || [ -z "$expected" ] || [ -z "$reason" ]; then
    echo "✗ 基线行格式错误（应为 路径|条数|理由）：$line" >&2
    fail=1
    continue
  fi
  if [ "${path#"$UI_PREFIX"}" != "$path" ]; then
    echo "✗ 基线不得登记 UI 层文件（UI 必须清零）：$path" >&2
    fail=1
    continue
  fi
  if [ ! -f "$path" ]; then
    echo "✗ 基线登记的文件已不存在：$path" >&2
    fail=1
    continue
  fi

  actual="$(awk -F'\t' -v p="$path" '$1 == p' "$HITS_FILE" | grep -c . || true)"
  baseline_hits=$((baseline_hits + expected))

  if [ "${actual:-0}" -ne "$expected" ]; then
    echo "✗ $path 登记 $expected 条中文话术，实际 $actual 条" >&2
    if [ "${actual:-0}" -gt "$expected" ]; then
      echo "  → 新增了未登记的话术（或原话术改成了字面量）。请确认它是否该进 strings.xml；" >&2
      echo "    确实属于数据层 userMessage 口径，再手工把基线数字改大并写明理由。" >&2
    else
      echo "  → 话术变少了（已资源化 / 已删除）。请把基线数字改小，别让登记过期。" >&2
    fi
    awk -F'\t' -v p="$path" '$1 == p { printf "    %s:%s: %s\n", $1, $2, $3 }' "$HITS_FILE" >&2
    fail=1
  fi
done < "$BASELINE"

# 反向查漏：数据层有命中但完全没登记的文件（新增文件最常见）
unregistered="$(awk -F'\t' -v p="$UI_PREFIX" '
  index($1, p) == 1 { next }
  { print $1 }
' "$HITS_FILE" | sort -u | while IFS= read -r f; do
  [ -z "$f" ] && continue
  grep -qF "$f|" "$BASELINE" || echo "$f"
done)"

if [ -n "$unregistered" ]; then
  echo "✗ 以下文件含中文话术但未登记进 $BASELINE：" >&2
  printf '%s\n' "$unregistered" | sed 's/^/  /' >&2
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "✓ 数据层登记一致（$baseline_hits 条，$(grep -cvE '^$|^#' "$BASELINE") 个文件；理由见 $BASELINE）"
  echo "硬编码文案门禁通过（UI 0 处，总命中 $total_hits 处均在登记范围内）。"
  exit 0
fi

echo "硬编码文案门禁未通过（详见上方输出）。" >&2
exit 1
