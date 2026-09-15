#!/usr/bin/env bash
# =============================================================================
# dshroid · runtime/root/start.sh
#
# 按 architecture.md §4 的 10 步顺序建立 root 模式挂载树，并把环境拉起来。
#
# 架构要点（为什么要写成"两段式"）：
#   §4 第 1 步是 `unshare -m` —— 新 mount namespace **只影响被 unshare 出来的那个
#   子进程**。所以 mount 序列 + chroot 必须**同一个进程/同一个 ns 里**跑完，否则
#   linuxctl（父进程）挂的树在自己的 ns 里，chroot 又看不到 → 白干。
#   因此：
#     父进程（linuxctl / 本脚本默认模式）：unshare -m -u + setsid 起一个守护子进程，
#                                        它持有命名空间，直到环境停止。
#     子进程（--inner）：挂载树 + 失败回滚 + 每挂一个立刻登记到 run/mounts；
#                        全部就绪后写 run/mounted.json、run/ready，再前台跑 entry.sh。
#   守护子进程 = 命名空间的锚点（run/supervisor.pid 记它的 PID），
#   stop.sh 用 nsenter --mount=/proc/<pid>/ns/mnt 进去优雅卸载。
#
# 层文件格式：**本机内核实测不支持 squashfs**（`# CONFIG_SQUASHFS is not set`，
# /proc/filesystems 里没有 squashfs，也没有 squashfs.ko），但 **EROFS 是内核内建**
# （CONFIG_EROFS_FS=y + /proc/filesystems 有 erofs，/sys/fs/erofs 有活动挂载）。
# 所以层格式由 layer_format 探测（erofs / squashfs 都支持），
# 默认走 EROFS，见 LINUX_LAYER_EXT。overlay 的 lowerdir 必须是**目录**，
# 因此每个层先 loop 挂到 layers-mnt/<name>，再用这些目录做 lowerdir。
#
# 只 unshare mount + UTS：
#   - 不 unshare net（architecture.md §1 / findings §6：进 netns 会断网）
#   - 不 unshare pid（内核无 CONFIG_PID_NS，findings §2）
#   - hostname 只在 UTS ns 内改，不影响宿主
#
# 用法：
#   start.sh                  # 幂等启动（默认）
#   start.sh --foreground     # 不 setsid 脱离终端（调试用）
#   start.sh --inner          # 内部使用：已在正确的 ns 中，只做挂载+运行
#   start.sh --check-ready    # 只等待就绪并打印状态 JSON（linuxctl 用）
#
# 退出码：0 成功 / 1 失败（失败时 run/last-error 里是可读原因）
# =============================================================================
set -euo pipefail

# --- 路径与常量 -------------------------------------------------------------
SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
LINUX_HOME="${LINUX_HOME:-/data/linux}"
LH="$LINUX_HOME"

RUN_DIR="$LH/run"
ETC_DIR="$LH/etc"
STATE_JSON="$ETC_DIR/state.json"
LAYERS_DIR="$LH/layers"
LAYERS_MNT="$LH/layers-mnt"
UPPER_DIR="$LH/upper"
WORK_DIR="$LH/work"
ROOTFS_DIR="$LH/rootfs"

SUPERVISOR_PID_FILE="$RUN_DIR/supervisor.pid"
DSH_PID_FILE="$RUN_DIR/dsh.pid"
READY_FILE="$RUN_DIR/ready"
MOUNTS_FILE="$RUN_DIR/mounts"
MOUNTED_JSON="$RUN_DIR/mounted.json"
ERROR_FILE="$RUN_DIR/last-error"
DAEMON_LOG="$RUN_DIR/start.log"


# 层格式：空 = 自动探测；也可用 LINUX_LAYER_EXT=.squashfs 强制
LINUX_LAYER_EXT="${LINUX_LAYER_EXT:-}"
LAYER_NAMES=( base runtime dsh )

# 常用工具（本脚本在宿主 Android 侧运行，走 toybox；不要假定有 GNU 专属选项）
UMOUNT=/system/bin/umount
MOUNT=/system/bin/mount
NSENTER=/system/bin/nsenter
SETSID=/system/bin/setsid
UNSHARE=/system/bin/unshare

MODE="start"
FOREGROUND=0
MAKE_PRIVATE=0
for arg in "$@"; do
    case "$arg" in
        --inner)      MODE=inner ;;
        --make-private) MODE=inner; MAKE_PRIVATE=1 ;;
        --foreground) FOREGROUND=1 ;;
        --check-ready) MODE=check_ready ;;
        --probe-only)  MODE=probe_only ;;
        *) echo "start.sh: 未知参数 $arg" >&2; exit 2 ;;
    esac
done

# --- 日志：一律走 stderr + 追加 run/linux.log（App 日志页读它）--------------
log() {
    local line
    line="[$(date '+%Y-%m-%d %H:%M:%S')] [start.sh] $*"
    printf '%s\n' "$line" >&2
    if [ -d "$RUN_DIR" ]; then
        printf '%s\n' "$line" >> "$RUN_DIR/linux.log" 2>/dev/null || true
    fi
}
die() {
    log "ERROR: $*"
    [ -d "$RUN_DIR" ] && printf '%s\n' "$*" > "$ERROR_FILE" 2>/dev/null || true
    exit 1
}

have() { command -v "$1" >/dev/null 2>&1; }
# 软警告：记日志但不失败（用于"能降级继续"的情况）
warn_soft() { log "WARN: $*"; return 0; }

# 路径所在文件系统的类型（stat -f 在 toybox 与 coreutils 都是 -c %T）
fstype_of() { stat -f -c '%T' "$1" 2>/dev/null || echo unknown; }

# ===========================================================================
# 命令能力探测（**必读，改挂载代码前先看这里**）
# ===========================================================================
# 事实：Android 的 /system/bin/{mount,unshare,chroot,losetup,umount} 都是
# **toybox 软链**，不是 util-linux。toybox 的选项支持面比 util-linux 窄，
# 而且**不同 Android 版本/厂商的 toybox 配置还不一样**。
# 设备 shell 策略又禁止我们直接跑 `mount --help` 去探测（会被拦截），
# 所以只能**运行时探测**，并且**每个探测都必须是"无害尝试"**。
#
# 探测结果缓存到 $RUN_DIR/cmdprobe（宿主可见，doctor 也读它），避免每次启动重复试。
# 格式：key=value，value ∈ long|short|none
PROBE_FILE="$RUN_DIR/cmdprobe"
# util-linux mount 回退（检测到 base 层已挂时才会填上）
UTIL_MOUNT=()

