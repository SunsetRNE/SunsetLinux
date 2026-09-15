#!/usr/bin/env bash
# =============================================================================
# 打包 KernelSU 模块（把运行时脚本铺进 bin/ 再压成 zip）
#
# 为什么需要这个脚本：`module/post-fs-data.sh` 会从 `$MODDIR/bin` 同步运行时脚本到
# `/data/sunsetlinux/bin`，而仓库里的 `module/` **本身不含 bin/**（它是打包产物）。
# 没有这一步，模块装上去 `bin/` 是空的 → `post-fs-data.sh` 无事可做 →
# **`/data/sunsetlinux/bin/linuxctl` 永远不会出现 → App 无法控制环境**。
#
# 用法：
#   bash module/mkmodule.sh [--version <ver>] [--out <zip 路径>]
# 产物：
#   dist/sunsetlinux-module.zip（+ .sha256、MANIFEST 清单）
# =============================================================================
set -euo pipefail

SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_DIR="$(cd -- "$SELF_DIR/.." && pwd -P)"

VERSION=""
OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --version) VERSION="${2:-}"; shift 2 ;;
        --version=*) VERSION="${1#*=}"; shift ;;
        --out) OUT="${2:-}"; shift 2 ;;
        --out=*) OUT="${1#*=}"; shift ;;
        -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "未知参数：$1" >&2; exit 2 ;;
    esac
done

log() { printf '[mkmodule] %s\n' "$*" >&2; }
die() { printf '[mkmodule] 错误: %s\n' "$*" >&2; exit 1; }

command -v zip >/dev/null 2>&1 || die "缺少 zip 命令"

# ---- 模块自带的文件（module/ 下） ------------------------------------------
MODULE_FILES=(module.prop customize.sh post-fs-data.sh service.sh uninstall.sh)
for f in "${MODULE_FILES[@]}"; do
    [ -f "$SELF_DIR/$f" ] || die "模块缺少必需文件：module/$f"
done
# 可选文档
OPTIONAL_FILES=(README-sepolicy.md)

# ---- 要铺进 bin/ 的运行时脚本：**由目录派生，不手写清单** -------------------
# ★ 为什么改成 glob：以前这里是手写列表，于是同一个 bug 犯了三次 ——
#     detect-mount.sh 没进包 → customize 的探测静默失效；
#     lib/ 没人同步 → doctor §1c 静默 skip；
#     update.sh 没进包 → WebUI 的更新按钮直接报"找不到 update.sh"。
#   手写清单必然漂移，所以：**bin/ 内容 = runtime/root/*.sh 全量 + rootfs/layer-spec.sh**。
#   新增脚本会自动进包；只会在"忘了加必需项断言"时退化，而不会整块功能消失。
BIN_SRC_DIR="$REPO_DIR/runtime/root"
[ -d "$BIN_SRC_DIR" ] || die "找不到运行时脚本目录：$BIN_SRC_DIR"

# 必需项断言：这些缺任何一个都说明包是残的，直接拒绝打包
BIN_REQUIRED=(
    linuxctl.sh          # 主入口（App 通过 su 调用）
    layer-spec.sh        # linuxctl.sh 会 source 它（唯一事实源）
    start.sh  stop.sh  status.sh
    entry.sh             # 环境内入口（也会被装进 runtime 层的 /opt/sunsetlinux）
    supervise.sh
    doctor.sh
    update.sh            # WebUI / App 的更新入口（缺失→更新功能整块失效）
    selftest.sh          # 回归测试（真机上也能跑）
    device-provision.sh  # 设备侧首次部署
    common/status_json.sh common/http_health.sh
)

BIN_COMMON=(status_json.sh http_health.sh)
BIN_FIXTURES=(erofs-head.bin squashfs-head.bin zstd-head.bin)
# ---- 解析实际路径 -----------------------------------------------------------
# layer-spec.sh 在 rootfs/，其余在 runtime/root/，common 在 runtime/common/
resolve_src() { # resolve_src <name> <subdir>
    local n="$1" sub="$2" c
    for c in "$REPO_DIR/$sub/$n" "$REPO_DIR/runtime/root/$n" "$REPO_DIR/$n"; do
        [ -f "$c" ] && { printf '%s' "$c"; return 0; }
    done
    return 1
}

# ---- 版本：优先命令行，其次 module.prop 里的 version= -----------------------
if [ -z "$VERSION" ]; then
    VERSION="$(sed -n 's/^version=//p' "$SELF_DIR/module.prop" | head -n1)"
fi
[ -n "$VERSION" ] || die "无法确定版本（module.prop 里没有 version=，请用 --version 指定）"

