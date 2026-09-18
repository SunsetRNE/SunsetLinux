#!/system/bin/sh
# =============================================================================
# sunsetlinux · runtime/root/doctor.sh
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
SELF_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"

LINUX_HOME="${LINUX_HOME:-/data/sunsetlinux}"
LH="$LINUX_HOME"
LAYERS_DIR="$LH/layers"
ETC_DIR="$LH/etc"
RUN_DIR="$LH/run"
UPPER_IMG="$LH/upper.img"
ROOTFS_DIR="$LH/rootfs"

# ★ 测试接缝（**只在测试里设**，设备侧永远不设）：CI 容器里没有 /system/bin/mount，
#   而 §3 的探针要能用桩复现"只读挂不上、读写正常"（真机第四起假警报）。
#   与 SUNSETLINUX_KCONFIG_FILE 同一套做法：只开一个明确的接缝，不改默认行为。
UMOUNT="${SUNSETLINUX_UMOUNT:-/system/bin/umount}"
MOUNT="${SUNSETLINUX_MOUNT:-/system/bin/mount}"

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
# 层内**某个路径是否存在**：0=在 1=不在 2=判不了（工具不可用）
#
# ⚠️ 为什么不能拿 `dump.erofs --ls --path=/` 的输出去 grep：
#   它**不递归**，根目录只列出 `root` / `usr` 两个名字。
#   早先的 profile / pnpm 检查正是这么写的，于是**永远匹配不上**，真机上无论层里有没有
#   都报 [fail]「dsh 层内没有 /root/.dsh/profiles/**」+ [warn]「runtime 层里没有 pnpm」——
#   两个假阴性，把用户引向"重装层/重跑 provision"（其实层是好的）。
#   正确做法是**直接问那条路径**：erofs 用 `dump.erofs --path=<p>`（不存在的路径会打印
#   "read inode failed"），squashfs 用 `unsquashfs -l <img> <dir>`。
# layer_has_path 现在住在 runtime/common/layer-inspect.sh（doctor 与 linuxctl 共用一份）。
# 为什么搬走：`linuxctl dsh builtin` 也要用同一个判断（"生效层里有没有 web profile"）来决定
# 要不要把层切到内置那份 —— 两处各写一遍必然漂移（真机 2026-09-17：内置层已就位却没被启用）。
COMMON_DIR=""
for _c in "$SELF_DIR/common" "$SELF_DIR/../common"; do
    [ -f "$_c/layer-inspect.sh" ] && { COMMON_DIR="$_c"; break; }
done
if [ -n "$COMMON_DIR" ]; then
    # shellcheck source=/dev/null
    SUNSETLINUX_SOURCED=1 . "$COMMON_DIR/layer-inspect.sh"
else
    # 兜底：找不到公共库时**明说**并让检查退化成"判不了"（return 2），绝不假装"层里没有"
    layer_has_path() { return 2; }
fi

# 环境是否真的在跑（ready + supervisor 进程都活着）。§1d/§3/§8 都要用，
# 所以放在最前面统一定义一次 —— 三处各写一遍迟早漂移（"运行中"与"没在跑"会互相矛盾）。
env_running() {
    [ -f "$RUN_DIR/ready" ] || return 1
    local p=""
    p="$(head -n1 "$RUN_DIR/supervisor.pid" 2>/dev/null | tr -dc '0-9' || true)"
    [ -n "$p" ] && [ -d "/proc/$p" ]
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

# 收集给 JSON 的结论（**纯 POSIX 字符串累加**，不依赖数组）
#
# ⚠️ 为什么不用数组：本脚本在**设备侧**由 /system/bin/sh（Android mksh）执行，不是 bash。
#    `declare -a` 在 mksh 里是**语法错误** → 整个脚本一行都跑不了（真机上实测过）。
#    逗号就地拼好（`${FINDINGS:+,}`），省掉输出端的 first 标志。
FINDINGS=""
add_finding() { FINDINGS="${FINDINGS}${FINDINGS:+,}{\"level\":\"$1\",\"id\":\"$(jesc "$2")\",\"detail\":\"$(jesc "$3")\"}"; }

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
# 内核配置来源，优先级：
#   ① SUNSETLINUX_KCONFIG_FILE（测试/排障用：指一份配置文本，让本节的判定在任何机器上
#      都可复现 —— 真机上 /proc/config.gz 可读，CI 容器里往往不可读，没有这个接缝就
#      "squashfs 不得报 fail"这类闸门在 CI 上永远跑不到）；
#   ② /proc/config.gz（真机常规来源，需要 CONFIG_IKCONFIG_PROC）；
#   ③ /boot/config-<kver>（少数内核把配置放这儿）。
KC=""
SRC=""
if [ -n "${SUNSETLINUX_KCONFIG_FILE:-}" ] && [ -r "${SUNSETLINUX_KCONFIG_FILE}" ]; then
    KC="$(cat "$SUNSETLINUX_KCONFIG_FILE" 2>/dev/null || true)"
    [ -n "$KC" ] && SRC="$SUNSETLINUX_KCONFIG_FILE"
fi
if [ -z "$KC" ] && [ -r /proc/config.gz ]; then
    if have zcat; then KC="$(zcat /proc/config.gz 2>/dev/null)"; fi
    [ -z "$KC" ] && KC="$(gzip -dc /proc/config.gz 2>/dev/null || true)"
    [ -n "$KC" ] && SRC="/proc/config.gz"
fi
if [ -z "$KC" ] && [ -r /boot/config-"$(uname -r)" ]; then
    KC="$(cat /boot/config-"$(uname -r)" 2>/dev/null || true)"
    [ -n "$KC" ] && SRC="/boot/config-$(uname -r)"
fi

if [ -n "$KC" ]; then
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
    # 必需项：缺任何一个都真的会影响 root 模式（overlay/erofs/loop 挂载）
    for opt in NAMESPACES UTS_NS NET_NS OVERLAY_FS EXT4_FS TMPFS; do
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
    # squashfs 单独判：本项目的三层只读镜像**支持 erofs 与 squashfs 两种**，
    # 内核没有 squashfs 只说明"不能用 squashfs 层"，而 erofs 是 Android 原生格式、
    # 默认就有（§2 会实测）。所以这里**不是 fail** —— 真机上打成 fail 会让自检
    # 在完全能跑的环境里报红（用户看到的 4 项 fail 里有两项就是这么来的）。
    v="$(kcval SQUASHFS)"
    case "$v" in
        y)
            ok "CONFIG_SQUASHFS=y （$SRC）"
            add_finding ok config_SQUASHFS "=y" ;;
        m)
            warn "CONFIG_SQUASHFS=m（模块，需先加载；$SRC）"
            add_finding warn config_SQUASHFS "=m 需加载模块" ;;
        *)
            info "CONFIG_SQUASHFS 未启用 —— **不是问题**：本机只用 erofs 层（§2 会实测内核是否支持 erofs）"
            add_finding ok config_SQUASHFS "未启用（改用 erofs，见 §2）" ;;
    esac
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
    info "也可以手动探测：LINUX_HOME=$LH /system/bin/sh $LH/bin/start.sh --probe-only 2>&1 | tail"
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
# SunsetLinux 是**纯脚本模块**（module/ 下没有 system/、vendor/ 等目录），**不需要挂载**，
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
    # ★ 必须把模块根**显式**传进去：被 `sh -c` 起来时 `$0` 是 "sh"，
    #   detect-mount.sh 里任何"用 $0 推模块根"的写法都会退化成当前工作目录，
    #   而用户在 `/` 下跑时 `/system` 必然存在 → 误报"需要挂载系统路径"（真机踩过）。
    DETECT_ROOT=""
    for _c in /data/adb/modules/sunsetlinux /data/adb/modules_update/sunsetlinux; do
        [ -f "$_c/module.prop" ] && { DETECT_ROOT="$_c"; break; }
    done
    [ -n "$DETECT_ROOT" ] || DETECT_ROOT="$(cd -- "$SELF_DIR/.." 2>/dev/null && pwd || printf '')"
    # 把 detect 脚本当 $1 传，$0 用占位符 —— 顺便让 $0 不再是无意义的 "sh"
    MM_JSON="$(MODULE_ROOT="$DETECT_ROOT" sh -c '. "$1"; detect_mount_json' _ "$DETECT_MOUNT" 2>/dev/null || true)"
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
                    ok "本模块是纯脚本模块：**不向系统分区放任何文件**，无需 metamodule 支持（不受 KernelSU 删除自带挂载实现的影响）"
                    info "  这句只说系统分区：环境自己的 overlay/erofs/loop 挂载在私有 mount ns 里，见 §1d"
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