probe_get() {  # probe_get <key> [default]
    local v=""
    [ -f "$PROBE_FILE" ] && v="$(sed -n "s/^$1=//p" "$PROBE_FILE" 2>/dev/null | head -n1)"
    [ -n "$v" ] && printf '%s' "$v" || printf '%s' "${2:-none}"
}
probe_set() {
    local key="$1" val="$2"
    mkdir -p "$RUN_DIR" 2>/dev/null || true
    if [ -f "$PROBE_FILE" ]; then
        grep -v "^$key=" "$PROBE_FILE" > "$PROBE_FILE.tmp" 2>/dev/null || : > "$PROBE_FILE.tmp"
        mv -f "$PROBE_FILE.tmp" "$PROBE_FILE" 2>/dev/null || true
    fi
    printf '%s=%s\n' "$key" "$val" >> "$PROBE_FILE" 2>/dev/null || true
}

# 探测 mount 的绑定挂载语法。**无害**：全是必然失败的挂载（源路径不存在），
# 我们只看"命令是否把参数判成语法错误"，不看挂载成功与否。
# 判定：stderr 出现 usage/bad/unknown/invalid 之类 => 该语法不被支持。
probe_mount_syntax() {
    local m="$1" tmp out rc
    tmp="$RUN_DIR/.probe-$$"
    mkdir -p "$tmp" 2>/dev/null || tmp=/tmp
    out="$("$m" --rbind /nonexistent-dshroid-probe "$tmp" 2>&1)"; rc=$?
    case "$out" in
        *[Uu]sage*|*[Bb]ad*option*|*unknown*option*|*[Ii]nvalid*|*unrecognized*)
            probe_set mount_rbind short ;;
        *)  [ "$rc" -ne 0 ] && probe_set mount_rbind long || probe_set mount_rbind long ;;
    esac
    out="$("$m" -o rbind /nonexistent-dshroid-probe "$tmp" 2>&1)"; rc=$?
    case "$out" in
        *[Uu]sage*|*[Bb]ad*option*|*unknown*option*|*[Ii]nvalid*|*unrecognized*)
            probe_set mount_rbind_o none ;;
        *)  probe_set mount_rbind_o short ;;
    esac
    out="$("$m" --make-rslave "$tmp" 2>&1)"; rc=$?
    case "$out" in
        *[Uu]sage*|*[Bb]ad*option*|*unknown*option*|*[Ii]nvalid*|*unrecognized*)
            probe_set mount_make_rslave short ;;
        *)  probe_set mount_make_rslave long ;;
    esac
    out="$("$m" -o remount,bind "$tmp" 2>&1)"; rc=$?
    case "$out" in
        *[Uu]sage*|*[Bb]ad*option*|*unknown*option*|*[Ii]nvalid*|*unrecognized*)
            probe_set mount_remount_bind none ;;
        *)  probe_set mount_remount_bind ok ;;
    esac
    rmdir "$tmp" 2>/dev/null || true
}

# 探测 unshare 是否支持 --propagation（toybox 很可能是最近才加的）
probe_unshare_syntax() {
    local u="$1" out
    out="$("$u" -m --propagation private true 2>&1)" || true
    case "$out" in
        *[Uu]sage*|*[Bb]ad*option*|*unknown*option*|*[Ii]nvalid*|*unrecognized*|*[Nn]otfound*|*not*found*)
            probe_set unshare_propagation no ;;
        *)  probe_set unshare_propagation yes ;;
    esac
}

# 关键挂载（proc/sys/dev）是否有一条能用的 util-linux 回退路径
#   思路：base 层里的 util-linux 是**宿主 glibc 编译**的，不能直接当 Android 原生二进制跑；
#   但可以用它自己的动态加载器执行：
#     /…/layers-mnt/base/lib/ld-linux-aarch64.so.1 --library-path <该层 lib 目录> <该层 mount> …
#   base 层只需要**已经 loop 挂上**（在挂载树里它是最早挂的那批之一），所以这条回退可行。
detect_util_mount() {
    local base="$LAYERS_MNT/base" ld="" m=""
    local cand
    for cand in "$base/lib/ld-linux-aarch64.so.1" "$base/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"; do
        [ -x "$cand" ] && { ld="$cand"; break; }
    done
    for cand in "$base/usr/bin/mount" "$base/bin/mount"; do
        [ -x "$cand" ] && { m="$cand"; break; }
    done
    if [ -n "$ld" ] && [ -n "$m" ]; then
        UTIL_MOUNT=( "$ld" --library-path "$base/lib:$base/usr/lib:$base/lib/aarch64-linux-gnu:$base/usr/lib/aarch64-linux-gnu" "$m" )
        probe_set util_mount yes
    else
        UTIL_MOUNT=()
        probe_set util_mount no
    fi
}

# 跑一次全部探测（幂等：已有结果且不是 --force 就跳过）
run_probes() {
    local force="${1:-0}"
    if [ "$force" != "1" ] && [ -s "$PROBE_FILE" ] && grep -q '^probed=' "$PROBE_FILE" 2>/dev/null; then
        return 0
    fi
    : > "$PROBE_FILE" 2>/dev/null || true
    log "开始探测 toybox 的 mount/unshare 选项支持（结果写入 $PROBE_FILE）"
    [ -x "$MOUNT" ] && probe_mount_syntax "$MOUNT"
    [ -x "$UNSHARE" ] && probe_unshare_syntax "$UNSHARE"
    find_layer base >/dev/null 2>&1 && detect_util_mount
    printf 'probed=%s\n' "$(date +%s)" >> "$PROBE_FILE" 2>/dev/null || true
    log "探测结果：rbind=$(probe_get mount_rbind) | rbind_o=$(probe_get mount_rbind_o) | make_rslave=$(probe_get mount_make_rslave) | unshare_propagation=$(probe_get unshare_propagation) | util_mount=$(probe_get util_mount)"
}

# rbind 挂载（语法自适应）
#   顺序：--rbind → -o rbind（递归）→ -o bind（非递归，子挂载丢，要告警）
do_rbind_op() {
    local src="$1" dst="$2"
    case "$(probe_get mount_rbind)" in
        long)  "$MOUNT" --rbind "$src" "$dst" 2>>"$DAEMON_LOG" && return 0 ;;
    esac
    case "$(probe_get mount_rbind_o)" in
        short) "$MOUNT" -o rbind "$src" "$dst" 2>>"$DAEMON_LOG" && return 0 ;;
    esac
    if "$MOUNT" -o bind "$src" "$dst" 2>>"$DAEMON_LOG"; then
        warn_soft "$dst 只能用非递归 bind（子挂载不会带进来）"
        return 0
    fi
    return 1
}

