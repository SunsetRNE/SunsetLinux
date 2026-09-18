#!/system/bin/sh
# =============================================================================
# sunsetlinux · runtime/root/linuxctl.sh
#
# root 模式的 linuxctl 实现（architecture.md §3 的全部子命令）。
#
# 铁律：
#   1) **stdout 只有 JSON**（App 解析它）；所有人类可读文本走 stderr。
#   2) 所有子命令**幂等**：重复 start / stop / provision 都不报错。
#   3) status 严格输出 §3.1 的 schema —— 所有键都在，取不到写 null，不省略键。
#   4) LINUX_HOME 可用环境变量覆盖，默认 /data/sunsetlinux。
#
# 子命令与退出码（§3）：
#   provision [--seed <dir>]   0 成功 / 2 已部署
#   start / stop / status      0
#   attach [-- cmd...]         命令退出码（无 -- 则开交互 shell）
#   exec -- cmd...             命令退出码
#   logs [-n N]                0
#   snapshot <name>            0
#   restore <name>             0
#   reset                      0
#   update <layer> <file>      0
#   doctor                     0/1
#
# 与 architecture.md 的一致性说明：
#   - 挂载/卸载的**顺序与内容**在 start.sh / stop.sh 里，本脚本只做编排与 JSON。
#   - 层格式：内核不支持 squashfs，实际用 erofs（见 doctor.sh / start.sh 注释）。
#     本脚本对两种扩展名都兼容。
# =============================================================================
set -uo pipefail

# --- 目录：脚本自身 + 公共库 ------------------------------------------------
# 设备侧只用 `$0`：`${BASH_SOURCE[0]:-$0}` 在 busybox ash（模块自启的 PATH 里
# `sh` 可能就是它）下是 **syntax error: bad substitution**，整个脚本一行都跑不了。
SELF_PATH="$0"
SELF_DIR="$(cd -- "$(dirname -- "$SELF_PATH")" && pwd -P)"

# 公共库查找顺序：同目录（安装后 bin/ 里会一起放）→ 仓库相对路径
COMMON_DIR=""
for cand in "$SELF_DIR/common" "$SELF_DIR/../common" "$SELF_DIR/../../runtime/common" "$SELF_DIR/../lib/sunsetlinux"; do
    if [ -f "$cand/status_json.sh" ]; then COMMON_DIR="$cand"; break; fi
done
if [ -z "$COMMON_DIR" ]; then
    printf 'linuxctl: 找不到 status_json.sh（应与本脚本同目录或 ../common）\n' >&2
    exit 1
fi
# shellcheck source=/dev/null
# 显式告知被 source（mksh 下无法自查 BASH_SOURCE，见 status_json.sh 尾部注释）
SUNSETLINUX_SOURCED=1 . "$COMMON_DIR/status_json.sh"
# 内核 v2 的状态读取（**唯一实现**：linuxctl 与 start.sh 共用一份 —— 见该文件头注释）
[ -f "$COMMON_DIR/kernel-state.sh" ] && SUNSETLINUX_SOURCED=1 . "$COMMON_DIR/kernel-state.sh"

# layer-spec：分层规格的唯一共同事实源（层命名/版本格式/压缩口径）。
# 找不到时**不致命**：本脚本里所有用到的地方都有内联兜底默认值，
# 但会告警提示"版本命名与 provision 可能不一致"。
LAYER_SPEC_FILE=""
for _cand in "$SELF_DIR/layer-spec.sh" "$SELF_DIR/common/layer-spec.sh" \
             "$SELF_DIR/../layer-spec.sh" "$SELF_DIR/../../rootfs/layer-spec.sh"; do
    [ -f "$_cand" ] && { LAYER_SPEC_FILE="$_cand"; break; }
done
if [ -n "$LAYER_SPEC_FILE" ]; then
    # shellcheck source=/dev/null
    . "$LAYER_SPEC_FILE"
else
    printf '[linuxctl][warn] 未找到 rootfs/layer-spec.sh，使用内联兜底默认值\n' >&2
    LAYER_SPEC_VERSION="${LAYER_SPEC_VERSION:-1}"
    LAYER_EXT="${LAYER_EXT:-erofs}"
    EROFS_COMPRESS="${EROFS_COMPRESS:-none}"
    TRANSPORT_COMPRESS_PRIMARY="${TRANSPORT_COMPRESS_PRIMARY:-zstd}"
    layer_file_name() { printf '%s-%s.%s' "$1" "$2" "${LAYER_EXT:-erofs}"; }
    layer_transport_name() { printf '%s-%s.erofs.zst' "$1" "$2"; }
fi
# shellcheck source=/dev/null
SUNSETLINUX_SOURCED=1 . "$COMMON_DIR/http_health.sh"
# 层内容检查（layer_has_path）：与 doctor 共用一份实现 —— `dsh builtin` 要靠它判断
# "当前生效的 dsh 层是不是坏的"（真机 2026-09-17：内置层已就位、却因为旧记录一直没被启用）。
if [ -f "$COMMON_DIR/layer-inspect.sh" ]; then
    # shellcheck source=/dev/null
    SUNSETLINUX_SOURCED=1 . "$COMMON_DIR/layer-inspect.sh"
else
    layer_has_path() { return 2; }   # 三态里的"判不了"，绝不假装"没有"
fi
# 环境内进程的识别/清理（判据是 /proc/<pid>/root）——`dsh start|stop` 与 stop.sh 共用一份。
# 找不到时**不致命**：dsh stop 仍能按 run/dsh.pid 精确补刀，只是少了"pid 文件丢了"的兜底。
# 端口探测（port_busy）：`dsh start` 在端口被占时要让路 —— 与 start.sh 共用一份实现。
if [ -f "$COMMON_DIR/port-probe.sh" ]; then
    # shellcheck source=/dev/null
    SUNSETLINUX_SOURCED=1 . "$COMMON_DIR/port-probe.sh"
fi
if [ -f "$COMMON_DIR/env-procs.sh" ]; then
    # shellcheck source=/dev/null
    SUNSETLINUX_SOURCED=1 . "$COMMON_DIR/env-procs.sh"
fi

# ---------------------------------------------------------------------------
# 选一个能跑**设备侧脚本**的 shell（start/stop/update/doctor 都靠它）
#
# 真机事故（2026-09-16）：层文件三个都在、`state.json` 也齐，App 却一直显示
# 「未挂载 / 失败（文件/层缺失）」。`run/service.log` 里只有一行：
#
#     /data/sunsetlinux/bin/linuxctl: line 34: syntax error: bad substitution
#
# 两个原因叠在一起，都是"以为设备上有 bash"：
#   1) 模块 `service.sh` 用裸 `sh "$CTL" start` 发起 —— 模块环境里 PATH 前面是
#      KernelSU 的 busybox，`sh` 解析成 **busybox ash**（不是 mksh）。ash 见到
#      `${BASH_SOURCE[0]:-$0}` 直接判 `syntax error: bad substitution`，脚本一行都没跑。
#   2) 就算跑到了 `linuxctl start`，它内部又用 `bash start.sh` 去挂载 —— 而
#      **Android 上没有 bash**（`/system/bin/bash` 不存在），挂载这一步必然失败。
#
# 所以：只认两个解释器 —— 开发机/CI 上的 bash（自测用），或 Android 的
# `/system/bin/sh`（mksh）。**绝不用裸 `sh`**：谁在 PATH 里就听谁的，等于把
# "哪台机器能跑"交给运气。
# ---------------------------------------------------------------------------
pick_shell() {
    if [ -n "${SUNSETLINUX_SH:-}" ] && [ -x "${SUNSETLINUX_SH}" ]; then
        printf '%s' "$SUNSETLINUX_SH"; return 0
    fi
    if command -v bash >/dev/null 2>&1; then
        command -v bash; return 0
    fi
    if [ -x /system/bin/sh ]; then
        printf '/system/bin/sh'; return 0
    fi
    printf '/bin/sh'
}
SH_BIN="$(pick_shell)"
export SH_BIN

# --- 全局配置 ---------------------------------------------------------------
LINUX_HOME="${LINUX_HOME:-/data/sunsetlinux}"
LH="$LINUX_HOME"
MODE="${LINUXCTL_MODE:-root}"

LAYERS_DIR="$LH/layers"
LAYERS_MNT="$LH/layers-mnt"
ETC_DIR="$LH/etc"
RUN_DIR="$LH/run"
UPPER_DIR="$LH/upper"
UPPER_IMG="$LH/upper.img"
ROOTFS_DIR="$LH/rootfs"
SNAP_DIR="$LH/snapshots"
SEEDS_DIR="$LH/seeds"
CACHE_DIR="$LH/cache"

# 「卸载即清除」标记：模块 uninstall.sh 的唯一判据（不用环境变量，见 docs/uninstall.md）
PURGE_MARKER="$ETC_DIR/PURGE_ON_UNINSTALL"

CONFIG_JSON="$ETC_DIR/config.json"
STATE_JSON="$ETC_DIR/state.json"

SUPERVISOR_PID_FILE="$RUN_DIR/supervisor.pid"
DSH_PID_FILE="$RUN_DIR/dsh.pid"
DSH_URL_FILE="$RUN_DIR/dsh.url"
DSH_PORT_FILE="$RUN_DIR/dsh.port"
# 本次启动是「一键启动」(full) 还是「仅启动环境」(env-only)：App 的互斥判定读它
ENV_MODE_FILE="$RUN_DIR/env-mode"
READY_FILE="$RUN_DIR/ready"
MOUNTS_FILE="$RUN_DIR/mounts"
LOGFILE="$RUN_DIR/linux.log"
ERROR_FILE="$RUN_DIR/last-error"

# 环境内进程的判据根：/proc/<pid>/root 等于它 = 这个进程在我们的 chroot 里（见 env-procs.sh）
ENV_ROOTFS="$ROOTFS_DIR"

# 层顺序：**POSIX 空格分隔字符串，不用数组**。
# 本脚本在设备侧由 /system/bin/sh（Android mksh）执行，不能假设 bash 数组语义；
# 遍历一律写成 `for name in $LAYER_NAMES`（有意不加引号：就是要按空白分词）。
LAYER_NAMES="base runtime dsh"

UMOUNT=/system/bin/umount
MOUNT=/system/bin/mount
NSENTER=/system/bin/nsenter
# ★★ toybox 的 nsenter 会**一路解析到命令之后的每一个选项**（2026-09-18 真机 + 同二进制实测）：
#      nsenter --mount=X /bin/echo --port 3080   →  Unknown option 'port'
#      nsenter --mount=X /bin/echo -l            →  Unknown option 'l'      ← 短选项同样中招
#      nsenter --mount=X -- /bin/echo --port 3080 → ✅（`--` 之后才真正交给命令）
#   后果不是降级、是**整条命令不执行**。真机上因此连坏三处：
#      · `dsh start`（命令尾部是 `supervise.sh --port N`）→ DSH 永远起不来（用户报的正是这个）；
#      · 终端 attach/exec（用户命令带 `-l`/`-c`/`--xx`）→ 任何带选项的命令都失败；
#      · stop/清理里的 `umount -l` → 卸载根本没执行（于是"残留挂载"反复出现）。
#   ⇒ **所有** nsenter 调用必须在命令前写一个 `--`。回归闸门：
#      tools/shell-compat-check.mjs（静态：nsenter 后必须出现 `--`）+
#      runtime/root/selftest.sh「nsenter 的选项面」（行为级：拿设备真二进制跑上面三条）。

# ---------------------------------------------------------------------------
# 日志：一律 stderr（stdout 留给 JSON）
# ---------------------------------------------------------------------------
log()  { printf '[linuxctl] %s\n' "$*" >&2; }
warnl(){ printf '[linuxctl][warn] %s\n' "$*" >&2; }
die()  { printf '[linuxctl][error] %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# 输出一个 JSON 对象（stdout）并返回
emit() { printf '%s\n' "$1"; }

# 简易 JSON 转义（复用公共库的实现）
jesc() { dsh_json_escape "$1"; }

# 读文件偏移处的 4 字节小写 hex。
#   ★ 关键事实（真实产物实测）：**EROFS 的超级块在偏移 1024，不在偏移 0**
#     （偏移 0 处是 0x00000000）。squashfs 的 'hsqs' 才在偏移 0。
#     只读偏移 0 会把每一个合法的 erofs 层误判成 unknown —— 症状是
#     "层明明是好的，却报格式不可识别"，极具误导性。
#   非 0 偏移用 dd 读：toybox 的 `od -j` 支持面不确定，dd 同为 toybox 自带更稳。
_magic_at() { # _magic_at <file> <offset>
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

# 判定层镜像格式：偏移 0 查 squashfs，偏移 1024 查 erofs。
# 注意：**压缩产物（.erofs.zst/.gz）必须继续返回 unknown** —— 解压是客户端（App）
# 的职责，linuxctl 只接受可直接挂载的裸镜像。不要把这条校验放宽。
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

# 层文件查找（兼容 .erofs / .squashfs）
find_layer() {
    local name="$1" cand
    # ① 首选 state.json 记录的**当前生效版本**（"最高版本"≠"生效版本"，回滚靠它）
    if [ -f "$STATE_JSON" ]; then
        local ver=""
        ver="$(tr -d ' \n\t' < "$STATE_JSON" 2>/dev/null \
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

# ---------------------------------------------------------------------------
# 配置读取（不依赖 jq）
# ---------------------------------------------------------------------------
config_port() {
    local p=""
    [ -f "$CONFIG_JSON" ] && p="$(tr -d ' \n\t' < "$CONFIG_JSON" | sed -n 's/.*"port":\([0-9][0-9]*\).*/\1/p' | head -n1)"
    case "$p" in ''|*[!0-9]*) p=3080 ;; esac
    { [ "$p" -ge 1 ] && [ "$p" -le 65535 ]; } 2>/dev/null || p=3080
    printf '%s' "$p"
}

config_autostart() {
    local v=""
    [ -f "$CONFIG_JSON" ] && v="$(tr -d ' \n\t' < "$CONFIG_JSON" | sed -n 's/.*"autostart":\(true\|false\).*/\1/p' | head -n1)"
    case "$v" in true) printf 'true' ;; *) printf 'false' ;; esac
}

# ---------------------------------------------------------------------------
# 运行态探测（不修改任何状态）
# ---------------------------------------------------------------------------
ns_pid_alive() {
    local pid=""
    [ -f "$SUPERVISOR_PID_FILE" ] || return 1
    pid="$(head -n1 "$SUPERVISOR_PID_FILE" 2>/dev/null | tr -dc '0-9' || true)"
    [ -n "$pid" ] || return 1
    [ -d "/proc/$pid" ] || return 1
    [ -r "/proc/$pid/ns/mnt" ] || return 1
    printf '%s' "$pid"
}

# 环境是否"就绪"（ready 文件 + ns 持有者活着）
env_ready() {
    [ -f "$READY_FILE" ] && ns_pid_alive >/dev/null 2>&1
}

# 「正在启动」的持有进程 PID（run/start.lock + 进程活着）。
#
# 为什么需要它（真机 2026-09-18 03:39 的两棵挂载树）：建树要 30~50 秒（loop/erofs/
# overlay/chroot），这期间 `supervisor.pid` 还没写 ⇒ [ns_pid_alive] 返假 ⇒ 状态报
# `stopped` ⇒ App 上"启动"看着像没在跑，用户再点一次就**各建一棵挂载树**
# （run/mounts 从 13 条变 26 条、三条守护链同时活着）。锁是 start.sh 在开工前原子写下的，
# 这里的判据只有两条：文件在 + 里面的 pid 还活着。
start_lock_holder() {
    local pid=""
    [ -f "$RUN_DIR/start.lock" ] || return 1
    pid="$(head -n1 "$RUN_DIR/start.lock" 2>/dev/null | awk '{print $1}' | tr -dc '0-9' || true)"
    [ -n "$pid" ] || return 1
    [ -d "/proc/$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    printf '%s' "$pid"
}

# 取 dsh 进程 PID（run/dsh.pid 且进程活着）
dsh_pid_alive() {
    local pid=""
    [ -f "$DSH_PID_FILE" ] || return 1
    pid="$(head -n1 "$DSH_PID_FILE" 2>/dev/null | tr -dc '0-9' || true)"
    [ -n "$pid" ] || return 1
    [ -d "/proc/$pid" ] || return 1
    printf '%s' "$pid"
}

# DSH 进程是否在跑（在跑就打印 pid）。
#   「环境在跑但没有 DSH」是**一等状态**（App 的「仅启动环境」+ 终端维护），所以这个判断
#   不能只看 state。优先 run/dsh.pid；pid 文件丢了或过期了（模块 ≤1.0.26 的老现场、
#   手工 kill 过 supervisor）就按"root 在我们的 rootfs + 命令行像 dsh web"兜底 ——
#   光看命令行会误杀别的环境（DSHA proot 版也叫 dsh web，见 STATUS §3.10.31）。
dsh_is_running() {
    local pid=""
    pid="$(dsh_pid_alive 2>/dev/null || true)"
    if [ -n "$pid" ]; then printf '%s' "$pid"; return 0; fi
    if command -v env_proc_pids >/dev/null 2>&1; then
        pid="$(env_proc_pids '*dsh*web*' 2>/dev/null | head -n1)"
        if [ -n "$pid" ]; then printf '%s' "$pid"; return 0; fi
    fi
    return 1
}

# 本次启动是「一键启动」(full) 还是「仅启动环境」(env-only)；没启动过 → 空字符串
env_mode_read() {
    [ -f "$ENV_MODE_FILE" ] || return 0
    tr -d ' \n\r' < "$ENV_MODE_FILE" 2>/dev/null | head -c 16
}

# 重启时**保持原来的起法**：env-only 起的就别再把 DSH 拽起来。
#   层更新 / 版本回滚 / 快照恢复都会 stop+start。用户的场景恰恰是"在仅环境模式下更新层"——
#   重启后凭空冒出 DSH（占着 3080）等于把维护模式悄悄踢掉，这是拆分后最容易漏的破绽。
#   ★ 必须在 stop **之前**读 env-mode：stop.sh 会把它清掉。
resume_start_like_before() { # resume_start_like_before <旧 env-mode>
    case "${1:-}" in
        env-only) cmd_start --no-dsh >/dev/null 2>&1 ;;
        *)        cmd_start >/dev/null 2>&1 ;;
    esac
}

