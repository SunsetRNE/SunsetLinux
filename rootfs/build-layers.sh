#!/usr/bin/env bash
# ============================================================================
# rootfs/build-layers.sh —— dshroid 三层 rootfs 的**发布构建脚本**
#                            （宿主 / CI 路径：路线 A，需要真 chroot）
# ============================================================================
#
# ## 这个脚本在哪里跑
#   在**普通 Linux 主机或 CI**（x86_64 或 aarch64 都行）上跑，**不在 Android 设备上跑**。
#   设备侧（proot 内）作为普通应用 uid，**没有 CAP_SYS_CHROOT**，做不了真 chroot。
#
# ## 为什么必须真 chroot（引用 docs/findings.md §4.1）
#   findings §4.1 实测记录：
#     - 嵌套 proot 不可用（外层 proot 二次改写路径，报 can't chdir in the guest rootfs）；
#     - `fakechroot + apt` **结果不可信**：`env PATH=... apt-get install git` 时 apt 实际
#       跑在**宿主**的 apt 状态上，回复 "git is already the newest version"，而目标 rootfs
#       里根本没有 git —— 若用这条路构建，产物是错的，还有**误伤宿主**的风险。
#   所以发布构建只能在有 CAP_SYS_CHROOT 的宿主/CI 上用 mmdebstrap/debootstrap + 真 chroot。
#   推论（本脚本的两个硬约束）：
#     1. **不假装能跨架构**：目标 arm64、宿主非 arm64 时必须装 `qemu-user-static` +
#        注册 binfmt_misc，否则直接报错退出（绝不用 qemu 之外的花招）；
#     2. 进入目标 rootfs 的命令一律走 `chroot`（不是 proot/fakechroot），并且用
#        `env -i` 清空宿主环境，避免 PATH 逃逸。
#
# ## 前置依赖（宿主/CI）
#   - root 权限（chroot / mount 需要）
#   - mmdebstrap ≥ 1.4（首选）**或** debootstrap ≥ 1.0.126（兜底）
#   - mkfs.erofs + fsck.erofs（erofs-utils ≥ 1.6）—— 层镜像格式，squashfs 已在设备侧被证伪
#   - zstd（分发压缩；宿主没有时回退到仓库自带的 Node 实现 tools/seed/zstd-filter.mjs）
#   - rsync（做层间增量 diff）、tar、xz-utils（解 Node 官方 .tar.xz）
#   - curl 或 wget（下载 Node 包；DSH 由目标 rootfs 内的 npm 装）
#   - node ≥ 20（仅在 --sign-key 时需要，用来调 tools/channel/gen-manifest.mjs 与 sign.mjs）
#   - 跨架构额外需要：qemu-user-static（提供 qemu-aarch64-static）+ binfmt_misc
#
# ## 文件系统格式：EROFS（不是 squashfs）
#   本机内核实测 `# CONFIG_SQUASHFS is not set`，也没有 squashfs.ko
#   → **squashfs 层根本挂不起来**；而 CONFIG_EROFS_FS=y，设备上已有 1742 个 erofs 挂载。
#   命名、压缩、版本规则的**唯一事实源是 `rootfs/layer-spec.sh`**（本脚本 source 它）：
#     - 层镜像   `<id>-<version>.erofs`        mkfs.erofs -b 4096 --all-root（默认**不压缩**）
#     - 分发产物 `<id>-<version>.erofs.zst`    zstd（gzip 为兼容回退）
#   为什么不压缩镜像：本内核 EROFS 只支持 LZ4，压缩率太弱（同一份 node：
#   lz4 69.0 MB vs 不压缩 116.4 MB），而分发时整体 zstd-19 只要 31.1 MB。
#   flash 有 625 GB 不是瓶颈，且不压缩读取更快 —— 用户感知的是**下载体积**。
#
# ## 三层与"只含增量"的约定
#   L0 base    : Ubuntu 24.04 minimal（profiles/base.packages）+ 三个硬依赖库
#   L1 runtime : base + 层 profiles/runtime.packages + Node 官方 arm64 静态包 → /opt/node
#                + pnpm（`dsh plugin` 的硬依赖，见 docs/dsh-profile.md §6）
#                + /opt/dshroid 入口脚本（entry.sh / supervise.sh，必须在只读层）
#   L2 dsh     : runtime + npm i -g @deepseek-ai/dsh@<tag> → /usr/local
#                + profile 工作区 → /root/.dsh/profiles/web（见 docs/dsh-profile.md §5）
#   每层只打包**与上一层不同的文件**：用 `rsync -aHAX --compare-dest=<上一层完整树>` 生成
#   增量 staging 目录，再 mkfs.erofs 打包。所以 DSH 版本更新只需重下 dsh 层
#   （传输产物约 90 MB 量级，实测值见构建汇总）。
#
#   ⚠️ **层间不做删除**：overlayfs 的只读 lower 无法表达 whiteout，上层"删掉"的文件在合并
#      视图里依然存在。因此：
#        - 所有裁剪（doc/man/locale/多余包）**只在 base 层一次性做**；
#        - L1/L2 里如果出现"上一层有、这一层没有"的文件，本脚本会**明确报警**
#          （--strict-no-deletions 时直接失败）；
#        - 需要"去掉"某文件时，正确做法是改 base 层配方并重发 base，而不是在上层删。
#
# ## 许可红线（产物要能合法分发，务必遵守 docs/dsh-profile.md §2）
#   ✅ 允许：@deepseek-ai/dsh（DSH 主体）、@deepseek-ai/dsh-base、@deepseek-ai/dsh-web-app、
#            dsh-web-mobile（MIT，作者 mexiaosh，npm 公开）、dsh-task-notifier（MIT，npm 公开）
#   ⚠️ 可选且**必须随附 MIT 许可与来源**：dsh-device-shell-guide、dsh-status-overlay
#        （MIT 声明但非 npm 公开）→ 默认**不装**
#   ❌ **绝对禁止**：dsh-app-integration（package.json 无 license 字段 = 保留所有权利）
#   ❌ **绝对禁止**：把 DSHA 的 /root/dsha-* 私有目录复制进任何层
#   脚本在打包前调用 layer_spec_assert_no_forbidden()（来自 layer-spec.sh），命中即失败退出。
#
# ## 用法
#   sudo rootfs/build-layers.sh --dsh-dist-tag next
#   sudo rootfs/build-layers.sh --dsh-dist-tag next --base-url https://example.org/dshroid \
#        --sign-key ~/.dshroid-keys/channel.key --channel-name "官方"
#
# ============================================================================
set -euo pipefail

# ------------------------------------------------------------------ 常量 ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ★ 共享层规格：命名 / 压缩 / 版本 / 禁止路径 / profile 软链断言全在这里，
#   本脚本与 rootfs/device-provision.sh 必须产出**逐字节可比对**的同名层。
#   不要在本文件里重复定义这些决策 —— 改规格请改 layer-spec.sh。
# shellcheck source=layer-spec.sh
. "$SCRIPT_DIR/layer-spec.sh"

UBUNTU_SUITE="noble"                     # Ubuntu 24.04 LTS
UBUNTU_VERSION="24.04.3"                 # 用于 base 层默认版本号
UBUNTU_MIRROR_ARM64="http://ports.ubuntu.com/ubuntu-ports"
UBUNTU_MIRROR_AMD64="http://archive.ubuntu.com/ubuntu"

ARCH="arm64"
BASE_VERSION="${UBUNTU_VERSION}-l1"
RUNTIME_VERSION="1.0.0"
DSH_VERSION=""                            # 空 = 用 dist-tag 解析
DSH_DIST_TAG="next"
DSH_PACKAGE="@deepseek-ai/dsh"
NODE_VERSION=""                           # 空 = 联网取 v24 最新 LTS
NODE_FALLBACK_VERSION="v24.21.0"
NODE_MAJOR="24"
NODE_TARBALL_URL_BASE="https://nodejs.org/dist"
PNPM_VERSION="latest"                    # pnpm 是 dsh plugin 的硬依赖（dsh-profile.md §6）

OUT_DIR=""
WORK_DIR=""
EROFS_COMPRESS_ARG=""                    # 空 = 用 spec 默认（none，即不传 -z）
TRANSPORT_LEVEL="19"                     # zstd 级别（gzip 固定 -9）
GZIP_FALLBACK=1                          # 默认**同时产出 .gz**（设备侧只有 gzip，见 layer-spec.sh §3）
STRIP_XATTR=0                            # -x-1：不存 xattr（设备版 mkfs.erofs 可能不认，探测后再用）
ZSTD_CMD=""; GZIP_CMD=""
MKFS_ARGS_SUPPORTED=""                   # mkfs.erofs 选项能力探测结果（避免硬编码宿主专属参数）
REPRODUCIBLE=0
JOBS="$( (nproc 2>/dev/null || echo 2) )"
KEEP_WORK=0
STRICT_NO_DELETIONS=0
SKIP_DEPS_CHECK=0
SKIP_BASE=0
SKIP_RUNTIME=0
SKIP_DSH=0
SIGN_KEY=""
BASE_URL=""
CHANNEL_NAME="官方"
DHS_DEFAULTS_DIR=""
RUNTIME_DIR="auto"                        # 入口脚本目录；默认自动探测 runtime/root

# ------------------------------------------------------------------ 输出 ----
if [ -t 2 ]; then C_E=$'\033[31m'; C_I=$'\033[36m'; C_K=$'\033[32m'; C_W=$'\033[33m'; C_R=$'\033[0m'
else C_E=''; C_I=''; C_K=''; C_W=''; C_R=''; fi
die()  { printf '%s[错误]%s %s\n' "$C_E" "$C_R" "$*" >&2; exit 1; }
info() { printf '%s[信息]%s %s\n' "$C_I" "$C_R" "$*" >&2; }
ok()   { printf '%s[完成]%s %s\n' "$C_K" "$C_R" "$*" >&2; }
warn() { printf '%s[警告]%s %s\n' "$C_W" "$C_R" "$*" >&2; }
step() { printf '\n%s══> %s%s\n' "$C_I" "$*" "$C_R" >&2; }
sub()  { printf '   %s·%s %s\n' "$C_I" "$C_R" "$*" >&2; }