# ---- 组装 -------------------------------------------------------------------
STAGE="$REPO_DIR/build/module-pkg"
rm -rf "$STAGE"
mkdir -p "$STAGE/bin/common"
trap 'rm -rf "$STAGE"' EXIT

for f in "${MODULE_FILES[@]}"; do cp -f "$SELF_DIR/$f" "$STAGE/$f"; done
for f in "${OPTIONAL_FILES[@]}"; do [ -f "$SELF_DIR/$f" ] && cp -f "$SELF_DIR/$f" "$STAGE/$f"; done

# ---- lib/：模块内共享的 shell 库 -------------------------------------------
# ★ `customize.sh` 会 source `$MODDIR/lib/detect-mount.sh`（挂载实现探测）。
#   漏打包不会崩（customize.sh 有容错），但探测会被**静默跳过** —— 正是那种"看起来没事、
#   实际功能没了"的坑，所以这里既复制又断言。
MODULE_LIB_REQUIRED=(detect-mount.sh)
if [ -d "$SELF_DIR/lib" ]; then
    rm -rf "$STAGE/lib"
    cp -rf "$SELF_DIR/lib" "$STAGE/lib"
    chmod 0644 "$STAGE/lib"/*.sh 2>/dev/null || true
    log "lib/ 铺入 $(find "$STAGE/lib" -type f | wc -l) 个文件"
else
    log "警告：$SELF_DIR/lib 不存在"
fi
for f in "${MODULE_LIB_REQUIRED[@]}"; do
    [ -f "$STAGE/lib/$f" ] || die "模块缺少 lib/$f（customize.sh 会 source 它；缺了探测功能会静默失效）"
done

missing=0
# ① runtime/root/*.sh 全量（glob，自动包含以后新增的脚本）
bin_count=0
for src in "$BIN_SRC_DIR"/*.sh; do
    [ -f "$src" ] || continue
    install -m 0755 "$src" "$STAGE/bin/$(basename "$src")"
    bin_count=$(( bin_count + 1 ))
done
[ "$bin_count" -gt 0 ] || die "$BIN_SRC_DIR 里没有 .sh 脚本（路径不对？）"

# ② 有几个脚本住在 rootfs/（不是 runtime/root/），单独铺。
#    它们同样是"运行时"的一部分（linuxctl 会 source layer-spec.sh；
#    device-provision.sh 是设备侧首次部署入口），所以照样进 bin/。
for f in layer-spec.sh device-provision.sh; do
    src="$(resolve_src "$f" rootfs || true)"
    [ -n "$src" ] || die "找不到 rootfs/$f"
    install -m 0755 "$src" "$STAGE/bin/$f"
done

# ③ common/
for f in "${BIN_COMMON[@]}"; do
    src="$(resolve_src "$f" "runtime/common" || true)"
    [ -n "$src" ] || { log "缺少脚本：common/$f"; missing=1; continue; }
    install -m 0644 "$src" "$STAGE/bin/common/$f"
done

# ④ 必需项断言（缺一个就 die，不让残缺包流出去）
for f in "${BIN_REQUIRED[@]}"; do
    if [ ! -f "$STAGE/bin/$f" ]; then
        log "必需文件没进 stage：bin/$f"
        missing=1
    fi
done
[ "$missing" -eq 0 ] || die "有必需脚本缺失，拒绝打包（否则模块装上去是残缺的）"
log "bin/ 由目录派生：$bin_count 个 runtime/root 脚本 + layer-spec.sh + $((${#BIN_COMMON[@]})) 个 common"

# ---- profiles/：设备侧构建的"素材"（包清单 / web profile 模板 / 安装脚本）----
# ★ 为什么必须进包（真机实测，2026-09-16）：`device-provision.sh` 在设备上就是靠
#   `$SELF_DIR/../profiles/…` 找这些素材的。1.0.5 及更早**没有打包它们**，于是真机上：
#     · `素材：base.packages=未找到` → base 层退化成内置最小集
#     · `素材：runtime.packages=未找到`
#     · 跑到 dsh 阶段才 `die "缺少 profile 素材"` —— 用户已经白等 20 分钟。
#   所以这里既复制、也**断言**（跟 bin/ 的必需项断言同一个道理：残包不许流出去）。
PROFILES_SRC="$REPO_DIR/rootfs/profiles"
[ -d "$PROFILES_SRC" ] || die "找不到 $PROFILES_SRC —— 模块必须随包携带 profiles/"
mkdir -p "$STAGE/profiles"
cp -rf "$PROFILES_SRC"/. "$STAGE/profiles/"
chmod -R a+rX "$STAGE/profiles" 2>/dev/null || true
for f in base.packages runtime.packages install-web-profile.sh; do
    [ -f "$STAGE/profiles/$f" ] || { log "profiles/ 缺文件：$f"; missing=1; }
done
[ -d "$STAGE/profiles/web-profile" ] || { log "profiles/ 缺目录：web-profile/"; missing=1; }
[ "$missing" -eq 0 ] || die "profiles/ 不全（设备侧构建会失败/退化），拒绝打包"
log "profiles/ 铺入 $(find "$STAGE/profiles" -type f | wc -l) 个文件（base.packages / runtime.packages / web-profile / install-web-profile.sh）"

# ---- WebUI（KernelSU 模块 WebUI：模块根必须有 webroot/index.html）-----------
# KernelSU 管理器会在模块详情页打开 webroot/index.html（Magisk 需 MMRL 等第三方宿主）。
# **必须有**：没有它 WebUI 入口就不存在，而"不开 App 也能启停/看状态/更新"是用户明确要的能力。
# 注意：webroot/ 只在模块目录里，**不往 $LINUX_HOME 同步**
#   —— 管理器是直接从模块目录读它的，同步过去没有意义（也不会被读）。
if [ ! -f "$SELF_DIR/webroot/index.html" ]; then
    die "缺少 module/webroot/index.html —— KernelSU 的 WebUI 入口就靠它，不能缺"
fi
mkdir -p "$STAGE/webroot"
cp -rf "$SELF_DIR/webroot/." "$STAGE/webroot/"
chmod -R a+rX "$STAGE/webroot" 2>/dev/null || true
install -m 0644 "$SELF_DIR/webroot/index.html" "$STAGE/webroot/index.html"

# ---- 夹具：让 selftest.sh 在真机上也能真跑（否则只能 SKIP 掉全部断言） --------
mkdir -p "$STAGE/bin/fixtures"
for f in "${BIN_FIXTURES[@]}"; do
    # 优先仓库内可跟踪的 testdata/fixtures（build/ 是 gitignore 的，CI 上没有）
    src="$REPO_DIR/testdata/fixtures/$f"
    [ -f "$src" ] || src="$REPO_DIR/build/fixtures/$f"
    if [ -f "$src" ]; then
        install -m 0644 "$src" "$STAGE/bin/fixtures/$f"
    else
        log "警告：缺少夹具 $f —— 真机上 selftest 会 SKIP 相关断言"
    fi
done

# ---- 写入版本 ---------------------------------------------------------------
# module.prop 的 version 用给定版本；versionCode 若没有就按语义版本折算
if grep -q '^version=' "$STAGE/module.prop"; then
    sed -i "s|^version=.*|version=$VERSION|" "$STAGE/module.prop"
else
    printf 'version=%s\n' "$VERSION" >> "$STAGE/module.prop"
fi
{
    printf 'packed_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'packer=mkmodule.sh\n'
    printf 'version=%s\n' "$VERSION"
    printf '\n--- webroot/ 内容 ---\n'
    ( cd "$STAGE" && find webroot -type f 2>/dev/null | sort )
    printf '\n--- bin/ 内容 ---\n'
    ( cd "$STAGE" && find . -type f | sort )
} > "$STAGE/MANIFEST.txt"

log "版本：$VERSION"
log "bin/ 铺入 $(find "$STAGE/bin" -type f | wc -l) 个脚本"
log "顶层文件：$(find "$STAGE" -maxdepth 1 -type f | wc -l) 个"

# ---- 打包 -------------------------------------------------------------------
[ -n "$OUT" ] || OUT="$REPO_DIR/dist/sunsetlinux-module-$VERSION.zip"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
( cd "$STAGE" && zip -q -r -X "$OUT" . )
[ -f "$OUT" ] || die "打包失败"

# 校验 zip 内确实有 libproot 之外的必备项
command -v unzip >/dev/null 2>&1 && {
    unzip -l "$OUT" | grep -q 'module.prop'      || die "zip 里没有 module.prop"
    unzip -l "$OUT" | grep -q 'bin/linuxctl.sh'  || die "zip 里没有 bin/linuxctl.sh"
    unzip -l "$OUT" | grep -q 'bin/layer-spec.sh'|| die "zip 里没有 bin/layer-spec.sh"
    # ★ KernelSU 只在模块根找 webroot/index.html：缺了 WebUI 入口就没了
    unzip -l "$OUT" | grep -q 'webroot/index.html' || die "zip 里没有 webroot/index.html（KernelSU 的 WebUI 入口）"
    unzip -l "$OUT" | grep -q 'lib/detect-mount.sh' || die "zip 里没有 lib/detect-mount.sh（挂载实现探测）"
}

sha256sum "$OUT" > "$OUT.sha256"
printf '\n产物：%s\n  %s  %s 字节\n  %s\n' \
    "$OUT" "$(sha256sum "$OUT" | cut -d' ' -f1)" "$(stat -c %s "$OUT")" "$OUT.sha256"
