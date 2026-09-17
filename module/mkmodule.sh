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
#   bash module/mkmodule.sh [--version <ver>] [--out <zip 路径>] [--variant full|bare]
#                           [--dsh-layer <dsh-<版本>.erofs.gz>] [--dsh-sums <SHA256SUMS.layers.txt>]
# 产物（见 docs/module-variants.md §2）：
#   full → dist/sunsetlinux-module-<ver>.zip        ← **默认版本**，自带 DSH（dsh/ 目录）
#   bare → dist/sunsetlinux-module-<ver>-bare.zip   ← 不带 DSH，一条指令从频道装
#
# 为什么默认是 full 但**必须显式给 --dsh-layer**：
#   没有 dsh 层却产出"默认名"的包，就是"把不带 DSH 的包冒充默认版本"—— 用户装完
#   发现起不来，而这正是要消灭的失败模式。所以两条都硬拦：
#     · full 且没给 --dsh-layer → 拒绝打包（提示改用 --variant bare）
#     · bare 且给了 --dsh-layer → 也拒绝（不许"标 bare 却夹带"）
# =============================================================================
set -euo pipefail

SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_DIR="$(cd -- "$SELF_DIR/.." && pwd -P)"

VERSION=""
OUT=""
VARIANT="full"
DSH_LAYER=""
DSH_SUMS=""
while [ $# -gt 0 ]; do
    case "$1" in
        --version) VERSION="${2:-}"; shift 2 ;;
        --version=*) VERSION="${1#*=}"; shift ;;
        --out) OUT="${2:-}"; shift 2 ;;
        --out=*) OUT="${1#*=}"; shift ;;
        --variant) VARIANT="${2:-}"; shift 2 ;;
        --variant=*) VARIANT="${1#*=}"; shift ;;
        --dsh-layer) DSH_LAYER="${2:-}"; shift 2 ;;
        --dsh-layer=*) DSH_LAYER="${1#*=}"; shift ;;
        --dsh-sums) DSH_SUMS="${2:-}"; shift 2 ;;
        --dsh-sums=*) DSH_SUMS="${1#*=}"; shift ;;
        -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "未知参数：$1" >&2; exit 2 ;;
    esac
done

log() { printf '[mkmodule] %s\n' "$*" >&2; }
die() { printf '[mkmodule] 错误: %s\n' "$*" >&2; exit 1; }

# 官方频道 URL：与 App（core/Prefs.kt 的 OFFICIAL）、runtime/root/update.sh 里的**同一把**。
# 三处必须逐字一致（App 有 OfficialChannelContractTest 盯着；改了这里就要同步）。
SUNSETLINUX_OFFICIAL_CHANNEL_URL="${SUNSETLINUX_OFFICIAL_CHANNEL_URL:-https://sunsetrne.github.io/SunsetLinux/channel/channel.json}"

# 「编译只在 CI」的可判定形式之一（docs/module-variants.md §3.2）：
# 本地打出来的包只用于开发/排障，不作为发布来源 —— 这里出声，并落一个标记。
if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
    log "⚠️  本次是**本地打包**：产物仅供开发/排障，不作为发布来源（发布只由 CI 的 main 流水线产出）"
fi

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

BIN_COMMON=(status_json.sh http_health.sh layer-inspect.sh)
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

# ---- 变体校验 + 内嵌 DSH 层的元信息 -----------------------------------------
# 规则（docs/module-variants.md §2.1/§2.6）：默认 full 必须有层；bare 必须没有层。
# 这条硬拦是**故意的**：允许"full 但没层"就等于允许发布一个冒充默认版本的残包。
case "$VARIANT" in
    full|bare) ;;
    *) die "未知变体：$VARIANT（只支持 full / bare）" ;;
esac

DSH_VER=""
DSH_FILE=""
DSH_SHA_GZ=""
DSH_SHA_RAW=""
DSH_SIZE_RAW=""
DSH_SRC_NAME=""