usage() {
  cat <<'EOF'
用法：sudo rootfs/build-layers.sh [选项]

在宿主/CI 上用真 chroot 构建 dshroid 的三层 EROFS 镜像（base / runtime / dsh）。
层规格取自 rootfs/layer-spec.sh（命名/压缩/版本/禁止路径的唯一事实源）。

版本与内容：
  --base-version <ver>      base 层版本号（默认：24.04.3-l1）→ base-<ver>.erofs
  --runtime-version <ver>   runtime 层版本号（默认：1.0.0）
  --dsh-dist-tag <tag>      DSH 的 npm dist-tag（默认：next；可选 latest/alpha）
  --dsh-version <ver>       直接指定 DSH 版本号（覆盖 --dsh-dist-tag 的解析结果）
  --node-version <vX.Y.Z>   Node 版本（默认：联网取 v24 最新 LTS，失败用 v24.21.0）
  --pnpm-version <ver>      pnpm 版本（默认：latest）—— dsh plugin 的硬依赖，见下
  --arch <arm64|amd64>      目标架构（默认：arm64）
  --runtime-dir <目录>      把该目录内容复制到 **runtime 层** /opt/dshroid（entry.sh/supervise.sh）
                            （默认自动尝试 runtime/root；见 layer-spec.sh 的 LAYER_RUNTIME_PATHS）

产物：
  --out-dir <目录>          产物目录（默认：<仓库>/dist/layers-<日期>-<dsh版本>）
  --erofs-compress <算法>   镜像内压缩：none（默认，spec 的决定）| lz4 | lz4hc,9 | lzma | deflate
                            ⚠ 本内核 EROFS 只支持 LZ4，所以默认不压缩镜像，
                              改由分发时的 --transport-compress 压整体（压缩率高得多）
  --transport-level <N>     zstd 压缩级别（默认 19；gzip 固定 -9）
  --no-gzip-fallback        不再额外产出 .gz 回退产物
                            （默认两个都出：App 走 .zst 更小，设备侧纯 CLI 只有 gzip）
  --strip-xattr             镜像里不存 xattr（-x-1）。设备版 mkfs.erofs 对 xattr 挑剔时用；
                            代价是丢失文件 capabilities/xattr（本项目以真 root 运行，通常不需要）
  --reproducible            可重现构建：固定 mtime 与 UUID（层内容逐字节可比对）
  --jobs <N>                并行度（默认：CPU 核数）
  --work-dir <目录>         构建工作目录（默认：mktemp -d）
  --keep-work               保留工作目录（排错用）

校验与发布：
  --strict-no-deletions     若某层删除了上一层的文件则失败（默认只警告）
  --sign-key <channel.key>  打包后自动 gen-manifest + sign，产出 channel.json/.sig
  --base-url <前缀>         配合 --sign-key：写进 channel.json 的层下载前缀
  --channel-name <名字>     配合 --sign-key：频道显示名（默认：官方）

跳过与调试：
  --skip-deps-check         跳过宿主依赖检查（不推荐）
  --skip-base               复用 --work-dir 里已有的 base-root（续跑）
  --skip-runtime            同上，复用 runtime-root
  --skip-dsh                同上，复用 dsh-root
  -h, --help                显示本帮助

举例：
  # 官方发布：打包 + 生成并签名 channel.json
  sudo rootfs/build-layers.sh --dsh-dist-tag next \
      --base-url https://example.org/dshroid --sign-key ~/.dshroid-keys/channel.key

  # 只重出 dsh 层（DSH 升级场景，复用已有 base/runtime 树）
  sudo rootfs/build-layers.sh --dsh-dist-tag alpha --work-dir /var/tmp/dshroid-build \
      --skip-base --skip-runtime
EOF
}

# ------------------------------------------------------------- 参数解析 ----
while [ $# -gt 0 ]; do
  case "$1" in
    --base-version)     BASE_VERSION="${2:?}"; shift 2 ;;
    --runtime-version)  RUNTIME_VERSION="${2:?}"; shift 2 ;;
    --dsh-dist-tag)     DSH_DIST_TAG="${2:?}"; shift 2 ;;
    --dsh-version)      DSH_VERSION="${2:?}"; shift 2 ;;
    --dsh-package)      DSH_PACKAGE="${2:?}"; shift 2 ;;
    --node-version)     NODE_VERSION="${2:?}"; shift 2 ;;
    --pnpm-version)     PNPM_VERSION="${2:?}"; shift 2 ;;
    --arch)             ARCH="${2:?}"; shift 2 ;;
    --out-dir)          OUT_DIR="${2:?}"; shift 2 ;;
    --work-dir)         WORK_DIR="${2:?}"; shift 2 ;;
    --erofs-compress)   EROFS_COMPRESS_ARG="${2:?}"; shift 2 ;;
    --transport-compress)
      die "--transport-compress 已废弃：主分发压缩固定为 zstd（layer-spec.sh §3），
现在只用 --no-gzip-fallback 控制"是否额外产出 .gz 回退产物"（默认产出）。"
      ;;
    --no-gzip-fallback) GZIP_FALLBACK=0; shift ;;
    --strip-xattr)      STRIP_XATTR=1; shift ;;
    --transport-level)  TRANSPORT_LEVEL="${2:?}"; shift 2 ;;
    --reproducible)     REPRODUCIBLE=1; shift ;;
    --jobs)             JOBS="${2:?}"; shift 2 ;;
    --keep-work)        KEEP_WORK=1; shift ;;
    --strict-no-deletions) STRICT_NO_DELETIONS=1; shift ;;
    --skip-deps-check)  SKIP_DEPS_CHECK=1; shift ;;
    --skip-base)        SKIP_BASE=1; shift ;;
    --skip-runtime)     SKIP_RUNTIME=1; shift ;;
    --skip-dsh)         SKIP_DSH=1; shift ;;
    --sign-key)         SIGN_KEY="${2:?}"; shift 2 ;;
    --base-url)         BASE_URL="${2:?}"; shift 2 ;;
    --channel-name)     CHANNEL_NAME="${2:?}"; shift 2 ;;
    --dsh-defaults-dir) DHS_DEFAULTS_DIR="${2:?}"; shift 2 ;;
    --runtime-dir)      RUNTIME_DIR="${2:?}"; shift 2 ;;
    -h|--help)          usage; exit 0 ;;
    *)                  die "未知参数：$1（用 --help 查看用法）" ;;
  esac
done

case "$ARCH" in arm64|amd64) ;; *) die "--arch 只支持 arm64 / amd64，实际：$ARCH" ;; esac
# 镜像内压缩：默认 none（spec 的决定）。⚠ mkfs.erofs 没有 `-z none` 这个取值，
# 「不压缩」= 干脆不传 -z；这里把它们统一成「传不传 -z」。
[ -n "$EROFS_COMPRESS_ARG" ] && EROFS_COMPRESS="$EROFS_COMPRESS_ARG"
case "$EROFS_COMPRESS" in
  none|"") EROFS_COMPRESS="none" ;;
  lz4|lz4hc|lzma|deflate|libdeflate|lz4hc,*) ;;
  *) die "--erofs-compress 不支持：$EROFS_COMPRESS（可用：none | lz4 | lz4hc,9 | lzma | deflate）" ;;
esac
[[ "$TRANSPORT_LEVEL" =~ ^[0-9]+$ ]] && [ "$TRANSPORT_LEVEL" -ge 1 ] && [ "$TRANSPORT_LEVEL" -le 22 ] \
  || die "--transport-level 必须是 1–22 的整数"
# 主分发压缩由 spec 决定（zstd），不再由命令行切换 —— 两条分发路径都必须能走通
TRANSPORT_PRIMARY="$TRANSPORT_COMPRESS_PRIMARY"
TRANSPORT_FALLBACK="$TRANSPORT_COMPRESS_FALLBACK"
[[ "$JOBS" =~ ^[0-9]+$ ]] && [ "$JOBS" -ge 1 ] || die "--jobs 必须是正整数"

# --------------------------------------------------------- 前置条件检查 ----
[ "$(id -u)" -eq 0 ] || die "本脚本需要 root（要真 chroot 与 mount）。请用 sudo 运行。
（提示：Android 设备上的 proot 环境**没有** CAP_SYS_CHROOT，跑不了本脚本 —— 见 findings.md §4.1）"

HOST_ARCH_RAW="$(uname -m)"
case "$HOST_ARCH_RAW" in
  aarch64|arm64) HOST_ARCH="arm64" ;;
  x86_64|amd64)  HOST_ARCH="amd64" ;;
  *)             HOST_ARCH="unknown" ;;
esac
CROSS=0
[ "$HOST_ARCH" = "$ARCH" ] || CROSS=1
QEMU_BIN=""
if [ "$CROSS" -eq 1 ]; then
  QEMU_BIN="$(command -v "qemu-${ARCH}-static" || true)"
fi

need() { command -v "$1" >/dev/null 2>&1; }
check_deps() {
  step "检查宿主依赖"
  local missing=()
  for c in rsync tar xz sha256sum find awk sed; do
    need "$c" || missing+=("$c")
  done
  need mkfs.erofs || missing+=("erofs-utils（提供 mkfs.erofs；层镜像格式）")
  need fsck.erofs || missing+=("erofs-utils（提供 fsck.erofs；打包后完整性校验）")
  need curl || need wget || missing+=("curl 或 wget")

  local have_mm=0 have_db=0
  need mmdebstrap && have_mm=1
  need debootstrap && have_db=1
  if [ "$have_mm" -eq 0 ] && [ "$have_db" -eq 0 ]; then
    missing+=("mmdebstrap 或 debootstrap")
  fi

  if [ "$CROSS" -eq 1 ]; then
    [ -n "$QEMU_BIN" ] || missing+=("qemu-user-static（跨架构构建 ${ARCH} 必需，提供 qemu-${ARCH}-static）")
    if [ ! -e /proc/sys/fs/binfmt_misc/status ]; then
      missing+=("binfmt_misc 未挂载（跨架构构建必需：modprobe binfmt_misc && mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc）")
    fi
  fi

  if [ "${#missing[@]}" -gt 0 ]; then
    printf '%s缺失以下依赖：%s\n' "$C_E" "$C_R" >&2
    for m in "${missing[@]}"; do printf '  · %s\n' "$m" >&2; done
    cat >&2 <<'EOF'

安装建议（Debian/Ubuntu 宿主）：
  apt-get install -y mmdebstrap erofs-utils zstd rsync xz-utils curl
  # 跨架构（在 x86_64 上构建 arm64）额外：
  apt-get install -y qemu-user-static binfmt-support
  # 若用 debootstrap 兜底：apt-get install -y debootstrap

为什么必须真 chroot：见 docs/findings.md §4.1 —— proot 嵌套不可用，
fakechroot+apt 会读到宿主的 apt 状态（结果不可信且有误伤宿主风险）。
EOF
    die "宿主依赖不满足，已中止。"
  fi
  sub "mmdebstrap=$have_mm debootstrap=$have_db 宿主架构=$HOST_ARCH 目标架构=$ARCH 跨架构=$CROSS"
  ok "宿主依赖齐备。"
}
[ "$SKIP_DEPS_CHECK" -eq 1 ] || check_deps

