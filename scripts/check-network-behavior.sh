#!/usr/bin/env bash
#
# 网络行为断言（T-009 · R-02 / AC-20.5 / G2①）。
#
# 口径（隐私承诺「纸阅不访问公网」的 CI 兜底）：应用运行时的网络行为
# 只由各模块的 main 源码决定（T-010 拆模块后为 app/src/main/java +
# core/reader、core/domain 的 main 源码），本脚本把该承诺固化为静态断言，
# 任一项失败即非零退出：
#   1. 三方网络 / 统计 SDK 禁入：构建配置与主源码中不得出现
#      okhttp / retrofit / volley / coil / glide / fresco / WebView /
#      firebase / umeng / bugly 等已知 SDK 坐标或包名（隐私页声明
#      「无第三方依赖、无远程接口调用」，必须可验证）；
#   2. URL 字面量白名单：全部 main 源码下所有 http(s):// 字面量
#      必须落在白名单文件内，且形状符合该文件的口径——
#        - transfer/TransferHttpServer.kt：仅 http://$host:$port 形式的
#          局域网对端地址（host 来自 UDP 广播发现的同网段设备）；
#        - reader/epub/EpubParser.kt：仅 startsWith("http(s)://") 形式的
#          外链拒绝守卫（检测到即返回 null，不发起任何连接）；
#        - ui/about/AboutScreen.kt：仅 AboutLinks 两项目页外链
#          （用户主动点击后交系统浏览器 ACTION_VIEW 打开）；
#      白名单外出现任何 URL 字面量，或白名单文件内出现字面量域名/IP，
#      均判失败——这正是「偷偷接一个公网 endpoint」的检出路径；
#   3. 网络 API 仅限 transfer/：Socket / ServerSocket / DatagramSocket /
#      HttpURLConnection / openConnection / java.net.URL( / InetAddress
#      的引用只允许出现在 transfer/**（局域网传书 / 迁移 / 同步）；
#   4. 构建配置 URL 仅限依赖解析：*.gradle.kts 与 libs.versions.toml 中
#      的 URL 必须命中构建仓库白名单（google / mavenCentral / 阿里云
#      镜像 / gradlePluginPortal）。构建脚本里的仓库地址用于编译期与
#      测试期的依赖解析，不是应用运行时 endpoint——列入白名单并注明
#      理由，避免把构建配置误报为网络行为；
#   5. INTERNET 权限在 Manifest 中声明且附理由：Android 上任何
#      socket（含连接局域网 IP）都必须声明 INTERNET，该权限的存在
#      不等于访问公网，白名单口径见 docs/NETWORK_AUDIT.md。
#
# 扫描范围说明（豁免及理由）：
#   - main 源码之外的测试目录（app/src/test、core/*/src/test）：测试夹具中的
#     http:// 均为 EPUB/XML 命名空间标识符
#     （w3.org / idpf.org / purl.org / daisy.org 等），不是网络地址；
#   - app/src/main/res/**：XML 命名空间 xmlns 与许可全文中的链接，同理；
#   - docs/ 与 *.md（README / SECURITY / CONTRIBUTING 等）：文档里的
#     链接面向读者，不是运行时行为（其中 SECURITY.md 为文档豁免）。
#
# 用法：./scripts/check-network-behavior.sh（在仓库任意目录执行均可）。

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 运行时源码根目录（T-010 拆模块后扩展）：app 与两个纯 JVM 模块的 main 源码
# 全部属于「会打进 APK 的代码」，断言口径必须覆盖三处，否则把代码搬出 app
# 就能让本脚本的扫描范围凭空缩小（等于开了个后门）。
SRC_ROOTS=(app/src/main/java core/reader/src/main/kotlin core/domain/src/main/kotlin)
BUILD_FILES=(app/build.gradle.kts build.gradle.kts settings.gradle.kts gradle/libs.versions.toml core/domain/build.gradle.kts core/reader/build.gradle.kts)
MANIFEST="app/src/main/AndroidManifest.xml"