# make-rslave（**非致命**：私有命名空间里已经不会传播回宿主，
# 所以长选项不支持时只告警，绝不让启动失败 —— 见交付说明）
do_make_rslave() {
    local target="$1"
    case "$(probe_get mount_make_rslave)" in
        long)  "$MOUNT" --make-rslave "$target" 2>>"$DAEMON_LOG" && return 0 ;;
        short) "$MOUNT" -o make-rslave "$target" 2>>"$DAEMON_LOG" && return 0 ;;
    esac
    log "提示：$target 未能设为 rslave（toybox 不支持该选项）。私有 mount ns 已隔断传播，可继续"
    return 1
}

# ---------------------------------------------------------------------------
# 幂等判断：环境是否已经在跑
#   ready 文件 + supervisor.pid 存活 + /proc/<pid>/ns/mnt 可读 → 已经在跑
# ---------------------------------------------------------------------------
running_ns_pid() {
    local pid=""
    [ -f "$SUPERVISOR_PID_FILE" ] || return 1
    pid="$(head -n1 "$SUPERVISOR_PID_FILE" 2>/dev/null | tr -dc '0-9' || true)"
    [ -n "$pid" ] || return 1
    [ -d "/proc/$pid" ] || return 1
    [ -r "/proc/$pid/ns/mnt" ] || return 1
    printf '%s' "$pid"
}

# ---------------------------------------------------------------------------
# wait_ready <timeout_sec>
#   等 run/ready 出现且 supervisor 存活。轮询而不是睡眠定长，避免"start 返回了
#   但树还没挂好"的竞态。
# ---------------------------------------------------------------------------
wait_ready() {
    local timeout="${1:-30}" i=0 pid rc=0
    while [ "$i" -lt $((timeout * 10)) ]; do
        if [ -f "$READY_FILE" ]; then
            pid="$(running_ns_pid 2>/dev/null || true)"
            # ready 存在但守护进程死了 → 启动失败，读出原因
            if [ -z "$pid" ]; then
                if [ -s "$ERROR_FILE" ]; then rc=1; break; fi
            else
                return 0
            fi
        else
            # 守护进程还没写 ready 就退了 → 失败
            if [ -f "$SUPERVISOR_PID_FILE" ] && ! running_ns_pid >/dev/null 2>&1 \
               && [ -s "$ERROR_FILE" ]; then
                rc=1; break
            fi
        fi
        sleep 0.1
        i=$(( i + 1 ))
    done
    return "$rc"
}

# ---------------------------------------------------------------------------
# layer_format <file> —— 判定层格式
#   squashfs: magic 'hsqs'（小端存放为 68 73 71 73）在**偏移 0**
#   erofs   : magic 0xE0F5E1E2（小端 e2 e1 f5 e0）在**偏移 1024**
# ★ 关键坑（实测踩过）：EROFS 的超级块**不在文件开头**，偏移 0 处是 0x00000000。
#   只读偏移 0 会把**每一个真实 erofs 镜像**误判为 unknown，
#   于是 update 拒收合法层、start 拒绝挂载 —— 症状是"层明明没问题却报格式不可识别"。
#   实测：真实 erofs 偏移 0 = 00000000，偏移 1024 = e2e1f5e0。
# 为什么按 magic 判而不是按扩展名：产物可能来自第三方频道，扩展名不可信。
# ---------------------------------------------------------------------------
_magic_at() { # _magic_at <file> <offset> —— 返回小写 hex，失败返回空
    local f="$1" off="$2" hex=""
    have od || return 1
    if [ "$off" = "0" ]; then
        hex="$(od -An -tx1 -N4 "$f" 2>/dev/null | tr -d ' \n')"
    else
        # 用 dd 取偏移处的 4 字节：toybox 的 `od -j` 支持面不确定，dd 更稳（同为 toybox 自带）
        hex="$(dd if="$f" bs=1 skip="$off" count=4 2>/dev/null \
               | od -An -tx1 2>/dev/null | tr -d ' \n')"
    fi
    printf '%s' "$hex" | tr 'A-F' 'a-f'
}

layer_format() {
    local f="$1" h0="" h1024=""
    [ -f "$f" ] || { printf 'missing'; return 1; }
    h0="$(_magic_at "$f" 0)"
    case "$h0" in
        68737173) printf 'squashfs'; return 0 ;;
    esac
    h1024="$(_magic_at "$f" 1024)"
    case "$h1024" in
        e2e1f5e0) printf 'erofs'; return 0 ;;
    esac
    printf 'unknown'; return 1
}

# find_layer <name> —— 返回层文件路径（支持 .erofs / .squashfs），找不到返回空
find_layer() {
    local name="$1" cand
    # ---- ① 首选：state.json 里记录的**当前生效版本** ----------------------
    # 这一点很关键：`sort -V` 取的是"版本号最高"，但"最高"≠"当前生效"。
    # 例如 update 失败留下了一个更新的层文件，或用户保留了旧层用于回滚 ——
    # 此时挑最高版本会挑错层，回滚也会失效。
    if [ -f "$STATE_JSON" ]; then
        local ver=""
        ver="$(tr -d ' \n\t' < "$STATE_JSON" 2>/dev/null \
               | sed -n "s/.*\"$name\":{[^}]*\"version\":\"\([^\"]*\)\".*/\1/p" | head -n1)"
        if [ -n "$ver" ] && [ "$ver" != "unknown" ] && [ "$ver" != "null" ]; then
            for cand in "$LAYERS_DIR/$name-$ver.erofs" "$LAYERS_DIR/$name-$ver.squashfs"; do
                [ -f "$cand" ] && { printf '%s' "$cand"; return 0; }
            done
            log "WARN: state.json 记的 $name 版本 $ver 找不到对应文件，回退为扫描目录"
        fi
    fi
    # ---- ② 回退：兼容无版本后缀的旧命名 + 扫描取最高版本（并告警）--------
    local best="" ver2=""
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
    if [ -n "$best" ]; then
        log "WARN: 未从 state.json 定位到 $name 层，按版本号最高选取：$(basename "$best")"
        printf '%s' "$best"; return 0
    fi
    return 1
}

