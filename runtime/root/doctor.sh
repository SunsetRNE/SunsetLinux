#!/usr/bin/env bash
# =============================================================================
# dshroid · runtime/root/doctor.sh
#
# 自检：内核能力 / 层完整性 / 可写层可挂性 / 端口占用 / SELinux denial / proot
#       可用性 / DSH profile 可解析性。
# 输出：人类可读报告走 stdout（doctor 是给人看的），**最后一行是 JSON**。
# 退出码：0 = 没有 fail（warn 可以有）；1 = 至少一个 fail。
#
# 设计原则：
#   - 读操作优先，绝不改设备状态（挂载一律挂到 mktemp -d 的临时点并立刻卸载）。
#   - 每条结论都给出"怎么修"，而不是只报问题。
#   - 内核能力用 /proc/config.gz（CONFIG_IKCONFIG_PROC）判定，取不到就退回
#     /proc/filesystems + 实测探测，并标注证据来源（避免"猜"）。
# =============================================================================
set -uo pipefail

# 脚本自身目录：用于定位可选的辅助脚本（detect-mount.sh 等）
SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" && pwd -P)"

LINUX_HOME="${LINUX_HOME:-/data/linux}"
LH="$LINUX_HOME"
LAYERS_DIR="$LH/layers"
ETC_DIR="$LH/etc"
RUN_DIR="$LH/run"
UPPER_IMG="$LH/upper.img"
ROOTFS_DIR="$LH/rootfs"

UMOUNT=/system/bin/umount
MOUNT=/system/bin/mount

FAILS=0
WARNS=0

ok()   { printf '  \033[32m[ok]\033[0m   %s\n' "$*"; }
warn() { WARNS=$((WARNS+1)); printf '  \033[33m[warn]\033[0m %s\n' "$*"; }
bad()  { FAILS=$((FAILS+1)); printf '  \033[31m[fail]\033[0m %s\n' "$*"; }
info() { printf '  \033[36m[info]\033[0m %s\n' "$*"; }
head_() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

# 层格式探测：
#   squashfs 'hsqs' 在**偏移 0**；erofs 0xE0F5E1E2 在**偏移 1024**（超级块不在文件开头！）
# 只读偏移 0 会把真实 erofs 误判为 unknown —— 见 start.sh 里同一处注释的实测记录。
_magic_at() {
    local f="$1" off="$2" hex=""
    have od || return 1
    if [ "$off" = "0" ]; then
        hex="$(od -An -tx1 -N4 "$f" 2>/dev/null | tr -d ' \n')"
    else
        hex="$(dd if="$f" bs=1 skip="$off" count=4 2>/dev/null \
               | od -An -tx1 2>/dev/null | tr -d ' \n')"
    fi
    printf '%s' "$hex" | tr 'A-F' 'a-f'
}
layer_format() {
    local f="$1"
    [ -f "$f" ] || { printf 'missing'; return 1; }
    case "$(_magic_at "$f" 0)" in 68737173) printf 'squashfs'; return 0 ;; esac
    case "$(_magic_at "$f" 1024)" in e2e1f5e0) printf 'erofs'; return 0 ;; esac
    printf 'unknown'; return 1
}
# 支持 .erofs 与 .squashfs 两种扩展名
find_layer() {
    local name="$1" cand
    # ① 首选 state.json 记录的**当前生效版本**（"最高版本"≠"生效版本"，回滚靠它）
    if [ -f "$STATE" ]; then
        local ver=""
        ver="$(tr -d ' \n\t' < "$STATE" 2>/dev/null \
               | sed -n "s/.*\"$name\":{[^}]*\"version\":\"\([^\"]*\)\".*/\1/p" | head -n1)"
        if [ -n "$ver" ] && [ "$ver" != "unknown" ] && [ "$ver" != "null" ]; then
            for cand in "$LAYERS_DIR/$name-$ver.erofs" "$LAYERS_DIR/$name-$ver.squashfs"; do
                [ -f "$cand" ] && { printf '%s' "$cand"; return 0; }
            done
        fi
    fi
    # ② 回退：旧命名（无版本后缀）优先，其次扫描取最高版本
    local best=""
    for cand in "$LAYERS_DIR/$name.erofs" "$LAYERS_DIR/$name.squashfs"; do
        [ -f "$cand" ] && { printf '%s' "$cand"; return 0; }
    done
    for cand in "$LAYERS_DIR/$name"-*.erofs "$LAYERS_DIR/$name"-*.squashfs; do
        [ -f "$cand" ] || continue
        if [ -z "$best" ]; then best="$cand"; continue; fi
        if [ "$(printf '%s\n%s\n' "$best" "$cand" | sort -V | tail -n1)" = "$cand" ]; then
            best="$cand"
        fi
    done
    [ -n "$best" ] && { printf '%s' "$best"; return 0; }
    return 1
}
# 列出层内容（用于 profile/pnpm 存在性检查），按格式选工具
layer_list() {
    local f="$1" fmt
    fmt="$(layer_format "$f" 2>/dev/null || echo unknown)"
    case "$fmt" in
        squashfs) have unsquashfs && unsquashfs -l "$f" 2>/dev/null ;;
        erofs)    have dump.erofs && dump.erofs --ls --path=/ "$f" 2>/dev/null ;;
    esac
}

