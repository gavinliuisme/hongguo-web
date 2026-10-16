#!/usr/bin/env bash
# ============================================================
#  启动 API 服务（Linux / macOS）
#
#  设计原则与 start.bat 一致：脚本层保持极薄。
#  不解析 config.json、不判断 Node 版本、不决定任何事——
#  配置一律由 Node 侧读取 server/config/config.json。
#
#  这里只保留三件 Node 做不到的事：
#    1. 找 Node 运行时（没有 node 就跑不起后端代码）
#    2. 缺 Node 时提示如何安装
#    3. 拉起 server/src/main.js
# ============================================================
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ENTRY="server/src/main.js"

# ---------- 找 Node ----------
# 顺序：自带 node/ > NODE_HOME > PATH。
# 自带副本优先，是为了避免系统上的旧 Node(14/16) 抢先被选中。
find_node() {
  local exe="node"
  [ -x "node/bin/$exe" ] && { echo "$PWD/node/bin/$exe"; return; }
  [ -n "${NODE_HOME:-}" ] && [ -x "$NODE_HOME/bin/$exe" ] && { echo "$NODE_HOME/bin/$exe"; return; }
  command -v "$exe" 2>/dev/null && return
  echo ""
}

NODE_BIN="$(find_node)"

if [ -z "$NODE_BIN" ]; then
  echo
  echo "  未找到 Node 运行时。"
  echo
  echo "  发行包自带对应平台的 Node，正常不会走到这里；"
  echo "  若你是从源码运行，请任选一种方式："
  echo "    1) 安装 Node.js 18+：https://nodejs.org/   （推荐）"
  echo "    2) 把任意 Node 18+ 解压到项目的 node/ 目录（需要里面有 bin/node）"
  echo
  exit 1
fi

# ---------- 入口是否存在 ----------
if [ ! -f "$ENTRY" ]; then
  echo
  echo "  缺少 $PWD/$ENTRY"
  echo
  echo "  发行包自带完整代码，所以这里缺失通常是解压不完整。"
  echo
  exit 1
fi

# ---------- 拉起 ----------
exec "$NODE_BIN" "$ENTRY" "$@"