# 等 dsh.pid 出现（supervise.sh 后台起完 dsh 会立刻写它）。
#   为什么需要："起不来"与"起来了但没打印 URL"是两件完全不同的事，报错也完全不同：
#   前者要去看 nsenter/chroot（例如真机上 `nsenter: Unknown option 'port'` 就是整条没执行），
#   后者才是 DSH 自己起不来（端口被占、profile 缺失…）。2026-09-18 真机上因为只有后者那句，
#   用户看到的是"20s 内没有打印登录 URL"，而真相是命令压根没被执行。
wait_dsh_pid() { # wait_dsh_pid <秒>
    local i=0 n=$((${1:-6} * 5))
    while [ "$i" -lt "$n" ]; do
        [ -s "$DSH_PID_FILE" ] && dsh_pid_alive >/dev/null 2>&1 && return 0
        sleep 0.2
        i=$(( i + 1 ))
    done
    return 1
}

# 等带令牌的登录 URL（supervise.sh 抓到 `dsh web: ` 那一行后写 run/dsh.url）
wait_dsh_url() { # wait_dsh_url <秒>
    local i=0 n=$((${1:-20} * 5))
    while [ "$i" -lt "$n" ]; do
        [ -s "$DSH_URL_FILE" ] && return 0
        sleep 0.2
        i=$(( i + 1 ))
    done
    return 1
}

# 在**运行中的环境里**把 DSH supervisor 拉起来（detached）。
#   为什么 nsenter：本脚本在宿主 mount ns 里，挂载树在 start.sh 的私有 ns 里，不进去看不见。
#   为什么 setsid：linuxctl 是 App 通过 `su -c` 起的；su 一退，进程组可能吃 SIGHUP ——
#   DSH 必须活过"点按钮的那一次调用"。找不到 setsid 只是告警（真机 toybox 有）。
spawn_dsh_in_env() { # spawn_dsh_in_env <port>
    local port="$1" nspid="" sid="" c=""
    nspid="$(ns_pid_alive)" || return 1
    # 必须 nsenter：挂载树是在 start.sh 的**私有 mount ns** 里建的，宿主 ns 里直接 chroot
    # 只会看到一个空壳（挂载点没被带进来），DSH 起不来还很难懂原因 —— 这里提前判死。
    [ -x "$NSENTER" ] || { log "ERROR: 缺 $NSENTER，无法进入环境的私有 mount ns"; return 1; }
    [ -f "$ROOTFS_DIR/opt/sunsetlinux/supervise.sh" ] || {
        log "ERROR: 环境里没有 /opt/sunsetlinux/supervise.sh（模块与 rootfs 版本不同步？重装模块）"
        return 1
    }
    for c in /system/bin/setsid /usr/bin/setsid; do
        [ -x "$c" ] && { sid="$c"; break; }
    done
    [ -n "$sid" ] || warnl "找不到 setsid：DSH 可能随这次 su 会话一起被挂断"
    if [ -n "$sid" ]; then
        # ★ `--` 不能省：没有它，toybox nsenter 会把命令尾部的 `--port` 当成自己的选项
        # （真机原话 `nsenter: Unknown option 'port'`），DSH 根本没被拉起来 —— 而报错
        # 只落在 run/linux.log 里，用户看到的是"20s 内没有打印登录 URL"这种**误导性**结论。
        "$sid" "$NSENTER" --mount="/proc/$nspid/ns/mnt" --uts="/proc/$nspid/ns/uts" \
            -- chroot "$ROOTFS_DIR" /usr/bin/env -i \
            HOME=/root DSH_HOME=/root/.dsh \
            PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            TERM="${TERM:-xterm-256color}" LANG="${LANG:-C.UTF-8}" \
            /opt/sunsetlinux/supervise.sh --port "$port" >>"$LOGFILE" 2>&1 </dev/null &
    else
        "$NSENTER" --mount="/proc/$nspid/ns/mnt" --uts="/proc/$nspid/ns/uts" \
            -- chroot "$ROOTFS_DIR" /usr/bin/env -i \
            HOME=/root DSH_HOME=/root/.dsh \
            PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            TERM="${TERM:-xterm-256color}" LANG="${LANG:-C.UTF-8}" \
            /opt/sunsetlinux/supervise.sh --port "$port" >>"$LOGFILE" 2>&1 </dev/null &
    fi
    return 0
}

last_error_read() {
    [ -s "$ERROR_FILE" ] && cat "$ERROR_FILE" 2>/dev/null | head -n1
}

# 陈旧的 last-error 会让 status **永远**显示 error（明明问题已经解决）。
# 真机就踩过：设备侧建层失败留下 "缺少层文件 …"，后来层齐了、环境还没启动，
# App 的「更新」页顶部一直挂着"失败（文件/层缺失）"，用户以为装坏了。
# 这里只自愈**能当场证伪**的那几类：说缺层/缺可写层，而层与 upper.img 现在都在。
last_error_stale() {
    local e="" l=""
    e="$(last_error_read || true)"
    [ -n "$e" ] || return 1
    case "$e" in
        *缺少层文件*|*缺少可写层*|*缺少层*)
            for l in $LAYER_NAMES; do
                [ -n "$(find_layer "$l" 2>/dev/null || true)" ] || return 1
            done
            [ -f "$UPPER_IMG" ] || return 1
            return 0
            ;;
    esac
    return 1
}

# 自愈：清掉证伪得了的陈旧错误，并**留下痕迹**（不静默）
drop_stale_last_error() {
    last_error_stale || return 0
    log "清掉陈旧的 last-error（层与 upper.img 现在都在，那条已经不成立）"
    rm -f "$ERROR_FILE" 2>/dev/null || true
    return 0
}

# ---------------------------------------------------------------------------
# 收集 status 的全部字段到 DSH_ST_* 变量
# ---------------------------------------------------------------------------
gather_status() {
    drop_stale_last_error   # ★ 先在"状态汇报"这一层自愈陈旧错误，别让它一直挂着
    local pid="" state="stopped" port="" base_url="" url="" version="" healthy=""
    local ns_pid="" started_at="" pid_for_uptime=""
    local lv="" ls="" lm=""
    local up_used="" up_total=""

    # ---- state / pid ------------------------------------------------------
    if ns_pid="$(ns_pid_alive)"; then
        if env_ready; then
            state="running"
        else
            # 守护进程活着但 ready 没了：上次 stop 未完成 or 正在启动
            if [ -f "$RUN_DIR/stopping" ]; then state="stopping"; else state="starting"; fi
        fi
        if pid="$(dsh_pid_alive)"; then :; else pid="$ns_pid"; fi
    else
        # ★ 环境还没就绪，但**有人正在建树**（run/start.lock 且持锁进程活着）⇒ starting。
        #   以前这里只看 supervisor.pid，于是 30~50 秒的建树窗口被报成 stopped，
        #   App 的启动卡看起来"没在跑"，用户再点一次 → 两棵挂载树叠在一起。
        if start_lock_holder >/dev/null 2>&1; then
            state="starting"
        elif [ -s "$ERROR_FILE" ] && [ -f "$RUN_DIR/started" ]; then
            state="error"
        else
            state="stopped"
        fi
    fi
    # 有 last-error 且没有可用守护进程 → error（比 stopped 更准确地表达"坏掉了"）
    if [ "$state" = "stopped" ] && [ -s "$ERROR_FILE" ]; then
        state="error"
    fi

    # ★ 内核 v2 在线 ⇒ 相位**以它为准**（唯一状态源）。上面那套是它不在时的降级判据。
    local kphase=""
    if kphase="$(kernel_phase_v1)"; then
        state="$kphase"
    fi
    kernel_fill_status

    # ---- uptime -----------------------------------------------------------
    pid_for_uptime="$(dsh_pid_alive || true)"
    [ -z "$pid_for_uptime" ] && pid_for_uptime="$(ns_pid_alive || true)"
    if [ -n "$pid_for_uptime" ]; then
        started_at="$(dsh_proc_start_time "$pid_for_uptime" 2>/dev/null || true)"
    fi

    # ---- dsh.url / base_url / port ---------------------------------------
    port="$(dsh_read_port_file "$DSH_PORT_FILE" 2>/dev/null || true)"
    [ -z "$port" ] && port="$(config_port)"
    base_url="http://127.0.0.1:$port"
    if [ -f "$DSH_URL_FILE" ]; then
        url="$(head -n1 "$DSH_URL_FILE" 2>/dev/null | tr -d '\r\n')"
        # URL 里的端口才是实际端口
        if [ -n "$url" ]; then
            local up
            up="$(dsh_port_from_url "$url")"
            case "$up" in ''|*[!0-9]*) ;; *) port="$up"; base_url="http://127.0.0.1:$up" ;; esac
        fi
    fi

    # ---- version ----------------------------------------------------------
    # 优先 state.json 里记录的 dsh 层版本（= 已装版本），其次 rootfs 里的 package.json
    if [ -f "$STATE_JSON" ]; then
        version="$(dsh_layer_version "$STATE_JSON" dsh 2>/dev/null || true)"
    fi
    if [ -z "$version" ] && [ -f "$ROOTFS_DIR/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json" ]; then
        version="$(tr -d ' \n\t' < "$ROOTFS_DIR/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json" 2>/dev/null \
                   | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -n1)"
    fi

    # ---- layers -----------------------------------------------------------
    local name f
    for name in $LAYER_NAMES; do
        f=""
        [ -n "$(find_layer "$name" 2>/dev/null || true)" ] && f="$(find_layer "$name")"
        ls=""
        [ -n "$f" ] && ls="$(dsh_size_of "$f")"
        lv=""
        [ -f "$STATE_JSON" ] && lv="$(dsh_layer_version "$STATE_JSON" "$name" 2>/dev/null || true)"
        if [ -s "$RUN_DIR/mounted.json" ]; then
            case "$(tr -d ' \n' < "$RUN_DIR/mounted.json" 2>/dev/null)" in
                *"\"$name\":true"*) lm=true ;;
                *) lm=false ;;
            esac
        else
            lm=false
        fi
        case "$name" in
            base)    DSH_ST_LAYER_BASE_VERSION="$lv";    DSH_ST_LAYER_BASE_SIZE="$ls";    DSH_ST_LAYER_BASE_MOUNTED="$lm" ;;
            runtime) DSH_ST_LAYER_RUNTIME_VERSION="$lv"; DSH_ST_LAYER_RUNTIME_SIZE="$ls"; DSH_ST_LAYER_RUNTIME_MOUNTED="$lm" ;;
            dsh)     DSH_ST_LAYER_DSH_VERSION="$lv";     DSH_ST_LAYER_DSH_SIZE="$ls";     DSH_ST_LAYER_DSH_MOUNTED="$lm" ;;
        esac
    done

    # ---- 层模式（loop / dir）-----------------------------------------------
    # start.sh 启动时写 run/layer-mode；status 是另一个进程，只能靠文件知道。
    DSH_ST_LAYER_MODE=""
    if [ -f "$RUN_DIR/layer-mode" ]; then
        # 顺序要紧：`< file 2>/dev/null` 会把"文件不存在"的重定向错误漏到 stderr
        DSH_ST_LAYER_MODE="$(tr -d ' \n\r' < "$RUN_DIR/layer-mode" 2>/dev/null | head -c 16 || true)"
    fi

    # ---- storage ----------------------------------------------------------
    if [ -d "$UPPER_DIR" ] && mountpoint -q "$UPPER_DIR" 2>/dev/null; then
        local uu
        uu="$(dsh_upper_usage "$UPPER_DIR" 2>/dev/null || true)"
        up_used="${uu%% *}"
        up_total="${uu##* }"
        [ "$up_used" = "$up_total" ] && up_total=""
    fi
    if [ -z "$up_used" ] && [ -f "$UPPER_IMG" ]; then
        # 未挂载时给"表观容量/占用"，total 用镜像大小，used 取 null（不编造）
        up_total="$(dsh_size_of "$UPPER_IMG")"
    fi

    # ---- dsh 进程是否在跑 / 本次启动模式 -----------------------------------
    #   ★「环境在跑但没跑 DSH」是**一等状态**（App 的「仅启动环境」+ 终端维护、或 DSH 崩了），
    #     所以 status 必须能把它和"环境没跑"分开 —— 这是 App 按钮互斥判定的输入。
    #     dsh.running 是 §3.1 的**附加键**（契约允许；不改变任何既有键的语义）。
    DSH_ST_DSH_RUNNING="false"
    if [ "$state" = "running" ] && dsh_is_running >/dev/null 2>&1; then
        DSH_ST_DSH_RUNNING="true"
    fi
    DSH_ST_ENV_MODE=""
    case "$state" in
        running|starting) DSH_ST_ENV_MODE="$(env_mode_read || true)" ;;
    esac

    # ---- 组装 -------------------------------------------------------------
    DSH_ST_MODE="$MODE"
    DSH_ST_STATE="$state"
    DSH_ST_PID="$pid"
    DSH_ST_STARTED_AT="$started_at"
    DSH_ST_PORT="$port"
    DSH_ST_BASE_URL="$base_url"
    DSH_ST_URL="$url"
    DSH_ST_VERSION="$version"
    DSH_ST_UPPER_USED="$up_used"
    DSH_ST_UPPER_TOTAL="$up_total"
    DSH_ST_LAST_ERROR="$(last_error_read || true)"
    # healthy 由 status_json.sh 的 dsh_status_json_inputs 探测（只有 running 才探）
    unset DSH_ST_HEALTHY 2>/dev/null || true
    DSH_ST_UPTIME=""
    return 0
}

# ===========================================================================
# 子命令
# ===========================================================================

# ---------------------------------------------------------------------------
# provision [--seed <dir>]
#   幂等：目录树与 upper.img 只建一次；层文件缺失时从 seed 目录找。
#   若完整部署已存在 → 退出码 2（§3 契约）。
# ---------------------------------------------------------------------------
cmd_provision() {
    local seed=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --seed) seed="${2:-}"; shift 2 ;;
            *) shift ;;
        esac
    done

    local created=""
    for d in layers seeds cache etc bin run snapshots upper work rootfs; do
        if [ ! -d "$LH/$d" ]; then
            mkdir -p "$LH/$d" || { emit "{\"ok\":false,\"error\":\"无法创建 $LH/$d\"}"; return 1; }
            created="$created $d"
        fi
    done
    [ -n "$created" ] && log "新建目录：$created" || log "目录树已存在（幂等）"

    # 可写层镜像
    if [ ! -f "$UPPER_IMG" ]; then
        local size_mb="${UPPER_SIZE_MB:-8192}"
        log "创建可写层 upper.img（$(( size_mb / 1024 )) GiB 稀疏 ext4）"
        if ! make_upper_img "$size_mb"; then
            emit "{\"ok\":false,\"error\":\"创建 upper.img 失败（需要 mkfs.ext4，设备上一般由 provision 的 chroot 内 mke2fs 提供）\"}"
            return 1
        fi
    else
        log "upper.img 已存在（幂等，不重建；要重置用 reset）"
    fi

    # 层文件：已存在就不动；缺失则尝试从 seed 复制
    local missing="" name src
    for name in $LAYER_NAMES; do
        if ! find_layer "$name" >/dev/null 2>&1; then
            missing="$missing $name"
        fi
    done
    if [ -n "$missing" ]; then
        if [ -n "$seed" ] && [ -d "$seed" ]; then
            log "从 seed 目录补齐层：$seed"
            for name in $missing; do
                for src in "$seed/$name.erofs" "$seed/$name.squashfs"; do
                    [ -f "$src" ] || continue
                    cp -f "$src" "$LAYERS_DIR/$(basename "$src")" && { log "已复制 $(basename "$src")"; break; }
                done
            done
        fi
    fi

    # 复查
    local still=""
    for name in $LAYER_NAMES; do
        find_layer "$name" >/dev/null 2>&1 || still="$still $name"
    done

    # config.json / state.json（缺才写，幂等）
    [ -f "$CONFIG_JSON" ] || write_default_config
    [ -f "$STATE_JSON" ] || write_default_state

    local provisioned_all=true
    [ -n "$still" ] && provisioned_all=false

    if [ -n "$still" ]; then
        log "以下层仍缺失：$still"
        log "请由真 root 在设备上运行 device-provision.sh 原生生成（推荐路径），"
        log "或把预构建的层放到 $LAYERS_DIR/ 或 --seed 指定的目录。"
        # 注意：不要在 $( ) 里再嵌单引号（tr -d ' '），bash 无法在外层双引号内
        # 转义它，会解析失败（踩过，见交付报告）
        local missing_csv
        missing_csv="$(printf '%s' "$still" | tr -s " " "," | sed "s/^,//; s/,$//")"
        emit "{\"ok\":false,\"already\":false,\"linux_home\":\"$(jesc "$LH")\",\"missing_layers\":\"$(jesc "$missing_csv")\"}"
        return 1
    fi

    # 已完整部署过？→ 退出码 2（§3）
    if [ -f "$RUN_DIR/.provisioned" ]; then
        log "环境已部署过，本次仅补齐缺失项（幂等）"
        emit "{\"ok\":true,\"already\":true,\"linux_home\":\"$(jesc "$LH")\"}"
        return 2
    fi
    : > "$RUN_DIR/.provisioned"
    log "provision 完成"
    emit "{\"ok\":true,\"already\":false,\"linux_home\":\"$(jesc "$LH")\"}"
    return 0
}

