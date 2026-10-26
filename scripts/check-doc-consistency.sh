#!/usr/bin/env bash
#
# 文档口径一致性检查（T-001 · R-03 / G3）。
#
# 五项断言，任一失败即非零退出：
#   1. 权限清单：CONTRIBUTING.md 声明的 Manifest 权限与
#      app/src/main/AndroidManifest.xml 实际声明逐一一致（多写 / 漏写都算失败）。
#      比对源清单而非合并清单：合并清单会引入库贡献的权限（随依赖升级漂移），
#      本断言关注「本应用声明了什么」这一文档契约，取源清单确定可复现；
#   2. 覆盖率口径：文档不得写死行覆盖率实测数字（约 82% / 87%（1778/2036）等），
#      统一表述为「以 jacocoTestReport 实际 XML 报告为准」；门禁阈值
#      （≥ 80% 等构建配置引用）允许出现；
#   3. Kotlin 版本单一来源：文档不得硬编码 Kotlin 版本号，唯一来源是
#      gradle/libs.versions.toml；
#   4. 单测总数单一来源：README / docs/ROADMAP.md / docs/TASKS.md「质量基线」
#      里的「N 单测」必须等于 app/src/test 下 @Test 用例数。
#      单一来源取**代码**而非某份文档：2026-10 批次 4 收尾时三份文档同时停在
#      v1.2.0 的 382，即漂移是「彼此一致地过期」，只做文档互比拦不住。
#      TASKS.md 内已完成工单里如实记录的当时数字（如「244 单测全绿」）属历史
#      记录，不参与比对；
#   5. CI 作业数：文档中「N 门禁 / N 个作业 / N 作业」的 N 必须等于
#      .github/workflows/android.yml 的 jobs 顶层键个数，且每个作业名都要在
#      README.md 以反引号形式登记（新增作业而不写文档 = 失败）。
#
# 扫描范围：仓库根与 docs/ 下的 Markdown。CHANGELOG.md 为追加式历史日志，
# 如实记录历史版本号 / 当时的作业结构属正常内容，不纳入扫描。
#
# 用法：./scripts/check-doc-consistency.sh（在仓库任意目录执行均可）。

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DOC_FILES=(AGENTS.md README.md CONTRIBUTING.md SECURITY.md docs/*.md)
MANIFEST="app/src/main/AndroidManifest.xml"

fail=0

# 中文数字 → 阿拉伯数字（作业数不会超过十，只覆盖单字写法）。
cn2num() {
  case "$1" in
    一) echo 1 ;;
    二) echo 2 ;;
    三) echo 3 ;;
    四) echo 4 ;;
    五) echo 5 ;;
    六) echo 6 ;;
    七) echo 7 ;;
    八) echo 8 ;;
    九) echo 9 ;;
    十) echo 10 ;;
    *) echo 0 ;;
  esac
}

# ---- 1) 权限清单比对（CONTRIBUTING.md vs AndroidManifest.xml） ----

manifest_perms="$(grep -o 'android\.permission\.[A-Z_]*' "$MANIFEST" | sort -u || true)"
doc_perms="$(grep -o 'android\.permission\.[A-Z_]*' CONTRIBUTING.md | sort -u || true)"

if [ "$manifest_perms" != "$doc_perms" ]; then
  echo "✗ 权限清单不一致（CONTRIBUTING.md vs $MANIFEST）：" >&2
  echo "--- Manifest 实际声明 ---" >&2
  echo "$manifest_perms" >&2
  echo "--- CONTRIBUTING.md 声明 ---" >&2
  echo "$doc_perms" >&2
  echo "请以 Manifest 为准修正 CONTRIBUTING.md（或评审后同步改 Manifest）。" >&2
  fail=1
else
  echo "✓ 权限清单一致（$(printf '%s\n' "$manifest_perms" | wc -l) 项：$(printf '%s' "$manifest_perms" | tr '\n' ' ')）"
fi

# ---- 2) 覆盖率实测数字禁入文档 ----
#
# 实测值特征：「约 / ≈ + 百分比」或「百分比 + （」（带分数的实测口径，
# 如 87%（1778/2036））；行门禁阈值（门禁 / ≥）为构建配置引用，允许出现。

cov_hits="$(grep -REn '(行覆盖|覆盖率)[^。|]{0,14}[0-9]+(\.[0-9]+)?%' "${DOC_FILES[@]}" 2>/dev/null \
  | grep -Ev '门禁|≥|阈值' || true)"

if [ -n "$cov_hits" ]; then
  echo "✗ 文档写死了行覆盖率实测数字（统一口径：以 jacocoTestReport 实际 XML 为准）：" >&2
  echo "$cov_hits" >&2
  fail=1
else
  echo "✓ 无写死覆盖率实测数字"
fi

# ---- 3) Kotlin 版本号禁入文档（单一来源 = libs.versions.toml） ----

kotlin_hits="$(grep -REn 'Kotlin[^。|]{0,14}[0-9]+\.[0-9]+(\.[0-9]+)?' "${DOC_FILES[@]}" 2>/dev/null || true)"

if [ -n "$kotlin_hits" ]; then
  echo "✗ 文档硬编码了 Kotlin 版本号（单一来源为 gradle/libs.versions.toml）：" >&2
  echo "$kotlin_hits" >&2
  fail=1
else
  echo "✓ 无硬编码 Kotlin 版本号"
fi

# ---- 4) 单测总数单一来源（app/src/test 的 @Test 用例数） ----
#
# 计数口径：一个 @Test 注解 = 一例。本项目当前无参数化用例（@ParameterizedTest /
# Parameterized runner，一个注解对应多例）；若将来引入，需同步调整本口径，
# 否则断言会偏小。
# 文档侧写法要求：精确数字（「530 单测」），不写「约 500」「500+」——后两者
# 既不可断言，也会让断言被绕过。

TEST_DIR="app/src/test"
TEST_CLAIM_RE='[0-9]{2,}[ ]*个?[ ]*(单测|单元测试)'

actual_tests="$(grep -rhoE '@Test\b' --include='*.kt' "$TEST_DIR" 2>/dev/null | wc -l | tr -d ' ')"

# 逐行比对某文件中「N 单测」的数字。$2 非空时只比对内容命中该正则的行
# （用于把 TASKS.md 限定在「质量基线」段，历史工单行不参与）。
check_test_claims() {
  local file="$1"
  local scope_re="${2:-}"
  local lineno content n matched src

  if [ -n "$scope_re" ]; then
    src="$(grep -nE "$scope_re" "$file" 2>/dev/null || true)"
  else
    src="$(grep -n '' "$file" 2>/dev/null || true)"
  fi

  while IFS= read -r line_content; do
    [ -z "$line_content" ] && continue
    lineno="${line_content%%:*}"
    content="${line_content#*:}"
    matched="$(printf '%s\n' "$content" | grep -oE "$TEST_CLAIM_RE" || true)"
    [ -z "$matched" ] && continue
    for n in $(printf '%s\n' "$matched" | grep -oE '[0-9]+' | sort -u); do
      if [ "$n" != "$actual_tests" ]; then
        echo "✗ $file:$lineno 写「$n 单测」，而 $TEST_DIR 下 @Test 用例数为 $actual_tests" >&2
        echo "  → 测试数变了就同步改文档；本仓库口径为精确数字（不写「约」「N+」）" >&2
        fail=1
      fi
    done
  done <<< "$src"
}

if [ "${actual_tests:-0}" -lt 1 ]; then
  echo "✗ 无法统计 $TEST_DIR 下的 @Test 用例数（结果为 $actual_tests），请检查仓库完整性" >&2
  fail=1
else
  fail_before_test="$fail"
  check_test_claims README.md
  check_test_claims docs/ROADMAP.md
  check_test_claims docs/TASKS.md '\*\*质量基线\*\*'
  if [ "$fail" -eq "$fail_before_test" ]; then
    echo "✓ 单测总数口径一致（$actual_tests 例，来源：$TEST_DIR 的 @Test 计数）"
  fi
fi

# ---- 5) CI 作业数口径（.github/workflows/android.yml 的 jobs 顶层键） ----
#
# 作业结构多次演进（单次 Gradle 调用 → 四 → 七），文档里「N 门禁 / N 个作业」
# 这类概述最容易停在旧数字。作业数从 workflow 解析（`^  [a-z-]+:` 顶层键），
# 文档侧数字与 README 的作业清单都必须跟它对齐。

WORKFLOW=".github/workflows/android.yml"
JOB_CLAIM_RE='([0-9]+|[一二三四五六七八九十])[ ]*(道|个)?[ ]*(独立)?[ ]*(作业|job|门禁)'

actual_jobs="$(sed -n '/^jobs:/,$p' "$WORKFLOW" | grep -cE '^  [a-z][a-z0-9-]*:$' || true)"
job_ids="$(sed -n '/^jobs:/,$p' "$WORKFLOW" | grep -oE '^  [a-z][a-z0-9-]*:$' | tr -d ' :' | sort)"

if [ "${actual_jobs:-0}" -lt 1 ]; then
  echo "✗ 无法从 $WORKFLOW 解析 CI 作业数（结果为 $actual_jobs）" >&2
  fail=1
else
  fail_before_jobs="$fail"

  # 5a：每个作业名都要在 README.md 以反引号形式出现（新增作业必须同步文档）
  missing_jobs=""
  for j in $job_ids; do
    grep -qF -- "\`$j\`" README.md || missing_jobs="$missing_jobs $j"
  done
  if [ -n "$missing_jobs" ]; then
    echo "✗ README.md 未登记以下 CI 作业（应写成 \`job-name\`）：$missing_jobs" >&2
    echo "  实际作业（$actual_jobs 个）：$(printf '%s' "$job_ids" | tr '\n' ' ')" >&2
    echo "  → 新增 / 改名作业后，同步 README「开发规范」的 CI 门禁清单" >&2
    fail=1
  fi

  # 5b：文档「N 门禁 / N 个作业 / N 作业」的数字必须等于实际作业数
  for f in "${DOC_FILES[@]}" "$WORKFLOW"; do
    while IFS= read -r hit; do
      [ -z "$hit" ] && continue
      lineno="${hit%%:*}"
      content="${hit#*:}"
      phrase_hits="$(printf '%s\n' "$content" | grep -oE "$JOB_CLAIM_RE" || true)"
      [ -z "$phrase_hits" ] && continue
      while IFS= read -r phrase; do
        [ -z "$phrase" ] && continue
        raw_num="$(printf '%s' "$phrase" | grep -oE '^([0-9]+|[一二三四五六七八九十])' || true)"
        case "$raw_num" in
          [0-9]*) claim_num="$raw_num" ;;
          '' | *) claim_num="$(cn2num "${raw_num:-}")" ;;
        esac
        if [ "$claim_num" != "$actual_jobs" ]; then
          echo "✗ $f:$lineno 写「$phrase」，而 $WORKFLOW 实际有 $actual_jobs 个作业" >&2
          echo "  → 现状：$(printf '%s' "$job_ids" | tr '\n' ' ')" >&2
          fail=1
        fi
      done <<< "$phrase_hits"
    done <<< "$(grep -nE "$JOB_CLAIM_RE" "$f" 2>/dev/null || true)"
  done

  if [ "$fail" -eq "$fail_before_jobs" ]; then
    echo "✓ CI 作业数口径一致（$actual_jobs 个：$(printf '%s' "$job_ids" | tr '\n' ' '))"
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "文档口径一致性检查未通过（详见上方输出）。" >&2
  exit 1
fi

echo "文档口径一致性检查通过。"
