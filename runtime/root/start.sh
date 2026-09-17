#!/system/bin/sh
# =============================================================================
# sunsetlinux · runtime/root/start.sh
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
SELF_PATH="$0"   # mksh 下 BASH_SOURCE 未定义 → 退回 $0
SELF_DIR="$(cd -- "$(dirname -- "$SELF_PATH")" && pwd -P)"
LINUX_HOME="${LINUX_HOME:-/data/sunsetlinux}"
LH="$LINUX_HOME"

RUN_DIR="$LH/run"
ETC_DIR="$LH/etc"
STATE_JSON="$ETC_DIR/state.json"
CONFIG_JSON="$ETC_DIR/config.json"   # resolve_layer_mode 要读它（此前只定义了 STATE_JSON）
LAYERS_DIR="$LH/layers"
LAYERS_MNT="$LH/layers-mnt"
UPPER_DIR="$LH/upper"
UPPER_IMG="$LH/upper.img"

# ── 层模式（loop / dir）────────────────────────────────────────────────────
#   loop（默认）：losetup + erofs 挂载 + upper.img(ext4) + overlayfs —— 省磁盘，但要用 loop 设备
#   dir（兼容开关）：把层**解包成目录** + 目录 overlay —— 完全不碰 loop、不碰 erofs 挂载、
#                     不需要 upper.img；代价是磁盘（解包后约 1.6 GB）与首次解包时间。
#   为什么要有它：真机上 loop/erofs 这条链最容易出问题（toybox 选项、loop 数量、挂载权限），
#   而 overlayfs 对目录是内核确认支持的最基本用法（CONFIG_OVERLAY_FS=y）。
LAYER_MODE=""
DIRS_DIR="$LH/dirs"            # 解出来的只读层
DIRS_UPPER="$LH/dirs-upper"    # 可写层（真目录，替代 upper.img）
DIRS_WORK="$LH/dirs-work"      # overlayfs 的 workdir
EROFS_EXTRACT="${SUNSETLINUX_EROFS_EXTRACT:-/system/bin/fsck.erofs}"
WORK_DIR="$LH/work"
ROOTFS_DIR="$LH/rootfs"

SUPERVISOR_PID_FILE="$RUN_DIR/supervisor.pid"
DSH_PID_FILE="$RUN_DIR/dsh.pid"
READY_FILE="$RUN_DIR/ready"
MOUNTS_FILE="$RUN_DIR/mounts"
MOUNTED_JSON="$RUN_DIR/mounted.json"
ERROR_FILE="$RUN_DIR/last-error"
DAEMON_LOG="$RUN_DIR/start.log"
# 「正在启动」的互斥锁（内容 `<pid> <epoch>`，`set -C` 原子写）—— 覆盖"建树中"这段窗口，
# 详见 start_lock_acquire 的注释（真机 2026-09-18 03:39 那两棵叠起来的挂载树）。
START_LOCK="$RUN_DIR/start.lock"


# 层格式：空 = 自动探测；也可用 LINUX_LAYER_EXT=.squashfs 强制
LINUX_LAYER_EXT="${LINUX_LAYER_EXT:-}"
# 层顺序：POSIX 空格分隔字符串（本脚本在设备侧由 mksh 执行，不能用 bash 数组）
LAYER_NAMES="base runtime dsh"

# 常用工具（本脚本在宿主 Android 侧运行，走 toybox；不要假定有 GNU 专属选项）
UMOUNT=/system/bin/umount
MOUNT=/system/bin/mount
NSENTER=/system/bin/nsenter
SETSID=/system/bin/setsid
UNSHARE=/system/bin/unshare

MODE="start"
FOREGROUND=0
MAKE_PRIVATE=0
# ★ --no-dsh：「只起环境，不启动 DSH」（App 的「仅启动环境」）。
#   为什么用环境变量兜底：内层是**重新执行本脚本**（`start.sh --inner …`），命令行参数不会
#   自动带过去 —— 层模式早就用了同一招（export SUNSETLINUX_LAYER_MODE）。
NO_DSH="${SUNSETLINUX_NO_DSH:-0}"
# ★ 用 while + shift 解析，而不是 `for arg in "$@"`：后者在循环里 shift 不了下一个参数，
#   `--layer-mode dir` 会被当成两个参数、报"未知参数 dir"（本地自测时踩到）。
while [ $# -gt 0 ]; do
    arg="$1"
    case "$arg" in
        --inner)      MODE=inner; shift ;;
        --make-private) MODE=inner; MAKE_PRIVATE=1; shift ;;
        --foreground) FOREGROUND=1; shift ;;
        --check-ready) MODE=check_ready; shift ;;
        --probe-only)  MODE=probe_only; shift ;;
        --no-dsh)      NO_DSH=1; shift ;;
        --layer-mode)  LAYER_MODE_FLAG="${2:-}"; shift 2 ;;
        --layer-mode=*) LAYER_MODE_FLAG="${arg#--layer-mode=}"; shift ;;
        *) echo "start.sh: 未知参数 $arg" >&2; exit 2 ;;
    esac
done
SUNSETLINUX_NO_DSH="$NO_DSH"
export SUNSETLINUX_NO_DSH

# --- 层模式：**尽早**解析（拼错要立刻报，而不是等到挂载阶段才发现）--------
#   注意这里 log/die 还没定义，所以用一个最小的本地报错。
case "${LAYER_MODE_FLAG:-}" in
    ""|loop|dir) ;;
    *) echo "start.sh: 未知的层模式：$LAYER_MODE_FLAG（只支持 loop / dir）" >&2; exit 2 ;;
esac

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
# 用三个 POSIX 变量代替原来的数组（本脚本在设备侧由 mksh 执行）：
#   UTIL_LD        base 层里的动态加载器
#   UTIL_LIBPATH   base 层的库搜索路径
#   UTIL_MNT_BIN   base 层里的 mount
# 三者都非空才算可用；执行统一走 util_mount_run()。
UTIL_LD=""
UTIL_LIBPATH=""
UTIL_MNT_BIN=""

util_mount_run() {  # util_mount_run <mount 的参数...>
    "$UTIL_LD" --library-path "$UTIL_LIBPATH" "$UTIL_MNT_BIN" "$@"
}

# 脱离控制终端地起守护进程：use=1 用 setsid 包一层，use=0 直接起。
# 这是原来 `launcher` 数组的 POSIX 等价物（本脚本在设备侧由 mksh 执行，不能用数组）。
spawn_detached() {  # spawn_detached <use_setsid> <cmd> [args...]
    local use="$1"; shift
    if [ "$use" = "1" ]; then
        "$SETSID" "$@"
    else
        "$@"
    fi
}

# 起守护进程：**后台子 shell + `</dev/null`**，父脚本立刻返回、绝不等它。
#
# ### 为什么必须后台（真机事故 · 2026-09-18 · 用户截图「点了启动环境，然后就没了，
# ### 点不了启动 DSH」）
#
# 以前调用点是 `if spawn_detached "$use_setsid" …; then uc_ok=1; fi` —— **前台**执行，
# 父 shell 必须等这条命令结束。而守护链的最后一段 `entry.sh` 是**按设计永不退出**的
# （`--no-dsh` 是 `while :; do sleep 1; done`，full 模式是 `exec supervise.sh` 长住），
# 于是整条链一起挂住：
#
#     start.sh（本脚本）不退 → `linuxctl start` 不退 → App 的
#     `su -c '… linuxctl start --no-dsh'` 不退 → App 的 `busy` 一直是 true
#     ⇒ 启动区五张卡片全置灰；「启动 DSH」点下去被 `if (_ui.value.busy) return` 丢弃。
#
# 设备实证（修复前现场）：那条链
#     `sh -c(3172) → linuxctl(3175) → start.sh(3224) → start.sh --inner(3455) → entry.sh(4624)`
# **20 分钟后仍全部停在 `rt_sigsuspend`**（= 在等子进程），而环境本身 4 秒时就已就绪
# （`run/ready` 已写、`supervisor.pid` 已写）——"环境好了、按钮却全灰"就是这么来的。
#
# `( … & )` 让守护进程被 init 收养：父脚本不等它，App 的 su 会话结束也不影响环境
# （"与 App 生命周期解耦"本来就是设计目标）。`</dev/null` 断开继承来的 stdin ——
# 真机上那条 fd0 就是 App 的 su 管道，不还回去等于占着 App 的通道。
spawn_daemon() {  # spawn_daemon <use_setsid> <cmd> [args...]
    ( spawn_detached "$@" >>"$DAEMON_LOG" 2>&1 </dev/null & )
}