# ===========================================================================
head_ "1d. 挂载冲突检查（我们的挂载 vs KernelSU/metamodule 的自动挂载）"
# 背景：KernelSU 的模块挂载由 **metamodule**（如 magic_mount_rs）在启动时完成，
#   它的职责是把**常规模块的 system/ 等目录** overlay/重定向到 Android 系统路径，
#   且要求挂载 source 标成 KSU。我们的 chroot 环境自己挂 loop/erofs/overlay，
#   所以必须能证明"两者不打架"。这一节就是把证据摆出来：
#     ① 我们的挂载在 unshare -m 出来的**私有命名空间**里（PID 1 的 mountinfo 里看不到）
#     ② 我们的挂载目标全部在 $LINUX_HOME 下 → 与 /system /vendor /product … **不相交**
#     ③ 我们只用**空闲** loop 设备（losetup -f），不抢别人的
#     ④ 本模块**没有** system/ 等目录 → metamodule 对本模块无事可做
#     ⑤ 环境运行期间我们的挂载点仍然存在（没被 KSU 的 umount 特性或别人清掉）
# ===========================================================================
MOUNT_CONFLICT_BAD=0

# ④ 先看本模块有没有"会被 metamodule 挂载"的东西（最根本的一条）
if [ -n "${MODDIR:-}" ] && [ -d "${MODDIR:-/nonexistent}" ]; then
    _mm_dirs=""
    for _d in system system_ext vendor product odm my_product my_heytap oplus .replace; do
        [ -d "$MODDIR/$_d" ] && _mm_dirs="$_mm_dirs $_d"
    done
    if [ -z "$_mm_dirs" ]; then
        ok "本模块目录下没有 system/ vendor/ 等（只有脚本）：metamodule **无事可做**，不存在挂载冲突"
    else
        warn "本模块目录下有会被 metamodule 挂载的内容：$_mm_dirs —— 那才会与 metamodule 交互"
        MOUNT_CONFLICT_BAD=1
    fi
fi

# ① 命名空间隔离：我们的 supervisor 与 PID 1 的 mount namespace 必须不同
SUP_PID=""
if [ -f "$RUN_DIR/supervisor.pid" ]; then
    SUP_PID="$(tr -dc '0-9' < "$RUN_DIR/supervisor.pid" 2>/dev/null | head -c 12)"
fi
if [ -n "$SUP_PID" ] && [ -d "/proc/$SUP_PID" ]; then
    _ours="$(readlink "/proc/$SUP_PID/ns/mnt" 2>/dev/null || printf '?')"
    _init="$(readlink /proc/1/ns/mnt 2>/dev/null || printf '?')"
    if [ "$_ours" = "?" ] || [ "$_init" = "?" ]; then
        info "读不到 mount namespace（受限上下文）→ 跳过隔离性判定"
    elif [ "$_ours" != "$_init" ]; then
        ok "我们的挂载在**私有** mount namespace 里（supervisor=$_ours ≠ PID1=$_init）"
    else
        warn "supervisor 与 PID 1 同一个 mount namespace —— 挂载会**泄漏到全局**（应检查 unshare 是否失败）"
        MOUNT_CONFLICT_BAD=1
    fi
else
    info "环境未运行 → 命名空间隔离性无法判定（start 之后再看）"