# JSON 字符串转义（不依赖 jq）
jesc() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    s="${s//$'\n'/ }"
    s="${s//$'\t'/ }"
    s="${s//$'\r'/}"
    printf '%s' "$s"
}

# 收集给 JSON 的结论（用 bash 数组，避免依赖外部工具）
declare -a FINDINGS=()
add_finding() { FINDINGS+=( "{\"level\":\"$1\",\"id\":\"$(jesc "$2")\",\"detail\":\"$(jesc "$3")\"}" ); }

# ===========================================================================
head_ "0. 运行环境"
printf '  LINUX_HOME = %s\n' "$LH"
printf '  内核       = %s\n' "$(uname -r 2>/dev/null || echo '?')"
printf '  架构       = %s\n' "$(uname -m 2>/dev/null || echo '?')"
# /proc/self/attr/current 是 **NUL 结尾**的；直接 cat 进命令替换会让 bash 报
# "ignored null byte in input" 这类看着像出错的告警。先用 tr 去掉 NUL。
_selinux_ctx="$(tr -d '\000' < /proc/self/attr/current 2>/dev/null || true)"
printf '  uid/ctx    = %s / %s\n' "$(id -u 2>/dev/null)" "${_selinux_ctx:-?}"
if [ ! -d "$LH" ]; then
    bad "$LH 不存在（还没 provision？执行 device-provision.sh 或 linuxctl provision）"
    add_finding fail linux_home_missing "$LH 不存在"
else
    ok "$LH 存在"
    add_finding ok linux_home "$LH 存在"
fi

# ===========================================================================
head_ "1. 内核能力（证据来源标注在括号里）"
KC=""
if [ -r /proc/config.gz ]; then
    if have zcat; then KC="$(zcat /proc/config.gz 2>/dev/null)"; fi
    [ -z "$KC" ] && KC="$(gzip -dc /proc/config.gz 2>/dev/null || true)"
fi
if [ -z "$KC" ] && [ -r /boot/config-"$(uname -r)" ]; then
    KC="$(cat /boot/config-"$(uname -r)" 2>/dev/null || true)"
fi

if [ -n "$KC" ]; then
    SRC="/proc/config.gz"
    # ⚠ 这里**刻意不用** `printf '%s' "$KC" | grep -q ...`：
    #   KC 有 200 KB，grep -q 命中后立刻退出 → printf 收到 SIGPIPE（退出码 141），
    #   在 `set -o pipefail` 下整条管道被判为失败，于是"匹配上了却报未启用"。
    #   这是个真实的坑（本机 proot 里实测到），改用 bash 内建子串匹配彻底绕开。
    #
    # 找出内核配置项的取值：y / m / 未设置（"# CONFIG_X is not set" 或整行不存在）
    kcval() {
        local name="$1" line
        # 用 case 做整行锚定匹配，避免正则与子串误命中（如 CONFIG_NAMESPACES 命中 CONFIG_NAMESPACES_XXX）
        local tmp="
$KC
"
        case "$tmp" in
            *"
CONFIG_${name}=y
"*) printf 'y'; return 0 ;;
            *"
CONFIG_${name}=m
"*) printf 'm'; return 0 ;;
        esac
        # 兜底：逐行扫描（防止最后一行没有换行）
        while IFS= read -r line; do
            case "$line" in
                "CONFIG_${name}=y") printf 'y'; return 0 ;;
                "CONFIG_${name}=m") printf 'm'; return 0 ;;
            esac
        done <<< "$KC"
        printf 'n'
    }
    for opt in NAMESPACES UTS_NS NET_NS OVERLAY_FS EXT4_FS TMPFS SQUASHFS; do
        v="$(kcval "$opt")"
        case "$v" in
            y)
                ok "CONFIG_${opt}=y （$SRC）"
                add_finding ok "config_$opt" "=y" ;;
            m)
                warn "CONFIG_${opt}=m（模块，需先加载；$SRC）"
                add_finding warn "config_$opt" "=m 需加载模块" ;;
            *)
                bad "CONFIG_${opt} 未启用（$SRC）"
                add_finding fail "config_$opt" "未启用" ;;
        esac
    done
else
    info "取不到 /proc/config.gz（内核未开 CONFIG_IKCONFIG_PROC），改用实测探测"
    # 实测：overlay / squashfs 会在 /proc/filesystems 里出现（注册后即列出）
    for fs in overlay squashfs ext4 tmpfs; do
        if grep -qw "$fs" /proc/filesystems 2>/dev/null; then
            ok "文件系统 $fs 已注册（/proc/filesystems）"
            add_finding ok "fs_$fs" "已注册"
        else
            warn "文件系统 $fs 未出现在 /proc/filesystems（可能只是尚未注册；ext4/overlay 也可能由内核内建但未列出）"
            add_finding warn "fs_$fs" "未出现在 /proc/filesystems"
        fi
    done
fi