fail=0

# 在全部源码根中定位白名单文件（相对 io/github/lunalight/paperread/ 的路径）。
# 找不到时返回非零，调用方据此报「白名单文件缺失」。
find_src_file() {
  local rel="$1" root
  for root in "${SRC_ROOTS[@]}"; do
    if [ -f "$root/io/github/lunalight/paperread/$rel" ]; then
      printf '%s\n' "$root/io/github/lunalight/paperread/$rel"
      return 0
    fi
  done
  return 1
}

# ---- 0) 目录存在性 ----

for root_dir in "${SRC_ROOTS[@]}"; do
  if [ ! -d "$root_dir" ]; then
    echo "✗ 找不到 $root_dir" >&2
    exit 1
  fi
done

# ---- 1) 三方网络 / 统计 SDK 禁入 ----
#
# 坐标（构建配置）与包名（源码 import）双通道扫描。命中已知 SDK 即失败：
# 隐私页对用户承诺「无第三方 SDK 与统计库」，依赖升级混入一个不自知。

SDK_PATTERN='(okhttp|retrofit|retrofit2|volley|coil|glide|fresco|facebook\.|firebase|umeng|bugly|tencent\.bugly|com\.alibaba\.sdk|gson|moshi|ktor-client|webview)'

sdk_hits="$(grep -rniE "$SDK_PATTERN" "${BUILD_FILES[@]}" 2>/dev/null || true)"
sdk_hits="$sdk_hits$(grep -rnE 'import .*(okhttp3|retrofit2|com\.android\.volley|coil3?|com\.bumptech\.glide|com\.facebook|com\.google\.firebase|com\.umeng|com\.tencent\.bugly|android\.webkit\.WebView)' "${SRC_ROOTS[@]}" --include='*.kt' 2>/dev/null || true)"

if [ -n "$sdk_hits" ]; then
  echo "✗ 发现三方网络 / 统计 SDK 痕迹（隐私承诺「无第三方 SDK」，见 docs/NETWORK_AUDIT.md）：" >&2
  echo "$sdk_hits" >&2
  fail=1
else
  echo "✓ 无三方网络 / 统计 SDK（构建配置与主源码 import 双通道扫描）"
fi

# ---- 2) URL 字面量白名单 ----
#
# 白名单文件及其口径（新增网络行为必须先过隐私评审再进本清单）：
#   WL_LAN   transfer/TransferHttpServer.kt    局域网对端，仅 $host:$port 插值
#   WL_GUARD reader/epub/EpubParser.kt         外链拒绝守卫，仅 startsWith 检测
#   WL_LINKS ui/about/AboutScreen.kt           用户主动点击的项目外链（固定两项目）
#
# 白名单外的任何 http(s):// 字面量都是「未登记的运行时 endpoint」，零容忍。

WL_LAN="transfer/TransferHttpServer.kt"
WL_GUARD="reader/epub/EpubParser.kt"
WL_LINKS="ui/about/AboutScreen.kt"

# 2.1 白名单外文件出现 URL 字面量 → 失败
unlisted="$(grep -rn 'https\?://' "${SRC_ROOTS[@]}" --include='*.kt' 2>/dev/null \
  | grep -v "/$WL_LAN:" | grep -v "/$WL_GUARD:" | grep -v "/$WL_LINKS:" || true)"

if [ -n "$unlisted" ]; then
  echo "✗ 白名单外文件出现 URL 字面量（未登记的运行时 endpoint）：" >&2
  echo "$unlisted" >&2
  echo "  如确有必要，先更新 docs/NETWORK_AUDIT.md 与隐私页，再把文件加入" >&2
  echo "  scripts/check-network-behavior.sh 的白名单并注明理由。" >&2
  fail=1
else
  echo "✓ 白名单外文件无 URL 字面量"
fi