fi

# ② PID 1 的挂载表里不许出现我们的路径（泄漏检查）+ 我们的挂载点清单
if [ -r /proc/1/mountinfo ]; then
    _leak="$(grep -c "[[:space:]]$LINUX_HOME" /proc/1/mountinfo 2>/dev/null || true)"
    case "${_leak:-}" in ''|*[!0-9]*) _leak=0 ;; esac
    if [ "$_leak" = "0" ]; then
        ok "全局挂载表（PID 1）里没有任何 $LINUX_HOME/… 条目 → 与 metamodule 的目标路径**不相交**"
    else
        warn "全局挂载表里有 $_leak 条 $LINUX_HOME/… —— 有挂载泄漏到全局命名空间"
        grep "[[:space:]]$LINUX_HOME" /proc/1/mountinfo 2>/dev/null | head -n 3 | sed 's/^/        /'
        MOUNT_CONFLICT_BAD=1
    fi
fi
if [ -n "$SUP_PID" ] && [ -r "/proc/$SUP_PID/mountinfo" ]; then
    _mine="$(grep -o "[^ ]*$LINUX_HOME[^ ]*" "/proc/$SUP_PID/mountinfo" 2>/dev/null | sort -u | head -n 6)"
    if [ -n "$_mine" ]; then
        info "我们命名空间内的挂载点（全部在 $LINUX_HOME 下）："
        printf '%s\n' "$_mine" | sed 's/^/          /'
    fi
fi

# ③ loop 设备占用：只报"谁在用"，并确认我们的都指向 $LINUX_HOME
#
# ⚠️ 旧实现的两个计数都是错的（真机输出自相矛盾：说"在用 1 个，且都指向
#   /data/sunsetlinux"，下面却列出 4 个 loop，其中 swap/APEX 都不属于我们）：
#     · 总数用 `grep -c ":'"` —— losetup -a 的行形如 `/dev/block/loop49: [65103]:2924912 (file)`，
#       `:'` 根本匹配不到，于是总数恒为 0；
#     · "我们的"用 `grep -c "$LINUX_HOME"` 但工具是 `grep -c ":'"` 那套，且没按行前缀判设备。
#   现在：**以 `/dev/` 开头的行**才算 loop 设备，backing 路径含 $LINUX_HOME 的才算我们的。
if have losetup; then
    _lo="$(losetup -a 2>/dev/null || true)"
    if [ -z "$_lo" ]; then
        info "当前没有 loop 设备在用（我们的 erofs/upper 只在环境运行时占用）"
    else
        _all_n="$(printf '%s\n' "$_lo" | awk '/^\/dev\//{n++} END{printf "%d", n+0}')"
        _ours_n="$(printf '%s\n' "$_lo" | awk -v h="$LINUX_HOME" '/^\/dev\// && index($0,h)>0 {n++} END{printf "%d", n+0}')"
        case "${_all_n:-}" in ''|*[!0-9]*) _all_n=0 ;; esac
        case "${_ours_n:-}" in ''|*[!0-9]*) _ours_n=0 ;; esac
        _other_n=$(( _all_n - _ours_n ))
        if [ "$_ours_n" -gt 0 ] && [ "$_other_n" -eq 0 ]; then
            ok "loop 设备：在用 $_ours_n 个，且都指向 $LINUX_HOME（未与其它模块争用）"
        elif [ "$_ours_n" -gt 0 ]; then
            # 别的 loop 不是"抢用"：我们用 losetup -f 取空闲设备，与 swap/APEX/别的模块并存是常态。
            # 旧版把这条打成 [warn]，等于给正常现象报警。
            info "loop 设备：我们在用 $_ours_n 个（指向 $LINUX_HOME）；另有 $_other_n 个是别的（swap/APEX/其它模块），我们用 losetup -f 取空闲，不争用"
        else
            info "loop 设备：在用 $_all_n 个，都不是本项目的（不争用）"
        fi
        printf '%s\n' "$_lo" | head -n 4 | sed 's/^/        /'
        # ★ 残留信号：环境没在跑，却有 loop 指着我们的镜像 → 上次 start 失败留下的。
        #   这条值得单独说，因为它会直接影响下一次挂载（真机就在这条上卡过）。
        if ! env_running; then
            case "$_lo" in
                *"$UPPER_IMG"*)
                    warn "有 loop 设备仍指向 $UPPER_IMG，但环境并没有在跑 —— 上次启动失败的残留"
                    info "下一次 start 会先自动清理它；要手动清：linuxctl stop（幂等），必要时 losetup -d /dev/block/loopN" ;;
            esac
        fi
    fi
fi

# ⑤ 运行中挂载点是否还在（KSU 的 umount 特性 / 别的机制可能清掉它们）
if [ -n "$SUP_PID" ] && [ -d "/proc/$SUP_PID" ]; then
    if [ -d "$ROOTFS_DIR/etc" ] && [ -r "$ROOTFS_DIR/etc/os-release" ]; then
        ok "运行中：overlay 根仍挂在 $ROOTFS_DIR（没有被其它机制清掉）"
    else
        warn "环境在运行，但 $ROOTFS_DIR 里读不到 etc/os-release —— 挂载可能已被清掉"
        MOUNT_CONFLICT_BAD=1
    fi
fi
if [ "$MOUNT_CONFLICT_BAD" = "0" ]; then
    add_finding ok mount_conflict "无冲突：私有命名空间 + 目标路径不相交 + 模块不含 system/"
else
    add_finding warn mount_conflict "有可疑项，见上面 [warn]"
fi

head_ "1e. 层模式（loop / dir）"
# loop = losetup + erofs 挂载 + upper.img(ext4) + overlay；dir = 层解包成目录 + 目录 overlay。
# dir 是"不想碰 loop/erofs"时的兼容开关（见 docs/layer-mode.md）。
LM=""
if [ -f "$RUN_DIR/layer-mode" ]; then
    LM="$(tr -d ' \n\r' < "$RUN_DIR/layer-mode" 2>/dev/null | head -c 16 || true)"
