#!/bin/bash
# =============================================================================
# DSHroid — rootfs 内的环境入口 + supervisor（entry.sh）        v1.1.0
#
# 由 proot 启动：proot ... /opt/dshroid/entry.sh
#
# 职责：
#   A. 环境准备（architecture.md §3.2）：
#        /etc/hosts、时区(/etc/localtime)、HOME=/root、DSH_HOME=/root/.dsh、
#        DNS 兜底检查、加载密钥环境文件 /root/.dsh/env（0600，只经环境变量传递）
#   B. supervisor（**不能** `exec node ...`，依据 §3.3 的实测鉴权模型）：
#        `dsh web` 的登录令牌 launchToken = base64url(randomBytes(...))，
#        **只存在于进程内、不落任何文件**，唯一出口是它启动时打印的那一行：
#            dsh web: http://127.0.0.1:<port>/?token=<launchToken>[ (LAN: ...)]
#        裸 GET / 默认 401，App 必须拿带令牌的 URL 才能登录。
#        所以必须「后台启动 + 截获 stdout/stderr + 解析带令牌 URL + 落盘 +
#        前台等待 + SIGTERM 透传」。
#
#   C. 落盘（宿主 $LINUX_HOME/run 经 bind 出现在本环境内 /run/dshroid，
#      两边是同一批 inode，所以这里写 = 宿主侧写）：
#        run/dsh.url   0600  带令牌 URL（App 用它登录；进程退出即失效）
#        run/dsh.port         实际生效端口（以 URL 里的端口为准）
#        run/dsh.pid          受监管 node 的 PID（+ dsh.pid.starttime 防 PID 复用）
#        run/linux.log        dsh 的原始 stdout+stderr 追加到这里（含那一行 `dsh web: ...`，
#                             便于排查；打到 stderr 也能抓到，因为 stdout/stderr 同向）
#
# 明令禁止：NODE_OPTIONS --import=... 之类的注入式补丁（DSHA 那套脆弱做法的根源）。
#           本脚本不但不注入，还会主动 unset 掉继承来的 NODE_OPTIONS。
#
# 不做「崩溃自动重启」：反复崩会变成静默重启循环，反而难排查；
# 崩溃由 linuxctl status 的 state=error + last_error 暴露给用户。
#
# 注意：proot 不虚拟 PID，所以这里写下的 PID 在宿主上同样有效
#       （linuxctl status/stop 依赖这一点）。
# =============================================================================
set -uo pipefail

RUNDIR=${DSHROID_RUN_DIR:-/run/dshroid}
LOGFILE="$RUNDIR/linux.log"
URLFILE="$RUNDIR/dsh.url"
PIDFILE="$RUNDIR/dsh.pid"
STARTFILE="$RUNDIR/dsh.pid.starttime"
PORTFILE="$RUNDIR/dsh.port"
ERRFILE="$RUNDIR/last_error"
STATEFILE="$RUNDIR/state"

ts()   { date -u '+%H:%M:%S' 2>/dev/null || printf '??:??:??'; }
log()  { printf '[entry %s] %s\n' "$(ts)" "$*" >&2; }
warn() { printf '[entry 警告] %s\n' "$*" >&2; }
err()  { printf '[entry 错误] %s\n' "$*" >&2; }

# =============================================================================
# 函数区（放在主流程之前：DSHROID_ENTRY_LIB=1 时可以只加载函数做自测）
# =============================================================================
write_atomic() { # write_atomic <file> <content> [mode]：先写临时文件再 mv，避免读到半截
  local f=$1 c=$2 m=${3:-0600} t
  t="$f.tmp.$$"
  printf '%s\n' "$c" >"$t" 2>/dev/null || { rm -f -- "$t" 2>/dev/null; return 1; }
  chmod "$m" "$t" 2>/dev/null || true
  mv -f -- "$t" "$f" 2>/dev/null || { rm -f -- "$t" 2>/dev/null; return 1; }
  return 0
}

write_state() { # write_state <stopped|starting|running|error>
  printf '%s\n' "$1" >"$STATEFILE.tmp.$$" 2>/dev/null &&
    mv -f "$STATEFILE.tmp.$$" "$STATEFILE" 2>/dev/null || true
  return 0
}