probe_get() {  # probe_get <key> [default]
    local v=""
    # `|| true`：读探测文件失败不能让整个 start.sh 退出（set -e + pipefail 下很容易踩）
    [ -f "$PROBE_FILE" ] && { v="$(sed -n "s/^$1=//p" "$PROBE_FILE" 2>/dev/null | head -n1)" || true; }
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

# toybox 说"这个参数我不认识"时到底怎么打印（**真机实测，2026-09-16/17**）：
#     unshare: Unknown option 'propagation' (see "unshare --help")
#     mount:   bad /etc/fstab: No such file or directory    ← 不认的参数被当成 fstab 挂载
# 早期判定只认 usage / bad option / **小写** unknown option / invalid，于是真机上：
#   · `Unknown option`（大写 U）不匹配 → unshare_propagation=yes（谎报）
#   · `bad /etc/fstab` 不匹配         → mount_make_rslave=long（谎报）
# 结果 start.sh 每次启动都先按"支持"去调 --propagation / --make-rprivate，注定失败后才回退：
# 探测白做、日志里的两行 `bad /etc/fstab` 就是这么来的，而那句
# "私有 ns 已由 unshare 保证"在 `/` 是 shared 的机器上**并不成立**（会回流宿主 ns）。
probe_says_unsupported() {   # probe_says_unsupported "<命令输出>"
    case "$1" in
        *[Uu]sage*)          return 0 ;;
        *[Bb]ad*option*)     return 0 ;;
        *[Uu]nknown*option*) return 0 ;;
        *[Uu]nrecognized*)   return 0 ;;
        *bad*fstab*)         return 0 ;;
        *[Nn]ot*found*)      return 0 ;;
    esac
    return 1
}

# 探测 mount 的绑定挂载语法。**无害**：全是必然失败的挂载（源路径不存在），
# 我们只看"命令是否把参数判成语法错误"，不看挂载成功与否。
# 判定：输出里出现 toybox 的"我没听懂"就算该语法不被支持 —— 见 probe_says_unsupported。
probe_mount_syntax() {
    local m="$1" tmp out rc
    tmp="$RUN_DIR/.probe-$$"
    mkdir -p "$tmp" 2>/dev/null || tmp=/tmp
    rc=0
    out="$("$m" --rbind /nonexistent-sunsetlinux-probe "$tmp" 2>&1)" || rc=$?
    if probe_says_unsupported "$out"; then
        probe_set mount_rbind short
    else
        probe_set mount_rbind long
    fi
    out="$("$m" -o rbind /nonexistent-sunsetlinux-probe "$tmp" 2>&1)" || true
    if probe_says_unsupported "$out"; then
        probe_set mount_rbind_o none
    else
        probe_set mount_rbind_o short
    fi
    out="$("$m" --make-rslave "$tmp" 2>&1)" || true
    if probe_says_unsupported "$out"; then
        probe_set mount_make_rslave short
    else
        probe_set mount_make_rslave long
    fi
    out="$("$m" -o remount,bind "$tmp" 2>&1)" || true
    if probe_says_unsupported "$out"; then
        probe_set mount_remount_bind none
    else
        probe_set mount_remount_bind ok
    fi
    rmdir "$tmp" 2>/dev/null || true
}

# 探测 unshare 是否支持 --propagation（toybox 很可能是最近才加的）
probe_unshare_syntax() {
    local u="$1" out
    out="$("$u" -m --propagation private true 2>&1)" || true
    if probe_says_unsupported "$out"; then
        probe_set unshare_propagation no
    else
        probe_set unshare_propagation yes
    fi
}

# 关键挂载（proc/sys/dev）是否有一条能用的 util-linux 回退路径
#   思路：base 层里的 util-linux 是**宿主 glibc 编译**的，不能直接当 Android 原生二进制跑；
#   但可以用它自己的动态加载器执行：
#     /…/layers-mnt/base/lib/ld-linux-aarch64.so.1 --library-path <该层 lib 目录> <该层 mount> …
#   base 层只需要**已经 loop 挂上**（在挂载树里它是最早挂的那批之一），所以这条回退可行。
detect_util_mount() { # detect_util_mount [base_dir]（默认 loop 模式下的 $LAYERS_MNT/base）
    local base="${1:-$LAYERS_MNT/base}" ld="" m=""
    local cand
    for cand in "$base/lib/ld-linux-aarch64.so.1" "$base/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"; do
        [ -x "$cand" ] && { ld="$cand"; break; }
    done
    for cand in "$base/usr/bin/mount" "$base/bin/mount"; do
        [ -x "$cand" ] && { m="$cand"; break; }
    done
    if [ -n "$ld" ] && [ -n "$m" ]; then
        UTIL_LD="$ld"
        UTIL_MNT_BIN="$m"
        UTIL_LIBPATH="$base/lib:$base/usr/lib:$base/lib/aarch64-linux-gnu:$base/usr/lib/aarch64-linux-gnu"
        probe_set util_mount yes
    else
        UTIL_LD=""; UTIL_MNT_BIN=""; UTIL_LIBPATH=""
        probe_set util_mount no
    fi
}

# 探测 loop 能不能**读**我们目录里的文件。为什么必须实测（真机实测 2026-09-17，6.1.141-android14）：
#   loop 的 I/O 不在我们进程里做，而在 kworker（u:r:kernel:s0）里做，SELinux 用 current_sid()
#   判权限。实测结论：
#     · 厂商只读 loop（backing = /system_ext 的 system_file）读得到；
#     · 我们的文件（system_data_file / shell_data_file）**连读都被拒** → 块层统一报 I/O error；
#     · chcon 成 system_file 后读通了、写仍被拒（内核原文仍有 `loop: Write error`）
#       ⇒ 没有"改标签就能当可写层"的捷径，真要用 loop 只能补 kernel 域的 sepolicy。
#   所以：读不通 ⇒ 本机 loop 这条链没戏（连只读层都挂不上），别再走
#   "清残留 loop → e2fsck -p → 显式 losetup" 那套仪式（那三步救不了"域没权限"）。
#   读得通**不代表**能当可写层（写权限另算）—— 那一步由 mount_upper_rw 实测。
probe_loop_io() {
    local f="$RUN_DIR/.loopprobe.img" dev=""
    have losetup || { probe_set loop_read none; return 0; }
    if ! dd if=/dev/zero of="$f" bs=4096 count=64 2>/dev/null; then
        probe_set loop_read no; rm -f "$f"; return 0
    fi
    dev="$(losetup -f --show "$f" 2>/dev/null || true)"
    if [ -z "$dev" ]; then
        probe_set loop_read no; rm -f "$f"; return 0
    fi
    # 只测**读**：读是同步的，结果可信；写会进页缓存，"dd 成功"不等于写到了文件
    #（真机上就是 `Buffer I/O error … lost async page write` 这种异步失败）。
    if dd if="$dev" of=/dev/null bs=4096 count=1 2>/dev/null; then
        probe_set loop_read yes
    else
        probe_set loop_read no
    fi
    losetup -d "$dev" 2>/dev/null || true
    rm -f "$f"
    return 0
}

# 探测"我们能不能挂 fuse"（用户态 union 路线的前提；/dev/fuse 在不在是另一回事）。
# 用一个**故意无效的 fd** 去挂：读回 "Permission denied" = 策略不让（SELinux 的 mount 判定），
# 其他错误（Bad file descriptor / Invalid argument / No such device）= mount 这条被允许，
# 缺的只是真正的 fuse 守护进程。
# FUSE_DEV 是给自测用的接缝（同 SUNSETLINUX_EROFS_EXTRACT 的用法）。
probe_fuse_mount() {
    local mnt="$RUN_DIR/.fuseprobe" out="" rc=0
    FUSE_DEV="${SUNSETLINUX_FUSE_DEV:-/dev/fuse}"
    [ -c "$FUSE_DEV" ] || { probe_set fuse_mount no_dev; return 0; }
    mkdir -p "$mnt" 2>/dev/null || { probe_set fuse_mount unknown; return 0; }
    out="$("$MOUNT" -t fuse -o fd=9999,rootmode=040000,user_id=0,group_id=0 none "$mnt" 2>&1)" || rc=$?
    rmdir "$mnt" 2>/dev/null || true
    case "$out" in
        *[Pp]ermission*|*[Dd]enied*) probe_set fuse_mount denied ;;
        *) probe_set fuse_mount ok ;;
    esac
    return 0
}