# 创建稀疏 ext4 镜像（设备侧通常由 provision 的 chroot 内 mke2fs 完成）
make_upper_img() {
    local size_mb="$1"
    have truncate || return 1
    truncate -s "${size_mb}M" "$UPPER_IMG" || return 1
    local mk=""
    for mk in /system/bin/mke2fs /system/bin/mkfs.ext4 "$ROOTFS_DIR/sbin/mke2fs" \
              "$ROOTFS_DIR/sbin/mkfs.ext4" /usr/sbin/mkfs.ext4 /sbin/mkfs.ext4; do
        [ -x "$mk" ] || continue
        if "$mk" -F -q -t ext4 -m 0 -L sunsetlinux-upper "$UPPER_IMG" >/dev/null 2>&1; then
            return 0
        fi
    done
    rm -f "$UPPER_IMG"
    return 1
}

write_default_config() {
    local port="${SUNSETLINUX_PORT:-3080}"
    cat > "$CONFIG_JSON" <<EOF
{
  "schema": 1,
  "port": $port,
  "mode": "root",
  "autostart": true,
  "host": "127.0.0.1",
  "freeze_exempt": true,
  "log_max_bytes": 4194304,
  "note": "autostart=true 表示 KernelSU 模块 service.sh 在 late_start 会调用 linuxctl start；与 App 无关"
}
EOF
    log "已写 $CONFIG_JSON"
}

# 写 state.json（层版本 + 大小）。真机 provision 会覆盖成带 sha256/真实版本号的版本；
# 这里只保证文件存在且 JSON 合法，避免 App 读到坏 JSON。
write_default_state() {
    local name f sz ver first
    first=1
    {
        printf '{\n  "schema": 1,\n  "layers": {\n'
        for name in $LAYER_NAMES; do
            f="$(find_layer "$name" 2>/dev/null || true)"
            sz="null"; ver="null"
            if [ -n "$f" ]; then
                sz="$(dsh_size_of "$f")"
                [ -z "$sz" ] && sz="null"
                # 版本暂记文件名；真机 provision 会写真实版本号与 sha256
                ver="\"$(jesc "$(basename "$f")")\""
            fi
            [ "$first" = 1 ] || printf ',\n'
            printf '    "%s": { "version": %s, "size": %s, "sha256": null }' "$name" "$ver" "$sz"
            first=0
        done
        printf '\n  },\n  "generated_at": "%s",\n  "generator": "linuxctl provision"\n}\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    } > "$STATE_JSON"
    log "已写 $STATE_JSON"
}

# ---------------------------------------------------------------------------
# start —— 幂等
# ---------------------------------------------------------------------------
cmd_start() {
    # --layer-mode loop|dir：透传给 start.sh（docs/layer-mode.md 的三处开关之一）
    # --no-dsh：只起环境、不启动 DSH（App 的「仅启动环境」；见 docs/architecture.md §3.4）
    local lm_flag="" no_dsh=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --layer-mode)   lm_flag="${2:-}"; shift 2 ;;
            --layer-mode=*) lm_flag="${1#--layer-mode=}"; shift ;;
            --no-dsh)       no_dsh=1; shift ;;
            *) warnl "start: 忽略未知参数 $1"; shift ;;
        esac
    done
    case "${lm_flag:-}" in
        ""|loop|dir) : ;;
        *)
            local bad="未知的层模式：$lm_flag（只支持 loop / dir）"
            printf '%s\n' "$bad" > "$ERROR_FILE" 2>/dev/null || true
            emit "{\"schema\":1,\"state\":\"error\",\"last_error\":\"$(jesc "$bad")\"}"
            return 1
            ;;
    esac
    # 解析顺序与 start.sh 保持一致：命令行 > env > config.json > loop
    local lm="${lm_flag:-${SUNSETLINUX_LAYER_MODE:-}}"
    if [ -z "$lm" ] && [ -f "$CONFIG_JSON" ]; then
        lm="$(sed -n 's/.*"layer_mode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$CONFIG_JSON" 2>/dev/null | head -n1)"
    fi
    [ -n "$lm" ] || lm=loop
    SUNSETLINUX_LAYER_MODE="$lm"
    export SUNSETLINUX_LAYER_MODE

    # 已在运行：直接返回 running（§3 契约：幂等、退出码 0）
    #   ★ 唯一的例外是「两种方法互斥」（App 的按钮判定就是这条规则的 UI 面）：
    #     环境是按 --no-dsh（env-only）起来的，就不许再用"一键启动"悄悄把 DSH 塞进来 ——
    #     用户特意把环境单独起来做维护（更新层/装包），凭空多出一个占着 3080 的 DSH
    #     会让他分不清自己处于哪种模式。要起 DSH 就走 `linuxctl dsh start`，一步一个意图。
    if env_ready; then
        if [ "$no_dsh" = "1" ]; then
            log "环境已在运行（幂等；--no-dsh 不影响已运行的环境，run/env-mode=$(env_mode_read || true)）"
            gather_status
            emit "$(dsh_status_json)"
            return 0
        fi
        if [ "$(env_mode_read || true)" = "env-only" ]; then
            local msg="环境是按「仅启动环境」起的（run/env-mode=env-only）：要起 DSH 请用「启动 DSH」（linuxctl dsh start），或先「停止环境」再「一键启动」"
            printf '%s\n' "$msg" > "$ERROR_FILE" 2>/dev/null || true
            log "ERROR: $msg"
            gather_status
            DSH_ST_LAST_ERROR="$msg"
            emit "$(dsh_status_json)"
            return 1
        fi
        log "环境已在运行（幂等）"
        gather_status
        emit "$(dsh_status_json)"
        return 0
    fi

    mkdir -p "$RUN_DIR" || { emit "{\"schema\":1,\"state\":\"error\",\"last_error\":\"无法创建 $RUN_DIR\"}"; return 1; }
    rm -f "$ERROR_FILE" 2>/dev/null || true
    : > "$RUN_DIR/started" 2>/dev/null || true

    # 前置：层文件与 upper.img
    local name
    for name in $LAYER_NAMES; do
        if ! find_layer "$name" >/dev/null 2>&1; then
            local msg="缺少层文件 $LAYERS_DIR/$name.{erofs,squashfs}"
            printf '%s\n' "$msg" > "$ERROR_FILE"
            log "ERROR: $msg"
            emit "{\"schema\":1,\"state\":\"error\",\"last_error\":\"$(jesc "$msg")\"}"
            return 1
        fi
    done
    if [ "$lm" != "dir" ]; then
        [ -f "$UPPER_IMG" ] || {
            local msg="缺少可写层 $UPPER_IMG（先 provision；或用 --layer-mode dir）"
            printf '%s\n' "$msg" > "$ERROR_FILE"; log "ERROR: $msg"
            emit "{\"schema\":1,\"state\":\"error\",\"last_error\":\"$(jesc "$msg")\"}"
            return 1
        }
    fi

    local starter="$SELF_DIR/start.sh"
    [ -x "$starter" ] || [ -f "$starter" ] || {
        local msg="找不到 $starter"
        printf '%s\n' "$msg" > "$ERROR_FILE"; log "ERROR: $msg"
        emit "{\"schema\":1,\"state\":\"error\",\"last_error\":\"$(jesc "$msg")\"}"
        return 1
    }

    log "调用 start.sh（stdout 已丢弃，只保留 JSON 通道）"
    local dsh_opt=""
    [ "$no_dsh" = "1" ] && dsh_opt="--no-dsh"
    if ! LINUX_HOME="$LH" SUNSETLINUX_LAYER_MODE="$lm" SUNSETLINUX_NO_DSH="$no_dsh" \
            "$SH_BIN" "$starter" --layer-mode "$lm" $dsh_opt >/dev/null 2>>"$LOGFILE"; then
        local msg
        msg="$(last_error_read || true)"
        [ -z "$msg" ] && msg="start.sh 失败，详见 $LOGFILE"
        printf '%s\n' "$msg" > "$ERROR_FILE"
        log "ERROR: $msg"
        gather_status
        DSH_ST_STATE="error"
        DSH_ST_LAST_ERROR="$msg"
        emit "$(dsh_status_json)"
        return 1
    fi

    gather_status
    # start.sh 成功后 ready 一定存在；若 dsh 还在打印 URL，url 字段允许为 null
    emit "$(dsh_status_json)"
    return 0
}

# ---------------------------------------------------------------------------
# stop —— 幂等，按相反顺序整洁卸载，杀掉 supervisor
# ---------------------------------------------------------------------------
cmd_stop() {
    mkdir -p "$RUN_DIR" 2>/dev/null || true
    : > "$RUN_DIR/stopping" 2>/dev/null || true

    local stopper="$SELF_DIR/stop.sh"
    if [ -f "$stopper" ]; then
        LINUX_HOME="$LH" "$SH_BIN" "$stopper" >/dev/null 2>>"$LOGFILE" || warnl "stop.sh 返回非 0（继续做收尾清理）"
    else
        warnl "找不到 $stopper，退化为内建停止逻辑"
        builtin_stop
    fi

    rm -f "$RUN_DIR/stopping" "$READY_FILE" "$DSH_URL_FILE" "$DSH_PORT_FILE" "$DSH_PID_FILE" \
          "$SUPERVISOR_PID_FILE" "$RUN_DIR/started" 2>/dev/null || true
    # 卸载状态收尾
    rm -f "$RUN_DIR/mounted.json" 2>/dev/null || true
    : > "$MOUNTS_FILE" 2>/dev/null || true

    log "已停止（幂等）"
    gather_status
    DSH_ST_STATE="stopped"
    emit "$(dsh_status_json)"
    return 0
}

# stop.sh 不存在时的兜底：尽力卸载 + 杀守护进程
builtin_stop() {
    local nspid=""
    if nspid="$(ns_pid_alive)"; then
        if [ -x "$NSENTER" ]; then
            local rel
            for rel in mnt/sdcard run tmp dev/shm dev/pts dev sys proc; do
                # `--` 之后才是 umount 与它的 `-l`：toybox nsenter 会把 `-l` 当成自己的选项，
                # 没有 `--` 时**卸载根本没执行**（真机上"残留挂载"反复出现的根因之一）。
                "$NSENTER" --mount="/proc/$nspid/ns/mnt" -- "$UMOUNT" -l "$ROOTFS_DIR/$rel" 2>/dev/null || true
            done
            "$NSENTER" --mount="/proc/$nspid/ns/mnt" -- "$UMOUNT" -l "$ROOTFS_DIR" 2>/dev/null || true
            "$NSENTER" --mount="/proc/$nspid/ns/mnt" -- "$UMOUNT" -l "$UPPER_DIR" 2>/dev/null || true
        fi
        for rel in layers-mnt/dsh layers-mnt/runtime layers-mnt/base; do
            "$UMOUNT" -l "$LH/$rel" 2>/dev/null || true
        done
        kill -TERM "$nspid" 2>/dev/null || true
        local i=0
        while [ "$i" -lt 50 ]; do kill -0 "$nspid" 2>/dev/null || break; sleep 0.1; i=$((i+1)); done
        kill -KILL "$nspid" 2>/dev/null || true
    fi
    # 杀死可能残留的 dsh
    local dpid=""
    if dpid="$(dsh_pid_alive)"; then kill -TERM "$dpid" 2>/dev/null || true; fi
    return 0
}

# ---------------------------------------------------------------------------
# status —— 严格 §3.1
# ---------------------------------------------------------------------------
cmd_status() {
    gather_status
    emit "$(dsh_status_json)"
    return 0
}

# ---------------------------------------------------------------------------
# attach / exec —— nsenter 进环境的 mount+UTS ns，再 chroot
#   no PID ns（内核实测无 CONFIG_PID_NS），所以只能靠 nsenter 拿 mount/uts。
#
# ⚠️ **不要给 nsenter 传 cwd 选项**（这里历史上有过 `--wd="$ROOTFS_DIR"`）。
#   设备上的 nsenter 是 **toybox**（0.8.12-android，实测 `/system/bin/nsenter --help`）：
#   它**根本没有 cwd 选项** —— `--wd=X` / `--wd X` / `-w X` 全是
#   `nsenter: Unknown option 'wd=…'`（真机原话：`Unknown option 'wd=/data/sunsetlinux/rootfs'`），
#   于是 attach / exec 在这台机上**必然失败**（2026-09-17 真机两处报错）。
#   cwd 也不需要它：`chroot NEWROOT` 自己会 chdir 进新根（实测：从 /tmp 起
#   `chroot <rootfs> /usr/bin/env -i /bin/pwd` 打印 `/`），而这正是唯一的要求
#   （chroot 后 cwd 必须在环境内，否则 cwd 悬空、相对路径与 pwd 全废）。
#   另一条 toybox 约束：ns 参数必须写成**等号**形式 `--mount=/path`（`-m=/path` 亦可），
#   空格分隔会被判 `need -t or =filename`。
#   闸门：tools/shell-compat-check.mjs 的「nsenter 选项面」检查（对照设备 `nsenter --help`）。
# ---------------------------------------------------------------------------
run_in_env() {
    local interactive="$1"; shift
    local nspid=""
    if ! nspid="$(ns_pid_alive)"; then
        log "ERROR: 环境未运行（没有可用的 mount namespace）"
        return 1
    fi
    # 要执行的命令：改用**位置参数**承载，避免 bash 数组（设备侧是 Android mksh）。
    # 无参数时默认进交互 shell（与旧行为一致）。
    [ $# -gt 0 ] || set -- /bin/bash

    if [ -x "$NSENTER" ]; then
        # --mount/--uts 进入环境 ns；chroot 用宿主二进制（静态路径已在 ns 内可见）
        if [ "$interactive" = 1 ]; then
            exec "$NSENTER" --mount="/proc/$nspid/ns/mnt" --uts="/proc/$nspid/ns/uts" \
                 -- chroot "$ROOTFS_DIR" /usr/bin/env -i \
                 HOME=/root DSH_HOME=/root/.dsh \
                 PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                 TERM="${TERM:-xterm-256color}" LANG="${LANG:-C.UTF-8}" \
                 "$@"
        else
            "$NSENTER" --mount="/proc/$nspid/ns/mnt" --uts="/proc/$nspid/ns/uts" \
                 -- chroot "$ROOTFS_DIR" /usr/bin/env -i \
                 HOME=/root DSH_HOME=/root/.dsh \
                 PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                 TERM="${TERM:-xterm-256color}" LANG="${LANG:-C.UTF-8}" \
                 "$@"
        fi
        return $?
    fi

    # nsenter 不可用：退化为直接 chroot（挂载已经在，但没有 ns 隔离）
    warnl "nsenter 不可用，退化为直接 chroot（不推荐）"
    if [ "$interactive" = 1 ]; then
        exec chroot "$ROOTFS_DIR" /usr/bin/env -i HOME=/root DSH_HOME=/root/.dsh \
             PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
             "$@"
    fi
    chroot "$ROOTFS_DIR" /usr/bin/env -i HOME=/root DSH_HOME=/root/.dsh \
         PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
         "$@"
    return $?
}

cmd_attach() {
    # 去掉可选的前导 --；无参数 → 交互 shell
    if [ $# -gt 0 ] && [ "$1" = "--" ]; then shift; fi
    run_in_env 1 "$@"
}

cmd_exec() {
    if [ $# -gt 0 ] && [ "$1" = "--" ]; then shift; fi
    [ $# -gt 0 ] || { log "exec 需要一个命令（exec -- cmd...）"; return 2; }
    run_in_env 0 "$@"
}

# ---------------------------------------------------------------------------
# logs [-n N]
# ---------------------------------------------------------------------------
cmd_logs() {
    local n=200
    while [ $# -gt 0 ]; do
        case "$1" in
            -n) n="${2:-200}"; shift 2 ;;
            [0-9]*) n="$1"; shift ;;
            *) shift ;;
        esac
    done
    case "$n" in ''|*[!0-9]*) n=200 ;; esac
    if [ ! -f "$LOGFILE" ]; then
        emit "{\"ok\":false,\"error\":\"日志文件不存在：$LOGFILE\"}"
        return 0
    fi
    # 日志本身是人类可读内容，但本子命令的 stdout 就是"日志尾部"（§3 未要求 JSON）
    tail -n "$n" "$LOGFILE"
    return 0
}

# ---------------------------------------------------------------------------
# snapshot <name> —— 把可写层打包到 snapshots/<name>.tar.zst
#   注意：为了一致性，先把 upper 目录 umount 后按镜像打包更安全，但那要求先 stop。
#   这里采用"stop → 按镜像 tar → start 回去"的稳妥路线，并在日志里说明。
# ---------------------------------------------------------------------------
cmd_snapshot() {
    local name="${1:-}"
    [ -n "$name" ] || { log "用法：linuxctl snapshot <name>"; emit '{"ok":false,"error":"缺少快照名"}'; return 1; }
    case "$name" in */*|.|..) log "快照名不能含路径分隔符"; emit '{"ok":false,"error":"非法快照名"}'; return 1 ;; esac
    [ -f "$UPPER_IMG" ] || { emit '{"ok":false,"error":"upper.img 不存在"}'; return 1; }
    mkdir -p "$SNAP_DIR" || { emit '{"ok":false,"error":"无法创建 snapshots 目录"}'; return 1; }

    local was_running=false
    env_ready && was_running=true
    # 记住本次起法（stop 之后 run/env-mode 就被清掉了）：env-only 的维护会话重启后
    # 不该被塞回一个 DSH（见 resume_start_like_before）
    local was_mode=""
    [ "$was_running" = true ] && was_mode="$(env_mode_read || true)"
    if [ "$was_running" = true ]; then
        log "为保证快照一致，先停止环境（完成后自动恢复）"
        cmd_stop >/dev/null || true
    fi

    # --- 关键：先把 upper.img 瘦身 -----------------------------------------
    # upper.img 是 8 GiB **稀疏** ext4，实际可能只用了几百 MB。
    # 如果直接 tar（即使带 --sparse），也要读满 8 GiB 地址空间；不带 --sparse 时
    # tar 会把空洞写成真实 0 字节 → 8 GiB 全量读，实测**会卡死数十秒到分钟级**。
    # 所以：umount → e2fsck → 用 dumpe2fs/resize2fs 把镜像收缩到实际占用大小。
    shrink_image || warnl "瘦身失败（继续按原镜像打包，用 --sparse 跳过空洞）"

    local out="$SNAP_DIR/$name.tar.zst"
    local comp="zstd"
    if ! have zstd; then comp="gzip"; out="$SNAP_DIR/$name.tar.gz"; fi

    log "打包可写层：$UPPER_IMG -> $out（压缩器 $comp，--sparse 跳过空洞）"
    local rc=0
    if ! tar --sparse -cf - -C "$(dirname "$UPPER_IMG")" "$(basename "$UPPER_IMG")" 2>/dev/null \
            | { if [ "$comp" = zstd ]; then zstd -q -T0 -o "$out"; else gzip -9 > "$out"; fi; }; then
        rc=1
    fi
    if [ "$rc" -ne 0 ]; then
        rm -f "$out"
        emit '{"ok":false,"error":"打包失败（详见日志）"}'
        [ "$was_running" = true ] && resume_start_like_before "$was_mode"
        return 1
    fi
    local sz; sz="$(dsh_size_of "$out")"
    local usize; usize="$(dsh_size_of "$UPPER_IMG")"
    emit "{\"ok\":true,\"name\":\"$(jesc "$name")\",\"path\":\"$(jesc "$out")\",\"size\":$( [ -n "$sz" ] && printf '%s' "$sz" || printf 'null'),\"upper_size\":$( [ -n "$usize" ] && printf '%s' "$usize" || printf 'null'),\"compression\":\"$(jesc "$comp")\"}"
    [ "$was_running" = true ] && resume_start_like_before "$was_mode"
    return 0
}

# 把 upper.img 收缩到实际占用（尽量）。任何一步不可用就返回 1，由调用方决定继续。
shrink_image() {
    # 必须先卸载（e2fsck/resize2fs 都要求未挂载）
    if mountpoint -q "$UPPER_DIR" 2>/dev/null; then
        "$UMOUNT" -l "$UPPER_DIR" 2>/dev/null || "$UMOUNT" -f "$UPPER_DIR" 2>/dev/null || return 1
    fi

    local fsck=""
    for c in /system/bin/e2fsck "$ROOTFS_DIR/sbin/e2fsck" "$ROOTFS_DIR/usr/sbin/e2fsck" e2fsck; do
        [ -x "$c" ] && { fsck="$c"; break; }
    done
    [ -n "$fsck" ] || { warnl "无 e2fsck，跳过瘦身"; return 1; }
    # -f 强制检查，-y 自动修；对**未挂载**的镜像文件是安全操作
    "$fsck" -f -y "$UPPER_IMG" >/dev/null 2>&1 || {
        warnl "e2fsck 报告问题（镜像可能不一致）；仍继续尝试瘦身"
    }

    local new_mb=""
    # 路线 A：resize2fs -M（把 fs 缩到最小）
    local rsz=""
    for c in "$ROOTFS_DIR/sbin/resize2fs" "$ROOTFS_DIR/usr/sbin/resize2fs" resize2fs; do
        [ -x "$c" ] && { rsz="$c"; break; }
    done
    if [ -n "$rsz" ]; then
        if "$rsz" -M "$UPPER_IMG" >/dev/null 2>&1; then
            new_mb="$("$rsz" "$UPPER_IMG" 2>/dev/null | sed -n 's/.*: \([0-9][0-9]*\)\/[0-9]* blocks.*/\1/p')"
        fi
    fi
    # 路线 B：dumpe2fs 算"最后一个非空 block 组"的结束位置
    if [ -z "$new_mb" ]; then
        local dump=""
        for c in "$ROOTFS_DIR/sbin/dumpe2fs" "$ROOTFS_DIR/usr/sbin/dumpe2fs" dumpe2fs; do
            [ -x "$c" ] && { dump="$c"; break; }
        done
        [ -n "$dump" ] || return 1
        local info last_block bsize
        info="$("$dump" "$UPPER_IMG" 2>/dev/null)" || return 1
        bsize="$(printf '%s' "$info" | awk '/^Block size:/{print $3; exit}')"
        last_block="$(printf '%s' "$info" | awk '
            /^ *[0-9]+: [0-9]+\/[0-9]+/ { gsub(":","",$1); if (($1+0) > m) m=$1 }
            END { print m+0 }')"
        case "$bsize" in ''|*[!0-9]*) bsize=4096 ;; esac
        [ "${last_block:-0}" -gt 0 ] 2>/dev/null || return 1
        # 最后一个非空组的**起点** + 一个组的大小，留足余量
        local grp_blocks
        grp_blocks="$(printf '%s' "$info" | awk '/^Blocks per group:/{print $4; exit}')"
        case "$grp_blocks" in ''|*[!0-9]*) grp_blocks=32768 ;; esac
        new_mb=$(( (last_block + grp_blocks + 1024) * bsize / 1048576 + 8 ))
    fi
    [ -n "$new_mb" ] || return 1
    [ "$new_mb" -lt 64 ] 2>/dev/null && new_mb=64

    local cur_mb
    # ★ 同上：字节数可能超过 2^31（8 GiB 的 upper.img 就是），mksh 的 32 位算术会直接环绕，
    #   于是 cur_mb 变成 0、"已是最小"被误判。用 awk 做这个除法（双精度，安全）。
    cur_mb="$(awk -v b="$(dsh_size_of "$UPPER_IMG" || echo 0)" 'BEGIN{printf "%d", b/1048576}')"
    if [ "$new_mb" -ge "$cur_mb" ] 2>/dev/null; then
        log "镜像已是最小（$cur_mb MiB），无需收缩"
        return 0
    fi
    if truncate -s "${new_mb}M" "$UPPER_IMG" 2>/dev/null; then
        log "已把 upper.img 从 $cur_mb MiB 收缩到 $new_mb MiB（打包量随之大幅下降）"
        # 收缩后镜像**容量**变小，下次 overlay 挂载仍可用（文件系统本身已 resize2fs -M 过）
        return 0
    fi
    return 1
}