printf '\n  —— 关键缺失项与其影响 ——\n'
IMPLACT=""
if [ -n "$KC" ]; then
    if [ "$(kcval PID_NS)" = y ]; then
        info "CONFIG_PID_NS=y（与 docs/findings.md §2 的实测不同，请在真机复核）"
    else
        info "CONFIG_PID_NS 未启用 → **不做 PID 命名空间容器**；不假设 PID 1 语义；不 unshare -p（start.sh 已遵守）"
        IMPLACT="${IMPLACT}pidns_absent "
    fi
    if [ "$(kcval USER_NS)" = y ]; then
        info "CONFIG_USER_NS=y"
    else
        info "CONFIG_USER_NS 未启用 → 无法在非 root 下做 overlay/bind 挂载；非 root 只能走 proot 降级路线"
        IMPLACT="${IMPLACT}userns_absent "
    fi
    if [ "$(kcval SYSVIPC)" = y ]; then
        info "CONFIG_SYSVIPC=y"
    else
        info "CONFIG_SYSVIPC 未启用 → fakeroot/faked 不可用（本项目不需要）；不依赖 SysV IPC"
        IMPLACT="${IMPLACT}sysvipc_absent "
    fi
fi

# 真 root 能力
CAPEFF="$(grep -E '^CapEff:' /proc/self/status 2>/dev/null | awk '{print $2}')"
if [ -n "${CAPEFF:-}" ] && [ "$CAPEFF" != "0000000000000000" ]; then
    ok "CapEff=$CAPEFF（非零 → 有真 capabilities）"
    add_finding ok capabilities "CapEff=$CAPEFF"
else
    bad "CapEff=${CAPEFF:-未知}（为零 → 不是真 root，mount/chroot 都会失败）"
    add_finding fail capabilities "CapEff 为空或零"
fi

# ===========================================================================
# ===========================================================================
head_ "1b. 命令能力探测（toybox 的 mount/unshare 选项支持面）"
# 为什么单独一节：/system/bin/{mount,umount,unshare,chroot,losetup} 全是 **toybox 软链**，
# 不是 util-linux。toybox 的选项支持面窄，且各 Android 版本/厂商配置不同，
# 而我们又不能在设备 shell 上直接 `mount --help`（策略拦截）。
# 所以由 start.sh 在启动时**无害探测**并把结果缓存到 run/cmdprobe，这里读出来做成表。
PROBE_FILE="$RUN_DIR/cmdprobe"
probe_val() {
    local v=""
    [ -f "$PROBE_FILE" ] && v="$(sed -n "s/^$1=//p" "$PROBE_FILE" 2>/dev/null | head -n1)"
    printf '%s' "${v:-未探测}"
}

# toybox 识别（软链指向 toybox 即认为是 toybox 实现）
is_toybox() {
    local p="$1" t=""
    [ -e "$p" ] || { printf 'no'; return; }
    if [ -L "$p" ]; then
        t="$(readlink "$p" 2>/dev/null)"
        case "$t" in *toybox*) printf 'yes'; return ;; esac
        # 也可能是指向 /system/bin/toybox 的相对链接
        t="$(readlink -f "$p" 2>/dev/null || true)"
        case "$t" in *toybox*) printf 'yes'; return ;; esac
    fi
    printf 'no'
}

printf '\n  %-26s %-10s %s\n' "命令" "实现" "说明"
printf '  %-26s %-10s %s\n' "--------------------------" "----------" "------------------------------"
for c in /system/bin/mount /system/bin/umount /system/bin/unshare /system/bin/chroot /system/bin/losetup; do
    [ -e "$c" ] || { printf '  %-26s %-10s %s\n' "$c" "缺失" "该功能无法使用"; continue; }
    printf '  %-26s %-10s %s\n' "$(basename "$c")" "$(is_toybox "$c")" ""
done

printf '\n  选项支持（来自 start.sh 的运行时探测，缓存 %s）\n' "$PROBE_FILE"
if [ -f "$PROBE_FILE" ]; then
    probe_tbl() { # probe_tbl <key> <描述> <降级方式>
        local v; v="$(probe_val "$1")"
        local mark
        case "$v" in
            long|short|ok|yes) mark="\033[32m支持\033[0m" ;;
            no|none)           mark="\033[31m不支持\033[0m" ;;
            未探测)            mark="\033[33m$v\033[0m" ;;
            *)                 mark="\033[33m$v\033[0m" ;;
        esac
        printf '    %-24s %b   降级：%s\n' "$2" "$mark" "$3"
    }
    probe_tbl mount_rbind          "mount --rbind"        "-o rbind → -o bind（非递归）"
    probe_tbl mount_rbind_o        "mount -o rbind"       "-o bind（子挂载会丢，有告警）"
    probe_tbl mount_make_rslave    "mount --make-rslave"  "跳过（私有 ns 已隔断传播，非致命）"
    probe_tbl mount_remount_bind   "mount remount,bind"   "-o remount,<opts>"
    probe_tbl unshare_propagation  "unshare --propagation" "内层 mount --make-rprivate /"
    probe_tbl util_mount           "util-linux 回退"      "base 层 /usr/bin/mount + 其 ld.so --library-path"

    # 结论式提醒
    ur="$(probe_val unshare_propagation)"; um="$(probe_val util_mount)"
    if [ "$ur" = "no" ]; then
        info "unshare 不支持 --propagation：start.sh 会自动改用内层 make-rprivate（已实现，不影响功能）"
    fi
    if [ "$um" = "no" ]; then
        info "util-linux 回退不可用：若 toybox 的 rbind 也失败，关键挂载（proc/sys/dev）会起不来"
    fi
    add_finding ok cmd_probe "rbind=$(probe_val mount_rbind) rslave=$(probe_val mount_make_rslave) unshare_prop=$(probe_val unshare_propagation) util=$(probe_val util_mount)"
