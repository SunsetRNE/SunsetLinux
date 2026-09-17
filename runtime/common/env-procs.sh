#!/system/bin/sh
# =============================================================================
# sunsetlinux · runtime/common/env-procs.sh
#
# 「环境内进程」的识别与清理 —— linuxctl（dsh start/stop、status）与 stop.sh 共用一份。
# 为什么必须共用：两处各写一遍必然漂移（本仓库已经踩过一回：doctor 与 linuxctl 的
# layer_has_path 各写一份，后来抽到 runtime/common/layer-inspect.sh）。
#
# ★ 唯一可靠的判据：**/proc/<pid>/root 指向我们的 rootfs**。
#   真机实测（2026-09-17）：别的环境里的 dsh 长得**一模一样** —— DSHA（免 root/proot 版）
#   跑的是 `node /usr/local/bin/dsh web --no-open --host 127.0.0.1 --port 0`，
#   我们的（chroot 进 overlay）是 `… --port 3080`；**光看命令行分不开**，
#   但 /proc/<pid>/root 一个是 `/`、一个是 $LINUX_HOME/rootfs。
#   只看命令行去 kill 会误杀别的环境 —— 所以判据必须带 root 这一条。
#
# 调用方要设置：
#   ENV_ROOTFS   必填（环境根，例如 /data/sunsetlinux/rootfs）。为空时本文件**什么都不做**
#                （判据残缺时宁可不认进程，也不误杀）。
# 可选：
#   ENV_PROC_KILL_WAIT   TERM 之后最多等几个 0.1s（默认 30 → 3 秒）
# =============================================================================

# 进程的 root（判不了就输出空 —— 权限不足/进程已消失都算"判不了"）
env_proc_root_of() {
    readlink "/proc/$1/root" 2>/dev/null || printf ''
}

# 进程命令行（NUL 转空格；读不到给空）
env_proc_cmd_of() {
    tr '\0' ' ' < "/proc/$1/cmdline" 2>/dev/null || printf ''
}

# env_proc_pids [cmdline 通配] —— 一行一个 pid
#   不传通配 = 环境内**所有**进程（stop 用：停环境就该把里面的一切都结束）
#   传 '*dsh*web*' = 只挑 DSH（dsh stop 用：**不能**顺手把用户开着的终端会话杀掉，
#   终端也是 chroot 进 rootfs 的进程）
env_proc_pids() {
    [ -n "${ENV_ROOTFS:-}" ] || return 0
    local pat="${1:-}"
    ps -A -o PID,ARGS 2>/dev/null | while read -r _pid _cmd; do
        case "${_pid:-}" in ''|*[!0-9]*) continue ;; esac
        [ "$(env_proc_root_of "$_pid")" = "$ENV_ROOTFS" ] || continue
        if [ -n "$pat" ]; then
            case "${_cmd:-}" in
                $pat) ;;
                *) continue ;;
            esac
        fi
        printf '%s\n' "$_pid"
    done
}

# env_proc_kill_all [cmdline 通配] —— TERM →（等）→ KILL；**stdout 保持干净**
env_proc_kill_all() {
    [ -n "${ENV_ROOTFS:-}" ] || return 0
    local pat="${1:-}" _pid _i=0 _n="${ENV_PROC_KILL_WAIT:-30}"
    for _pid in $(env_proc_pids "$pat"); do
        env_proc_log "发现环境内进程（pid=$_pid，root=$ENV_ROOTFS）：$(env_proc_cmd_of "$_pid")"
        kill -TERM "$_pid" 2>/dev/null || true
        _i=0
        while [ "$_i" -lt "$_n" ] && [ -d "/proc/$_pid" ]; do sleep 0.1; _i=$(( _i + 1 )); done
        if [ -d "/proc/$_pid" ]; then
            env_proc_log "  pid=$_pid 未在期限内退出，发送 KILL"
            kill -KILL "$_pid" 2>/dev/null || true
        fi
    done
    return 0
}

# 日志：调用方已经定义了 log() 就用它的（写进 run/linux.log 的那条路径），否则退到 stderr
env_proc_log() {
    if command -v log >/dev/null 2>&1; then log "$@"; else printf '%s\n' "$*" >&2; fi
}