# 分发压缩命令：主产物 zstd（优先系统 zstd，其次 Node 内置实现），回退产物 gzip。
# 为什么要两个都出：设备实测**没有 zstd/xz**，只有 toybox gzip ——
#   App 路径走 .zst（体积小 ~35%），纯 CLI / 无 App 路径只能走 .gz（layer-spec.sh §3）。
setup_transport_cmds() {
  case "$TRANSPORT_PRIMARY" in
    zstd)
      # ★ 窗口必须锁在默认档（windowLog=23 = 8 MiB）：
      #   dshroid App 用**纯 Java** zstd 解码器（io.airlift:aircompressor —— zstd-jni
      #   没有 Android ABI），窗口上限就是 8 MiB。若改用 --long=27 / -22 之类放大窗口的
      #   参数，App 会**自动退回 gzip**：dsh 层从 31.2 MB 涨到 47.8 MB，用户白下。
      #   ⚠ 不要提高 windowLog。
      local wlog=23
      if need zstd; then
        if zstd --help 2>&1 | grep -q -- '--zstd='; then
          ZSTD_CMD="zstd -${TRANSPORT_LEVEL} --zstd=wlog=${wlog} -T0 -q -c"
        else
          warn "本机 zstd 不支持 --zstd=wlog=（多半是老版本）—— 将用默认窗口；请人工确认 windowLog ≤ ${wlog}"
          ZSTD_CMD="zstd -${TRANSPORT_LEVEL} -T0 -q -c"
        fi
      elif need node; then
        # Node 过滤器默认就锁 windowLog=23，显式传一遍更醒目
        ZSTD_CMD="node --no-warnings $REPO_ROOT/tools/seed/zstd-filter.mjs -l ${TRANSPORT_LEVEL} -w ${wlog}"
        info "宿主没有 zstd 命令，改用 Node 内置 zstd 实现（tools/seed/zstd-filter.mjs，窗口锁 ${wlog}）"
      else
        die "主分发压缩是 zstd，但宿主既没有 zstd 也没有 node。装 zstd，或装 Node ≥ 20。"
      fi
      ;;
    gzip) ZSTD_CMD="gzip -9 -c" ;;
    *)    die "layer-spec.sh 里的 TRANSPORT_COMPRESS_PRIMARY 不支持：$TRANSPORT_PRIMARY" ;;
  esac
  if [ "$GZIP_FALLBACK" -eq 1 ]; then
    case "$TRANSPORT_FALLBACK" in
      gzip)
        if need gzip; then
          GZIP_CMD="gzip -9 -c"
        elif need node; then
          # 宿主连 gzip 都没有（极罕见）：用仓库自带的 Node 过滤器
          # （tools/seed/zstd-filter.mjs 支持 -a gzip）
          GZIP_CMD="node --no-warnings $REPO_ROOT/tools/seed/zstd-filter.mjs -a gzip -l 9"
        else
          warn "既没有 gzip 也没有 node —— 无法产出 .gz 回退产物（纯 CLI 路径会断）"
          GZIP_CMD=""
        fi
        ;;
      *) die "layer-spec.sh 里的 TRANSPORT_COMPRESS_FALLBACK 不支持：$TRANSPORT_FALLBACK" ;;
    esac
  fi
  info "分发压缩：主 $TRANSPORT_PRIMARY${ZSTD_CMD:+（已就绪，windowLog=23/8 MiB）}${GZIP_CMD:+，回退 $TRANSPORT_FALLBACK}"
  if [ -n "$ZSTD_CMD" ]; then
    info "  ⚠ zstd 窗口锁在 8 MiB：App 是纯 Java 解码器，超过就会退回 gzip（白下十几 MB）"
  fi
  [ -n "$GZIP_CMD" ] || warn "不会产出 .gz 回退产物：设备侧（只有 toybox gzip）将无法手工装层"
}
# 无论是否跳过依赖检查，都要装配分发压缩命令（只是探测命令是否存在，代价极低）
setup_transport_cmds

# 目标 rootfs 内执行命令的统一入口：真 chroot + 干净环境（findings §4.1 的教训）
in_root() {
  local root="$1"; shift
  chroot "$root" /usr/bin/env -i \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/root LANG=C.UTF-8 TZ=UTC DEBIAN_FRONTEND=noninteractive \
    "$@"
}

# 部分包（apt/npm）需要 /proc /dev；用 bind mount 临时提供，退出时统一卸载
MOUNTED_ROOTS=""
bind_mounts() {
  local root="$1"
  mkdir -p "$root/proc" "$root/sys" "$root/dev/pts" "$root/dev/shm" "$root/tmp"
  mount -t proc proc "$root/proc" 2>/dev/null || warn "mount /proc 失败（apt 可能报错）"
  mount --bind /sys "$root/sys" 2>/dev/null || true
  mount --bind /dev "$root/dev" 2>/dev/null || true
  mount -t devpts devpts "$root/dev/pts" 2>/dev/null || true
  mount -t tmpfs -o mode=1777 tmpfs "$root/dev/shm" 2>/dev/null || true
  MOUNTED_ROOTS="$MOUNTED_ROOTS $root"
}
unbind_all() {
  local root
  for root in $MOUNTED_ROOTS; do
    umount -l "$root/dev/pts" 2>/dev/null || true
    umount -l "$root/dev/shm" 2>/dev/null || true
    umount -l "$root/dev" 2>/dev/null || true
    umount -l "$root/sys" 2>/dev/null || true
    umount -l "$root/proc" 2>/dev/null || true
  done
  MOUNTED_ROOTS=""
}
cleanup() {
  local rc=$?
  unbind_all
  if [ -n "$WORK_DIR" ] && [ "$KEEP_WORK" -eq 0 ]; then
    rm -rf "$WORK_DIR"
  elif [ -n "$WORK_DIR" ]; then
    warn "保留工作目录：$WORK_DIR"
  fi
  return $rc
}

# ------------------------------------------------------- 包清单 / 排除表 ----
read_packages() { # 读 profiles/*.packages：去注释、取第一列、去重、逗号连接
  local f="$1"
  [ -f "$f" ] || die "找不到包清单：$f"
  grep -v '^[[:space:]]*#' "$f" \
    | awk '{print $1}' \
    | grep -v '^$' \
    | awk '!seen[$1]++' \
    | paste -sd, -
}
count_packages() { read_packages "$1" | tr ',' '\n' | grep -c . ; }

# 从各层 staging 里排除的易变路径：apt 缓存、日志、临时文件、机器标识……
# 这些不排掉的话，每层都会因为"上一次构建的残渣"而虚胖，还会泄漏宿主信息。
# 这些排除规则对**三层一致**应用，所以不存在"上层删掉下层文件"的情况 ——
# 被排除的东西在每一层里都不存在，合并视图里自然也没有（overlay 语义安全）。
EXCLUDES=(
  '/var/cache/apt/archives/*'
  '/var/lib/apt/lists/*'
  '/var/lib/apt/lists/partial/*'
  '/var/log/*'
  '/var/tmp/*'
  '/tmp/*'
  '/proc/*'
  '/sys/*'
  '/dev/*'
  '/run/*'
  '/root/.npm'
  '/root/.cache'
  '/root/.bash_history'
  '/usr/share/doc/*'
  '/usr/share/man/*'
  '/usr/share/info/*'
  '/usr/share/lintian/*'
  '/usr/share/doc-base/*'
)
# 注：/etc/resolv.conf、/etc/hostname、/etc/machine-id **不排除**：
#   base 层的 customize hook 会写入固定占位值（entry.sh 运行时按 Android 实际值重写），
#   这样即便有人直接 chroot 进去排障，DNS 与主机名也是可用的；
#   固定值还避免了 machine-id 随机生成导致的层间 churn。
rsync_excludes() { local e; for e in "${EXCLUDES[@]}"; do printf -- '--exclude=%s\n' "$e"; done; }

# ------------------------------------------------------------ 版本解析 ----
DSH_VERSION_FROM_TAG=0
resolve_dsh_version() {
  if [ -n "$DSH_VERSION" ]; then
    info "使用显式指定的版本：${DSH_PACKAGE}@${DSH_VERSION}（跳过 dist-tag 解析与比对）"
    return 0
  fi
  if ! need node; then
    die "需要 node 来解析 npm dist-tag，或直接用 --dsh-version 指定版本号"
  fi
  info "查询 npm dist-tag：${DSH_PACKAGE}@${DSH_DIST_TAG}"
  DSH_VERSION="$(node -e '
    const pkg=process.argv[1], tag=process.argv[2];
    fetch(`https://registry.npmjs.org/-/package/${pkg.replace("/","%2f")}/dist-tags`)
      .then(r=>r.json())
      .then(j=>{ const v=j[tag]; if(!v){console.error("dist-tag 不存在，可用："+Object.keys(j).join(", "));process.exit(1);} process.stdout.write(v); })
      .catch(e=>{ console.error("查询失败："+e.message); process.exit(1); });
  ' "$DSH_PACKAGE" "$DSH_DIST_TAG")" || die "无法解析 dist-tag ${DSH_DIST_TAG}（网络问题？可用 --dsh-version 直接指定）"
  DSH_VERSION_FROM_TAG=1
  ok "dist-tag ${DSH_DIST_TAG} → ${DSH_PACKAGE}@${DSH_VERSION}"
}