# ---------------------------------------------------------------------------
# restore <name>
# ---------------------------------------------------------------------------
cmd_restore() {
    local name="${1:-}"
    [ -n "$name" ] || { log "用法：linuxctl restore <name>"; emit '{"ok":false,"error":"缺少快照名"}'; return 1; }
    local src=""
    for cand in "$SNAP_DIR/$name.tar.zst" "$SNAP_DIR/$name.tar.gz"; do
        [ -f "$cand" ] && { src="$cand"; break; }
    done
    [ -n "$src" ] || { emit "{\"ok\":false,\"error\":\"找不到快照 $name\"}"; return 1; }

    local was_running=false
    env_ready && was_running=true
    local was_mode=""
    [ "$was_running" = true ] && was_mode="$(env_mode_read || true)"
    [ "$was_running" = true ] && { log "先停止环境"; cmd_stop >/dev/null || true; }

    local tmp="$SNAP_DIR/.restore.$$"
    mkdir -p "$tmp"
    local rc=0
    # -S/--sparse：必须加。快照用 --sparse 打包，不加 -S 解包会把 8 GiB 空洞
    # 写成真实 0 字节（慢且占满磁盘）。
    case "$src" in
        *.tar.zst) have zstd || { log "ERROR: 需要 zstd 解压 $src"; rm -rf "$tmp"; emit '{"ok":false,"error":"缺少 zstd"}'; return 1; }
                   zstd -dc "$src" | tar --sparse -xf - -C "$tmp" || rc=1 ;;
        *)         tar --sparse -xzf "$src" -C "$tmp" || rc=1 ;;
    esac
    if [ "$rc" -ne 0 ] || [ ! -f "$tmp/upper.img" ]; then
        rm -rf "$tmp"
        emit '{"ok":false,"error":"解包失败或快照内没有 upper.img"}'
        return 1
    fi
    # 原子替换：先写 .tmp 再 rename
    if mv -f "$tmp/upper.img" "$UPPER_IMG.new" && mv -f "$UPPER_IMG.new" "$UPPER_IMG"; then
        log "已恢复 $UPPER_IMG <- $src"
    else
        rm -rf "$tmp"; emit '{"ok":false,"error":"替换 upper.img 失败"}'; return 1
    fi
    rm -rf "$tmp"
    emit "{\"ok\":true,\"name\":\"$(jesc "$name")\",\"path\":\"$(jesc "$src")\"}"
    [ "$was_running" = true ] && resume_start_like_before "$was_mode"
    return 0
}

# ---------------------------------------------------------------------------
# reset —— 清空可写层（恢复出厂）
#   做法：删掉旧镜像 → 新建同尺寸空 ext4。**不动只读层**（它们才是"出厂"）。
# ---------------------------------------------------------------------------
cmd_reset() {
    env_ready && { log "先停止环境"; cmd_stop >/dev/null || true; }
    if [ ! -f "$UPPER_IMG" ]; then
        emit '{"ok":false,"error":"upper.img 不存在，无需 reset（请先 provision）"}'
        return 1
    fi
    local size_mb="${UPPER_SIZE_MB:-8192}"
    # 保留旧镜像做兜底（.resetbak），成功后再删
    log "重置可写层：删除并重建 $UPPER_IMG（$(( size_mb / 1024 )) GiB 稀疏）"
    if ! mv -f "$UPPER_IMG" "$UPPER_IMG.resetbak" 2>/dev/null; then
        emit '{"ok":false,"error":"无法移走旧 upper.img（可能仍被挂载，先 stop 并重试）"}'
        return 1
    fi
    if ! make_upper_img "$size_mb"; then
        mv -f "$UPPER_IMG.resetbak" "$UPPER_IMG" 2>/dev/null || true
        emit '{"ok":false,"error":"重建 upper.img 失败，已回滚"}'
        return 1
    fi
    rm -f "$UPPER_IMG.resetbak"
    rm -f "$ERROR_FILE" "$READY_FILE" "$RUN_DIR/mounted.json" 2>/dev/null || true
    emit "{\"ok\":true,\"reset\":true,\"upper_img\":\"$(jesc "$UPPER_IMG")\",\"size_bytes\":$(dsh_size_of "$UPPER_IMG" 2>/dev/null || echo null)}"
    return 0
}

# ---------------------------------------------------------------------------
# update <layer> <file> —— 原子替换某层并重启环境
# ---------------------------------------------------------------------------
cmd_update() {
    local layer="" file="" version=""
    # 参数：update <layer> <file> [--version <ver>]
    #   --version 由 App 从频道清单的 layers[].version 传入（layer-spec §5 的语义版本）。
    #   缺了它就无法按版本落盘 → find_layer 的"state.json 版本优先"永远走不到，
    #   回滚语义也会失效（旧版被同名覆盖）。所以缺失只做**告警降级**，不静默。
    while [ $# -gt 0 ]; do
        case "$1" in
            --version) version="${2:-}"; shift 2 ;;
            --version=*) version="${1#--version=}"; shift ;;
            -*) log "未知选项：$1"; emit '{"ok":false,"error":"未知选项"}'; return 1 ;;
            *)
                if [ -z "$layer" ]; then layer="$1"
                elif [ -z "$file" ]; then file="$1"
                else log "多余参数：$1"; fi
                shift ;;
        esac
    done
    case "$layer" in
        base|runtime|dsh) ;;
        *) log "用法：linuxctl update <base|runtime|dsh> <file> [--version <ver>]"
           emit '{"ok":false,"error":"layer 必须是 base/runtime/dsh"}'; return 1 ;;
    esac
    [ -n "$file" ] || { emit '{"ok":false,"error":"缺少层文件参数"}'; return 1; }
    [ -f "$file" ] || { emit "{\"ok\":false,\"error\":\"文件不存在：$(jesc "$file")\"}"; return 1; }

    # ---- 格式判定：偏移 0 查 squashfs，偏移 1024 查 erofs ----
    # 压缩产物（.zst/.gz）会落到 unknown 并在这里被拒 —— 这是**正确**行为，
    # 解压是客户端（App）的职责。
    local fmt=""
    fmt="$(layer_format "$file" 2>/dev/null || echo unknown)"
    if [ "$fmt" != "erofs" ] && [ "$fmt" != "squashfs" ]; then
        local h0 h1024
        h0="$(_magic_at "$file" 0)"
        h1024="$(_magic_at "$file" 1024)"
        log "ERROR: $file 不是 erofs/squashfs 镜像（offset0=$h0 offset1024=$h1024）"
        log "       提示：如果是 .erofs.zst/.gz，请先解压成裸 .erofs 再 update（解压由 App 负责）"
        emit '{"ok":false,"error":"格式不可识别"}'
        return 1
    fi

    # 必须挑内核支持的格式（本机内核实测不支持 squashfs）。
    # ★ 三态处理，不能把"读不到"当成"不支持"：
    #     · /proc/filesystems 可读且含该格式 → 放行
    #     · 可读但不含                        → **拒绝**（真不支持）
    #     · 不可读（受限上下文 / 容器 / 沙箱）→ **放行并告警**（信息不可得≠不支持）
    #   这一条是被真实场景逼出来的：某次运行里 /proc/filesystems 变成 Permission denied，
    #   旧写法把"读不到"当成"内核不支持 erofs"，于是**合法的 erofs 层被拒收**。
    #
    # ★ 第四态：**显式测试开关** `LINUXCTL_KERNEL_FS_OVERRIDE=<格式…>`
    #   在"内核确实没有该格式"的环境里（例如 GitHub runner 的内核没有 erofs）跑集成测试用。
    #   为什么需要它：`selftest.sh` 有一组断言要验证"合法 erofs 被接受 → 按 layer-spec 命名
    #   落盘 → state.json 写版本 → find_layer 版本优先 → rollback"，这些是**我们自己的逻辑**，
    #   跟宿主内核有没有 erofs 无关。没有这个开关，CI 上就只能跳过它们（等于测不到）。
    #   它只影响这道**用户态预检**：真挂不上时 start.sh 仍会失败，不存在"骗过挂载"。
    local fssup="" fslist="" fs_readable=1 found=0 fsline fslast
    case " ${LINUXCTL_KERNEL_FS_OVERRIDE:-} " in
        *" $fmt "*)
            warnl "LINUXCTL_KERNEL_FS_OVERRIDE 指定跳过内核格式检查（仅测试用）；真机请勿设置"
            fssup="$fmt" ;;
    esac
    fslist="$(cat /proc/filesystems 2>/dev/null)" || fs_readable=0
    if [ -s "$fslist" ]; then fs_readable=1; fi
    if [ -z "$fssup" ] && [ "$fs_readable" = "1" ] && [ -n "$fslist" ]; then
        # ★ 整词匹配：/proc/filesystems 的行是 "<TAB>erofs" / "nodev<TAB>sysfs"，
        #   类型名是**行内最后一个词**。早先用 "*\n$fmt\n*" 匹配会因行首 TAB **永远失败**，
        #   把合法的 erofs 判成"内核不支持"（实测：那一行是 `^Ierofs$`）。
        #   纯 bash 逐行取末词：无子进程、不会触发 grep -q + pipefail 的 SIGPIPE 假阴性。
        while IFS= read -r fsline; do
            fslast="${fsline##*[[:space:]]}"
            [ "$fslast" = "$fmt" ] && { found=1; break; }
        done <<< "$fslist"
        [ "$found" = "1" ] && fssup="$fmt"
        if [ -z "$fssup" ]; then
            local msg="内核不支持 $fmt（/proc/filesystems 里没有）。本机内核实测 CONFIG_SQUASHFS 未启用，请用 erofs 层"
            log "ERROR: $msg"
            emit "{\"ok\":false,\"error\":\"$(jesc "$msg")\"}"
            return 1
        fi
    else
        warnl "读不到 /proc/filesystems（受限上下文），跳过内核格式支持性检查；层最终能否挂载由 start.sh 判定"
    fi

    # ---- 版本与落点 ----
    local ver_note=""
    if [ -z "$version" ]; then
        # 尝试从 state.json 继承当前版本（至少保持一致，不再产生无版本文件）
        version="$(dsh_layer_version "$STATE_JSON" "$layer" 2>/dev/null || true)"
        case "$version" in ""|unknown|null) version="" ;; esac
        if [ -n "$version" ]; then
            log "未显式给 --version，沿用 state.json 里的当前版本：$version"
        fi
    fi
    local dst tmpsrc ver_used
    if [ -n "$version" ]; then
        ver_used="$version"
        dst="$LAYERS_DIR/$(layer_file_name "$layer" "$ver_used")"
    else
        # 降级：无版本名。告警说明后果（App/频道必须传 version）
        ver_used=""
        dst="$LAYERS_DIR/$layer.$fmt"
        ver_note="未提供 --version 且 state.json 无记录：落盘为 $(basename "$dst")（无版本名），"
        ver_note="$ver_note 无法按版本定位，回滚语义不可用"
        log "WARN: $ver_note"
    fi
    tmpsrc="$LAYERS_DIR/.$layer.$(basename "$dst").tmp"

    # 可选 sha256 校验（给了 LINUXCTL_EXPECT_SHA256 才校验）
    local new_sha=""
    if have sha256sum; then
        new_sha="$(sha256sum "$file" 2>/dev/null | awk '{print $1}')"
    fi
    if [ -n "${LINUXCTL_EXPECT_SHA256:-}" ] && [ -n "$new_sha" ]; then
        if [ "$new_sha" != "$LINUXCTL_EXPECT_SHA256" ]; then
            log "ERROR: sha256 不匹配（期望 $LINUXCTL_EXPECT_SHA256，实际 $new_sha）"
            emit '{"ok":false,"error":"sha256 不匹配"}'
            return 1
        fi
    fi

    # ---- 记录"上一个生效层"，供启动失败时回滚 ----
    # 注意：同层多版本**并存**，不删旧文件（回滚要用）。
    local prev_path=""
    prev_path="$(find_layer "$layer" 2>/dev/null || true)"
    if [ "$prev_path" = "$dst" ]; then
        prev_path=""   # 同文件覆盖，无需回滚副本
    fi

    # 复制新层（先写临时名再原子 rename）
    if ! cp -f "$file" "$tmpsrc" 2>/dev/null; then
        rm -f "$tmpsrc"
        emit '{"ok":false,"error":"写入新层失败（空间不足？）"}'
        return 1
    fi
    if ! mv -f "$tmpsrc" "$dst"; then
        rm -f "$tmpsrc"
        emit '{"ok":false,"error":"原子替换层文件失败"}'
        return 1
    fi
    log "已安装 $dst（格式 $fmt${ver_used:+，版本 $ver_used}）"

    # ---- 写 state.json（必须在重启**之前**写：find_layer 依赖它定位）----
    write_state_for_layer "$layer" "$ver_used" "$dst" "$new_sha" "$fmt"

    # ---- 重启环境让新层生效 ----
    local was_running=false
    env_ready && was_running=true
    # env-only 起的维护会话：更新层之后仍保持"只环境"（见 resume_start_like_before）
    local was_mode=""
    [ "$was_running" = true ] && was_mode="$(env_mode_read || true)"
    if [ "$was_running" = true ]; then
        log "重启环境以应用新层"
        cmd_stop >/dev/null 2>&1 || true
        if ! resume_start_like_before "$was_mode"; then
            log "ERROR: 新层启动失败，回滚"
            local rolled=false
            if [ -n "$prev_path" ] && [ -f "$prev_path" ]; then
                # 回滚 = 把 state.json 指回旧版本（旧文件仍在，不覆盖新文件）
                local pver pfmt
                pver="$(basename "$prev_path")"
                pver="${pver#$layer-}"; pver="${pver%.*}"
                pfmt="$(layer_format "$prev_path" 2>/dev/null || echo "$fmt")"
                write_state_for_layer "$layer" "$pver" "$prev_path" \
                    "$(dsh_layer_sha256 "$STATE_JSON" "$layer" 2>/dev/null || true)" "$pfmt"
                # 若新层是无版本名落盘，回滚时直接删掉它
                [ -z "$ver_used" ] && [ "$dst" != "$prev_path" ] && rm -f "$dst" 2>/dev/null || true
                rolled=true
                log "已回滚到 $prev_path"
            fi
            resume_start_like_before "$was_mode" || true
            emit "{\"ok\":false,\"error\":\"新层启动失败$([ "$rolled" = true ] && echo '，已回滚' || echo '，且无法回滚')\",\"layer\":\"$(jesc "$layer")\"}"
            return 1
        fi
    fi

    emit "{\"ok\":true,\"layer\":\"$(jesc "$layer")\",\"file\":\"$(jesc "$dst")\",\"version\":$( [ -n "$ver_used" ] && printf '"%s"' "$(jesc "$ver_used")" || printf 'null'),\"format\":\"$(jesc "$fmt")\",\"sha256\":$( [ -n "$new_sha" ] && printf '"%s"' "$new_sha" || printf 'null'),\"restarted\":$was_running${ver_note:+,\"warning\":\"$(jesc "$ver_note")\"}}"
    return 0
}

