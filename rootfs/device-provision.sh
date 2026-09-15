#!/system/bin/sh
# =============================================================================
# sunsetlinux · rootfs/device-provision.sh
#
# **设备侧原生 provisioning（主路径）**。由真 root（KernelSU）在 Android 上直接运行，
# 不依赖宿主/CI，也不用 proot/ptrace。
#
# 为什么要设备侧原生做（docs/findings.md §4.1）：
#   宿主侧（本开发机）处于 proot 里，**没有 CAP_SYS_CHROOT**，真 chroot 做不到；
#   fakechroot+apt 又被实测证明不可信（env 的 PATH 查找会逃出 chroot，误伤宿主）。
#   → 只有真 root 在设备上 chroot 才靠谱。
#
# 它做什么（每一步都可重复执行）：
#   1. 建 /data/sunsetlinux/{layers,seeds,cache,etc,bin,run,snapshots,upper,work,rootfs,layers-mnt}
#   2. 从 seeds/ 取 ubuntu-base tarball（缺则打印下载 URL），解到 rootfs-provision/
#   3. **一次真 chroot** 内顺序完成三层构建：
#        base   : apt 源 + apt-get update + base.packages + 裁剪
#        runtime: Node 官方 arm64 静态包（/opt/node）+ pnpm + runtime.packages
#        dsh    : npm i -g @deepseek-ai/dsh + profile 工作区（web-profile 模板）
#      层间用「文件清单比对」切出增量：每层都从同一 base 解包（干净起点），
#      阶段前后各生成一份 (dev,inode,size,mtime) 清单，取差集 = 本层内容。
#      （不用 find -newerct：toybox 的 find 不支持，且 tar/dpkg 会保留旧 mtime）
#   4. 每层用 mkfs.erofs（或 mksquashfs）打成只读镜像
#   5. 创建 upper.img（ext4 稀疏，默认 8 GiB）
#   6. 写 etc/config.json 与 etc/state.json
#
# 关键实测约束（务必遵守，改脚本前先读）：
#   ★ 内核**不支持 squashfs**（CONFIG_SQUASHFS is not set，/proc/filesystems 无 squashfs）
#     → 只读层默认用 **EROFS**（CONFIG_EROFS_FS=y，内核内建）。这是本脚本与
#       architecture.md §2.1 曾写 "*.squashfs" 命名，现由 rootfs/layer-spec.sh 统一定义
#       为 <id>-<semver>.erofs + 可选传输压缩 <id>-<semver>.erofs.zst。
#   ★ 层间**不做删除**（overlay 只读 lower 无法表达 whiteout）→ 所有裁剪只在 base 做一次。
#   ★ 不动 DSHA 的任何私有代码：只从公开 npm 取 @deepseek-ai/dsh 与两个 MIT 插件
#     （dsh-web-mobile / dsh-task-notifier）。**绝不**碰 /root/dsha-* 与 dsh-app-integration。
#   ★ chroot 内所有命令用绝对路径，避免 findings §4.1 记录的"PATH 逃出 chroot"问题。
#
# 用法：
#   sh device-provision.sh [选项]
#     --seeds <dir>       种子目录（默认 $LINUX_HOME/seeds）
#     --node <version>    Node 版本，如 v24.21.0（默认见 NODE_VERSION）
#     --dsh <tag>         DSH npm dist-tag 或版本（默认 latest；可用 next/alpha/0.1.5-rc.2）
#     --upper-mb <MB>     可写层大小（默认 8192）
#     --force             已存在同名层也重建
#     --skip-<layer>      跳过某层（base/runtime/dsh）
#     --clean             构建前删除临时 chroot（保留层与 upper.img）
#     -h|--help
#
# 环境变量：LINUX_HOME / NODE_VERSION / DSH_NPM_TAG / UPPER_SIZE_MB / LAYER_FORMAT(erofs|squashfs)
#
# ★ 解释器要求：**/system/bin/sh（mksh）即可，不需要 bash**。
#   2026-09-16 实测：真机上**没有 Termux、没有 /system/bin/bash**，原先那句
#   "内部要求 bash，请用 Termux 的 bash 跑" 等于把设备侧首次部署彻底堵死。
#   本脚本现在只用 mksh 也支持的东西；唯一两处 bash 专有写法已清掉：
#     · `[ -z "$BASH_VERSION" ] → exec bash` 的自我再执行守卫（已删）
#     · `declare -f` 把函数文本转储进内层脚本（bash 专有；mksh 下
#       "declare: inaccessible or not found"）→ 改成内层脚本 exec 本文件 `--inner-run`
#   闸门：tools/shell-compat-check.mjs 管"能不能解析"，tools/provision-selftest.mjs 管
#   "在 mksh 下真的跑得起来"（行为级冒烟，两者缺一不可）。
# =============================================================================
set -euo pipefail

# ===========================================================================
# 0. 基础
# ===========================================================================
SELF_PATH="${BASH_SOURCE[0]:-$0}"
# 用**纯 shell** 拆目录，不调 dirname/basename：设备上它们是 toybox applet，
# 命令行细节（比如 `--`）没必要赌；`${var%/*}` / `${var##*/}` 在 mksh 与 bash 里一致。
case "$SELF_PATH" in
    */*) SELF_DIR_RAW="${SELF_PATH%/*}" ;;
    *)   SELF_DIR_RAW="." ;;
esac
SELF_DIR="$(cd "$SELF_DIR_RAW" && pwd -P)"
# SELF_PATH 必须是**绝对路径**：内层引导脚本要 exec 它（那时工作目录可能已经不同），
# sync_scripts 也要把它拷进 $LH/bin。用相对路径调用本脚本（`sh rootfs/…`）时同样要成立。
SELF_PATH="$SELF_DIR/${SELF_PATH##*/}"