fi
if [ -n "$LM" ]; then
    ok "当前（上次 start）层模式：$LM"
else
    info "run/layer-mode 不存在（环境还没启动过）→ 默认 loop"
    LM=loop
fi
case "$LM" in
    dir)
        _ex="${SUNSETLINUX_EROFS_EXTRACT:-/system/bin/fsck.erofs}"
        if [ -x "$_ex" ]; then ok "erofs 解包器可用：$_ex"
        else warn "erofs 解包器不可执行：$_ex（dir 模式会直接失败；可设 SUNSETLINUX_EROFS_EXTRACT）"; fi
        _n=0; _bad=""
        for _l in base runtime dsh; do
            _d="$LH/dirs/$_l"; _st="$LH/dirs/.$_l.stamp"
            if [ -d "$_d" ] && [ -f "$_st" ]; then
                _n=$((_n + 1))
                _lf="$(find_layer "$_l" 2>/dev/null || true)"
                if [ -n "$_lf" ]; then
                    _want="$(basename "$_lf"):$(wc -c < "$_lf" 2>/dev/null | tr -d ' ')"
                    [ "$_want" = "$(cat "$_st" 2>/dev/null)" ] || _bad="$_bad $_l(需重解)"
                fi
            else
                _bad="$_bad $_l(未解包)"
            fi
        done
        if [ -z "$_bad" ]; then
            ok "三层都已解包且与当前层文件一致（$_n/3）"
        else
            warn "下列层需要解包/重解（下次 start 会自动做）：$_bad"
        fi
        _du="$(du -sk "$LH/dirs" 2>/dev/null | awk '{print $1}')"
        [ -n "$_du" ] && info "解包后占用：$(( _du / 1024 )) MB（loop 模式只需层镜像本身）"
        ;;
    loop)
        if [ -f "$LH/upper.img" ]; then
            ok "loop 模式必需件齐全（layers/*.erofs + upper.img）"
        else
            warn "loop 模式缺 upper.img → 启动会失败；可改用 dir 模式（SUNSETLINUX_LAYER_MODE=dir）"
        fi
        ;;
esac

head_ "1f. 视图与挂载隔离（我在哪个命名空间 / 有没有回流）"
# 为什么有这一节（2026-09-19 真机）：同一台机器上同时存在**三个挂载命名空间**，
# 同一个路径在不同视角下指向不同目录（详见 module/share/地图-视角.md）。
# 两件必须能自检的事：
#   ① 我这份 /data 是不是**影子**（proot 容器没 bind /data 时会解析到自己的 rootfs）；
#   ② 环境的挂载有没有**回流到全局命名空间**（`/` 是 shared、toybox unshare 不认
#      --propagation 时会发生 —— 两个环境同时起就会互踩挂载）。
{
    _my_ns="$(readlink /proc/self/ns/mnt 2>/dev/null || printf '?')"
    _p1_ns="$(readlink /proc/1/ns/mnt 2>/dev/null || printf '?')"
    info "我的 mount ns：$_my_ns（pid 1：$_p1_ns）$([ "$_my_ns" = "$_p1_ns" ] && printf '（同在全局 ns）' || printf '（**不是**全局 ns —— 别人的挂载你看不见，反之亦然）')"

    # ① 影子视图
    if [ -d "$LH" ] && [ ! -f "$UPPER_IMG" ] && [ ! -d "$LH/dirs-upper" ] && [ ! -x "$LH/bin/linuxctl.sh" ]; then
        warn "$LH 存在但里面没有 upper.img / dirs-upper / bin —— 这多半是**影子视图**（容器把 /data 解析到了自己的 rootfs）"
        add_finding warn "view-shadow" "$LH 看起来不是真的那份（缺 upper.img/dirs-upper/bin）：绝对路径会读错，请用 linuxctl whereami 确认视角"
    else
        ok "$LH 看起来是真的那一份（upper.img/dirs-upper/bin 至少一个在）"
    fi

    # ② 回流：环境的挂载出现在全局 ns（pid 1）的 mountinfo 里 = 泄漏
    if env_running; then
        _leak=0
        if [ -r /proc/1/mountinfo ]; then
            _leak="$(grep -c 'sunsetlinux/rootfs' /proc/1/mountinfo 2>/dev/null || printf '0')"
        else
            _leak="?"
        fi
        case "$_leak" in
            0) ok "环境在跑，但它的挂载**没有**出现在全局 ns（隔离有效）" ;;
            "?") info "读不到 /proc/1/mountinfo，跳过回流检查（换 root shell 再跑一次可判定）" ;;
            *) warn "环境的挂载出现在**全局命名空间**（/proc/1/mountinfo 命中 ${_leak} 处）—— 私有 ns 没隔住，两个环境同起会互踩挂载"
               add_finding warn "mount-leak" "环境挂载回流到全局 ns（命中 ${_leak} 处）：/ 是 shared 且 toybox unshare 不认 --propagation 时会这样；先别同时跑两个环境" ;;
        esac
    else
        info "环境未运行，跳过回流检查"
    fi

    # ③ 交换目录
    if [ -d "$LH/share" ]; then
        if env_running && [ -d "$ROOTFS_DIR/share" ] && mountpoint -q "$ROOTFS_DIR/share" 2>/dev/null; then
            ok "交换目录已挂载：$LH/share <-> 环境 /share"
        elif env_running; then
            warn "交换目录没挂上（$LH/share 在，但 $ROOTFS_DIR/share 不是挂载点）—— 环境里看不到 /share"
            add_finding warn "share-not-mounted" "环境内的 /share 没挂上：看 run/linux.log 里那行『交换目录』；不影响环境本身可用"
        else
            info "交换目录 $LH/share 已创建（环境未运行，看不到 /share）"
        fi
    else
        info "还没有交换目录 $LH/share（下次启动环境会自动建）"
    fi
}

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
        1) # 不 fail：squashfs 只是"两种可选只读格式"里的一种；下面会实测 erofs。
           #    真机上这里打成 fail 是**假警报**（环境跑得好好的也报红）。
           info "内核不支持 squashfs（/proc/filesystems 无 squashfs）→ 层必须用 erofs（下面是实测结果）"
           add_finding ok kernel_squashfs "unsupported（层改用 erofs）" ;;
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

