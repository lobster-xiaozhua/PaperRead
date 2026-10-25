#!/usr/bin/env bash
#
# 文档口径一致性检查（T-001 · R-03 / G3）。
#
# 三项断言，任一失败即非零退出：
#   1. 权限清单：CONTRIBUTING.md 声明的 Manifest 权限与
#      app/src/main/AndroidManifest.xml 实际声明逐一一致（多写 / 漏写都算失败）。
#      比对源清单而非合并清单：合并清单会引入库贡献的权限（随依赖升级漂移），
#      本断言关注「本应用声明了什么」这一文档契约，取源清单确定可复现；
#   2. 覆盖率口径：文档不得写死行覆盖率实测数字（约 82% / 87%（1778/2036）等），
#      统一表述为「以 jacocoTestReport 实际 XML 报告为准」；门禁阈值
#      （≥ 80% 等构建配置引用）允许出现；
#   3. Kotlin 版本单一来源：文档不得硬编码 Kotlin 版本号，唯一来源是
#      gradle/libs.versions.toml。
#
# 扫描范围：仓库根与 docs/ 下的 Markdown。CHANGELOG.md 为追加式历史日志，
# 如实记录历史版本号属正常内容，不纳入扫描。
#
# 用法：./scripts/check-doc-consistency.sh（在仓库任意目录执行均可）。

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DOC_FILES=(AGENTS.md README.md CONTRIBUTING.md SECURITY.md docs/*.md)
MANIFEST="app/src/main/AndroidManifest.xml"

fail=0

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

if [ "$fail" -ne 0 ]; then
  echo "文档口径一致性检查未通过（详见上方输出）。" >&2
  exit 1
fi

echo "文档口径一致性检查通过。"