# ---------------------------------------------------------------------------
# write_state_for_layer <id> <ver> <path> <sha256> <format>
#   合并式写 state.json：只动 <id> 那一项，其余层原样保留。
#   口径与 device-provision.sh 完全一致（含 spec / erofs_compress / transport_compress）。
#   为什么必须由 update 写：find_layer 优先按 state.json 的 version 定位文件，
#   state.json 不写就会退化成"挑版本号最高"，回滚语义随之失效。
# ---------------------------------------------------------------------------
write_state_for_layer() {
    local id="$1" ver="$2" path="$3" sha="$4" fmt="$5"
    mkdir -p "$ETC_DIR" 2>/dev/null || true
    local size="null"
    [ -f "$path" ] && size="$(dsh_size_of "$path")"
    [ -n "$size" ] || size="null"
    local file base
    file="$(basename "$path")"
    local tname=""
    if [ -n "$ver" ] && command -v layer_transport_name >/dev/null 2>&1; then
        tname="$(layer_transport_name "$id" "$ver" 2>/dev/null || true)"
    fi

    local tmp="$STATE_JSON.tmp.$$"
    if dsh_jq_ok; then
        # jq 路径：读旧 → setpath → 写回（最稳）
        if [ -f "$STATE_JSON" ]; then
            if jq --arg id "$id" --arg v "$ver" --arg f "$file" --arg fm "$fmt" \
                  --arg sh "$sha" --argjson sz "$size" --arg tr "$tname" \
                  --argjson spec "${LAYER_SPEC_VERSION:-1}" --arg ec "${EROFS_COMPRESS:-none}" \
                  --arg tc "${TRANSPORT_COMPRESS_PRIMARY:-zstd}" \
                  '(.schema //= 1)
                   | (.spec = $spec)
                   | (.erofs_compress = $ec)
                   | (.transport_compress = $tc)
                   | (.layers //= {})
                   | (.layers[$id] = { version: (if $v == "" then null else $v end),
                                       size: $sz,
                                       sha256: (if $sh == "" then null else $sh end),
                                       format: $fm,
                                       file: $f,
                                       transport: (if $tr == "" then null else $tr end) })
                   | (.updated_at = (now | todate))' \
                  "$STATE_JSON" > "$tmp" 2>/dev/null && mv -f "$tmp" "$STATE_JSON"; then
                chmod 0644 "$STATE_JSON" 2>/dev/null || true
                log "已更新 $STATE_JSON（jq，层 $id 版本 ${ver:-null}）"
                return 0
            fi
            rm -f "$tmp" 2>/dev/null || true
        fi
    fi

    # 纯 bash 回退：解析旧文件的 layers 段，替换/追加该项，其余行原样保留。
    # 说明：不追求通用 JSON 编辑器，只处理我们**自己写出来**的这份固定格式；
    # 解析不了就整体重写（并告警），保证 status 至少能读到正确版本。
    {
        printf '{\n'
        printf '  "schema": 1,\n'
        printf '  "spec": %s,\n' "${LAYER_SPEC_VERSION:-1}"
        printf '  "generated_at": "%s",\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        printf '  "generator": "linuxctl update",\n'
        printf '  "erofs_compress": "%s",\n' "${EROFS_COMPRESS:-none}"
        printf '  "transport_compress": "%s",\n' "${TRANSPORT_COMPRESS_PRIMARY:-zstd}"
        printf '  "layers": {\n'
        local first=1 name f2 v2 a b
        for name in base runtime dsh; do
            if [ "$name" = "$id" ]; then
                a="$ver"; b="$file"
                f2="$path"
            else
                # 继承旧 state.json 的值；读不到就 null
                a="$(dsh_layer_version "$STATE_JSON" "$name" 2>/dev/null || true)"
                f2="$(find_layer "$name" 2>/dev/null || true)"
                b=""
                [ -n "$f2" ] && b="$(basename "$f2")"
            fi
            [ "$first" = 1 ] || printf ',\n'
            printf '    "%s": { "version": %s, "size": %s, "sha256": %s, "format": "%s", "file": %s }' \
                "$name" \
                "$( [ -n "$a" ] && [ "$a" != unknown ] && printf '"%s"' "$(jesc "$a")" || printf 'null')" \
                "$( [ -n "$f2" ] && [ -f "$f2" ] && printf '%s' "$(dsh_size_of "$f2")" || printf 'null')" \
                "$( [ "$name" = "$id" ] && [ -n "$sha" ] && printf '"%s"' "$sha" || printf 'null')" \
                "$fmt" \
                "$( [ -n "$b" ] && printf '"%s"' "$(jesc "$b")" || printf 'null')"
            first=0
        done
        printf '\n  }\n}\n'
    } > "$tmp" 2>/dev/null && mv -f "$tmp" "$STATE_JSON" || { rm -f "$tmp"; warnl "state.json 写入失败"; return 1; }
    chmod 0644 "$STATE_JSON" 2>/dev/null || true
    log "已更新 $STATE_JSON（纯 bash，层 $id 版本 ${ver:-null}）"
    return 0
}

# ---------------------------------------------------------------------------
# rollback <layer> —— 把 state.json 指回该层的"上一个版本"并重启环境
#   为什么不删新层：多版本并存是回滚的前提。本命令只改 state.json 的指向，
#   于是 find_layer 会立刻定位到旧版本文件（它优先读 state.json 的 version）。
#   可用 LINUXCTL_ROLLBACK_TO=<ver> 指定目标版本，否则挑"次高版本"。
# ---------------------------------------------------------------------------
cmd_rollback() {
    # 用法：rollback <layer> [<version>]
    #   给了版本就切到该版本；没给就挑"比当前低的最高版本"。
    #   也支持 rollback <layer> --to <version>（WebUI 用这个形式，更好读）。
    local layer="${1:-}" second="${2:-}" third="${3:-}"
    case "$layer" in base|runtime|dsh) ;; *) log "用法：linuxctl rollback <base|runtime|dsh> [<version>]"; emit '{"ok":false,"error":"layer 必须是 base/runtime/dsh"}'; return 1 ;; esac

    local cur="" target="${LINUXCTL_ROLLBACK_TO:-}"
    case "$second" in
        --to|--version) target="$third" ;;
        "") ;;
        *) target="$second" ;;
    esac
    cur="$(dsh_layer_version "$STATE_JSON" "$layer" 2>/dev/null || true)"

    # ★ dsh 的默认回滚目标 = **内置版本**（随模块冻结的那份，docs/module-variants.md §2.5）。
    #   为什么优先内置而不是"次高版本"：用户更新出问题时的第一诉求是"回到装模块时那份"，
    #   而"次高版本"可能只是另一个同样有问题的频道版本。没有内置记录才退回老逻辑。
    if [ -z "$target" ] && [ "$layer" = "dsh" ]; then
        local _bver=""
        _bver="$(dsh_builtin_field version 2>/dev/null || true)"
        if [ -n "$_bver" ] && [ "$_bver" != "$cur" ] \
           && { [ -f "$LAYERS_DIR/$(layer_file_name dsh "$_bver")" ] || [ -f "$LAYERS_DIR/dsh-$_bver.squashfs" ]; }; then
            target="$_bver"
            log "回滚目标取内置版本（模块自带的那份）：$_bver"
        fi
    fi

    # 已安装版本清单：POSIX 字符串（换行分隔），不用数组 —— 见文件头 LAYER_NAMES 的说明
    local versions="" f v
    for f in "$LAYERS_DIR/$layer"-*.erofs "$LAYERS_DIR/$layer"-*.squashfs; do
        [ -f "$f" ] || continue
        v="$(basename "$f")"
        v="${v#$layer-}"; v="${v%.*}"
        versions="${versions}${versions:+
}$v"
    done
    if [ -z "$versions" ]; then
        emit "{\"ok\":false,\"error\":\"没有可回滚的版本（$layer 只有一个层文件或没有带版本名的层）\"}"
        return 1
    fi
    if [ -z "$target" ]; then
        # 挑"比当前版本低"的最高版本；没有更低的就取次高
        local sorted
        # sort -V 是 GNU 扩展；Android toybox 不一定有 → 没有就退回字典序并明确告警
        if printf '%s\n' x | sort -V >/dev/null 2>&1; then
            sorted="$(printf '%s\n' "$versions" | sort -V -r)"
        else
            warnl "本机 sort 不支持 -V（版本序），回滚候选按字典序排列，可能不是你期望的顺序"
            sorted="$(printf '%s\n' "$versions" | sort -r)"
        fi
        while IFS= read -r v; do
            [ -n "$v" ] || continue
            if [ "$v" != "$cur" ]; then target="$v"; break; fi
        done <<EOF
$sorted
EOF
    fi
    [ -n "$target" ] || { emit "{\"ok\":false,\"error\":\"找不到可回滚的目标版本（当前 $cur）\"}"; return 1; }

    local path=""
    for f in "$LAYERS_DIR/$layer-$target.erofs" "$LAYERS_DIR/$layer-$target.squashfs"; do
        [ -f "$f" ] && { path="$f"; break; }
    done
    [ -n "$path" ] || { emit "{\"ok\":false,\"error\":\"版本 $target 的层文件不存在\"}"; return 1; }

    local fmt sha
    fmt="$(layer_format "$path" 2>/dev/null || echo erofs)"
    sha="$(have sha256sum && sha256sum "$path" 2>/dev/null | awk '{print $1}' || true)"
    write_state_for_layer "$layer" "$target" "$path" "$sha" "$fmt"
    log "已把 $layer 指回 $target（$(basename "$path")）"

    local was_running=false
    env_ready && was_running=true
    local was_mode=""
    [ "$was_running" = true ] && was_mode="$(env_mode_read || true)"
    if [ "$was_running" = true ]; then
        cmd_stop >/dev/null 2>&1 || true
        resume_start_like_before "$was_mode" || log "WARN: 回滚后启动失败，请查 run/last-error"
    fi
    emit "{\"ok\":true,\"layer\":\"$(jesc "$layer")\",\"from\":$( [ -n "$cur" ] && printf '"%s"' "$(jesc "$cur")" || printf 'null'),\"to\":\"$(jesc "$target")\",\"file\":\"$(jesc "$path")\",\"restarted\":$was_running}"
    return 0
}

# ---------------------------------------------------------------------------
# dsh —— DSH 这一块的两种交付方式（docs/module-variants.md §2）
#
#   dsh info                       只读：模块载荷 / 内置版本 / 当前生效版本
#   dsh builtin [--module-dir D]   把模块自带的 dsh 层落到 layers/（幂等；安装时 customize.sh 调）
#   dsh install                    一条指令：从**内置官方频道**装/更新 dsh 层
#
# 为什么要有 builtin：模块 `full` 变体自带 DSH 层镜像（.erofs.gz）——装完重启就能用，
#   不需要联网、也不需要设备侧再构建（设备侧构建 DSH 正是 `exit 78` 的成因）。
# 为什么 install 转发到 update.sh：拉清单 + Ed25519 验签 + 下载 + 校验 + 解压 + 落盘 + 失败
#   回滚是 update.sh 的既有实现（唯一一份）；在这里重写一份必然漂移（repo-strategy §2.3）。
# ---------------------------------------------------------------------------
DSH_BUILTIN_JSON="$ETC_DIR/dsh-builtin.json"
# dsh 层里"能不能起 DSH web"的关键路径（supervise.sh 的前置检查之一）。
# 唯一事实源：doctor §7 与这里的"层坏没坏"判断都指向它。
LAYER_DSH_PROFILE_PATH="/root/.dsh/profiles/web/package.json"

# 从 etc/dsh-builtin.json 取字段（纯 sed：设备侧没有 jq 也能读）
dsh_builtin_field() { # <key>
    [ -f "$DSH_BUILTIN_JSON" ] || return 0
    tr -d ' \n\t' < "$DSH_BUILTIN_JSON" 2>/dev/null \
        | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n1
}

# 找"模块自带的 DSH 载荷"所在目录（里面有 dsh/manifest.json）。
# 候选顺序：显式 --module-dir / 环境变量 → 本脚本上一级（模块 bin/ 的上级就是模块根）
#          → KernelSU 的模块目录 → $LINUX_HOME（万一有人把载荷拷过去）
dsh_module_dir() {
    local c="" d="${LINUXCTL_MODULE_DIR:-}"
    for c in "$d" "$SELF_DIR/.." "/data/adb/modules/sunsetlinux" "$LH"; do
        [ -n "$c" ] || continue
        [ -f "$c/dsh/manifest.json" ] && { printf '%s' "$c"; return 0; }
    done
    return 1
}

# JSON 值：空 → null
_jstr() { if [ -n "${1:-}" ]; then printf '"%s"' "$(jesc "$1")"; else printf 'null'; fi; }

# ---------------------------------------------------------------------------
# dsh 版本比较 —— 语义**必须**与另外两处一致：App 的 `PURE.cmpVer`、
# update.sh 验签器里的 `cmp`（三处不一致 → "界面说能更新、CLI 说不用"这种自相矛盾）。
#   主干（第一个 `-` 之前）逐段按**数值**比；都没预发布后缀则相等；
#   有后缀的更小（0.1.5-rc.2 < 0.1.5）；两边都有后缀时逐段比
#   （数字段按数值、数字段 < 非数字段、其余字典序）。
# 返回：0 = 相等；1 = $1 更新；2 = $2 更新。
#
# ★ 为什么**不用** `sort -V`（本文件 find_layer / rollback 用的是它）：
#   实测 GNU 的版本序把**预发布判成更大** ——
#     `printf '0.1.5\n0.1.5-rc.2\n' | sort -V | tail -n1` → `0.1.5-rc.2`
#     `printf '0.1.6\n0.1.6-alpha.2\n' | sort -V | tail -n1` → `0.1.6-alpha.2`
#   而本项目的语义正相反。拿它判"内置是不是更新"，会把**旧的预发布**当成升级去激活 ——
#   正是"看起来在升级、其实在回退"那类事故。
# ★ 设备侧是 mksh（`$(( ))` 只有 32 位有符号），所以整数比较全交给 awk，不自己乘大数。
dsh_ver_cmp() {
    have awk || return 0     # 连 awk 都没有就"判不了"→ 当相等（保守：不动生效层）
    awk -v A="$1" -v B="$2" 'BEGIN{
        ia=index(A,"-"); ah=(ia>0); acore=(ah?substr(A,1,ia-1):A); apre=(ah?substr(A,ia+1):"");
        ib=index(B,"-"); bh=(ib>0); bcore=(bh?substr(B,1,ib-1):B); bpre=(bh?substr(B,ib+1):"");
        ax=split(acore,ap,"."); bx=split(bcore,bp,".");
        n=(ax>bx?ax:bx);
        for(i=1;i<=n;i++){ x=ap[i]+0; y=bp[i]+0; if(x!=y){ print(x>y?1:2); exit } }
        if(!ah && !bh){ print 0; exit }
        if(!ah){ print 1; exit }          # A 是正式版 → A 更新
        if(!bh){ print 2; exit }
        ax=split(apre,ap,"."); bx=split(bpre,bp,".");
        n=(ax>bx?ax:bx);
        for(i=1;i<=n;i++){
            x=ap[i]; y=bp[i];
            if(x=="" || y==""){ print(x==""?2:1); exit }   # 段多的一方更新（alpha < alpha.1）
            xn=(x ~ /^[0-9]+$/); yn=(y ~ /^[0-9]+$/);
            if(xn && yn){ if(x+0!=y+0){ print(x+0>y+0?1:2); exit } }
            else if(xn!=yn){ print(xn?2:1); exit }         # 数字段 < 非数字段
            else if(x!=y){ print(x>y?1:2); exit }
        }
        print 0
    }'
}