# ---------------------------------------------------------------------------
# 可写层探针：**与 start.sh 的 mount_upper_rw 同口径**，而且只留**一个**结论。
#
# 为什么改（真机 2026-09-17，用户手机的 doctor 实测）：
#   原来的写法是"先只读试挂、再读写试挂"，两个结果各自成为一条 finding。真机上出现
#   `-o loop,ro` 失败、`-o loop,rw` 成功 → 报告同时打：
#       [fail] upper.img 挂不上（可能不是 ext4，或 loop 设备不可用，或已坏）
#       [ok]   upper.img 可**读写**挂载（ext4, loop, rw）—— 与 start.sh 的第一步一致
#   自相矛盾，用户完全无从下手（而且 fail 会把整份自检判成"未通过"）。
#   真正决定"环境能不能起来"的只有**读写**这一条（start.sh 做的也是它），所以：
#     · 读写探针 = 唯一结论；失败时再补一次显式 losetup（本机 toybox 的 -o loop 不可靠）；
#     · 只读探针降级为**诊断**：只在读写也失败时才跑，报 info，不产生 finding。
#   doctor 仍然**只读**：不跑 e2fsck -p、不清残留 loop、不动 state.json；探针自己建过
#   的 loop 设备自己拔掉（不留垃圾给下一次 start）。
# ---------------------------------------------------------------------------
_UP_PROBE_DEV=""   # 探针自己建的 loop（用完必须拔）
_UP_RW_HOW=""      # 成功时用的方式（写进日志，排障用）
_UP_RW_ERR=""      # 失败时的内核线索

_doctor_loop_visible() {
    [ -w /dev ] && return 0
    [ -e /dev/block/loop-control ] && return 0
    # 测试接缝：CI 容器里没有 loop 设备，用它强制走一遍探针（与 SUNSETLINUX_KCONFIG_FILE 同理）
    [ "${SUNSETLINUX_LOOP_DEV_OK:-}" = "1" ] && return 0
    return 1
}

_doctor_kernel_hint() {
    dmesg 2>/dev/null | grep -iE 'loop|ext4|jbd2' | tail -n 2 | tr '\n' ' '
}

# 读写探针：① toybox -o loop,rw,noatime → ② 显式 losetup + mount
# 成功 0 / 失败 1；成功时若用了 losetup 会把设备记在 _UP_PROBE_DEV（调用方负责卸载+拔掉）
_doctor_probe_upper_rw() {
    local t="$1" dev=""
    _UP_RW_HOW=""; _UP_RW_ERR=""
    mkdir -p "$t" 2>/dev/null || { _UP_RW_ERR="无法创建试挂点 $t"; return 1; }

    if "$MOUNT" -t ext4 -o loop,rw,noatime "$UPPER_IMG" "$t" 2>/dev/null; then
        _UP_RW_HOW="mount -o loop,rw,noatime"
        return 0
    fi
    _UP_RW_ERR="$(_doctor_kernel_hint)"

    # 显式 losetup：本机实测 toybox 的 `-o loop` 偶尔不可靠，而两步走能归因到具体哪一步
    dev="$(losetup -f --show "$UPPER_IMG" 2>/dev/null || true)"
    if [ -n "$dev" ]; then
        if "$MOUNT" -t ext4 -o rw,noatime "$dev" "$t" 2>/dev/null; then
            _UP_PROBE_DEV="$dev"
            _UP_RW_HOW="显式 losetup $dev"
            return 0
        fi
        losetup -d "$dev" 2>/dev/null || true
    fi
    _UP_RW_ERR="$(_doctor_kernel_hint)"
    return 1
}

if [ ! -f "$UPPER_IMG" ]; then
    bad "$UPPER_IMG 不存在（执行 provision）"
    add_finding fail upper_missing "$UPPER_IMG 不存在"