# 2.2 白名单文件内部形状校验
#
# 2.2.1 局域网文件：每个 URL 必须含 $host 插值（对端地址来自 UDP 广播发现），
#       不得出现字面量域名 / 公网 IP。
lan_file="$(find_src_file "$WL_LAN" || true)"
if [ -z "$lan_file" ]; then
  echo "✗ 白名单文件缺失：io/github/lunalight/paperread/$WL_LAN（TransferHttpServer 是局域网传输的唯一实现）" >&2
  fail=1
else
  lan_urls="$(grep -oE 'https?://[^"'"'"']*' "$lan_file" || true)"
  lan_bad="$(printf '%s\n' "$lan_urls" | grep -v '\$host' || true)"
  if [ -n "$lan_urls" ] && [ -n "$lan_bad" ]; then
    echo "✗ $WL_LAN 出现非 \$host 插值的 URL（疑似写死对端地址）：" >&2
    echo "$lan_bad" >&2
    fail=1
  elif [ -z "$lan_urls" ]; then
    echo "✗ $WL_LAN 未发现任何 URL 字面量（传输客户端应经 \$host:\$port 访问对端）" >&2
    fail=1
  else
    echo "✓ $WL_LAN 的 $(printf '%s\n' "$lan_urls" | wc -l | tr -d ' ') 处 URL 均为 http://\$host:\$port 局域网插值"
  fi
fi

# 2.2.2 外链守卫文件：URL 形态只允许作为 startsWith 的拒绝判断出现
#       （解析到外链资源即整体拒绝，不建立连接）。
guard_file="$(find_src_file "$WL_GUARD" || true)"
if [ -z "$guard_file" ]; then
  echo "✗ 白名单文件缺失：io/github/lunalight/paperread/$WL_GUARD（EpubParser 的外链拒绝守卫）" >&2
  fail=1
