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
#    3. 把 /app/server/config/apikeys.json 软链接到 /app/data/config/apikeys.json
#       （hongguo-web 代码把 apikeys.json 路径写死在 server/config/，不可配，
#        只能用软链接把数据重定向到挂载卷，实现持久化）
#    4. 修目录属主为 node 用户（UID 1000）
#    5. 切到 node 用户执行 node server/src/main.js
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
# hongguo-web 代码写死的 apikeys.json 路径（server/src/core/keystore.js 第 19 行）
HARDCODED_APIKEYS="$APP_DIR/server/config/apikeys.json"
# 实际持久化位置（在挂载卷里）
PERSIST_APIKEYS="$DATA_DIR/config/apikeys.json"

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

# ---------- 3. 把 apikeys.json 软链接到挂载卷 ----------
# hongguo-web 代码把 apikeys.json 写死在 server/config/apikeys.json，
# 没有环境变量可以重定向。这里用软链接把数据实际落到挂载卷，
# 这样容器重启后设备 ID 和链路密钥不会丢。
#
# 处理 4 种状态：
#   a) 软链接已存在且指向正确 → 无需操作
#   b) 真实文件存在（旧版本镜像可能写过） → 迁移到挂载卷后替换为软链接
#   c) 不存在 → 创建空文件占位，然后替换为软链接（让代码首次写入时落到挂载卷）
#   d) 软链接指向错误 → 删除重建
if [ -L "$HARDCODED_APIKEYS" ]; then
  # 已是软链接，检查指向
  CURRENT_TARGET="$(readlink "$HARDCODED_APIKEYS" 2>/dev/null || true)"
  if [ "$CURRENT_TARGET" = "$PERSIST_APIKEYS" ]; then
    echo "[entrypoint] apikeys.json 软链接已就绪: $HARDCODED_APIKEYS -> $PERSIST_APIKEYS"
  else
    echo "[entrypoint] apikeys.json 软链接指向错误（$CURRENT_TARGET），重建"
    rm -f "$HARDCODED_APIKEYS"
    ln -s "$PERSIST_APIKEYS" "$HARDCODED_APIKEYS"
  fi
elif [ -f "$HARDCODED_APIKEYS" ]; then
  # 真实文件存在（旧版本镜像写过），迁移到挂载卷
  echo "[entrypoint] 检测到旧 apikeys.json，迁移到挂载卷: $PERSIST_APIKEYS"
  cp "$HARDCODED_APIKEYS" "$PERSIST_APIKEYS"
  rm -f "$HARDCODED_APIKEYS"
  ln -s "$PERSIST_APIKEYS" "$HARDCODED_APIKEYS"
else
  # 不存在，创建软链接（目标文件可不存在，代码首次写入时会创建）
  echo "[entrypoint] 创建 apikeys.json 软链接: $HARDCODED_APIKEYS -> $PERSIST_APIKEYS"
  ln -s "$PERSIST_APIKEYS" "$HARDCODED_APIKEYS"
fi

# ---------- 4. 修属主（仅 root 能 chown）----------
if [ "$(id -u)" = "0" ]; then
  chown -R node:node "$DATA_DIR" 2>/dev/null || true
  # 软链接本身的属主也修一下
  chown -h node:node "$HARDCODED_APIKEYS" 2>/dev/null || true
  # 降权执行
  exec gosu node:node node "$APP_DIR/server/src/main.js" "$@"
else
  # 已是非 root，直接跑
  exec node "$APP_DIR/server/src/main.js" "$@"
fi
