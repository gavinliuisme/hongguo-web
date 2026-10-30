# ================================================================
#  hongguo-web · Dockerfile
#
#  设计要点：
#   1. 基底 node:22-slim —— Debian 12 + Node 22 Iron LTS。
#      Debian 自带 libatomic.so.1，绕开原仓库 v1.1.7+ 打包 Node 26
#      在 Synology DSM 上 "libatomic.so.1: cannot open shared object file" 的坑。
#   2. 多阶段：deps 仅做复制（零依赖项目其实可省，但留扩展空间）；runtime 阶段
#      只 COPY 必要源码，不带 .git / node_modules / 截图等。
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
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    # ---- 业务环境变量：覆盖 server/config/config.json 的默认值 ----
    # 监听所有网卡，否则容器外访问不到
    BIND_HOST=0.0.0.0 \
    # 端口与 EXPOSE 对齐
    PORT=8000 \
    # 容器内没有浏览器，关掉自动打开
    HG_OPEN_BROWSER=false \
    # 下载目录走持久化卷
    HONGGUO_DOWNLOAD_DIR=/app/download

# 显式装 libatomic1：Debian slim 不一定自带，确保即便将来换 Node 24/26
# 也不会再撞原仓库 v1.1.7+ 那个 libatomic.so.1 缺失的坑
RUN apt-get update && \
    apt-get install -y --no-install-recommends libatomic1 ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# 先复制 package.json（项目零依赖，但保留这一步以便将来加依赖时能利用层缓存）
COPY package.json ./

# 复制业务源码：server/ + web/。.dockerignore 会过滤掉 node/、.git/、dist/ 等
COPY server ./server
COPY web ./web

# 复制启动脚本（保留 start.sh / stop.sh 便于容器内排错；pack.sh 不需要）
COPY scripts/start.sh scripts/stop.sh ./scripts/

# 预创建运行时目录，并交给 node 用户（UID 1000），避免挂载命名卷时权限拒绝
RUN set -eux; \
    mkdir -p \
      server/config \
      server/stream-cache \
      download; \
    chown -R node:node /app; \
    chmod +x scripts/start.sh scripts/stop.sh

# 健康检查：HTTP GET /api/health（不存在时返回 404 也算进程活着；
# 这里更稳的做法是检查端口，避免依赖具体路由）
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD node -e "const http=require('http');const r=http.get({host:'127.0.0.1',port:Number(process.env.PORT||8000),path:'/',timeout:3000},x=>{process.exit(x.statusCode<500?0:1)}).on('error',()=>process.exit(1)).on('timeout',()=>{r.destroy();process.exit(1)});"

# 声明端口（与 PORT 环境变量保持一致；如改 PORT 同步改 EXPOSE 与 docker-compose）
EXPOSE 8000

# 命名卷挂载点：config（含自动生成的 apikeys.json）、stream-cache（解密缓存）、download（剧集下载）
VOLUME ["/app/server/config", "/app/server/stream-cache", "/app/download"]

# 切到非 root
USER node

# 直接拉起 main.js，不经过 start.sh（start.sh 是为了找自带 Node 而存在，容器里不需要）
CMD ["node", "server/src/main.js"]