cmd_dsh_info() {
    local mod_dir="" payload=false variant="none" pver="" pfile=""
    local bver="" bfile="" bsha="" bsrc="" bfilepath=""
    local aver="" afile="" afmt="" bnewer="null"

    # 可选 --module-dir：从别处调用时（例如 /data/sunsetlinux/bin/linuxctl）也能指定模块目录
    while [ $# -gt 0 ]; do
        case "$1" in
            --module-dir) mod_dir="${2:-}"; shift 2 ;;
            --module-dir=*) mod_dir="${1#*=}"; shift ;;
            *) shift ;;
        esac
    done
    [ -n "$mod_dir" ] || mod_dir="$(dsh_module_dir 2>/dev/null || true)"
    if [ -n "$mod_dir" ]; then
        payload=true
        variant="$(sed -n 's/^variant=//p' "$mod_dir/module.prop" 2>/dev/null | head -n1)"
        [ -n "$variant" ] || variant="full"
        pver="$(tr -d ' \n\t' < "$mod_dir/dsh/manifest.json" 2>/dev/null \
                | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -n1)"
        pfile="$(tr -d ' \n\t' < "$mod_dir/dsh/manifest.json" 2>/dev/null \
                | sed -n 's/.*"file":"\([^"]*\)".*/\1/p' | head -n1)"
    fi

    bver="$(dsh_builtin_field version)"
    bfile="$(dsh_builtin_field file)"
    bsha="$(dsh_builtin_field sha256_raw)"
    bsrc="$(dsh_builtin_field source)"
    if [ -n "$bver" ]; then
        bfilepath="$LAYERS_DIR/$(layer_file_name dsh "$bver")"
        [ -f "$bfilepath" ] || bfilepath="$LAYERS_DIR/dsh-$bver.squashfs"
        [ -f "$bfilepath" ] || bfilepath=""
    fi

    aver="$(dsh_layer_version "$STATE_JSON" dsh 2>/dev/null || true)"
    case "$aver" in ""|unknown|null) aver="" ;; esac
    afile="$(find_layer dsh 2>/dev/null || true)"
    [ -n "$afile" ] && afmt="$(layer_format "$afile" 2>/dev/null || echo unknown)"

    # 内置是不是比生效层新（三方共用的版本语义，见 dsh_ver_cmp）：
    #   true/false 两个版本都知道时才有意义；缺一个就是 null（**不编造**）。
    #   为什么要暴露它：① `dsh builtin` 的切换判据就是这一条，行为要可观测；
    #   ② App / doctor 以后可以直接说"内置 DSH 比当前新，建议切换"，不用各自再写一套比较。
    bnewer="null"
    if [ -n "$bver" ] && [ -n "$aver" ]; then
        if [ "$(dsh_ver_cmp "$bver" "$aver")" = "1" ]; then bnewer="true"; else bnewer="false"; fi
    fi

    emit "{\"ok\":true,\"module\":{\"dir\":$(_jstr "$mod_dir"),\"variant\":$(_jstr "$variant"),\"has_payload\":$payload,\"payload_version\":$(_jstr "$pver"),\"payload_file\":$(_jstr "$pfile")},\"builtin\":$( [ -n "$bver" ] && printf '{"version":%s,"file":%s,"sha256_raw":%s,"source":%s,"path":%s,"present":%s,"newer_than_active":%s}' "$(_jstr "$bver")" "$(_jstr "$bfile")" "$(_jstr "$bsha")" "$(_jstr "$bsrc")" "$(_jstr "$bfilepath")" "$( [ -n "$bfilepath" ] && printf 'true' || printf 'false' )" "$bnewer" || printf 'null' ),\"active\":$( [ -n "$aver" ] && printf '{"version":%s,"file":%s,"format":%s}' "$(_jstr "$aver")" "$(_jstr "$afile")" "$(_jstr "$afmt")" || printf 'null' )}"
    return 0
}

# 把模块自带的 DSH 层落到 $LINUX_HOME/layers/（幂等 + 校验 + 空间检查）
cmd_dsh_builtin() {
    local mod_dir="" force=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --module-dir) mod_dir="${2:-}"; shift 2 ;;
            --module-dir=*) mod_dir="${1#*=}"; shift ;;
            --force) force=1; shift ;;
            -*) log "未知选项：$1"; emit '{"ok":false,"error":"未知选项"}'; return 1 ;;
            *) shift ;;
        esac
    done
    [ -n "$mod_dir" ] || mod_dir="$(dsh_module_dir 2>/dev/null || true)"
    if [ -z "$mod_dir" ]; then
        emit '{"ok":false,"error":"找不到模块自带的 DSH 载荷（<模块目录>/dsh/manifest.json）","hint":"本模块很可能是 bare 变体（不带 DSH）：用 linuxctl dsh install 从内置官方频道装"}'
        return 1
    fi
    local mf="$mod_dir/dsh/manifest.json"
    [ -f "$mf" ] || { emit "{\"ok\":false,\"error\":\"没有载荷清单：$(jesc "$mf")\"}"; return 1; }

    local flat="" ver="" file="" sha_raw="" size_raw=""
    flat="$(tr -d ' \n\t' < "$mf" 2>/dev/null)"
    ver="$(printf '%s' "$flat" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -n1)"
    file="$(printf '%s' "$flat" | sed -n 's/.*"file":"\([^"]*\)".*/\1/p' | head -n1)"
    # ★ sha256_raw 可能是 null（频道没给 raw 值时）：那种情况只能核 gz 的 sha256，
    #   要如实记下来（"没有 raw 校验"≠"校验通过"）。
    sha_raw="$(printf '%s' "$flat" | sed -n 's/.*"sha256_raw":"\([^"]*\)".*/\1/p' | head -n1)"
    size_raw="$(printf '%s' "$flat" | sed -n 's/.*"size_raw":\([0-9][0-9]*\).*/\1/p' | head -n1)"
    [ -n "$ver" ] && [ -n "$file" ] || { emit '{"ok":false,"error":"manifest.json 解析失败（缺 version/file）"}'; return 1; }
    local src="$mod_dir/dsh/$file"
    [ -f "$src" ] || { emit "{\"ok\":false,\"error\":\"载荷文件不存在：$(jesc "$src")\"}"; return 1; }

    local dst=""
    dst="$LAYERS_DIR/$(layer_file_name dsh "$ver")"
    mkdir -p "$LAYERS_DIR" "$ETC_DIR" 2>/dev/null || true

    # 幂等：同版本已落地、且内置记录一致 → **跳过解压**（不重复解 200 MB）。
    # ★ 但**不能就此返回**：启用与否是另一件事 —— 真机 2026-09-17 的现场正是
    #   "载荷早就解开了、state.json 却还指着坏的旧层"，如果这里直接 return，
    #   `dsh builtin` 会一直答"already"，而环境永远起不来。所以分成两步：
    #     A. 确保载荷在磁盘上（下面这段，贵）
    #     B. 确保启用的是它（函数末尾，便宜）
    local already=0
    if [ "$force" != "1" ] && [ -f "$dst" ] && [ "$(dsh_builtin_field version)" = "$ver" ]; then
        already=1
    fi
    if [ "$already" = "1" ]; then
        log "内置 DSH 载荷已在磁盘上（$dst），跳过解压；继续检查是否需要启用它"
    else
        # 空间检查：解压后 ≈ size_raw（拿不到就按 320 MB 估），至少留 300 MB 余量
        #
        # ★★ **只在 KB 这一档做比较，绝不乘成字节**（真机事故，2026-09-17）：
        #   设备侧跑的是 mksh（/system/bin/sh），它的 `$(( ))` 是 **32 位有符号**整数。
        #   写成 `[ $(( avail_kb * 1024 )) -lt "$need_b" ]` 时，空闲多的机器会溢出成负数：
        #       632669468 KB（603 GiB 空闲）× 1024 = 647853535232 → 环绕成 **-686526464**
        #   → 被判成"空间不足"，而 needed 只有 620 MB。用户装 full 模块时就是这么被挡住的。
        #   （本机 mksh 也能复现：`mksh -c 'echo $((632669468 * 1024))'` → -686526464。）
        local avail_kb="" need_kb=0
        avail_kb="$(df -k "$LAYERS_DIR" 2>/dev/null | awk 'NR==2{print $4}' | tr -dc '0-9')"
        case "$size_raw" in ''|*[!0-9]*) size_raw=0 ;; esac
        if [ "$size_raw" -gt 0 ]; then need_kb=$(( size_raw / 1024 )); else need_kb=$(( 320 * 1024 )); fi
        need_kb=$(( need_kb + 300 * 1024 ))
        if [ -n "$avail_kb" ] && [ "$avail_kb" -gt 0 ]; then
            if [ "$avail_kb" -lt "$need_kb" ]; then
                emit "{\"ok\":false,\"error\":\"空间不足，无法展开内置 DSH 层\",\"available_kb\":$avail_kb,\"needed_kb\":$need_kb,\"hint\":\"清理 cache/ 或 snapshots/ 后重试：linuxctl dsh builtin --force\"}"
                return 1
            fi
        fi

        have gzip || { emit '{"ok":false,"error":"缺少 gzip（toybox 应自带）"}'; return 1; }
        local tmp="$dst.tmp.$$"
        rm -f "$tmp" 2>/dev/null || true
        if ! gzip -dc "$src" > "$tmp" 2>/dev/null; then
            rm -f "$tmp" 2>/dev/null || true
            emit '{"ok":false,"error":"gzip 解压失败（模块包损坏？重刷模块后再试）"}'
            return 1
        fi
        local got=""
        if have sha256sum; then got="$(sha256sum "$tmp" 2>/dev/null | awk '{print $1}')"; fi
        if [ -n "$sha_raw" ] && [ -n "$got" ] && [ "$got" != "$sha_raw" ]; then
            rm -f "$tmp" 2>/dev/null || true
            emit "{\"ok\":false,\"error\":\"解压结果 sha256 与载荷清单不符（模块包损坏）\",\"expected\":\"$(jesc "$sha_raw")\",\"actual\":\"$(jesc "$got")\"}"
            return 1
        fi
        local dfmt=""
        dfmt="$(layer_format "$tmp" 2>/dev/null || echo unknown)"
        if [ "$dfmt" != "erofs" ] && [ "$dfmt" != "squashfs" ]; then
            rm -f "$tmp" 2>/dev/null || true
            emit '{"ok":false,"error":"解压结果不是有效的 erofs/squashfs 镜像（载荷损坏）"}'
            return 1
        fi
        if ! mv -f "$tmp" "$dst" 2>/dev/null; then
            rm -f "$tmp" 2>/dev/null || true
            emit '{"ok":false,"error":"落盘失败（空间不足？）"}'
            return 1
        fi
        chmod 0644 "$dst" 2>/dev/null || true

        # etc/dsh-builtin.json = **内置版本的唯一事实源**（回滚与 doctor 都读它）
        {
            printf '{\n'
            printf '  "schema": 1,\n'
            printf '  "version": "%s",\n' "$(jesc "$ver")"
            printf '  "file": "%s",\n' "$(jesc "$(basename "$dst")")"
            printf '  "path": "%s",\n' "$(jesc "$dst")"
            printf '  "sha256_raw": %s,\n' "$(_jstr "$sha_raw")"
            printf '  "size_raw": %s,\n' "$( [ "$size_raw" -gt 0 ] && printf '%s' "$size_raw" || printf 'null' )"
            printf '  "source": "module",\n'
            printf '  "module_dir": "%s",\n' "$(jesc "$mod_dir")"
            printf '  "at": "%s"\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date)"
            printf '}\n'
        } > "$DSH_BUILTIN_JSON" 2>/dev/null || warnl "写 $DSH_BUILTIN_JSON 失败（回滚目标会退化）"
        chmod 0644 "$DSH_BUILTIN_JSON" 2>/dev/null || true
    fi

    # ------------------------------------------------------------------
    # 第二步（**两条路径都要走**）：决定要不要把生效层切到内置这份
    #   ① 没有 dsh 记录            → 切（全新环境，start.sh 要能立刻定位到它）
    #   ② 生效层**确定**缺 web profile → 切（这份层起不来：supervise.sh 必退 78）
    #   ③ **内置版本比生效层新**   → 切（模块带来的内置 DSH 更新要能落地）
    #   ④ 其余（生效层健康且不比内置旧）→ **不动**（用户可能已经从频道更新过 dsh，
    #                                模块升级不该把好意变成回退）
    #
    # ② 是真机事故补上的（2026-09-17）：装 full 模块后内置 rc.2 已经落到 layers/ 了，
    # 但 state.json 还指着**设备侧自建**的 rc.1（那份没有 /root/.dsh/profiles/**），
    # 于是每次 start 都用坏层 → 永远 rc=78；而 doctor 里"内置 DSH 0.1.5-rc.2 已就位"
    # 与"dsh 层内没有 profile"两行同时出现，用户完全无法理解。
    # layer_has_path 三态：0 有 / 1 没有 / 2 判不了 —— 只有**确定的 1** 才切（保守）。
    #
    # ③ 是 2026-09-18 真机暴露的：模块 1.0.42 自带内置 dsh **0.1.6-alpha.2**，而生效层是
    # **健康**的 0.1.5-rc.2 —— 旧逻辑只看"有没有 profile"⇒ 不切，于是用户"覆盖更新模块 +
    # 重启"之后环境照旧跑 rc.2（service.log 里那行"内置 DSH 就位 …（启用更新=false）"就是它），
    # 看起来像什么都没发生。注意**方向**：只有内置**更新**才切，内置更旧或相同一律不动。
    # ------------------------------------------------------------------
    local fmt="" cur="" cur_file="" profile_rc=2 why="" activated=false
    fmt="$(layer_format "$dst" 2>/dev/null || echo erofs)"
    cur="$(dsh_layer_version "$STATE_JSON" dsh 2>/dev/null || true)"
    case "$cur" in ""|unknown|null) cur="" ;; esac
    cur_file="$(find_layer dsh 2>/dev/null || true)"
    if [ -z "$cur" ]; then
        why="本机还没有 dsh 记录（全新环境）"
    elif [ -n "$cur_file" ] && [ "$cur_file" = "$dst" ]; then
        why=""   # 生效的就是这份内置层，什么都不用做
    else
        if [ -n "$cur_file" ]; then
            if layer_has_path "$cur_file" "$LAYER_DSH_PROFILE_PATH"; then
                profile_rc=0
            else
                profile_rc=$?
            fi
        fi
        if [ "$profile_rc" = "1" ]; then
            why="当前生效层 $cur 里没有 $LAYER_DSH_PROFILE_PATH（这份层起不来：supervise.sh 会退 78）"
        elif [ -n "$cur" ] && [ -n "$ver" ]; then
            # 生效层是健康的：内置**更新**才切（见上面 ③）
            if [ "$(dsh_ver_cmp "$ver" "$cur")" = "1" ]; then
                why="内置版本 $ver 比生效层 $cur 新"
            fi
        fi
    fi
    if [ -n "$why" ]; then
        write_state_for_layer dsh "$ver" "$dst" "$sha_raw" "$fmt" && activated=true
        log "把生效层切到内置 DSH（版本 $ver）：$why"
    fi

    local action="materialized"
    [ "$already" = "1" ] && action="already"
    log "内置 DSH 就位：$dst（版本 $ver${sha_raw:+, sha256_raw 已核}；启用更新=$activated）"
    emit "{\"ok\":true,\"action\":\"$action\",\"layer\":\"dsh\",\"version\":$( _jstr "$ver" ),\"file\":\"$(jesc "$dst")\",\"sha256_raw\":$(_jstr "$sha_raw"),\"size_raw\":$( [ "$size_raw" -gt 0 ] && printf '%s' "$size_raw" || printf 'null' ),\"state_updated\":$activated,\"reason\":$(_jstr "$why")}"
    return 0
}

# 一条指令：从内置官方频道装/更新 dsh 层（转发 update.sh install）
cmd_dsh_install() {
    local u="$SELF_DIR/update.sh"
    [ -f "$u" ] || u="$LH/bin/update.sh"
    if [ ! -f "$u" ]; then
        emit '{"ok":false,"error":"找不到 update.sh（应与 linuxctl.sh 同目录）"}'
        return 1
    fi
    # 人类可读进度走 stderr（不捕获）；stdout 只留 JSON。
    local out=""
    out="$(LINUX_HOME="$LH" LINUXCTL_SH="$SELF_DIR/linuxctl.sh" "$SH_BIN" "$u" install dsh "$@")" || true
    local last=""
    last="$(printf '%s\n' "$out" | tail -n1)"
    case "$last" in
        \{*)
            emit "$last"
            # ★ 退出码要跟着 ok 走：脚本调用方（模块 service.sh / CI / 用户）靠退出码判断，
            #   不能"失败也 exit 0"（那会让自动化以为装好了）。
            case "$last" in
                *'"ok":true'*) return 0 ;;
                *) return 1 ;;
            esac ;;
        *)   emit "{\"ok\":false,\"error\":\"install dsh 没有输出可解析的 JSON\",\"detail\":$(_jstr "$out")}"; return 1 ;;
    esac
}

