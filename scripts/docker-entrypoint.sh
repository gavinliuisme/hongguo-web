#!/bin/sh
# ================================================================
#  docker-entrypoint.sh
#
#  容器启动入口包装。原因：
#  Dockerfile 的 VOLUME /app/data 指令会让宿主机卷挂载时覆盖镜像内
#  /app/data/ 下所有内容（包括构建时 mkdir 出来的子目录）。所以必须在
#  容器启动后、Node 进程拉起前，把目录补回来。
#
#  本脚本做的事（每次启动都跑，幂等）：
#    1. 确保 /app/data/{config,stream-cache,download} 三个子目录存在
#    2. 首次启动（config.json 不存在）从镜像模板拷贝默认配置
#    3. 修目录属主为 node 用户（UID 1000）
#    4. 切到 node 用户执行 node server/src/main.js
#
#  入口设计：
#    - 若以 root 启动（如某些 NAS 强制）：完成初始化后用 gosu 降权
#    - 若以 node 启动：直接执行（chown 跳过，因无权限也无必要）
# ================================================================
set -eu

APP_DIR="${APP_DIR:-/app}"
DATA_DIR="${DATA_DIR:-$APP_DIR/data}"
TEMPLATE_CONFIG="$APP_DIR/server/config/config.json"
TARGET_CONFIG="$DATA_DIR/config/config.json"

# ---------- 1. 确保数据子目录存在 ----------
mkdir -p \
  "$DATA_DIR/config" \
  "$DATA_DIR/stream-cache" \
  "$DATA_DIR/download" 2>/dev/null || {
    echo "[entrypoint] 无法创建数据子目录，检查挂载权限: $DATA_DIR" >&2
    exit 1
  }

# ---------- 2. 首次启动从模板拷贝 config.json ----------
# 已存在则不覆盖，保留用户自定义配置
if [ ! -f "$TARGET_CONFIG" ] && [ -f "$TEMPLATE_CONFIG" ]; then
  cp "$TEMPLATE_CONFIG" "$TARGET_CONFIG"
  echo "[entrypoint] 已从模板初始化: $TARGET_CONFIG"
fi

# ---------- 3. 修属主（仅 root 能 chown）----------
if [ "$(id -u)" = "0" ]; then
  chown -R node:node "$DATA_DIR" 2>/dev/null || true
  # 降权执行
  exec gosu node:node node "$APP_DIR/server/src/main.js" "$@"
else
  # 已是非 root，直接跑
  exec node "$APP_DIR/server/src/main.js" "$@"
fi