else
    warn "还没有探测结果（环境从未 start 过）。执行一次 linuxctl start 后会生成 $PROBE_FILE"
    info "也可以手动探测：LINUX_HOME=$LH bash $LH/bin/start.sh --probe-only 2>&1 | tail"
    add_finding warn cmd_probe "未探测（先跑一次 start）"
fi

# 非 toybox 的常规工具（如果设备上装了别的 mount，这里会显示出来）
for c in /system/xbin/mount /sbin/mount /usr/bin/mount; do
    if [ -e "$c" ]; then
        if [ "$(is_toybox "$c")" = "yes" ]; then
            info "另发现 $c（toybox 实现）"
        else
            info "另发现 $c（非 toybox 实现 —— 可能是可用的 util-linux 回退）"
        fi
    fi
done

# ===========================================================================
head_ "1c. 挂载实现（root 管理器 / KernelSU metamodule）"
# 背景（实测）：KernelSU 某版本起**删掉了自带的模块挂载实现**，改为完全交给第三方
# metamodule（具体实现由第三方提供，各设备不同）；Magisk 仍是原生挂载。
# 社区里大量模块是"自动挂载"型，它们的假设在装了 metamodule 的环境里经常不成立。
# DSHroid 是**纯脚本模块**（module/ 下没有 system/、vendor/ 等目录），**不需要挂载**，
# 所以不受这次变动影响 —— 这条要在报告里明确讲出来，省掉用户排查。
#
# ★ 上下文守卫（重要）：/data/adb 在不同上下文可见性完全不同 ——
#     proot 内 / 我们的 chroot 环境内：条目很少且常缺 modules/
#     原生 root：完整（ksu / ksud / metamodule / modules / …）
#   我们的 chroot 挂载树只 bind 了 /dev /proc /sys sdcard，**没 bind /data/adb**。
#   所以这里**必须先判上下文**：不可信就标 [skip]，绝不断言"没有 metamodule"。
DETECT_MOUNT=""
for _c in "$SELF_DIR/lib/detect-mount.sh" \
          "$SELF_DIR/../../module/lib/detect-mount.sh" \
          "$SELF_DIR/../module/lib/detect-mount.sh" \
          "$SELF_DIR/../lib/detect-mount.sh" "$SELF_DIR/common/detect-mount.sh"; do
    [ -f "$_c" ] && { DETECT_MOUNT="$_c"; break; }
done
if [ -z "$DETECT_MOUNT" ]; then
    info "[skip] 找不到 lib/detect-mount.sh，跳过挂载实现探测（不影响其它检查）"
    add_finding warn detect_mount "脚本缺失"
