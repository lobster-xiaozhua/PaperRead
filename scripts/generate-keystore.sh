#!/usr/bin/env bash
# 生成 PaperRead release 签名 keystore。
#
# 产物默认落在 ~/.paperread/paperread-release.jks（绝不入库，.gitignore 已屏蔽 *.jks）。
# 用法：
#   ./scripts/generate-keystore.sh [keystore 输出路径]
#
# 生成后在 local.properties（或 ~/.gradle/gradle.properties）配置：
#   RELEASE_STORE_FILE=/absolute/path/to/paperread-release.jks
#   RELEASE_STORE_PASSWORD=<输入>
#   RELEASE_KEY_ALIAS=paperread
#   RELEASE_KEY_PASSWORD=<输入>
# 然后 ./gradlew assembleRelease 即产出已签名 APK。
set -euo pipefail

OUT="${1:-$HOME/.paperread/paperread-release.jks}"
ALIAS="paperread"

if [ -f "$OUT" ]; then
    echo "已存在：$OUT（如需重新生成请先删除）" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")"
keytool -genkeypair -v \
    -keystore "$OUT" \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storetype PKCS12 \
    -dname "CN=PaperRead, OU=Open Source, O=lunalight, L=, S=, C=CN"

echo
echo "已生成：$OUT"
echo "请把下列四项写入 local.properties（勿提交到 git）："
echo "  RELEASE_STORE_FILE=$OUT"
echo "  RELEASE_STORE_PASSWORD=<你输入的 store 密码>"
echo "  RELEASE_KEY_ALIAS=$ALIAS"
echo "  RELEASE_KEY_PASSWORD=<你输入的 key 密码>"