if [ "$VARIANT" = "full" ]; then
    [ -n "$DSH_LAYER" ] || die "full 变体必须给 --dsh-layer <dsh-<版本>.erofs.gz>（拿不到层就用 --variant bare）"
fi
if [ "$VARIANT" = "bare" ] && [ -n "$DSH_LAYER" ]; then
    die "bare 变体不接受 --dsh-layer（不许标 bare 却夹带 DSH）"
fi

if [ -n "$DSH_LAYER" ]; then
    [ -f "$DSH_LAYER" ] || die "找不到 dsh 层文件：$DSH_LAYER"
    DSH_SRC_NAME="$(basename "$DSH_LAYER")"
    case "$DSH_SRC_NAME" in
        *.erofs.gz) ;;
        *) die "只接受 .erofs.gz（设备侧没有 zstd；裸镜像 201 MB 也不该塞进模块）——实际：$DSH_SRC_NAME" ;;
    esac
    DSH_VER="${DSH_SRC_NAME#dsh-}"
    DSH_VER="${DSH_VER%.erofs.gz}"
    [ -n "$DSH_VER" ] && [ "$DSH_VER" != "$DSH_SRC_NAME" ] \
        || die "层文件名要形如 dsh-<版本>.erofs.gz（解析不出版本）：$DSH_SRC_NAME"
    DSH_FILE="dsh-$DSH_VER.erofs.gz"
    DSH_SHA_GZ="$(sha256sum "$DSH_LAYER" | awk '{print $1}')"

    # sha256_raw / size_raw 从频道的 SHA256SUMS.layers.txt 里取（**绝不填 0 冒充**）。
    # 取不到就留 null：那只是"解压后没法二次核对"，不影响安装，但 manifest 要如实写。
    [ -n "$DSH_SUMS" ] || DSH_SUMS="$REPO_DIR/dist/SHA256SUMS.layers.txt"
    if [ -f "$DSH_SUMS" ]; then
        line="$(awk -v want="dsh-$DSH_VER.erofs" '$2==want || $3==want {print; exit}' "$DSH_SUMS" 2>/dev/null || true)"
        # 两种格式都认：`<sha256_raw>  dsh-<ver>.erofs <size_raw>`（本项目）与 sha256sum 的 `<sha>  <file>`
        DSH_SHA_RAW="$(printf '%s' "$line" | awk '{print $1}')"
        DSH_SIZE_RAW="$(printf '%s' "$line" | awk 'NF>=3 && $3 ~ /^[0-9]+$/ {print $3}')"
    fi
    log "内嵌 DSH 层：$DSH_FILE（版本 $DSH_VER，gz sha256 ${DSH_SHA_GZ:0:12}…，raw ${DSH_SHA_RAW:0:12}…）"
fi

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

# ---- dsh/：**内嵌 DSH 层**（只有 full 变体；docs/module-variants.md §2.2）------
# 这里放的是**官方签名频道里的 gzip 产物**（不是裸镜像，也不是"DSH 源码树"）：
#   · 设备侧只有 toybox 的 gzip，没有 zstd → 只能 .gz
#   · 裸镜像 201 MB 压不动 → 不该进模块 zip
#   · 让设备自己构建 DSH 层正是 `supervise.sh exit 78` 的成因（缺 web profile / pnpm /
#     软链层级）—— 把它交给 CI 一次做好，才是"只缺 DSH 这一块"的正确补法。
# manifest.json 是**落地时唯一要读的东西**：版本、文件名、两个 sha256、raw 大小、来源。
if [ "$VARIANT" = "full" ]; then
    mkdir -p "$STAGE/dsh"
    install -m 0644 "$DSH_LAYER" "$STAGE/dsh/$DSH_FILE"
    {
        printf '{\n'
        printf '  "schema": 1,\n'
        printf '  "layer": "dsh",\n'
        printf '  "variant": "full",\n'
        printf '  "version": "%s",\n' "$DSH_VER"
        printf '  "file": "%s",\n' "$DSH_FILE"
        printf '  "sha256_gz": "%s",\n' "$DSH_SHA_GZ"
        printf '  "sha256_raw": %s,\n' "$( [ -n "$DSH_SHA_RAW" ] && printf '"%s"' "$DSH_SHA_RAW" || printf 'null' )"
        printf '  "size_raw": %s,\n' "$( [ -n "$DSH_SIZE_RAW" ] && printf '%s' "$DSH_SIZE_RAW" || printf 'null' )"
        printf '  "source": {\n'
        printf '    "kind": "channel",\n'
        printf '    "url": "%s",\n' "$SUNSETLINUX_OFFICIAL_CHANNEL_URL"
        printf '    "built_at": "%s"\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf '  }\n'
        printf '}\n'
    } > "$STAGE/dsh/manifest.json"
    [ -f "$STAGE/dsh/$DSH_FILE" ] || die "dsh 载荷没铺进 stage"
    log "dsh/ 铺入：$DSH_FILE + manifest.json"