resolve_node_version() {
  if [ -n "$NODE_VERSION" ]; then
    case "$NODE_VERSION" in v*) ;; *) NODE_VERSION="v$NODE_VERSION" ;; esac
    return 0
  fi
  NODE_VERSION="$NODE_FALLBACK_VERSION"
  if need node; then
    local v
    v="$(node -e '
      fetch("https://nodejs.org/dist/index.json").then(r=>r.json()).then(j=>{
        const m=process.argv[1];
        const list=j.filter(x=>x.version.startsWith("v"+m+".")&&x.lts)
                    .sort((a,b)=>a.version.localeCompare(b.version,undefined,{numeric:true}));
        process.stdout.write(list.length?list[list.length-1].version:"");
      }).catch(()=>process.stdout.write(""));
    ' "$NODE_MAJOR")" || true
    [ -n "$v" ] && NODE_VERSION="$v"
  fi
  ok "Node 版本：${NODE_VERSION}（arm64 官方静态包）"
}

# =========================================================== 构建工作流 ====

WORK_DIR="${WORK_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/dshroid-build.XXXXXX")}"
mkdir -p "$WORK_DIR"
trap cleanup EXIT

BASE_ROOT="$WORK_DIR/base-root"
RUNTIME_ROOT="$WORK_DIR/runtime-root"
DSH_ROOT="$WORK_DIR/dsh-root"
HOOK_DIR="$WORK_DIR/hooks"
mkdir -p "$HOOK_DIR"

[ -n "$OUT_DIR" ] || OUT_DIR="$REPO_ROOT/dist/layers-$(date -u +%Y%m%d)"
mkdir -p "$OUT_DIR"

BASE_PKGS_FILE="$REPO_ROOT/rootfs/profiles/base.packages"
RUNTIME_PKGS_FILE="$REPO_ROOT/rootfs/profiles/runtime.packages"

# ---------------------------------------------------- 0. 定制 hook 脚本 ----
write_customize_hook() {
  cat > "$HOOK_DIR/base-customize.sh" <<'HOOK'
#!/bin/sh
# mmdebstrap --customize-hook：在 chroot 内做 base 层的"一次性裁剪与设置"。
# 注意：mmdebstrap 会以 <target-rootfs> 作为 $1 调用本脚本。
set -eu
ROOT="$1"

# 1) 只生成必要 locale（locales 包默认生成一堆，白白占体积）
#    C.UTF-8 给 DSH/Node 与脚本用；zh_CN.UTF-8 给中文界面与用户；en_US.UTF-8 兜底。
if [ -x "$ROOT/usr/sbin/locale-gen" ] || [ -x "$ROOT/sbin/locale-gen" ]; then
  printf 'C.UTF-8 UTF-8\nzh_CN.UTF-8 UTF-8\nen_US.UTF-8 UTF-8\n' > "$ROOT/etc/locale.gen"
  printf 'LANG=C.UTF-8\n' > "$ROOT/etc/default/locale"
  chroot "$ROOT" /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin /usr/sbin/locale-gen || \
    chroot "$ROOT" /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin locale-gen
fi

# 2) 证书（npm/GitHub HTTPS 全靠它）
if [ -x "$ROOT/usr/sbin/update-ca-certificates" ]; then
  chroot "$ROOT" /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin update-ca-certificates --fresh >/dev/null 2>&1 || true
fi

# 3) 裁掉文档/手册残渣（dpkg path-exclude 覆盖不到的部分）
rm -rf "$ROOT"/usr/share/man/* "$ROOT"/usr/share/info/* "$ROOT"/usr/share/lintian/* 2>/dev/null || true
# /usr/share/doc 只保留 copyright（法律要求保留许可声明）
if [ -d "$ROOT/usr/share/doc" ]; then
  find "$ROOT/usr/share/doc" -mindepth 1 -maxdepth 1 -type d | while read -r d; do
    [ -f "$d/copyright" ] || rm -rf "$d"
  done
fi
# 非中文/英文的 locale 数据（如果 locales 包带了）
[ -d "$ROOT/usr/share/locale" ] && find "$ROOT/usr/share/locale" -mindepth 1 -maxdepth 1 -type d \
  ! -name 'zh*' ! -name 'en*' ! -name 'C*' -exec rm -rf {} + 2>/dev/null || true

# 4) 清掉 apt 缓存与列表（层里不留宿主构建时刻的网络状态）
rm -rf "$ROOT"/var/cache/apt/* "$ROOT"/var/lib/apt/lists/* 2>/dev/null || true

# 5) 固定的主机名/时区/解析器/machine-id 占位（entry.sh 运行时会按 Android 实际值重写）
#    machine-id 用固定值而不是随机值：避免每次构建产生不同的层内容（层间 churn）。
printf 'dshroid\n' > "$ROOT/etc/hostname"
printf '0123456789abcdef0123456789abcdef\n' > "$ROOT/etc/machine-id"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOT/etc/resolv.conf"
[ -e "$ROOT/etc/localtime" ] || ln -sfn /usr/share/zoneinfo/UTC "$ROOT/etc/localtime"

# 6) 校验三个硬依赖库在不在（findings §6.2：DSH 运行只需要这三个库）
for so in ld-linux-aarch64.so.1 libc.so.6 libstdc++.so.6 libgcc_s.so.1; do
  if [ "$(uname -m)" = "aarch64" ] || [ "$(uname -m)" = "arm64" ]; then
    find "$ROOT/usr/lib" "$ROOT/lib" -name "$so" 2>/dev/null | grep -q . || \
      echo "[警告] base 层里找不到 $so —— DSH 可能起不来" >&2
  fi
done

echo "[hook] base 层裁剪完成" >&2
exit 0
HOOK
  chmod +x "$HOOK_DIR/base-customize.sh"
}

# ------------------------------------------------------------ L0 base ----
build_base() {
  step "L0 base：构建 Ubuntu ${UBUNTU_VERSION} minimal（真 chroot）"
  local pkgs
  pkgs="$(read_packages "$BASE_PKGS_FILE")"
  sub "包清单：$(count_packages "$BASE_PKGS_FILE") 个包（profiles/base.packages）"
  sub "裁剪策略：dpkg path-exclude 掉 doc/man/info + locale 只留 3 个 + 无 systemd/snapd"

  if need mmdebstrap; then
    local args=(--arch="$ARCH" --variant=minbase --components=main,universe
                --include="$pkgs"
                --aptopt='APT::Install-Recommends "false"'
                --aptopt='APT::Install-Suggests "false"'
                --aptopt='Acquire::Languages "none"'
                --dpkgopt='path-exclude=/usr/share/man/*'
                --dpkgopt='path-exclude=/usr/share/info/*'
                --dpkgopt='path-exclude=/usr/share/lintian/*'
                --dpkgopt='path-exclude=/usr/share/doc/*'
                --dpkgopt='path-include=/usr/share/doc/*/copyright'
                --customize-hook="$HOOK_DIR/base-customize.sh")
    [ "$CROSS" -eq 1 ] && args+=(--qemu "$QEMU_BIN")
    local mirror="$UBUNTU_MIRROR_AMD64"
    [ "$ARCH" = "arm64" ] && mirror="$UBUNTU_MIRROR_ARM64"
    sub "mmdebstrap ${UBUNTU_SUITE} → $BASE_ROOT"
    mmdebstrap "${args[@]}" "$UBUNTU_SUITE" "$BASE_ROOT" "$mirror" \
      || die "mmdebstrap 失败（看上面输出；跨架构请确认 binfmt_misc 已注册 qemu-${ARCH}）"
  else
    warn "没有 mmdebstrap，回退 debootstrap（功能等价但慢，且 hook 需手工执行）"
    local mirror="$UBUNTU_MIRROR_AMD64"
    [ "$ARCH" = "arm64" ] && mirror="$UBUNTU_MIRROR_ARM64"
    sub "debootstrap --arch=$ARCH --variant=minbase → $BASE_ROOT"
    debootstrap --arch="$ARCH" --variant=minbase --include="$pkgs" \
      --components=main,universe "$UBUNTU_SUITE" "$BASE_ROOT" "$mirror" \
      || die "debootstrap 失败"
    # debootstrap 不认 dpkg path-exclude，改为装完后手工裁剪（写在 hook 里统一执行）
    if [ "$CROSS" -eq 1 ]; then
      cp "$QEMU_BIN" "$BASE_ROOT/usr/bin/qemu-${ARCH}-static"
    fi
    bind_mounts "$BASE_ROOT"
    "$HOOK_DIR/base-customize.sh" "$BASE_ROOT" || die "base 定制 hook 失败"
  fi

  # 真 chroot 健康检查：确认这是"能跑起来"的 rootfs 而不是半成品
  bind_mounts "$BASE_ROOT"
  in_root "$BASE_ROOT" /bin/bash -c 'echo "  chroot 自检: $(. /etc/os-release; echo $PRETTY_NAME) / kernel-abi=$(uname -m)"' >&2
  in_root "$BASE_ROOT" /bin/bash -c 'command -v bash tar grep sed sha256sum ca-certificates' >/dev/null \
    || warn "base 层缺少个别基础命令，请检查 base.packages"
  ok "L0 base 就绪：$BASE_ROOT"
}

