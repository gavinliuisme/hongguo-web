# ================================================================
#  hongguo-web · Dockerfile
#
#  设计要点：
#   1. 基底 node:22-slim —— Debian 12 + Node 22 Iron LTS。
#      Debian 自带 libatomic.so.1，绕开原仓库 v1.1.7+ 打包 Node 26
#      在 Synology DSM 上 "libatomic.so.1: cannot open shared object file" 的坑。
#   2. 源码与数据分离：
#        /app/server/   镜像内置源码（不可挂载覆盖）
#        /app/data/     运行时数据根，单卷挂载点
#      通过环境变量把 config / stream-cache / download 全收进 /app/data/
#      → 宿主机只需挂一个目录到 /app/data/
#   3. 非 root 运行：node:22-slim 自带 UID 1000 的 node 用户，给挂载目录 chown。
#   4. 多架构：基础镜像 node:22-slim 在 amd64 与 arm64 都有官方构建，
#      buildx 一把过。
#   5. 不打包项目内自带的 node/ 运行时目录（容器用基础镜像里的 Node）。
# ================================================================

# build 参数：默认与 latest tag 一致；CI 用 --build-arg 覆盖
ARG NODE_VERSION=22-slim

# ---------- Stage 1: runtime ----------
FROM node:${NODE_VERSION}

# 容器内项目根
WORKDIR /app

# 时区设为 Asia/Shanghai（日志可读性，不影响业务逻辑）
# 数据根目录 /app/data：单卷挂载点，承载 config / stream-cache / download
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    # ---- 业务环境变量：覆盖 server/config/config.json 的默认值 ----
    # 监听所有网卡，否则容器外访问不到
    BIND_HOST=0.0.0.0 \
    # 端口与 EXPOSE 对齐
    PORT=8000 \
    # 容器内没有浏览器，关掉自动打开
    HG_OPEN_BROWSER=false \
    # ---- 数据路径全部收进 /app/data/，单卷挂载即可 ----
    # config.json 路径（覆盖默认的 server/config/config.json）
    HONGGUO_CONFIG=/app/data/config/config.json \
    # stream-cache 路径（覆盖默认的 server/stream-cache）
    HONGGUO_STREAM_CACHE=/app/data/stream-cache \
    # 下载目录路径（覆盖默认的 download）
    HONGGUO_DOWNLOAD_DIR=/app/data/download

# 显式装 libatomic1：Debian slim 不一定自带，确保即便将来换 Node 24/26
# 也不会再撞原仓库 v1.1.7+ 那个 libatomic.so.1 缺失的坑
RUN apt-get update && \
    apt-get install -y --no-install-recommends libatomic1 ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# 先复制 package.json（项目零依赖，但保留这一步以便将来加依赖时能利用层缓存）
COPY package.json ./

# 复制业务源码：server/ + web/。源码留在镜像内 /app/server/，不会被挂载覆盖
COPY server ./server
COPY web ./web

# 复制启动脚本（保留 start.sh / stop.sh 便于容器内排错；pack.sh 不需要）
COPY scripts/start.sh scripts/stop.sh ./scripts/

# 预创建数据目录，并交给 node 用户（UID 1000），避免挂载命名卷时权限拒绝
# 注意：不 mkdir /app/server/config 和 /app/server/stream-cache ——
#       源码里 server/config/ 已经带默认 config.json，那是模板，
#       真正生效的 config 走 HONGGUO_CONFIG 指向 /app/data/config/
RUN set -eux; \
    mkdir -p \
      /app/data/config \
      /app/data/stream-cache \
      /app/data/download; \
    # 首次启动若 /app/data/config/config.json 不存在，从源码模板拷一份
    if [ ! -f /app/data/config/config.json ]; then \
      cp server/config/config.json /app/data/config/config.json; \
    fi; \
    chown -R node:node /app; \
    chmod +x scripts/start.sh scripts/stop.sh

# 健康检查：检查 HTTP 端口能响应（不依赖具体路由，404 也算进程活着）
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD node -e "const http=require('http');const r=http.get({host:'127.0.0.1',port:Number(process.env.PORT||8000),path:'/',timeout:3000},x=>{process.exit(x.statusCode<500?0:1)}).on('error',()=>process.exit(1)).on('timeout',()=>{r.destroy();process.exit(1)});"

# 声明端口（与 PORT 环境变量保持一致；如改 PORT 同步改 EXPOSE 与 docker-compose）
EXPOSE 8000

# 单卷挂载点：所有运行时数据（config / stream-cache / download）都在这里
VOLUME ["/app/data"]

# 切到非 root
USER node

# 直接拉起 main.js，不经过 start.sh（start.sh 是为了找自带 Node 而存在，容器里不需要）
CMD ["node", "server/src/main.js"]
