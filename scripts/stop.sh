#!/usr/bin/env bash
# ============================================================
#  停止 API 服务（Linux / macOS）
#
#  设计原则与 stop.bat 一致：脚本层保持极薄。
#  进程匹配、端口检查全部交给 Node 侧——它按入口路径匹配，
#  端口则来自 server/config/config.json，脚本不必再解析一遍。
# ============================================================
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ENTRY="server/src/main.js"

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
  echo "  未找到 Node 运行时，无法执行停止命令。"
  echo "  若服务仍在运行，可手动结束：pkill -f 'server/src/main.js'"
  echo
  exit 1
fi

if [ ! -f "$ENTRY" ]; then
  echo
  echo "  缺少 $PWD/$ENTRY"
  echo "  若服务运行在本项目的另一份拷贝里，请到那份拷贝中停止。"
  echo
  exit 1
fi

# --stop 恒返回 0：「停掉了」与「本来就没在跑」都是幂等停止的正常结局。
"$NODE_BIN" "$ENTRY" --stop
exit $?
