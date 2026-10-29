#!/usr/bin/env bash
# ================================================================
#  打包红果短剧 Web 项目
#
#  为每个目标平台产出一个独立 zip，内含该平台对应的 Node 运行时，
#  解压即用、目标机无需预装 Node。
#
#  产物（版本号取自 package.json 的 version 字段）：
#    红果web-<版本>-Windows-x64.zip     仅 .bat 脚本
#    红果web-<版本>-Windows-arm.zip     仅 .bat 脚本
#    红果web-<版本>-Linux-x64.zip       仅 .sh 脚本
#    红果web-<版本>-Linux-arm.zip       仅 .sh 脚本
#    红果web-<版本>-macOS-arm.zip       仅 .sh 脚本
#
#  用法：
#    bash scripts/pack.sh              打包全部 5 个平台
#    bash scripts/pack.sh Linux-arm    只打指定平台
#    bash scripts/pack.sh --list       列出可用平台名
#
#  Node 运行时来自 nodejs.org/dist，打包时按平台下载并缓存到
#  .pack-cache/。首次打包需联网，之后同一平台直接命中缓存。
#  可用 HG_NODE_VERSION 固定版本号，缺省取当前最新 LTS。
# ================================================================
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$SRC/.pack-cache"
# 产物落在仓库根的 dist/。放在仓库内而不是 /workspace，是因为打包脚本
# 可能从任意目录被调用，锚定到 SRC 才能保证「谁跑都写到同一个地方」。
# 仍可用 HG_PACK_OUTDIR 覆盖（CI 里常用临时目录）。
OUTDIR="${HG_PACK_OUTDIR:-$SRC/dist}"

# ---------- 平台定义 ----------
# key       包名用的标识   nodejs.org/dist 的 os/arch
ALL_PLATFORMS=(Windows-x64 Windows-arm Linux-x64 Linux-arm macOS-arm)

platform_os() {
  case "$1" in
    Windows-*) echo "win" ;;
    Linux-*)   echo "linux" ;;
    macOS-*)   echo "darwin" ;;
  esac
}

platform_arch() {
  case "$1" in
    *-x64)  echo "x64" ;;
    *-arm)  echo "arm64" ;;
  esac
}

platform_family() {
  case "$1" in
    Windows-*) echo "bat" ;;
    *)echo "sh" ;;
  esac
}

usage() {
  sed -n '2,23p' "${BASH_SOURCE[0]}" | sed 's/^#\{0,1\} \{0,1\}//'
}

case "${1:-}" in
  --list)
    printf '%s\n' "${ALL_PLATFORMS[@]}"
    exit 0
    ;;
  -h|--help)
    usage
    exit 0
    ;;
esac

TARGETS=("$@")
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=("${ALL_PLATFORMS[@]}")

for t in "${TARGETS[@]}"; do
  if ! printf '%s\n' "${ALL_PLATFORMS[@]}" | grep -Fxq "$t"; then
    echo "[错误] 未知平台: $t" >&2
    echo "       可用: ${ALL_PLATFORMS[*]}" >&2
    exit 1
  fi
done

# ---------- 版本号：单一数据源 ----------
# package.json 的 version 字段。head -1 是保平安：scripts.version 那行
# 也含 "version": "..." 字样，但真版本号在前，第一个匹配即所求。
VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  "$SRC/package.json" | head -1)"
if [ -z "$VERSION" ]; then
  echo "[错误]未能从 package.json 读到 version字段。" >&2
  exit 1
fi

# 包名用 ASCII，不含中文。
#
# 「红果web-1.0.1-….zip」这个名字在 GitHub 上传后，asset 名会变成
# 「web-1.0.1-….zip」—— 非 ASCII 前缀被服务端丢掉。这不是 gh 的问题：
# 实测 gh release create、gh release upload、以及直接调 REST API 并把
# 中文名 URL 编码后传给 ?name=，三种方式返回的 asset 名都是被剥掉前缀的
# 版本。客户端绕不过去，所以只能在源头用 ASCII 名。
#
# 中文标识没有丢：Release 标题（红果web 1.0.1）与包内「平台说明.txt」
# 都还带着。
PKG_ROOT="hongguo-web-$VERSION"