else
    # 在子 shell 里 source，避免它的变量/函数污染 doctor 的命名空间
    MM_JSON="$(sh -c ". \"$DETECT_MOUNT\"; detect_mount_json" 2>/dev/null || true)"
    if [ -z "$MM_JSON" ]; then
        info "[skip] 挂载实现探测执行失败，跳过"
        add_finding warn detect_mount "执行失败"
    else
        # 极简字段提取（不依赖 jq）：先看 applicable/trusted
        case "$MM_JSON" in
            *'"trusted":false'*|*'"applicable":false'*)
                # 不可信上下文：这是**正确的 skip**，不计入 fail
                printf '  \033[33m[skip]\033[0m 当前不在 Android 侧（/data/adb 不可见或不完整），挂载实现探测不适用\n'
                info "实测差异：proot / 环境内看到的 /data/adb 只有少量条目且常缺 modules/；"
                info "          原生 root 下才有完整的 ksu / ksud / metamodule / modules 等条目"
                info "如需此项，请在 **Android 侧的 root 终端**执行：su -c 'linuxctl doctor'"
                add_finding warn detect_mount "skip: 非 Android 侧上下文"
                ;;
            *)
                ri="$(printf '%s' "$MM_JSON" | sed -n 's/.*"root_impl":"\([^"]*\)".*/\1/p')"
                rl="$(printf '%s' "$MM_JSON" | sed -n 's/.*"root_label":"\([^"]*\)".*/\1/p')"
                mp="$(printf '%s' "$MM_JSON" | sed -n 's/.*"metamodule_present":"\([^"]*\)".*/\1/p')"
                mn="$(printf '%s' "$MM_JSON" | sed -n 's/.*"metamodule_names":"\([^"]*\)".*/\1/p')"
                nd="$(printf '%s' "$MM_JSON" | sed -n 's/.*"module_needs_mount":\([a-z]*\).*/\1/p')"
                printf '\n  %-22s %s\n' "root 实现" "${rl:-未知} ($ri)"
                printf '  %-22s %s\n' "metamodule 框架" "${mp:-?}"
                printf '  %-22s %s\n' "metamodule 实现" "${mn:-（无）}"
                # 实践里"有实现、但没有 /data/adb/metamodule 框架目录"并不罕见
                # （不同 metamodule 的落点不同），不能让人误读成"没装 metamodule"。
                if [ "$mp" = "no" ] && [ -n "$mn" ]; then
                    info "（未发现 /data/adb/metamodule 框架目录，但发现了声明 metamodule=1 的模块；以实现为准）"
                fi
                if [ "$nd" = "true" ]; then nd_zh="是"; else nd_zh="否"; fi
                printf '  %-22s %s\n' "本模块是否需要挂载" "${nd_zh}"
                if [ "$nd" = "false" ]; then
                    ok "本模块是纯脚本模块，**不挂载任何系统路径，无需 metamodule 支持**（不受 KernelSU 删除自带挂载实现的影响）"
                    add_finding ok detect_mount "kernelsu=${ri:-?} metamodule=${mn:-none} needs_mount=false"
                    if [ "$ri" = "kernelsu" ] && [ "$mp" = "no" ] && [ -z "$mn" ]; then
                        info "提醒：当前 KernelSU 没有 metamodule。以后若要装**需要挂载**的社区模块，需先装一个；"
                        info "      但这**不影响本模块**。"
                    fi
                else
                    if [ -z "$mn" ] && [ "$ri" = "kernelsu" ]; then
                        bad "本模块需要挂载系统路径，但当前 KernelSU 没有任何 metamodule（挂载不会生效）"
                        add_finding fail detect_mount "needs_mount=true 但无 metamodule"
                    else
                        warn "本模块需要挂载系统路径；当前 metamodule=${mn:-无}"
                        add_finding warn detect_mount "needs_mount=true"
                    fi
                fi
                ;;
        esac
    fi
fi

head_ "2. 三层只读镜像（erofs / squashfs）"
# ★ 三态：/proc/filesystems 可读→按内容判定；不可读→ **skip**（信息不可得≠不支持）。
#   被真实场景逼出来的：某次 /proc/filesystems 变成 Permission denied，旧写法把
#   "读不到"当成"内核不支持 erofs"，让合法的 erofs 层被误判（linuxctl update 拒收）。
FSLIST=""
FS_READABLE=0
if FSLIST="$(cat /proc/filesystems 2>/dev/null)" && [ -n "$FSLIST" ]; then FS_READABLE=1; fi
fs_supported() { # fs_supported <fs> —— 依赖上面的 FSLIST / FS_READABLE
    [ "$FS_READABLE" = "1" ] || return 2      # 2 = 信息不可得
    # ★ /proc/filesystems 的行形如 "<TAB>erofs" 或 "nodev<TAB>sysfs" —— 类型名是**行内最后一个词**。
    #   早先用 "*\n$fs\n*" 匹配会因行首那个 **TAB** 而永远匹配不上，
    #   把合法的 erofs 判成"内核不支持"（实测：`grep erofs /proc/filesystems` 给出的是 `^Ierofs$`）。
    #   改成取每行末词比较：纯 bash、无子进程，也顺带避开 grep -q + pipefail 的 SIGPIPE 假阴性。
    local line last
    while IFS= read -r line; do
        last="${line##*[[:space:]]}"
        [ "$last" = "$1" ] && return 0
    done <<< "$FSLIST"
    return 1
}
STATE="$ETC_DIR/state.json"
# 先判内核到底支持哪些只读镜像格式（这是本机最关键的一条）
KFMT=""
if [ "$FS_READABLE" != "1" ]; then
    printf '  \033[33m[skip]\033[0m 读不到 /proc/filesystems（受限上下文）→ 无法判定内核支持的镜像格式\n'
    info "这不是内核不支持，而是信息不可得；请在 Android 侧 root 终端重跑本检查"
    add_finding warn kernel_fs "skip: /proc/filesystems 不可读"
else
    fs_supported squashfs
    case $? in
        0) KFMT="$KFMT squashfs"
           ok "内核支持 squashfs（/proc/filesystems）"
           add_finding ok kernel_squashfs "supported" ;;
        1) bad "内核**不支持** squashfs（/proc/filesystems 无 squashfs，且 CONFIG_SQUASHFS is not set）→ squashfs 层无法挂载"
           info "必须改用 erofs 层（内核内建 CONFIG_EROFS_FS=y）。见下面 erofs 检查"
           add_finding fail kernel_squashfs "unsupported" ;;
    esac
    fs_supported erofs
    case $? in
        0) KFMT="$KFMT erofs"
           ok "内核支持 erofs（/proc/filesystems；这是 Android 原生只读镜像格式，推荐）"
           add_finding ok kernel_erofs "supported" ;;
        1) bad "内核不支持 erofs"
           add_finding fail kernel_erofs "unsupported" ;;
    esac
fi
info "本机可用只读层格式：${KFMT:-（无）}"
if [ -z "$KFMT" ]; then
    bad "没有任何可用的只读镜像格式 → root 模式分层挂载无法工作"
fi
# 构建工具
if [ -x /system/bin/mkfs.erofs ]; then
    ok "设备自带 /system/bin/mkfs.erofs（可在设备侧生成 erofs 层）"
    add_finding ok mkfs_erofs "device"
elif have mkfs.erofs; then
    ok "PATH 里有 mkfs.erofs"
    add_finding ok mkfs_erofs "path"
else
    warn "未找到 mkfs.erofs（设备侧无法生成 erofs 层）"
    add_finding warn mkfs_erofs "missing"
fi
if have mksquashfs; then
    ok "有 mksquashfs（能生成 squashfs 层，但本机内核挂不了，仅当内核支持时才有用）"
else
    info "无 mksquashfs"
fi

for layer in base runtime dsh; do
    if ! f="$(find_layer "$layer")"; then
        bad "缺少 $LAYERS_DIR/$layer.{erofs,squashfs}（执行 device-provision.sh，或用 linuxctl update $layer <file>）"
        add_finding fail "layer_${layer}_missing" "层文件不存在"
        continue
    fi
    fmt="$(layer_format "$f" 2>/dev/null || echo unknown)"
    size="$(stat -c '%s' "$f" 2>/dev/null || echo 0)"
    case "$fmt" in
        erofs|squashfs)
            ok "$(basename "$f") 存在（格式 $fmt，$(( size / 1024 / 1024 )) MB）"
            add_finding ok "layer_${layer}_present" "$(basename "$f") $fmt ${size}B" ;;
        *)
            bad "$(basename "$f") 格式不可识别（magic=$fmt）→ 文件损坏或未下载完全"
            add_finding fail "layer_${layer}_magic" "unknown $fmt"
            continue ;;
    esac
    # 格式与内核能力匹配性
    if ! printf '%s' " $KFMT " | grep -q " $fmt "; then
        bad "$layer 是 $fmt，但内核不支持 $fmt → start 必然失败"
        add_finding fail "layer_${layer}_unsupported" "$fmt unsupported by kernel"
    fi
    # 可选 sha256 校验：state.json 里记了 sha256 才校验（避免无谓的全量读取）
    want=""
    if [ -f "$STATE" ]; then
        want="$(tr -d ' \n\t' < "$STATE" | sed -n "s/.*\"$layer\":{[^}]*\"sha256\":\"\([^\"]*\)\".*/\1/p" | head -n1)"
    fi
    if [ -n "$want" ]; then
        if have sha256sum; then
            got="$(sha256sum "$f" 2>/dev/null | awk '{print $1}')"
            if [ "$got" = "$want" ]; then
                ok "$layer sha256 校验通过"
                add_finding ok "layer_${layer}_sha256" "ok"
            else
                bad "$layer sha256 不匹配（期望 $want，实际 $got）→ 层文件损坏或被改动"
                add_finding fail "layer_${layer}_sha256" "不匹配"
            fi
        else
            warn "无 sha256sum，跳过 $layer 校验"
        fi
    else
        info "$layer 未记录 sha256，跳过校验"
    fi
    # 结构校验：让对应工具读一下镜像（squashfs→unsquashfs -s；erofs→fsck.erofs）
    case "$fmt" in
        squashfs)
            if have unsquashfs; then
                if unsquashfs -s "$f" >/dev/null 2>&1; then
                    ok "$layer squashfs 超级块可读"
                else
                    bad "$layer squashfs 超级块不可读（不是合法 squashfs）"
                    add_finding fail "layer_${layer}_superblock" "不可读"
                fi
            fi ;;
        erofs)
            if have fsck.erofs; then
                if fsck.erofs "$f" >/dev/null 2>&1; then
                    ok "$layer erofs 校验通过（fsck.erofs）"
                    add_finding ok "layer_${layer}_fsck" "ok"
                else
                    bad "$layer erofs 校验失败（fsck.erofs）"
                    add_finding fail "layer_${layer}_fsck" "fsck failed"
                fi
            fi ;;
    esac
