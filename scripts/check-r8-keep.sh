#!/usr/bin/env bash
#
# R8 keep 断言（T-008 · R-07 / G7，架构设计 §6.2「方案 B」）。
#
# 背景：R8 的运行时行为（混淆后 kotlinx.serialization 反射 / 生成序列化器是否仍
# 可用、Application 能否起来）只能在真机 / 模拟器上验证，本仓库的 CI runner 无
# KVM、跑不了 instrumented 测试。方案 B 把「运行时冒烟」退化为两件可执行的事：
#   1. **mapping 静态断言**（本脚本）：R8 产物 mapping.txt 里必须仍存在
#      序列化契约类型及其生成的 `$$serializer`——类被裁掉 / 序列化器被裁掉，
#      运行时的 `SerializationException` 就是必然；
#   2. **release 变体单测**（ReleaseSerializationTest）：在 clientRelease 变体下
#      对全部序列化对象做 JSON round-trip，验证「序列化契约在 release 变体下成立」。
# 代价（明确承认）：两者都不覆盖 **R8 混淆后**的真实运行时行为，须由真机冒烟补齐；
# 预留步骤见 .github/workflows/android.yml 的 r8-smoke 作业注释。
#
# 断言的能力边界（QA 第 0 阶段出口验收实测，勿高估本脚本）：
# - 能检出：契约类 / $$serializer 被 R8 **裁掉**（从产物消失 → mapping 无该类行）；
#   已用「从 mapping 删行」的方式复验过三组断言均会红。
# - **不能**检出：proguard-rules.pro 的 keep 规则被删除 / 削弱。原因有二：
#   ① 本脚本比对的是 mapping 的**左侧原 FQCN**，而 R8 只做改名时该类仍会留下
#     `<原FQCN> -> a.a.b:` 行，改名对被裁判定不可见；
#   ② kotlinx-serialization-core 的 jar 自带 consumer 规则
#     （META-INF/proguard/kotlinx-serialization-common.pro）已覆盖 Companion /
#     serializer() / INSTANCE / descriptor 的保留，本仓库的三条规则属叠加冗余。
#   实测：注释掉本仓库全部 keep 规则后 assembleClientRelease 仍成功、三组断言仍全绿
#   （仅混淆编号变化：PresencePacket -> d2.u0）。故「改 keep 规则」这条路不能靠
#   本脚本守，须靠人工评审 + 真机 install smoke。
#
# 断言分三组：
#   A. @Serializable 漂移守卫：各模块 main 源码中每个 @Serializable 声明（按简单名）
#      都必须在 mapping 中出现——新增序列化类型时**自动**纳入，无需改脚本；
#   B. 关键契约 FQCN：envelope / state / error 三类逐一断言（被裁即失败）；
#   C. 生成序列化器：上述每个类型的 `<类型>$$serializer` 必须存在
#      （kotlinx.serialization 的 `serializer()` 是 reflectively 取到的入口）。
#
# 前置：先跑 `./gradlew assembleClientRelease`（R8 + 资源收缩全开）。
# 用法：./scripts/check-r8-keep.sh
#
# 退出码：0 = 全部通过；1 = 任一组断言失败或 mapping 缺失。

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MAPPING="app/build/outputs/mapping/clientRelease/mapping.txt"
# @Serializable 漂移守卫的扫描范围（T-010 拆模块后扩展，T-013 增补 :core:data）：
# app 与各 core 模块的 main 源码都会打进 APK，任何一处新增序列化类型都要被本守卫覆盖。
SRC_ROOTS=(app/src/main core/domain/src/main core/reader/src/main core/data/src/main)
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail=0

if [ ! -f "$MAPPING" ]; then
  echo "✗ 找不到 R8 mapping：$MAPPING" >&2
  echo "  请先执行：./gradlew assembleClientRelease（R8 关闭时不会产出 mapping）" >&2
  exit 1
fi

# mapping 每行形如 `<原 FQCN> -> <混淆后名>:`（类行不缩进），成员行带缩进且
# 左值形如 `12:34:returnType pkg.Class.method(...)`。这里先把**类行**的左值
# 抽成一份精确名单（排序去重），后续用 `grep -F -x` 精确比对——
# 不用 `grep -E "^$fqcn -> "`：FQCN 里的 `$`（内部类）会被当正则锚点，
# 导致 `ImportError$ParseFailed` / `Foo$$serializer` 永远匹配不上（首版踩过）。
sed -n 's/^\([^[:space:]].*\) -> .*$/\1/p' "$MAPPING" | sort -u > "$TMP/kept_names.txt"

