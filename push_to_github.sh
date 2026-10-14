#!/usr/bin/env bash
# 把 main + 22 个版本分支推到 GitHub。
#
# 用法：
#   export GITHUB_TOKEN=ghp_xxx          # 或提前 gh auth login
#   ./push_to_github.sh
#
# 说明：脚本优先用 GitHub Git Data API（对 github.com 直连不稳的网络环境最可靠）。
#      如果 `git ls-remote` 能通，也可以直接走普通 git push（见脚本末尾的提示）。

set -euo pipefail

GITHUB_USERNAME="${GITHUB_USERNAME:-Zxin-Pro}"
GITHUB_REPO="${GITHUB_REPO:-unbounded-height}"
REPO_SLUG="${GITHUB_USERNAME}/${GITHUB_REPO}"
VISIBILITY="${VISIBILITY:-public}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

say "检查依赖"
for c in git python3 gh; do
  command -v "$c" >/dev/null || { echo "缺少 $c"; exit 1; }
done

say "检查 gh 登录状态"
if ! gh auth status >/dev/null 2>&1; then
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    echo "$GITHUB_TOKEN" | gh auth login --with-token
  else
    echo "未登录 gh，也没有 GITHUB_TOKEN。请先 gh auth login 或导出 GITHUB_TOKEN。"
    exit 1
  fi
fi
gh auth status

say "创建仓库 ${REPO_SLUG}（已存在则跳过）"
if gh api "repos/${REPO_SLUG}" >/dev/null 2>&1; then
  echo "仓库已存在，跳过创建"
else
  gh repo create "${REPO_SLUG}" --"${VISIBILITY}" \
    --description "Unbounded Height — remove Minecraft build height limits and auto-apply them to modded dimensions (1.20.1-26.3, Forge/NeoForge/Fabric)" \
    --add-readme
fi

say "生成 22 个分支（如果 tools/out 为空）"
cd "$(dirname "$0")"
if [ ! -d tools/out ] || [ -z "$(ls -A tools/out 2>/dev/null)" ]; then
  python3 tools/gen.py
fi

say "推送 main + 22 个版本分支"
python3 tools/push_all.py

say "完成"
cat <<EOF

仓库：    https://github.com/${REPO_SLUG}
分支：    https://github.com/${REPO_SLUG}/branches
Actions： https://github.com/${REPO_SLUG}/actions

如果 github.com 直连可用（git ls-remote 能通），也可以改用普通 git：
  for b in main forge-1.20.1 fabric-1.21.1; do
    ( cd "<分支目录>" && git init -q && git add -A && \\
      git -c user.email=you@example.com -c user.name=you commit -qm init && \\
      git remote add origin https://github.com/${REPO_SLUG}.git && \\
      git push -f origin HEAD:refs/heads/\$b )
  done
EOF