# 设备上跑本脚本的 shell：Android 上是 /system/bin/sh（mksh）。开发机/CI 上没有它，
# 依次退回 mksh / bash —— **故意不退回 dash**：它没有 `set -o pipefail`，也理解不了
# 本脚本用的数组，跑起来是立刻炸而不是降级。用途有二：① --dump-inner 的语法自检
# ② 没有 unshare 时的兜底执行（以及内层引导脚本 exec 的目标）。
SH_BIN=""
for _shcand in /system/bin/sh mksh bash; do
    case "$_shcand" in
        /*) [ -x "$_shcand" ] && { SH_BIN="$_shcand"; break; } ;;
        *)  _shp="$(command -v "$_shcand" 2>/dev/null || true)"
            [ -n "$_shp" ] && { SH_BIN="$_shp"; break; } ;;
    esac
done
[ -n "$SH_BIN" ] || SH_BIN="sh"

# LINUX_HOME 必须**先定义**：下面的 layer-spec 查找会把它当作候选项之一
LINUX_HOME="${LINUX_HOME:-/data/sunsetlinux}"
LH="$LINUX_HOME"

# ---------------------------------------------------------------------------
# 共享层规格：**唯一共同事实源**（rootfs/layer-spec.sh）
#   build-layers.sh（宿主/CI 发布构建）与本脚本（设备侧原生构建）都必须 source 它，
#   不得各自内联定义层内容、命名、压缩参数或版本格式 —— 两条路径产出的层必须可比对。
# 找不到时不静默：给出可读错误。但也允许在 $LH/bin/common 里找（安装后的位置）。
# ---------------------------------------------------------------------------
LAYER_SPEC_FILE=""
for _cand in "$SELF_DIR/layer-spec.sh" "$SELF_DIR/../layer-spec.sh" "$SELF_DIR/../../rootfs/layer-spec.sh" \
             "$LINUX_HOME/bin/common/layer-spec.sh" "$LINUX_HOME/bin/layer-spec.sh"; do
    [ -f "$_cand" ] && { LAYER_SPEC_FILE="$_cand"; break; }
done
if [ -z "$LAYER_SPEC_FILE" ]; then
    printf 'device-provision: 找不到 rootfs/layer-spec.sh（分层规格的唯一共同事实源），拒绝继续。\n' >&2
    printf '  已查找：$SELF_DIR/layer-spec.sh、../layer-spec.sh、../../rootfs/layer-spec.sh、$LINUX_HOME/bin/common/\n' >&2
    exit 1
fi
# shellcheck source=/dev/null
. "$LAYER_SPEC_FILE"

LAYERS_DIR="$LH/layers"
LAYERS_MNT="$LH/layers-mnt"
# ★ 允许从环境继承：内层（--inner-run）是**重新执行本文件**，外层用 --seeds 指定的目录
#   只能靠环境变量传进来；写成无条件赋值会把 --seeds 悄悄吞掉（真机表现为"种子在却被报缺少"）。
SEEDS_DIR="${SEEDS_DIR:-$LH/seeds}"
CACHE_DIR="$LH/cache"
ETC_DIR="$LH/etc"
BIN_DIR="$LH/bin"
RUN_DIR="$LH/run"
SNAP_DIR="$LH/snapshots"
UPPER_DIR="$LH/upper"
WORK_DIR="$LH/work"
ROOTFS_DIR="$LH/rootfs"           # 运行时 overlay 的合并点（空目录，构建期不用）
BUILD_DIR="$LH/cache/rootfs-provision"   # 构建期真正 chroot 进去的目录

LOG_FILE="$LH/cache/provision.log"
# 阶段基线清单（"before"）：内层每个阶段开始时生成，build_layer 用它切增量。
# 允许从环境继承 —— 外层生成的引导脚本会把同一个路径导进来。
MANIFEST_BEFORE="${MANIFEST_BEFORE:-$CACHE_DIR/.manifest-before}"

NODE_VERSION="${NODE_VERSION:-v24.21.0}"
DSH_NPM_TAG="${DSH_NPM_TAG:-latest}"
DSH_PACKAGE="@deepseek-ai/dsh"
UPPER_SIZE_MB="${UPPER_SIZE_MB:-8192}"
LAYER_FORMAT="${LAYER_FORMAT:-$LAYER_FS}"  # 由 layer-spec 决定（erofs）；squashfs 在本机内核挂不了
# EROFS_COMPRESS 由 layer-spec 提供，默认 none（镜像内不压缩；分发时整体 zstd 压缩）
# 若要兼顾 flash 可设 EROFS_COMPRESS=lz4hc,9
# ★ 这几个开关同样必须能从环境继承：外层用 --force/--skip-* 指定的意图要传进 --inner-run，
#   否则内层会把它们重置成 0 —— 表现为"加了 --force 却还是跳过已存在的层"。
FORCE="${FORCE:-0}"
CLEAN="${CLEAN:-0}"
DUMP_INNER=0
SKIP_BASE="${SKIP_BASE:-0}"; SKIP_RUNTIME="${SKIP_RUNTIME:-0}"; SKIP_DSH="${SKIP_DSH:-0}"
# 内层模式（由本脚本生成的引导脚本传入）：只跑三层构建，不做前置检查/不重新生成引导脚本
INNER_RUN="${INNER_RUN:-0}"

# 语义版本（layer-spec §5）：base=<ubuntu 版本>-l<n>、runtime=<semver>、dsh=<npm 版本>
# 可在命令行/环境变量覆盖；dsh 的真实版本构建时从安装后的 package.json 读出。
BASE_VERSION="${BASE_VERSION:-24.04.3-l1}"
RUNTIME_VERSION="${RUNTIME_VERSION:-1.0.0}"
DSH_VERSION="${DSH_VERSION:-}"

UBUNTU_BASE_TARBALL="ubuntu-base-24.04.3-base-arm64.tar.gz"
UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/${UBUNTU_BASE_TARBALL}"

# 本仓库里附带的素材（脚本会被一起复制到 $LH/bin，素材在 ../profiles 或同目录）
PKG_LIST_BASE=""
PKG_LIST_RUNTIME=""
WEB_PROFILE_TEMPLATE=""
INSTALL_WEB_PROFILE=""

# ===========================================================================
# 1. 日志与工具
# ===========================================================================
log() {
    local line=""
    line="[$(date '+%Y-%m-%d %H:%M:%S')] [provision] $*"
    printf '%s\n' "$line" >&2
    [ -d "$(dirname "$LOG_FILE")" ] && printf '%s\n' "$line" >> "$LOG_FILE" 2>/dev/null || true
}
step() { printf '\n\033[1m=== %s ===\033[0m\n' "$*" >&2; log "== $* =="; }
warn() { log "WARN: $*"; }
die()  { log "ERROR: $*"; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# POSIX 单引号转义：`it's` → `'it'\''s'`。
#   ★ 为什么不用 `printf %q`：**Android 的 mksh 没有 %q**。真机实测（2026-09-16 05:36）：
#     `printf: bad %q@20` —— 生成内层引导脚本的那一段当场把整个部署打死。
#     容器/CI 里的 mksh（R59）有 %q，所以本地怎么测都测不出来 → 只能用不依赖 %q 的写法。
#   sed 是 toybox 的，设备上必然有。
squote() {
    printf "'"
    printf '%s' "$1" | sed "s/'/'\\\\''/g"
    printf "'"
}

# 这里**故意不再有** bash 自我再执行守卫。
# 原先的写法是：`[ -z "$BASH_VERSION" ] && { [ -x /system/bin/bash ] && exec bash …; die "需要 bash"; }`
# —— 而真机上 /system/bin/bash 不存在、Termux 也没装，于是这个守卫直接把设备侧
# 首次部署判了死刑（2026-09-16 实测：`ls /data/data/com.termux/files/usr/bin/bash` 不存在）。
# 现在整个脚本只用 mksh 支持的东西，/system/bin/sh 直接跑。

# ===========================================================================
# 2. 参数
#   ★ 参数解析必须在**函数**里：main 要保住原始 "$@"，才能把它们原样重放给内层
#     （见 main 里生成引导脚本的那段）。函数里的 `shift` 只动函数自己的副本。
# ===========================================================================
parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --seeds)   SEEDS_DIR="${2:-}"; shift 2 ;;
            --node)    NODE_VERSION="${2:-}"; shift 2 ;;
            --dsh)     DSH_NPM_TAG="${2:-}"; shift 2 ;;
            --upper-mb) UPPER_SIZE_MB="${2:-}"; shift 2 ;;
            --force)   FORCE=1; shift ;;
            --clean)   CLEAN=1; shift ;;
            --skip-base)    SKIP_BASE=1; shift ;;
            --skip-runtime) SKIP_RUNTIME=1; shift ;;
            --skip-dsh)     SKIP_DSH=1; shift ;;
            -h|--help) sed -n '2,60p' "$SELF_PATH" >&2; exit 0 ;;
            # 只生成内层引导脚本后退出（离线体检用：可在本机做语法校验，不碰设备）
            --dump-inner) DUMP_INNER=1; shift ;;
            # 内部使用：由生成的引导脚本调用（见 main 的说明）。不对外。
            --inner-run) INNER_RUN=1; shift ;;
            *) printf 'device-provision: 未知参数 %s\n' "$1" >&2; exit 2 ;;
        esac
    done
}

# ===========================================================================
# 3. 素材定位（包清单 / profile 模板 / 安装脚本）
# ===========================================================================
locate_assets() {
    local cand=""
    # 候选顺序（都是"base/profiles/…"的形式）：
    #   · $SELF_DIR/..        模块里的 bin/ 旁边 => 模块根（模块包的正确位置）
    #   · $LH/profiles        脚本被拷到 $LH/bin 后自足的那份（见 sync_scripts）
    #   · 模块根 / 模块 bin   —— 从 $LH/bin 那份跑时也要能找到
    for base in "$SELF_DIR" "$SELF_DIR/.." "$SELF_DIR/../.." "$LH" "$LH/bin" \
                "/data/adb/modules/sunsetlinux" "/data/adb/modules/sunsetlinux/bin"; do
        [ -d "$base" ] || continue
        [ -z "$PKG_LIST_BASE" ] && [ -f "$base/profiles/base.packages" ] && PKG_LIST_BASE="$base/profiles/base.packages"
        [ -z "$PKG_LIST_RUNTIME" ] && [ -f "$base/profiles/runtime.packages" ] && PKG_LIST_RUNTIME="$base/profiles/runtime.packages"
        [ -z "$WEB_PROFILE_TEMPLATE" ] && [ -d "$base/profiles/web-profile" ] && WEB_PROFILE_TEMPLATE="$base/profiles/web-profile"
        [ -z "$INSTALL_WEB_PROFILE" ] && [ -f "$base/profiles/install-web-profile.sh" ] && INSTALL_WEB_PROFILE="$base/profiles/install-web-profile.sh"
    done
    log "素材：base.packages=${PKG_LIST_BASE:-未找到}"
    log "素材：runtime.packages=${PKG_LIST_RUNTIME:-未找到}"
    log "素材：web-profile=${WEB_PROFILE_TEMPLATE:-未找到}"
    log "素材：install-web-profile.sh=${INSTALL_WEB_PROFILE:-未找到}"
}

# 解析包清单：取每行第一个字段，忽略注释与空行
parse_packages() {
    local f="$1"
    [ -f "$f" ] || return 0
    sed -e 's/#.*$//' -e 's/[[:space:]]*$//' "$f" \
      | awk 'NF>0 {print $1}' \
      | grep -v '^$' || true
}

# ===========================================================================
# 4. 前置检查
# ===========================================================================
preflight() {
    step "0. 前置检查"
    [ "$(id -u)" = "0" ] || die "需要真 root（当前 uid=$(id -u)）。请用 su 执行。"

    # 真 root 证据：CapEff 非零（proot 伪 root 的 CapEff 是 0）
    local capeff=""
    capeff="$(grep -E '^CapEff:' /proc/self/status 2>/dev/null | awk '{print $2}')"
    if [ -z "$capeff" ] || [ "$capeff" = "0000000000000000" ]; then
        die "CapEff=${capeff:-未知} 为零 → 这不是真 root（可能是 proot 伪 root）。必须用 KernelSU 的真 root。"
    fi
    log "真 root 校验通过（CapEff=$capeff，context=$(cat /proc/self/attr/current 2>/dev/null || echo '?'))"

    # 真 chroot 能力
    [ -x /system/bin/chroot ] || have chroot || die "找不到 chroot（需要真 root + CONFIG 支持）"

    # 挂载能力（本次需要 mount proc/dev 到 chroot）
    [ -x /system/bin/mount ] || have mount || die "找不到 mount"

    # 只读层格式与内核能力匹配
    local fslist=""
    fslist="$(cat /proc/filesystems 2>/dev/null | tr '\n' ' ')"
    case "$LAYER_FORMAT" in
        erofs)
            case "$fslist" in
                *erofs*) log "内核支持 erofs ✓" ;;
                *) die "内核不支持 erofs（/proc/filesystems 里没有），无法用 EROFS 层。" ;;
            esac
            ;;
        squashfs)
            case "$fslist" in
                *squashfs*) log "内核支持 squashfs ✓" ;;
                *) die "内核**不支持** squashfs（CONFIG_SQUASHFS is not set）。请用 LAYER_FORMAT=erofs（默认）。" ;;
            esac
            ;;
        *) die "LAYER_FORMAT 只能是 erofs 或 squashfs（当前 $LAYER_FORMAT）" ;;
    esac

    # ext4（upper.img）
    case "$fslist" in
        *ext4*) log "内核支持 ext4 ✓" ;;
        *) die "内核不支持 ext4，可写层无法创建" ;;
    esac

    locate_assets
    # ★ 素材必须齐 —— 缺了就是"跑 20 分钟才死"，所以在这里就拦住（fail fast）。
    #   真机实测（2026-09-16）：模块 1.0.5 **压根没打包 profiles/**，于是
    #   "素材：base.packages=未找到" 一路往下跑，base 层退化成内置最小集、
    #   runtime 层只装 Node+pnpm，最后在 dsh 阶段才因为缺 web-profile 而 die。
    #   判据：profiles/ 是模块的一部分，缺了说明模块包是残的（而不是用户操作问题）。
    if [ -z "$PKG_LIST_BASE" ]; then
        die "找不到 profiles/base.packages（模块包不完整？）。
      已查找：$SELF_DIR/profiles/、$SELF_DIR/../profiles/、$SELF_DIR/../../profiles/、$LH/bin/profiles/
      修：重装 ≥1.0.6 的模块 zip（1.0.5 及更早**没有随包带 profiles/**，设备侧构建必然失败）。"
    fi
    [ -n "$PKG_LIST_RUNTIME" ] || die "找不到 profiles/runtime.packages（模块包不完整？修法同上）。"
    [ -n "$WEB_PROFILE_TEMPLATE" ] || die "找不到 profiles/web-profile（模块包不完整？修法同上）——dsh 层没有它装不出可用的 profile。"
    [ -n "$INSTALL_WEB_PROFILE" ] || die "找不到 profiles/install-web-profile.sh（模块包不完整？修法同上）。"
    log "素材齐全：base.packages / runtime.packages / web-profile / install-web-profile.sh"
}

# ===========================================================================
# 5. 目录树
# ===========================================================================
make_tree() {
    step "1. 建目录树"
    mkdir -p "$LAYERS_DIR" "$LAYERS_MNT" "$SEEDS_DIR" "$CACHE_DIR" "$ETC_DIR" \
             "$BIN_DIR" "$RUN_DIR" "$SNAP_DIR" "$UPPER_DIR" "$WORK_DIR" "$ROOTFS_DIR"
    chmod 0755 "$LH" 2>/dev/null || true
    chmod 0700 "$RUN_DIR" 2>/dev/null || true
    log "目录就绪：$LH"
    # 脚本自身同步到 $LH/bin（App 侧调用契约：$LINUX_HOME/bin/linuxctl）
    sync_scripts
}

sync_scripts() {
    local f="" src=""
    # layer-spec.sh 必须一起装：linuxctl.sh 会 source 它（唯一事实源）。
    # 它位于 rootfs/，所以靠 "$SELF_DIR/$f"（SELF_DIR == rootfs/）命中。
    local files="linuxctl.sh layer-spec.sh start.sh stop.sh status.sh entry.sh supervise.sh doctor.sh selftest.sh"
    for f in $files; do
        for src in "$SELF_DIR/$f" "$SELF_DIR/../runtime/root/$f"; do
            [ -f "$src" ] || continue
            install -m 0755 "$src" "$BIN_DIR/$f" 2>/dev/null || cp -f "$src" "$BIN_DIR/$f"
            break
        done
    done
    # common/（status_json.sh、http_health.sh）—— linuxctl.sh 会找 ../common 或同目录/common
    mkdir -p "$BIN_DIR/common"
    for f in status_json.sh http_health.sh; do
        for src in "$SELF_DIR/common/$f" "$SELF_DIR/../common/$f" "$SELF_DIR/../../runtime/common/$f"; do
            [ -f "$src" ] || continue
            install -m 0644 "$src" "$BIN_DIR/common/$f" 2>/dev/null || cp -f "$src" "$BIN_DIR/common/$f"
            break
        done
    done
    [ -f "$BIN_DIR/linuxctl.sh" ] && ln -sfn linuxctl.sh "$BIN_DIR/linuxctl" 2>/dev/null || true
    # lib/（模块内共享的 shell 库，如 detect-mount.sh）
    # ★ doctor.sh 会找 "_SELF_DIR/../lib/detect-mount.sh"；doctor 装在 $BIN_DIR，
    #   所以 lib 必须落在 $LINUX_HOME/lib（= $BIN_DIR/..）。漏了它 doctor 的挂载实现探测
    #   会静默降级成 "[skip] 找不到 lib/detect-mount.sh" —— 功能没了但看起来正常。
    local libdir; libdir="$(dirname "$BIN_DIR")/lib"
    for src in "$SELF_DIR/../module/lib" "$SELF_DIR/../../module/lib"; do
        if [ -d "$src" ]; then
            mkdir -p "$libdir"
            cp -rf "$src"/. "$libdir"/ 2>/dev/null || true
            chmod 0644 "$libdir"/*.sh 2>/dev/null || true
            log "已同步 lib/ -> $libdir（$(find "$libdir" -type f 2>/dev/null | wc -l) 个文件）"
            break
        fi
    done
    cp -f "$SELF_PATH" "$BIN_DIR/device-provision.sh" 2>/dev/null || true
    chmod 0755 "$BIN_DIR"/*.sh 2>/dev/null || true
    # profiles/：设备侧构建的素材（包清单 / web profile 模板）。**必须跟着落盘** ——
    # 否则从 $LH/bin/device-provision.sh 再跑一次就"找不到 profiles"（真机踩过：
    # 模块 1.0.5 压根没打包它们，1.0.6 才补上；这里再兜一层，让已安装副本自足）。
    if [ -n "$PKG_LIST_BASE" ]; then
        local pdir=""
        pdir="$(dirname "$PKG_LIST_BASE")"
        if [ -d "$pdir" ]; then
            mkdir -p "$LH/profiles"
            cp -rf "$pdir"/. "$LH/profiles"/ 2>/dev/null || true
            log "已同步 profiles/ -> $LH/profiles（$(find "$LH/profiles" -type f 2>/dev/null | wc -l) 个文件）"
        fi
    fi
    log "已同步运行时脚本到 $BIN_DIR"
}

# ===========================================================================
# 6. 种子：ubuntu-base tarball
# ===========================================================================
ensure_base_tarball() {
    local tb="$SEEDS_DIR/$UBUNTU_BASE_TARBALL"
    if [ -f "$tb" ]; then
        log "已有种子：$tb（$(stat -c '%s' "$tb" 2>/dev/null) 字节）"
        printf '%s' "$tb"
        return 0
    fi
    # 也接受别的 24.04 arm64 base 包名
    local alt=""
    for alt in "$SEEDS_DIR"/ubuntu-base-24.04*-base-arm64.tar.gz "$SEEDS_DIR"/ubuntu-base-*-arm64.tar.gz; do
        [ -f "$alt" ] && { log "使用已有种子：$alt"; printf '%s' "$alt"; return 0; }
    done

    warn "缺少 Ubuntu base 种子：$SEEDS_DIR/$UBUNTU_BASE_TARBALL"
    printf '\n  请用以下任一方式准备：\n' >&2
    printf '    a) 下载到 %s：\n       %s\n' "$SEEDS_DIR" "$UBUNTU_BASE_URL" >&2
    printf '    b) 从开发机推送：adb push %s %s/\n' "$UBUNTU_BASE_TARBALL" "$SEEDS_DIR" >&2
    printf '    c) 用 --seeds <dir> 指向已有目录\n\n' >&2
    return 1
}

# ===========================================================================
# 7. 构建环境：解包 + 文件系统准备（供 chroot 用）
# ===========================================================================
# 解包 base tarball 到 $BUILD_DIR（**清空重建**：每个阶段都要干净起点）
extract_base() {
    local tb=""
    # 允许直接指定 tarball 路径（内层脚本自己找）
    for cand in "$SEEDS_DIR/$UBUNTU_BASE_TARBALL" "$SEEDS_DIR"/ubuntu-base-24.04*-base-arm64.tar.gz \
                "$SEEDS_DIR"/ubuntu-base-*-arm64.tar.gz; do
        [ -f "$cand" ] && { tb="$cand"; break; }
    done
    [ -n "$tb" ] || die "缺少 Ubuntu base tarball：$SEEDS_DIR/$UBUNTU_BASE_TARBALL（下载：$UBUNTU_BASE_URL）"

    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"
    log "解包 $tb → $BUILD_DIR"
    if ! tar -xzf "$tb" -C "$BUILD_DIR" 2>>"$LOG_FILE"; then
        die "解包失败：$tb 可能不完整"
    fi
    [ -x "$BUILD_DIR/bin/sh" ] || die "解包结果里没有 /bin/sh（不是 ubuntu-base？）"
    [ -d "$BUILD_DIR/usr/bin" ] || die "解包结果里没有 /usr/bin"
    log "解包完成（$(du -sh "$BUILD_DIR" 2>/dev/null | awk '{print $1}')）"
}

# 外层 dry 准备：只确认种子可用 + 建好目录（真正的解包由内层每阶段做）
prepare_build_root() {
    step "2. 检查构建素材"
    local tb=""
    tb="$(ensure_base_tarball)" || die "没有可用的 Ubuntu base 种子"
    log "种子：$tb（$(stat -c '%s' "$tb" 2>/dev/null) 字节）"
    if [ "$CLEAN" = "1" ]; then
        log "--clean：删除旧的构建目录与清单"
        rm -rf "$BUILD_DIR" "$CACHE_DIR"/.manifest* "$CACHE_DIR"/manifest-*.txt "$CACHE_DIR"/.files-* 2>/dev/null || true
    fi
    log "说明：每个层阶段都会重新解包 base，保证起点干净（共 3 次，约 1-2 分钟）"
}

# ===========================================================================
# 8. chroot 执行器（核心：所有安装都在真 chroot 内完成）
# ===========================================================================
CHROOT_MOUNTED=0
CHROOT_RESOLV_BACKUP=""

chroot_mounts() {
    [ "$CHROOT_MOUNTED" = "1" ] && return 0
    log "给 chroot 挂载 /proc /sys /dev /dev/pts /dev/shm"
    # 注意：这些是临时的，必须在同一个 mount ns 里挂与卸。
    # 我们在 unshare -m 里做（见 main），因此不会污染宿主挂载表。
    mount -t proc proc "$BUILD_DIR/proc" 2>/dev/null || die "挂载 $BUILD_DIR/proc 失败"
    mount --rbind /sys "$BUILD_DIR/sys" 2>/dev/null || die "挂载 $BUILD_DIR/sys 失败"
    mount --make-rslave "$BUILD_DIR/sys" 2>/dev/null || true
    mount --rbind /dev "$BUILD_DIR/dev" 2>/dev/null || die "挂载 $BUILD_DIR/dev 失败"
    mount --make-rslave "$BUILD_DIR/dev" 2>/dev/null || true
    mkdir -p "$BUILD_DIR/dev/pts" "$BUILD_DIR/dev/shm"
    mount -t devpts devpts "$BUILD_DIR/dev/pts" 2>/dev/null || true
    mount -t tmpfs -o mode=1777 tmpfs "$BUILD_DIR/dev/shm" 2>/dev/null || true

    # DNS：chroot 内 apt 必须能解析域名。
    # Ubuntu base 的 /etc/resolv.conf 常是指向 systemd 的坏软链 → 换成真实文件。
    if [ -L "$BUILD_DIR/etc/resolv.conf" ] || [ ! -e "$BUILD_DIR/etc/resolv.conf" ]; then
        rm -f "$BUILD_DIR/etc/resolv.conf" 2>/dev/null || true
    fi
    if [ ! -s "$BUILD_DIR/etc/resolv.conf" ]; then
        write_resolv_conf
    fi
    CHROOT_MOUNTED=1
    return 0
}

# 把宿主（Android）当前 DNS 写进 chroot 的 /etc/resolv.conf（多路回退）
write_resolv_conf() {
    local dns="" raw="" ns=""
    # ① ndc（netd 权威）
    if [ -x /system/bin/ndc ]; then
        raw="$(/system/bin/ndc resolver getresolvers 2>/dev/null || true)"
        dns="$(printf '%s' "$raw" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' 2>/dev/null | sort -u | tr '\n' ' ' || true)"
    fi
    # ② getprop
    if [ -z "$dns" ] && [ -x /system/bin/getprop ]; then
        raw=""
        for k in net.dns1 net.dns2 net.dns3 net.dns4; do
            raw="$raw $(/system/bin/getprop "$k" 2>/dev/null || true)"
        done
        dns="$(printf '%s' "$raw" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' 2>/dev/null | sort -u | tr '\n' ' ' || true)"
    fi
    # ③ /system/etc/resolv.conf
    if [ -z "$dns" ] && [ -f /system/etc/resolv.conf ]; then
        dns="$(awk '/^[[:space:]]*nameserver/{print $2}' /system/etc/resolv.conf 2>/dev/null | tr '\n' ' ' || true)"
    fi
    # ④ 兜底：公共 DNS + 明确警告
    if [ -z "$dns" ]; then
        dns="8.8.8.8 1.1.1.1"
        warn "取不到 Android 当前 DNS，先用公共 DNS：$dns（若 apt 报解析失败请检查网络）"
    fi
    {
        printf '# generated by sunsetlinux device-provision.sh\n'
        for ns in $dns; do printf 'nameserver %s\n' "$ns"; done
        printf 'options timeout:2 attempts:3\n'
    } > "$BUILD_DIR/etc/resolv.conf"
    log "chroot DNS：$dns"
}

chroot_umounts() {
    [ "$CHROOT_MOUNTED" = "1" ] || return 0
    local rel=""
    for rel in dev/shm dev/pts dev sys proc; do
        umount -l "$BUILD_DIR/$rel" 2>/dev/null || umount -f "$BUILD_DIR/$rel" 2>/dev/null || true
    done
    CHROOT_MOUNTED=0
    return 0
}

# in_chroot <cmd...> —— 在真 chroot 内执行（绝对路径 + 受控环境）
#   PATH 固定，不用调用者的 PATH（findings §4.1：PATH 逃出 chroot 会导致跑错 apt）
in_chroot() {
    chroot "$BUILD_DIR" /usr/bin/env -i \
        HOME=/root \
        PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb \
        DEBIAN_FRONTEND=noninteractive \
        "$@"
}

# in_chroot_net <cmd...> —— 需要联网时额外带代理相关与 npm 配置
in_chroot_net() {
    chroot "$BUILD_DIR" /usr/bin/env -i \
        HOME=/root \
        PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb \
        DEBIAN_FRONTEND=noninteractive \
        npm_config_cache=/tmp/npm-cache \
        npm_config_fund=false \
        npm_config_audit=false \
        npm_config_update_notifier=false \
        "$@"
}

# ===========================================================================
# 9. 层内文件清单（用 inode+size+mtime 三元组判定"新增/修改"）
#
# 为什么不用 overlayfs 的 upper 目录来做增量（更"正宗"）：
#   构建期要在 chroot 里跑 apt/npm，若 root 是 overlay 的 merge 点，overlay 需要
#   CAP_SYS_ADMIN 且一旦某步失败，残留的 overlay 挂载会让整个 provision 卡死；
#   而**运行时**的 overlay 是必须的（那次是用户环境），构建期不是。
#   所以构建期用"完整清单比对"：
#     - 每个阶段都从同一个 Ubuntu base 解包（干净起点，不会互相污染）
#     - 阶段开始时生成 before 清单，结束时生成 after 清单，取差集 = 本层内容
#     - 判定依据是 (dev, inode, size, mtime)：dpkg/tar 会把 mtime 设成包内时间，
#       所以**不能**只看 mtime；但换了 inode（重装覆盖）或 size/mtime 变了都能抓到。
#   这样不依赖任何挂载，失败可重放，且没有任何残留。
#
# 另一个好处：白盒可审计 —— 每层的 before/after 清单都留在 cache/ 里，
# 出问题可以直接 diff 两份清单，不需要重新构建。

# write_manifest <out_file> —— 输出 "dev inode size mtime path" 并按路径排序
manifest_path="$CACHE_DIR/.manifest"
# 设备上的 find 是否支持 `-exec … {} +`（批量）？toybox 的版本差异在这点上不一样：
#   支持 → 一条 find 批量 stat（快）；不支持 → 退化成每个文件一次 stat（慢，但正确）。
# 不这么做的话，万一 toybox 不认 `+`，清单会是**空的** → "本层没有任何变更文件" →
# 三层全空 → 白等半小时。宁可花一次 3 毫秒探测把这个不确定性钉死。
MANIFEST_EXEC_STYLE=""
manifest_exec_style() {
    [ -n "$MANIFEST_EXEC_STYLE" ] && { printf '%s' "$MANIFEST_EXEC_STYLE"; return 0; }
    local probe="$CACHE_DIR/.find-exec-probe" got=""
    rm -rf "$probe" 2>/dev/null || true
    mkdir -p "$probe" 2>/dev/null || true
    : > "$probe/x" 2>/dev/null || true
    got="$(find "$probe" -xdev -exec stat -c '%n' {} + 2>/dev/null || true)"
    if [ -n "$got" ]; then MANIFEST_EXEC_STYLE="+"; else MANIFEST_EXEC_STYLE=";"; fi
    rm -rf "$probe" 2>/dev/null || true
    log "文件清单：find -exec … {} $MANIFEST_EXEC_STYLE（$([ "$MANIFEST_EXEC_STYLE" = "+" ] && printf '批量，快' || printf '逐个，慢但可用')）"
    printf '%s' "$MANIFEST_EXEC_STYLE"
}

write_manifest() {
    local out="$1" style=""
    style="$(manifest_exec_style)"
    (
        cd "$BUILD_DIR" || exit 1
        # -xdev：不跨文件系统（/proc /sys /dev 是挂载点，必须排除）
        if [ "$style" = "+" ]; then
            find . -xdev -exec stat -c '%d %i %s %Y %n' {} + 2>/dev/null
        else
            find . -xdev -exec stat -c '%d %i %s %Y %n' {} \; 2>/dev/null
        fi
    ) | LC_ALL=C sort -k5 > "$out" || true
}

# strip_manifest_prefix <manifest> —— 只留路径列（用于喂给 tar / 做集合运算）
manifest_paths() {
    # 第 5 列开始是路径（路径里可以有空格，所以用 cut -d' ' -f5-）
    cut -d' ' -f5- "$1" 2>/dev/null || true
}

# ===========================================================================
# 10. 打包一层（只含该阶段新增/修改的文件）
# ===========================================================================
# collect_changed <before_manifest> <after_manifest> <out_list>
#   产出本层文件清单（NUL 分隔）到 out_list
collect_changed() {
    local before="$1" after="$2" out="$3"
    # 差集：after 里有、before 里没有的整行 = 新增或已修改
    LC_ALL=C comm -13 "$before" "$after" 2>/dev/null \
        | cut -d' ' -f5- \
        | LC_ALL=C sort -u > "$out.txt" || true
    # 转成 NUL 分隔（文件名可含空格/中文；这里不允许含换行——若含则明确报错）
    : > "$out"
    local line=""
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        printf '%s\0' "$line" >> "$out"
    done < "$out.txt"
    wc -l < "$out.txt" | tr -d ' '
}

# 删除检测：本层"消失"的文件（overlay 只读 lower 无法表达 whiteout，
# 按 architecture.md §5.3 的约定，层间**不允许删除**，所以这里只报警不改行为）
report_deletions() {
    local before="$1" after="$2" name="$3"
    # 只看**普通文件与符号链接**：目录的 mtime 会因为子文件增删而变化，
    # 若不排除会把每个父目录都误报成"消失"（第一次实测就踩到了）。
    local gone=""
    gone="$( { LC_ALL=C comm -23 "$before" "$after" 2>/dev/null || true; } \
             | awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ && substr($0, index($0,$3)) != "" {
                       # 只保留 $6 里的类型标记？清单里没有类型列，用下面的 find 交叉核对
                       print
                   }' \
             | cut -d' ' -f5- | grep -v '^\\.$' | head -n 200 || true)"
    if [ -n "$gone" ]; then
        # 交叉核对：清单里"消失"的行**不等于文件被删** —— 属性变了（size/mtime/inode）也会换行。
        # ★ 这里必须 `-e || -L`，不能只判 `-e`（真机踩过）：
        #   `-e` 会**跟着绝对软链走**，而 rootfs 里的软链大多指向 chroot 内的绝对路径
        #   （例：usr/bin/awk → /etc/alternatives/awk），从 chroot 外面解析必然失败，
        #   于是"软链明明还在"却被判成删除。base 层那 14 条里有 13 条就是这么来的。
        local real="" p=""
        while IFS= read -r p; do
            [ -n "$p" ] || continue
            [ -e "$BUILD_DIR/$p" ] && continue          # 还存在（只是属性变了）
            [ -L "$BUILD_DIR/$p" ] && continue          # 软链本身还在（目标可能悬空，那也是"在"）
            real="$real$p\n"
        done <<EOF
$gone
EOF
        if [ -n "$real" ]; then
            if [ "$name" = "base" ]; then
                # base 是最底层，没有 lower 层可残留 —— 上面的删除天然生效，不算问题
                log "$name 层：$(printf '%b' "$real" | head -n 50 | grep -c . ) 个文件在构建中被移除（base 没有下层，直接生效；例如 apt sources.list 被换成 .sources）"
            else
                warn "$name 阶段检测到文件消失（层间不做删除，这些文件会残留在 lower 层）："
                printf '%b' "$real" | head -n 20 | while IFS= read -r p; do warn "    少了: $p"; done
                warn "  若是有意裁剪，请把裁剪动作挪到 base 层（architecture.md §5.3）。"
            fi
        fi
    fi
}


build_layer() {
    local name="$1" before_manifest="$2"
    local stage_layer_dir="$CACHE_DIR/layer-stage"
    step "打包 $name 层（清单比对）"

    local ver="" file=""
    ver="$(layer_version "$name")"
    file="$(layer_file_name "$name" "$ver")"
    local out="$LAYERS_DIR/$file"

    if [ -f "$out" ] && [ "$FORCE" != "1" ]; then
        log "已存在 $out（跳过；要重建加 --force 或先删除）"
        return 0
    fi

    local after_manifest="$CACHE_DIR/.manifest-$name-after"
    write_manifest "$after_manifest"
    report_deletions "$before_manifest" "$after_manifest" "$name"

    local list="$CACHE_DIR/.files-$name"
    local count=""
    count="$(collect_changed "$before_manifest" "$after_manifest" "$list")"
    log "本层变更条目数：$count（清单：$CACHE_DIR/.files-$name.txt）"
    if [ "$count" = "0" ]; then
        warn "本层没有任何变更文件，跳过打包（是不是 --skip 了安装步骤？）"
        return 0
    fi

    # 版本格式必须合规（layer-spec §5）：不允许用文件名/时间戳占位
    if ! layer_version_valid "$name" "$ver"; then
        die "层 $name 的版本 \"$ver\" 不符合 layer-spec 要求（base 形如 24.04.3-l1，其余为 semver）"
    fi
    log "层 $name 版本：$ver → 文件名 $file"

    # 汇入 stage 目录（tar 用 NUL 分隔喂入，文件名可含空格/中文）
    rm -rf "$stage_layer_dir"
    mkdir -p "$stage_layer_dir"
    ( cd "$BUILD_DIR" && tar --null --no-recursion -T "$list" -cf - 2>/dev/null \
        | tar -xf - -C "$stage_layer_dir" 2>/dev/null ) \
        || die "把变更文件汇入 stage 目录失败（详见 $LOG_FILE）"

    # 许可红线 / 凭据红线断言（layer-spec §7，用 spec 提供的共享实现）
    if ! layer_spec_assert_no_forbidden "$stage_layer_dir"; then
        die "层 $name 命中 layer-spec 禁止路径（见上面的清单）。这是许可/凭据红线，不允许发布。"
    fi
    # 软链必须原样保留（尤其 profile 的 @deepseek-ai 相对软链）
    if [ "$name" = "dsh" ] && [ -d "$stage_layer_dir/root/.dsh/profiles/web" ]; then
        if ! layer_spec_assert_profile_symlink "$stage_layer_dir$LAYER_DSH_PROFILE_DIR"; then
            die "层 dsh 的 profile 软链校验失败（必须是可解析的相对软链，否则 DSH 起不来）"
        fi
        log "profile 软链校验通过（相对软链且可达）"
    fi

    # 硬性校验：stage 里绝不能出现 overlayfd 内部产物
    if find "$stage_layer_dir" -xdev -type c 2>/dev/null | head -n1 | grep -q .; then
        warn "stage 目录里出现字符设备（overlay 白障/whiteout）。EROFS 无法表达，"
        warn "本层将不含该条目；按约定层间不应有删除，请检查构建步骤。"
        find "$stage_layer_dir" -xdev -type c -delete 2>/dev/null || true
    fi

    case "$LAYER_FORMAT" in
        erofs)    make_erofs "$stage_layer_dir" "$out" ;;
        squashfs) make_squashfs "$stage_layer_dir" "$out" ;;
    esac
    local sz=""
    sz="$(stat -c '%s' "$out" 2>/dev/null || echo 0)"
    log "$name 层完成：$out（$(( sz / 1024 / 1024 )) MB）"
    # 把 after 清单留档，供下一阶段/排障使用
    cp -f "$after_manifest" "$CACHE_DIR/manifest-$name.txt" 2>/dev/null || true
}

# 当前生效的层语义版本（layer-spec §5）
#   base    : 24.04.3-l1 这种 <ubuntu 版本>-l<n>
#   runtime : semver
#   dsh     : 安装后从 package.json 读真实版本（构建时才知道）
layer_version() {
    case "$1" in
        base)    printf '%s' "$BASE_VERSION" ;;
        runtime) printf '%s' "$RUNTIME_VERSION" ;;
        dsh)
            if [ -n "$DSH_VERSION" ]; then printf '%s' "$DSH_VERSION"; return 0; fi
            local pj="$BUILD_DIR/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json"
            if [ -f "$pj" ]; then
                tr -d ' \n\t' < "$pj" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -n1
                return 0
            fi
            printf 'unknown' ;;
        *) printf 'unknown' ;;
    esac
}

# 本层镜像在本地的实际路径（走 spec 的 layer_file_name，不再内联命名）
local_layer_path_for() {
    local name="$1" ver=""
    ver="$(layer_version "$name" 2>/dev/null || echo unknown)"
    [ -n "$ver" ] || ver="unknown"
    printf '%s/layers/%s' "$LINUX_HOME" "$(layer_file_name "$name" "$ver")"
}

# 版本是否符合 layer-spec 的格式要求（不合规就不许发布该层）
layer_version_valid() {
    local name="$1" ver="$2"
    case "$name" in
        base)    printf '%s' "$ver" | grep -qE "$version_regex_base" ;;
        runtime) printf '%s' "$ver" | grep -qE "$version_regex_semver" ;;
        dsh)     printf '%s' "$ver" | grep -qE "$version_regex_semver" ;;
        *)       return 1 ;;
    esac
}

# mkfs.erofs 定位
mkfs_erofs_bin() {
    local c=""
    for c in "$BUILD_DIR/usr/bin/mkfs.erofs" "$BUILD_DIR/usr/sbin/mkfs.erofs" \
             /system/bin/mkfs.erofs /system/vendor/bin/mkfs.erofs \
             /usr/bin/mkfs.erofs /usr/sbin/mkfs.erofs; do
        [ -x "$c" ] && { printf '%s' "$c"; return 0; }
    done
    if command -v mkfs.erofs >/dev/null 2>&1; then command -v mkfs.erofs; return 0; fi
    return 1
}

# 打 EROFS 镜像
#   压缩默认 **none**（由 layer-spec §2 决定，基于实测）：
#     - 本机内核 EROFS **只有 CONFIG_EROFS_FS_ZIP=y**，LZMA/DEFLATE/ZSTD 均未启用
#       → 镜像内压缩只能用 lz4/lz4hc，压缩率太弱。
#     - 实测同一份 node 二进制：`-zlz4`=69.0 MB；不压缩=116.4 MB，
#       而后者再走 zstd-19 传输压缩只需 31.1 MB。
#     - 分发体积才是用户痛点（flash 有 625 GB 可用），且不压缩**读取更快**。
#   → 策略：镜像不压缩 + 分发时整体 zstd（见 layer-spec §3 的 layer_transport_name）。
#   想兼顾 flash 可设 EROFS_COMPRESS=lz4hc,9（本函数会自动补 -z 参数）。
make_erofs() {
    local src="$1" out="$2" mk=""
    # 压缩由 layer-spec 决定：默认 none（不传 -z 就是不压缩；`-z none` 不是合法取值！）
    local comp="${EROFS_COMPRESS:-none}"
    local bs="${EROFS_BLOCK_SIZE:-4096}"
    mk="$(mkfs_erofs_bin)" || die "找不到 mkfs.erofs（erofs-utils 未安装，且设备没有 /system/bin/mkfs.erofs）"
    log "mkfs.erofs：$mk（compress=$comp，block=$bs）"

    # 参数能力探测 + 逐级降级。
    # 为什么要探测：设备用 /system/bin/mkfs.erofs（Android 自带，参数兼容性可能与
    # 宿主 erofs-utils 1.7.1 不同），--all-root / -x-1 / -b 未必都认。
    # 每次尝试都**把实际执行的命令原文 + 退出码写进日志**，便于真机排错。
    #
    # ★ 这里**刻意不用数组**（真机第二次失败的原因）：
    #   mksh 里 `zargs=()` 并**不生成一个空数组**，随后 `"${zargs[@]}"` 在 `set -u` 下是
    #   `zargs[@]: parameter not set` —— 而默认 EROFS_COMPRESS=none 正好走这条分支，
    #   于是真机上每个阶段都在这里静默死掉（stderr 一行，日志里看不出来）。
    #   改成"取第 N 组参数"的纯函数 + while 循环：没有数组、没有空展开。
    local i=1 args_str="" rc=0
    while [ "$i" -le 5 ]; do
        args_str="$(erofs_args_for "$i" "$comp" "$bs")"
        rm -f "$out"
        if [ -z "$args_str" ]; then
            log "尝试 #$i：$mk $out $src"
            "$mk" "$out" "$src" >>"$LOG_FILE" 2>&1 && { log "第 $i 次尝试成功（无额外参数）"; probe_set_erofs_args ""; return 0; }
        else
            # shellcheck disable=SC2086
            log "尝试 #$i：$mk $args_str $out $src"
            # shellcheck disable=SC2086
            "$mk" $args_str "$out" "$src" >>"$LOG_FILE" 2>&1 && { log "第 $i 次尝试成功（参数：$args_str）"; probe_set_erofs_args "$args_str"; return 0; }
        fi
        rc=$?
        log "尝试 #$i 失败（rc=$rc），继续降级"
        i=$((i+1))
    done
    die "mkfs.erofs 全部参数组合都失败（共 $((i - 1)) 次，详见 $LOG_FILE）。可手动验证：$mk -b $bs $out $src"
}

# mkfs.erofs 的**逐级降级参数组合**：第 n 组（1..5），只打印参数原文（空 = 不加任何参数）。
#   · 1 完整集 → 2 去 xattr → 3 去 --all-root → 4 只留压缩 → 5 什么都不加
#   ★ 纯粹为了"能被单测"：tools/provision-selftest.mjs 会把这个函数抽出来，
#     在 mksh / bash / 设备 sh 下各跑一遍（真机就是因为空数组在这里炸的）。
#   ★ 不要改回数组写法：mksh 的 `x=()` ≠ 空数组。
erofs_args_for() {
    local n="$1" comp="$2" bs="$3" zarg=""
    case "$comp" in
        none|"") zarg="" ;;
        *)       zarg="-z$comp" ;;
    esac
    case "$n" in
        1) printf '%s' "$zarg -b $bs --all-root -x-1" ;;
        2) printf '%s' "$zarg -b $bs --all-root" ;;
        3) printf '%s' "$zarg -b $bs" ;;
        4) printf '%s' "$zarg" ;;
        5) printf '' ;;
        *) return 1 ;;
    esac
    return 0
}

# 记录本次实际生效的 mkfs.erofs 参数（供 doctor/排障读取）
probe_set_erofs_args() {
    [ -d "$CACHE_DIR" ] || return 0
    printf '%s\n' "$1" > "$CACHE_DIR/.erofs-args" 2>/dev/null || true
}

make_squashfs() {
    local src="$1" out="$2"
    have mksquashfs || die "找不到 mksquashfs（需要 squashfs-tools）"
    # 本机内核不支持 squashfs；此分支仅在确认内核支持时才应被选中
    mksquashfs "$src" "$out" -comp xz -noappend -no-progress -quiet >>"$LOG_FILE" 2>&1 \
        || die "mksquashfs 失败（详见 $LOG_FILE）"
    return 0
}

# ===========================================================================
# 11. base 层
# ===========================================================================
build_base() {
    step "3. base 层：apt 源 + apt-get update + base.packages + 裁剪"

    # --- apt 源：用官方 ports（arm64 官方源就是 ports.ubuntu.com）----------
    # 容器/CI 里常被换成内网镜像；这里**显式写一份**，保证可重现。
    # 允许用 UBUNTU_MIRROR 覆盖（例如国内加速）。
    local mirror="${UBUNTU_MIRROR:-http://ports.ubuntu.com/ubuntu-ports}"
    mkdir -p "$BUILD_DIR/etc/apt/sources.list.d"
    cat > "$BUILD_DIR/etc/apt/sources.list.d/ubuntu.sources" <<EOF
# 由 sunsetlinux device-provision.sh 生成（可重现的官方源）
Types: deb
URIs: $mirror
Suites: noble noble-updates noble-security
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
    # 去掉可能存在的旧 sources.list，避免重复源
    if [ -f "$BUILD_DIR/etc/apt/sources.list" ]; then
        mv -f "$BUILD_DIR/etc/apt/sources.list" "$BUILD_DIR/etc/apt/sources.list.disabled" 2>/dev/null || true
    fi
    log "apt 源已写入：$mirror"

    # --- apt-get update -----------------------------------------------------
    log "apt-get update ..."
    in_chroot /usr/bin/apt-get update -o Acquire::Retries=3 || die "apt-get update 失败（DNS/网络？）"

    # --- 安装 base.packages ------------------------------------------------
    local pkgs=""
    if [ -n "$PKG_LIST_BASE" ]; then
        pkgs="$(parse_packages "$PKG_LIST_BASE" | tr '\n' ' ')"
    fi
    # 内置最小集（清单缺失时兜底；清单存在时**也**合并，保证硬依赖一定在）
    pkgs="$pkgs bash coreutils dash sed grep findutils tar gzip apt dpkg gpgv \
ca-certificates libc6 libstdc++6 libgcc-s1 zlib1g tzdata netbase mount util-linux \
squashfs-tools xz-utils"
    # 去重
    pkgs="$(printf '%s' "$pkgs" | tr ' ' '\n' | awk 'NF>0 && !seen[$0]++' | tr '\n' ' ')"
    log "将安装 $(printf '%s' "$pkgs" | wc -w) 个包（--no-install-recommends）"

    # --no-install-recommends：不加会拖进一堆非必需包（体积控制的关键）
    in_chroot /usr/bin/apt-get install -y --no-install-recommends -o Acquire::Retries=3 $pkgs \
        || die "apt-get install 失败（详见 $LOG_FILE）"

    # --- 裁剪（只在 base 做一次；层间不做删除，见 architecture.md §5.3）-----
    step "3b. base 层裁剪"
    local removed=0
    # 1) 文档/手册
    rm -rf "$BUILD_DIR/usr/share/doc" "$BUILD_DIR/usr/share/man" \
           "$BUILD_DIR/usr/share/info" "$BUILD_DIR/usr/share/groff" 2>/dev/null || true
    # 2) apt 缓存与索引
    rm -rf "$BUILD_DIR/var/cache/apt/archives"/*.deb 2>/dev/null || true
    rm -rf "$BUILD_DIR/var/lib/apt/lists"/* 2>/dev/null || true
    mkdir -p "$BUILD_DIR/var/lib/apt/lists/partial" 2>/dev/null || true
    # 3) locale：只保留 C / C.UTF-8 / zh_CN（其余删掉；locale-archive 保留）
    local loc="" keep=""
    for loc in "$BUILD_DIR"/usr/share/locale/*; do
        [ -d "$loc" ] || continue
        keep=0
        case "$(basename "$loc")" in
            C|C.UTF-8|POSIX|zh_CN|zh|en|en_US|locale-archive) keep=1 ;;
        esac
        [ "$keep" = "1" ] || { rm -rf "$loc" 2>/dev/null && removed=$((removed+1)); }
    done
    # 4) __pycache__ / *.pyc
    find "$BUILD_DIR" -xdev -type d -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
    find "$BUILD_DIR" -xdev -type f -name '*.py[co]' -delete 2>/dev/null || true
    # 5) systemd 相关（内核无 PID_NS，systemd 不可用；dpkg 的 init 脚本保留即可）
    #    ★ 连 /etc/systemd 一起删：那一堆 *.wants/*.timer 是指向 /usr/lib/systemd 的**悬空软链**，
    #      留着纯属垃圾（而且会让删除检测刷一屏噪音 —— 真机第一跑就是 13 条这种）。
    rm -rf "$BUILD_DIR/lib/systemd" "$BUILD_DIR/usr/lib/systemd" "$BUILD_DIR/etc/systemd" 2>/dev/null || true
    # 6) 常见体积大户但本项目用不到
    rm -rf "$BUILD_DIR/usr/share/perl"/*/unicore 2>/dev/null || true
    log "裁剪完成（locale 目录删除 $removed 项；doc/man/info/apt 缓存已清）"

    # base 是完整层：before 清单是"解包后、安装前"的空基线（由 main 生成）
    build_layer base "$MANIFEST_BEFORE"
}