save_error() { # 把失败原因写回宿主可见的 run/last_error，供 linuxctl status 使用
  write_atomic "$ERRFILE" "$1" 0644 || true
  write_state error
  return 0
}

die() { err "$1"; save_error "entry.sh: $1"; exit 1; }

proc_starttime() { # /proc/<pid>/stat 第 22 项（clock ticks）
  local p="${1:-}" s=""
  [ -n "$p" ] || return 0
  s=$(cat "/proc/$p/stat" 2>/dev/null || true)
  [ -n "$s" ] || return 0
  s=${s##*') '}
  # shellcheck disable=SC2086
  set -- $s                  # $1 = 第 3 项(state)，故第 22 项 = $20
  printf '%s' "${20-}"
  return 0
}

url_port_of() { # 从 URL 里取端口
  printf '%s' "${1:-}" | sed -n 's|^http://[^:/]*:\([0-9][0-9]*\).*|\1|p'
  return 0
}

# --- 契约 §3.3 的解析规则 -----------------------------------------------------
# 匹配以 `dsh web: ` 开头的行；取 `http://127.0.0.1:` 起、到**第一个空白**为止的子串
# （不能贪婪匹配到行尾，否则会吞掉 " (LAN: http://...)"）；必须含 `?token=`。
# 只扫「本次启动之后」新写入的日志行，避免把上一轮重启前的旧令牌当成本次令牌。
parse_url_from_log() { # parse_url_from_log <logfile> <start_line>
  local log="${1:-}" start="${2:-1}" url=""
  [ -f "$log" ] || return 0
  case "$start" in ''|*[!0-9]*) start=1 ;; esac
  # tr -d '\r'：行尾 CR（CRLF 输出）必须去掉，否则会混进令牌里
  url=$(sed -n "${start},\$p" "$log" 2>/dev/null | tr -d '\r' \
        | sed -n 's/^[[:space:]]*dsh web: \(http:\/\/127\.0\.0\.1:[^ ]*\).*/\1/p' \
        | grep -F '?token=' | head -n1 || true)
  if [ -z "$url" ]; then
    # 兜底：更宽松（仍然「到第一个空白为止」且必须含 ?token=），
    # 以适配 --host 未来被改成别的地址的情况。
    url=$(sed -n "${start},\$p" "$log" 2>/dev/null | tr -d '\r' \
          | sed -n 's/^[[:space:]]*dsh web: \([^ ]*\).*/\1/p' \
          | grep -E '^https?://' | grep -F '?token=' | head -n1 || true)
  fi
  printf '%s' "$url"
  return 0
}

persist_url() { # persist_url <url>：写 run/dsh.url(0600) + 用 URL 里的端口覆盖 run/dsh.port
  local url="${1:-}" p=""
  [ -n "$url" ] || return 1
  case "$url" in
    *'?token='*) : ;;
    *) return 1 ;;
  esac
  write_atomic "$URLFILE" "$url" 0600 || return 1
  p=$(url_port_of "$url")
  case "$p" in ''|*[!0-9]*) p="" ;; esac
  [ -n "$p" ] && write_atomic "$PORTFILE" "$p" 0644
  return 0
}

parse_url_loop() { # parse_url_loop <start_line>：轮询直到抓到 URL 或 node 退出
  local start="${1:-1}" i=0 warn_at=300
  while :; do
    local u=""
    u=$(parse_url_from_log "$LOGFILE" "$start")
    if [ -n "$u" ]; then
      if persist_url "$u"; then
        log "已捕获带令牌 URL（port=$(url_port_of "$u")）→ $URLFILE"
        return 0
      fi
    fi
    # node 已经死了就没必要继续找
    kill -0 "${DSH_PID:-0}" 2>/dev/null || return 1
    i=$(( i + 1 ))
    [ "$i" -eq "$warn_at" ] && warn "启动 60s 仍未在日志里抓到 'dsh web: ...?token=' 行：status.dsh.url 会是 null，App 将无法登录（见 $LOGFILE）"
    sleep 0.2
  done
}