# mount_layer <name> <file> —— 把只读层 loop 挂到 $LAYERS_MNT/<name>
#   erofs 与 squashfs 的挂载选项基本一致（-t <fs> -o loop,ro）。
#   失败原因要能诊断：内核不支持该 fs → 提示 doctor 第 1 节。
mount_layer() {
    local name="$1" file="$2" fmt="" dst="$LAYERS_MNT/$name" fssup=""
    fmt="$(layer_format "$file" 2>/dev/null || echo unknown)"
    case "$fmt" in
        erofs|squashfs) ;;
        *) die "层 $name 既不是 erofs 也不是 squashfs（magic=$fmt）：$file 可能没下完或已损坏" ;;
    esac
    # 内核支持性检查（/proc/filesystems 里没有就不要白试）
    if ! fssup="$(grep -w "$fmt" /proc/filesystems 2>/dev/null)"; then
        die "内核不支持 $fmt（/proc/filesystems 里没有）。层 $name=$file 无法挂载。若是 squashfs，本机内核 CONFIG_SQUASHFS 未启用，请改用 erofs 层（见 doctor 第 1 节）"
    fi
    mkdir -p "$dst" || die "无法创建层挂载点 $dst"
    if mountpoint -q "$dst" 2>/dev/null; then
        "$UMOUNT" -l "$dst" 2>/dev/null || die "无法卸载残留的层挂载点 $dst"
    fi
    log "挂载层 $name（$fmt）: $file -> $dst"
    if ! "$MOUNT" -t "$fmt" -o loop,ro "$file" "$dst" 2>>"$DAEMON_LOG"; then
        die "层 $name 挂载失败（$fmt, $file）：内核可能不支持该格式或文件损坏（doctor 可诊断）"
    fi
    printf '%s\n' "layers-mnt/$name" >> "$MOUNTS_FILE"
}

# ---------------------------------------------------------------------------
# 已挂载登记表（用于 stop.sh 的精确卸载）
#   顺序登记；stop.sh 反序卸载。用 mountpoint 实测判定，不靠状态猜测。
# ---------------------------------------------------------------------------
record_mount() {
    local rel="$1"
    printf '%s\n' "$rel" >> "$MOUNTS_FILE"
}

is_mounted() {
    local rel="$1"
    [ -d "$ROOTFS_DIR/$rel" ] || return 1
    mountpoint -q "$ROOTFS_DIR/$rel" 2>/dev/null
}

# ---------------------------------------------------------------------------
# do_mount <rel_dst> <source> [fstype] [options]
#   挂载 + 登记 + 失败即 die（由 cleanup_trap 反序回滚）。绝不使用 set -e 之外
#   的静默失败，避免留下半挂状态。
# ---------------------------------------------------------------------------
do_mount() {
    local rel="$1"; shift
    local dst="$ROOTFS_DIR/$rel"
    [ -d "$dst" ] || mkdir -p "$dst" || die "无法创建挂载点 $dst"
    log "mount $rel <- $*"
    if ! "$MOUNT" "$@" "$dst" 2>>"$DAEMON_LOG"; then
        die "挂载 $rel 失败：mount $* $dst"
    fi
    record_mount "$rel"
}

do_bind() {
    local rel="$1" src="$2" opts="${3:-}"
    local dst="$ROOTFS_DIR/$rel"
    [ -d "$dst" ] || mkdir -p "$dst" || die "无法创建挂载点 $dst"
    log "rbind $src -> $rel ${opts:+($opts)}"
    # 语法自适应：--rbind / -o rbind / -o bind（见 do_rbind_op）
    if ! do_rbind_op "$src" "$dst"; then
        # 最后兜底：base 层里的 util-linux mount（若能取到）
        if [ "${#UTIL_MOUNT[@]}" -gt 0 ] && \
           "${UTIL_MOUNT[@]}" --rbind "$src" "$dst" 2>>"$DAEMON_LOG"; then
            warn_soft "$rel 走 util-linux 回退路径（toybox mount 语法不足）"
        else
            die "bind 挂载 $src -> $rel 失败（toybox mount 不支持，且无 util-linux 回退）"
        fi
    fi
    # rw/ro 之类的选项在 bind 之后需要 remount 才生效
    if [ -n "$opts" ]; then
        local ok=1
        "$MOUNT" --remount "$dst" -o "$opts" 2>>"$DAEMON_LOG" && ok=0
        [ "$ok" != "0" ] && "$MOUNT" -o "remount,$opts" "$dst" 2>>"$DAEMON_LOG" && ok=0
        [ "$ok" != "0" ] && log "提示：remount $rel ($opts) 未成功，按原属性继续"
    fi
    record_mount "$rel"
}