else
    usize="$(stat -c '%s' "$UPPER_IMG" 2>/dev/null || echo 0)"
    # ★ 别用 mksh 的算术算 GiB：它是 **32 位**，`8589934592 / …` 一步就越界 ——
    #   8 GiB 的 upper.img 会显示成"表观 0 GiB"（真机 2026-09-17 实测就是这样）。awk 用双精度。
    ok "upper.img 存在（表观 $(awk -v b="$usize" 'BEGIN{printf "%.1f", b/1073741824}') GiB，稀疏）"

    if env_running; then
        info "环境在运行：跳过可写层试挂（避免干扰运行中的 overlay；要试先 stop）"
    elif ! _doctor_loop_visible; then
        warn "看不到 loop 设备，跳过可挂性测试"
    else
        TMPMNT="$(mktemp -d 2>/dev/null || echo /tmp/sunsetlinux-doctor-mnt)"
        mkdir -p "$TMPMNT"
        if _doctor_probe_upper_rw "$TMPMNT"; then
            ok "upper.img 可**读写**挂载（ext4, rw；方式：$_UP_RW_HOW）—— 与 start.sh 的第一步一致"
            add_finding ok upper_mountable_rw "$_UP_RW_HOW"
            "$UMOUNT" "$TMPMNT" 2>/dev/null || "$UMOUNT" -l "$TMPMNT" 2>/dev/null || true
            if [ -n "$_UP_PROBE_DEV" ]; then
                losetup -d "$_UP_PROBE_DEV" 2>/dev/null || true   # 拔掉我们自己建的 loop
            fi
        else
            # 读写挂不上 → 这才是真 fail；再补一次**只读**试挂当诊断（只读能挂说明问题在"写"）
            bad "upper.img **读写挂不上** → linuxctl start 会卡在「挂载 upper」"
            add_finding fail upper_mountable_rw "${_UP_RW_ERR:-挂载失败}"
            if "$MOUNT" -t ext4 -o loop,ro "$UPPER_IMG" "$TMPMNT" 2>/dev/null; then
                info "只读能挂、读写挂不上 → 问题在「写」这一侧（SELinux 域权限 / 残留 loop / ext4 日志）"
                "$UMOUNT" "$TMPMNT" 2>/dev/null || "$UMOUNT" -l "$TMPMNT" 2>/dev/null || true
            else
                info "只读也挂不上 → 更像镜像本身或 loop 设备的问题（见 §1d 的残留清单）"
            fi
            info "内核侧原因只以 'I/O error' 的形式返回，看原文：dmesg | grep -iE 'loop|ext4|jbd2' | tail -20"
            info "start.sh 还会多做两步（doctor 只读、不替你做）：① 清掉指向本镜像的残留 loop ② e2fsck -p"
            info "对应命令：linuxctl stop（清残留）→ linuxctl start；实在不行：设置 → 层模式 → dir（全程不碰 loop/upper.img）"
        fi
        rmdir "$TMPMNT" 2>/dev/null || true
    fi

    if have e2fsck; then
        _fsck_out="$(e2fsck -fn "$UPPER_IMG" 2>&1)"
        _fsck_rc=$?
        if [ "$_fsck_rc" = "0" ]; then
            ok "e2fsck -fn 通过（文件系统一致）"
            add_finding ok upper_fsck "ok"
        else
            # 把 e2fsck 的第一条实质结论带出来（只读检查，未修改）——
            # "报问题"三个字对定位没用，"recovering journal / needs recovery"才是线索。
            _fsck_line="$(printf '%s\n' "$_fsck_out" | grep -v '^e2fsck ' | head -n1)"
            warn "e2fsck -fn 报问题（rc=$_fsck_rc，只读检查未修改）：${_fsck_line:-见原始输出}"
            add_finding warn upper_fsck "e2fsck rc=$_fsck_rc：${_fsck_line:-报问题}"
            info "修法：linuxctl start 会在挂载前做一次 e2fsck -p（自动），或手动 e2fsck -fp $UPPER_IMG"
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
# ★ 相关性要和计数分开记：厂商 HAL 扫 ksu 进程的 avc 噪声动辄几百条，
#   它们是 **info**（人读的那行早就这么显示了），但旧代码给 JSON 的 finding
#   一律打 warn —— 于是 `{"level":"warn","id":"avc_denied"}` 与上面那行
#   "都与本项目无关" 互相打脸（真机截图里就是这个）。现在两者同一判据。
AVC_RELEVANT=0
if have dmesg; then
    n="$(dmesg 2>/dev/null | grep -ci 'avc: *denied' || true)"
    case "${n:-}" in ''|*[!0-9]*) n=0 ;; esac
    if [ "$n" -gt 0 ]; then
        # 只有**确实涉及我们**的 denial 才值得用户紧张：厂商 HAL 扫 ksu 进程产生的
        # avc 噪声在真机上动辄几百条，一律 warn 会让真正的信号被淹没（真机反馈）。
        if dmesg 2>/dev/null | grep -i 'avc: *denied' | grep -qE 'sunsetlinux|/data/sunsetlinux'; then
            warn "dmesg 里有 $n 条 'avc: denied'，其中**有涉及 /data/sunsetlinux 的**（要看具体行）"
            AVC_RELEVANT=1
        else
            info "dmesg 里有 $n 条 'avc: denied'，但都**与本项目无关**（没有一条提到 /data/sunsetlinux；多为厂商 HAL 扫 ksu 进程）"
        fi
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
        if logcat -d -b all 2>/dev/null | grep -i 'avc: *denied' | grep -qE 'sunsetlinux|/data/sunsetlinux'; then
            warn "logcat 里有 $n2 条 'avc: denied'，其中有涉及 /data/sunsetlinux 的（要看具体行）"
            AVC_RELEVANT=1
        else
            info "logcat 里有 $n2 条 'avc: denied'，但都**与本项目无关**（没有提到 /data/sunsetlinux）"
        fi
        logcat -d -b all 2>/dev/null | grep -i 'avc: *denied' | tail -n 5 | sed 's/^/        /'
        AVC_HITS=$(( AVC_HITS + n2 ))
    else
        ok "logcat 无 avc denial"
    fi
else
    info "无 logcat 命令，跳过"
fi
if [ "$AVC_HITS" -gt 0 ]; then
    if [ "$AVC_RELEVANT" = "1" ]; then
        add_finding warn avc_denied "$AVC_HITS 条 avc denial，其中**有涉及 $LH 的**（看上文具体行）"
        info "若确认与 $LH 相关，再考虑加 sepolicy.rule（本项目默认**不带**，见 module/README 说明）"
    else
        # 与本项目无关的 denial = 不是本项目的问题，JSON 里也必须是 ok/中性，
        # 否则 App 的"自检未通过"会挂在这些噪声上。
        add_finding ok avc_denied "$AVC_HITS 条，均与本项目无关（未提到 $LH 或 su 域）"
    fi
else
    add_finding ok avc_denied "无"
fi

# ===========================================================================
head_ "6. proot 降级路线（**本模式已切割，不再降级**）"
# 决策 1（docs/module-variants.md §一）：root App **锁定**真 chroot + overlayfs，
# 不再有"没有 su 就降级到 proot"这条路 —— 那条路会去动**另一个环境**（App 私有目录下的
# proot rootfs），用户以为在修 root 环境，实际动的是别处。免 root 请用免 root 版 App。
# 这里只如实报一句、不再打 warn：在本模式里"没有 proot"是**预期状态**，不是缺陷。
info "root 模式不提供 proot 降级；免 root 请使用免 root 版 App（包名 …sunsetlinux.proot）"
add_finding ok proot "not-applicable（root 模式不降级）"
if [ -d "$LH/rootfs-proot" ]; then
    info "存在 $LH/rootfs-proot（那是免 root 版的东西，本模式不用它）"
