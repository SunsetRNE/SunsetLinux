#!/usr/bin/env bash
# ============================================================================
# tools/seed/mkseed.sh —— 制作 sunsetlinux **离线种子包**
# ============================================================================
# 目的（对应用户痛点"首次部署慢"）：把首次部署必须联网下载的大件一次性打包，
# 新设备拿到一个 .tar.zst 就能离线起步，不必在手机上等慢速网络。
#
# 种子包内容（顶层目录 sunsetlinux-seed-<日期>/）：
#   ubuntu-base-24.04.3-base-arm64.tar.gz   # Ubuntu 24.04.3 base rootfs（官方）
#   node-v<ver>-linux-arm64.tar.xz          # Node 24 LTS 官方 arm64 静态包
#   debs/*.deb                              # 可选：apt 包缓存（--deb-dir）
#   MANIFEST                                # sha256sum 格式，verify-seed.sh 直接消费
#   MANIFEST.json                           # 同上 + 每个文件大小 + 来源 URL
#   SOURCES.txt                             # 上游 URL 与校验依据（可追溯）
#   README.md                               # 给人看的离线部署说明
#
# 可重复性：所有下载都做 sha256 校验（对照上游 SHA256SUMS / SHASUMS256.txt），
# 校验不过立刻失败 —— 我们不允许把来路不明的 tarball 塞进种子包。
#
# 体积参考（arm64）：ubuntu-base ≈ 28.5 MB，node ≈ 29.4 MB，合计 ≈ 58 MB，
#   zstd -19 之后约 50 MB 量级（已经是压缩过的 tarball，收益有限）。
#
# 前置依赖：bash 4+、curl（或 wget）、tar、sha256sum、node ≥ 20（无系统 zstd 时压缩用）
# 本脚本**不需要 root**，可以在设备侧（proot 内）或宿主/CI 上跑。
# ============================================================================
set -euo pipefail

# ---------------------------------------------------------------- 常量 ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

UBUNTU_SERIES="24.04.3"                     # 与 findings.md §4 一致（已验证 sha256）
UBUNTU_BASE_NAME="ubuntu-base-${UBUNTU_SERIES}-base-arm64.tar.gz"
UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/${UBUNTU_SERIES}/release/${UBUNTU_BASE_NAME}"
UBUNTU_SUMS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/${UBUNTU_SERIES}/release/SHA256SUMS"

NODE_MAJOR="24"                             # Node 24 LTS（代号 Krypton）
NODE_FALLBACK_VERSION="v24.21.0"            # 无网时使用的已知版本（2026-09-07 发布）
NODE_DIST_BASE="https://nodejs.org/dist"

OUT_DIR="$REPO_ROOT/dist"
CACHE_DIR=""                                # 默认 <out-dir>/.seed-cache
DATE_TAG="$(date -u +%Y-%m-%d)"
ZSTD_LEVEL=19
DEB_DIR=""
DEB_LIST=""
UBUNTU_FILE=""                              # --ubuntu-base-file：复用已下载的 tarball
NODE_FILE=""                                # --node-file：复用已下载的 node tarball
OFFLINE=0
KEEP_WORK=0
RUN_VERIFY=0
NODE_VERSION=""

# ---------------------------------------------------------------- 输出 ----
if [ -t 2 ]; then C_E=$'\033[31m'; C_I=$'\033[36m'; C_K=$'\033[32m'; C_W=$'\033[33m'; C_R=$'\033[0m'
else C_E=''; C_I=''; C_K=''; C_W=''; C_R=''; fi
die()  { printf '%s[错误]%s %s\n' "$C_E" "$C_R" "$*" >&2; exit 1; }
info() { printf '%s[信息]%s %s\n' "$C_I" "$C_R" "$*" >&2; }
ok()   { printf '%s[完成]%s %s\n' "$C_K" "$C_R" "$*" >&2; }
warn() { printf '%s[警告]%s %s\n' "$C_W" "$C_R" "$*" >&2; }
step() { printf '\n%s==>%s %s\n' "$C_I" "$C_R" "$*" >&2; }