# ---------------------------------------------------------------------------
# 失败回滚：反序卸载已登记的挂载点。任何一步失败都走这里。
# ---------------------------------------------------------------------------
unmount_recorded() {
    local rel
    [ -f "$MOUNTS_FILE" ] || return 0
    # tac 不一定有：用数组反序
    local -a list=()
    while IFS= read -r rel; do
        [ -n "$rel" ] && list+=( "$rel" )
    done < "$MOUNTS_FILE"
    local i
    for (( i=${#list[@]}-1; i>=0; i-- )); do
        rel="${list[$i]}"
        if is_mounted "$rel"; then
            log "回滚：umount $rel"
            "$UMOUNT" -l "$ROOTFS_DIR/$rel" 2>/dev/null || \
                "$UMOUNT" -f "$ROOTFS_DIR/$rel" 2>/dev/null || \
                log "WARN: umount $rel 失败（可能需要手动清理）"
        fi
    done
    : > "$MOUNTS_FILE"
}

cleanup_on_fail() {
    local rc=$?
    if [ "$rc" -ne 0 ]; then
        log "启动失败（rc=$rc），回滚已做的挂载"
        unmount_recorded
        rm -f "$READY_FILE" "$SUPERVISOR_PID_FILE"
    fi
    return "$rc"
}

# ---------------------------------------------------------------------------
# 挂载树本体（即 §4 的 3–9 步；1/2 步由父进程的 unshare 完成，10 步是 chroot）
# ---------------------------------------------------------------------------
build_mount_tree() {
    # --- 前置检查：层文件齐不齐 + 格式是否被内核支持 -----------------------
    local l lf fmt
    for l in "${LAYER_NAMES[@]}"; do
        lf="$(find_layer "$l" || true)"
        [ -n "$lf" ] || die "缺少层文件 $LAYERS_DIR/$l.{erofs,squashfs}（先跑 linuxctl provision）"
        fmt="$(layer_format "$lf" 2>/dev/null || echo unknown)"
        case "$fmt" in
            erofs|squashfs) log "层 $l = $lf（格式 $fmt）" ;;
            *) die "层 $l 格式不可识别（magic=$fmt）：$lf" ;;
        esac
    done
    [ -f "$LH/upper.img" ] || die "缺少可写层镜像 $LH/upper.img（先跑 linuxctl provision）"

    # 残留清理：上次异常退出可能留下 mounts 记录，先按记录卸一遍
    if [ -s "$MOUNTS_FILE" ]; then
        log "发现上次残留的挂载记录，先清理"
        unmount_recorded
    fi

    # --- §4 步 3：ext4 可写层（loop）--------------------------------------
    # 说明：这里用 `-o loop` 让内核自动分配 loop 设备。内核 CONFIG_EXT4_FS=y。
    do_mount upper "$LH/upper.img" -t ext4 -o loop,rw,noatime
    mkdir -p "$UPPER_DIR/upper" "$UPPER_DIR/work" || die "无法创建 overlay upper/work 目录"

    # --- §4 步 4（前半）：把三层只读镜像 loop 挂成目录 ---------------------
    # **对 architecture.md §4 的必要修正**：原文写 `lowerdir=layers/dsh:...`，
    # 直接把镜像文件路径当 lowerdir。这要求镜像能被 overlayfs 直接当 lower，
    # 而 overlayfs 的 lowerdir 必须是**目录**（squashfs/erofs 镜像是块设备内容，
    # 不是目录）。因此先把每层 loop 挂到 $LAYERS_MNT/<name>，再用这些目录组 lowerdir。
    # 代价：多 3 个 loop 挂载；收益：格式无关（erofs/squashfs 都行），且能对每层
    # 单独做健康检查。
    mkdir -p "$LAYERS_MNT"
    for l in "${LAYER_NAMES[@]}"; do
        mount_layer "$l" "$(find_layer "$l")"
    done
    # base 层挂上之后才可能取到 util-linux 的 mount，这里补一次探测，
    # 让后面的 proc/sys/dev 挂载有真正的回退路径可用
    detect_util_mount

    # --- §4 步 4（后半）：overlay 合并层 -----------------------------------
    # 顺序：最右 = 最底层。dsh 在最上 → 更新 dsh 层即可换 DSH 版本。
    local ovl_opts="lowerdir=$LAYERS_MNT/dsh:$LAYERS_MNT/runtime:$LAYERS_MNT/base"
    ovl_opts="$ovl_opts,upperdir=$UPPER_DIR/upper,workdir=$UPPER_DIR/work"
    # 挂载点先建好（overlay 要求目录存在且为空；非空会报 ENOTEMPTY）
    [ -d "$ROOTFS_DIR" ] || mkdir -p "$ROOTFS_DIR" || die "无法创建 $ROOTFS_DIR"
    if ! "$MOUNT" -t overlay overlay -o "$ovl_opts" "$ROOTFS_DIR" 2>>"$DAEMON_LOG"; then
        die "overlay 挂载失败（lowerdir/upperdir/workdir 见 $DAEMON_LOG）"
    fi
    # 登记为 'overlay'（相对 rootfs 就是它自己），stop.sh 会把它放到**最后**卸载
    record_mount "overlay"

    # 环境内需要的目录骨架（overlay 可写层里建，重启保持）
    mkdir -p "$ROOTFS_DIR"/{proc,sys,dev,dev/pts,dev/shm,tmp,run,mnt/sdcard,storage/emulated} \
        || die "无法创建 rootfs 内部目录骨架"

    # --- 抓取 Android 侧事实（DNS/时区），chroot 后就取不到了 ---------------
    gather_android_facts

    # --- 先把 /opt/dshroid 入口脚本同步进 rootfs（本地最新版优先）----------
    # 理由：entry.sh/supervise.sh 属于"启动器"，不该随 squashfs 层更新才有新版。
    install_runtime_entry

    # --- §4 步 5：/proc ----------------------------------------------------
    do_mount proc -t proc proc

    # --- §4 步 6：/sys（rbind + rslave，ro）--------------------------------
    # 为什么不 rw：overlay 的 known issue 要求自己别被别的 fs 当 upper 覆盖，
    # 而宿主 /sys 是 sysfs；bind 成 ro 既避免写穿内核状态，也不影响 overlay。
    do_bind sys /sys ro
    do_make_rslave "$ROOTFS_DIR/sys" || true

    # --- §4 步 7：/dev（rbind + rslave；pts/shm 单独挂）--------------------
    do_bind dev /dev
    do_make_rslave "$ROOTFS_DIR/dev" || true
    # devpts 在 toybox 里没有专门实现，需要 -t devpts；失败则回退 syscall 形式
    if "$MOUNT" -t devpts devpts "$ROOTFS_DIR/dev/pts" 2>>"$DAEMON_LOG"; then
        record_mount "dev/pts"
    elif "$MOUNT" -t devpts -o mode=0620,gid=5 devpts "$ROOTFS_DIR/dev/pts" 2>>"$DAEMON_LOG"; then
        record_mount "dev/pts"
    else
        die "devpts 挂载失败（node-pty 需要伪终端）"
    fi
    do_mount dev/shm -t tmpfs -o mode=1777,nosuid,nodev tmpfs

    # --- §4 步 8：/tmp 与 /run ---------------------------------------------
    # /tmp 用 tmpfs（环境内临时文件，刻意不落盘）。
    do_mount tmp -t tmpfs -o mode=1777,nosuid,nodev tmpfs
    # /run 例外：**不用 tmpfs，而是把宿主的 $LINUX_HOME/run rbind 进来**。
    # 理由（对 architecture.md §2.1/§3.1 的落地修正）：
    #   契约要求环境把 dsh.pid / dsh.port / dsh.url / linux.log 写在 run/ 下，
    #   而 App 侧 linuxctl（不在 chroot 内）必须能读到这些文件。若 rootfs/run 是
    #   tmpfs，宿主就看不到里面的内容 → 契约无法实现。rbind 之后两侧是**同一批
    #   inode**，语义与 §2.1 完全一致，且不需要任何额外同步。
    rbind_host_run

    # --- §4 步 9：sdcard（优先绕开 FUSE 的 pass_through）-------------------
    mount_sdcard

    log "挂载树建立完成（共 $(wc -l < "$MOUNTS_FILE") 项）"
}

# ---------------------------------------------------------------------------
# rbind_host_run —— 让 rootfs/run 与宿主 $LINUX_HOME/run 是同一批文件
#   步骤：清掉 overlay 上可能存在的 /run 目录（不是挂载点，只是空目录），
#   建好宿主共享目录，再 --rbind。
# ---------------------------------------------------------------------------
rbind_host_run() {
    local dst="$ROOTFS_DIR/run"
    [ -d "$dst" ] || mkdir -p "$dst" || die "无法创建 $dst"
    if mountpoint -q "$dst" 2>/dev/null; then
        # 上一次的残留挂载：先卸掉再重挂
        "$UMOUNT" -l "$dst" 2>/dev/null || die "无法卸载残留的 $dst"
    fi
    do_bind run "$RUN_DIR"
}

# ---------------------------------------------------------------------------
# gather_android_facts —— chroot 之前抓 Android 当前 DNS / 时区，落到
#   $LH/etc/android-{resolv,dns,timezone}.txt，供 entry.sh 在环境内读取。
#
# 为什么必须在这里抓：chroot 之后根变成 overlay，Android 的 /system、/data 都
# 不可见，getprop / ndc 也都不在 PATH 里。所以在**宿主侧**先取好是唯一可靠做法。
#
# DNS 多路回退（设备实测：/system/etc/resolv.conf 在这个机型上不存在，net.dns1 为空）：
#   ① ndc resolver getresolvers        （netd 的权威答案）
#   ② ndc resolver getnetdns <netid>
#   ③ getprop 里所有含 dns 的属性
#   ④ /system/etc/resolv.conf          （部分机型/AOSP 有）
#   ⑤ 默认网关（/proc/net/route + 修正字节序）—— 只作为"可能的 DNS"提示，不写死
# 全部失败：不编造，entry.sh 会兜底 8.8.8.8/1.1.1.1 并在日志里警告。
# ---------------------------------------------------------------------------
gather_android_facts() {
    local dns_file="$ETC_DIR/android-dns.txt"
    local resolv_file="$ETC_DIR/android-resolv.txt"
    local tz_file="$ETC_DIR/android-timezone.txt"
    local dns="" line raw=""

    mkdir -p "$ETC_DIR" 2>/dev/null || true

    # --- ① ndc resolver getresolvers ---
    if [ -x /system/bin/ndc ]; then
        raw="$(/system/bin/ndc resolver getresolvers 2>/dev/null || true)"
        dns="$(printf '%s' "$raw" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' 2>/dev/null | sort -u | tr '\n' ' ' || true)"
        [ -n "$dns" ] && log "DNS 取自 ndc resolver getresolvers：$dns"
    fi
    # --- ② ndc resolver getnetdns（默认网络 id 通常是 0/100）---
    if [ -z "$dns" ] && [ -x /system/bin/ndc ]; then
        local netid
        for netid in 0 100 101 1; do
            raw="$(/system/bin/ndc resolver getnetdns "$netid" 2>/dev/null || true)"
            dns="$(printf '%s' "$raw" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' 2>/dev/null | sort -u | tr '\n' ' ' || true)"
            [ -n "$dns" ] && { log "DNS 取自 ndc resolver getnetdns $netid：$dns"; break; }
        done
    fi
    # --- ③ getprop ---
    if [ -z "$dns" ] && [ -x /system/bin/getprop ]; then
        raw=""
        for k in net.dns1 net.dns2 net.dns3 net.dns4 ro.net.dns1 dhcp.eth0.dns1; do
            local v
            v="$(/system/bin/getprop "$k" 2>/dev/null || true)"
            [ -n "$v" ] && raw="$raw $v"
        done
        dns="$(printf '%s' "$raw" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' 2>/dev/null | sort -u | tr '\n' ' ' || true)"
        [ -n "$dns" ] && log "DNS 取自 getprop：$dns"
    fi
    # --- ④ /system/etc/resolv.conf ---
    if [ -z "$dns" ] && [ -f /system/etc/resolv.conf ]; then
        dns="$(grep -E '^[[:space:]]*nameserver' /system/etc/resolv.conf 2>/dev/null \
               | awk '{print $2}' | grep -E '^([0-9]{1,3}\.){3}[0-9]{1,3}$' | tr '\n' ' ' || true)"
        [ -n "$dns" ] && log "DNS 取自 /system/etc/resolv.conf：$dns"
    fi
    # --- ⑤ 默认网关（仅提示，标注不可信）---
    if [ -z "$dns" ] && [ -f /proc/net/route ]; then
        local gw_hex gw
        # /proc/net/route 的 Gateway 是**小端十六进制**，需要按字节反转
        gw_hex="$(awk 'NR>1 && $2=="00000000" && $3!="00000000" {print $3; exit}' /proc/net/route 2>/dev/null || true)"
        if [ -n "$gw_hex" ] && [ "${#gw_hex}" = 8 ]; then
            gw="$(printf '%d.%d.%d.%d' "0x${gw_hex:6:2}" "0x${gw_hex:4:2}" "0x${gw_hex:2:2}" "0x${gw_hex:0:2}" 2>/dev/null || true)"
            if [ -n "$gw" ]; then
                dns="$gw"
                log "WARN: 未取到 netd 的 DNS，暂用默认网关 $gw 当 DNS（不保证可用）"
            fi
        fi
    fi

    if [ -n "$dns" ]; then
        printf '%s\n' "$dns" | tr ' ' '\n' | grep . > "$dns_file" 2>/dev/null || true
        { for line in $dns; do printf 'nameserver %s\n' "$line"; done; } > "$resolv_file" 2>/dev/null || true
        log "已写 $dns_file"
    else
        log "WARN: 完全取不到 Android DNS（ndc/getprop/resolv.conf/route 全部失败）"
        : > "$dns_file" 2>/dev/null || true
        : > "$resolv_file" 2>/dev/null || true
    fi

    # --- 时区 ---
    local tz=""
    if [ -x /system/bin/getprop ]; then
        tz="$(/system/bin/getprop persist.sys.timezone 2>/dev/null | tr -d ' \r\n' || true)"
    fi
    if [ -z "$tz" ] && [ -f /data/property/persist.sys.timezone ]; then
        tz="$(tr -d ' \r\n' < /data/property/persist.sys.timezone 2>/dev/null || true)"
    fi
    if [ -n "$tz" ]; then
        printf '%s\n' "$tz" > "$tz_file" 2>/dev/null || true
        log "时区取自 Android：$tz"
    else
        log "WARN: 取不到 persist.sys.timezone，环境内将回退 UTC"
    fi

    # --- 顺手清掉 rootfs 里可能存在的坏 resolv.conf 软链 ---
    # overlay 的 lower 里如果 /etc/resolv.conf 是指向 /run/systemd/... 的软链，
    # 环境内写它会 ENOTDIR。提前删掉，让 entry.sh 能直接创建普通文件。
    if [ -L "$ROOTFS_DIR/etc/resolv.conf" ]; then
        rm -f "$ROOTFS_DIR/etc/resolv.conf" 2>/dev/null && log "已删除 rootfs 内的 resolv.conf 软链（改由 entry.sh 生成）"
    fi
    return 0
}

# ---------------------------------------------------------------------------
# /opt/dshroid 启动器脚本同步（幂等）
# ---------------------------------------------------------------------------
install_runtime_entry() {
    local src="" dst="$ROOTFS_DIR/opt/dshroid"
    # 来源优先级：KernelSU 模块目录 → 本脚本同目录 → LINUX_HOME/bin
    for cand in /data/adb/modules/dshroid/bin "$SELF_DIR" "$LH/bin"; do
        if [ -f "$cand/entry.sh" ] && [ -f "$cand/supervise.sh" ]; then
            src="$cand"; break
        fi
    done
    if [ -z "$src" ]; then
        log "WARN: 找不到 entry.sh/supervise.sh 源，沿用 rootfs 里已有版本"
        return 0
    fi
    mkdir -p "$dst" || { log "WARN: 无法创建 $dst"; return 0; }
    cp -f "$src/entry.sh" "$dst/entry.sh" 2>/dev/null || true
    cp -f "$src/supervise.sh" "$dst/supervise.sh" 2>/dev/null || true
    chmod 0755 "$dst/entry.sh" "$dst/supervise.sh" 2>/dev/null || true
    log "已同步启动器脚本：$src -> $dst"
}

# ---------------------------------------------------------------------------
# sdcard 挂载：优先 /mnt/pass_through/0/emulated（绕过 FUSE），失败回退
# /storage/emulated/0。两处结果都写日志（architecture.md §1/§2）。
# ---------------------------------------------------------------------------
mount_sdcard() {
    local pt="/mnt/pass_through/0/emulated"
    local fb="/storage/emulated/0"
    local chosen="" fs=""

    if [ -d "$pt" ]; then
        log "sdcard 首选路径 $pt 存在，尝试 rbind（绕开 FUSE）"
        if do_rbind_op "$pt" "$ROOTFS_DIR/mnt/sdcard"; then
            chosen="$pt"
            record_mount "mnt/sdcard"
            fs="$(fstype_of "$ROOTFS_DIR/mnt/sdcard")"
            log "sdcard 已挂载：$pt -> /mnt/sdcard（fstype=$fs）"
            if [ "$fs" = "fuse" ] || [ "$fs" = "fuseblk" ]; then
                log "WARN: 结果仍是 $fs，说明 pass_through 本身被 FUSE 包着；回退到 $fb 再试一次"
                "$UMOUNT" -l "$ROOTFS_DIR/mnt/sdcard" 2>/dev/null || true
                chosen=""
            fi
        else
            log "WARN: rbind $pt 失败，回退 $fb"
        fi
    else
        log "sdcard 首选路径 $pt 不存在（非 Oplus 或 pass_through 未开），回退 $fb"
    fi

    if [ -z "$chosen" ]; then
        [ -d "$fb" ] || die "sdcard 回退路径 $fb 也不存在"
        if ! do_rbind_op "$fb" "$ROOTFS_DIR/mnt/sdcard"; then
            die "sdcard 两种路径都挂载失败（$pt / $fb）"
        fi
        record_mount "mnt/sdcard"
        chosen="$fb"
        fs="$(fstype_of "$ROOTFS_DIR/mnt/sdcard")"
        log "sdcard 已挂载（回退）：$fb -> /mnt/sdcard（fstype=$fs）"
    fi

    # /storage/emulated/0 -> /mnt/sdcard 的软链（契约要求，供 App 与用户习惯路径）
    mkdir -p "$ROOTFS_DIR/storage/emulated"
    ln -sfn /mnt/sdcard "$ROOTFS_DIR/storage/emulated/0" || log "WARN: 创建 /storage/emulated/0 软链失败"
    # 幂等：删掉可能已存在的真目录占位（目录会遮住软链）
    if [ -d "$ROOTFS_DIR/storage/emulated/0" ] && [ ! -L "$ROOTFS_DIR/storage/emulated/0" ]; then
        log "WARN: /storage/emulated/0 是目录而非软链，跳过（用户数据优先）"
    fi
    printf '%s' "$chosen" > "$RUN_DIR/sdcard.source" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# 生成 mounted.json —— 给 status 用的"层挂载态"快照
#   判定方式：解析宿主 /proc/self/mountinfo，看 /data/linux/rootfs 上的 overlay
#   挂载的 lowerdir 列表里是否出现该层文件，以及 upper.img 是否 loop 挂着。
# ---------------------------------------------------------------------------
write_mounted_json() {
    local mi="/proc/self/mountinfo"
    local base=false runtime=false dsh=false upper=false
    [ -f "$mi" ] || mi="/proc/mounts"

    # 用 `grep -c` 而不是 `grep -q`：grep -q 命中即退出会触发 SIGPIPE，
    # 在 pipefail 下可能被判失败（详见 doctor.sh 里同类问题的注释）。
    local mi_content
    mi_content="$(cat "$mi" 2>/dev/null || true)"
    case "$mi_content" in *"$LAYERS_MNT/base"*)    base=true ;; esac
    case "$mi_content" in *"$LAYERS_MNT/runtime"*) runtime=true ;; esac
    case "$mi_content" in *"$LAYERS_MNT/dsh"*)     dsh=true ;; esac
    case "$mi_content" in *"$LH/upper.img"*)       upper=true ;; esac

    cat > "$MOUNTED_JSON" <<EOF
{"schema":1,"ts":$(date +%s),"ns_pid":${1:-null},
 "layers":{"base":$base,"runtime":$runtime,"dsh":$dsh},
 "upper":$upper}