echo "版本: $VERSION   (来自 package.json)"
echo "输出: $OUTDIR"
echo

# 输出目录可能还不存在（默认 dist/ 首次打包时才创建）
mkdir -p "$OUTDIR" || {
  echo "[错误] 无法创建输出目录：$OUTDIR" >&2
  exit 1
}

# ---------- 公共树：复制一次，各平台共用 ----------
echo "[1/3] 准备公共文件树 ..."
WORK="$(mktemp -d)"
COMMON="$WORK/common"
mkdir -p "$COMMON"
trap 'rm -rf "$WORK"' EXIT

tar -C "$SRC" -cf - \
  --exclude='./server/stream-cache' \
  --exclude='./server/config/apikeys.json' \
  --exclude='./.git' \
  --exclude='./node' \
  --exclude='./.pack-cache' \
  --exclude='./dist' \
  --exclude='./screenshot' \
  --exclude='*.log' \
  --exclude='.DS_Store' \
  --exclude='__pycache__' \
  --exclude='*.pyc' \
  . | tar -C "$COMMON" -xf -

# 运行时目录：空目录不会被 git/tar 保留，需显式重建
mkdir -p "$COMMON/server/stream-cache"
cat > "$COMMON/server/stream-cache/.gitkeep" <<'EOF'
解密成品缓存目录。首次启动会自动签发
server/config/apikeys.json，设备标识（device_id）与链路密钥
同存于该文件（属长期状态，归入 config 目录）。
EOF

# ---------- Node 运行时下载与缓存 ----------
mkdir -p "$CACHE"

# resolve_node_version -> 版本号（无 v 前缀）
resolve_node_version() {
  if [ -n "${HG_NODE_VERSION:-}" ]; then
    echo "${HG_NODE_VERSION#v}"
    return 0
  fi
  curl -fsSL --max-time 60 https://nodejs.org/dist/index.json | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit('nodejs.org index.json 不是合法 JSON')
for e in d:
    if e.get('lts'):
        sys.stdout.write(e['version'].lstrip('v'))
        break
else:
    sys.exit('index.json 里找不到 LTS 版本')
"
}