cleanup_run_files() { # 清掉会过期的凭证类文件（dsh.url 里的令牌随进程失效）
  rm -f -- "$URLFILE" "$PIDFILE" "$STARTFILE" "$PORTFILE" 2>/dev/null || true
  return 0
}

# 防重入的停止流程：TERM 透传给 node 与解析器，最多等 15s，再 KILL
SUPERVISOR_STOPPING=0
shutdown() {
  [ "$SUPERVISOR_STOPPING" = "1" ] && return 0
  SUPERVISOR_STOPPING=1
  log "收到停止信号，向 dsh(pid=${DSH_PID:-?}) 与解析器(pid=${PARSER_PID:-?}) 转发"
  [ -n "${PARSER_PID:-}" ] && kill -TERM "$PARSER_PID" 2>/dev/null || true
  [ -n "${DSH_PID:-}" ] && kill -TERM "$DSH_PID" 2>/dev/null || true
  local i=0
  while [ "$i" -lt 150 ]; do
    kill -0 "${DSH_PID:-0}" 2>/dev/null || break
    sleep 0.1
    i=$(( i + 1 ))
  done
  if kill -0 "${DSH_PID:-0}" 2>/dev/null; then
    log "dsh 未在 15s 内退出，发送 KILL"
    kill -KILL "$DSH_PID" 2>/dev/null || true
  fi
  wait "${DSH_PID:-}" 2>/dev/null || true
  cleanup_run_files
  write_state stopped
  log "supervisor 退出（stop 请求）"
  exit 0
}

# =============================================================================
# 自测钩子：DSHROID_ENTRY_LIB=1 时只加载上面的函数，不碰系统文件、不启动任何东西。
# （entry.sh 会写 /etc/hosts、/etc/localtime、/etc/resolv.conf，在开发机上直接跑很危险，
#   所以留下这个钩子，让解析器/落盘逻辑可以在宿主上被真实测试。）
# =============================================================================
if [ "${DSHROID_ENTRY_LIB:-0}" = "1" ]; then
  return 0 2>/dev/null || exit 0
fi

# 如果 stderr 不是 linux.log（例如有人手工在环境里跑 entry.sh），就把日志补到那里去，
# 但绝不重复重定向（start.sh 已经把 fd1/fd2 指向同一个文件了）。
if [ -w "$RUNDIR" ]; then
  cur_err=$(readlink /proc/self/fd/2 2>/dev/null || true)
  if [ "$cur_err" != "$LOGFILE" ]; then
    exec >>"$LOGFILE" 2>&1
  fi
fi

# -----------------------------------------------------------------------------
# A0. 基本环境
# -----------------------------------------------------------------------------
mkdir -p -- "$RUNDIR" 2>/dev/null || true
rm -f -- "$ERRFILE" 2>/dev/null || true
# 启动先清掉上一轮的令牌/端口（进程被 kill -9 时没机会清理，这里双保险）
cleanup_run_files

PORT=${DSHROID_PORT:-}
case "$PORT" in ''|*[!0-9]*) PORT=3080 ;; esac
if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then PORT=3080; fi
HOST=${DSHROID_HOST:-127.0.0.1}
GUEST_NAME=${DSHROID_HOSTNAME:-dshroid}

export HOME=/root
export DSH_HOME=/root/.dsh
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export LANG=${LANG:-C.UTF-8}
export TMPDIR=${TMPDIR:-/tmp}
export SHELL=/bin/bash
# proot 模式下没有真实 root：明确告诉环境内的程序「别指望 mount/chown」。
export DSHROID_MODE=proot
export DSHROID_FAKE_ROOT=${DSHROID_FAKE_ROOT:-0}

# 拒绝注入式补丁：DSHroid 不做 NODE_OPTIONS --import 那一套。
if [ -n "${NODE_OPTIONS:-}" ]; then
  warn "清除继承来的 NODE_OPTIONS（DSHroid 不使用注入式启动补丁）：$NODE_OPTIONS"
  unset NODE_OPTIONS
fi

mkdir -p -- /root/.dsh 2>/dev/null || true
chmod 0700 /root/.dsh 2>/dev/null || true
mkdir -p -- /tmp 2>/dev/null || true
chmod 1777 /tmp 2>/dev/null || true