EOF
    chmod 0644 "$MOUNTED_JSON" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# 读 etc/config.json 的 port（无 jq 依赖）
#   期望格式（device-provision.sh 写出）：
#   { "schema":1, "port":3080, "mode":"root", "autostart":true, ... }
# ---------------------------------------------------------------------------
config_port() {
    local f="$ETC_DIR/config.json" p=""
    if [ -f "$f" ]; then
        p="$(tr -d ' \n\t' < "$f" | sed -n 's/.*"port":\([0-9][0-9]*\).*/\1/p' | head -n1)"
    fi
    case "$p" in ''|*[!0-9]*) p=3080 ;; esac
    if [ "$p" -lt 1 ] || [ "$p" -gt 65535 ]; then p=3080; fi
    printf '%s' "$p"
}

# ---------------------------------------------------------------------------
# 收尾：等待环境退出，记录退出码
# ---------------------------------------------------------------------------
finish_environment() {
    local rc="$1"
    log "环境进程退出（rc=$rc）"
    if [ "$rc" -ne 0 ] && [ ! -s "$ERROR_FILE" ]; then
        printf '环境进程非正常退出（rc=%s），详见 %s\n' "$rc" "$RUN_DIR/linux.log" > "$ERROR_FILE"
    fi
    # 只清"就绪"标记，不清挂载与 supervisor.pid —— 后续 stop/start 需要它们做锚点
    rm -f "$READY_FILE" "$DSH_PID_FILE" "$RUN_DIR/dsh.url" "$RUN_DIR/dsh.port" 2>/dev/null || true
}