# fetch_node <os> <arch>
#   成功：把 Node 运行时目录写入全局 NODE_DIR
#   失败：返回非 0，诊断信息只走 stderr
NODE_DIR=""
fetch_node() {
  local os="$1" arch="$2" ver="$3"
  local cached="$CACHE/node-v${ver}-${os}-${arch}"

  if [ -d "$cached" ]; then
    NODE_DIR="$cached"
    return 0
  fi

  # Windows 的 Node 发行包是 .zip，Linux/macOS 是 .tar.gz。
  local ext="tar.gz"
  [ "$os" = "win" ] && ext="zip"
  local name="node-v${ver}-${os}-${arch}.${ext}"
  local link="https://nodejs.org/dist/v${ver}/${name}"
  local tgz="$CACHE/${name}"
  local tmp="$tgz.part"

  echo "      下载 Node 运行时: ${os}/${arch} v${ver}" >&2

  # 下载策略：静默重试 + 从零重来，配合 nodejs.org 的 SHASUMS 校验。
  local attempt
  for attempt in 1 2 3; do
    rm -f "$tmp"
    if curl -fsSL \
         --retry 5 --retry-delay 3 --retry-all-errors \
         --connect-timeout 20 --max-time 480 \
         -o "$tmp" "$link"; then
      break
    fi
    echo "      第 $attempt 次下载中断，重试..." >&2
  done

  if [ ! -s "$tmp" ]; then
    rm -f "$tmp"
    echo "[错误] Node 运行时下载失败：${os}/${arch}" >&2
    echo "       $link" >&2
    return 1
  fi

  # 用官方 SHASUMS256.txt 校验完整性（防截断/损坏）。
  local want got
  want="$(curl -fsSL --max-time 60 "https://nodejs.org/dist/v${ver}/SHASUMS256.txt" \
    | awk -v n="$name" '$2 == n { print $1; exit }' || true)"
  if [ -n "$want" ]; then
    got="$(sha256sum "$tmp" | awk '{print $1}')"
    if [ "$got" != "$want" ]; then
      rm -f "$tmp"
      echo "[错误] Node 运行时校验失败：${os}/${arch}（sha256 不符）" >&2
      return 1
    fi
  fi

  mv "$tmp" "$tgz"

  # 定位 Node 根：找到「含有 bin/node（Unix）或 node.exe（Windows）的那一层」，
  # 把它的内容搬进 cached/。
  local unpack="$WORK/unpack-$os-$arch"
  rm -rf "$unpack"
  mkdir -p "$unpack"
  if [ "$ext" = "zip" ]; then
    if ! unzip -q "$tgz" -d "$unpack"; then
      rm -rf "$cached" "$unpack" "$tgz"
      echo "[错误] Node 运行时解压失败：${os}/${arch}（压缩包可能已损坏）" >&2
      return 1
    fi
  else
    if ! tar -xzf "$tgz" -C "$unpack"; then
      rm -rf "$cached" "$unpack" "$tgz"
      echo "[错误] Node 运行时解压失败：${os}/${arch}（压缩包可能已损坏）" >&2
      return 1
    fi
  fi

  local launcher root
  if [ "$os" = "win" ]; then
    launcher="$(find "$unpack" -maxdepth 2 -type f -name node.exe -print -quit 2>/dev/null)"
  else
    launcher="$(find "$unpack" -type f -name node -path '*/bin/*' -print -quit 2>/dev/null)"
  fi
  if [ -z "$launcher" ]; then
    rm -rf "$cached" "$unpack" "$tgz"
    echo "[错误] 压缩包里找不到 Node 入口：${os}/${arch}" >&2
    return 1
  fi
  if [ "$os" = "win" ]; then
    root="$(dirname "$launcher")"
  else
    root="$(dirname "$(dirname "$launcher")")"
  fi

  cp -r "$root/." "$cached/"
  rm -rf "$unpack"

  NODE_DIR="$cached"
}

# 校验解压结果：Unix 必须有 bin/node，Windows 必须有 node.exe。
verify_node() {
  local d="$1" os="$2"
  local launcher="bin/node"
  [ "$os" = "win" ] && launcher="node.exe"
  if [ ! -e "$d/$launcher" ]; then
    echo "[错误] Node 运行时内容不完整（缺少 $launcher）：$d" >&2
    return 1
  fi
}

# ---------- 逐平台打包 ----------
NODE_VERSION="$(resolve_node_version)"
echo "[2/3] 下载并打包 Node 运行时 v$NODE_VERSION ..."
BUILT=()