# ---------------------------------------------------------------------------
# dsh start / stop —— 在**运行中的环境里**单独开关 DSH（App 的「启动 DSH」/「停止 DSH」）
#
#   为什么不复用 start/stop：这是两条**独立**的意图（用户明确要求拆开）：
#     · 「仅启动环境」= 把 Ubuntu 环境起来做维护（更新层、装包、跑终端），此时不该有 DSH
#       占着 3080；
#     · 「启动 DSH」= 环境已经在跑，只把这一个服务拉起来/停掉，环境保持不动。
#   互斥规则（App 的按钮判定与这里**必须**一致，见 docs/architecture.md §3.4）：
#     env_mode=full（一键启动）→ 不提供"单独停 DSH"：否则用户会处在一个自己都说不清的
#       中间态；要单独控制就先「停止环境」，再用「仅启动环境」。
#     env_mode=env-only → 两个方向都允许。
# ---------------------------------------------------------------------------
cmd_dsh_start() {
    local port=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --port)   port="${2:-}"; shift 2 ;;
            --port=*) port="${1#--port=}"; shift ;;
            *) warnl "dsh start: 忽略未知参数 $1"; shift ;;
        esac
    done
    case "${port:-}" in ''|*[!0-9]*) port="$(config_port)" ;; esac
    if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then port="$(config_port)"; fi

    if ! env_ready; then
        local msg="环境未运行：先用「仅启动环境」或「一键启动」把环境起来，再启动 DSH"
        printf '%s\n' "$msg" > "$ERROR_FILE" 2>/dev/null || true
        log "ERROR: $msg"
        gather_status
        DSH_ST_LAST_ERROR="$msg"
        emit "$(dsh_status_json)"
        return 1
    fi

    local pid=""
    if pid="$(dsh_is_running)"; then
        log "DSH 已在运行（pid=$pid，幂等）"
        gather_status
        emit "$(dsh_status_json)"
        return 0
    fi

    # 端口被占就让路：真机上"另一个环境"（免 root 的 DSHA）会占着 3080，
    #   此时若照原端口起，DSH 会 bind 失败 → 用户看到"起不来"却不知道为什么。
    #   让路后实际端口由 supervise.sh 写进 run/dsh.port（登录 URL 里也带真实端口），
    #   App 读的是 URL/端口文件，因此换端口对上层是透明的。
    if command -v port_pick_free >/dev/null 2>&1; then
        local _want="$port"
        port="$(port_pick_free "$_want")"
        [ "$port" != "$_want" ] && warnl "端口 $_want 被占用 → DSH 改用 $port（占用者可能是另一个环境）"
    fi

    rm -f "$ERROR_FILE" "$DSH_PID_FILE" "$DSH_URL_FILE" "$DSH_PORT_FILE" 2>/dev/null || true
    log "在环境内启动 DSH（port=$port）"
    if ! spawn_dsh_in_env "$port"; then
        local msg="DSH 启动失败：进不去环境（nsenter/chroot 不可用）"
        printf '%s\n' "$msg" > "$ERROR_FILE" 2>/dev/null || true
        log "ERROR: $msg"
        gather_status
        DSH_ST_LAST_ERROR="$msg"
        emit "$(dsh_status_json)"
        return 1
    fi
    if ! wait_dsh_pid 6; then
        # 连 pid 都没出现 = 那条 nsenter/chroot 命令没跑起来（或 supervise.sh 立刻退了）
        local msg="DSH 没起来：进不去环境或 supervise.sh 立刻退出 —— 看 $LOGFILE 末尾（常见：nsenter 选项、DSH 层/profile 缺失、端口被占）"
        printf '%s\n' "$msg" > "$ERROR_FILE" 2>/dev/null || true
        log "ERROR: $msg"
        gather_status
        DSH_ST_LAST_ERROR="$msg"
        emit "$(dsh_status_json)"
        return 1
    fi
    if wait_dsh_url 20; then
        log "DSH 已启动（带令牌 URL 已就绪）"
        gather_status
        emit "$(dsh_status_json)"
        return 0
    fi
    # 进程拉起来了但没打印 URL：把排查入口给用户（supervisor 的原话在 linux.log 里）
    local msg="DSH 进程已拉起，但 20s 内没有打印带令牌的登录 URL —— 看 $LOGFILE 里 'dsh web: ' 那一行"
    printf '%s\n' "$msg" > "$ERROR_FILE" 2>/dev/null || true
    log "ERROR: $msg"
    gather_status
    DSH_ST_LAST_ERROR="$msg"
    emit "$(dsh_status_json)"
    return 1
}

cmd_dsh_stop() {
    if ! env_ready; then
        # 幂等：环境没跑时只需清掉过期凭证（令牌随进程失效，§3.3）
        rm -f "$DSH_PID_FILE" "$DSH_URL_FILE" "$DSH_PORT_FILE" 2>/dev/null || true
        log "环境未运行（幂等）：已清掉过期的 dsh 凭证文件"
        gather_status
        emit "$(dsh_status_json)"
        return 0
    fi
    if [ "$(env_mode_read || true)" = "full" ]; then
        local msg="本环境是「一键启动」起来的（run/env-mode=full）：按设计不单独停 DSH —— 请「停止环境」；要单独控制 DSH，就先停止环境再选「仅启动环境」"
        printf '%s\n' "$msg" > "$ERROR_FILE" 2>/dev/null || true
        log "ERROR: $msg"
        gather_status
        DSH_ST_LAST_ERROR="$msg"
        emit "$(dsh_status_json)"
        return 1
    fi

    local pid="" i=0
    if pid="$(dsh_is_running)"; then
        log "停止 DSH（pid=$pid），环境保持运行"
        kill -TERM "$pid" 2>/dev/null || true
        while [ "$i" -lt 150 ] && [ -d "/proc/$pid" ]; do sleep 0.1; i=$(( i + 1 )); done
        if [ -d "/proc/$pid" ]; then
            warnl "dsh(pid=$pid) 未在 15s 内退出，发送 KILL"
            kill -KILL "$pid" 2>/dev/null || true
        fi
    else
        log "DSH 本来就没在跑（幂等）"
    fi
    # 兜底：dsh.pid 丢了也要清干净 —— 否则 3080 与可写层被它占着，下次启动直接失败
    #（真机 2026-09-17 的事故现场，见 docs/STATUS.md §3.10.31）
    if command -v env_proc_kill_all >/dev/null 2>&1; then
        ENV_ROOTFS="$ROOTFS_DIR" env_proc_kill_all '*dsh*web*'
    fi
    rm -f "$DSH_PID_FILE" "$DSH_URL_FILE" "$DSH_PORT_FILE" 2>/dev/null || true
    gather_status
    emit "$(dsh_status_json)"
    return 0
}

cmd_dsh() {
    local sub="${1:-info}"; shift || true
    case "$sub" in
        info)    cmd_dsh_info "$@" ;;
        builtin) cmd_dsh_builtin "$@" ;;
        install) cmd_dsh_install "$@" ;;
        start)   cmd_dsh_start "$@" ;;
        stop)    cmd_dsh_stop "$@" ;;
        -h|--help|help)
            printf '用法：linuxctl dsh {info|builtin [--module-dir D] [--force]|install|start [--port N]|stop}\n' >&2
            emit '{"ok":true,"usage":"dsh info|builtin|install|start|stop"}'
            return 0 ;;
        *) log "未知子命令：dsh $sub"; emit '{"ok":false,"error":"dsh 的子命令必须是 info/builtin/install/start/stop"}'; return 2 ;;
    esac
}

# ---------------------------------------------------------------------------
# update-check / update-apply / update-versions
#   极薄转发到同目录的 update.sh（更新逻辑的唯一实现）。
#   为什么要转发：App 与模块 WebUI 都只认 linuxctl 这一个入口，
#   把"拉清单+验签+下载+解压"藏在 linuxctl 后面，前端就不必理解频道细节。
#   注意：本函数只有转发职责，不含任何业务逻辑，避免与 update.sh 漂移。
# ---------------------------------------------------------------------------
cmd_update_proxy() {
    local sub="$1"; shift || true
    local u="$SELF_DIR/update.sh"
    [ -f "$u" ] || u="$LH/bin/update.sh"
    if [ ! -f "$u" ]; then
        emit '{"ok":false,"error":"找不到 update.sh（应与 linuxctl.sh 同目录）"}'
        return 1
    fi
    local out
    out="$(LINUX_HOME="$LH" LINUXCTL_SH="$SELF_DIR/linuxctl.sh" "$SH_BIN" "$u" "$sub" "$@" 2>/dev/null)" || true
    if [ -z "$out" ]; then
        emit '{"ok":false,"error":"update.sh 没有输出（执行失败）"}'
        return 1
    fi
    emit "$out"
    return 0
}

# ---------------------------------------------------------------------------
# doctor
# ---------------------------------------------------------------------------
cmd_doctor() {
    local doc="$SELF_DIR/doctor.sh"
    [ -f "$doc" ] || { log "找不到 $doc"; emit '{"schema":1,"ok":false,"fails":1,"last_error":"doctor.sh 缺失"}'; return 1; }
    LINUX_HOME="$LH" "$SH_BIN" "$doc"
    return $?
}

# ---------------------------------------------------------------------------
# 帮助
# ---------------------------------------------------------------------------
usage() {
    cat >&2 <<'EOF'
linuxctl（sunsetlinux root 模式）

用法：linuxctl <命令> [参数]

  provision [--seed <dir>]   首次部署：目录树 / upper.img / 配置（幂等；已部署返回 2）
  start [--layer-mode loop|dir] [--no-dsh]
                             启动环境（幂等）。--no-dsh = **只起环境、不启动 DSH**
                             （在环境里更新层/装包/开终端时用；与一键启动互斥，
                              见 docs/architecture.md §3.4）
  stop                       停止环境（幂等，连同里面的一切进程）
  status                     输出状态 JSON（architecture.md §3.1）
  whereami [--json]          **我在哪个视角**（三个挂载命名空间 + 路径地图；只读）
  attach [-- cmd...]         进入环境执行命令；无参数则开交互 shell
  exec -- cmd...             非交互执行（供 App 调用）
  logs [-n N]                日志尾部（默认 200 行）
  snapshot <name>            可写层打包到 snapshots/<name>.tar.zst
  restore <name>             从快照恢复可写层
  reset                      清空可写层 → 恢复出厂
  update <layer> <file> [--version <ver>]
                             安装 base/runtime/dsh 层并重启环境。
                             --version 由 App 从频道清单 layers[].version 传入；
                             缺省时沿用 state.json 的当前版本，都没有才降级为
                             <layer>.erofs（无版本名，回滚不可用，会告警）。
                             同层多版本并存，旧文件不删（回滚要用）。
  update dsh                 **一条指令**：从内置官方频道装/更新 dsh 层
                             （等价于 linuxctl dsh install；验签失败即拒绝）
  dsh info                   只读：模块载荷 / 内置 DSH 版本 / 当前生效版本
  dsh builtin [--module-dir D] [--force]
                             把模块自带的 DSH 层落到 layers/（装模块时自动做，幂等）
  dsh install                从内置官方频道装/更新 dsh 层（一条指令）
  dsh start [--port N]       在**运行中的环境里**单独启动 DSH（环境保持不动）
  dsh stop                   单独停止 DSH（环境保持运行）；「一键启动」的环境不允许单独停
                             —— 要单独控制 DSH 就先 stop，再用 start --no-dsh
  rollback <layer> [<ver>]   切回该层的指定版本（省略则挑比当前低的最高版本；
                             dsh 有内置版本时**优先回内置版本**）
                             只改 state.json 的指向，**不删任何层文件**，可来回切
  update-check               拉取并**验签**频道清单，报告可更新项（Ed25519，失败即拒绝）
  update-apply <layer> <ver> <url> [sha256]
                             下载→校验 sha256→解压→安装（WebUI/CLI 走 .gz，App 可走 .zst）
  update-versions            列出本机已有的层版本（供回滚选择）
  update-module-info         读模块 module.prop 的版本（无需 root 侧探测）
  update-module-check        检查**模块自身**是否有更新（同一套 Ed25519 验签）
  doctor                     自检
  footprint                  **只读**残留报告：环境根各项大小 + 模块目录 + loop/挂载/进程/端口残留
  purge [--yes]              彻底清除环境根（默认空跑；先 stop，有挂载残留则拒绝删除）
  purge --arm | --disarm     开启/撤销「卸载即清除」标记（卸载模块时是否连用户数据一起删）

环境变量：LINUX_HOME（默认 /data/sunsetlinux）、UPPER_SIZE_MB（默认 8192）
stdout 只输出 JSON；人类可读信息走 stderr。
EOF
}

# ---------------------------------------------------------------------------
# footprint —— **只读**残留报告：清理/卸载前用它看清"到底还剩什么"
#
# 为什么需要它：本项目的数据**刻意放在 App 私有目录之外**（卸载 App 不会删），
# 所以"卸载后还剩什么"必须能**看得见**，而不是靠猜。
# 它同时报告**运行态残留**（loop 设备 / 挂载点 / 进程 / 端口）—— 这类最难自己发现，
# 也正是"手动 rm -rf 清不干净"的根因：环境还在跑时挂载点被占用，删不掉但错误容易被忽略。
#
# 只读，不改任何东西。JSON 走 stdout。
# ---------------------------------------------------------------------------
_fp_size() { # _fp_size <路径> → 字节；目录用 du（toybox 兼容 -k）
    local p="$1" k
    [ -e "$p" ] || { printf '0'; return; }
    if [ -d "$p" ]; then
        k="$(du -sk "$p" 2>/dev/null | awk '{print $1}')"
        # ★ 用 awk 做 KB→字节：mksh 的算术是 32 位，`k * 1024` 在超过 2 GiB 的目录上会溢出
        #   （upper.img 表观 8 GiB、rootfs 上 GB 都是常态）→ 报出来的大小会变成负数/怪值。
        #   awk 用双精度浮点，这个量级精确无虞。同样的坑 2026-09-17 在空间检查上真机爆过一次。
        printf '%s' "$(awk -v k="${k:-0}" 'BEGIN{printf "%.0f", k*1024}')"
    else
        dsh_size_of "$p" 2>/dev/null || printf '0'
    fi
}

_fp_human() { # _fp_human <字节数> → 人类可读（toybox 也有 awk，用它避免浮点运算）
    local b="${1:-0}"
    case "$b" in ''|*[!0-9]*) b=0 ;; esac
    if [ "$b" -ge 1073741824 ]; then
        awk -v b="$b" 'BEGIN{printf "%.2f GiB", b/1073741824}'
    elif [ "$b" -ge 1048576 ]; then
        awk -v b="$b" 'BEGIN{printf "%.1f MiB", b/1048576}'
    elif [ "$b" -ge 1024 ]; then
        awk -v b="$b" 'BEGIN{printf "%.1f KiB", b/1024}'
    else
        printf '%s B' "$b"
    fi
}

_fp_loops() { # 指向我们镜像的 loop 设备（每行一个）    have losetup || return 0
    losetup -a 2>/dev/null | grep -F "$LH" || true
}

_fp_mounts() { # 挂载点在我们环境根之下的（每行一个）
    grep -F "$LH" /proc/mounts 2>/dev/null || true
}

_fp_pid_alive() { # _fp_pid_alive <pid 文件>
    local f="$1" p
    [ -f "$f" ] || return 1
    p="$(tr -dc '0-9' < "$f" 2>/dev/null)"
    [ -n "$p" ] || return 1
    kill -0 "$p" 2>/dev/null
}