# ===========================================================================
# 12. runtime 层：Node 官方静态包 + pnpm + runtime.packages
# ===========================================================================
build_runtime() {
    step "4. runtime 层：Node $NODE_VERSION + pnpm + runtime.packages"

    # --- Node 官方 arm64 tarball（比 apt 的 node 新且可控）------------------
    local node_tar="$SEEDS_DIR/node-$NODE_VERSION-linux-arm64.tar.xz"
    local node_url="https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz"
    if [ ! -f "$node_tar" ]; then
        log "下载 Node：$node_url"
        # chroot 里没有宿主网络缓存，直接在 chroot 内用 curl/wget 下到 /tmp
        if in_chroot_net /usr/bin/curl -fsSL -o "/tmp/node.tar.xz" "$node_url"; then
            cp -f "$BUILD_DIR/tmp/node.tar.xz" "$node_tar" 2>/dev/null || true
        elif in_chroot_net /usr/bin/wget -q -O "/tmp/node.tar.xz" "$node_url"; then
            cp -f "$BUILD_DIR/tmp/node.tar.xz" "$node_tar" 2>/dev/null || true
        else
            die "下载 Node 失败：$node_url（可手动放到 $node_tar 后重跑）"
        fi
    fi
    [ -f "$BUILD_DIR/tmp/node.tar.xz" ] || cp -f "$node_tar" "$BUILD_DIR/tmp/node.tar.xz"
    # 校验是否真的是 xz（下载被劫持/半截时给可读错误）
    if ! in_chroot /usr/bin/xz -t /tmp/node.tar.xz 2>/dev/null; then
        die "Node tarball 校验失败（$node_tar 不是完整 xz 包）。删除后重跑。"
    fi

    log "解压 Node 到 /opt/node"
    in_chroot /bin/mkdir -p /opt
    in_chroot /bin/rm -rf "/opt/node"
    in_chroot /bin/sh -c "xz -dc /tmp/node.tar.xz | tar -x -C /opt" || die "解压 Node 失败"
    # 官方包解出来是 node-vX.Y.Z-linux-arm64/，改名成 /opt/node
    in_chroot /bin/sh -c "mv /opt/node-$NODE_VERSION-linux-arm64 /opt/node" || die "整理 /opt/node 失败"
    # 暴露到 /usr/local/bin（随 runtime 层，dsh 层只放 dsh 自己的入口）
    in_chroot /bin/mkdir -p /usr/local/bin
    in_chroot /bin/ln -sfn /opt/node/bin/node /usr/local/bin/node
    in_chroot /bin/ln -sfn /opt/node/bin/npm  /usr/local/bin/npm
    in_chroot /bin/ln -sfn /opt/node/bin/npx  /usr/local/bin/npx
    in_chroot /bin/rm -f /tmp/node.tar.xz

    local nv=""
    nv="$(in_chroot /opt/node/bin/node --version 2>/dev/null || echo '?')"
    log "Node 安装完成：$nv"
    [ "$nv" != "?" ] || die "node --version 失败（/opt/node 可能不完整）"

    # --- pnpm（docs/dsh-profile.md §6：dsh plugin 必须靠它装第三方插件）----
    log "安装 pnpm（npm i -g pnpm，前缀 /opt/node）"
    if ! in_chroot_net /usr/local/bin/npm i -g --prefix /opt/node pnpm --no-audit --no-fund; then
        die "pnpm 安装失败（dsh plugin 将无法安装第三方插件，不能静默跳过）"
    fi
    local pv=""
    pv="$(in_chroot /opt/node/bin/pnpm --version 2>/dev/null || echo '?')"
    if [ "$pv" = "?" ]; then
        # 有些环境 pnpm 落在 /opt/node/lib/node_modules/pnpm/bin，补一个软链
        in_chroot /bin/sh -c 'for p in /opt/node/lib/node_modules/pnpm/bin/*.js /opt/node/lib/node_modules/pnpm/bin/pnpm*; do [ -f "$p" ] && { printf "#!/bin/sh\nexec /opt/node/bin/node \"$p\" \"\$@\"\n" > /opt/node/bin/pnpm; chmod 0755 /opt/node/bin/pnpm; break; }; done' 2>/dev/null || true
        pv="$(in_chroot /opt/node/bin/pnpm --version 2>/dev/null || echo '?')"
    fi
    [ "$pv" != "?" ] || die "pnpm --version 失败：pnpm 没装好（dsh plugin 会用不了）"
    log "pnpm 安装完成：$pv"

    # --- 运行时入口脚本：/opt/sunsetlinux/{entry.sh,supervise.sh} -----------------
    # layer-spec §6 明确：这两个脚本必须在**只读层**（否则 `linuxctl reset` 清空可写层
    # 后环境再也起不来），且放 **runtime 层** 而不是 base —— 它们属于"运行时"变更，
    # 不该逼用户为了改 entry.sh 去重下 OS 基线层。
    step "4b. 安装运行时入口脚本到 $LAYER_RUNTIME_ENTRY_DIR（runtime 层）"
    in_chroot /bin/mkdir -p "$LAYER_RUNTIME_ENTRY_DIR"
    local ef="" found=0
    for ef in entry.sh supervise.sh; do
        local src=""
        for src in "$SELF_DIR/$ef" "$SELF_DIR/../runtime/root/$ef" "$BIN_DIR/$ef"; do
            [ -f "$src" ] || continue
            copy_into_chroot "$src" "$LAYER_RUNTIME_ENTRY_DIR/$ef" 0755
            found=$((found+1))
            break
        done
    done
    [ "$found" = "2" ] || die "运行时入口脚本不全（找到 $found/2 个：entry.sh supervise.sh）"
    in_chroot /bin/sh -c "test -x $LAYER_RUNTIME_ENTRY && test -x $LAYER_RUNTIME_SUPERVISE" \
        || die "复制后 $LAYER_RUNTIME_ENTRY / $LAYER_RUNTIME_SUPERVISE 不可执行"
    log "入口脚本就绪：$LAYER_RUNTIME_ENTRY、$LAYER_RUNTIME_SUPERVISE（随 runtime 层发布）"

    # --- 其它运行期工具（runtime.packages 的工具组）------------------------
    local pkgs=""
    [ -n "$PKG_LIST_RUNTIME" ] && pkgs="$(parse_packages "$PKG_LIST_RUNTIME" | tr '\n' ' ')"
    if [ -n "$pkgs" ]; then
        pkgs="$(printf '%s' "$pkgs" | tr ' ' '\n' | awk 'NF>0 && !seen[$0]++' | tr '\n' ' ')"
        log "安装 runtime.packages 工具：$(printf '%s' "$pkgs" | wc -w) 个"
        in_chroot /usr/bin/apt-get install -y --no-install-recommends -o Acquire::Retries=3 $pkgs \
            || die "runtime.packages 安装失败（详见 $LOG_FILE）"
        # 装完再清一次 apt 缓存（层里不留 deb）
        rm -rf "$BUILD_DIR/var/cache/apt/archives"/*.deb 2>/dev/null || true
        rm -rf "$BUILD_DIR/var/lib/apt/lists"/* 2>/dev/null || true
        mkdir -p "$BUILD_DIR/var/lib/apt/lists/partial" 2>/dev/null || true
    else
        warn "runtime.packages 为空，只装 Node 与 pnpm"
    fi

    build_layer runtime "$MANIFEST_BEFORE"
}

# ===========================================================================
# 13. dsh 层：npm i -g + profile 工作区
# ===========================================================================
build_dsh() {
    step "5. dsh 层：npm i -g $DSH_PACKAGE@$DSH_NPM_TAG + profile 工作区"

    # --- 全局安装 DSH -------------------------------------------------------
    # 所有原生模块（node-pty/sharp/koffi/node-addon-system）都带预编译产物，
    # **不需要编译器**（findings §6.1/§6.2）。所以这一步是纯下载+解包。
    log "npm i -g --prefix /usr/local $DSH_PACKAGE@$DSH_NPM_TAG"
    if ! in_chroot_net /usr/local/bin/npm i -g --prefix /usr/local \
            "$DSH_PACKAGE@$DSH_NPM_TAG" --no-audit --no-fund; then
        die "DSH 全局安装失败（详见 $LOG_FILE）。可加 --dsh <版本> 指定可用版本。"
    fi

    local dsh_bin="$BUILD_DIR/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
    [ -f "$dsh_bin" ] || die "安装后找不到 $dsh_bin（npm 前缀不对？必须是 --prefix /usr/local）"
    # 入口软链（契约 §5.3 / findings §6.5）
    in_chroot /bin/ln -sfn ../lib/node_modules/@deepseek-ai/dsh/lib/bin.js /usr/local/bin/dsh
    local dv=""
    dv="$(in_chroot /bin/sh -c 'cd /usr/local/lib/node_modules/@deepseek-ai/dsh && node -e "console.log(require(\"./package.json\").version)"' 2>/dev/null || echo '?')"
    [ "$dv" != "?" ] || die "读不出 DSH 版本（安装可能不完整）"
    log "DSH 版本：$dv"

    # --- profile 工作区（docs/dsh-profile.md §5，**必须**，否则界面退化成桌面版）
    step "5b. 安装 web profile 工作区"
    if [ -z "$WEB_PROFILE_TEMPLATE" ] || [ -z "$INSTALL_WEB_PROFILE" ]; then
        die "缺少 profile 素材（profiles/web-profile 或 profiles/install-web-profile.sh）。dsh 层不能缺 profile。"
    fi
    # 把素材搬进 chroot（放 /opt/sunsetlinux，与 entry.sh 同目录）
    in_chroot /bin/mkdir -p /opt/sunsetlinux
    copy_into_chroot "$INSTALL_WEB_PROFILE" /opt/sunsetlinux/install-web-profile.sh 0755
    in_chroot /bin/mkdir -p /opt/sunsetlinux/web-profile
    local tf=""
    for tf in "$WEB_PROFILE_TEMPLATE"/*; do
        [ -f "$tf" ] || continue
        copy_into_chroot "$tf" "/opt/sunsetlinux/web-profile/$(basename "$tf")" 0644
    done

    # ★ 必须直接装到最终路径 /root/.dsh/profiles/web：
    #   install-web-profile.sh 算的是**相对软链**，先装到临时目录再 mv 会让软链失效
    #   （docs/dsh-profile.md §5.5）。
    in_chroot /bin/mkdir -p /root/.dsh/profiles
    if ! in_chroot /bin/bash /opt/sunsetlinux/install-web-profile.sh \
            /opt/sunsetlinux/web-profile /root/.dsh/profiles/web; then
        die "profile 安装失败（install-web-profile.sh 自检未通过，dsh web 会缺失移动端适配）"
    fi

    # 注意：entry.sh / supervise.sh **不放这一层**，按 layer-spec §6 归 runtime 层
    # （见 build_runtime 的 "4b" 步）。这里只放 DSH 主体与 profile。

    # --- 默认配置目录（§5.3 要求 /root/.dsh-defaults/**，不含密钥）---------
    in_chroot /bin/mkdir -p /root/.dsh-defaults
    cat > "$CACHE_DIR/dsh-defaults-README.txt" <<'EOF'
# /root/.dsh-defaults
#
# 这里是 SunsetLinux 分发的 DSH 默认配置模板，随只读 dsh 层（L2）发布。
# 它**不包含任何密钥**：API key 请放在 /root/.dsh/.credentials.yaml（0600）
# 或环境变量文件 /root/.dsh/env（0600，由 entry.sh 以 set -a 加载）。
#
# 用户实际配置在可写层 /root/.dsh/ 下，reset 会清空它们（回到出厂），
# 但不会影响这里。
EOF
    copy_into_chroot "$CACHE_DIR/dsh-defaults-README.txt" /root/.dsh-defaults/README.txt 0644

    # --- 自检：profile 可解析性（doctor 也会查，但这里失败就不该收工）------
    step "5c. dsh 层自检"
    if ! in_chroot /bin/bash -c 'cd /root/.dsh/profiles/web && /opt/node/bin/node -e "
const need=[\"dsh-web-mobile\",\"dsh-task-notifier\",\"@deepseek-ai/dsh-base\",\"@deepseek-ai/dsh-web-app\"];
let bad=0; for (const n of need) { try { await import(n); } catch(e) { console.error(\"FAIL \"+n+\": \"+e.message); bad++; } }
process.exit(bad?1:0);" --input-type=module' ; then
        die "dsh 层自检失败：profile 里的 bundle 无法解析（不要发布这个层）"
    fi
    log "profile 自检通过（4 个 bundle 均可解析）"
    if ! in_chroot /bin/sh -c 'test -x /opt/node/bin/pnpm && /opt/node/bin/pnpm --version >/dev/null'; then
        die "dsh 层自检失败：pnpm 不可用（dsh plugin 会报 pnpm not found）"
    fi
    log "pnpm 自检通过"

    build_layer dsh "$MANIFEST_BEFORE"
}

# copy_into_chroot <host_file> <chroot_abs_path> <mode>
copy_into_chroot() {
    local src="$1" dst_rel="$2" mode="${3:-0644}" dst="$BUILD_DIR$2"
    mkdir -p "$(dirname "$dst")" 2>/dev/null || true
    cp -f "$src" "$dst" || die "复制 $src -> $dst 失败"
    chmod "$mode" "$dst" 2>/dev/null || true
}

# ===========================================================================
# 14. upper.img
# ===========================================================================
make_upper() {
    step "6. 创建可写层 upper.img（ext4 稀疏，$(( UPPER_SIZE_MB / 1024 )) GiB）"
    if [ -f "$UPPER_DIR/../upper.img" ] && [ "$FORCE" != "1" ]; then
        log "upper.img 已存在（幂等跳过；要重建先删除它或加 --force）"
        return 0
    fi
    local img="$LH/upper.img"
    have truncate || die "需要 truncate（toybox 自带）"
    truncate -s "${UPPER_SIZE_MB}M" "$img" || die "创建 $img 失败（空间不足？）"

    # 优先用 chroot 内的 mke2fs（e2fsprogs 已在 base.packages）；否则用设备自带的
    local ok=0
    # 注意：mke2fs 会碰块设备吗？不会——这里操作的是普通文件镜像。
    if [ -x "$BUILD_DIR/sbin/mke2fs" ] || [ -x "$BUILD_DIR/usr/sbin/mke2fs" ]; then
        if in_chroot /sbin/mke2fs -F -q -t ext4 -m 0 -L sunsetlinux-upper "/upper.img.tmp" 2>/dev/null; then
            ok=1
        fi
    fi
    if [ "$ok" != "1" ]; then
        # 退化为设备自带 mke2fs（Android 有 /system/bin/mke2fs）
        local mk=""
        for mk in /system/bin/mke2fs /system/bin/mkfs.ext4; do
            [ -x "$mk" ] || continue
            if "$mk" -F -q -t ext4 -m 0 -L sunsetlinux-upper "$img" >>"$LOG_FILE" 2>&1; then ok=1; break; fi
        done
    fi
    [ "$ok" = "1" ] || { rm -f "$img"; die "创建 ext4 文件系统失败（mke2fs 不可用？）"; }
    # 说明：mke2fs 直接写 $img（设备自带分支）或写 $BUILD_DIR/upper.img.tmp（chroot 分支）；
    #       后者需要搬回来。
    if [ -f "$BUILD_DIR/upper.img.tmp" ]; then
        mv -f "$BUILD_DIR/upper.img.tmp" "$img" || die "移动 upper.img 失败"
    fi
    mkdir -p "$UPPER_DIR/upper" "$UPPER_DIR/work"
    log "upper.img 就绪（$img，$(( UPPER_SIZE_MB )) MiB 稀疏）"
}

# ===========================================================================
# 15. config.json / state.json
# ===========================================================================
write_etc() {
    step "7. 写 etc/config.json 与 etc/state.json"

    if [ ! -f "$ETC_DIR/config.json" ]; then
        cat > "$ETC_DIR/config.json" <<EOF
{
  "schema": 1,
  "port": ${SUNSETLINUX_PORT:-3080},
  "mode": "root",
  "host": "127.0.0.1",
  "autostart": true,
  "layer_format": "$LAYER_EXT",
  "freeze_exempt": true,
  "log_max_bytes": 4194304,
  "note": "autostart=true：KernelSU 模块 service.sh 在 late_start 调 linuxctl start，与 App 无关"
}
EOF
        log "已写 $ETC_DIR/config.json（已存在则不覆盖）"
    else
        log "config.json 已存在，保留用户设置"
    fi

    # state.json：层**语义版本** + 大小 + sha256（App/频道比对用，layer-spec §5）
    local name="" f="" sz="" sha="" ver="" json_layers="" first=1
    for name in "${LAYERS_ORDER[@]}"; do
        f="$(local_layer_path_for "$name")"
        sz="null"; sha="null"
        ver="$(layer_version "$name")"
        [ -n "$ver" ] || ver="unknown"
        if [ -n "$f" ] && [ -f "$f" ]; then
            sz="$(stat -c '%s' "$f" 2>/dev/null || echo null)"
            if have sha256sum; then
                sha="\"$(sha256sum "$f" 2>/dev/null | awk '{print $1}')\""
            fi
        fi
        [ "$first" = 1 ] || json_layers="$json_layers,"
        json_layers="$json_layers
    \"$name\": { \"version\": \"$ver\", \"size\": $sz, \"sha256\": $sha, \"format\": \"$LAYER_EXT\", \"file\": \"$(basename "${f:-}")\", \"transport\": \"$(layer_transport_name "$name" "$ver" 2>/dev/null || echo "")\" }"
        first=0
    done
    local dshver="null"
    if [ -n "$DSH_VERSION" ]; then dshver="\"$DSH_VERSION\""; fi
    cat > "$ETC_DIR/state.json" <<EOF
{
  "schema": 1,
  "spec": $LAYER_SPEC_VERSION,
  "generated_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "generator": "device-provision.sh",
  "dsh_version": $dshver,
  "node_version": "$(in_chroot /opt/node/bin/node --version 2>/dev/null || echo '?')",
  "layer_format": "$LAYER_EXT",
  "erofs_compress": "$EROFS_COMPRESS",
  "transport_compress": "$TRANSPORT_COMPRESS_PRIMARY",
  "transport_compress_fallback": "$TRANSPORT_COMPRESS_FALLBACK",
  "layers": {$json_layers
  }
}
EOF
    log "已写 $ETC_DIR/state.json"
}

# ===========================================================================
# 15b. 分发压缩：为每层产出 .erofs.zst 与 .erofs.gz
#   layer-spec §3 实测结论：设备侧**既没 zstd 也没 xz**（只有 toybox gzip），
#   而 App 路径想用体积小 35% 的 zstd。所以两种都产出：
#     - App 下载 .zst（App 内解压 → 写裸 .erofs → linuxctl update）
#     - 纯 CLI / 无 App 路径只能用 .gz（toybox gzip 可解）
#   缺哪一种都会打断一条安装路径，所以不做"二选一"。
# ===========================================================================
make_transports() {
    step "8. 产出分发压缩包（.zst + .gz）"
    local name="" f="" ver="" tname="" zst="" gz="" rc_z=0 rc_g=0
    for name in "${LAYERS_ORDER[@]}"; do
        f="$(local_layer_path_for "$name")"
        if [ ! -f "$f" ]; then
            warn "$name 层文件缺失（$f），跳过分发压缩"
            continue
        fi
        ver="$(layer_version "$name")"
        # --- zstd（首选）---
        tname="$(layer_transport_name "$name" "$ver" zstd)"
        if have zstd; then
            if zstd -q -T0 -19 -f "$f" -o "$LAYERS_DIR/$tname" >>"$LOG_FILE" 2>&1; then
                log "  $tname（$(( $(stat -c '%s' "$LAYERS_DIR/$tname") / 1024 / 1024 )) MB，zstd-19）"
            else
                warn "zstd 压缩 $name 失败"
                rc_z=1
            fi
        else
            warn "本机没有 zstd，无法产出 $tname（只在设备侧构建时会出现；宿主/CI 上应有 zstd）"
            rc_z=1
        fi
        # --- gzip（兼容回退；设备上只有这个）---
        tname="$(layer_transport_name "$name" "$ver" gzip)"
        if have gzip; then
            if gzip -9 -c "$f" > "$LAYERS_DIR/$tname" 2>>"$LOG_FILE"; then
                log "  $tname（$(( $(stat -c '%s' "$LAYERS_DIR/$tname") / 1024 / 1024 )) MB，gzip-9）"
            else
                warn "gzip 压缩 $name 失败"
                rc_g=1
            fi
        else
            warn "本机没有 gzip（非常意外，toybox 自带）"
            rc_g=1
        fi
    done
    [ "$rc_g" = "0" ] || warn "缺少 .gz 会打断「纯 CLI / 无 App」的安装路径，请补上"
    return 0
}

# ===========================================================================
# 16. 收尾
# ===========================================================================
summary() {
    step "完成摘要"
    local name="" f=""
    for name in base runtime dsh; do
        f=""
        for cand in "$LAYERS_DIR/$name.erofs" "$LAYERS_DIR/$name.squashfs"; do
            [ -f "$cand" ] && { f="$cand"; break; }
        done
        if [ -n "$f" ]; then
            log "  $name : $(basename "$f")  $(( $(stat -c '%s' "$f") / 1024 / 1024 )) MB"
        else
            log "  $name : （未生成）"
        fi
    done
    [ -f "$LH/upper.img" ] && log "  upper: upper.img  $(( $(stat -c '%s' "$LH/upper.img") / 1024 / 1024 / 1024 )) GiB 稀疏"
    log "  分发压缩包（$LAYERS_DIR/）："
    for tf in "$LAYERS_DIR"/*.erofs.zst "$LAYERS_DIR"/*.erofs.gz; do
        [ -f "$tf" ] || continue
        log "    $(basename "$tf")  $(( $(stat -c '%s' "$tf") / 1024 / 1024 )) MB"
    done
    log ""
    log "下一步："
    log "  sh $BIN_DIR/linuxctl status      # 看 JSON 状态"
    log "  sh $BIN_DIR/linuxctl start       # 启动（开机也会由模块 service.sh 自动启）"
    log "  sh $BIN_DIR/linuxctl doctor      # 自检"
    log ""
    log "日志：$LOG_FILE"
}

# ===========================================================================
# 17. 三层阶段编排（只在内层 --inner-run 里跑）
#   ★ 每一步都"重新解包同一个 base"再构建，保证每层的 before 基线是干净起点，
#     层与层之间不会互相污染（这是"层可比对"的前提）。
#   ★ 以前这段是作为 heredoc 文本拼进内层脚本的；现在内层就是本文件本身，
#     所以它就是普通函数 —— 改这里等于改设备上真正执行的代码，不需要两头同步。
# ===========================================================================
prepare_stage() {
    local name="$1"
    step "准备 $name 阶段的干净起点（重新解包 base）"
    extract_base
    chroot_mounts
    write_manifest "$MANIFEST_BEFORE"
    log "before 清单：$(wc -l < "$MANIFEST_BEFORE" | tr -d ' ') 条"
}

run_stages() {
    if [ "$SKIP_BASE" != "1" ]; then
        prepare_stage base
        build_base
        chroot_umounts
    fi
    if [ "$SKIP_RUNTIME" != "1" ]; then
        prepare_stage runtime
        build_runtime
        chroot_umounts
    fi
    if [ "$SKIP_DSH" != "1" ]; then
        prepare_stage dsh
        build_dsh
        chroot_umounts
    fi
}

main() {
    # ★ 参数解析放在这里（而不是脚本顶层）：main 必须保住原始 "$@"，
    #   内层引导脚本要把它们**原样重放**（见下面生成引导脚本那段）。
    parse_args "$@"

    mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || true
    : >> "$LOG_FILE" 2>/dev/null || true

    # 内层模式：本进程由外层生成的引导脚本**重新执行本文件**拉起（--inner-run），
    # 只做三层构建。前置检查 / 建目录 / 种子检查 / upper.img 都在外层做，这里绝不重复，
    # 也绝不重新生成引导脚本（否则递归）。
    if [ "$INNER_RUN" = "1" ]; then
        log "内层构建开始（shell=$SH_BIN，uid=$(id -u)）"
        locate_assets
        run_stages
        log "内层构建结束"
        return 0
    fi

    # --dump-inner 是离线体检模式：只生成内层引导脚本供语法校验 / 人工审阅，不做任何设备操作
    if [ "${DUMP_INNER:-0}" != "1" ]; then
        preflight
        make_tree
        prepare_build_root
    else
        mkdir -p "$CACHE_DIR" 2>/dev/null || true
        locate_assets
        log "离线模式（--dump-inner）：跳过前置检查与设备操作"
    fi

    # 构建期的挂载（proc/sys/dev）放在**独立 mount namespace** 里做：
    # 进程退出时随 ns 一起消失，绝不污染宿主挂载表（这是"无残留"的保障）。
    #
    # ★ 内层脚本为什么是"带着同样的命令行参数重新执行本文件"：
    #   ① 旧写法用 `declare -f` 转储函数 —— bash 专有，mksh 下必然
    #      "declare: inaccessible or not found"；
    #   ② 改成"导出环境变量"后又踩了第二个坑：转义要用 `printf %q`，而
    #      **Android 的 mksh 没有 %q**（真机实测 `printf: bad %q@20`，部署就此终止；
    #      容器里的 mksh 有 %q，所以本地怎么测都测不出来）。
    #   ③ 现在：**原样重放命令行参数**（外加 --inner-run）。既不需要 %q，也不需要
    #      猜"哪些值要传过去" —— 用户用环境变量给的值本来就会被继承，命令行给的值
    #      由参数重放覆盖。函数与常量都来自同一份源码。
    local inner="$CACHE_DIR/.provision-inner.sh"
    {
        printf '#!/system/bin/sh\n'
        printf '# 由 device-provision.sh 生成：在 unshare -m 内部执行的引导脚本\n'
        printf '# 只做一件事：带着**同样的命令行参数**重新执行本脚本（外加 --inner-run）。\n'
        printf '# 刻意不导出变量：Android 的 mksh 不支持 printf 的百分号转义格式\n'
        printf '# （真机实测报 "printf: bad ...@20"），而重放参数既准确又不需要任何转义技巧。\n'
        printf 'set -eu\n'
        printf 'exec %s %s --inner-run' "$(squote "$SH_BIN")" "$(squote "$SELF_PATH")"
        for _a in "$@"; do
            # 这两个只对外层有意义，别重放给内层
            case "$_a" in --dump-inner|--inner-run) continue ;; esac
            printf ' %s' "$(squote "$_a")"
        done
        printf '\n'
    } > "$inner"
    chmod 0755 "$inner"

    if [ "${DUMP_INNER:-0}" = "1" ]; then
        printf '内层引导脚本已写出：%s\n' "$inner" >&2
        printf '语法自检（%s -n）：%s\n' "$SH_BIN" "$("$SH_BIN" -n "$inner" 2>&1 && echo OK || echo FAIL)" >&2
        exit 0
    fi

    if [ -x /system/bin/unshare ]; then
        log "在独立 mount namespace 中构建（unshare -m --propagation private）"
        if ! /system/bin/unshare -m --propagation private "$SH_BIN" "$inner"; then
            # toybox unshare 可能不认 --propagation，退化为不带该参数
            warn "unshare --propagation private 失败，改为 unshare -m"
            /system/bin/unshare -m "$SH_BIN" "$inner" || die "构建失败（详见 $LOG_FILE）"
        fi
    else
        warn "找不到 /system/bin/unshare，构建期挂载可能残留（脚本会自动尝试卸载）"
        "$SH_BIN" "$inner" || die "构建失败（详见 $LOG_FILE）"
    fi

    make_upper
    write_etc
    make_transports
    summary
}

main "$@"