fi

# ===========================================================================
head_ "7. DSH profile 与插件可解析性（docs/dsh-profile.md §4.2）"
# 7a) **内置 DSH**（随模块冻结的那份；docs/module-variants.md §2.3）
#   为什么单独报：用户更新出问题时的第一诉求是"回到装模块时那份"，而"那份在不在、
#   是哪个版本"只有 etc/dsh-builtin.json 说得清（它也是 rollback dsh 默认目标的来源）。
if [ -f "$ETC_DIR/dsh-builtin.json" ]; then
    _bv="$(tr -d ' \n\t' < "$ETC_DIR/dsh-builtin.json" 2>/dev/null | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -n1)"
    _bf="$(tr -d ' \n\t' < "$ETC_DIR/dsh-builtin.json" 2>/dev/null | sed -n 's/.*"file":"\([^"]*\)".*/\1/p' | head -n1)"
    [ -n "$_bf" ] || _bf="dsh-$_bv.erofs"
    if [ -n "$_bv" ] && [ -f "$LAYERS_DIR/$_bf" ]; then
        ok "内置 DSH：$_bv（随模块冻结 → $LAYERS_DIR/$_bf）"
        info "回到它：linuxctl rollback dsh（不带 --to 就回内置版本）"
        add_finding ok dsh_builtin "$_bv"
    else
        warn "有内置 DSH 记录（${_bv:-未知}）但层文件不在：$LAYERS_DIR/$_bf"
        info "重刷模块后重启，或在设备上重展开：linuxctl dsh builtin --force"
        add_finding warn dsh_builtin "missing-layer"
    fi
else
    info "没有内置 DSH 记录（bare 变体，或模块还没展开）——可用 linuxctl dsh install 从官方频道装"
fi
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
    # 环境没起来：检查层文件里有没有那个路径。
    # ★ 用 layer_has_path（对着**目标路径**问），不要拿不递归的 `--ls --path=/` 输出 grep ——
    #   后者永远是"没有"，真机上就是这么误报 [fail] 的。
    dl="$(find_layer dsh 2>/dev/null || true)"
    if [ -n "$dl" ]; then
        layer_has_path "$dl" "/root/.dsh/profiles/web/package.json"
        case $? in
            0)
                ok "dsh 层内含 /root/.dsh/profiles/web/package.json"
                add_finding ok profile "in-layer" ;;
            1)
                bad "**当前生效**的 dsh 层内没有 /root/.dsh/profiles/** → dsh web 会退化成桌面版（supervise.sh 会退 78）"
                # ★ 真机 2026-09-17 的教训：这里最容易误读 —— 上面 §7a 可能刚说过
                #   "内置 DSH 0.1.5-rc.2 已就位"，而这条 fail 说的是 **state.json 指的那份**
                #   （可能是设备侧自建的旧层）。两行同时出现时用户完全无从下手，
                #   所以必须把"有好的、却没被用上"直接点出来，并给出那一条命令。
                _bv="$(tr -d ' \n\t' < "$ETC_DIR/dsh-builtin.json" 2>/dev/null | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -n1)"
                if [ -n "$_bv" ] && [ -f "$LAYERS_DIR/dsh-$_bv.erofs" ] && [ "$(basename "$dl")" != "dsh-$_bv.erofs" ]; then
                    info "但**内置那份是好的**：$LAYERS_DIR/dsh-$_bv.erofs（版本 $_bv，带 profile）"
                    info "一条命令切过去（只改 state.json 指向，不删任何层文件）："
                    info "  linuxctl rollback dsh"
                    info "（本机 dsh 生效版本会从 $(basename "$dl") 变成 dsh-$_bv.erofs；随后 linuxctl start）"
                else
                    info "修复：换一份带 profile 的 dsh 层（docs/dsh-profile.md §3.1 / §5）——"
                    info "  linuxctl update dsh <dsh-<版本>.erofs> --version <版本>"
                    info "或重跑 device-provision.sh（模块 ≥1.0.6 随包带 profiles/，构建时会装好 profile）"
                fi
                add_finding fail profile "layer-missing-profile" ;;
            *)
                info "环境未运行且无 dump.erofs/unsquashfs，跳过 profile 检查（启动后可用 linuxctl exec -- ls /root/.dsh/profiles/web 复核）"
                add_finding warn profile "unchecked" ;;
        esac
    else
        info "找不到 dsh 层文件，跳过 profile 检查"
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
    if [ -n "$rl" ]; then
        # 同上：必须对着路径问，不能靠不递归的目录列表（真机上误报 [warn] 的另一处）
        layer_has_path "$rl" "/opt/node/bin/pnpm"
        case $? in
            0)
                ok "runtime 层内含 /opt/node/bin/pnpm（dsh plugin 可用）"
                add_finding ok pnpm "in-layer" ;;
            1)
                warn "runtime 层里没有 pnpm → 用户无法用 dsh plugin add 装第三方插件"
                info "修复：换用带 pnpm 的 runtime 层（rootfs/profiles/runtime.packages 里列着 node+pnpm，重跑 device-provision.sh 即会带上）"
                add_finding warn pnpm "missing" ;;
            *)
                warn "未找到 pnpm（dsh plugin 会报 'pnpm not found on PATH'）"
                add_finding warn pnpm "missing" ;;
        esac
    else
        warn "未找到 pnpm 且没有 runtime 层文件可查（dsh plugin 会报 'pnpm not found on PATH'）"
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
        # 本次是哪种起法（§3.4）：doctor 是排障入口，"环境在跑但没 DSH"必须一眼看出来，
        # 否则用户会照着一键启动的预期去找一个根本不存在的故障。
        case "$(cat "$RUN_DIR/env-mode" 2>/dev/null | tr -d ' \n\r')" in
            full)     info "本次是「一键启动」：环境 + DSH（run/env-mode=full）" ;;
            env-only) info "本次是「仅启动环境」：环境在跑、DSH 没起（run/env-mode=env-only，维护模式）" ;;
            *)        info "run/env-mode 缺失或无法识别：判不了本次是一键启动还是仅启动环境（旧模块起的？）" ;;
        esac
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
    add_finding ok dsh_url "present"