# 跑一次全部探测（幂等：已有结果且不是 --force 就跳过）
# PROBE_VERSION：**探测项变了就要 +1**，否则老设备上 cmdprobe 里没有新键，
# probe_get 只能拿到默认值（新增的 loop_read/fuse_mount 就永远不生效）。
PROBE_VERSION=2
run_probes() {
    local force="${1:-0}"
    if [ "$force" != "1" ] && [ -s "$PROBE_FILE" ] && grep -q '^probed=' "$PROBE_FILE" 2>/dev/null \
       && [ "$(sed -n 's/^probe_version=//p' "$PROBE_FILE" 2>/dev/null | head -n1)" = "$PROBE_VERSION" ]; then
        return 0
    fi
    : > "$PROBE_FILE" 2>/dev/null || true
    log "开始探测 toybox 的 mount/unshare 选项支持（结果写入 $PROBE_FILE）"
    # ★ 这一整段必须关掉 `set -e`：探测命令**本来就是设计成失败的**
    #   （拿不存在的源去 mount，只看它是不是把参数判成语法错误）。
    #   真机事故（2026-09-16）：`out="$(mount --rbind …)"; rc=$?` 在 `set -e` 下，
    #   那次"预期失败"直接让 start.sh 在探测第一行退出 —— cmdprobe 是 0 字节、
    #   linux.log 停在"开始探测…"，用户侧看到的就是「start.sh 失败 / 未挂载」，
    #   而真正的挂载步骤一行都没跑到。
    set +e
    if [ -x "$MOUNT" ]; then probe_mount_syntax "$MOUNT"; fi
    if [ -x "$UNSHARE" ]; then probe_unshare_syntax "$UNSHARE"; fi
    if find_layer base >/dev/null 2>&1; then detect_util_mount; fi
    probe_loop_io
    probe_fuse_mount
    printf 'probe_version=%s\n' "$PROBE_VERSION" >> "$PROBE_FILE" 2>/dev/null || true
    printf 'probed=%s\n' "$(date +%s)" >> "$PROBE_FILE" 2>/dev/null || true
    set -e
    log "探测结果：rbind=$(probe_get mount_rbind) | rbind_o=$(probe_get mount_rbind_o) | make_rslave=$(probe_get mount_make_rslave) | unshare_propagation=$(probe_get unshare_propagation) | util_mount=$(probe_get util_mount) | loop_read=$(probe_get loop_read unknown) | fuse_mount=$(probe_get fuse_mount unknown)"
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
# 启动锁：挡住"第二次 start 又建一棵挂载树"
#
# ### 真机事故（2026-09-18 03:39，用户问「脚本似乎在尝试重复挂载？」）
#
# `running_ns_pid` 只看**已就绪**（`supervisor.pid` + `ready`），而建树要 **30~50 秒**
# （loop + erofs + overlay + chroot）。这段窗口里它返假，`linuxctl status` 也就报 `stopped`,
# 于是第二次 `start`（用户在界面上再点一次）会**各建一棵挂载树**：
#
#     03:39:16  start --no-dsh → inner pid=31547 开始建树（到 03:40:08 才 ready）
#     03:39:36  又一次 start --no-dsh → inner pid=4672（此时 31547 还没写 supervisor.pid/ready）
#     ⇒ run/mounts 13 → 26 条，两条守护链同时活着，最后 dsh start 报"进不去环境"
#
# 判据只有两条：**锁文件在 + 里面的 pid 还活着**。写法用 `set -C`（noclobber）保证
# "创建即占有"的原子性（mksh 支持；比 mkdir 锁少一个目录，且能顺手记下 pid 与时间）。
# 锁的生命周期：start.sh 开工前占有 → inner 写完 `supervisor.pid`+`ready` 时释放
# （脚本 EXIT trap 也释放一次）→ stop.sh 也清。持有者死掉留下的陈旧锁会被下一次 start 接管。
# ---------------------------------------------------------------------------
start_lock_acquire() {   # 0 = 本次占有；1 = 已有人持有
    if ( set -C; printf '%s %s\n' "$$" "$(date +%s)" > "$START_LOCK" ) 2>/dev/null; then
        return 0
    fi
    return 1
}

start_lock_holder() {    # 打印持锁且仍活着的 pid；否则返回 1
    local pid=""
    [ -f "$START_LOCK" ] || return 1
    pid="$(head -n1 "$START_LOCK" 2>/dev/null | awk '{print $1}' | tr -dc '0-9' || true)"
    [ -n "$pid" ] || return 1
    [ -d "/proc/$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    printf '%s' "$pid"
}

start_lock_release() {
    rm -f "$START_LOCK" 2>/dev/null || true
}

# 锁的持有者要**跟着活着的那个进程走**：
#   外层 start.sh 只在 `wait_ready`（默认 45s）之内有意义，而建树实测可以到 50s
#   （03:39 那次就是）。若外层超时退出时把锁一起放掉，第二次 start 又会各建一棵树 ——
#   正是我们要修的那个竞态在更慢的机器上重演。所以：
#     · 守护进程（inner）一开工就把锁改写成自己的 pid（它活到环境结束）；
#     · 外层退出时**只释放属于自己 pid 的锁**，绝不碰别人的。
start_lock_rewrite_self() {   # 由 inner 调用
    printf '%s %s\n' "$$" "$(date +%s)" > "$START_LOCK" 2>/dev/null || true
}

start_lock_release_own() {    # 只释放"我自己的"锁
    local pid=""
    [ -f "$START_LOCK" ] || return 0
    pid="$(head -n1 "$START_LOCK" 2>/dev/null | awk '{print $1}' | tr -dc '0-9' || true)"
    [ "$pid" = "$$" ] || return 0
    rm -f "$START_LOCK" 2>/dev/null || true
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
    local name="$1" file="$2" fmt="" fssup=""
    # ★ 必须**分成两条** local：mksh 与 bash 在同一条 `local` 里都取不到刚赋的变量
    #   （`local a="X" b="[$a]"` → mksh: `a: parameter not set` / bash: unbound variable）。
    #   真机事故（2026-09-17）：把 loop 写权限修好之后 start.sh **第一次真的走到这行**
    #   → `start.sh[1477]: name: parameter not set`，整脚本死在这里（此前 loop 永远挂不上，
    #   这行从未被执行过，所以一直没暴露）。
    local dst="$LAYERS_MNT/$name"
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

# 登记项 → 绝对路径。以 / 开头的按原样（可写层挂在 $LINUX_HOME/upper，**不在**
# $ROOTFS_DIR 下面），其余相对 $ROOTFS_DIR。
mount_path_of() {
    case "$1" in
        /*) printf '%s' "$1" ;;
        *)  printf '%s' "$ROOTFS_DIR/$1" ;;
    esac
}

is_mounted() {
    local p
    p="$(mount_path_of "$1")"
    [ -d "$p" ] || return 1
    mountpoint -q "$p" 2>/dev/null
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
        if [ -n "$UTIL_MNT_BIN" ] && \
           util_mount_run --rbind "$src" "$dst" 2>>"$DAEMON_LOG"; then
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
# 可写层 upper.img 的挂载：**自愈 + 可诊断**
#
# 真机实测的失败形态（2026-09-16，6.1.141-android14）：
#     mount: '/dev/block/loop49'->'/data/sunsetlinux/rootfs/upper': I/O error
# 而同一镜像 `-o loop,ro` 能挂、`e2fsck -fn` 也说"文件系统一致"。
# 原因：ext4 只有在**读写**挂载时才会写超级块 / 恢复日志，这条路径上的任何写失败
# 都被内核统一报成 EIO —— 光看 errno 什么都推不出来，必须看 dmesg。所以这里：
#   ① 先清掉指向我们镜像的**残留 loop**（上次启动失败留下的，真机上确实存在）
#   ② e2fsck -p（自动修复"上次没干净卸载"留下的 needs_recovery）
#   ③ 显式 `losetup` + `mount` 两步走（错误可归因，绕开 toybox 的 `-o loop` 合并路径）
#   ④ 每次都把 dmesg 里 loop/ext4/jbd2 的原文记进 $DAEMON_LOG，并压成一行给 last-error
# 全失败 → 返回 1，由 build_mount_tree 决定是否切 dir 模式（那条路不碰 loop）。
# ---------------------------------------------------------------------------
UPPER_ERR=""

# 抓内核里与本项目挂载相关的原文（mount 只回一句 'I/O error'，原因只在这里）。
kernel_hint() {
    have dmesg || { printf '%s' "（拿不到 dmesg）"; return 0; }
    local out=""
    # `|| true`：grep 无命中会返回 1，在 `set -o pipefail` 下会让命令替换整体失败，
    # 进而被 `set -e` 带走 —— 那会把"挂载失败"变成"脚本静默退出"（真机上踩过同类）。
    out="$(dmesg 2>/dev/null | grep -iE 'loop|ext4|jbd2|overlay' | tail -n 5 | tr '\n' '|' | sed 's/|*$//' || true)"
    printf '%s' "${out:-（dmesg 里没有 loop/ext4/jbd2/overlay 相关行）}"
}

kernel_hint_log() {
    have dmesg || return 0
    # ★ 模式必须与 kernel_hint 一致：**要包含 overlay**。
    #   真机事故（2026-09-16 23:53）：dir 模式的 `mount -t overlay` 回 EINVAL，
    #   而这里只 grep 'loop|ext4|jbd2'，于是内核那句 `overlayfs: …`（唯一能说明
    #   为什么 EINVAL 的原话）被过滤掉了 —— 事后只剩一句 "Invalid argument"，查不动。
    log "—— 内核原文（dmesg | grep -iE 'loop|ext4|jbd2|overlay' | tail -20）——"
    dmesg 2>/dev/null | grep -iE 'loop|ext4|jbd2|overlay' | tail -n 20 >> "$DAEMON_LOG" 2>/dev/null || true
}

# 清掉"backing 指向 $LH 下镜像"且**没有被任何进程挂载**的残留 loop。
# 为什么必须清：残留 loop 会让下一次 ext4 读写挂载失败（真机就在这条上卡了整天）。
# 为什么只清没挂载的：挂着的 loop 属于别人的活挂载，硬拔会打断它。
cleanup_stale_loops() {
    have losetup || return 0
    local devs dev mm hit mi
    devs="$(losetup -a 2>/dev/null \
            | awk -v h="$LH" '/^\/dev\// && index($0,h)>0 {sub(/:$/,"",$1); print $1}' || true)"
    [ -n "$devs" ] || return 0
    for dev in $devs; do
        mm="$(cat "/sys/block/$(basename "$dev")/dev" 2>/dev/null || true)"
        hit=""
        if [ -n "$mm" ]; then
            for mi in /proc/[0-9]*/mountinfo; do
                [ -r "$mi" ] || continue
                if grep -q " $mm " "$mi" 2>/dev/null; then hit="$mi"; break; fi
            done
        fi
        if [ -n "$hit" ]; then
            log "残留 loop $dev 仍被挂载（$hit），跳过 detach"
            continue
        fi
        if losetup -d "$dev" 2>>"$DAEMON_LOG"; then
            log "清掉残留 loop：$dev（上次启动失败留下的，不指向任何活挂载）"
        else
            log "WARN: 无法 detach 残留 loop $dev（继续尝试挂载）"
        fi
    done
    return 0
}

# e2fsck -p：修"没干净卸载"留下的 needs_recovery / 孤立 inode。
# 为什么敢自动做：这是只读 overlay 的**可写层**（用户数据在上层文件里，不动下层），
# 而 -p（preen）本身就是设计给"无人值守启动"的；修不了会退非 0，那时我们不做
# 更激进的 -fy（自动大改用户数据风险太高，交给用户跑 linuxctl reset）。
e2fsck_preen() {
    local fsck=""
    # 候选顺序里**第一位是覆盖点** `$SUNSETLINUX_E2FSCK`（与 SUNSETLINUX_EROFS_EXTRACT 同类）。
    # 为什么必须有它：真机上 /system/bin/e2fsck **一定存在**，而绝对路径排在 PATH 前面 ——
    # 自测放的桩永远轮不到，于是 selftest 里"失败后先清 loop 再 e2fsck -p"这条断言在设备上
    # **必然假红**（2026-09-17 实测：真 e2fsck 去跑 0 字节的 upper.img → rc=8 → 走 return 1）。
    # 留空时行为与以前完全一致（跳过空候选继续往下走）。
    for fsck in "${SUNSETLINUX_E2FSCK:-}" /system/bin/e2fsck "$ROOTFS_DIR/sbin/e2fsck" "$ROOTFS_DIR/usr/sbin/e2fsck"; do
        [ -x "$fsck" ] || continue
        log "e2fsck -p $UPPER_IMG（自动修日志/孤立 inode，工具：$fsck）"
        if "$fsck" -p "$UPPER_IMG" >>"$DAEMON_LOG" 2>&1; then
            log "e2fsck -p 通过"
            return 0
        fi
        log "WARN: e2fsck -p 未通过（详情见 $DAEMON_LOG 尾部）"
        return 1
    done
    # PATH 里的 e2fsck（`[ -x e2fsck ]` 判不出来：那是相对路径，永远不成立）
    if have e2fsck; then
        log "e2fsck -p $UPPER_IMG（自动修日志/孤立 inode，工具：PATH 里的 e2fsck）"
        if e2fsck -p "$UPPER_IMG" >>"$DAEMON_LOG" 2>&1; then
            log "e2fsck -p 通过"
            return 0
        fi
        log "WARN: e2fsck -p 未通过（详情见 $DAEMON_LOG 尾部）"
        return 1
    fi
    log "WARN: 没有 e2fsck，跳过可写层体检"
    return 1
}

# 挂 ext4 可写层。成功返回 0，并把 UPPER_ERR 留空；失败返回 1 且 UPPER_ERR 可读。
mount_upper_rw() {
    # ★ 挂到 $UPPER_DIR（= $LINUX_HOME/upper），**不是** $ROOTFS_DIR/upper。
    #   真机实测（2026-09-17 核对代码与文档）：两者曾不一致 ——
    #     · docs/architecture.md 第 90/243 行：`mount -t ext4 … upper.img upper/`，
    #       第 252 行：`upperdir=upper/upper`；
    #     · stop.sh（do_umount "$UPPER_DIR"）、linuxctl.sh（mountpoint -q "$UPPER_DIR"）也都在 $LH/upper 上找它；
    #     · 而这里原来挂到 $ROOTFS_DIR/upper，于是 overlay 的 upperdir（$UPPER_DIR/upper）
    #       根本看不到这块 ext4：可写层实际落在 f2fs 的普通目录上，8 GiB 的 upper.img 白挂，
    #       而且它还被随后挂在 $ROOTFS_DIR 的 overlay **遮住**（成了不可达的死挂载）。
    local dst="$UPPER_DIR" dev=""
    UPPER_ERR=""
    [ -f "$UPPER_IMG" ] || { UPPER_ERR="缺少可写层镜像 $UPPER_IMG"; return 1; }
    mkdir -p "$dst" || { UPPER_ERR="无法创建挂载点 $dst"; return 1; }

    cleanup_stale_loops

    # ① 与历史行为一致的第一步（toybox 的 `-o loop` 自己分配 loop 设备）
    log "mount upper <- $UPPER_IMG -t ext4 -o loop,rw,noatime -> $dst"
    if "$MOUNT" -t ext4 -o loop,rw,noatime "$UPPER_IMG" "$dst" 2>>"$DAEMON_LOG"; then
        record_mount "$dst"
        return 0
    fi
    UPPER_ERR="$(kernel_hint)"
    warn_soft "第一次挂载 upper 失败：$UPPER_ERR"

    # ② 清残留后再试一次（清完可能就好了：真机上就是残留 loop 加没干净卸载）
    e2fsck_preen || true

    # ③ 显式 losetup + mount：两步走，错误能归因到具体哪一步
    dev="$(losetup -f --show "$UPPER_IMG" 2>>"$DAEMON_LOG" || true)"
    if [ -z "$dev" ]; then
        warn_soft "losetup -f --show 失败（没有空闲 loop 设备？）"
    else
        log "显式 losetup：$dev <- $UPPER_IMG"
        if "$MOUNT" -t ext4 -o rw,noatime "$dev" "$dst" 2>>"$DAEMON_LOG"; then
            record_mount "$dst"
            log "显式 losetup 路径挂载成功（记下：本机 toybox 的 -o loop 不可靠，已自动兜住）"
            return 0
        fi
        UPPER_ERR="$(kernel_hint)"
        warn_soft "显式 losetup 后仍挂不上：$UPPER_ERR"
        # 把这次尝试留下的 loop 拔掉，别给下一次留垃圾
        "$UMOUNT" -l "$dst" 2>/dev/null || true
        losetup -d "$dev" 2>>"$DAEMON_LOG" || true
    fi

    kernel_hint_log
    return 1
}

# ---------------------------------------------------------------------------
# 这个文件系统能不能给 overlayfs 当层？——**经验性预检**（真机实测 2026-09-17）
#
# 为什么要它：overlayfs 在挂载时就会筛文件系统。fs/overlayfs/util.c 的
#   bool ovl_dentry_weird(struct dentry *d) {
#       return d->d_flags & (DCACHE_NEED_AUTOMOUNT | DCACHE_MANAGE_TRANSIT |
#                            DCACHE_OP_HASH | DCACHE_OP_COMPARE);
#   }
# 一旦命中就直接 `pr_err("filesystem on '%s' not supported")` + **EINVAL**。
# 本机（OnePlus 6.1.141-android14，/data 是 f2fs）三个路径上都被拒：
#   overlayfs: filesystem on '/data/sunsetlinux/dirs-upper' not supported
#   overlayfs: filesystem on '/data/local/tmp/sl-diag/lo/lower' not supported   ← 只读 lowerdir 也不行
# 也就是说 **dir 模式在这台机上永远起不来**，而它要先解包 1.6 GB / 几分钟才失败。
# 所以：拿同文件系统上的两个空目录做一次**只读**试挂，挂不上就立刻带原因退出。
# 注意不能用"fs 名字"硬编码判断（有的 f2fs 能用、有的不能，取决于 casefold 等特性），
# 只能这样实测 —— 探测本身就是无害的（不写任何内容，只建空目录，挂完立刻卸）。
# ---------------------------------------------------------------------------
overlay_fs_usable() {  # overlay_fs_usable <该文件系统上的一个目录>
    local base="$1" a b m rc=1
    [ -d "$base" ] || return 1
    a="$base/.ovlprobe-a"; b="$base/.ovlprobe-b"; m="$base/.ovlprobe-mnt"
    mkdir -p "$a" "$b" "$m" 2>/dev/null || return 1
    if "$MOUNT" -t overlay overlay -o "lowerdir=$a:$b" "$m" 2>>"$DAEMON_LOG"; then
        rc=0
        "$UMOUNT" -l "$m" 2>/dev/null || "$UMOUNT" "$m" 2>/dev/null || true
    fi
    rmdir "$m" "$a" "$b" 2>/dev/null || true
    return "$rc"
}

# ---------------------------------------------------------------------------
# 失败回滚：反序卸载已登记的挂载点。任何一步失败都走这里。
# ---------------------------------------------------------------------------
unmount_recorded() {
    local rel
    [ -f "$MOUNTS_FILE" ] || return 0
    # 反序卸载：tac 在 toybox 里不一定有，C 式 for + 数组又不保证 mksh 能跑，
    # 所以每读一行就**前插**进字符串，读完自然就是倒序。
    local rev=""
    while IFS= read -r rel; do
        [ -n "$rel" ] && rev="$rel
$rev"
    done < "$MOUNTS_FILE"
    while IFS= read -r rel; do
        [ -n "$rel" ] || continue
        if is_mounted "$rel"; then
            log "回滚：umount $rel"
            "$UMOUNT" -l "$(mount_path_of "$rel")" 2>/dev/null || \
                "$UMOUNT" -f "$(mount_path_of "$rel")" 2>/dev/null || \
                log "WARN: umount $rel 失败（可能需要手动清理）"
        fi
    done <<EOF
$rev
EOF
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
# ---------------------------------------------------------------------------
# 层模式：命令行 --layer-mode > 环境变量 SUNSETLINUX_LAYER_MODE > config.json > loop
# ---------------------------------------------------------------------------
resolve_layer_mode() {
    local m="${LAYER_MODE_FLAG:-}"
    [ -n "$m" ] || m="${SUNSETLINUX_LAYER_MODE:-}"
    if [ -z "$m" ] && [ -f "$CONFIG_JSON" ]; then
        m="$(sed -n 's/.*"layer_mode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$CONFIG_JSON" 2>/dev/null | head -n1)"
    fi
    [ -n "$m" ] || m=loop
    case "$m" in
        loop|dir) LAYER_MODE="$m" ;;
        *) die "未知的层模式：$m（只支持 loop / dir）" ;;
    esac
    log "层模式：$LAYER_MODE（loop=losetup+erofs+upper.img；dir=解包成目录+overlay）"
}

# 目录模式：把每一层解包成目录（幂等：版本/大小没变就跳过）
materialize_dirs() {
    local l lf want got stamp
    [ -x "$EROFS_EXTRACT" ] || die "目录模式需要 erofs 解包器：$EROFS_EXTRACT 不可执行。
  可以用 SUNSETLINUX_EROFS_EXTRACT=<路径> 指定，或改用层模式 loop。
  注：Android 自带 /system/bin/fsck.erofs（erofs-utils ≥1.6，支持 --extract=DIR）。"
    mkdir -p "$DIRS_DIR" "$DIRS_UPPER" "$DIRS_WORK" || die "无法创建目录模式所需目录（$DIRS_DIR）"
    for l in $LAYER_NAMES; do
        lf="$(find_layer "$l" || true)"
        [ -n "$lf" ] || die "缺少层文件 $LAYERS_DIR/$l.{erofs,squashfs}（先跑 linuxctl provision）"
        case "$(layer_format "$lf" 2>/dev/null || echo unknown)" in
            erofs) : ;;
            *) die "目录模式只支持 erofs 层（$l 是 $(layer_format "$lf" 2>/dev/null || echo unknown)）：$lf。" ;;
        esac
        # 戳 = 文件名 + 字节数：层被替换（新版本）就会变；不用每层 1 GB 去算 sha256
        [ -f "$lf" ] || die "层文件不存在（刚被删除？）：$lf"
        want="$(basename "$lf"):$(wc -c < "$lf" 2>/dev/null | tr -d ' ')"
        stamp="$DIRS_DIR/.$l.stamp"
        got="$(cat "$stamp" 2>/dev/null || printf '')"
        if [ "$got" = "$want" ] && [ -d "$DIRS_DIR/$l" ]; then
            log "目录模式：$l 已解包且未变化（$want），跳过"
            continue
        fi
        log "目录模式：解包 $l（$want）→ $DIRS_DIR/$l（首次可能要几分钟）"
        rm -rf "$DIRS_DIR/$l"
        mkdir -p "$DIRS_DIR/$l" || die "无法创建 $DIRS_DIR/$l"
        if ! "$EROFS_EXTRACT" --extract="$DIRS_DIR/$l" "$lf" >>"$DAEMON_LOG" 2>&1; then
            die "解包 $l 失败：$EROFS_EXTRACT --extract=$DIRS_DIR/$l $lf（详见 $DAEMON_LOG）"
        fi
        if [ ! -f "$DIRS_DIR/$l/etc/os-release" ] && [ ! -d "$DIRS_DIR/$l/usr" ]; then
            warn_soft "层 $l 解出来后没看到 /usr 或 /etc/os-release（层内容可疑）"
        fi
        printf '%s' "$want" > "$stamp" 2>/dev/null || true
        log "目录模式：$l 解包完成"
    done
}