done

# ===========================================================================
head_ "3. 可写层 upper.img"
if [ ! -f "$UPPER_IMG" ]; then
    bad "$UPPER_IMG 不存在（执行 provision）"
    add_finding fail upper_missing "$UPPER_IMG 不存在"
else
    usize="$(stat -c '%s' "$UPPER_IMG" 2>/dev/null || echo 0)"
    ok "upper.img 存在（表观 $(( usize / 1024 / 1024 / 1024 )) GiB，稀疏）"
    # 只读试挂：挂到临时目录，立刻卸载（绝不改内容）
    if [ -w /dev ] || [ -e /dev/block/loop-control ]; then
        TMPMNT="$(mktemp -d 2>/dev/null || echo /tmp/dshroid-doctor-mnt)"
        mkdir -p "$TMPMNT"
        if "$MOUNT" -t ext4 -o loop,ro "$UPPER_IMG" "$TMPMNT" 2>/dev/null; then
            ok "upper.img 可挂载（ext4, loop, ro）"
            add_finding ok upper_mountable "可挂载"
            "$UMOUNT" "$TMPMNT" 2>/dev/null || "$UMOUNT" -l "$TMPMNT" 2>/dev/null || warn "试挂点卸载失败：$TMPMNT"
        else
            bad "upper.img 挂不上（可能不是 ext4，或 loop 设备不可用，或已坏）"
            add_finding fail upper_mountable "挂载失败"
        fi
        rmdir "$TMPMNT" 2>/dev/null || true
    else
        warn "看不到 loop 设备，跳过可挂性测试"
    fi
    if have e2fsck; then
        if e2fsck -fn "$UPPER_IMG" >/dev/null 2>&1; then
            ok "e2fsck -fn 通过（文件系统一致）"
            add_finding ok upper_fsck "ok"
        else
            warn "e2fsck -fn 报问题（只读检查，未修改）；可考虑 linuxctl reset"
            add_finding warn upper_fsck "e2fsck 报问题"
        fi
    fi
fi
# 空间
if [ -d "$LH" ]; then
    av="$(df -P "$LH" 2>/dev/null | awk 'NR==2 {print $4}')"
    [ -n "$av" ] && info "宿主 $LH 可用空间：$(( av / 1024 )) MB"
fi