elif env_running && [ "$(cat "$RUN_DIR/env-mode" 2>/dev/null | tr -d ' \n\r')" = "env-only" ]; then
    # 「仅启动环境」（`linuxctl start --no-dsh`）：**没有 dsh.url 才是正常的** ——
    # 这条路就是给"在环境里更新/装包/开终端"用的，DSH 没起来是预期行为，不是故障。
    # （§3.4；用错判据会把它报成 fail，用户就会去"修"一个本来就对的状态。）
    ok "环境按「仅环境」方式运行（run/env-mode=env-only）：没跑 DSH 是预期行为"
    info "要用 DSH：linuxctl dsh start（环境保持运行）；要改成整体启动：先 linuxctl stop"
    add_finding ok dsh_url "env-only"
elif env_running; then
    # 环境在跑却没有登录 URL = App 一定打不开界面（"环境已在运行，但登录地址还没写出来"）。
    # 这与"环境没起来"是**两件事**，必须分开报，否则用户会去重启一个本来就好的环境。
    # 2026-09-17 真机事故：模块 ≤1.0.26 的 entry.sh/supervise.sh 用 "$LINUX_HOME/run" 拼路径，
    # 而它们在 chroot 内跑（根是 overlay）→ 写进了**可写层里的假目录**，宿主读不到。
    bad "环境在运行，但 run/dsh.url 不存在 —— App 拿不到带令牌登录 URL（WebView 打不开）"
    add_finding fail dsh_url "running but run/dsh.url missing"
    # 找那个错位副本：upperdir 在 loop 模式是 $LH/upper/upper、dir 模式是 $LH/dirs-upper；
    # 环境内的 run 目录按老代码是 "$LINUX_HOME/run"，而环境内的 LINUX_HOME 要么继承了宿主的
    # 路径、要么退回内置默认值 /data/sunsetlinux —— 两种都试，别只赌一种。
    _stray=""
    for _up in "$LH/upper/upper" "$LH/dirs-upper"; do
        for _gr in "$LH" /data/sunsetlinux; do
            _c="$_up$_gr/run/dsh.url"
            if [ -f "$_c" ]; then _stray="$_c"; break; fi
        done
        if [ -n "$_stray" ]; then break; fi
    done
    if [ -n "$_stray" ]; then
        info "可写层里有一个**错位副本**：$_stray"
        info "根因（模块 ≤1.0.26）：entry.sh / supervise.sh 用 \$LINUX_HOME/run 拼 run 目录，"
        info "  但它们在 chroot 内运行，那里的 /data/sunsetlinux 不是宿主目录 → 写进了可写层。"
        info "  1.0.27 起改写 /run（宿主 \$LINUX_HOME/run 的 rbind 落点）；升级模块后重启环境即修好。"
        info "要立刻登录：把错位副本按 dsh.url / dsh.port / dsh.pid 三个一起 cp 到 $RUN_DIR/，再点 App 的「重新登录」"
        info "  （这只是临时续命：下次 start 仍会写错位，除非升级到模块 1.0.27）"
    else
        info "可写层里也没有错位副本：更可能是 dsh 还没起/起不来 —— 看 $RUN_DIR/linux.log 里有没有"
        info "  'dsh web: http://127.0.0.1:<port>/?token=...' 这一行（没有就是 supervisor 阶段就失败了）。"
    fi
else
    info "run/dsh.url 不存在（环境未运行）"
    add_finding ok dsh_url "absent-stopped"
fi
if [ -s "$RUN_DIR/last-error" ]; then
    _le="$(head -n1 "$RUN_DIR/last-error" 2>/dev/null)"
    if env_running; then
        # 环境都在跑了，这条失败记录必然是**上一次**的 —— 把它当 fail 报，
        # 用户会以为"现在也是坏的"（App 的「自检未通过」也会因此常年挂着）。
        info "run/last-error 里是**上一次**的失败记录（当前环境已在运行，这条是历史的）：$_le"
        add_finding ok last_error "历史记录：$_le"
    else
        bad "run/last-error：$_le"
        add_finding fail last_error "$_le"
        # 针对"挂载 upper 失败"这一类给出可执行下一步：它是最常见、也是最难自己看懂的
        # （内核只回一句 'I/O error'）。新 start.sh 会在失败前自动做四件事，并在日志里
        # 留下内核原文，所以这里的指引必须与实现一致。
        case "$_le" in
            *"挂载 upper 失败"*|*upper.img*)
                info "这一类失败与 §3 的「读写试挂」是同一件事（只读能挂 ≠ 读写能挂）"
                info "新 start.sh 的顺序：清残留 loop → e2fsck -p → 显式 losetup 重试 → 仍失败则记下 dmesg 原文"
                info "要立刻可用：设置 → 层模式 → dir（全程不碰 loop/upper.img，代价是磁盘占用）"
                info "要看内核原话：dmesg | grep -iE 'loop|ext4|jbd2' | tail -20" ;;
        esac
    fi
fi

# ===========================================================================
head_ "结论"
if [ "$FAILS" -gt 0 ]; then
    printf '  \033[31m%d 项 fail\033[0m，%d 项 warn\n' "$FAILS" "$WARNS"
else
    printf '  \033[32m全部通过\033[0m（%d 项 warn）\n' "$WARNS"
fi

# 结尾一行 JSON（App / 自动化解析用）
# findings 已由 add_finding 拼成逗号分隔的完整数组体，这里直接嵌进去
printf '{"schema":1,"ok":%s,"fails":%d,"warns":%d,"linux_home":"%s","findings":[%s]}\n' \
    "$([ "$FAILS" -eq 0 ] && echo true || echo false)" "$FAILS" "$WARNS" "$(jesc "$LH")" "$FINDINGS"

[ "$FAILS" -eq 0 ] && exit 0 || exit 1