log "proot 环境入口启动：pid=$$ uid=$(id -u 2>/dev/null || printf '?')/gid=$(id -g 2>/dev/null || printf '?')"
if [ "$DSHROID_FAKE_ROOT" = "1" ]; then
  warn "当前为伪造 root（proot -0）：id 显示 uid=0 但没有真实 capabilities，"
  warn "  mount/chown/mknod 等操作依旧失败；这不是真 root。"
fi

# -----------------------------------------------------------------------------
# A1. /etc/hosts
# -----------------------------------------------------------------------------
if [ -w /etc ] || [ -w /etc/hosts ]; then
  cat >/etc/hosts <<EOF
# 由 DSHroid entry.sh 写入 $(date -u '+%Y-%m-%dT%H:%M:%SZ')
127.0.0.1        localhost
127.0.0.1        $GUEST_NAME
::1              localhost ip6-localhost ip6-loopback
EOF
  log "已写 /etc/hosts（主机名 $GUEST_NAME）"
else
  warn "/etc/hosts 不可写，跳过"
fi

# -----------------------------------------------------------------------------
# A2. 时区
# -----------------------------------------------------------------------------
TZ_WANT=${DSHROID_TZ:-}
[ -n "$TZ_WANT" ] || TZ_WANT=$(cat /etc/dshroid-tz 2>/dev/null | tr -d '[:space:]' || true)
[ -n "$TZ_WANT" ] || TZ_WANT=UTC
if [ -f "/usr/share/zoneinfo/$TZ_WANT" ]; then
  if ln -sfn "/usr/share/zoneinfo/$TZ_WANT" /etc/localtime 2>/dev/null; then
    printf '%s\n' "$TZ_WANT" >/etc/timezone 2>/dev/null || true
    log "时区设为 $TZ_WANT"
  else
    warn "设置 /etc/localtime 失败（保持系统默认）"
  fi
else
  warn "宿主时区 $TZ_WANT 在 rootfs 里没有对应 zoneinfo，回退 UTC"
  TZ_WANT=UTC
fi
export TZ="$TZ_WANT"

# -----------------------------------------------------------------------------
# A3. DNS 兜底检查（正常由宿主 start.sh 写好）
# -----------------------------------------------------------------------------
if [ ! -s /etc/resolv.conf ] || ! grep -q '^nameserver' /etc/resolv.conf 2>/dev/null; then
  warn "/etc/resolv.conf 里没有 nameserver，尝试用 /system/etc/resolv.conf 兜底"
  if [ -s /system/etc/resolv.conf ]; then
    cp -f /system/etc/resolv.conf /etc/resolv.conf 2>/dev/null || true
  fi
  if ! grep -q '^nameserver' /etc/resolv.conf 2>/dev/null; then
    printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:2 attempts:3\n' >/etc/resolv.conf 2>/dev/null || true
    warn "仍拿不到 DNS，已写入公共 DNS 兜底（1.1.1.1 / 8.8.8.8）"
  fi
else
  log "DNS：$(grep -c '^nameserver' /etc/resolv.conf 2>/dev/null || printf '?') 条 nameserver（来源：${DSHROID_DNS_SOURCE:-宿主 start.sh}）"
fi

# -----------------------------------------------------------------------------
# A4. 密钥环境文件（0600；只经环境变量传递，永不进命令行）
# -----------------------------------------------------------------------------
if [ -f /root/.dsh/env ]; then
  mode=$(stat -c %a /root/.dsh/env 2>/dev/null || printf '')
  if [ -n "$mode" ] && [ "$mode" != "600" ]; then
    warn "/root/.dsh/env 权限是 $mode，正在收紧到 600"
    chmod 0600 /root/.dsh/env 2>/dev/null || true
  fi
  set -a
  # shellcheck disable=SC1091
  if ! . /root/.dsh/env; then
    warn "/root/.dsh/env 解析失败（语法错误？）已忽略"
  fi
  set +a
  log "已加载 /root/.dsh/env（变量名不打印，值更不会打印）"
fi

# -----------------------------------------------------------------------------
# B0. supervisor 前置：找到 node 与 dsh 入口
# -----------------------------------------------------------------------------
NODE_BIN=""
for c in "${DSHROID_NODE_BIN:-}" /usr/local/bin/node /usr/bin/node; do
  [ -n "$c" ] && [ -x "$c" ] && { NODE_BIN=$c; break; }