# ===========================================================================
# 主流程
# ===========================================================================

main() {
    mkdir -p "$RUN_DIR" "$LAYERS_DIR" "$UPPER_DIR" "$WORK_DIR" "$ROOTFS_DIR"
    touch "$RUN_DIR/linux.log" 2>/dev/null || true

    # ---- --probe-only：只做能力探测并打印结果（doctor / 排障用）------------
    if [ "$MODE" = "probe_only" ]; then
        run_probes 1
        printf 'cmdprobe 文件：%s\n' "$PROBE_FILE"
        cat "$PROBE_FILE" 2>/dev/null
        exit 0
    fi

    # ---- --check-ready：只等就绪，给 linuxctl 用 ----------------------------
    if [ "$MODE" = "check_ready" ]; then
        if wait_ready "${READY_WAIT:-30}"; then
            printf '{"ready":true,"ns_pid":%s}\n' "$(running_ns_pid)"
            exit 0
        fi
        printf '{"ready":false,"last_error":%s}\n' \
            "$(printf '%s' "$(cat "$ERROR_FILE" 2>/dev/null || echo '')" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/')"
        exit 1
    fi

    # ---- --inner：已在正确 ns 中，直接建树并运行 ----------------------------
    if [ "$MODE" = "inner" ]; then
        exec >>"$DAEMON_LOG" 2>&1
        trap cleanup_on_fail EXIT
        log "=== inner 启动（pid=$$，LINUX_HOME=$LH）==="
        # 如果外层 unshare 不支持 --propagation，就在这里把整个 ns 设为私有
        #   （--make-rprivate /：一条命令覆盖 ns 内所有挂载点）
        if [ "${MAKE_PRIVATE:-0}" = "1" ]; then
            if "$MOUNT" --make-rprivate / 2>>"$DAEMON_LOG"; then
                log "已把 mount ns 根设为 rprivate"
            elif "$MOUNT" -o make-rprivate / 2>>"$DAEMON_LOG"; then
                log "已把 mount ns 根设为 rprivate（-o 形式）"
            else
                warn_soft "make-rprivate 失败（私有 ns 已由 unshare 保证，可继续）"
            fi
        fi
        build_mount_tree

        # UTS：本 ns 内改主机名；不改宿主（findings §6）
        if have hostname; then hostname dshroid 2>/dev/null || log "WARN: hostname dshroid 失败（非致命）"; fi

        write_mounted_json "$$"
        printf '%s\n' "$$" > "$SUPERVISOR_PID_FILE"
        rm -f "$ERROR_FILE"
        : > "$READY_FILE"
        log "挂载树就绪，交给 entry.sh"
        local rc=0
        chroot "$ROOTFS_DIR" /opt/dshroid/entry.sh "$(config_port)" || rc=$?
        finish_environment "$rc"
        trap - EXIT
        exit "$rc"
    fi

    # ---- 默认：幂等启动 ----------------------------------------------------
    local ns_pid=""
    if ns_pid="$(running_ns_pid)"; then
        if [ -f "$READY_FILE" ]; then
            log "环境已在运行（ns_pid=$ns_pid），无需重复启动"
            exit 0
        fi
        # 守护进程活着但 ready 被清掉：可能正处于 stopping，等一下看看
        log "守护进程 $ns_pid 存活但未就绪，等待 5s"
        if wait_ready 5; then
            log "已就绪（ns_pid=$ns_pid）"
            exit 0
        fi
        log "仍未就绪，按残留处理：先停掉旧守护进程"
        kill -TERM "$ns_pid" 2>/dev/null || true
        sleep 1
        kill -KILL "$ns_pid" 2>/dev/null || true
        rm -f "$SUPERVISOR_PID_FILE" "$READY_FILE"
    fi

    # 前置检查放在起守护进程之前，失败能立刻给 App 可读原因
    local l
    for l in "${LAYER_NAMES[@]}"; do
        find_layer "$l" >/dev/null 2>&1 || die "缺少层文件 $LAYERS_DIR/$l.{erofs,squashfs}（先跑 linuxctl provision）"
    done
    [ -f "$LH/upper.img" ] || die "缺少可写层镜像 $LH/upper.img（先跑 linuxctl provision）"
    # 端口占用提前查（doctor 也会查；这里失败要 fail fast）
    local port; port="$(config_port)"
    if port_busy "$port"; then
        log "WARN: 端口 $port 已被占用；dsh 可能自行换端口，实际端口以 run/dsh.port 为准"
    fi

    # 启动方式：setsid + unshare(-m -u) --fork，双 fork 脱离终端与生命周期
    local launcher=()
    if [ -x "$SETSID" ] && [ "$FOREGROUND" = "0" ]; then
        launcher=( "$SETSID" )
    fi

    # 启动方式：setsid + unshare(-m -u) + 守护子进程。
    # 选项语法按探测结果选择（toybox 可能不认 --propagation / --fork）。
    run_probes 0
    local prop
    prop="$(probe_get unshare_propagation no)"
    log "启动守护进程：unshare -m -u（propagation=$prop）$SELF_DIR/start.sh --inner"

    local uc_ok=0
    if [ "$prop" = "yes" ]; then
        # 首选：显式要求私有传播（一步到位，最干净）
        if "${launcher[@]}" "$UNSHARE" -m -u --propagation private --fork \
                "$SELF_DIR/start.sh" --inner >>"$DAEMON_LOG" 2>&1; then
            uc_ok=1
        fi
    fi
    if [ "$uc_ok" != "1" ] && [ "$prop" = "yes" ]; then
        # --propagation 认了但 --fork 不认 → 去掉 --fork（用 setsid 兜底脱离）
        log "提示：unshare --fork 可能不支持，改用不带 --fork 的形式"
        if "${launcher[@]}" "$UNSHARE" -m -u --propagation private \
                "$SELF_DIR/start.sh" --inner >>"$DAEMON_LOG" 2>&1; then
            uc_ok=1
        fi
    fi
    if [ "$uc_ok" != "1" ]; then
        # 退化：只 unshare -m -u，然后在**内层**自己把传播设为 private
        log "提示：unshare 不支持 --propagation，改为内层 make-rprivate"
        if "${launcher[@]}" "$UNSHARE" -m -u "$SELF_DIR/start.sh" --inner --make-private >>"$DAEMON_LOG" 2>&1; then
            uc_ok=1
        fi
    fi
    [ "$uc_ok" = "1" ] || log "WARN: unshare 发起进程退出码非 0（继续等 ready 判定，真正结果以 ready 为准）"

    if wait_ready "${READY_WAIT:-45}"; then
        ns_pid="$(running_ns_pid)"
        log "环境启动成功（ns_pid=$ns_pid）"
        exit 0
    fi

    local reason="启动超时或被中断，未出现 $READY_FILE"
    [ -s "$ERROR_FILE" ] && reason="$(cat "$ERROR_FILE")"
    printf '%s\n' "$reason" > "$ERROR_FILE"
    log "ERROR: $reason"
    exit 1
}

# 端口占用的最小判定：用 bash /dev/tcp（不需要 ss/netstat，Android 上也可能没有）
port_busy() {
    local p="$1"
    (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null && { exec 3>&- 2>/dev/null; return 0; }
    return 1
}

main "$@"