for plat in "${TARGETS[@]}"; do
  os="$(platform_os "$plat")"
  arch="$(platform_arch "$plat")"
  family="$(platform_family "$plat")"
  out="$OUTDIR/${PKG_ROOT}-${plat}.zip"

  printf '  %-14s -> %s\n' "$plat" "$(basename "$out")"

  fetch_node "$os" "$arch" "$NODE_VERSION" || exit 1
  verify_node "$NODE_DIR" "$os" || exit 1

  # 暂存目录必须放在 COMMON 之外：放在里面会让 cp -r 把目录复制进自身
  stage="$WORK/stage-$plat"
  mkdir -p "$stage"
  cp -r "$COMMON/." "$stage/"

  # ---- 放入 Node 运行时 ----
  cp -r "$NODE_DIR" "$stage/node"

  # ---- 平台专属脚本：只留同族 ----
  # 只带 start/stop：源码已在公共树里，构建/打包脚本本身不必随发行包
  # 分发 —— 使用发行包的人不需要额外工具集，也用不到它们。
  rm -f "$stage"/scripts/*.sh "$stage"/scripts/*.bat
  if [ "$family" = "bat" ]; then
    cp "$SRC/scripts/start.bat" "$SRC/scripts/stop.bat" "$stage/scripts/"
  else
    cp "$SRC/scripts/start.sh" "$SRC/scripts/stop.sh" "$stage/scripts/"
    chmod +x "$stage"/scripts/*.sh
  fi

  # ---- 平台说明 ----
  cat > "$stage/平台说明.txt" <<EOF
红果web $VERSION  ·  $plat

启动：
$( [ "$family" = "bat" ] \
   && echo "  双击 scripts\\start.bat" \
   || echo "  bash scripts/start.sh" )

Node 运行时：已内置 Node.js v$NODE_VERSION（$os/$arch），无需另行安装。
如需查看生效配置：$([ "$family" = "bat" ] && echo "node server\\src\\main.js --config" || echo "node server/src/main.js --config")
版本      ：$VERSION
EOF

  ( cd "$stage" && zip -qr9 "$out" . -x '.*' )
  rm -rf "$stage"
  BUILT+=("$out")
done

# ---------- 校验 ----------
echo "[3/3] 校验产物 ..."
for out in "${BUILT[@]}"; do
  echo
  echo "  $(basename "$out")  ($(du -h "$out" | cut -f1))"

  if unzip -Z1 "$out" >/dev/null 2>&1; then
    LIST="$(unzip -Z1 "$out")"
  else
    LIST="$(unzip -l "$out" | awk 'NF>=4 {print $NF}')"
  fi

  check() {
    if grep -Fxq "$1" <<<"$LIST"; then
      echo "    ✅ $1"
    else
      echo "    ❌ 缺失 $1"
      FAILED=1
    fi
  }

  # 每个包都该有的
  for f in server/config/config.json \
           server/src/main.js \
           web/index.html \
           平台说明.txt; do
    check "$f"
  done

  # Node 主程序：按平台
  grep -Eq '(^|/)node/bin/node$|(^|/)node/node\.exe$' <<<"$LIST" \
    && echo "    ✅ node/bin/node 或 node/node.exe" \
    || { echo "    ❌ 缺失 Node 主程序"; FAILED=1; }

  # 启动脚本互斥：只看 scripts/ 目录，不扫描整个包。
  # 内置 Node 运行时自带的 npm 也含跨平台脚本（如 Linux 包的 gyp.bat、
  # Windows 包的 completion.sh），但那些不是我们的启动脚本，不该由本项判定。
  plat="$(basename "$out" .zip | sed "s/^${PKG_ROOT}-//")"
  if [ "$(platform_family "$plat")" = "bat" ]; then
    if grep -qE '(^|/)scripts/.*\.sh$' <<<"$LIST"; then
      echo "    ❌ scripts/ 下混入了 .sh 脚本："
      grep -E '(^|/)scripts/.*\.sh$' <<<"$LIST" | sed 's/^/       /'
      FAILED=1
    else
      echo "    ✅ scripts/ 仅含 .bat 脚本"
    fi
  else
    if grep -qE '(^|/)scripts/.*\.bat$' <<<"$LIST"; then
      echo "    ❌ scripts/ 下混入了 .bat 脚本："
      grep -E '(^|/)scripts/.*\.bat$' <<<"$LIST" | sed 's/^/       /'
      FAILED=1
    else
      echo "    ✅ scripts/ 仅含 .sh 脚本"
    fi
  fi
done

echo
if [ "${FAILED:-0}" = 1 ]; then
  echo "打包完成，但有校验未通过项，请检查上面的 ❌。"
  exit 1
fi
echo "全部打包完成。"