done
[ -n "$NODE_BIN" ] || NODE_BIN=$(command -v node 2>/dev/null || true)
[ -n "$NODE_BIN" ] && [ -x "$NODE_BIN" ] || die "环境内找不到 node。rootfs 的 runtime 层不完整：请 linuxctl update runtime <file> 或 provision --seed 重新部署。"

DSH_BIN=""
for c in "${DSHROID_DSH_BIN:-}" /usr/local/bin/dsh \
         /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js; do
  [ -n "$c" ] && [ -f "$c" ] && { DSH_BIN=$c; break; }
done
[ -n "$DSH_BIN" ] || die "环境内找不到 DSH：既没有 /usr/local/bin/dsh，也没有 /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js。请 linuxctl update dsh <dsh层tar> 安装。"

# -----------------------------------------------------------------------------
# B1. 记下日志行号，然后**后台**启动 dsh（stdout+stderr 都追加到 linux.log）
#     —— 先记行号再启动，避免 dsh 打印太快、解析器还没起来就错过了那一行。
#     —— stdout 与 stderr 同向追加：即使 DSH 把 `dsh web: ...` 打到 stderr 也能抓到
#        （契约 §3.3 只保证那一行的格式，不保证它走哪个流），原始行同时留在日志里便于排查。
# -----------------------------------------------------------------------------
LOG_LINES=0
[ -f "$LOGFILE" ] && LOG_LINES=$(wc -l <"$LOGFILE" 2>/dev/null | tr -dc '0-9' || true)
case "${LOG_LINES:-}" in ''|*[!0-9]*) LOG_LINES=0 ;; esac

write_state starting
log "supervisor：$NODE_BIN $DSH_BIN web --no-open --host $HOST --port $PORT（日志起始行 $(( LOG_LINES + 1 ))）"
log "HOME=$HOME DSH_HOME=$DSH_HOME TZ=$TZ；带令牌 URL 会写入 $URLFILE（0600）"

"$NODE_BIN" "$DSH_BIN" web --no-open --host "$HOST" --port "$PORT" >>"$LOGFILE" 2>&1 &
DSH_PID=$!
log "dsh 已后台启动，pid=$DSH_PID"

# 先落 PID（含 starttime，宿主用它防 PID 复用）与请求端口；
# 端口稍后会被 URL 里的实际端口覆盖。
write_atomic "$STARTFILE" "$(proc_starttime "$DSH_PID")" 0644 || true
write_atomic "$PORTFILE" "$PORT" 0644 || warn "写 dsh.port 失败"
write_atomic "$PIDFILE" "$DSH_PID" 0644 || warn "写 dsh.pid 失败"
write_state running

# -----------------------------------------------------------------------------
# B2. 后台解析带令牌 URL（契约 §3.3）
# -----------------------------------------------------------------------------
parse_url_loop "$(( LOG_LINES + 1 ))" &
PARSER_PID=$!

# -----------------------------------------------------------------------------
# B3. 前台等待 + 信号透传
# -----------------------------------------------------------------------------
trap shutdown TERM INT HUP QUIT

wait "$DSH_PID"
RC=$?

# 正常路径：dsh 自己退出了（崩溃或它自己的 shutdown）
if [ -n "${PARSER_PID:-}" ]; then
  kill -TERM "$PARSER_PID" 2>/dev/null || true
  wait "$PARSER_PID" 2>/dev/null || true
fi
# 令牌是登录凭据且随进程失效：进程没了就必须删掉，避免 App 读到过期令牌
if [ ! -s "$URLFILE" ]; then
  warn "dsh 退出前没有捕获到带令牌 URL（App 无法登录）；日志：$LOGFILE"
fi
cleanup_run_files
if [ "$RC" -ne 0 ]; then
  log "dsh 进程异常退出，rc=$RC"
  save_error "dsh 进程异常退出（rc=$RC）；详见 $LOGFILE"
else
  log "dsh 进程正常退出，rc=$RC"
  write_state stopped
fi
exit "$RC"