# 挂载树本体（即 §4 的 3–9 步；1/2 步由父进程的 unshare 完成，10 步是 chroot）
# ---------------------------------------------------------------------------
build_mount_tree() {
    # --- 前置检查：层文件齐不齐 + 格式是否被内核支持 -----------------------
    local l lf fmt
    for l in $LAYER_NAMES; do
        lf="$(find_layer "$l" || true)"
        [ -n "$lf" ] || die "缺少层文件 $LAYERS_DIR/$l.{erofs,squashfs}（先跑 linuxctl provision）"
        fmt="$(layer_format "$lf" 2>/dev/null || echo unknown)"
        case "$fmt" in
            erofs|squashfs) log "层 $l = $lf（格式 $fmt）" ;;
            *) die "层 $l 格式不可识别（magic=$fmt）：$lf" ;;
        esac
    done
    # 残留清理：上次异常退出可能留下 mounts 记录，先按记录卸一遍
    if [ -s "$MOUNTS_FILE" ]; then
        log "发现上次残留的挂载记录，先清理"
        unmount_recorded
    fi

    # 本机 loop 连**读**都过不去（cmdprobe loop_read=no，原因见 probe_loop_io 的注释：
    # loop 的 I/O 在 kernel 域做，本机策略不给它读我们的文件）→ 别走
    # "清残留 loop → e2fsck -p → 显式 losetup" 那套仪式：那三步救不了"域没权限"，
    # 只会再刷一屏 I/O error。显式 `--layer-mode loop` 时尊重用户选择、照旧试。
    if [ "$LAYER_MODE" = "loop" ] && [ -z "${LAYER_MODE_FLAG:-}" ] \
       && [ "$(probe_get loop_read unknown)" = "no" ]; then
        warn_soft "本机 loop 读不通（cmdprobe loop_read=no）→ 跳过 loop 模式，直接按 dir 模式处理（要强试：linuxctl start --layer-mode loop）"
        LAYER_MODE=dir
        printf 'dir\n' > "$RUN_DIR/layer-mode" 2>/dev/null || true
    fi

    local ovl_opts="" base_for_util=""
    if [ "$LAYER_MODE" = "dir" ]; then
        # ---- 目录模式：不碰 loop / 不挂 erofs / 不需要 upper.img ----
        # ★ 先判"这个文件系统能不能当 overlay 的层"（见 overlay_fs_usable 的注释）：
        #   不行的话，解包 1.6 GB 只是白等 —— 直接带原因退出。
        if ! overlay_fs_usable "$LH"; then
            die "目录模式不可用：$LH 所在的文件系统不能给 overlayfs 当层（内核：filesystem on '…' not supported → EINVAL）。
  原因见 fs/overlayfs/util.c 的 ovl_dentry_weird()：f2fs 一旦带 casefold 等特性就命中 DCACHE_OP_*，overlayfs 一律拒绝（连只读 lowerdir 都不行）。
  这台设备上请改用 loop 模式（linuxctl start --layer-mode loop）：那里 upper 在 ext4、lower 在 erofs，都不是被拒的文件系统。"
        fi
        materialize_dirs
        base_for_util="$DIRS_DIR/base"
        mkdir -p "$DIRS_UPPER" "$DIRS_WORK" || die "无法创建目录模式 upper/work：$DIRS_UPPER"
        ovl_opts="lowerdir=$DIRS_DIR/dsh:$DIRS_DIR/runtime:$DIRS_DIR/base"
        ovl_opts="$ovl_opts,upperdir=$DIRS_UPPER,workdir=$DIRS_WORK"
    else
        # --- §4 步 3：ext4 可写层（loop），带自愈（见 mount_upper_rw）--------
        if mount_upper_rw; then
            mkdir -p "$UPPER_DIR/upper" "$UPPER_DIR/work" || die "无法创建 overlay upper/work 目录"

            # --- §4 步 4（前半）：把三层只读镜像 loop 挂成目录 -------------
            # **对 architecture.md §4 的必要修正**：原文写 `lowerdir=layers/dsh:...`，
            # 直接把镜像文件路径当 lowerdir。这要求镜像能被 overlayfs 直接当 lower，
            # 而 overlayfs 的 lowerdir 必须是**目录**（squashfs/erofs 镜像是块设备内容，
            # 不是目录）。因此先把每层 loop 挂到 $LAYERS_MNT/<name>，再用这些目录组 lowerdir。
            # 代价：多 3 个 loop 挂载；收益：格式无关（erofs/squashfs 都行），且能对每层
            # 单独做健康检查。
            mkdir -p "$LAYERS_MNT"
            for l in $LAYER_NAMES; do
                mount_layer "$l" "$(find_layer "$l")"
            done
            base_for_util="$LAYERS_MNT/base"

            # --- §4 步 4（后半）：overlay 合并层 ---------------------------
            # 顺序：最右 = 最底层。dsh 在最上 → 更新 dsh 层即可换 DSH 版本。
            ovl_opts="lowerdir=$LAYERS_MNT/dsh:$LAYERS_MNT/runtime:$LAYERS_MNT/base"
            ovl_opts="$ovl_opts,upperdir=$UPPER_DIR/upper,workdir=$UPPER_DIR/work"
        else
            # ---- 自愈失败 → 自动降级到 dir 模式 ---------------------------
            # 为什么要自动切、而不是 die：loop + ext4 这条链在部分设备/内核上就是
            # 挂不上（真机：只读能挂、读写 EIO），而 overlayfs over 目录是内核确认
            # 支持的最基本用法。用户要的是"环境能起来"，不是"必须用 loop"。
            # 代价：磁盘（解包后约 1.6 GB）。这条降级会把原因与代价都写进日志。
            warn_soft "loop 可写层挂不上：$UPPER_ERR"
            warn_soft "自动降级到 dir 层模式（不碰 loop / upper.img；代价是解包后约 1.6 GB 磁盘）"
            log "要固定这个选择：linuxctl start --layer-mode dir，或设置 → 层模式 → dir"

            LAYER_MODE=dir
            printf 'dir\n' > "$RUN_DIR/layer-mode" 2>/dev/null || true
            # 降级也要先判：本机 /data 的 f2fs 被 overlayfs 拒（见 overlay_fs_usable），
            # 那就没有必要解包 1.6 GB —— 直接把两条路都不通的事实摆清楚。
            if ! overlay_fs_usable "$LH"; then
                die "loop 可写层挂不上，而目录模式在这台机上也不可用：$LH 所在的文件系统不能给 overlayfs 当层（内核：filesystem on '…' not supported → EINVAL）。
  证据：loop 原文 $UPPER_ERR
  两条路都不通 ⇒ 需要修 loop（可写层）或换一个能当 overlay 层的文件系统；详见 docs/（诊断脚本已记录内核原文）。"
            fi
            materialize_dirs
            base_for_util="$DIRS_DIR/base"
            mkdir -p "$DIRS_UPPER" "$DIRS_WORK" || die "无法创建目录模式 upper/work：$DIRS_UPPER"
            ovl_opts="lowerdir=$DIRS_DIR/dsh:$DIRS_DIR/runtime:$DIRS_DIR/base"
            ovl_opts="$ovl_opts,upperdir=$DIRS_UPPER,workdir=$DIRS_WORK"
        fi
    fi
    # base 层挂上/解开之后才可能取到 util-linux 的 mount，这里补一次探测，
    # 让后面的 proc/sys/dev 挂载有真正的回退路径可用
    detect_util_mount "$base_for_util"
    # 挂载点先建好（overlay 要求目录存在且为空；非空会报 ENOTEMPTY）
    [ -d "$ROOTFS_DIR" ] || mkdir -p "$ROOTFS_DIR" || die "无法创建 $ROOTFS_DIR"
    # overlay 失败时 toybox 只回一句 "Invalid argument"，**原因只在 dmesg**（overlayfs
    # 会用 pr_err 说明是哪条检查不过：missing 'lowerdir' / unrecognized mount option /
    # workdir 与 upperdir 不在同一 mount / upper fs is r/o / 栈深超限 …）。
    # 所以这里：① 先把**实际传下去的选项**记进日志（否则事后无法还原我们的入参），
    #            ② 失败时抓内核原文，并把摘要写进 last-error（App 诊断页读的就是它）。
    log "overlay 参数：mount -t overlay overlay -o $ovl_opts $ROOTFS_DIR"
    if ! "$MOUNT" -t overlay overlay -o "$ovl_opts" "$ROOTFS_DIR" 2>>"$DAEMON_LOG"; then
        OVL_ERR="$(kernel_hint)"
        kernel_hint_log
        die "overlay 挂载失败：mount -t overlay overlay -o $ovl_opts $ROOTFS_DIR（内核原文：$OVL_ERR）"
    fi
    # 登记为 'overlay'（相对 rootfs 就是它自己），stop.sh 会把它放到**最后**卸载
    record_mount "overlay"

    # 环境内需要的目录骨架（overlay 可写层里建，重启保持）
    mkdir -p "$ROOTFS_DIR"/{proc,sys,dev,dev/pts,dev/shm,tmp,run,mnt/sdcard,storage/emulated} \
        || die "无法创建 rootfs 内部目录骨架"

    # --- 把宿主父进程预抓好的 Android 事实（DNS/时区）**拷进** rootfs 的 /etc -----
    # ★ 真机事故（2026-09-17）：这里原来是 gather_android_facts —— 在**内层**（私有
    #   mount/UTS ns）调用 /system/bin/ndc，ndc 打不开 /dev/binder → SIGABRT；连锁出
    #   5 个 tombstone + system_app_anr + system_server crash ⇒ **整机卡死，只能重启**。
    #   抓取（ndc/getprop/route，都是安卓系统工具）必须在**宿主父进程**做（未 unshare），
    #   内层只负责拷贝 —— 这也是 gather_android_facts 注释里本来写的做法。
    install_android_facts

    # --- 先把 /opt/sunsetlinux 入口脚本同步进 rootfs（本地最新版优先）----------
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
# gather_android_facts —— 抓 Android 当前 DNS / 时区，落到 $LINUX_HOME/etc/android-*.txt
#   ★★ 只能在**宿主父进程**（main() 里 spawn unshare 之前）调用 ★★
#   严禁在 `--inner` / chroot 路径里调用：ndc/getprop 是安卓系统工具，在私有 ns 里会
#   打不开 /dev/binder 与 /dev/__properties__ 而 abort，连锁把 system_server 带崩
#   （2026-09-17 真机事故：5 个 tombstone + ANR + system_server crash ⇒ 整机卡死，只能重启）。
#   内层只调用 install_android_facts() 做拷贝。
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
# ---------------------------------------------------------------------------
# install_android_facts —— 把**宿主父进程**预抓的 Android 事实拷进 rootfs 的 /etc
#   为什么只能拷、不能抓：抓取要调 /system/bin/ndc、getprop，它们依赖 binder / 属性区；
#   而本函数运行在 `--inner`（私有 mount/UTS ns）里。真机事故（2026-09-17）：
#       ndc: SIGABRT "Binder driver '/dev/binder' could not be opened. Error: 2"
#   → 5 个 tombstone + system_app_anr + system_server_crash ⇒ 整机卡死、只能重启。
#   entry.sh 读的是 **/etc/android-resolv.txt**（chroot 内），所以这里拷到 rootfs/etc/。
# ---------------------------------------------------------------------------
install_android_facts() {
    local dst="$ROOTFS_DIR/etc" f n=0
    [ -d "$dst" ] || { log "WARN: $dst 不存在，跳过 Android 事实拷贝"; return 0; }
    for f in android-dns.txt android-resolv.txt android-timezone.txt; do
        [ -f "$ETC_DIR/$f" ] || continue
        cp -f "$ETC_DIR/$f" "$dst/$f" 2>/dev/null && n=$((n + 1))
    done
    if [ "$n" -gt 0 ]; then
        log "已拷入 rootfs/etc：$n 个 Android 事实文件（宿主父进程预抓；环境内不调安卓工具）"
    else
        log "WARN: $ETC_DIR 下没有 Android 事实文件（父进程抓取失败？entry.sh 会兜底 8.8.8.8）"
    fi
    return 0
}