else
    rm -rf "$STAGE/dsh"
    log "变体 bare：不内嵌 DSH（DSH 从内置官方频道用 linuxctl dsh install 一条指令装）"
fi

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

# ---- 写入版本 + 变体标识 -----------------------------------------------------
# module.prop 的 version 用给定版本；versionCode 若没有就按语义版本折算。
# variant= 是**新增键**（KernelSU/Magisk 忽略未知键）：让用户与工具都能一眼看出
# "这个包自带 DSH 还是不带"，也是回归断言（tools/module-variant-selftest.mjs）的抓手。
if grep -q '^version=' "$STAGE/module.prop"; then
    sed -i "s|^version=.*|version=$VERSION|" "$STAGE/module.prop"
else
    printf 'version=%s\n' "$VERSION" >> "$STAGE/module.prop"
fi
if grep -q '^variant=' "$STAGE/module.prop"; then
    sed -i "s|^variant=.*|variant=$VARIANT|" "$STAGE/module.prop"
else
    printf 'variant=%s\n' "$VARIANT" >> "$STAGE/module.prop"
fi
# 描述里写明变体（模块管理器里显示的就是它）：用户不打开包也该知道装的是哪一种。
case "$VARIANT" in
    full) _VNOTE="本包**自带 DSH**（$DSH_VER）：装完重启即可用，可更新、可一键回滚到内置版本。" ;;
    bare) _VNOTE="本包**不含 DSH**：DSH 用一条指令从官方频道装（linuxctl dsh install）。" ;;
esac
if grep -q '^description=' "$STAGE/module.prop"; then
    _DBASE="$(sed -n 's/^description=//p' "$STAGE/module.prop" | head -n1 | sed 's/ *\[变体[^]]*\]$//')"
    printf '%s [变体 %s]\n' "$_DBASE" "$VARIANT" > "$STAGE/.desc"
    sed -i "s|^description=.*|description=$(cat "$STAGE/.desc")|" "$STAGE/module.prop"
    rm -f "$STAGE/.desc"
fi
{
    printf 'packed_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'packer=mkmodule.sh\n'
    printf 'version=%s\n' "$VERSION"
    printf 'variant=%s\n' "$VARIANT"
    printf 'build_source=%s\n' "$( [ "${GITHUB_ACTIONS:-}" = "true" ] && printf 'ci' || printf 'local' )"
    [ -n "$DSH_VER" ] && printf 'dsh_version=%s\n' "$DSH_VER"
    printf 'note=%s\n' "$_VNOTE"
    printf '\n--- webroot/ 内容 ---\n'
    ( cd "$STAGE" && find webroot -type f 2>/dev/null | sort )
    printf '\n--- bin/ 内容 ---\n'
    ( cd "$STAGE" && find . -type f | sort )
} > "$STAGE/MANIFEST.txt"