# --------------------------------------------------------- L1 runtime ----
build_runtime() {
  step "L1 runtime：base + 工具 + Node ${NODE_VERSION} → /opt/node"
  local pkgs
  pkgs="$(read_packages "$RUNTIME_PKGS_FILE")"
  sub "包清单：$(count_packages "$RUNTIME_PKGS_FILE") 个包（profiles/runtime.packages）"

  # 先复制 base 树，再在里面装东西 —— 这样 L1 = L0 的"超集"，diff 出来天然是增量
  sub "复制 base 树（硬链接加速）"
  rm -rf "$RUNTIME_ROOT"
  mkdir -p "$RUNTIME_ROOT"
  rsync -aHAX --delete "$BASE_ROOT/" "$RUNTIME_ROOT/"

  bind_mounts "$RUNTIME_ROOT"
  in_root "$RUNTIME_ROOT" /bin/bash -c 'printf "deb %s %s main universe\n" "$1" "$2" > /etc/apt/sources.list' _ \
    "$([ "$ARCH" = arm64 ] && echo "$UBUNTU_MIRROR_ARM64" || echo "$UBUNTU_MIRROR_AMD64")" "$UBUNTU_SUITE" \
    || die "写入 sources.list 失败"

  sub "apt-get update && install（--no-install-recommends）"
  in_root "$RUNTIME_ROOT" /bin/bash -c "
    set -e
    export DEBIAN_FRONTEND=noninteractive
    apt-get -q update
    apt-get -q -y --no-install-recommends install $pkgs
    rm -rf /var/cache/apt/* /var/lib/apt/lists/*
  " || die "runtime 层 apt 安装失败（包名拼错？网络？）"

  # Node：官方 arm64 静态 tarball → /opt/node（不用 apt 的 nodejs，版本太旧）
  local node_tar="$WORK_DIR/node-${NODE_VERSION}-linux-arm64.tar.xz"
  if [ ! -f "$node_tar" ]; then
    local url="$NODE_TARBALL_URL_BASE/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.xz"
    sub "下载 $url"
    if need curl; then
      curl -fL --retry 3 -C - -o "$node_tar.part" "$url" || die "下载 Node 包失败：$url"
    else
      wget -q -O "$node_tar.part" "$url" || die "下载 Node 包失败：$url"
    fi
    mv "$node_tar.part" "$node_tar"
  fi
  # 官方 sha256 校验（不校验就打包 = 把信任根交给网络）
  if need node && need curl; then
    local want got
    want="$(curl -fsL "$NODE_TARBALL_URL_BASE/${NODE_VERSION}/SHASUMS256.txt" 2>/dev/null \
            | awk -v f="node-${NODE_VERSION}-linux-arm64.tar.xz" '$2==f{print $1; exit}')"
    got="$(sha256sum "$node_tar" | awk '{print $1}')"
    if [ -n "$want" ] && [ "$want" != "$got" ]; then
      die "Node 包 sha256 与官方 SHASUMS256.txt 不一致！期望 $want 实际 $got"
    fi
    [ -n "$want" ] && ok "Node 包 sha256 与官方一致：$got"
  fi

  sub "解到 /opt/node 并建符号链接 /usr/local/bin/{node,npm,npx}"
  mkdir -p "$RUNTIME_ROOT/opt/node"
  tar -xJf "$node_tar" -C "$RUNTIME_ROOT/opt/node" --strip-components=1 \
    || die "解压 Node 包失败（宿主需要 xz-utils）"
  mkdir -p "$RUNTIME_ROOT/usr/local/bin"
  ( cd "$RUNTIME_ROOT/usr/local/bin" && ln -sfn /opt/node/bin/node node \
      && ln -sfn /opt/node/bin/npm npm && ln -sfn /opt/node/bin/npx npx )

  # ★ 关键：npm 全局前缀必须显式指向 /usr/local，否则 `npm i -g` 会装进
  #   /opt/node/lib/node_modules，dsh 层就切不出来了（architecture.md §5.3）。
  in_root "$RUNTIME_ROOT" /bin/bash -c '
    set -e
    export PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin
    npm config set prefix /usr/local --global
    npm config set fund false --global
    npm config set audit false --global
    npm config set update-notifier false --global
  ' || die "配置 npm prefix 失败"

  # ★ pnpm：`dsh plugin --profile web add <包名>` 内部是 spawnSync("pnpm", ...)。
  #   没有它，用户就装不了第三方 DSH 插件 —— 而这正是本项目要解决的核心诉求
  #   （"拿不到第三方开发的内测内容"，见 docs/dsh-profile.md §6）。
  #   用 --prefix /opt/node 让它落在 runtime 层（/opt/node/bin），**不要**落到 /usr/local，
  #   否则会被算进 dsh 层的增量里，把 Node 工具链与 DSH 版本耦合起来。
  sub "npm i -g --prefix /opt/node pnpm@${PNPM_VERSION}（dsh plugin 的硬依赖）"
  # 先带 --allow-scripts=pnpm（新版 npm 默认拦截安装脚本，pnpm 有 install.js），
  # 老版本 npm 不认这个 flag 时回退到普通安装（实测两者都能得到可用的 pnpm）。
  in_root "$RUNTIME_ROOT" /bin/bash -c "
    set -e
    export PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin
    export HOME=/root
    npm i -g --prefix /opt/node --no-audit --no-fund --allow-scripts=pnpm 'pnpm@${PNPM_VERSION}' \
      || npm i -g --prefix /opt/node --no-audit --no-fund 'pnpm@${PNPM_VERSION}'
  " || die "安装 pnpm 失败（网络？）"

  # /usr/bin/pnpm 兜底软链：无论 entry.sh 的 PATH 怎么设，`command -v pnpm` 都能命中。
  # 放在 /usr/bin（而不是 /usr/local/bin）是为了让它留在 runtime 层，不污染 dsh 层。
  ln -sfn /opt/node/bin/pnpm "$RUNTIME_ROOT/usr/bin/pnpm"

  PNPM_RESOLVED="$(in_root "$RUNTIME_ROOT" /bin/bash -c '
    set -e
    command -v pnpm >/dev/null || { echo "PATH 上找不到 pnpm" >&2; exit 1; }
    pnpm --version
    pnpm store path >/dev/null 2>&1 || { echo "pnpm store path 失败（装坏了？）" >&2; exit 1; }
    command -v npm >/dev/null || { echo "PATH 上找不到 npm" >&2; exit 1; }
  ' 2>/dev/null | tail -1)" || die "pnpm 自检失败：dsh plugin 会报 \"pnpm not found on PATH\""
  [ -n "$PNPM_RESOLVED" ] || die "拿不到 pnpm 版本号，安装可能不完整"
  sub "pnpm 自检通过：pnpm ${PNPM_RESOLVED}（/opt/node/bin/pnpm，/usr/bin/pnpm 兜底软链已建）"
  # 版本号写进文件，供 BUILD-INFO 与排错引用
  printf '%s\n' "$PNPM_RESOLVED" > "$WORK_DIR/pnpm-version.txt"

  # 硬依赖复核：DSH 运行只需要 libc6/libstdc++6/libgcc-s1（findings §6.2）
  in_root "$RUNTIME_ROOT" /bin/bash -c '
    set -e
    for so in libc.so.6 libstdc++.so.6 libgcc_s.so.1; do
      ldconfig -p 2>/dev/null | grep -q "$so" || { echo "缺少 $so —— Node/DSH 起不来" >&2; exit 1; }
    done
    /opt/node/bin/node -e "console.log(\"  Node 自检: \"+process.version+\" / \"+process.arch)"
  ' >&2 || die "runtime 层自检失败"
  ok "L1 runtime 就绪：$RUNTIME_ROOT"
}

# ------------------------------------------------------------- L2 dsh ----
build_dsh() {
  step "L2 dsh：${DSH_PACKAGE}@${DSH_VERSION} + profile 工作区"
  sub "安装落点（architecture.md §5.3）：/usr/local + /root/.dsh/profiles/web"

  rm -rf "$DSH_ROOT"
  mkdir -p "$DSH_ROOT"
  rsync -aHAX --delete "$RUNTIME_ROOT/" "$DSH_ROOT/"

  bind_mounts "$DSH_ROOT"

  # 1) DSH 主体：全局装到 /usr/local（npm prefix 已在 runtime 层设好）
  sub "npm i -g ${DSH_PACKAGE}@${DSH_VERSION}"
  in_root "$DSH_ROOT" /bin/bash -c "
    set -e
    export PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin
    export HOME=/root
    npm i -g --no-audit --no-fund '${DSH_PACKAGE}@${DSH_VERSION}'
  " || die "安装 DSH 失败（版本号不对？DNS？）"

  in_root "$DSH_ROOT" /bin/bash -c '
    set -e
    test -x /usr/local/bin/dsh || { echo "/usr/local/bin/dsh 不存在" >&2; exit 1; }
    test -f /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js || { echo "DSH 入口 lib/bin.js 缺失" >&2; exit 1; }
    echo "  dsh -> $(readlink -f /usr/local/bin/dsh)"
  ' >&2 || die "DSH 安装结果不符合 architecture.md §5.3"

  # 1.5) `dsh plugin` 前提复核：必须能看到 pnpm（否则用户装不了第三方插件）
  in_root "$DSH_ROOT" /bin/bash -c '
    set -e
    export PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin
    command -v pnpm >/dev/null || { echo "PATH 上找不到 pnpm → dsh plugin 不可用" >&2; exit 1; }
    echo "  dsh plugin 前提成立：pnpm $(pnpm --version) @ $(command -v pnpm)"
  ' >&2 || die "dsh plugin 的 pnpm 前提不成立"

  # 2) profile 工作区 —— **复用已实测的安装脚本**，不在这里内联实现
  #    rootfs/profiles/install-web-profile.sh（+ 模板 rootfs/profiles/web-profile/）
  #    已经实测跑通：11 包 / 5–6 秒 / 4 个包的 import 自检全 ok / 真启动 dsh web 成功。
  #    原理与坑见 docs/dsh-profile.md §5（含 §5.5）。
  #
  #    ⚠ 该脚本必须**在最终路径**安装：目标就是 /root/.dsh/profiles/web。
  #      它算的是**相对**软链，层级随目录深度变化 —— 先装到临时目录再 mv 会直接失效。
  #      所以这里只把"脚本 + 模板"放进 chroot 的临时目录（/tmp，不进层），
  #      安装目标始终是最终路径。
  local profile_installer="$REPO_ROOT/rootfs/profiles/install-web-profile.sh"
  local profile_template="$REPO_ROOT/rootfs/profiles/web-profile"
  [ -f "$profile_installer" ] || die "缺少 profile 安装脚本：$profile_installer"
  [ -d "$profile_template" ] || die "缺少 profile 模板目录：$profile_template"

  local build_aux="$DSH_ROOT/tmp/dshroid-profile-build"
  mkdir -p "$build_aux"
  cp -f "$profile_installer" "$build_aux/install-web-profile.sh"
  cp -a "$profile_template" "$build_aux/web-profile"
  chmod 0755 "$build_aux/install-web-profile.sh"

  sub "执行 install-web-profile.sh（npm install + 相对软链 + import 自检）"
  in_root "$DSH_ROOT" /bin/bash -c '
    set -e
    export PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin
    export HOME=/root
    export NPM_BIN=npm
    bash /tmp/dshroid-profile-build/install-web-profile.sh \
         /tmp/dshroid-profile-build/web-profile \
         /root/.dsh/profiles/web
  ' || die "profile 安装失败（脚本里有明确的中文报错，见上）"

  # 安装完立刻清掉构建辅助文件：它们不该出现在只读层里
  rm -rf "$DSH_ROOT/tmp/dshroid-profile-build"

  # 3) 可选 /root/.dsh-defaults（默认配置模板；绝不含密钥）
  if [ -n "$DHS_DEFAULTS_DIR" ]; then
    [ -d "$DHS_DEFAULTS_DIR" ] || die "--dsh-defaults-dir 不是目录：$DHS_DEFAULTS_DIR"
    mkdir -p "$DSH_ROOT/root/.dsh-defaults"
    rsync -aHA --delete "$DHS_DEFAULTS_DIR/" "$DSH_ROOT/root/.dsh-defaults/"
    sub "已并入 /root/.dsh-defaults"
  fi

  # 4) 许可与卫生检查 —— 调用共享规格里的实现（layer-spec.sh §7/§8），不再自研
  if ! layer_spec_assert_no_forbidden "$DSH_ROOT"; then
    die "许可红线检查未通过（见 layer-spec.sh §7 的 LAYER_FORBIDDEN_PATHS）。层里不得出现无许可代码与凭据。"
  fi
  sub "许可红线检查通过（layer_spec_assert_no_forbidden：无 dsh-app-integration / 无 /root/dsha-* / 无凭据）"

  # 5) 清掉构建残渣：npm 缓存、.npmrc 里的宿主机信息、临时文件
  rm -rf "$DSH_ROOT/root/.npm" "$DSH_ROOT/root/.cache" "$DSH_ROOT/tmp"/* 2>/dev/null || true

  # 6) profile 可用性自检 —— 用共享规格里的断言（layer-spec.sh §8）
  local link="$DSH_ROOT/root/.dsh/profiles/web/node_modules/@deepseek-ai"
  if ! layer_spec_assert_profile_symlink "$DSH_ROOT$LAYER_DSH_PROFILE_DIR"; then
    die "profile 软链断言未通过（见上）。profile 失效会导致 DSH 插件解析失败。"
  fi
  local n
  n="$(find "$link" -maxdepth 1 -mindepth 1 2>/dev/null | wc -l)"
  [ "$n" -ge 50 ] || warn "软链目标下只有 $n 个包，预期 ≥ 50（疑点，请人工确认）"
  sub "@deepseek-ai → $(readlink "$link")（相对路径 ✓，目标下有 $n 个包）"
  # profile 的落点必须与 architecture.md §5.3 一致（运行时不联网装插件）
  for f in package.json cordis.yml; do
    [ -f "$DSH_ROOT/root/.dsh/profiles/web/$f" ] || die "profile 缺少 $f（install-web-profile.sh 没跑成功？）"
  done

  if [ -f "$DSH_ROOT/root/.dsh/.credentials.yaml" ] || [ -f "$DSH_ROOT/root/.dsh/settings.yaml" ]; then
    die "层里出现了用户状态文件（.credentials.yaml / settings.yaml）—— 绝不允许打进只读层"
  fi
  ok "L2 dsh 就绪：$DSH_ROOT"
}

# --------------------------------------------------- 层间增量 diff 打包 ----
# 用 rsync --compare-dest：只把「与上一层不同或新增」的文件复制进 staging。
# 注意：**不做删除**（overlay 只读 lower 无法表达 whiteout），因此这里没有 --delete。
make_layer_stage() { # make_layer_stage <上一层的完整树|空> <当前完整树> <staging 目标> <层名>
  local prev="$1" cur="$2" stage="$3" label="$4"
  rm -rf "$stage"; mkdir -p "$stage"

  local ex=(); while IFS= read -r e; do ex+=("$e"); done < <(rsync_excludes)

  if [ -z "$prev" ]; then
    sub "$label：整层打包（无上一层）"
    rsync -aHAX --numeric-ids "${ex[@]}" "$cur/" "$stage/"
  else
    sub "$label：相对上一层做增量（rsync --compare-dest）"
    rsync -aHAX --numeric-ids "${ex[@]}" --compare-dest="$prev" "$cur/" "$stage/"
  fi

  # 删除检测：上一层有、这一层没有的文件 —— overlay 里"删不掉"，必须报警
  if [ -n "$prev" ]; then
    local plist="$WORK_DIR/.prev.list" clist="$WORK_DIR/.cur.list" dlist="$WORK_DIR/.del.list"
    ( cd "$prev" && find . \( -type f -o -type l \) | LC_ALL=C sort ) > "$plist"
    ( cd "$cur"  && find . \( -type f -o -type l \) | LC_ALL=C sort ) > "$clist"
    LC_ALL=C comm -23 "$plist" "$clist" > "$dlist" || true
    local delcount; delcount="$(wc -l < "$dlist" | tr -d ' ')"
    if [ "$delcount" -gt 0 ]; then
      warn "$label 删除了上一层的 $delcount 个文件 —— overlay 只读 lower 无法表达 whiteout，"
      warn "合并视图里它们**依然存在**。示例（最多 5 个）："
      head -5 "$dlist" | sed 's/^/        /' >&2
      warn "正确做法：改 base 层配方（所有裁剪只在 base 做）并重发 base 层。"
      if [ "$STRICT_NO_DELETIONS" -eq 1 ]; then
        die "--strict-no-deletions：$label 存在删除，已中止。"
      fi
    else
      sub "$label：与上一层相比无删除（符合 overlay 语义）"
    fi
  fi

  local n size
  n="$(find "$stage" -type f 2>/dev/null | wc -l | tr -d ' ')"
  size="$(du -sh "$stage" 2>/dev/null | awk '{print $1}')"
  sub "$label：staging 含 $n 个文件，解包后 $size"
}

# mkfs.erofs 的**能力探测**：宿主是 erofs-utils 1.7.1，设备上是 Android 自带版本，
# 参数集不一定一致（例如 Android 版未必认 -x/-U 等）。所以这里逐项试一下，
# 不支持的选项就降级丢掉，绝不硬编码一整套宿主专属参数。
mkfs_erofs_supports() { # mkfs_erofs_supports <选项...> → 0 支持 / 1 不支持
  local probe_dir probe_img rc=0
  probe_dir="$(mktemp -d "$WORK_DIR/.mkfs-probe.XXXXXX")"
  probe_img="$probe_dir.img"
  printf 'probe' > "$probe_dir/f"
  if mkfs.erofs "$@" "$probe_img" "$probe_dir" >/dev/null 2>&1; then rc=0; else rc=1; fi
  rm -rf "$probe_dir" "$probe_img"
  return $rc
}

# 探测一次并缓存结果（整个构建只探一次，避免每个层都试）
probe_mkfs_capabilities() {
  [ -n "$MKFS_ARGS_SUPPORTED" ] && return 0
  MKFS_ARGS_SUPPORTED=""
  if mkfs_erofs_supports -b "$EROFS_BLOCK_SIZE"; then
    MKFS_ARGS_SUPPORTED="$MKFS_ARGS_SUPPORTED -b:$EROFS_BLOCK_SIZE"
  else
    warn "本机 mkfs.erofs 不支持 -b $EROFS_BLOCK_SIZE（用默认块大小）"
  fi
  if mkfs_erofs_supports --all-root; then
    MKFS_ARGS_SUPPORTED="$MKFS_ARGS_SUPPORTED --all-root"
  else
    warn "本机 mkfs.erofs 不支持 --all-root（宿主 uid 会进镜像；不影响功能）"
  fi
  if mkfs_erofs_supports --quiet; then MKFS_ARGS_SUPPORTED="$MKFS_ARGS_SUPPORTED --quiet"; fi
  if [ "$EROFS_COMPRESS" != "none" ]; then
    if mkfs_erofs_supports -z "$EROFS_COMPRESS"; then
      MKFS_ARGS_SUPPORTED="$MKFS_ARGS_SUPPORTED -z:$EROFS_COMPRESS"
    else
      die "本机 mkfs.erofs 不支持 -z $EROFS_COMPRESS（可用 --erofs-compress none）"
    fi
  fi
  if [ "$REPRODUCIBLE" -eq 1 ]; then
    local ts="${SOURCE_DATE_EPOCH:-1700000000}"
    if mkfs_erofs_supports -T "$ts"; then MKFS_ARGS_SUPPORTED="$MKFS_ARGS_SUPPORTED -T:$ts"
    else warn "本机 mkfs.erofs 不支持 -T（可重现构建的时间戳固定失效）"; fi
    if mkfs_erofs_supports -U "00000000-0000-0000-0000-000000000000"; then
      MKFS_ARGS_SUPPORTED="$MKFS_ARGS_SUPPORTED -U:00000000-0000-0000-0000-000000000000"
    else warn "本机 mkfs.erofs 不支持 -U（UUID 固定失效）"; fi
  fi
  if [ "$STRIP_XATTR" -eq 1 ]; then
    if mkfs_erofs_supports -x-1; then MKFS_ARGS_SUPPORTED="$MKFS_ARGS_SUPPORTED -x:-1"
    else warn "本机 mkfs.erofs 不支持 -x-1（--strip-xattr 无效，保留 xattr）"; fi
  fi
  info "mkfs.erofs 可用参数：${MKFS_ARGS_SUPPORTED:-（仅默认）}"
}

# 把缓存的 "选项:值" 列表展开成真正的参数数组
mkfs_args_array() {
  local out=() item opt val
  for item in $MKFS_ARGS_SUPPORTED; do
    opt="${item%%:*}"; val="${item#*:}"
    out+=("$opt")
    [ "$opt" = "$item" ] || out+=("$val")   # 没有冒号说明该选项不带值
  done
  printf '%s\n' "${out[@]}"
}

pack_erofs() { # pack_erofs <staging> <输出镜像> <层名>
  local stage="$1" out="$2" label="$3"
  probe_mkfs_capabilities
  local args=(); while IFS= read -r a; do [ -n "$a" ] && args+=("$a"); done < <(mkfs_args_array)
  local comp_note="镜像不压缩（不传 -z；内核只支持 LZ4，见 layer-spec.sh §2）"
  [ "$EROFS_COMPRESS" != "none" ] && comp_note="镜像内压缩 = $EROFS_COMPRESS"
  sub "$label：mkfs.erofs（$comp_note）"
  # ⚠ EROFS 保留符号链接（默认行为）：profile 的 @deepseek-ai 就是相对软链，
  #   一旦展平/丢弃，profile 直接失效（docs/dsh-profile.md §5.4）。
  mkfs.erofs "${args[@]}" "$out" "$stage" >"$WORK_DIR/mkfs.erofs-$label.log" 2>&1 \
    || { cat "$WORK_DIR/mkfs.erofs-$label.log" >&2; die "$label mkfs.erofs 失败"; }

  # 打包后立刻做完整性校验（fsck.erofs 会遍历并验证所有 inode/extent 的编码）
  fsck.erofs "$out" >>"$WORK_DIR/mkfs.erofs-$label.log" 2>&1 \
    || { cat "$WORK_DIR/mkfs.erofs-$label.log" >&2; die "$label 的 EROFS 镜像 fsck 失败"; }
  sub "$label：fsck.erofs 通过"
}

# 分发压缩：<id>-<ver>.erofs → <id>-<ver>.erofs.zst（主）+ <id>-<ver>.erofs.gz（回退）
# 命名一律走 layer-spec.sh 的 layer_transport_name，保证与 device-provision.sh 一致。
# 用法：pack_transport <镜像> <层id> <版本> → stdout 打印 "主产物<TAB>回退产物"
pack_transport() {
  local img="$1" id="$2" ver="$3"
  local primary="" fallback=""

  if [ -n "$ZSTD_CMD" ]; then
    primary="$OUT_DIR/$(layer_transport_name "$id" "$ver" "$TRANSPORT_PRIMARY")"
    sub "$(basename "$img") → $(basename "$primary")（$TRANSPORT_PRIMARY -$TRANSPORT_LEVEL）"
    $ZSTD_CMD < "$img" > "$primary.part" || die "主分发压缩失败：$(basename "$img")"
    mv "$primary.part" "$primary"
  fi
  if [ -n "$GZIP_CMD" ]; then
    fallback="$OUT_DIR/$(layer_transport_name "$id" "$ver" "$TRANSPORT_FALLBACK")"
    sub "$(basename "$img") → $(basename "$fallback")（$TRANSPORT_FALLBACK，给设备侧纯 CLI 用）"
    $GZIP_CMD < "$img" > "$fallback.part" || die "回退分发压缩失败：$(basename "$img")"
    mv "$fallback.part" "$fallback"
  fi
  [ -n "$primary" ] || die "没有产出任何主分发产物（zstd 不可用？）"
  printf '%s\t%s\n' "$primary" "$fallback"
}

# 打包后验证：确认 profile 的相对软链在 EROFS 里仍然活着。
# 两条路，优先用轻量的那条：
#   ① dump.erofs --path=<软链路径>：输出里有 "symlink file" 且 Size = 软链目标长度
#      （宿主 erofs-utils 实测可用；Android 版 dump.erofs 未必有 --path，探测后降级）
#   ② fsck.erofs --extract：把镜像解出来，再用 layer-spec.sh 的断言核对（慢但一定准）
verify_erofs_profile() { # verify_erofs_profile <dsh.erofs> <staging 里对应的 profile 目录>
  local img="$1" stage_profile="$2"
  local link_path="$LAYER_DSH_PROFILE_DIR/node_modules/@deepseek-ai"

  if need dump.erofs && dump.erofs --path="$link_path" "$img" >"$WORK_DIR/dump-erofs.log" 2>&1; then
    local want_len; want_len="$(readlink "$stage_profile/node_modules/@deepseek-ai" | wc -c)"
    want_len=$((want_len - 1))   # 去掉 readlink 输出末尾的换行
    local got_len
    got_len="$(awk '/^Size:/ {print $2; exit}' "$WORK_DIR/dump-erofs.log")"
    if grep -q 'symlink file' "$WORK_DIR/dump-erofs.log" && [ "$got_len" = "$want_len" ]; then
      ok "打包后验证（dump.erofs）：$link_path 仍是软链，目标长度 $got_len 与 staging 一致"
      return 0
    fi
    warn "dump.erofs 的检查结果可疑（symlink/size 不匹配），改用解包核对"
    sed 's/^/        /' "$WORK_DIR/dump-erofs.log" >&2
  else
    warn "dump.erofs 不可用或不支持 --path，改用 fsck.erofs --extract 核对"
  fi

  local xdir="$WORK_DIR/verify-erofs"
  rm -rf "$xdir"; mkdir -p "$xdir"
  if ! fsck.erofs --extract="$xdir" --overwrite "$img" >"$WORK_DIR/fsck-extract.log" 2>&1; then
    cat "$WORK_DIR/fsck-extract.log" >&2
    warn "本机 fsck.erofs 不支持 --extract，无法在打包后核对软链（staging 里的断言已通过）"
    return 0
  fi
  local profile="$xdir$LAYER_DSH_PROFILE_DIR"
  [ -d "$profile" ] || die "EROFS 镜像里没有 $LAYER_DSH_PROFILE_DIR（profile 没打进去？）"
  if layer_spec_assert_profile_symlink "$profile"; then
    ok "打包后验证（解包）：EROFS 里的 profile 相对软链完好（$(readlink "$profile/node_modules/@deepseek-ai")）"
  else
    die "打包后验证失败：EROFS 镜像里 profile 的 @deepseek-ai 软链有问题 —— profile 会失效"
  fi
  rm -rf "$xdir"
}

# ================================================================ 主流程 ====
printf '\n%s' "$C_I" >&2
cat >&2 <<EOF
════════════════════════════════════════════════════════════════════════
 dshroid 三层 rootfs 构建（宿主/CI 真 chroot 路径）
   目标架构 : $ARCH     宿主架构 : $HOST_ARCH     跨架构 : $CROSS
   Ubuntu   : $UBUNTU_VERSION ($UBUNTU_SUITE)
   DSH      : ${DSH_PACKAGE}@${DSH_VERSION:-<由 dist-tag ${DSH_DIST_TAG} 解析>}
   Node     : ${NODE_VERSION:-<自动取 v${NODE_MAJOR} 最新 LTS>}
   镜像压缩 : $EROFS_COMPRESS        分发压缩 : $TRANSPORT_PRIMARY（级别 $TRANSPORT_LEVEL）${GZIP_CMD:+ + 回退 $TRANSPORT_FALLBACK}
   镜像格式 : EROFS -b $EROFS_BLOCK_SIZE（layer-spec.sh）  并行度 : $JOBS
   工作目录 : $WORK_DIR
   产物目录 : $OUT_DIR
════════════════════════════════════════════════════════════════════════
EOF
printf '%s' "$C_R" >&2

resolve_dsh_version
resolve_node_version
write_customize_hook

# ---- 构建三棵完整树（L0 ⊂ L1 ⊂ L2）----
if [ "$SKIP_BASE" -eq 1 ] && [ -d "$BASE_ROOT" ]; then
  warn "跳过 base 构建，复用 $BASE_ROOT"
else
  build_base
fi
if [ "$SKIP_RUNTIME" -eq 1 ] && [ -d "$RUNTIME_ROOT" ]; then
  warn "跳过 runtime 构建，复用 $RUNTIME_ROOT"
else
  build_runtime
fi
if [ "$SKIP_DSH" -eq 1 ] && [ -d "$DSH_ROOT" ]; then
  warn "跳过 dsh 构建，复用 $DSH_ROOT"
else
  build_dsh
fi

# ---- 环境入口脚本 → **runtime 层** $LAYER_RUNTIME_ENTRY_DIR（layer-spec.sh §6）----
# 为什么放只读层而不是可写上层：`linuxctl reset` 会清空可写层，
# entry.sh / supervise.sh 若在其中就会一起消失，环境再也起不来。
# 为什么是 runtime 层而不是 base 层：它们的变更频率属于"运行时"，不是 OS 基线。
if [ "$RUNTIME_DIR" = "auto" ]; then
  if [ -d "$REPO_ROOT/runtime/root" ]; then
    RUNTIME_DIR="$REPO_ROOT/runtime/root"
  else
    RUNTIME_DIR=""
  fi
fi
if [ -n "$RUNTIME_DIR" ]; then
  [ -d "$RUNTIME_DIR" ] || die "--runtime-dir 不是目录：$RUNTIME_DIR"
  step "并入环境入口脚本 → runtime 层 $LAYER_RUNTIME_ENTRY_DIR"
  mkdir -p "$RUNTIME_ROOT$LAYER_RUNTIME_ENTRY_DIR" "$DSH_ROOT$LAYER_RUNTIME_ENTRY_DIR"
  rsync -aHA --delete "$RUNTIME_DIR/" "$RUNTIME_ROOT$LAYER_RUNTIME_ENTRY_DIR/"
  rsync -aHA --delete "$RUNTIME_DIR/" "$DSH_ROOT$LAYER_RUNTIME_ENTRY_DIR/"
  chmod 0755 "$RUNTIME_ROOT$LAYER_RUNTIME_ENTRY_DIR"/*.sh 2>/dev/null || true
  sub "已复制 $(find "$RUNTIME_ROOT$LAYER_RUNTIME_ENTRY_DIR" -type f | wc -l | tr -d ' ') 个文件到 runtime 层"
  [ -f "$RUNTIME_ROOT$LAYER_RUNTIME_ENTRY" ] || warn "runtime 层里没有 $LAYER_RUNTIME_ENTRY（环境入口缺失）"
else
  warn "未找到环境入口脚本目录（runtime/root 为空且未给 --runtime-dir）"
  warn "→ runtime 层里不会有 $LAYER_RUNTIME_ENTRY_DIR/{entry.sh,supervise.sh}（layer-spec.sh §6 要求有）"
fi

# ---- 生成增量 staging 并打包 ----
step "生成层间增量并打包 EROFS"

# 命名一律走 layer-spec.sh —— 保证与 device-provision.sh 产出的层同名同内容
BASE_IMG="$OUT_DIR/$(layer_file_name base "$BASE_VERSION")"
RUNTIME_IMG="$OUT_DIR/$(layer_file_name runtime "$RUNTIME_VERSION")"
DSH_IMG="$OUT_DIR/$(layer_file_name dsh "$DSH_VERSION")"

make_layer_stage ""            "$BASE_ROOT"    "$WORK_DIR/stage-base"    "L0 base"
make_layer_stage "$BASE_ROOT"  "$RUNTIME_ROOT" "$WORK_DIR/stage-runtime" "L1 runtime"
make_layer_stage "$RUNTIME_ROOT" "$DSH_ROOT"   "$WORK_DIR/stage-dsh"     "L2 dsh"

pack_erofs "$WORK_DIR/stage-base"    "$BASE_IMG"    "base"
pack_erofs "$WORK_DIR/stage-runtime" "$RUNTIME_IMG" "runtime"
pack_erofs "$WORK_DIR/stage-dsh"     "$DSH_IMG"     "dsh"

# 打包后验证：EROFS 里 profile 的相对软链必须完好（失效则 DSH 起不来）
verify_erofs_profile "$DSH_IMG" "$WORK_DIR/stage-dsh$LAYER_DSH_PROFILE_DIR"

# 分发压缩：频道里发布的是 <name>.erofs.zst（不是裸镜像）
step "生成分发产物（主 $TRANSPORT_PRIMARY${GZIP_CMD:+ + 回退 $TRANSPORT_FALLBACK}）"
# pack_transport 输出 "主产物<TAB>回退产物"
BASE_TX_LINE="$(pack_transport "$BASE_IMG" base "$BASE_VERSION")"
RUNTIME_TX_LINE="$(pack_transport "$RUNTIME_IMG" runtime "$RUNTIME_VERSION")"
DSH_TX_LINE="$(pack_transport "$DSH_IMG" dsh "$DSH_VERSION")"
BASE_TX="${BASE_TX_LINE%%$'\t'*}";  BASE_TX_GZ="${BASE_TX_LINE#*$'\t'}"
RUNTIME_TX="${RUNTIME_TX_LINE%%$'\t'*}"; RUNTIME_TX_GZ="${RUNTIME_TX_LINE#*$'\t'}"
DSH_TX="${DSH_TX_LINE%%$'\t'*}";    DSH_TX_GZ="${DSH_TX_LINE#*$'\t'}"
for gz in "$BASE_TX_GZ" "$RUNTIME_TX_GZ" "$DSH_TX_GZ"; do
  if [ -n "$gz" ] && [ ! -f "$gz" ]; then warn "回退产物缺失：$gz"; fi
done

# ---- 汇总 ----
step "构建汇总"
human() { numfmt --to=iec --suffix=B "$1" 2>/dev/null || echo "$1 B"; }
size_of() { stat -c %s "$1" 2>/dev/null || stat -f %z "$1"; }

printf '  %-8s %-16s %11s %11s %11s  %s\n' "层" "版本" "下载(.zst)" "回退(.gz)" "裸镜像" "sha256(主产物)" >&2
TOTAL_IMG=0; TOTAL_TX=0; TOTAL_GZ=0
for quad in "base:$BASE_IMG:$BASE_TX:$BASE_TX_GZ:$BASE_VERSION" \
            "runtime:$RUNTIME_IMG:$RUNTIME_TX:$RUNTIME_TX_GZ:$RUNTIME_VERSION" \
            "dsh:$DSH_IMG:$DSH_TX:$DSH_TX_GZ:$DSH_VERSION"; do
  IFS=: read -r id img tx txgz ver <<< "$quad"
  si="$(size_of "$img")"; stx="$(size_of "$tx")"
  TOTAL_IMG=$((TOTAL_IMG + si)); TOTAL_TX=$((TOTAL_TX + stx))
  local_gz="—"
  if [ -n "$txgz" ] && [ -f "$txgz" ]; then
    local_gz="$(human "$(size_of "$txgz")")"
    TOTAL_GZ=$((TOTAL_GZ + $(size_of "$txgz")))
  fi
  printf '  %-8s %-16s %11s %11s %11s  %s\n' \
    "$id" "$ver" "$(human "$stx")" "$local_gz" "$(human "$si")" \
    "$(sha256sum "$tx" | cut -c1-16)…" >&2
done
printf '  %-8s %-16s %11s %11s %11s\n' "合计" "" "$(human "$TOTAL_TX")" \
  "$([ "$TOTAL_GZ" -gt 0 ] && human "$TOTAL_GZ" || echo '—')" "$(human "$TOTAL_IMG")" >&2
if [ "$TOTAL_IMG" -gt 0 ] && [ "$TOTAL_TX" -gt 0 ]; then
  printf '  主产物压缩比：%.2f×（裸镜像 %s → 下载 %s）\n' \
    "$(echo "$TOTAL_IMG $TOTAL_TX" | awk '{print $1/$2}')" "$(human "$TOTAL_IMG")" "$(human "$TOTAL_TX")" >&2
fi

# ---- 构建信息落盘（可追溯）----
BUILD_INFO="$OUT_DIR/BUILD-INFO.txt"
{
  echo "dshroid 层构建信息"
  echo "构建时间（UTC）: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "构建主机       : $(uname -srm)  架构=$HOST_ARCH  目标=$ARCH  跨架构=$CROSS"
  echo "Ubuntu         : $UBUNTU_VERSION ($UBUNTU_SUITE)"
  echo "DSH            : ${DSH_PACKAGE}@${DSH_VERSION}（dist-tag=${DSH_DIST_TAG}）"
  echo "Node           : ${NODE_VERSION}"
  echo "pnpm           : ${PNPM_RESOLVED:-$PNPM_VERSION}（dsh plugin 的硬依赖，落在 runtime 层 /opt/node/bin）"
  echo "镜像格式       : EROFS（layer-spec.sh LAYER_FS=erofs）；块大小 $EROFS_BLOCK_SIZE"
  echo "镜像内压缩     : $EROFS_COMPRESS（none = 不传 -z）"
  echo "分发压缩       : 主 $TRANSPORT_PRIMARY（级别 $TRANSPORT_LEVEL）${GZIP_CMD:+，回退 $TRANSPORT_FALLBACK}"
  echo "mkfs.erofs 参数: ${MKFS_ARGS_SUPPORTED:-（默认）}   [能力探测后得到]"
  echo "base 包清单    : rootfs/profiles/base.packages（$(count_packages "$BASE_PKGS_FILE") 项）"
  echo "runtime 包清单 : rootfs/profiles/runtime.packages（$(count_packages "$RUNTIME_PKGS_FILE") 项）"
  echo "profile bundles: @deepseek-ai/dsh-base, @deepseek-ai/dsh-web-app, dsh-web-mobile, dsh-task-notifier"
  echo "许可红线       : 不含 dsh-app-integration（无 license）；不含 /root/dsha-*；不含凭据"
  echo "层间语义       : 只含增量，不做删除（overlay 只读 lower 无 whiteout）"
  echo
  echo "产物:"
  for img in "$BASE_IMG" "$RUNTIME_IMG" "$DSH_IMG"; do
    echo "  镜像 $(basename "$img")  size=$(size_of "$img")  sha256=$(sha256sum "$img" | awk '{print $1}')"
  done
  for tx in "$BASE_TX" "$RUNTIME_TX" "$DSH_TX"; do
    echo "  分发(主) $(basename "$tx")  size=$(size_of "$tx")  sha256=$(sha256sum "$tx" | awk '{print $1}')"
  done
  for tx in "$BASE_TX_GZ" "$RUNTIME_TX_GZ" "$DSH_TX_GZ"; do
    [ -n "$tx" ] && [ -f "$tx" ] || continue
    echo "  分发(回退) $(basename "$tx")  size=$(size_of "$tx")  sha256=$(sha256sum "$tx" | awk '{print $1}')"
  done
} > "$BUILD_INFO"
ok "构建信息：$BUILD_INFO"

# ---- 可选：生成并签名 channel.json（一次跑完"构建 → 可发布"）----
if [ -n "$SIGN_KEY" ]; then
  step "生成并签名 channel.json"
  need node || die "--sign-key 需要宿主有 node（用来跑 tools/channel/*.mjs）"
  [ -f "$SIGN_KEY" ] || die "--sign-key 指向的私钥不存在：$SIGN_KEY"
  MODE_ARGS=()
  if [ -n "$BASE_URL" ]; then MODE_ARGS+=(--base-url "$BASE_URL"); fi
  # 版本是显式指定的（--dsh-version）时不能拿 dist-tag 去比对，否则 --strict 会误杀；
  # --offline 只跳过那次联网比对，dist_tag 字段照旧写进清单。
  if [ "$DSH_VERSION_FROM_TAG" -eq 1 ]; then
    MODE_ARGS+=(--dsh-dist-tag "$DSH_DIST_TAG")
  else
    MODE_ARGS+=(--dsh-dist-tag "$DSH_DIST_TAG" --offline)
  fi
  node "$REPO_ROOT/tools/channel/gen-manifest.mjs" \
      --dir "$OUT_DIR" --name "$CHANNEL_NAME" --strict "${MODE_ARGS[@]}" >/dev/null \
    || die "gen-manifest 失败"
  node "$REPO_ROOT/tools/channel/sign.mjs" \
      --in "$OUT_DIR/channel.json" --key "$SIGN_KEY" >/dev/null \
    || die "签名失败"
  node "$REPO_ROOT/tools/channel/verify.mjs" \
      --pub "$(dirname "$SIGN_KEY")/channel.pub" --in "$OUT_DIR/channel.json" --dir "$OUT_DIR" --strict \
    || die "自验失败：channel.json 的签名或层文件 sha256 校验没过，不要发布"
  ok "channel.json 已生成、签名并自验通过：$OUT_DIR/channel.json"
  {
    echo
    echo "频道清单:"
    echo "  channel.json      sha256=$(sha256sum "$OUT_DIR/channel.json" | awk '{print $1}')"
    echo "  channel.json.sig  $(wc -c < "$OUT_DIR/channel.json.sig") 字节"
    echo "  主分发  = $TRANSPORT_PRIMARY（App 走这条）"
    echo "  回退分发 = ${GZIP_CMD:+$TRANSPORT_FALLBACK（设备侧纯 CLI 走这条）}"
  } >> "$BUILD_INFO"
  sub "接下来把 $OUT_DIR 下所有文件上传到静态托管，并把 URL + 公钥 + 指纹发给用户"
  sub "用户侧加入频道：dshroid-channel channels add --id <id> --url <URL> --pub <base64>"
fi

printf '\n%s' "$C_K" >&2
ok "全部完成。产物目录：$OUT_DIR"
printf '%s' "$C_R" >&2
[ "$KEEP_WORK" -eq 1 ] || info "中间树已清理（--keep-work 可保留，供 --skip-* 续跑）"