gather_android_facts() {
    local dns_file="$ETC_DIR/android-dns.txt"
    local resolv_file="$ETC_DIR/android-resolv.txt"
    local tz_file="$ETC_DIR/android-timezone.txt"
    local dns="" line raw=""

    mkdir -p "$ETC_DIR" 2>/dev/null || true

    # --- ① ndc resolver getresolvers ---
    # ★ ndc 默认不用：它经 binder 找 netd，真机实测（2026-09-17）会 SIGABRT
    #   "Binder driver '/dev/binder' could not be opened"，父进程调用同样 abort。
    #   要试可设 SUNSETLINUX_ALLOW_NDC=1；DNS 走 getprop/resolv.conf/路由表/entry 兜底。
    if [ -n "${SUNSETLINUX_ALLOW_NDC:-}" ] && [ -x /system/bin/ndc ]; then
        raw="$(/system/bin/ndc resolver getresolvers 2>/dev/null || true)"
        dns="$(printf '%s' "$raw" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' 2>/dev/null | sort -u | tr '\n' ' ' || true)"
        [ -n "$dns" ] && log "DNS 取自 ndc resolver getresolvers：$dns"
    fi
    # --- ② ndc resolver getnetdns（默认网络 id 通常是 0/100）---
    if [ -n "${SUNSETLINUX_ALLOW_NDC:-}" ] && [ -z "$dns" ] && [ -x /system/bin/ndc ]; then
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
# /opt/sunsetlinux 启动器脚本同步（幂等）
# ---------------------------------------------------------------------------
install_runtime_entry() {
    local src="" dst="$ROOTFS_DIR/opt/sunsetlinux"
    # 来源优先级：KernelSU 模块目录 → 本脚本同目录 → LINUX_HOME/bin
    for cand in /data/adb/modules/sunsetlinux/bin "$SELF_DIR" "$LH/bin"; do
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
#   判定方式：解析宿主 /proc/self/mountinfo，看 /data/sunsetlinux/rootfs 上的 overlay
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
        # 锁的持有者换成**守护进程自己**：外层可能先超时退出（建树实测可到 50s，
        # 而外层 wait_ready 默认 45s），锁跟着它走才不会在窗口里被放掉。
        start_lock_rewrite_self
        # 如果外层 unshare 不支持 --propagation，就在这里把整个 ns 设为私有
        #   （--make-rprivate /：一条命令覆盖 ns 内所有挂载点）
        # ★ 真机事实（2026-09-17）：**toybox 的 mount 根本没有 --make-rprivate**，
        #   也没有 `-o make-rprivate`（它的选项表里只有 private/rprivate/slave/rslave）。
        #   不认识的参数会被 toybox 当成"只给了一个位置参数"→ 走 fstab 分支 →
        #   打印 `bad /etc/fstab` 后失败。日志里那两行就是这么来的。
        # ⚠ 也不要用 `-o rprivate / /` 顶替：toybox 见到"两个目录参数"会自行判成 bind，
        #   并且把 MS_REC 清掉 —— 结果是在 / 上压一层**非递归 bind**，/data 这类子挂载
        #   会被遮住（比不设私有更糟）。真正能改传播的只有 util-linux 的 mount：
        #   base 层里那份（用 util_mount_run 经动态加载器跑）或外层 unshare。
        if [ "${MAKE_PRIVATE:-0}" = "1" ]; then
            if "$MOUNT" --make-rprivate / 2>>"$DAEMON_LOG"; then
                log "已把 mount ns 根设为 rprivate"
            elif [ -n "$UTIL_MNT_BIN" ] && util_mount_run --make-rprivate / 2>>"$DAEMON_LOG"; then
                log "已把 mount ns 根设为 rprivate（base 层的 util-linux）"
            else
                # 不必惊慌也不必撒谎：/ 与 /data 是 slave(master:) 时本就不会回流，
                # 只有它们是 shared(:) 时才有风险 —— 给出可核对的判据。
                warn_soft "make-rprivate 未成功（toybox 无此选项）：若 / 或 /data 是 shared: 则本 ns 的挂载会回流宿主（用 grep -E ' / | /data ' /proc/self/mountinfo 看 master:/shared: 字段；是 master: 就安全）"
            fi
        fi
        printf '%s\n' "$LAYER_MODE" > "$RUN_DIR/layer-mode" 2>/dev/null || true
        build_mount_tree

        # UTS：本 ns 内改主机名；不改宿主（findings §6）
        if have hostname; then hostname sunsetlinux 2>/dev/null || log "WARN: hostname sunsetlinux 失败（非致命）"; fi

        write_mounted_json "$$"
        printf '%s\n' "$$" > "$SUPERVISOR_PID_FILE"
        rm -f "$ERROR_FILE"
        : > "$READY_FILE"
        # 「正在启动」的锁到这里完成使命：`supervisor.pid` + `ready` 都在了，之后由
        # running_ns_pid 正常挡住重复启动。这里由**守护进程**自己释放，是为了盖住
        # "外层 start.sh 已经超时退出、建树还在继续"的那种时间线（外层 EXIT trap 也会释放一次）。
        rm -f "$START_LOCK" 2>/dev/null || true
        # ★ run/env-mode：本次是「一键启动」(full) 还是「仅启动环境」(env-only)。
        #   这是**宿主侧**（App / `linuxctl dsh start|stop`）判定互斥的唯一依据，
        #   所以写在共享的 run/ 里（环境内 /run 与宿主 $LINUX_HOME/run 是同一批 inode）。
        local dsh_flag=""
        if [ "$NO_DSH" = "1" ]; then
            printf '%s\n' "env-only" > "$RUN_DIR/env-mode" 2>/dev/null || true
            dsh_flag="--no-dsh"
            log "挂载树就绪，交给 entry.sh（--no-dsh：只起环境，不启动 DSH）"
        else
            printf '%s\n' "full" > "$RUN_DIR/env-mode" 2>/dev/null || true
            log "挂载树就绪，交给 entry.sh"
        fi
        local rc=0
        # ★ 进 chroot 前必须显式给环境变量：`#!/usr/bin/env bash` 里的 env 用的是
        #   **从安卓继承的 PATH**（/product/bin:/system/bin 那一套），里面没有 /usr/bin
        #   ⇒ 真机 2026-09-17 实测：`/usr/bin/env: 'bash': No such file or directory` → rc=127；
        #   挂载树全建好了却起不来（日志停在"挂载树就绪，交给 entry.sh"）。
        #   entry.sh/supervise.sh 的 shebang 也一并从 `env bash` 改成 `/bin/bash`（双保险）。
        # 端口让路：一键启动这条路上，DSH 的端口是**这里**定死的（entry.sh → supervise.sh --port）。
        #   真机现场：免 root 的 DSHA 占着 3080，照原端口起只会 bind 失败、环境被判"启动失败"。
        #   让路后真实端口由 supervise.sh 写进 run/dsh.port，登录 URL 里也带它 ⇒ App 侧透明。
        local _want_port _port
        _want_port="$(config_port)"
        if command -v port_pick_free >/dev/null 2>&1; then
            _port="$(port_pick_free "$_want_port")"
        else
            _port="$_want_port"
        fi
        if [ "$_port" != "$_want_port" ]; then
            log "提示：端口 $_want_port 被占用 → 本次 DSH 用 $_port（登录地址里会带真实端口）"
        fi
        PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
        HOME=/root TERM="${TERM:-xterm-256color}" \
            chroot "$ROOTFS_DIR" /opt/sunsetlinux/entry.sh "$_port" $dsh_flag || rc=$?
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

    # ---- 启动锁：挡住"第二次 start 又建一棵挂载树"（理由见 start_lock_acquire）----
    # 注意顺序：上面的"已就绪"判定在前（真的在跑就直接返回，不碰锁）。
    if ! start_lock_acquire; then
        local holder=""
        if holder="$(start_lock_holder)"; then
            # 有人正在建树：**不重复建**，只等他就绪（这是真正要修的行为）
            log "另一次启动正在进行（pid=$holder）—— 不重复建挂载树，等它就绪"
            if wait_ready "${START_WAIT:-90}"; then
                ns_pid="$(running_ns_pid)"
                log "另一次启动已完成（ns_pid=$ns_pid）"
                exit 0
            fi
            printf '另一次启动仍在进行（pid=%s）：等它完成，或先「停止环境」再重试\n' "$holder" > "$ERROR_FILE"
            log "ERROR: 另一次启动仍在进行（pid=$holder），本次不重复建挂载树"
            exit 1
        fi
        # 陈旧锁（持锁进程已死）：接管，别让一次崩溃把以后所有启动都堵住
        log "WARN: 发现陈旧的启动锁（持锁进程已不在），接管"
        start_lock_release
        if ! start_lock_acquire; then
            printf '启动锁被另一个进程抢走（run/start.lock）：请稍后重试\n' > "$ERROR_FILE"
            log "ERROR: 接管启动锁失败（另一次启动刚好开始）"
            exit 1
        fi
    fi
    # 成功/失败/超时都要释放：脚本退出后要么 ready 已在（由 running_ns_pid 挡住重复启动），
    # 要么这条启动已经失败（不该继续挡着）。守护进程自己还会在写完 ready 时再释放一次。
    trap 'start_lock_release_own' EXIT INT TERM HUP

    # 前置检查放在起守护进程之前，失败能立刻给 App 可读原因
    local l
    for l in $LAYER_NAMES; do
        find_layer "$l" >/dev/null 2>&1 || die "缺少层文件 $LAYERS_DIR/$l.{erofs,squashfs}（先跑 linuxctl provision）"
    done
    # 目录模式不需要 upper.img（可写层是 $DIRS_UPPER 真目录）——先解析模式再判
    resolve_layer_mode
    if [ "$LAYER_MODE" != "dir" ]; then
        [ -f "$LH/upper.img" ] || die "缺少可写层镜像 $LH/upper.img（先跑 linuxctl provision；或用 --layer-mode dir）"
    fi
    # 端口占用提前查：**只报信息、不参与选端口**（选端口在内层，见下面的 entry.sh --port）。
    #   ⚠️ 这里的口气必须是"环境会让路"，不能说成"dsh 可能自行换端口" —— 后者是旧行为，
    #   现在由 `port_pick_free` 主动挑空闲端口，真实端口一定落在 run/dsh.port 里。
    #   两边各说一套，用户就会照着错的去查端口。
    local port; port="$(config_port)"
    if port_busy "$port"; then
        log "WARN: 端口 $port 已被占用 → 环境会让路到空闲端口（实际端口看 run/dsh.port）"
    fi

    # 启动方式：setsid + unshare(-m -u) + 守护子进程。
    # （原来用数组装 launcher，改成布尔开关 + spawn_detached()，见该函数注释）
    local use_setsid=0
    if [ -x "$SETSID" ] && [ "$FOREGROUND" = "0" ]; then
        use_setsid=1
    fi

    # 选项语法按**探测结果**选择（toybox 可能不认 --propagation）。
    run_probes 0
    local prop
    prop="$(probe_get unshare_propagation no)"
    # ★ 把已解析好的层模式 export 给守护进程：`--inner` 调用**不会**带 `--layer-mode`，
    #   子进程靠 resolve_layer_mode 从环境变量读到同一个值（否则会退回 loop，白解析一场）。
    SUNSETLINUX_LAYER_MODE="$LAYER_MODE"
    export SUNSETLINUX_LAYER_MODE
    # ★ 在**父进程（宿主 ns，未 unshare）**里抓 Android 事实：ndc/getprop/route 都是
    #   安卓系统工具，一旦在私有 ns 里调用就会像 2026-09-17 那样把整机带崩。
    #   内层只读取/拷贝这里落下的文件（见 install_android_facts）。
    gather_android_facts
    log "启动守护进程：unshare -m -u（propagation=$prop, layer_mode=$LAYER_MODE）$SELF_DIR/start.sh --inner"

    # 起守护进程：**两选一**，按探测结果决定；成功与否只看下面的 wait_ready。
    #
    #   ★ 不再用 `--fork`：脱离子进程现在由 spawn_daemon 的后台子 shell 负责（见该函数
    #     注释里的真机事故）。以前三种写法 + `uc_ok`（拿**前台** spawn 的退出码猜
    #     "unshare 认不认这组选项"）—— 正是那个前台 spawn 把整条调用链卡死的。
    if [ "$prop" = "yes" ]; then
        # 首选：显式要求私有传播（一步到位，最干净）
        spawn_daemon "$use_setsid" "$UNSHARE" -m -u --propagation private \
            "$SELF_DIR/start.sh" --inner
    else
        # 退化：只 unshare -m -u，然后在**内层**自己把传播设为 private
        log "提示：unshare 不支持 --propagation，改为内层 make-rprivate"
        spawn_daemon "$use_setsid" "$UNSHARE" -m -u "$SELF_DIR/start.sh" --inner --make-private
    fi

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

# 端口占用的判定。
#   ⚠️ 原来是 `exec 3<>/dev/tcp/…`——那是 **bash 专有**特性，而本脚本在设备上由
#   /system/bin/sh（mksh）执行，mksh **没有 /dev/tcp**：实测同一个端口 bash 连得上、
#   mksh 恒失败。后果是端口明明被占也会判"空闲"，`mksh -n` 抓不到（不是语法错）。
#   三层回退（与 runtime/proot/{start,linuxctl}.sh 保持同一套逻辑与顺序）：
#     1) /proc/net/tcp[6] 找 LISTEN（st=0A）——纯 awk，Android 上一定有
#     2) nc -z   3) ss -ltn
# 端口探测（tcp_listen_local / port_busy）已抽到 runtime/common/port-probe.sh：
# 与 linuxctl 的 `dsh start` 让路逻辑共用一份判据（两处各写一遍必然漂移）。
# 缺了它就不再"猜"端口是否被占 —— 直接报出来（否则会静默退回"判空闲"）。
_PORT_PROBE=""
for _cand in "$SELF_DIR/common/port-probe.sh" "$SELF_DIR/../common/port-probe.sh" \
             "$SELF_DIR/../../runtime/common/port-probe.sh"; do
    [ -f "$_cand" ] && { _PORT_PROBE="$_cand"; break; }
done
if [ -n "$_PORT_PROBE" ]; then
    # shellcheck source=/dev/null
    SUNSETLINUX_SOURCED=1 . "$_PORT_PROBE"
else
    die "缺 port-probe.sh（应与 start.sh 同目录的 common/ 或 ../common/）：端口占用判定无法进行"
fi

main "$@"
