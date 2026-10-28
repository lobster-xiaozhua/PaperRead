#!/usr/bin/env bash
#
# publish-v0.2.0.sh —— 在「能访问 GitHub」的机器上运行，覆盖远程遗留信息并发布 v0.2.0。
# 沙箱直连 github.com 被阻断，故由你在本地 / CI 执行本脚本即可完成「清理 + 覆盖 + 发布」。
#
# 前置：处于 PaperRead 仓库根目录，工作区干净，且已存在本地 tag v0.2.0。
# 用法：
#   GITHUB_TOKEN=ghp_xxx ./scripts/publish-v0.2.0.sh --yes
#   ./scripts/publish-v0.2.0.sh --token ghp_xxx --yes
#
# 动作（均为破坏性，需 --yes 确认）：
#   1. 强制推送当前分支 -> 远程默认分支（覆盖遗留历史，保留完整 41+ 提交）
#   2. 删除远程除默认分支外的所有分支
#   3. 删除远程除 v0.2.0 外的所有 tag
#   4. 推送 v0.2.0 tag
#   5. 删除远程除 v0.2.0 外的所有 Release
#   6. 创建 v0.2.0 Release（无 APK 附件；APK 需另行签名构建后手动上传）
#
set -euo pipefail

REPO="lobster-xiaozhua/PaperRead"
API="https://api.github.com/repos/${REPO}"

TOKEN=""
YES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --token) TOKEN="$2"; shift 2;;
    --yes)   YES=1; shift;;
    *) echo "未知参数: $1" >&2; exit 1;;
  esac
done
TOKEN="${TOKEN:-${GITHUB_TOKEN:-}}"
[[ -z "$TOKEN" ]] && { echo "缺少 token：用 --token <PAT> 或 GITHUB_TOKEN 环境变量" >&2; exit 1; }
[[ $YES -ne 1 ]] && { echo "破坏性操作，确认请加 --yes（会覆盖远程、删除旧分支/tag/Release）" >&2; exit 1; }

api() { curl -fsS -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "$@"; }

echo "==> 探测默认分支"
DEFAULT_BRANCH=$(api "${API}" | python3 -c "import sys,json;print(json.load(sys.stdin)['default_branch'])")
LOCAL_BRANCH=$(git rev-parse --abbrev-ref HEAD)
echo "    本地分支=${LOCAL_BRANCH}  远程默认分支=${DEFAULT_BRANCH}"

echo "==> 配置 origin（token 仅用于本次推送，不写入仓库文件）"
git remote remove origin 2>/dev/null || true
git remote add origin "https://x-access-token:${TOKEN}@github.com/${REPO}.git"

echo "==> [1/6] 强制推送 ${LOCAL_BRANCH} -> ${DEFAULT_BRANCH}（覆盖遗留历史）"
git push --force origin "${LOCAL_BRANCH}:${DEFAULT_BRANCH}"

echo "==> [2/6] 删除远程其他分支"
for b in $(git ls-remote --heads origin | sed 's#.*refs/heads/##' | grep -v "^${DEFAULT_BRANCH}$"); do
  echo "    删除分支 ${b}"; git push origin --delete "${b}" || echo "    (跳过 ${b})"
done

echo "==> [3/6] 删除远程旧 tag（保留 v0.2.0）"
for t in $(git ls-remote --tags origin | sed 's#.*refs/tags/##; s#^{}$##' | grep -v '^v0\.2\.0$'); do
  echo "    删除 tag ${t}"; git push origin --delete "refs/tags/${t}" || echo "    (跳过 ${t})"
done

echo "==> [4/6] 推送 v0.2.0 tag"
git push origin "v0.2.0"

echo "==> [5/6] 删除旧 Release（保留 v0.2.0）"
api "${API}/releases" | python3 -c "import sys,json;[print(r['id']) for r in json.load(sys.stdin)]" | while read -r rid; do
  tag=$(api "${API}/releases/${rid}" | python3 -c "import sys,json;print(json.load(sys.stdin).get('tag_name',''))")
  if [[ "$tag" != "v0.2.0" ]]; then
    echo "    删除 Release #${rid} (tag=${tag})"; api -X DELETE "${API}/releases/${rid}" >/dev/null || echo "    (跳过 #${rid})"
  fi
done

echo "==> [6/6] 创建 v0.2.0 Release（无 APK 附件）"
if api "${API}/releases/tags/v0.2.0" >/dev/null 2>&1; then
  echo "    v0.2.0 Release 已存在，跳过"
else
  api -X POST "${API}/releases" \
    -d '{"tag_name":"v0.2.0","name":"PaperRead v0.2.0","body":"首个公开版本。详见 CHANGELOG.md 与 README.md。APK 由签名构建流程产出后上传至本 Release。","draft":false,"prerelease":false}' \
    >/dev/null && echo "    已创建 v0.2.0 Release"
fi

echo "完成：远程仓库已覆盖为 v0.2.0 单一主分支，遗留分支/tag/Release 已清理。"