kept() {
  grep -qxF "$1" "$TMP/kept_names.txt"
}

# 按「简单名」判断保留：精确等于 `Name`，或以 `.Name` / `$Name` 结尾
kept_simple() {
  awk -v n="$1" '
    {
      if ($0 == n) { found = 1; exit }
      ln = length(n)
      if (length($0) > ln && substr($0, length($0) - ln + 1) == n) {
        c = substr($0, length($0) - ln, 1)
        if (c == "." || c == "$") { found = 1; exit }
      }
    }
    END { exit(found ? 0 : 1) }
  ' "$TMP/kept_names.txt"
}

header() { echo; echo "---- $1 ----"; }

# ---- 0) mapping 有效性（防止「mapping 存在但 R8 没跑」导致断言假绿） ----

header "0) mapping 有效性"
class_count="$(grep -cE '^.* -> .*:$' "$MAPPING" || true)"
renamed_count="$(
  awk -F' -> ' '
    /^.* -> .*:$/ {
      left = $1
      right = $2
      sub(/:$/, "", right)
      if (left != right) n++
    }
    END { print n + 0 }
  ' "$MAPPING"
)"
if [ "$class_count" -lt 500 ]; then
  echo "✗ mapping 仅 $class_count 个类，疑似 R8 未真正启用（isMinifyEnabled / 变体有误）" >&2
  fail=1
elif [ "$renamed_count" -lt 100 ]; then
  echo "✗ 仅 $renamed_count 个类被重命名，混淆疑似未生效（-dontobfuscate？）" >&2
  fail=1
else
  echo "✓ mapping 含 $class_count 个类，其中 $renamed_count 个被重命名（混淆确实生效）"
fi

# ---- A) @Serializable 漂移守卫 ----
#
# 提取方式：取 @Serializable 下一行（注解与声明分行的写法在本仓库是统一的），
# 剥掉 private / internal / data 修饰后取类型名。新增序列化类型无需改本脚本。

header "A) @Serializable 类型保留（漂移守卫）"
grep -rhA1 '@Serializable' "${SRC_ROOTS[@]}" --include='*.kt' 2>/dev/null \
  | sed -n 's/^\(private \|internal \|public \)*\(data \|sealed \|abstract \)*\(class\|object\|interface\) \([A-Za-z0-9_]*\).*/\4/p' \
  | sort -u > "$TMP/serializable_names.txt"

serializable_total="$(wc -l < "$TMP/serializable_names.txt" | tr -d ' ')"
if [ "$serializable_total" -eq 0 ]; then
  echo "✗ 未从 ${SRC_ROOTS[*]} 提取到任何 @Serializable 类型（提取规则失效？）" >&2
  fail=1
else
  missing_serializable=""
  while read -r name; do
    [ -n "$name" ] || continue
    if ! kept_simple "$name"; then
      missing_serializable="$missing_serializable $name"
    fi
  done < "$TMP/serializable_names.txt"
  if [ -n "$missing_serializable" ]; then
    echo "✗ 以下 @Serializable 类型在 R8 产物中缺失（被裁掉 = 运行时序列化必失败）：" >&2
    echo "  $missing_serializable" >&2
    fail=1
  else
    echo "✓ $serializable_total 个 @Serializable 类型全部保留在产物中"
  fi
fi

# ---- B) 关键契约 FQCN ----
#
# 三类（清单来自 grep 全量核对）：
#   envelope / state / snapshot：传书、迁移、进度同步的线上契约；
#   error：导入失败的结构化原因（UI 话术依赖 data object / data class 的
#          `userMessage` 常量与 sealed 子类身份）；
#   route：类型安全导航的 @Serializable 路由（参数经序列化进 Bundle）。