# ===========================================================================
head_ "4. 端口占用"
PORT=3080
if [ -f "$ETC_DIR/config.json" ]; then
    p="$(tr -d ' \n\t' < "$ETC_DIR/config.json" | sed -n 's/.*"port":\([0-9][0-9]*\).*/\1/p' | head -n1)"
    case "$p" in ''|*[!0-9]*) ;; *) PORT="$p" ;; esac
fi
if [ -f "$RUN_DIR/dsh.port" ]; then
    ap="$(head -n1 "$RUN_DIR/dsh.port" 2>/dev/null | tr -dc '0-9')"
    [ -n "$ap" ] && info "运行中的实际端口（run/dsh.port）：$ap"
fi
if have ss; then
    if ss -ltn 2>/dev/null | grep -q ":$PORT "; then
        warn "端口 $PORT 已被占用（ss）"
        add_finding warn port_busy "$PORT 被占用"
    else
        ok "端口 $PORT 空闲（ss）"
        add_finding ok port_free "$PORT 空闲"
    fi
elif have netstat; then
    if netstat -ltn 2>/dev/null | grep -q ":$PORT "; then
        warn "端口 $PORT 已被占用（netstat）"
        add_finding warn port_busy "$PORT 被占用"
    else
        ok "端口 $PORT 空闲（netstat）"
        add_finding ok port_free "$PORT 空闲"
    fi
else
    # Android/toybox 常常两者都没有 → 退化为 /proc/net/tcp 解析
    hexport="$(printf '%04X' "$PORT")"
    if [ -r /proc/net/tcp ] && awk 'NR>1 {print $2}' /proc/net/tcp 2>/dev/null | grep -qi ":$hexport$"; then
        warn "端口 $PORT 已被占用（/proc/net/tcp）"
        add_finding warn port_busy "$PORT 被占用"
    else
        ok "端口 $PORT 未在 /proc/net/tcp 中监听"
        add_finding ok port_free "$PORT 空闲"
    fi
fi

# ===========================================================================
head_ "5. SELinux / avc denial"
if [ -r /sys/fs/selinux/enforce ]; then
    enf="$(cat /sys/fs/selinux/enforce 2>/dev/null)"
    info "SELinux enforcing=$enf（本脚本**不修改** SELinux，也建议不要关）"
fi
AVC_HITS=0
if have dmesg; then
    n="$(dmesg 2>/dev/null | grep -ci 'avc: *denied' || true)"
    case "${n:-}" in ''|*[!0-9]*) n=0 ;; esac
    if [ "$n" -gt 0 ]; then
        warn "dmesg 里有 $n 条 'avc: denied'（可能与本项目无关，需看具体路径）"
        dmesg 2>/dev/null | grep -i 'avc: *denied' | tail -n 5 | sed 's/^/        /'
        AVC_HITS=$n
    else
        ok "dmesg 无 avc denial"
    fi
else
    info "无 dmesg 命令，跳过"
fi
if have logcat; then
    n2="$(logcat -d -b all 2>/dev/null | grep -ci 'avc: *denied' || true)"
    case "${n2:-}" in ''|*[!0-9]*) n2=0 ;; esac
    if [ "$n2" -gt 0 ]; then
        warn "logcat 里有 $n2 条 'avc: denied'"
        logcat -d -b all 2>/dev/null | grep -i 'avc: *denied' | tail -n 5 | sed 's/^/        /'
        AVC_HITS=$(( AVC_HITS + n2 ))
    else
        ok "logcat 无 avc denial"
    fi
else
    info "无 logcat 命令，跳过"
fi
if [ "$AVC_HITS" -gt 0 ]; then
    add_finding warn avc_denied "$AVC_HITS 条 avc denial（检查是否涉及 $LH 或 su 域）"
    info "若确认与 $LH 相关，再考虑加 sepolicy.rule（本项目默认**不带**，见 module/README 说明）"
else
    add_finding ok avc_denied "无"
fi

# ===========================================================================
head_ "6. proot 降级路线可用性"
PROOT_BIN=""
for c in "$LH/bin/proot" /usr/bin/proot /system/bin/proot /data/adb/dshroid/proot; do
    [ -x "$c" ] && { PROOT_BIN="$c"; break; }
done
if [ -n "$PROOT_BIN" ]; then
    ok "找到 proot：$PROOT_BIN"
    add_finding ok proot "found $PROOT_BIN"
else
    warn "未找到 proot（非 root 降级模式不可用；只影响 proot 模式，不影响 root 模式）"
    add_finding warn proot "not found"
fi
# 非 root 模式下 rootfs 只是普通目录
if [ -d "$LH/rootfs-proot" ]; then
    ok "存在 proot 模式的 rootfs 目录：$LH/rootfs-proot"
else
    info "无 $LH/rootfs-proot（正常，root 模式不用它）"
fi

