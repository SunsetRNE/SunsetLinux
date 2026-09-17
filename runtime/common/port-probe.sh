#!/system/bin/sh
# =============================================================================
# sunsetlinux · runtime/common/port-probe.sh
#
# 「这个端口在不在监听」的**唯一**实现 —— `start.sh`（启动前判占用）与
# `linuxctl.sh`（`dsh start` 端口被占时让路）共用一份。
# 为什么必须共用：本仓库已经因为"同一判断两处各写一遍"漂移过好几回
# （doctor 与 linuxctl 的 layer_has_path、stop.sh 与 linuxctl 的进程清理…）。
#
# 为什么不用 bash 的 `/dev/tcp`：设备侧真正跑的是 mksh（/system/bin/sh），
# **它没有 /dev/tcp** —— 实测同一个端口 bash 连得上、mksh 恒失败，
# 后果是"端口明明被占也判空闲"，而 `mksh -n` 抓不到（根本不是语法错）。
# 三层回退（与 runtime/proot/{start,linuxctl}.sh 同一套逻辑与顺序）：
#   1) /proc/net/tcp[6] 里找 LISTEN（st=0A）—— 纯 awk，Android 上一定有（首选）
#   2) nc -z   3) ss -ltn
#
# 调用方直接 source 本文件即可，不需要设置任何变量。
# =============================================================================

tcp_listen_local() { # tcp_listen_local <port>  →  0 = 有人在 LISTEN
    local hex=""
    hex=$(printf '%04X' "${1-}" 2>/dev/null || true)
    [ -n "$hex" ] || return 1
    awk -v want=":${hex}" '
        NR > 1 && $4 == "0A" && substr($2, length($2) - length(want) + 1) == want { f = 1; exit }
        END { exit(f ? 0 : 1) }
    ' /proc/net/tcp /proc/net/tcp6 2>/dev/null
}

port_busy() { # port_busy <port>  →  0 = 被占（三层回退都判不出来才返回 1）
    local p="${1-}"
    case $p in ''|*[!0-9]*) return 1 ;; esac
    # 去掉前导 0：`printf '%04X' 03080` 会按**八进制**解释 → 算出错误的端口号
    while :; do case $p in 0?*) p=${p#0} ;; *) break ;; esac; done
    tcp_listen_local "$p" && return 0
    if command -v nc >/dev/null 2>&1; then
        nc -z -w 2 127.0.0.1 "$p" >/dev/null 2>&1 && return 0
    fi
    if command -v ss >/dev/null 2>&1; then
        ss -ltn 2>/dev/null | awk -v want=":${p}" '
            NR > 1 && substr($4, length($4) - length(want) + 1) == want { f = 1; exit }
            END { exit(f ? 0 : 1) }
        ' && return 0
    fi
    return 1
}

port_pick_free() { # port_pick_free <首选端口> [尝试次数]  →  打印一个空闲端口
    # 为什么需要它：真机上"另一个环境"（免 root 的 DSHA）会占着 127.0.0.1:3080，
    #   此时照原端口起 DSH 只会 bind 失败，而用户看到的是"起不来"却不知道为什么。
    #   让路后实际端口由 supervise.sh 写进 run/dsh.port（登录 URL 里也带真实端口），
    #   App 读的是 URL/端口文件 ⇒ 换端口对上层透明。
    # 找不到空闲端口就原样打印首选值（宁可让 bind 失败报原错，也不要编造一个端口）。
    local p="${1:-3080}" tries="${2:-40}" i=0
    case $p in ''|*[!0-9]*) p=3080 ;; esac
    if [ "$p" -lt 1 ] || [ "$p" -gt 65535 ]; then p=3080; fi
    port_busy "$p" || { printf '%s' "$p"; return 0; }
    i=0
    while [ "$i" -lt "$tries" ]; do
        p=$(( p + 1 ))
        [ "$p" -gt 65535 ] && p=1024
        port_busy "$p" || { printf '%s' "$p"; return 0; }
        i=$(( i + 1 ))
    done
    printf '%s' "${1:-3080}"
}