header "B) 关键序列化契约 FQCN"
CONTRACT_TYPES=(
  # 传输协议 / 进度同步
  "io.github.lunalight.paperread.transfer.PresencePacket"
  "io.github.lunalight.paperread.transfer.SyncBookmark"
  "io.github.lunalight.paperread.transfer.BookSyncState"
  "io.github.lunalight.paperread.transfer.SyncEnvelope"
  # 换机迁移
  "io.github.lunalight.paperread.transfer.BookmarkSnapshot"
  "io.github.lunalight.paperread.transfer.BookStateSnapshot"
  "io.github.lunalight.paperread.transfer.MigrationManifest"
  # 发现服务元数据（private @Serializable，同样走生成的序列化器）
  "io.github.lunalight.paperread.transfer.ServerMeta"
  # 类型安全导航路由
  "io.github.lunalight.paperread.ui.nav.BookshelfRoute"
  "io.github.lunalight.paperread.ui.nav.ReaderRoute"
  "io.github.lunalight.paperread.ui.nav.SettingsRoute"
  "io.github.lunalight.paperread.ui.nav.AboutRoute"
  "io.github.lunalight.paperread.ui.nav.LicenseRoute"
  "io.github.lunalight.paperread.ui.nav.PrivacyRoute"
  "io.github.lunalight.paperread.ui.nav.TransferRoute"
  # 导入失败结构化原因（sealed + 8 子类）
  "io.github.lunalight.paperread.data.repository.ImportError"
  "io.github.lunalight.paperread.data.repository.ImportError\$UnsupportedFormat"
  "io.github.lunalight.paperread.data.repository.ImportError\$InsufficientSpace"
  "io.github.lunalight.paperread.data.repository.ImportError\$DrmEncrypted"
  "io.github.lunalight.paperread.data.repository.ImportError\$ReadFailed"
  "io.github.lunalight.paperread.data.repository.ImportError\$DirCreateFailed"
  "io.github.lunalight.paperread.data.repository.ImportError\$DecodeFailed"
  "io.github.lunalight.paperread.data.repository.ImportError\$ParseFailed"
  "io.github.lunalight.paperread.data.repository.ImportError\$SourceMissing"
)

missing_contract=""
for fqcn in "${CONTRACT_TYPES[@]}"; do
  if ! kept "$fqcn"; then
    missing_contract="$missing_contract ${fqcn##*.}"
  fi
done
if [ -n "$missing_contract" ]; then
  echo "✗ 关键契约类型在 R8 产物中缺失：$missing_contract" >&2
  fail=1
else
  echo "✓ ${#CONTRACT_TYPES[@]} 个关键契约类型全部保留（envelope / state / error / route）"
fi

# ---- C) 生成的序列化器 ----
#
# `Foo$$serializer` 是 kotlinx.serialization 编译器插件生成的类；`Foo.serializer()`
# 返回它。R8 若把 `$$serializer` 裁掉或改写其 `Companion`，运行时立刻
# SerializationException——proguard-rules.pro 的 keep 规则正是为此存在。
#
# 只列 **class**（data class）类型：`data object` 路由的序列化器是编译器生成的
# 匿名 object（`_init_$_anonymous_()` 里的单例），R8 会把它合并进宿主类，
# 产物里本来就不存在 `BookshelfRoute$$serializer` 这个类名——按类名断言会假红。
# data object 的运行时路径是 `X.serializer()` 返回单例，由
# `ReleaseSerializationTest.navigation routes round-trip` 覆盖（解码 `{}`），
# 其类型本身由上方 B 组断言保留。

header "C) 生成的 \$\$serializer 保留"
SERIALIZER_TYPES=(
  "io.github.lunalight.paperread.transfer.PresencePacket"
  "io.github.lunalight.paperread.transfer.SyncBookmark"
  "io.github.lunalight.paperread.transfer.BookSyncState"
  "io.github.lunalight.paperread.transfer.SyncEnvelope"
  "io.github.lunalight.paperread.transfer.BookmarkSnapshot"
  "io.github.lunalight.paperread.transfer.BookStateSnapshot"
  "io.github.lunalight.paperread.transfer.MigrationManifest"
  "io.github.lunalight.paperread.transfer.ServerMeta"
  "io.github.lunalight.paperread.ui.nav.ReaderRoute"
  "io.github.lunalight.paperread.ui.nav.TransferRoute"
)

missing_serializer=""
for fqcn in "${SERIALIZER_TYPES[@]}"; do
  if ! kept "${fqcn}\$\$serializer"; then
    missing_serializer="$missing_serializer ${fqcn##*.}"
  fi
done
if [ -n "$missing_serializer" ]; then
  echo "✗ 以下类型的 \$\$serializer 被裁掉（运行时 SerializationException）：$missing_serializer" >&2
  echo "  检查 app/proguard-rules.pro 的 kotlinx.serialization keep 规则。" >&2
  fail=1
else
  echo "✓ ${#SERIALIZER_TYPES[@]} 个 \$\$serializer 全部保留"
fi

if [ "$fail" -ne 0 ]; then
  echo >&2
  echo "R8 keep 断言未通过（详见上方输出）。" >&2
  exit 1
fi

echo
echo "R8 keep 断言通过：序列化契约类型与生成序列化器均保留（mapping=$MAPPING）。"
echo "注意：本脚本是静态断言，不覆盖 R8 后的运行时行为；真机 install smoke 见 CI 注释。"