# ---------------------------------------------------------------------------
# whereami —— **我在哪个视角？**（2026-09-19）
#
# 为什么要有这个东西：同一台机器上同时存在**三个挂载命名空间**，同一个路径字符串
# 在不同视角下指向**不同的目录**。真机实测（OnePlus PJD110）：
#   · MT 管理器 / 全局 ns（4026532884）      → /data/sunsetlinux = 真的那棵（含 upper.img）
#   · 设备 shell（ksu su，4026536055）        → 同一棵树，但看不到 env 的私有挂载
#   · SunsetLinux env（unshare -m，4026535677）→ 看不见 /data，只有 overlay 合成视图
#   · DSHA 的 proot 容器（AI 会话常在这里）    → /data 是 **rootfs 里的影子目录**
#     （实测：/data/sunsetlinux 的 inode 2909534 vs 真的 1652135 —— 同一分区、两份目录）
# 后果：拿绝对路径去猜"工作区在哪"必然翻车（本项目的 AI 会话就差点踩）。
#
# 所以：**先问 whereami，再动手**。它只读、不改任何东西（连 mkdir 都不做）。
# ---------------------------------------------------------------------------
cmd_whereami() {
    local json=0
    case "${1:-}" in
        --json) json=1 ;;
        ""|-h|--help|help)
            printf '用法：linuxctl whereami [--json]\n' >&2
            printf '  回答"我现在在哪个视角、这个路径在宿主上是什么、和别的视角怎么交换文件"。\n' >&2
            printf '  只读：不创建任何目录/文件。\n' >&2
            return 0 ;;
        *) printf 'whereami：未知参数 %s\n' "$1" >&2; return 2 ;;
    esac

    # --- 视角判定（用"只有这个视角才有的东西"当证据，不猜）----------------
    local view="unknown" why="" ns=""
    ns="$(readlink /proc/self/ns/mnt 2>/dev/null || printf '?')"

    if [ ! -d "$LH" ] && [ -d /opt/sunsetlinux ]; then
        view="env"
        why="看不到 $LH（chroot 里没有 /data），且 /opt/sunsetlinux 在 —— 这是**环境内部**"
    elif [ -f "$LH/upper.img" ] || [ -d "$LH/dirs-upper" ] || [ -x "$LH/bin/linuxctl.sh" ]; then
        view="host"
        why="能看到 $LH 的真实内容（upper.img / dirs-upper / bin 至少一个在）—— 这是**宿主**视角"
    elif [ -d "$LH" ]; then
        view="shadow"
        why="$LH 在，但里面没有 upper.img/dirs-upper/bin —— **这多半不是真的那一份**（影子视图：某个容器把 /data 解析到了自己的 rootfs 里）。绝对路径会读错！"
    fi

    # --- 路径地图（按视角给"你现在看到的 → 宿主上是"）----------------------
    local p_root p_write p_sd p_share p_run
    case "$view" in
        env)
            p_root="/  →  $LH/rootfs（overlay 合成：base+runtime+dsh 三层只读 + 可写层）"
            p_write="你写的任何文件  →  $LH/upper/upper/…（可写层；宿主侧看就是这个路径）"
            p_sd="/mnt/sdcard  →  Android 共享存储（/storage/emulated/0 是它的软链，同一份）"
            p_share="/share  →  $LH/share（交换目录：两侧同一批 inode）"
            p_run="/run  →  $LH/run（状态/日志；宿主侧同一批文件）" ;;
        host)
            p_root="$LH/rootfs（overlay 合成视图；环境内部看到的就是它）"
            p_write="$LH/upper/upper/…（可写层；环境内写的文件都落在这里）"
            p_sd="/storage/emulated/0（共享存储；环境内是 /mnt/sdcard）"
            p_share="$LH/share（交换目录；环境内是 /share）"
            p_run="$LH/run（状态/日志；环境内是 /run）" ;;
        shadow)
            p_root="（判不了）"
            p_write="（判不了——先确认真实路径，别写）"
            p_sd="（判不了）"
            p_share="（判不了）"
            p_run="（判不了）" ;;
        *)
            p_root="（环境根不存在，可能还没部署）"
            p_write="（同上）" ; p_sd="（同上）" ; p_share="（同上）" ; p_run="（同上）" ;;
    esac

    # --- 环境是否在跑（只读地看一眼，拿不到就说拿不到）--------------------
    local phase="?" pid="?"
    if [ -f "$RUN_DIR/state.json" ]; then
        phase="$(sed -n 's/.*"phase":"\([^"]*\)".*/\1/p' "$RUN_DIR/state.json" 2>/dev/null | head -n1)"
        pid="$(sed -n 's/.*"pid":\([0-9]*\).*/\1/p' "$RUN_DIR/state.json" 2>/dev/null | head -n1)"
        [ -n "$phase" ] || phase="?"
        [ -n "$pid" ] || pid="?"
    fi

    if [ "$json" = "1" ]; then
        emit "{\"schema\":1,\"view\":$(_jstr "$view"),\"evidence\":$(_jstr "$why"),\"mnt_ns\":$(_jstr "$ns"),\"linux_home\":$(_jstr "$LH"),\"phase\":$(_jstr "$phase"),\"pid\":$(_jstr "$pid"),\"paths\":{\"root\":$(_jstr "$p_root"),\"write\":$(_jstr "$p_write"),\"sdcard\":$(_jstr "$p_sd"),\"share\":$(_jstr "$p_share"),\"run\":$(_jstr "$p_run")},\"hint\":$(_jstr "三个命名空间（全局/设备shell/env）看到的挂载各不相同；交换文件请走 /share 或 /mnt/sdcard，别猜绝对路径")}"
        return 0
    fi

    printf '我在哪：%s\n' "$view"
    printf '  证据：%s\n' "$why"
    printf '  挂载命名空间：%s\n' "$ns"
    printf '  环境根：%s（phase=%s pid=%s）\n' "$LH" "$phase" "$pid"
    printf '\n路径地图\n'
    printf '  %s\n  %s\n  %s\n  %s\n  %s\n' "$p_root" "$p_write" "$p_sd" "$p_share" "$p_run"
    printf '\n怎么和别的视角交换文件\n'
    printf '  · 容器内 → 宿主：写进 /share（宿主侧就是 %s/share），或在 /mnt/sdcard 下写\n' "$LH"
    printf '  · 宿主 → 容器：往 %s/share 丢文件（容器内立刻出现在 /share）\n' "$LH"
    printf '  · 别用绝对路径猜宿主布局：容器里看不到 /data；宿主上看不到 overlay 的合成视图\n'
    if [ "$view" = "shadow" ]; then
        printf '\n⚠️  影子视图警告：你现在看到的 %s **很可能不是真的那一份**。\n' "$LH"
        printf '    请从 MT（全局命名空间）或设备 shell 复核，再决定要不要写。\n'
    fi
    return 0
}

cmd_footprint() {
    local home_exists=false layers_sz=0 upper_sz=0 snap_sz=0 seeds_sz=0 total_sz=0
    local module_id="${LINUXCTL_MODULE_ID:-sunsetlinux}"
    local adb_dir="${ADB_DIR:-/data/adb}"
    local module_dir="$adb_dir/modules/$module_id"
    local modupd_dir="$adb_dir/modules_update/$module_id"
    local app_data="${LINUXCTL_APP_DATA:-/data/data/io.github.sunsetrne.sunsetlinux}"

    [ -d "$LH" ] && home_exists=true
    layers_sz="$(_fp_size "$LH/layers")"
    upper_sz="$(_fp_size "$LH/upper.img")"
    snap_sz="$(_fp_size "$LH/snapshots")"
    seeds_sz="$(_fp_size "$LH/seeds")"
    total_sz="$(_fp_size "$LH")"

    local loops mounts
    loops="$(_fp_loops)"
    mounts="$(_fp_mounts)"
    local n_loops=0 n_mounts=0
    [ -n "$loops" ]  && n_loops="$(printf '%s\n' "$loops" | grep -c . || true)"
    [ -n "$mounts" ] && n_mounts="$(printf '%s\n' "$mounts" | grep -c . || true)"

    local supervisor_alive=false dsh_alive=false
    _fp_pid_alive "$RUN_DIR/supervisor.pid" && supervisor_alive=true
    _fp_pid_alive "$RUN_DIR/dsh.pid" && dsh_alive=true

    # 「卸载即清除」标记：模块的 uninstall.sh 只认这个文件，**不认环境变量**
    # （uninstall.sh 由 KernelSU 调用，用户没有机会给它传环境变量 —— 见 docs/uninstall.md）
    local purge_armed=false
    [ -f "$PURGE_MARKER" ] && purge_armed=true

    # 端口占用：有 ss/netstat 才判，否则如实标 unknown（不猜）
    local port="" port_state=unknown
    [ -f "$RUN_DIR/dsh.port" ] && port="$(tr -dc '0-9' < "$RUN_DIR/dsh.port" 2>/dev/null)"
    if [ -n "$port" ]; then
        if have ss; then
            if ss -ltn 2>/dev/null | grep -q ":$port "; then port_state=busy; else port_state=free; fi
        elif have netstat; then
            if netstat -ltn 2>/dev/null | grep -q ":$port "; then port_state=busy; else port_state=free; fi
        fi
    fi

    # ---- 人类可读（stderr）----
    {
        printf '\n== 残留清单 ==\n'
        printf '  环境根              %-38s %s   %s\n' "$LH" \
            "$([ "$home_exists" = true ] && echo 存在 || echo 不存在)" "$(_fp_human "$total_sz")"
        printf '    layers/           三层只读镜像%-24s %s\n' "" "$(_fp_human "$layers_sz")"
        printf '    upper.img         可写层（你的数据）%-16s %s（稀疏，这里报实际占用）\n' "" "$(_fp_human "$upper_sz")"
        printf '    snapshots/        快照%-32s %s\n' "" "$(_fp_human "$snap_sz")"
        printf '    seeds/            离线种子%-28s %s\n' "" "$(_fp_human "$seeds_sz")"
        printf '  模块目录            %-38s %s\n' "$module_dir" \
            "$([ -d "$module_dir" ] && echo 存在 || echo 不存在)"
        printf '  待生效的模块更新     %-38s %s\n' "$modupd_dir" \
            "$([ -d "$modupd_dir" ] && echo '存在（装了模块但还没重启）' || echo 不存在)"
        printf '  App 私有数据         %-38s %s（卸载 App 时由系统清理）\n' "$app_data" \
            "$([ -d "$app_data" ] && echo 存在 || echo 不存在)"
        printf '  -- 运行态 --\n'
        printf '  loop 设备残留        %s\n' "$([ "$n_loops" = 0 ] && echo '无 ✅' || printf '%s 个 ⚠️\n' "$n_loops")"
        [ "$n_loops" != 0 ] && printf '%s\n' "$loops" | sed 's/^/      /'
        printf '  挂载点残留           %s\n' "$([ "$n_mounts" = 0 ] && echo '无 ✅' || printf '%s 个 ⚠️\n' "$n_mounts")"
        [ "$n_mounts" != 0 ] && printf '%s\n' "$mounts" | sed 's/^/      /'
        printf '  supervisor 进程      %s\n' "$([ "$supervisor_alive" = true ] && echo '仍在运行 ⚠️' || echo '无 ✅')"
        printf '  dsh 进程             %s\n' "$([ "$dsh_alive" = true ] && echo '仍在运行 ⚠️' || echo '无 ✅')"
        printf '  端口 %-15s %s\n' "${port:-（未记录）}" \
            "$([ "$port_state" = busy ] && echo '被占用 ⚠️' || { [ "$port_state" = free ] && echo '空闲 ✅' || echo '未知（无 ss/netstat）'; })"
        printf '  卸载即清除标记      %s\n' \
            "$([ "$purge_armed" = true ] && echo '已开启 ⚠️ 卸载模块时会连用户数据一起删' || echo '未开启（卸载模块只删模块自身，用户数据保留）')"
        printf '\n  提示：清除前请先 `linuxctl stop`，否则挂载点被占用会导致 rm 删不干净。\n'
    } >&2

    emit "{\"ok\":true,\"linux_home\":\"$(jesc "$LH")\",\"exists\":$home_exists,\"total_bytes\":$total_sz,\"layers_bytes\":$layers_sz,\"upper_bytes\":$upper_sz,\"snapshots_bytes\":$snap_sz,\"seeds_bytes\":$seeds_sz,\"module_dir\":\"$(jesc "$module_dir")\",\"module_exists\":$([ -d "$module_dir" ] && echo true || echo false),\"module_update_pending\":$([ -d "$modupd_dir" ] && echo true || echo false),\"purge_on_uninstall\":$purge_armed,\"loop_residue\":$n_loops,\"mount_residue\":$n_mounts,\"supervisor_alive\":$supervisor_alive,\"dsh_alive\":$dsh_alive,\"port\":$([ -n "$port" ] && printf '%s' "$port" || echo null),\"port_state\":\"$port_state\"}"
    return 0
}

# ---------------------------------------------------------------------------
# purge —— 彻底清除：**先停、再确认没有残留、最后才删**
#
# 顺序是硬要求：环境还在跑时挂载点被占用，rm 会删不干净且错误容易被忽略
# （留下空目录 + 悬挂 loop + 仍在跑的进程），这正是"清不干净"的常见成因。
# 所以：有任何挂载/loop 残留时**直接拒绝删除**，让用户先 stop，而不是删一半。
#
# 默认是**空跑**（只打印将删除什么），必须显式 --yes 才真删。
# ---------------------------------------------------------------------------
cmd_purge() {
    local yes=0 action=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --yes|-y)   yes=1; shift ;;
            --arm)      action="arm"; shift ;;
            --disarm)   action="disarm"; shift ;;
            *) log "未知参数：$1"; emit '{"ok":false,"error":"用法：linuxctl purge [--yes] | purge --arm | purge --disarm"}'; return 2 ;;
        esac
    done

    # ---- 「卸载即清除」标记：写给模块的 uninstall.sh 看 ----
    # 为什么用文件而不是环境变量：uninstall.sh 是 KernelSU 在卸载时自己调的，
    # 用户的 shell 环境根本传不进去 —— 环境变量开关是**不可达代码**（详见 docs/uninstall.md）。
    if [ "$action" = "arm" ]; then
        if mkdir -p "$LH/etc" 2>/dev/null && \
           printf '由 `linuxctl purge --arm` 于 %s 写入。\n卸载模块时 uninstall.sh 见到本文件会连同用户数据一起删除。\n删除本文件（或跑 `linuxctl purge --disarm`）即可撤销。\n' \
               "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$PURGE_MARKER" 2>/dev/null; then
            log "已开启「卸载即清除」：$PURGE_MARKER"
            log "⚠️ 之后卸载模块会删除整个 $LH（不可恢复）。撤销：linuxctl purge --disarm"
            emit "{\"ok\":true,\"purge_on_uninstall\":true,\"linux_home\":\"$(jesc "$LH")\",\"marker\":\"$(jesc "$PURGE_MARKER")\"}"
            return 0
        fi
        emit '{"ok":false,"error":"无法写入标记文件（环境根不存在或无权限？）"}'
        return 1
    fi
    if [ "$action" = "disarm" ]; then
        if [ -f "$PURGE_MARKER" ]; then
            rm -f "$PURGE_MARKER" 2>/dev/null || { emit '{"ok":false,"error":"标记文件删除失败"}'; return 1; }
            log "已撤销「卸载即清除」：卸载模块将保留用户数据。"
        else
            log "本来就没开启「卸载即清除」，无需撤销。"
        fi
        emit "{\"ok\":true,\"purge_on_uninstall\":false,\"linux_home\":\"$(jesc "$LH")\"}"
        return 0
    fi

    if [ ! -d "$LH" ]; then
        log "环境根不存在，无需清除：$LH"
        emit "{\"ok\":true,\"purged\":false,\"reason\":\"not_found\",\"linux_home\":\"$(jesc "$LH")\"}"
        return 0
    fi

    local sz; sz="$(_fp_size "$LH")"
    if [ "$yes" != "1" ]; then
        log "空跑模式：将删除 $LH（$(_fp_human "$sz")）"
        log "确认无误后重跑：linuxctl purge --yes"
        emit "{\"ok\":true,\"purged\":false,\"dry_run\":true,\"linux_home\":\"$(jesc "$LH")\",\"size_bytes\":$sz}"
        return 0
    fi

    # 1) 先停（幂等；已经停了也无所谓）
    log "先停止环境…"
    cmd_stop >/dev/null 2>&1 || true

    # 2) 确认没有运行态残留；有就拒绝，避免"删一半"
    local loops mounts
    loops="$(_fp_loops)"; mounts="$(_fp_mounts)"
    if [ -n "$loops" ] || [ -n "$mounts" ]; then
        log "拒绝删除：仍有运行态残留（挂载点或 loop 设备），现在删会删不干净。"
        [ -n "$mounts" ] && printf '%s\n' "$mounts" | sed 's/^/  挂载: /' >&2
        [ -n "$loops" ]  && printf '%s\n' "$loops"  | sed 's/^/  loop: /' >&2
        log "请先手动卸载（umount）对应挂载点并释放 loop，再重跑本命令。"
        emit '{"ok":false,"error":"仍有挂载/loop 残留，已拒绝删除以避免删不干净"}'
        return 1
    fi

    # 3) 删（只删环境根；模块目录由管理器负责）
    log "删除 $LH（$(_fp_human "$sz")，不可恢复）…"
    if ! rm -rf "$LH" 2>/dev/null; then
        emit '{"ok":false,"error":"删除失败（可能仍有占用），请查看上文"}'
        return 1
    fi
    if [ -d "$LH" ]; then
        local left; left="$(_fp_size "$LH")"
        log "删除后仍残留 $(_fp_human "$left")（可能有不可删的挂载点）"
        emit "{\"ok\":false,\"error\":\"删除不完整\",\"remaining_bytes\":$left}"
        return 1
    fi

    # 注意：这里**不要用反引号**做强调 —— 它在双引号里会被 shell 当成命令替换执行
    # （实测过：日志变成 `linuxctl: command not found`，且真的会去执行那条命令）。
    log "已清除。可用「linuxctl footprint」复核（应全部为不存在/无残留）。"
    emit "{\"ok\":true,\"purged\":true,\"linux_home\":\"$(jesc "$LH")\",\"freed_bytes\":$sz}"
    return 0
}

# ===========================================================================
# 分发（子命令自己处理错误，这里不因 set -e 提前退出）
# ===========================================================================
main() {
    [ $# -gt 0 ] || { usage; exit 2; }
    local sub="$1"; shift

    # 目录按需创建 ── **只读子命令不建**。
    # 踩过的坑：这里原来无条件 mkdir，于是 `footprint`（只读残留报告）在环境根不存在时
    # **先把它建出来再报告"存在"**，得出完全错误的结论。只读工具不该有副作用。
    case "$sub" in
        footprint|status|logs|doctor|whereami|version|help|-h|--help) : ;;
        # dsh info 也是只读的；dsh builtin/install 自己会 mkdir 需要的那几个目录
        dsh) : ;;
        *) mkdir -p "$RUN_DIR" "$LAYERS_DIR" "$ETC_DIR" 2>/dev/null || true ;;
    esac

    case "$sub" in
        provision) cmd_provision "$@" ;;
        start)     cmd_start "$@" ;;
        stop)      cmd_stop "$@" ;;
        status)    cmd_status "$@" ;;
        attach)    cmd_attach "$@" ;;
        exec)      cmd_exec "$@" ;;
        logs)      cmd_logs "$@" ;;
        snapshot)  cmd_snapshot "$@" ;;
        restore)   cmd_restore "$@" ;;
        reset)     cmd_reset "$@" ;;
        update)
            # `linuxctl update dsh`（**不带文件**）= 从内置官方频道装/更新 dsh 层（一条指令）。
            # 带文件时保持老语义：安装本地裸镜像（App / WebUI 走的就是它）。
            if [ $# -eq 1 ] && [ "$1" = "dsh" ]; then
                cmd_dsh_install
            else
                cmd_update "$@"
            fi ;;
        dsh)       cmd_dsh "$@" ;;
        rollback)  cmd_rollback "$@" ;;
        doctor)    cmd_doctor "$@" ;;
        whereami)  cmd_whereami "$@" ;;
        footprint) cmd_footprint "$@" ;;
        purge)     cmd_purge "$@" ;;
        update-check)    cmd_update_proxy check "$@" ;;
        update-apply)    cmd_update_proxy apply "$@" ;;
        update-versions) cmd_update_proxy versions "$@" ;;
        update-module-info)  cmd_update_proxy module-info "$@" ;;
        update-module-check) cmd_update_proxy module-check "$@" ;;
        version)   emit '{"name":"linuxctl","mode":"root","schema":1}' ;;
        -h|--help|help) usage; exit 0 ;;
        *)         log "未知子命令：$sub"; usage; exit 2 ;;
    esac
    exit $?
}

main "$@"