# ===========================================================================
head_ "7. DSH profile 与插件可解析性（docs/dsh-profile.md §4.2）"
# 注意：宿主机上没有 chroot 进去的 rootfs 内容，所以这里**优先在环境内探**，
# 环境没起来就直接检查层文件里是否有 profile 路径。
PROBE_OK=""
if [ -f "$RUN_DIR/dsh.pid" ] && [ -d "$ROOTFS_DIR/usr/local/lib/node_modules/@deepseek-ai/dsh" ]; then
    # 环境可能正在运行，且 rootfs 已挂载 → 直接读挂载后的文件
    P="$ROOTFS_DIR/root/.dsh/profiles/web"
    if [ -f "$P/package.json" ]; then
        ok "rootfs 内有 profile：/root/.dsh/profiles/web/package.json"
        nm="$P/node_modules"
        miss=""
        for pkg in dsh-web-mobile dsh-task-notifier "@deepseek-ai/dsh-base" "@deepseek-ai/dsh-web-app"; do
            [ -e "$nm/$pkg" ] || miss="$miss $pkg"
        done
        if [ -z "$miss" ]; then
            ok "profile 依赖链接齐全（$nm）"
            add_finding ok profile "ok"
        else
            bad "profile 依赖缺失：$miss（重装 dsh 层，含 /root/.dsh/profiles/**）"
            add_finding fail profile "missing:$miss"
        fi
        PROBE_OK=1
    fi
fi
if [ -z "$PROBE_OK" ]; then
    # 环境没起来：检查层文件里有没有那个路径
    dl="$(find_layer dsh 2>/dev/null || true)"
    lst=""
    [ -n "$dl" ] && lst="$(layer_list "$dl")"
    if [ -n "$lst" ]; then
        case "$lst" in
            *"/root/.dsh/profiles/web/package.json"*)
                ok "dsh 层内含 /root/.dsh/profiles/web/package.json"
                add_finding ok profile "in-layer" ;;
            *)
                bad "dsh 层内**没有** /root/.dsh/profiles/** → dsh web 界面会退化成桌面版"
                info "修复：用包含 profile 的层替换（docs/dsh-profile.md §3.1 / §5）；或重跑 device-provision.sh"
                add_finding fail profile "layer-missing-profile" ;;
        esac
    else
        info "环境未运行且无 dump.erofs/unsquashfs，跳过 profile 检查（启动后可用 linuxctl exec -- ls /root/.dsh/profiles/web 复核）"
        add_finding warn profile "unchecked"
    fi
fi

# pnpm（docs/dsh-profile.md §6）：dsh plugin 靠它装第三方插件
PNPM_OK=""
for c in "$ROOTFS_DIR/opt/node/bin/pnpm" /opt/node/bin/pnpm /usr/local/bin/pnpm; do
    [ -x "$c" ] && { PNPM_OK="$c"; break; }
done
if [ -n "$PNPM_OK" ]; then
    ok "pnpm 存在：$PNPM_OK（dsh plugin 可安装第三方插件）"
    add_finding ok pnpm "found"
else
    rl="$(find_layer runtime 2>/dev/null || true)"
    rlst=""
    [ -n "$rl" ] && rlst="$(layer_list "$rl")"
    if [ -n "$rlst" ]; then
        case "$rlst" in
            *"opt/node/bin/pnpm"*)
                ok "runtime 层内含 /opt/node/bin/pnpm（dsh plugin 可用）"
                add_finding ok pnpm "in-layer" ;;
            *)
                warn "runtime 层里没有 pnpm → 用户无法用 dsh plugin add 装第三方插件"
                add_finding warn pnpm "missing" ;;
        esac
    else
        warn "未找到 pnpm（dsh plugin 会报 'pnpm not found on PATH'）"
        add_finding warn pnpm "missing"
    fi
fi

# ===========================================================================
head_ "8. 运行态"
if [ -f "$RUN_DIR/ready" ] && [ -f "$RUN_DIR/supervisor.pid" ]; then
    spid="$(head -n1 "$RUN_DIR/supervisor.pid" 2>/dev/null | tr -dc '0-9')"
    if [ -n "$spid" ] && [ -d "/proc/$spid" ]; then
        ok "环境运行中（ns_pid=$spid）"
        add_finding ok runtime "running ns_pid=$spid"
    else
        warn "ready 文件存在但守护进程 $spid 已不存在（残留状态，建议 linuxctl stop 后重新 start）"
        add_finding warn runtime "stale ready"
    fi
else
    info "环境未运行"
    add_finding ok runtime "stopped"
fi
if [ -f "$RUN_DIR/dsh.url" ]; then
    ok "run/dsh.url 存在（带令牌登录 URL，权限 $(stat -c '%a' "$RUN_DIR/dsh.url" 2>/dev/null)）"
else
    info "run/dsh.url 不存在（环境未运行或尚未打印 URL）"
fi
if [ -s "$RUN_DIR/last-error" ]; then
    bad "run/last-error：$(cat "$RUN_DIR/last-error")"
    add_finding fail last_error "$(cat "$RUN_DIR/last-error")"
fi

# ===========================================================================
head_ "结论"
if [ "$FAILS" -gt 0 ]; then
    printf '  \033[31m%d 项 fail\033[0m，%d 项 warn\n' "$FAILS" "$WARNS"
else
    printf '  \033[32m全部通过\033[0m（%d 项 warn）\n' "$WARNS"
fi

# 结尾一行 JSON（App / 自动化解析用）
{
    printf '{"schema":1,"ok":%s,"fails":%d,"warns":%d,"linux_home":"%s","findings":[' \
        "$([ "$FAILS" -eq 0 ] && echo true || echo false)" "$FAILS" "$WARNS" "$(jesc "$LH")"
    first=1
    for f in "${FINDINGS[@]}"; do
        [ "$first" = 1 ] || printf ','
        printf '%s' "$f"
        first=0
    done
    printf ']}\n'
}

[ "$FAILS" -eq 0 ] && exit 0 || exit 1
