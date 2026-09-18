#!/system/bin/sh
# =============================================================================
# sunsetlinux · host-channel.sh —— **宿主通道**（默认关；在环境内部调用）
#
# 用途：给"环境里的 agent"一条**有名字、有提示、有审计**的路去操作 Android 宿主，
#       而不是各自去 `chroot /proc/1/root` 摸黑走（那条路今天已经通着，只是隐式、无声、
#       无记录）。设计与安全模型见 docs/host-channel.md。
#
# ⚠️ 它**不新增任何权限**：环境本来就是 uid 0 + SELinux 域 u:r:ksu:s0（宿主 root 域），
#    chroot 从来不是安全边界。本脚本做的是把"事实上已经存在的访问"变成：
#      · **默认关**，开关在**宿主侧** $LINUX_HOME/etc/host-channel.json —— 环境里改不到它；
#      · 每次调用写一行**审计**（时间、动作、命令、退出码）；
#      · **路径白名单**（防手滑，不是安全边界：命令可以用变量绕过它）。
#
# 用法（在环境内部）：
#   /opt/sunsetlinux/host-channel.sh status
#   /opt/sunsetlinux/host-channel.sh run <cmd> [args...]
#
# 退出码：0 成功；3 = 未启用；4 = 被白名单拒绝；5 = 参数错；6 = 宿主根不可达；
#         其余 = 被调命令自己的退出码。
# =============================================================================
set -uo pipefail

LH=/data/sunsetlinux
CFG="$LH/etc/host-channel.json"
AUDIT="$LH/run/host-channel.log"
HOST_ROOT=/proc/1/root

cfg_enabled() {
    [ -f "$CFG" ] || return 1
    grep -q '"enabled"[[:space:]]*:[[:space:]]*true' "$CFG" 2>/dev/null
}

# 白名单前缀，一行一个（从 "allow": [ "…", "…" ] 里抠出来；不引 JSON 解析器）
cfg_allow_list() {
    [ -f "$CFG" ] || return 0
    tr -d '\n' < "$CFG" 2>/dev/null \
        | sed -n 's/.*"allow"[[:space:]]*:[[:space:]]*\[\([^]]*\)\].*/\1/p' \
        | tr ',' '\n' | tr -d '"' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | grep -v '^$' || true
}

audit() { # audit <verb> <detail> <rc>
    printf '%s\t%s\t%s\trc=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" "$2" "$3" >> "$AUDIT" 2>/dev/null || true
}

usage() {
    cat >&2 <<'EOF'
host-channel.sh —— 宿主通道（默认关）

用法：
  host-channel.sh status                 看开关状态与最近 10 条审计
  host-channel.sh run <cmd> [args...]    在宿主上执行一条命令（受白名单与审计约束）

开关（**只能在宿主侧改**）：/data/sunsetlinux/etc/host-channel.json
  { "enabled": false, "allow": ["/data/sunsetlinux/share", "/storage/emulated/0"] }
EOF
}

# 路径白名单：命令里出现的**绝对路径 token** 都必须落在 allow 前缀之下。
#
# ⚠️ 这里有个必须记住的形状（自测当场抓出来的）：`run` 收到的是**一整条命令字符串**
#   （`run 'cat /data/secret'` → $* = "cat /data/secret"），所以按"参数"逐个检查等于
#   什么都没查。正确做法是先把命令串**按空白切开成 token**，再逐个 token 看是不是绝对路径。
#   它不是 shell 词法分析（引号里的空格切不准、变量展开看不出来）—— 设计上就只当"防手滑"，
#   别当安全边界（见 docs/host-channel.md §3）。
check_allow() {
    local allow_list tok p ok
    allow_list="$(cfg_allow_list)"
    [ -n "$allow_list" ] || return 0
    # 引号/反引号先去掉（\047 = 单引号，避免在替换里嵌套引号）
    for tok in $(printf '%s' "$*" | tr ' \t' '\n\n' | tr -d '"\047`'); do
        case "$tok" in
            /*)
                ok=0
                for p in $allow_list; do
                    case "$tok" in "$p"|"$p"/*) ok=1 ;; esac
                    [ "$ok" = "1" ] && break
                done
                if [ "$ok" != "1" ]; then
                    printf '拒绝：命令里的路径不在白名单内：%s\n' "$tok" >&2
                    return 4
                fi ;;
        esac
    done
    return 0
}

case "${1:-}" in
    status)
        if cfg_enabled; then
            printf '宿主通道：**已启用**\n'
        else
            printf '宿主通道：**未启用**（默认）\n'
            printf '  开关（宿主侧）：%s\n' "$CFG"
        fi
        printf '白名单：\n'
        cfg_allow_list | sed 's/^/  · /' || true
        printf '最近审计：\n'
        if [ -f "$AUDIT" ]; then tail -n 10 "$AUDIT" | sed 's/^/  /'; else printf '  （还没有记录）\n'; fi
        exit 0 ;;

    run)
        shift
        [ $# -gt 0 ] || { usage; exit 5; }
        if ! cfg_enabled; then
            printf '宿主通道未启用（默认关）。\n' >&2
            printf '开关在宿主侧（环境里改不到）：%s\n' "$CFG" >&2
            printf '要开就在 MT / 宿主 root shell 里跑：linuxctl host-channel on\n' >&2
            audit "run-denied" "$*" 3
            exit 3
        fi
        if ! check_allow "$@"; then
            audit "run-denied-allowlist" "$*" 4
            exit 4
        fi
        # 跳出 chroot：/proc 是宿主 PID 视图（内核无 CONFIG_PID_NS），pid 1 的 root 就是真根；
        # 之后以宿主身份执行 —— 与环境里"uid 0 + ksu 域"是同一个主体，只是换了根与 cwd。
        if [ ! -x "$HOST_ROOT/system/bin/sh" ]; then
            printf '宿主根不可达（%s）—— 通道无法工作\n' "$HOST_ROOT" >&2
            audit "run-failed" "$*" 6
            exit 6
        fi
        audit "run" "$*" "start"
        "$HOST_ROOT/system/bin/chroot" "$HOST_ROOT" /system/bin/sh -c "$*"
        rc=$?
        audit "run-done" "$*" "$rc"
        exit $rc ;;

    ""|-h|--help|help) usage; exit 0 ;;
    *) printf 'host-channel.sh：未知子命令 %s\n' "$1" >&2; usage; exit 5 ;;
esac