log "版本：$VERSION；变体：$VARIANT${DSH_VER:+（内置 DSH $DSH_VER）}"
log "bin/ 铺入 $(find "$STAGE/bin" -type f | wc -l) 个脚本"
log "顶层文件：$(find "$STAGE" -maxdepth 1 -type f | wc -l) 个"

# ---- 打包 -------------------------------------------------------------------
# 命名规则（docs/module-variants.md §2.6）：**默认名只给 full**。
# bare 一律带 -bare 后缀 —— 它就是"告诉用户这个包不带 DSH"的那块牌子。
DIST_DIR="${SUNSETLINUX_DIST_DIR:-$REPO_DIR/dist}"
if [ -z "$OUT" ]; then
    case "$VARIANT" in
        full) OUT="$DIST_DIR/sunsetlinux-module-$VERSION.zip" ;;
        bare) OUT="$DIST_DIR/sunsetlinux-module-$VERSION-bare.zip" ;;
    esac
fi
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
( cd "$STAGE" && zip -q -r -X "$OUT" . )
[ -f "$OUT" ] || die "打包失败"

# 校验 zip 内确实有 libproot 之外的必备项
# ★ **不要写 `unzip -l "$OUT" | grep -q 模式`**：本脚本是 `set -o pipefail`，
#   `grep -q` 一命中就退出，unzip 可能吃 SIGPIPE(141)；反过来 unzip 因为一句无关的
#   warning 返回非 0 也会把管道判成失败 —— 两种都会把**正常包**说成坏包。
#   真事故（0.3.6 首跑）：同一个包、同一段代码，一次绿一次红，报"zip 里没有 module.prop"，
#   而包里明明有它。本项目在 linuxctl 里也踩过同一个坑（见那里的注释）。
#   改法：把清单读进变量，用 case 做字符串匹配（无管道、无子进程、也不看 unzip 的退出码）。
if command -v unzip >/dev/null 2>&1; then
    ZIP_LIST="$(unzip -l "$OUT" 2>/dev/null || true)"
    _zip_has() { # <成员路径> <人话说明>
        case "$ZIP_LIST" in
            *"$1"*) return 0 ;;
            *)
                printf '[mkmodule] zip 里没有 %s（%s）\n' "$1" "$2" >&2
                printf '[mkmodule] 实际清单（前 30 行）：\n' >&2
                printf '%s\n' "$ZIP_LIST" | sed -n '1,30p' >&2 || true
                die "打包结果不完整：缺 $1"
                ;;
        esac
    }
    _zip_has 'module.prop'        '模块标识文件'
    _zip_has 'bin/linuxctl.sh'    '主入口脚本'
    _zip_has 'bin/layer-spec.sh'  '层规格事实源'
    # ★ KernelSU 只在模块根找 webroot/index.html：缺了 WebUI 入口就没了
    _zip_has 'webroot/index.html' 'KernelSU 的 WebUI 入口'
    _zip_has 'lib/detect-mount.sh' '挂载实现探测'
    # ★ 变体自洽：full 必须有载荷、bare 必须没有（"冒充默认版本"在这里就被拦下）
    if [ "$VARIANT" = "full" ]; then
        _zip_has 'dsh/manifest.json' 'DSH 载荷清单'
        _zip_has "dsh/$DSH_FILE"     'DSH 载荷本体'
    else
        case "$ZIP_LIST" in
            *'dsh/'*) die "bare 变体里出现了 dsh/（标 bare 却夹带 DSH）" ;;
        esac
    fi
fi

# 「本地构建」标记：与 mkmodule 的警告配套（仅记录，不阻断）
if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
    printf 'source=local\nat=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$(dirname "$OUT")/.build-source"
fi

sha256sum "$OUT" > "$OUT.sha256"
printf '\n产物：%s\n  %s  %s 字节\n  %s\n' \
    "$OUT" "$(sha256sum "$OUT" | cut -d' ' -f1)" "$(stat -c %s "$OUT")" "$OUT.sha256"