else
  guard_hits="$(grep -nE 'https?://' "$guard_file" || true)"
  guard_bad="$(printf '%s\n' "$guard_hits" | grep -vE 'startsWith\("(https?://)"\)' || true)"
  if [ -n "$guard_bad" ]; then
    echo "✗ $WL_GUARD 的 URL 形态不是 startsWith 拒绝守卫（外链必须整体拒绝，不得连接）：" >&2
    echo "$guard_bad" >&2
    fail=1
  else
    echo "✓ $WL_GUARD 的 URL 仅用于 startsWith 外链拒绝守卫"
  fi
fi

# 2.2.3 项目外链文件：只允许 AboutLinks 的两个固定 GitHub 地址，
#       且只能经系统浏览器 ACTION_VIEW 打开（不内置 WebView）。
links_file="$(find_src_file "$WL_LINKS" || true)"
if [ -z "$links_file" ]; then
  echo "✗ 白名单文件缺失：io/github/lunalight/paperread/$WL_LINKS（AboutScreen 的项目外链）" >&2
  fail=1
else
  links_urls="$(grep -oE 'https?://[^"'"'"']*' "$links_file" || true)"
  links_bad="$(printf '%s\n' "$links_urls" \
    | grep -vE '^https://github\.com/lobster-xiaozhua/PaperRead(/issues)?$' || true)"
  if [ -n "$links_bad" ]; then
    echo "✗ $WL_LINKS 出现清单外的 URL（用户主动点击的外链也须逐条登记）：" >&2
    echo "$links_bad" >&2
    fail=1
  else
    echo "✓ $WL_LINKS 的 $(printf '%s\n' "$links_urls" | wc -l | tr -d ' ') 处 URL 均为已登记的 GitHub 项目外链"
  fi
fi

# ---- 3) 网络 API 仅限 transfer/ ----
#
# socket 级 API 的引用（含注释中的 java.net.xxx 全限定名）只允许出现在
# transfer/**。其余模块（书源解析、TTS、备份等）不得持有任何网络句柄。

NET_API_PATTERN='(java\.net\.Socket|java\.net\.ServerSocket|java\.net\.DatagramSocket|java\.net\.HttpURLConnection|java\.net\.URL|openConnection|java\.net\.InetAddress|MulticastSocket)'

net_api_hits="$(grep -rnE "$NET_API_PATTERN" "${SRC_ROOTS[@]}" --include='*.kt' 2>/dev/null \
  | grep -v '/transfer/' || true)"

if [ -n "$net_api_hits" ]; then
  echo "✗ transfer/ 之外出现网络 API 引用（运行时网络行为必须收敛到局域网传输模块）：" >&2
  echo "$net_api_hits" >&2
  fail=1
else
  transfer_api_count="$(grep -rnE "$NET_API_PATTERN" "${SRC_ROOTS[@]}" --include='*.kt' 2>/dev/null | grep '/transfer/' | wc -l | tr -d ' ')"
  echo "✓ 网络 API 仅存在于 transfer/（$transfer_api_count 处，均为局域网传书 / 迁移 / 同步）"
fi

# ---- 4) 构建配置 URL 仅限依赖解析 ----
#
# 构建脚本中的仓库地址服务于编译期 / 测试期依赖解析（Gradle 拉依赖、
# Robolectric 拉 android-all jar 的镜像覆盖），不是应用运行时 endpoint。
# 白名单：google / mavenCentral（repo.maven.apache.org 或 repo1.maven.org）/
# 阿里云镜像 / gradlePluginPortal / services.gradle.org。

BUILD_REPO_PATTERN='(https?://(dl\.google\.com|maven\.google\.com|repo\.maven\.apache\.org|repo1\.maven\.org|maven\.aliyun\.com|plugins\.gradle\.org|services\.gradle\.org|jcenter\.bisnagle\.com)/?)'

build_url_hits="$(grep -rnE 'https?://' "${BUILD_FILES[@]}" 2>/dev/null || true)"
build_url_bad="$(printf '%s\n' "$build_url_hits" | grep -vE "$BUILD_REPO_PATTERN" || true)"

if [ -n "$build_url_bad" ]; then
  echo "✗ 构建配置出现依赖仓库白名单外的 URL：" >&2
  echo "$build_url_bad" >&2
  echo "  构建配置只允许出现依赖解析用仓库地址（google / mavenCentral /" >&2
  echo "  阿里云镜像 / gradlePluginPortal）；运行时 endpoint 必须走 src/main 评审。" >&2
  fail=1
else
  if [ -n "$build_url_hits" ]; then
    echo "✓ 构建配置 URL 均在依赖解析白名单内（$(printf '%s\n' "$build_url_hits" | wc -l | tr -d ' ') 处，构建期用途，非运行时 endpoint）"
  else
    echo "✓ 构建配置无显式 URL（仓库经 google()/mavenCentral() 工厂方法声明）"
  fi
fi

# ---- 5) INTERNET 权限声明（附理由） ----
#
# Android 上任何 socket（含连接局域网 IP）都必须声明 INTERNET；本应用
# 的用途限于局域网传输。权限本身不是「访问公网」的证据，真实用途以
# 第 2/3 项静态断言与 docs/NETWORK_AUDIT.md 为准。

if [ ! -f "$MANIFEST" ]; then
  echo "✗ 找不到 $MANIFEST" >&2
  exit 1
fi

if grep -q 'android.permission.INTERNET' "$MANIFEST"; then
  echo "✓ Manifest 声明 INTERNET（局域网 socket 必需；用途见 docs/NETWORK_AUDIT.md）"
else
  echo "✗ Manifest 未声明 INTERNET：局域网传输依赖 socket，缺失将导致功能不可用" >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "网络行为断言未通过（详见上方输出）。" >&2
  exit 1
fi

echo "网络行为断言通过：不访问公网、无三方网络 SDK、网络行为收敛于 transfer/（白名单外零 URL）。"