usage() {
  cat <<'EOF'
用法：tools/seed/mkseed.sh [选项]

制作 sunsetlinux 离线种子包（ubuntu-base rootfs + Node 24 arm64 静态包 [+ deb 缓存]），
输出 dist/sunsetlinux-seed-<日期>.tar.zst 与同名 .sha256。

选项：
  --out-dir <目录>       产物目录（默认：<仓库>/dist）
  --date <YYYY-MM-DD>    覆盖包名里的日期（默认：UTC 当天）
  --cache-dir <目录>     下载缓存目录（默认：<out-dir>/.seed-cache；保留可用于重打包）
  --node-version <vX.Y.Z> 指定 Node 版本（默认：联网查 v24 最新 LTS，查不到用 v24.21.0）
  --ubuntu-base-file <文件>  复用已有的 ubuntu-base tarball（免下载，仍会校验 sha256）
  --node-file <文件>         复用已有的 node tarball（免下载，仍会校验 sha256）
  --deb-dir <目录>       把该目录下所有 *.deb 收进种子包（seeds/debs/），可重复
  --deb-list <文件>      按行给出包名，用 apt-get download 抓取（仅宿主/CI 可用）
  --zstd-level <1-22>    压缩级别（默认 19）
  --offline              完全离线：只用缓存/本地文件，不发起任何网络请求
  --keep-work            保留中间工作目录（排错用）
  --verify               打包完成后立刻跑 verify-seed.sh
  -h, --help             显示本帮助

举例：
  # 设备侧自带 ubuntu-base（build/ubuntu-base.tar.gz），只下 Node
  tools/seed/mkseed.sh --ubuntu-base-file build/ubuntu-base.tar.gz

  # 宿主/CI 上带 deb 缓存
  tools/seed/mkseed.sh --deb-dir /var/cache/apt/archives --verify
EOF
}

# ------------------------------------------------------------ 参数解析 ----
while [ $# -gt 0 ]; do
  case "$1" in
    --out-dir)          OUT_DIR="${2:?--out-dir 需要取值}"; shift 2 ;;
    --date)             DATE_TAG="${2:?--date 需要取值}"; shift 2 ;;
    --cache-dir)        CACHE_DIR="${2:?--cache-dir 需要取值}"; shift 2 ;;
    --node-version)     NODE_VERSION="${2:?--node-version 需要取值}"; shift 2 ;;
    --ubuntu-base-file) UBUNTU_FILE="${2:?}"; shift 2 ;;
    --node-file)        NODE_FILE="${2:?}"; shift 2 ;;
    --deb-dir)          DEB_DIR="${DEB_DIR}${DEB_DIR:+:}${2:?}"; shift 2 ;;
    --deb-list)         DEB_LIST="${2:?}"; shift 2 ;;
    --zstd-level)       ZSTD_LEVEL="${2:?}"; shift 2 ;;
    --offline)          OFFLINE=1; shift ;;
    --keep-work)        KEEP_WORK=1; shift ;;
    --verify)           RUN_VERIFY=1; shift ;;
    -h|--help)          usage; exit 0 ;;
    *)                  die "未知参数：$1（用 --help 查看用法）" ;;
  esac
done

[ -n "$CACHE_DIR" ] || CACHE_DIR="$OUT_DIR/.seed-cache"
[[ "$DATE_TAG" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "--date 格式应为 YYYY-MM-DD，实际：$DATE_TAG"
[[ "$ZSTD_LEVEL" =~ ^[0-9]+$ ]] && [ "$ZSTD_LEVEL" -ge 1 ] && [ "$ZSTD_LEVEL" -le 22 ] \
  || die "--zstd-level 必须是 1–22 的整数"

# ---------------------------------------------------------- 依赖检查 ----
need() { command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1（$2）"; }
need tar "解包/打包"
need sha256sum "计算与校验 sha256"
HAS_CURL=0; HAS_WGET=0
command -v curl >/dev/null 2>&1 && HAS_CURL=1
command -v wget >/dev/null 2>&1 && HAS_WGET=1
if [ "$OFFLINE" -eq 0 ] && [ "$HAS_CURL" -eq 0 ] && [ "$HAS_WGET" -eq 0 ]; then
  die "需要 curl 或 wget 之一下载文件（离线打包请加 --offline）"
fi

# zstd：优先系统命令，其次 Node 内置实现
ZSTD_CMD=""
if command -v zstd >/dev/null 2>&1; then
  ZSTD_CMD="zstd -${ZSTD_LEVEL} -T0 -q -c"
elif command -v node >/dev/null 2>&1; then
  ZSTD_CMD="node --no-warnings $SCRIPT_DIR/zstd-filter.mjs -l ${ZSTD_LEVEL}"
  info "系统没有 zstd 命令，改用 Node 内置 zstd 实现（tools/seed/zstd-filter.mjs）"
else
  die "既没有 zstd 命令，也没有 node —— 无法生成 .tar.zst。请安装 zstd 或 Node ≥ 20。"
fi
UNZSTD_CMD="zstd -d -q -c"
if ! command -v zstd >/dev/null 2>&1; then
  UNZSTD_CMD="node --no-warnings $SCRIPT_DIR/zstd-filter.mjs -d"
fi

# ------------------------------------------------------------ 下载工具 ----
fetch() { # fetch <url> <输出文件>
  local url="$1" out="$2"
  if [ -f "$out" ]; then
    info "已存在缓存：$(basename "$out")"
    return 0
  fi
  [ "$OFFLINE" -eq 0 ] || die "离线模式下缺少缓存文件：$out（URL：$url）"
  info "下载 $url"
  mkdir -p "$(dirname "$out")"
  if [ "$HAS_CURL" -eq 1 ]; then
    # -C - 断点续传：手机上网络抖动很常见，续传比从头再来划算
    curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -C - -o "$out.part" "$url" \
      || die "下载失败：$url（网络不可用？可先用 --offline + 缓存重试）"
  else
    wget -q --tries=3 --timeout=20 -c -O "$out.part" "$url" || die "下载失败：$url"
  fi
  mv "$out.part" "$out"
}

sha_of() { sha256sum "$1" | awk '{print $1}'; }

# 从上游校验文件里取出指定文件名的官方 sha256（校验依据，可追溯）
expected_from_sums() { # expected_from_sums <sums 文件> <文件名>
  awk -v f="$2" '
    { name=$2; sub(/^\*/, "", name); if (name == f) { print $1; exit } }
  ' "$1"
}

mkdir -p "$OUT_DIR" "$CACHE_DIR"

# ------------------------------------------------------ 1. 确定版本号 ----
step "确定 Node ${NODE_MAJOR} LTS 版本号"
if [ -z "$NODE_VERSION" ]; then
  NODE_VERSION="$NODE_FALLBACK_VERSION"
  if [ "$OFFLINE" -eq 0 ]; then
    tmp_idx="$CACHE_DIR/nodejs-dist-index.json"
    if [ ! -f "$tmp_idx" ]; then
      if [ "$HAS_CURL" -eq 1 ]; then
        curl -fsL --max-time 30 -o "$tmp_idx.part" "$NODE_DIST_BASE/index.json" \
          && mv "$tmp_idx.part" "$tmp_idx" || rm -f "$tmp_idx.part"
      elif [ "$HAS_WGET" -eq 1 ]; then
        wget -q -T 30 -O "$tmp_idx.part" "$NODE_DIST_BASE/index.json" \
          && mv "$tmp_idx.part" "$tmp_idx" || rm -f "$tmp_idx.part"
      fi
    fi
    if [ -f "$tmp_idx" ] && command -v node >/dev/null 2>&1; then
      NODE_VERSION="$(node -e '
        const fs=require("node:fs");
        try{
          const j=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
          const v=j.filter(x=>x.version.startsWith("v'"$NODE_MAJOR"'.")&&x.lts)
                   .sort((a,b)=>a.version.localeCompare(b.version,undefined,{numeric:true}));
          process.stdout.write(v.length?v[v.length-1].version:"");
        }catch{process.stdout.write("")}
      ' "$tmp_idx")"
      if [ -z "$NODE_VERSION" ]; then
        warn "从 index.json 里没解析出 v${NODE_MAJOR} 的 LTS 版本，回退到 $NODE_FALLBACK_VERSION"
        NODE_VERSION="$NODE_FALLBACK_VERSION"
      fi
    else
      warn "拿不到 nodejs.org/dist/index.json，使用已知版本 $NODE_FALLBACK_VERSION"
    fi
  else
    warn "离线模式：使用已知版本 $NODE_FALLBACK_VERSION"
  fi
fi
case "$NODE_VERSION" in v*) ;; *) NODE_VERSION="v$NODE_VERSION" ;; esac
NODE_TARBALL="node-${NODE_VERSION}-linux-arm64.tar.xz"
info "选用 Node 版本：$NODE_VERSION（文件 $NODE_TARBALL）"

# ------------------------------------------------------ 2. 取校验依据 ----
step "获取上游校验依据"
UBUNTU_SUMS="$CACHE_DIR/SHA256SUMS.ubuntu-base-${UBUNTU_SERIES}"
NODE_SUMS="$CACHE_DIR/SHASUMS256.txt.${NODE_VERSION}"
if [ "$OFFLINE" -eq 0 ]; then
  fetch "$UBUNTU_SUMS_URL" "$UBUNTU_SUMS" || warn "取不到 Ubuntu SHA256SUMS，将跳过比对"
  fetch "$NODE_DIST_BASE/${NODE_VERSION}/SHASUMS256.txt" "$NODE_SUMS" || warn "取不到 Node SHASUMS256.txt，将跳过比对"
else
  info "离线模式：仅使用缓存中的校验文件（如有）"
fi
# 先置空：后面多处引用这两个变量，而 set -u 下未赋值会直接退出
UB_EXPECT=""
NODE_EXPECT=""

# ------------------------------------------------------ 3. 准备大文件 ----
WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sunsetlinux-seed.XXXXXX")"
cleanup() {
  if [ "$KEEP_WORK" -eq 1 ]; then
    warn "保留工作目录：$WORK_ROOT"
  else
    rm -rf "$WORK_ROOT"
  fi
}
trap cleanup EXIT

SEED_DIR_NAME="sunsetlinux-seed-${DATE_TAG}"
STAGE="$WORK_ROOT/stage"
SEED_ROOT="$STAGE/$SEED_DIR_NAME"
mkdir -p "$SEED_ROOT/debs"

step "收集 ubuntu-base rootfs"
if [ -n "$UBUNTU_FILE" ]; then
  [ -f "$UBUNTU_FILE" ] || die "--ubuntu-base-file 指向的文件不存在：$UBUNTU_FILE"
  cp -f "$UBUNTU_FILE" "$SEED_ROOT/$UBUNTU_BASE_NAME"
else
  fetch "$UBUNTU_BASE_URL" "$CACHE_DIR/$UBUNTU_BASE_NAME"
  cp -f "$CACHE_DIR/$UBUNTU_BASE_NAME" "$SEED_ROOT/$UBUNTU_BASE_NAME"
fi
UB_SHA="$(sha_of "$SEED_ROOT/$UBUNTU_BASE_NAME")"
if [ -f "$UBUNTU_SUMS" ]; then
  UB_EXPECT="$(expected_from_sums "$UBUNTU_SUMS" "$UBUNTU_BASE_NAME")"
  if [ -z "$UB_EXPECT" ]; then
    warn "上游 SHA256SUMS 里没有 $UBUNTU_BASE_NAME，跳过比对"
  elif [ "$UB_EXPECT" != "$UB_SHA" ]; then
    die "ubuntu-base 校验失败！期望 $UB_EXPECT，实际 $UB_SHA —— 不要使用这个文件"
  else
    ok "ubuntu-base sha256 与上游一致：$UB_SHA"
  fi
else
  warn "没有上游校验文件，仅记录本地 sha256：$UB_SHA"
fi

step "收集 Node ${NODE_VERSION} arm64 静态包"
if [ -n "$NODE_FILE" ]; then
  [ -f "$NODE_FILE" ] || die "--node-file 指向的文件不存在：$NODE_FILE"
  cp -f "$NODE_FILE" "$SEED_ROOT/$NODE_TARBALL"
else
  fetch "$NODE_DIST_BASE/${NODE_VERSION}/${NODE_TARBALL}" "$CACHE_DIR/$NODE_TARBALL"
  cp -f "$CACHE_DIR/$NODE_TARBALL" "$SEED_ROOT/$NODE_TARBALL"
fi
NODE_SHA="$(sha_of "$SEED_ROOT/$NODE_TARBALL")"
if [ -f "$NODE_SUMS" ]; then
  NODE_EXPECT="$(expected_from_sums "$NODE_SUMS" "$NODE_TARBALL")"
  if [ -z "$NODE_EXPECT" ]; then
    warn "上游 SHASUMS256.txt 里没有 $NODE_TARBALL，跳过比对"
  elif [ "$NODE_EXPECT" != "$NODE_SHA" ]; then
    die "Node 包校验失败！期望 $NODE_EXPECT，实际 $NODE_SHA —— 不要使用这个文件"
  else
    ok "Node 包 sha256 与上游一致：$NODE_SHA"
    # 上游提供 GPG 分离签名：有 gpg + 公钥时可以做更强的校验（可选）
    if [ "$OFFLINE" -eq 0 ] && command -v gpg >/dev/null 2>&1; then
      info "（可选）如需 GPG 级校验：gpg --verify SHASUMS256.txt.sig SHASUMS256.txt，公钥见 nodejs.org 的 release keys"
    fi
  fi
else
  warn "没有上游校验文件，仅记录本地 sha256：$NODE_SHA"
fi

# ---------------------------------------------------------- 4. deb 缓存 ----
if [ -n "$DEB_DIR" ]; then
  step "收集 deb 缓存"
  IFS=':' read -r -a _debdirs <<< "$DEB_DIR"
  found=0
  for d in "${_debdirs[@]}"; do
    [ -d "$d" ] || die "--deb-dir 不是目录：$d"
    while IFS= read -r -d '' f; do
      cp -f "$f" "$SEED_ROOT/debs/"
      found=$((found + 1))
    done < <(find "$d" -maxdepth 1 -type f -name '*.deb' -print0)
  done
  ok "收入 $found 个 deb 文件（$(du -sh "$SEED_ROOT/debs" | awk '{print $1}')）"
fi

if [ -n "$DEB_LIST" ]; then
  step "按 --deb-list 抓取 deb"
  [ -f "$DEB_LIST" ] || die "--deb-list 文件不存在：$DEB_LIST"
  if ! command -v apt-get >/dev/null 2>&1; then
    warn "本机没有 apt-get（设备侧 proot 里通常没有），跳过 --deb-list；请改用 --deb-dir"
  else
    ( cd "$SEED_ROOT/debs" && xargs -a "$DEB_LIST" -r apt-get download )
    ok "deb 缓存目录：$(du -sh "$SEED_ROOT/debs" | awk '{print $1}')"
  fi
fi
# 空 debs/ 目录就不留在包里了，避免误导
if [ -z "$(ls -A "$SEED_ROOT/debs" 2>/dev/null || true)" ]; then
  rmdir "$SEED_ROOT/debs"
fi

# ------------------------------------------------- 5. MANIFEST 与文档 ----
step "生成 MANIFEST 与说明文件"

printf '%s\n' "$NODE_VERSION" > "$SEED_ROOT/node-version.txt"

cat > "$SEED_ROOT/README.md" <<EOF
# sunsetlinux 离线种子包（${DATE_TAG}）

给**新设备首次部署**用：不需要在手机上等慢速网络下载这几百 MB 的底座。

## 内容

| 文件 | 说明 |
|---|---|
| \`${UBUNTU_BASE_NAME}\` | Ubuntu ${UBUNTU_SERIES} base rootfs（官方 ubuntu-base，arm64） |
| \`${NODE_TARBALL}\` | Node.js ${NODE_VERSION} 官方 arm64 静态包（LTS「Krypton」） |
| \`debs/\` | 可选：apt 包缓存（如果有） |
| \`MANIFEST\` | 每个文件的 sha256（sha256sum -c 可直接校验） |
| \`MANIFEST.json\` | 同上 + 文件大小 + 上游下载地址 |
| \`SOURCES.txt\` | 上游 URL 与校验依据，便于追溯 |

## 怎么用

\`\`\`bash
# 1) 校验完整性（务必先做）
tools/seed/verify-seed.sh sunsetlinux-seed-${DATE_TAG}.tar.zst

# 2) 解到设备的种子目录
mkdir -p /data/sunsetlinux/seeds
tar --zstd -xf sunsetlinux-seed-${DATE_TAG}.tar.zst -C /data/sunsetlinux/seeds --strip-components=1

# 3) 首次部署时带上种子目录（linuxctl 会优先用本地文件，不再联网）
/data/sunsetlinux/bin/linuxctl provision --seed /data/sunsetlinux/seeds
\`\`\`

> 设备上没有 \`zstd\` 命令时，可先在电脑上解开，或用本仓库的
> \`node tools/seed/zstd-filter.mjs -d < 包 > 包.tar\` 再解开。

## 安全

种子包里的两个 tarball 都与上游官方 sha256 核对过（见 SOURCES.txt）。
**校验不过就不要用**：rootfs 与 Node 二进制是环境的信任根，被替换等于整机被接管。
EOF

cat > "$SEED_ROOT/SOURCES.txt" <<EOF
sunsetlinux 离线种子包 ${DATE_TAG}
生成时间（UTC）：$(date -u +%Y-%m-%dT%H:%M:%SZ)
生成脚本：tools/seed/mkseed.sh

[1] ${UBUNTU_BASE_NAME}
    URL    : ${UBUNTU_BASE_URL}
    校验依据: ${UBUNTU_SUMS_URL}
    sha256 : ${UB_SHA}
    官方值 : ${UB_EXPECT:-（未获取到上游校验文件）}

[2] ${NODE_TARBALL}
    URL    : ${NODE_DIST_BASE}/${NODE_VERSION}/${NODE_TARBALL}
    校验依据: ${NODE_DIST_BASE}/${NODE_VERSION}/SHASUMS256.txt （另有 .sig 可做 GPG 校验）
    sha256 : ${NODE_SHA}
    官方值 : ${NODE_EXPECT:-（未获取到上游校验文件）}
    Node LTS 代号: Krypton

说明：两个 tarball 均为上游官方产物，本脚本只做下载与 sha256 比对，不做任何改写。
EOF

# MANIFEST：sha256sum 兼容格式，路径相对种子根
( cd "$SEED_ROOT" && find . -type f ! -name MANIFEST ! -name 'MANIFEST.json' -print0 \
    | sort -z | xargs -0 sha256sum | sed 's| \./| |' ) > "$SEED_ROOT/MANIFEST"

# MANIFEST.json：给工具读的版本，带大小与来源
node - "$SEED_ROOT" "$UBUNTU_BASE_NAME" "$UBUNTU_BASE_URL" "$UB_EXPECT" \
        "$NODE_TARBALL" "$NODE_DIST_BASE/${NODE_VERSION}/${NODE_TARBALL}" "$NODE_EXPECT" \
        "$NODE_VERSION" "$DATE_TAG" <<'NODEEOF' > "$SEED_ROOT/MANIFEST.json"
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const [root, ubName, ubUrl, ubExpect, nodeName, nodeUrl, nodeExpect, nodeVersion, dateTag] = process.argv.slice(2);
const sources = {
  [ubName]: { url: ubUrl, upstream_sha256: ubExpect || null },
  [nodeName]: { url: nodeUrl, upstream_sha256: nodeExpect || null },
};
const files = [];
function walk(dir, rel = '') {
  for (const e of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const full = path.join(dir, e.name);
    const r = rel ? `${rel}/${e.name}` : e.name;
    if (e.isDirectory()) walk(full, r);
    else if (e.name !== 'MANIFEST' && e.name !== 'MANIFEST.json') {
      const buf = fs.readFileSync(full);
      files.push({
        path: r,
        size: buf.length,
        sha256: crypto.createHash('sha256').update(buf).digest('hex'),
        ...(sources[r] || {}),
      });
    }
  }
}
walk(root);
process.stdout.write(JSON.stringify({
  schema: 1,
  kind: 'sunsetlinux-seed',
  date: dateTag,
  node_version: nodeVersion,
  arch: 'arm64',
  ubuntu_series: '24.04.3',
  files,
}, null, 2) + '\n');
NODEEOF

ok "MANIFEST 收录 $(wc -l < "$SEED_ROOT/MANIFEST") 个文件"

# ------------------------------------------------------------ 6. 打包 ----
step "打包为 .tar.zst（级别 ${ZSTD_LEVEL}）"
OUT_FILE="$OUT_DIR/sunsetlinux-seed-${DATE_TAG}.tar.zst"
tar -C "$STAGE" -cf - "$SEED_DIR_NAME" | $ZSTD_CMD > "$OUT_FILE.part" \
  || die "打包失败（tar/zstd 出错）"
mv "$OUT_FILE.part" "$OUT_FILE"
( cd "$OUT_DIR" && sha256sum "$(basename "$OUT_FILE")" > "$(basename "$OUT_FILE").sha256" )
OUT_SHA="$(sha_of "$OUT_FILE")"

# ------------------------------------------------------------ 7. 汇总 ----
step "汇总"
printf '  %-46s %10s\n' "包名" "$(basename "$OUT_FILE")" >&2
printf '  %-46s %10s\n' "大小" "$(du -h "$OUT_FILE" | awk '{print $1}')" >&2
printf '  %-46s %10s\n' "sha256" "$OUT_SHA" >&2
printf '  %-46s %10s\n' "Node 版本" "$NODE_VERSION" >&2
printf '  %-46s %10s\n' "ubuntu-base" "${UBUNTU_SERIES} (arm64)" >&2
printf '  %-46s %10s\n' "解包后内容" "$(du -sh "$SEED_ROOT" | awk '{print $1}')" >&2
ok "种子包已生成：$OUT_FILE"
info "校验方式：tools/seed/verify-seed.sh $(basename "$OUT_FILE")"

if [ "$RUN_VERIFY" -eq 1 ]; then
  step "立即校验"
  "$SCRIPT_DIR/verify-seed.sh" "$OUT_FILE"
fi

# stdout 只输出产物路径，便于脚本串联
printf '%s\n' "$OUT_FILE"
