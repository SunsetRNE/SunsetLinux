#!/usr/bin/env bash
# =============================================================================
# SunsetLinux — linuxctl（proot / 非 root 降级运行时）          v1.0.0
#
# 契约（与 root 版完全一致，见 docs/architecture.md §3）：
#   provision / start / stop / status / attach / exec / logs /
#   snapshot / restore / reset / update / doctor
#
# 铁律：
#   1. stdout 只放 JSON；人类可读信息一律走 stderr。
#      status 的 stdout 严格遵循 §3.1 schema（键不可省略，取不到用 null）。
#      其它子命令输出「完整 §3.1 对象 + action/ok/result 三个附加键」，
#      这样 App 只写一套解析代码。
#   2. 所有子命令幂等。
#   3. mode 字段恒为 "proot"。
#
# 刻意保留的两处例外（见 README.md「stdout 契约的例外」，这是刻意的取舍）：
#   * logs（默认、不带 --json）把日志尾部原样写到 stdout —— 日志就是给人和日志页看的文本。
#   * attach / exec 的 stdout 就是所执行命令的 stdout/stderr，退出码即命令退出码。
#
# 与 root 版的差异（proot 模式没有 overlay / squashfs / upper.img）：
#   * 只有一个可直接解包的 rootfs 目录；rootfs 本身就是可写层。
#   * layers.* 是「逻辑映射」：base = rootfs 中 /usr/local 以外的部分，
#     runtime = rootfs/usr/local 内除 DSH 以外的部分，
#     dsh = rootfs/usr/local/lib/node_modules/@deepseek-ai/dsh。
#     三者 size 之和 = rootfs 实际占用；mounted 表示「该部分已就位」。
#   * storage.upper_used = rootfs 占用；upper_total = 宿主文件系统总容量
#     （proot 模式没有磁盘配额，这不是 quota，只是让 App 有个分母）。
#
# 重要（对应 findings.md §3.3）：proot 内的文件系统视图不可信。
#   本脚本一旦检测到自己被 ptrace 跟踪（TracerPid != 0，即被 proot 包裹），
#   就拒绝做任何「现场测量」（du / statvfs），只用 etc/state.json 里的缓存值，
#   并在 stderr 给出提示。这样从 rootfs 内调用也不会把 7 KB 当成 16 GB。
# =============================================================================
set -euo pipefail

SCHEMA_VERSION=1
MODE="proot"
LINUXCTL_VERSION="1.0.0"

PROG=$(basename -- "${BASH_SOURCE[0]}")
SELF_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)

# 可调参数（环境变量覆盖，便于测试与不同设备）
START_GRACE=${SUNSETLINUX_START_GRACE:-90}      # 超过这么多秒还不健康 → state=error
START_TIMEOUT=${SUNSETLINUX_START_TIMEOUT:-120} # start 等待就绪的上限
STOP_TIMEOUT=${SUNSETLINUX_STOP_TIMEOUT:-20}    # stop 等待进程退出的上限
LOCK_TIMEOUT=${SUNSETLINUX_LOCK_TIMEOUT:-30}    # 抢锁等待上限
DEFAULT_PORT=${SUNSETLINUX_DEFAULT_PORT:-3080}

# 由 resolve_paths 填充；LINUX_HOME 为空时全部为空字符串（build_status 会当作「不可得」）
# 注意：这里必须用 ${VAR:-} 形式，否则会把继承来的环境变量抹掉。
APP_FILES="${SUNSETLINUX_APP_FILES:-}"
LINUX_HOME="${LINUX_HOME:-}"
ROOTFS=""
ETC_DIR=""
RUN_DIR=""
SNAP_DIR=""
CACHE_DIR=""
BIN_DIR=""
LOG_FILE=""

# 运行时全局
CURRENT_CMD=""
FORCE_STATE=""
FORCE_LAST_ERROR=""
LOCK_FD=""
LOCK_DIR=""
TRACED=0

# =============================================================================
# 0. 日志（一律 stderr）
# =============================================================================
ts() { date -u '+%H:%M:%S' 2>/dev/null || printf '??:??:??'; }
log()  { printf '[linuxctl %s] %s\n' "$(ts)" "$*" >&2; }
warn() { printf '[linuxctl 警告] %s\n' "$*" >&2; }
err()  { printf '[linuxctl 错误] %s\n' "$*" >&2; }

# =============================================================================
# 1. JSON 小工具（不依赖 jq / python，因为 Android 侧可能两个都没有）
# =============================================================================
jstr() { # 把任意字符串编码成 JSON 字符串
  local s="${1-}"
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  s=${s//$'\n'/\\n}
  s=${s//$'\r'/\\r}
  s=${s//$'\t'/\\t}
  # 其余控制字符（0x00-0x1f）按需剔除，避免产出非法 JSON
  s=$(printf '%s' "$s" | tr -d '\000-\010\013\014\016-\037' 2>/dev/null || printf '%s' "$s")
  printf '"%s"' "$s"
}
jnull_or_str() { if [[ -n "${1-}" ]]; then jstr "$1"; else printf 'null'; fi }
jint() { if [[ "${1-}" =~ ^-?[0-9]+$ ]]; then printf '%s' "$1"; else printf 'null'; fi }
jbool() {
  case "${1-}" in
    true|1|yes|on) printf 'true' ;;
    false|0|no|off) printf 'false' ;;
    *) printf 'null' ;;
  esac
}
json_obj() { # json_obj <普通key> <已编码val> ... —— key 会被编码，value 必须已编码
  local out='{' first=1
  while (( $# >= 2 )); do
    (( first )) || out+=','
    out+=$(jstr "$1"):$2
    first=0
    shift 2
  done
  printf '%s}' "$out"
}
json_arr_str() { # json_arr_str a b c -> ["a","b"]
  local out='[' first=1 x
  for x in "$@"; do
    (( first )) || out+=','
    out+=$(jstr "$x")
    first=0
  done
  printf '%s]' "$out"
}
json_get_num() { # json_get_num <file> <key>：取第一个 "key": <数字>
  [[ -f "${1-}" ]] || return 0
  sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\(-\{0,1\}[0-9][0-9]*\).*/\1/p" "$1" 2>/dev/null | head -1
}
json_get_str() { # json_get_str <file> <key>：取第一个 "key": "字符串"
  [[ -f "${1-}" ]] || return 0
  sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$1" 2>/dev/null | head -1
}
json_get_bool() { # json_get_bool <file> <key>：true/false，取不到返回空
  [[ -f "${1-}" ]] || return 0
  sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\(true\|false\).*/\1/p" "$1" 2>/dev/null | head -1
}
# 带默认值的取法：注意 json_get_* 系列「取不到」时也会 return 0，
# 所以不能写 `$(json_get_bool f k || printf false)`，必须用下面这三个。
json_bool_or() { local v; v=$(json_get_bool "${1-}" "$2"); [[ -n "$v" ]] || v=$3; printf '%s' "$v"; }
json_str_or()  { local v; v=$(json_get_str  "${1-}" "$2"); [[ -n "$v" ]] || v=$3; printf '%s' "$v"; }
json_num_or()  { local v; v=$(json_get_num  "${1-}" "$2"); [[ -n "$v" ]] || v=$3; printf '%s' "$v"; }
# 从 package.json 里取第一个 "version"（单行/多行都成立；贪心匹配会取到最后一个，所以用 grep -o）
pkg_version_of() {
  local f="${1-}"
  [[ -f "$f" ]] || return 0
  grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -1 | sed 's/.*"\([^"]*\)"$/\1/'
  return 0
}

# =============================================================================
# 2. 路径解析
# =============================================================================
LINUX_HOME_ERR=""

resolve_paths() {
  local explicit="${LINUX_HOME:-}"
  local appfiles="${SUNSETLINUX_APP_FILES:-}"
  local home="" src=""

  if [[ -n "$explicit" ]]; then
    home=$explicit
    src="LINUX_HOME"
  elif [[ -n "$appfiles" ]]; then
    home="${appfiles%/}/linux"
    src="SUNSETLINUX_APP_FILES"
  elif [[ -d "$SELF_DIR/../rootfs" ]]; then
    # 兜底：linuxctl 被放在 $LINUX_HOME/bin/ 下时，父目录就是环境根。
    home=$(cd -- "$SELF_DIR/.." >/dev/null 2>&1 && pwd -P)
    src="脚本位置推断"
  else
    LINUX_HOME_ERR="未找到环境根：既没有设置 LINUX_HOME，也没有设置 SUNSETLINUX_APP_FILES。
  请二选一（推荐第一个，App 一般会自动注入）：
    export SUNSETLINUX_APP_FILES=/data/user/0/<包名>/files   # App 私有 files 目录
    export LINUX_HOME=\$SUNSETLINUX_APP_FILES/sunsetlinux           # 直接指定环境根
  提示：proot 模式的默认环境根是 \$APP_FILES/sunsetlinux（见 docs/architecture.md §2.2）。"
    LINUX_HOME=""; APP_FILES="$appfiles"
    ROOTFS=""; ETC_DIR=""; RUN_DIR=""; SNAP_DIR=""; CACHE_DIR=""; BIN_DIR=""; LOG_FILE=""
    return 1
  fi

  APP_FILES="${appfiles:-$(dirname -- "$home")}"
  LINUX_HOME="$home"
  ROOTFS="$LINUX_HOME/rootfs"
  ETC_DIR="$LINUX_HOME/etc"
  RUN_DIR="$LINUX_HOME/run"
  SNAP_DIR="$LINUX_HOME/snapshots"
  CACHE_DIR="$LINUX_HOME/cache"
  BIN_DIR="$LINUX_HOME/bin"
  LOG_FILE="$RUN_DIR/linux.log"
  [[ -z "$explicit" ]] && log "环境根（来自 $src）：$LINUX_HOME"
  return 0
}

require_linux_home() {
  if [[ -z "$LINUX_HOME" ]]; then
    fail 2 "$LINUX_HOME_ERR"
  fi
}

path_ok() { [[ -n "${1-}" ]]; }
exists() { path_ok "${1-}" && [[ -e "$1" ]]; }
rt() { # rootfs 相对路径 → 宿主路径；ROOTFS 为空时返回空串（绝不误判宿主同名文件）
  [[ -n "$ROOTFS" ]] || return 0
  printf '%s' "$ROOTFS$1"
}

mk() { # 只在路径可用时 mkdir；失败即致命错误（不留半成品）
  path_ok "${1-}" || return 0
  mkdir -p -- "$1" 2>/dev/null || fail 1 "无法创建目录：$1（请检查 $LINUX_HOME 的权限与剩余空间）"
}

# =============================================================================
# 3. 进程 / 文件系统探测
# =============================================================================
# 约定：所有「探测」函数都返回 0，取不到就打印空串，方便在 set -e 下直接赋值。

proc_field22() { # /proc/<pid>/stat 第 22 项 = 进程启动时刻（clock ticks）
  local p=$1 s=""
  s=$(cat "/proc/$p/stat" 2>/dev/null || true)
  [[ -n "$s" ]] || return 0
  s=${s##*') '}                 # 去掉 "pid (comm) "，comm 里可能有空格/括号
  # shellcheck disable=SC2086
  set -- $s                      # 现在 $1 = 第 3 项(state)，故第 22 项 = $20
  printf '%s' "${20-}"
}

pid_alive() {
  local p="${1-}"
  [[ -n "$p" && "$p" =~ ^[0-9]+$ ]] || return 1
  [[ -d "/proc/$p" ]] || return 1
  kill -0 "$p" 2>/dev/null || return 1
  return 0
}

pid_is_ours() { # 防止 PID 复用
  # 有 starttime 记录时严格比对（这是唯一可靠的防 PID 复用手段）；
  # 没有记录（老版本/手工写入）时只在「明确不像我们」的情况下拒绝，
  # 否则宁可以为是自己 —— 把活着的环境误判成 error 的代价更大。
  local p="${1-}" want="" got="" cmd=""
  [[ -n "$p" ]] || return 1
  want=$(cat "$RUN_DIR/dsh.pid.starttime" 2>/dev/null || true)
  got=$(proc_field22 "$p")
  if [[ -n "$want" ]]; then
    [[ "$want" == "$got" ]] && return 0 || return 1
  fi
  cmd=$(tr '\0' ' ' <"/proc/$p/cmdline" 2>/dev/null || true)
  if [[ -n "$cmd" ]]; then
    case "$cmd" in
      *dsh*|*node*|*proot*) return 0 ;;
    esac
  fi
  return 0
}

read_pid() {
  local f="$RUN_DIR/dsh.pid" v=""
  [[ -n "$RUN_DIR" && -f "$f" ]] || return 0
  v=$(tr -dc '0-9' <"$f" 2>/dev/null || true)
  printf '%s' "$v"
}

read_proot_pid() {
  local f="$RUN_DIR/proot.pid" v=""
  [[ -n "$RUN_DIR" && -f "$f" ]] || return 0
  v=$(tr -dc '0-9' <"$f" 2>/dev/null || true)
  printf '%s' "$v"
}

read_marker() { # run/state：最后一次由 linuxctl/start.sh 写入的意图
  [[ -n "$RUN_DIR" && -f "$RUN_DIR/state" ]] || return 0
  tr -d '[:space:]' <"$RUN_DIR/state" 2>/dev/null || true
}

read_last_error() {
  # 兼容两种命名：本实现写 last_error，root 模式写 last-error（都只是内部文件名）
  local f=""
  for f in "$RUN_DIR/last_error" "$RUN_DIR/last-error"; do
    if [[ -n "$RUN_DIR" && -s "$f" ]]; then
      head -c 4096 "$f" 2>/dev/null | tr '\n' ' ' || true
      return 0
    fi
  done
  return 0
}

uptime_of() { # 秒；拿不到为空
  local p="${1-}" started="" now="" mtime=""
  now=$(date +%s 2>/dev/null || printf '')
  [[ -n "$now" ]] || return 0
  if [[ -n "$RUN_DIR" && -s "$RUN_DIR/started_at" ]]; then
    started=$(tr -dc '0-9' <"$RUN_DIR/started_at" 2>/dev/null || true)
  fi
  if [[ -z "$started" && -n "$p" ]]; then
    mtime=$(stat -c %Y "/proc/$p" 2>/dev/null || true)
    started=${mtime:-}
  fi
  [[ -n "$started" ]] || return 0
  local d=$(( now - started ))
  (( d < 0 )) && d=0
  printf '%s' "$d"
}

proc_tracer_pid() {
  sed -n 's/^TracerPid:[[:space:]]*\([0-9]\{1,\}\).*/\1/p' /proc/self/status 2>/dev/null | head -1
}

detect_traced() { # 是否被 ptrace 跟踪（== 我们正跑在 proot 里面）
  local t=""
  t=$(proc_tracer_pid)
  [[ -n "$t" && "$t" != "0" ]] && return 0
  return 1
}

read_port() { # 运行中的实际端口 → 配置端口 → 空
  local p="" f="$RUN_DIR/dsh.port"
  if [[ -n "$RUN_DIR" && -s "$f" ]]; then
    p=$(tr -dc '0-9' <"$f" 2>/dev/null || true)
  fi
  [[ -n "$p" ]] || p=$(json_get_num "$ETC_DIR/config.json" port)
  [[ -n "$p" ]] || p=""
  printf '%s' "$p"
}

read_url() { # run/dsh.url：带令牌的登录 URL（§3.3）；没有就是空
  local f="$RUN_DIR/dsh.url" v=""
  [[ -n "$RUN_DIR" && -s "$f" ]] || return 0
  v=$(head -n1 "$f" 2>/dev/null | tr -d '\r\n' || true)
  # 只接受带 ?token= 的 http(s) URL：绝不把半截/脏内容当成登录地址交给 App
  case "$v" in
    http://*'?token='*|https://*'?token='*) printf '%s' "$v" ;;
  esac
  return 0
}

url_port_of() { # 从 URL 里取端口（拿不到就空）
  local u="${1-}" p=""
  [[ -n "$u" ]] || return 0
  p=$(printf '%s' "$u" | sed -n 's|^https\{0,1\}://[^:/]*:\([0-9][0-9]*\).*|\1|p')
  case "$p" in ''|*[!0-9]*) return 0 ;; esac
  printf '%s' "$p"
  return 0
}

http_ready() { # §3.1 / §3.3：对 base_url 做 GET /，**收到任何 HTTP 响应即健康**
  # 裸 GET / 返回 401 是**正常**的（未带令牌）——401 说明服务在监听，算健康；
  # 只有「连接被拒 / 超时 / 拿不到任何响应」才算不健康。
  local url="${1-}" code=""
  [[ -n "$url" ]] || return 1
  if command -v curl >/dev/null 2>&1; then
    code=$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$url" 2>/dev/null || true)
  elif command -v wget >/dev/null 2>&1; then
    code=$(wget -q -T 2 -O /dev/null -S "$url" 2>&1 | sed -n 's|.*HTTP/[0-9.]* \([0-9]\{3\}\).*|\1|p' | head -1 || true)
  else
    # 最后兜底：能建立 TCP 连接就算「Web 服务活着」（拿不到状态码，只能退而求其次）
    local host="${url#http://}" port=""
    host=${host%%/*}
    port=${host##*:}
    host=${host%%:*}
    if (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; then code=200; fi
  fi
  case "$code" in
    2??|3??|4??) return 0 ;;
    *) return 1 ;;
  esac
}

du_bytes() { # 目录/文件字节数；拿不到为空
  local p="${1-}" v=""
  exists "$p" || return 0
  command -v du >/dev/null 2>&1 || return 0
  v=$(du -sb -- "$p" 2>/dev/null | awk 'NR==1{print $1}' || true)
  [[ "$v" =~ ^[0-9]+$ ]] && printf '%s' "$v"
  return 0
}

fs_total_bytes() { # 宿主文件系统总容量
  local p="${1-}"
  exists "$p" || { path_ok "$LINUX_HOME" && p="$LINUX_HOME"; }
  path_ok "$p" || return 0
  stat -f -c '%b %S' -- "$p" 2>/dev/null | awk 'NR==1{printf "%d", $1*$2}' || true
}

# ---- 版本探测（全部只读文件，不执行 rootfs 里的二进制） ----
read_dsh_version() {
  # 以 rootfs 里的实际 package.json 为准（永远比缓存新），缓存只做兜底
  local cached="" v="" pkg=""
  pkg=$(rt /usr/local/lib/node_modules/@deepseek-ai/dsh/package.json)
  v=$(pkg_version_of "$pkg")
  if [[ -z "$v" ]]; then
    cached=$(json_get_str "$ETC_DIR/state.json" dsh_version)
    v=$cached
  fi
  printf '%s' "$v"
  return 0
}

read_base_version() {
  local v="" f; f=$(rt /etc/sunsetlinux-base-version)
  if exists "$f"; then
    head -1 "$f" 2>/dev/null | tr -d '\r\n' || true
    return 0
  fi
  f=$(rt /etc/os-release)
  if exists "$f"; then
    v=$(sed -n 's/^VERSION_ID="\{0,1\}\([^"]*\)"\{0,1\}.*/\1/p' "$f" 2>/dev/null | head -1 || true)
  fi
  printf '%s' "$v"
  return 0
}

read_runtime_version() {
  # 优先 rootfs 里由 runtime 层写入的版本文件，其次缓存。
  # 刻意不执行 rootfs 里的 node --version：proot 模式下执行宿主二进制不可靠。
  local v="" f=""
  f=$(rt /etc/sunsetlinux-runtime-version)
  if exists "$f"; then v=$(head -1 "$f" 2>/dev/null | tr -d '\r\n' || true); fi
  [[ -n "$v" ]] || v=$(json_get_str "$ETC_DIR/state.json" runtime_version)
  printf '%s' "$v"
  return 0
}

size_cached() { json_get_num "$ETC_DIR/state.json" "$1"; }

# =============================================================================
# 4. 状态构建 / 输出（§3.1 —— 冻结 schema，键不可省略）
# =============================================================================
ST_STATE='null'; ST_PID='null'; ST_UPTIME='null'
ST_URL='null'; ST_BASE_URL='null'; ST_PORT='null'; ST_HEALTHY='null'
ST_DSH_VERSION='null'
ST_BASE_VER='null'; ST_BASE_SIZE='null'; ST_BASE_MOUNTED='null'
ST_RT_VER='null';   ST_RT_SIZE='null';   ST_RT_MOUNTED='null'
ST_DSH_VER='null';  ST_DSH_SIZE='null';  ST_DSH_MOUNTED='null'
ST_UPPER_USED='null'; ST_UPPER_TOTAL='null'
ST_LAST_ERROR='null'

build_status() {
  local pid="" marker="" url="" port="" up="" state="" le="" h=0 alive=0 base_url=""

  pid=$(read_pid)
  marker=$(read_marker)
  # 端口：先看 run/dsh.port，其次 config.json；若 run/dsh.url 存在则以 URL 里的端口为准
  # （那才是真正生效的端口 —— 请求端口被占用时 dsh 可能换端口）。
  port=$(read_port)
  url=$(read_url)
  if [[ -n "$url" ]]; then
    local uport=""
    uport=$(url_port_of "$url")
    [[ -n "$uport" ]] && port=$uport
  fi
  # base_url = 不带令牌的裸地址（契约 §3.1：两个字段都要有；token 只允许出现在 url 里）。
  # 只要端口可知就给值（与 root 模式一致）；url 为空时它是探测服务是否活着的唯一地址。
  [[ -n "$port" ]] && base_url="http://127.0.0.1:$port"
  [[ -n "$pid" ]] && up=$(uptime_of "$pid")

  if [[ -n "$pid" ]] && pid_alive "$pid" && pid_is_ours "$pid"; then
    alive=1
  else
    alive=0
    [[ -n "$pid" ]] && warn "run/dsh.pid=$pid 已不是本环境的存活进程（PID 复用或进程已退出）"
  fi

  # 健康探针打 base_url（裸地址）：401 是正常响应 → 健康（与 common/http_health.sh 一致）
  if (( alive )) && [[ -n "$base_url" ]] && http_ready "$base_url"; then h=1; fi

  if [[ -n "$FORCE_STATE" ]]; then
    state=$FORCE_STATE
  elif (( alive )); then
    if (( h )); then
      state=running
    elif [[ -z "$up" ]] || (( up < START_GRACE )); then
      state=starting
    else
      state=error
      [[ -z "$FORCE_LAST_ERROR" ]] && FORCE_LAST_ERROR="DSH Web 在 ${up}s 后仍未就绪（$base_url）"
    fi
  else
    case "$marker" in
      starting) state=starting ;;
      running|stopping)
        state=error
        [[ -z "$FORCE_LAST_ERROR" ]] && FORCE_LAST_ERROR="环境进程已退出（run/state=$marker，PID ${pid:-未知} 不存活）" ;;
      error) state=error ;;
      *)     state=stopped ;;
    esac
  fi

  if [[ -n "$FORCE_LAST_ERROR" ]]; then
    le=$FORCE_LAST_ERROR
  elif [[ "$state" == error ]]; then
    le=$(read_last_error)
  fi

  (( alive )) || { pid=""; up=""; }

  ST_STATE=$(jstr "$state")
  ST_PID=$(jint "${pid:-}")
  ST_UPTIME=$(jint "${up:-}")
  # url = 带令牌 URL（来自 run/dsh.url，§3.3）；没有就是 null —— 绝不拿裸地址冒充登录地址。
  # base_url = 不带令牌的裸地址（与 root 模式同样由生效端口拼出，无尾斜杠）。
  ST_URL=$(jnull_or_str "$url")
  ST_BASE_URL=$(jnull_or_str "$base_url")
  ST_PORT=$(jint "$port")
  ST_HEALTHY=$(jbool "$h")
  ST_LAST_ERROR=$(jnull_or_str "$le")

  # ---- layers（逻辑映射，见文件头说明） ----
  ST_BASE_VER=$(jnull_or_str "$(read_base_version)")
  ST_BASE_SIZE=$(jint "$(size_cached base_bytes)")
  ST_BASE_MOUNTED=$(jbool "$(exists "$ROOTFS" && printf true || printf false)")

  ST_RT_VER=$(jnull_or_str "$(read_runtime_version)")
  ST_RT_SIZE=$(jint "$(size_cached runtime_bytes)")
  local node_bin; node_bin=$(rt /usr/local/bin/node)
  ST_RT_MOUNTED=$(jbool "$(exists "$node_bin" && printf true || printf false)")

  ST_DSH_VER=$(jnull_or_str "$(read_dsh_version)")
  ST_DSH_SIZE=$(jint "$(size_cached dsh_bytes)")
  local dsh_dir; dsh_dir=$(rt /usr/local/lib/node_modules/@deepseek-ai/dsh)
  ST_DSH_MOUNTED=$(jbool "$(exists "$dsh_dir" && printf true || printf false)")

  # ---- storage ----
  ST_UPPER_USED=$(jint "$(size_cached rootfs_bytes)")
  ST_UPPER_TOTAL=$(jint "$(fs_total_bytes "$LINUX_HOME")")
  return 0
}

emit_status() { # 参数：成对的 <已编码key> <已编码val>，附加在 §3.1 字段之后
  local extra=("$@") out="" i=0
  out=$(printf '{"schema":%d,"mode":"proot","state":%s,"pid":%s,"uptime_sec":%s,' \
    "$SCHEMA_VERSION" "$ST_STATE" "$ST_PID" "$ST_UPTIME")
  out+=$(printf '"dsh":{"url":%s,"base_url":%s,"port":%s,"version":%s,"healthy":%s},' \
    "$ST_URL" "$ST_BASE_URL" "$ST_PORT" "$ST_DSH_VER" "$ST_HEALTHY")
  out+=$(printf '"layers":{"base":{"version":%s,"size":%s,"mounted":%s},' \
    "$ST_BASE_VER" "$ST_BASE_SIZE" "$ST_BASE_MOUNTED")
  out+=$(printf '"runtime":{"version":%s,"size":%s,"mounted":%s},' \
    "$ST_RT_VER" "$ST_RT_SIZE" "$ST_RT_MOUNTED")
  out+=$(printf '"dsh":{"version":%s,"size":%s,"mounted":%s}},' \
    "$ST_DSH_VER" "$ST_DSH_SIZE" "$ST_DSH_MOUNTED")
  out+=$(printf '"storage":{"upper_used":%s,"upper_total":%s},' "$ST_UPPER_USED" "$ST_UPPER_TOTAL")
  out+=$(printf '"last_error":%s' "$ST_LAST_ERROR")
  for (( i = 0; i < ${#extra[@]}; i += 2 )); do
    out+=",$(jstr "${extra[i]}"):${extra[i + 1]}"
  done
  out+='}'
  printf '%s\n' "$out"
}

emit_result() { # emit_result <action> <ok-bool> <result-json>
  emit_status "action" "$(jstr "$1")" "ok" "$2" "result" "$3"
}

emit_ok() { # emit_ok <action> <result-json>
  build_status
  emit_result "$1" true "${2:-\{\}}"
}

fail() { # fail <exit-code> <message>
  local code="${1:-1}" msg="${2:-未知错误}"
  err "$msg"
  FORCE_STATE="error"
  FORCE_LAST_ERROR="$msg"
  save_last_error "$msg"
  build_status
  emit_result "${CURRENT_CMD:-unknown}" false \
    "$(json_obj reason "$(jstr "$msg")")"
  exit "$code"
}

save_last_error() {
  path_ok "$RUN_DIR" || return 0
  mkdir -p -- "$RUN_DIR" 2>/dev/null || return 0
  if printf '%s\n' "$1" >"$RUN_DIR/last_error.tmp" 2>/dev/null; then
    mv -f -- "$RUN_DIR/last_error.tmp" "$RUN_DIR/last_error" 2>/dev/null || true
  fi
  return 0
}

clear_last_error() {
  path_ok "$RUN_DIR" || return 0
  rm -f -- "$RUN_DIR/last_error" 2>/dev/null || true
  return 0
}

set_marker() { path_ok "$RUN_DIR" && printf '%s\n' "$1" >"$RUN_DIR/state" 2>/dev/null || true; }

# =============================================================================
# 5. 锁（flock 优先，缺失时用 mkdir 型锁 + 陈旧检测）
# =============================================================================
acquire_lock() {
  path_ok "$RUN_DIR" || return 0
  mkdir -p -- "$RUN_DIR" 2>/dev/null || fail 1 "无法创建 $RUN_DIR"
  if command -v flock >/dev/null 2>&1; then
    exec {LOCK_FD}>"$RUN_DIR/.linuxctl.lock"
    if ! flock -w "$LOCK_TIMEOUT" "$LOCK_FD"; then
      fail 3 "等待锁超时（${LOCK_TIMEOUT}s）：另一个 linuxctl 正在操作 $LINUX_HOME。
  若确认没有别的 linuxctl 在跑，说明有进程继承了锁 fd（旧版本 start.sh 会这样）。
  排查：ps -eo pid,args | grep -E 'proot|node'；释放：linuxctl stop，或结束那个进程。"
    fi
    return 0
  fi
  local d="$RUN_DIR/.linuxctl.lockd" owner="" at="" i=0
  while ! mkdir "$d" 2>/dev/null; do
    owner=$(cat "$d/pid" 2>/dev/null || true)
    if [[ -n "$owner" ]] && ! kill -0 "$owner" 2>/dev/null; then
      rm -rf -- "$d" 2>/dev/null || true
      continue
    fi
    at=$(stat -c %Y "$d" 2>/dev/null || printf '0')
    if (( $(date +%s) - at > 120 )); then
      warn "清理陈旧锁（$d 超过 120s 未释放）"
      rm -rf -- "$d" 2>/dev/null || true
      continue
    fi
    i=$(( i + 1 ))
    (( i > 600 )) && fail 3 "等待锁超时：另一个 linuxctl 正在操作 $LINUX_HOME"
    sleep 0.2
  done
  LOCK_DIR="$d"
  printf '%s\n' "$$" >"$d/pid" 2>/dev/null || true
  return 0
}

release_lock() {
  if [[ -n "$LOCK_FD" ]]; then
    eval "exec ${LOCK_FD}>&-" 2>/dev/null || true
    LOCK_FD=""
  fi
  if [[ -n "$LOCK_DIR" ]]; then
    rm -rf -- "$LOCK_DIR" 2>/dev/null || true
    LOCK_DIR=""
  fi
  return 0
}

cleanup() {
  release_lock
}
trap cleanup EXIT

# =============================================================================
# 6. 内部动作
# =============================================================================
ensure_layout() { # 目录树 + 脚本就位（幂等）；失败返回非 0
  require_linux_home
  local d
  for d in "$ROOTFS" "$ETC_DIR" "$RUN_DIR" "$SNAP_DIR" "$CACHE_DIR" "$BIN_DIR"; do mk "$d"; done
  # rootfs 内的入口脚本：每次调用都保证是最新版本（幂等，内容不同才写）
  if [[ -f "$SELF_DIR/entry.sh" && -d "$ROOTFS" ]]; then
    install_script "$SELF_DIR/entry.sh" "$ROOTFS/opt/sunsetlinux/entry.sh"
  fi
  # 宿主侧脚本副本（App 也可以直接调 $LINUX_HOME/bin/linuxctl）
  if [[ -f "$SELF_DIR/linuxctl.sh" ]]; then
    install_script "$SELF_DIR/linuxctl.sh" "$BIN_DIR/linuxctl"
  fi
  if [[ -f "$SELF_DIR/start.sh" ]]; then
    install_script "$SELF_DIR/start.sh" "$BIN_DIR/start.sh"
  fi
  if [[ -f "$SELF_DIR/entry.sh" ]]; then
    install_script "$SELF_DIR/entry.sh" "$BIN_DIR/entry.sh"
  fi
  # 开发/排错用：解析器回归自测（在设备上也能跑，因为它只加载 entry.sh 的函数）
  if [[ -f "$SELF_DIR/selftest.sh" ]]; then
    install_script "$SELF_DIR/selftest.sh" "$BIN_DIR/selftest.sh"
  fi
  ensure_linuxctl_entry
  return 0
}

ensure_linuxctl_entry() { # 契约路径（architecture.md §2.2）：$LINUX_HOME/bin/linuxctl 必须存在
  # 三种情况都要覆盖：
  #   a) 运行时脚本叫 linuxctl.sh（发行包就是这个名字）→ 复制成 bin/linuxctl
  #   b) 运行时脚本就叫 bin/linuxctl → 已经就位
  #   c) 从别处调用（例如 $LINUX_HOME/proot/linuxctl）→ 软链到 bin/linuxctl
  [[ -n "$BIN_DIR" ]] || return 0
  mkdir -p -- "$BIN_DIR" 2>/dev/null || return 0
  if [[ -f "$SELF_DIR/linuxctl.sh" ]]; then
    install_script "$SELF_DIR/linuxctl.sh" "$BIN_DIR/linuxctl"
    return 0
  fi
  if [[ ! -x "$BIN_DIR/linuxctl" ]]; then
    local me="$SELF_DIR/$PROG"
    if [[ -f "$me" ]]; then
      ln -sfn -- "$me" "$BIN_DIR/linuxctl" 2>/dev/null || install_script "$me" "$BIN_DIR/linuxctl"
    fi
  fi
  return 0
}

install_script() { # install_script <src> <dst>；同 inode / 同内容则跳过（避免覆盖正在运行的脚本）
  local src=$1 dst=$2
  [[ -f "$src" ]] || return 0
  if [[ -e "$dst" ]] && { [[ "$src" -ef "$dst" ]] || cmp -s -- "$src" "$dst"; }; then
    return 0
  fi
  mkdir -p -- "$(dirname -- "$dst")" 2>/dev/null || return 0
  if cp -f -- "$src" "$dst.tmp.$$" 2>/dev/null && mv -f -- "$dst.tmp.$$" "$dst" 2>/dev/null; then
    chmod 0755 -- "$dst" 2>/dev/null || true
  else
    rm -f -- "$dst.tmp.$$" 2>/dev/null || true
    warn "安装脚本失败：$src → $dst"
  fi
  return 0
}

# ---- 出厂种子 ----
find_seed() { # find_seed [显式路径]；打印选中的 tarball 路径，找不到打印空
  local cand="" d="" f="" explicit="${1-}"
  # 没有 zstd 时把 .tar.zst 排到最后：优先挑能直接解开的种子
  local exts=()
  if [[ -n "$(zstd_bin)" ]] && command -v "$(zstd_bin)" >/dev/null 2>&1; then
    exts=(tar.zst tar.gz tgz tar.xz tar.bz2 tar)
  else
    exts=(tar.gz tgz tar.xz tar.bz2 tar.zst tar)
  fi
  if [[ -n "$explicit" ]]; then
    if [[ -f "$explicit" ]]; then printf '%s' "$explicit"; return 0; fi
    if [[ -d "$explicit" ]]; then
      for e in "${exts[@]}"; do
        f=$(find "$explicit" -maxdepth 1 -type f -name "*.$e" 2>/dev/null | sort | head -1 || true)
        [[ -n "$f" ]] && { printf '%s' "$f"; return 0; }
      done
    fi
    return 0
  fi
  for d in "${SUNSETLINUX_SEED_DIR:-}" "$LINUX_HOME/seeds" "$LINUX_HOME/cache" "$LINUX_HOME"; do
    [[ -n "$d" && -d "$d" ]] || continue
    # 优先带 ubuntu/base 字样的
    for e in "${exts[@]}"; do
      f=$(find "$d" -maxdepth 1 -type f \( -iname "*ubuntu*.$e" -o -iname "*base*.$e" \) 2>/dev/null | sort | head -1 || true)
      [[ -n "$f" ]] && { printf '%s' "$f"; return 0; }
    done
  done
  for d in "${SUNSETLINUX_SEED_DIR:-}" "$LINUX_HOME/seeds" "$LINUX_HOME/cache"; do
    [[ -n "$d" && -d "$d" ]] || continue
    for e in "${exts[@]}"; do
      f=$(find "$d" -maxdepth 1 -type f -name "*.$e" 2>/dev/null | sort | head -1 || true)
      [[ -n "$f" ]] && { printf '%s' "$f"; return 0; }
    done
  done
  return 0
}

zstd_bin() { # 找出可用的 zstd：显式指定 > 随包携带 > PATH
  # Android 系统没有 zstd，而 root 版产出的种子/层常常是 .tar.zst，
  # 所以允许把静态 zstd 放进 $LINUX_HOME/bin/zstd 一起分发。
  local c=""
  for c in "${SUNSETLINUX_ZSTD:-}" "$BIN_DIR/zstd" "$LINUX_HOME/bin/zstd" "$LINUX_HOME/proot/bin/zstd"; do
    if [[ -n "$c" && -x "$c" ]]; then printf '%s' "$c"; return 0; fi
  done
  c=$(command -v zstd 2>/dev/null || true)
  printf '%s' "$c"
  return 0
}

zstd_hint() {
  printf '把静态 zstd 二进制放到 %s（或设 SUNSETLINUX_ZSTD=/path/to/zstd），或改用 .tar.gz 的种子/层包' "$BIN_DIR/zstd"
}

archive_test() { # 压缩包完整性校验
  local f="${1-}" rc=0
  [[ -f "$f" ]] || { warn "快照/层文件不存在：$f"; return 1; }
  case "$f" in
    *.zst)
      command -v "$(zstd_bin)" >/dev/null 2>&1 || { err "校验 $f 需要 zstd，但系统里没有。$(zstd_hint)"; return 1; }
      "$(zstd_bin)" -q -t -- "$f" >/dev/null 2>&1 || rc=1 ;;
    *.gz|*.tgz)
      if command -v gzip >/dev/null 2>&1; then gzip -t -- "$f" >/dev/null 2>&1 || rc=1; fi ;;
    *.xz)
      if command -v xz >/dev/null 2>&1; then xz -t -- "$f" >/dev/null 2>&1 || rc=1; fi ;;
  esac
  (( rc == 0 )) || err "压缩包完整性校验失败：$f"
  return $rc
}

extract_archive() { # extract_archive <file> <destdir>
  local f=$1 dest=$2
  command -v tar >/dev/null 2>&1 || fail 1 "缺少 tar，无法解包（proot 模式的快照/层操作需要 tar）"
  mkdir -p -- "$dest" || fail 1 "无法创建解包目录：$dest"
  local rc=0
  case "$f" in
    *.zst)
      if ! command -v "$(zstd_bin)" >/dev/null 2>&1; then
        fail 1 "无法解开 $f：缺少 zstd。$(zstd_hint)"
      fi
      "$(zstd_bin)" -dc -- "$f" | tar -C "$dest" --no-same-owner -xpf - || rc=$? ;;
    *.gz|*.tgz) tar -C "$dest" --no-same-owner -xzpf "$f" || rc=$? ;;
    *.xz)       tar -C "$dest" --no-same-owner -xJpf "$f" || rc=$? ;;
    *.bz2)      tar -C "$dest" --no-same-owner -xjpf "$f" || rc=$? ;;
    *)          tar -C "$dest" --no-same-owner -xpf  "$f" || rc=$? ;;
  esac
  return $rc
}

seed_extract_ok() { # 解包后判断关键文件在不在（非 root 解包可能报部分错误，但内容通常完整）
  exists "$ROOTFS/bin/sh" || exists "$ROOTFS/bin/bash" || exists "$ROOTFS/usr/bin/env"
}

extract_seed_into_rootfs() { # extract_seed_into_rootfs <tarball>；失败会自行清理
  local seed=$1 rc=0
  log "解包出厂种子：$seed"
  archive_test "$seed" || fail 1 "出厂种子损坏：$seed"
  extract_archive "$seed" "$ROOTFS" || rc=$?
  if (( rc != 0 )); then
    if seed_extract_ok; then
      warn "tar 返回 $rc（非 root 解包常见：设备节点/属主无法还原），但关键文件已就位，继续。"
    else
      fail 1 "解包出厂种子失败（tar 退出码 $rc）：$seed"
    fi
  fi
  seed_extract_ok || fail 1 "解包后 rootfs 里找不到 /bin/sh，种子不完整：$seed"
  return 0
}

# ---- 容量测量（被 ptrace 跟踪时拒绝现场测量，见文件头） ----
measure_sizes() {
  require_linux_home
  exists "$ROOTFS" || return 0
  if (( TRACED )) && [[ "${SUNSETLINUX_ALLOW_TRACED_MEASURE:-0}" != "1" ]]; then
    warn "检测到本进程被 ptrace 跟踪（跑在 proot 里）：拒绝现场测量容量（proot 的文件系统视图不可信），沿用 etc/state.json 缓存值。"
    return 0
  fi
  command -v du >/dev/null 2>&1 || { warn "缺少 du，跳过容量测量（status 里 size 会是 null）"; return 0; }
  local total="" local_b="" dsh="" rtsz=""
  total=$(du_bytes "$ROOTFS")
  local_b=$(du_bytes "$ROOTFS/usr/local")
  dsh=$(du_bytes "$ROOTFS/usr/local/lib/node_modules/@deepseek-ai/dsh")
  [[ -n "$local_b" && -n "$dsh" ]] && rtsz=$(( local_b - dsh ))
  local base=""
  [[ -n "$total" && -n "$local_b" ]] && base=$(( total - local_b ))
  [[ -n "$base" && "$base" -lt 0 ]] && base=0
  write_state_sizes "$base" "$rtsz" "$dsh" "$total"
  return 0
}

write_state_sizes() { # write_state_sizes <base> <runtime> <dsh> <rootfs>
  # 只更新 sizes.*，其余字段原样保留（绝不凭空造出 provisioned:true）。
  local sf="$ETC_DIR/state.json" tmp=""
  [[ -n "$ETC_DIR" ]] || return 0
  if [[ ! -f "$sf" ]]; then
    warn "没有 $sf，跳过容量缓存写入（请先 linuxctl provision）"
    return 0
  fi
  tmp="$sf.tmp.$$"
  cat >"$tmp" <<EOF
{
  "schema": 1,
  "provisioned": $(json_bool_or "$sf" provisioned true),
  "mode": "proot",
  "seed": $(jnull_or_str "$(json_get_str "$sf" seed)"),
  "created_at": $(jnull_or_str "$(json_get_str "$sf" created_at)"),
  "updated_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "fake_root": $(json_bool_or "$sf" fake_root false),
  "dsh_version": $(jnull_or_str "$(read_dsh_version_uncached)"),
  "base_version": $(jnull_or_str "$(read_base_version)"),
  "runtime_version": $(jnull_or_str "$(json_get_str "$sf" runtime_version)"),
  "sizes": {
    "base_bytes": $(jint "${1:-}"),
    "runtime_bytes": $(jint "${2:-}"),
    "dsh_bytes": $(jint "${3:-}"),
    "rootfs_bytes": $(jint "${4:-}"),
    "measured_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  }
}
EOF
  mv -f -- "$tmp" "$sf" 2>/dev/null || { rm -f -- "$tmp"; warn "写入 $sf 失败"; }
  return 0
}

refresh_state_versions() { # 只刷新版本字段（不动容量），供 update/restore/reset 使用
  local sf="$ETC_DIR/state.json"
  [[ -f "$sf" ]] || return 0
  write_state_sizes "$(json_get_num "$sf" base_bytes)" "$(json_get_num "$sf" runtime_bytes)" \
    "$(json_get_num "$sf" dsh_bytes)" "$(json_get_num "$sf" rootfs_bytes)"
  return 0
}

read_dsh_version_uncached() {
  local pkg; pkg=$(rt /usr/local/lib/node_modules/@deepseek-ai/dsh/package.json)
  pkg_version_of "$pkg"
  return 0
}

ensure_config() { # 幂等写 etc/config.json（保留已有值，只补缺键）
  local cf="$ETC_DIR/config.json" port=""
  mkdir -p -- "$ETC_DIR" 2>/dev/null || fail 1 "无法创建 $ETC_DIR"
  if [[ -f "$cf" ]]; then
    port=$(json_get_num "$cf" port)
    [[ -n "$port" ]] || port=$DEFAULT_PORT
    log "沿用已有配置：$cf（端口 $port）"
    return 0
  fi
  port=$DEFAULT_PORT
  cat >"$cf" <<EOF
{
  "schema": 1,
  "mode": "proot",
  "port": $port,
  "host": "127.0.0.1",
  "fake_root": false,
  "link2symlink": false,
  "extra_binds": [],
  "linux_home": "$LINUX_HOME",
  "created_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "notes": "proot 模式：见 runtime/proot/README.md。fake_root=true 会加 -0 伪造 root（默认关闭，能力受限）。"
}
EOF
  chmod 0644 -- "$cf" 2>/dev/null || true
  log "写入默认配置：$cf（端口 $port）"
  return 0
}

ensure_channels() {
  local cf="$ETC_DIR/channels.json"
  [[ -f "$cf" ]] && return 0
  mkdir -p -- "$ETC_DIR" 2>/dev/null || return 0
  cat >"$cf" <<'EOF'
{
  "schema": 1,
  "channels": []
}
EOF
  return 0
}

ensure_env_file() { # etc/env（0600，只放模板；真正的密钥由用户/App 写入）
  local ef="$ETC_DIR/env"
  [[ -f "$ef" ]] && { enforce_env_perms; return 0; }
  mkdir -p -- "$ETC_DIR" 2>/dev/null || return 0
  umask 077
  cat >"$ef" <<'EOF'
# SunsetLinux proot 运行时环境变量（权限必须 0600）
#
# start.sh 会以「环境变量」的方式把这些值传给环境内的进程，
# 绝不会出现在 proot 的命令行里 —— 因此 `ps` 看不到密钥（这是相对 DSHA 的硬改进）。
#
# 写法（每行一个 KEY=VALUE，支持 # 注释）：
#   DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
#   DSH_MODEL=deepseek-chat
#
# 另外：环境内 /root/.dsh/env（0600）也会被 entry.sh 加载，二者皆可。
EOF
  chmod 0600 -- "$ef" 2>/dev/null || true
  return 0
}

enforce_env_perms() {
  local ef="$ETC_DIR/env" mode=""
  [[ -f "$ef" ]] || return 0
  mode=$(stat -c %a "$ef" 2>/dev/null || printf '')
  if [[ -n "$mode" && "$mode" != "600" ]]; then
    warn "etc/env 权限是 $mode，正在收紧到 600（避免密钥被同组/其它 App 读到）"
    chmod 0600 -- "$ef" 2>/dev/null || warn "收紧 etc/env 权限失败，请手动执行 chmod 600 $ef"
  fi
  return 0
}

# ---- 停机（内部复用；幂等） ----
stop_env() {
  local pid="" ppid="" pgid="" waited=0 alive_pid=0 alive_proot=0

  pid=$(read_pid)
  ppid=$(read_proot_pid)

  if [[ -z "$pid" && -z "$ppid" ]]; then
    rm -f -- "$RUN_DIR/dsh.pid" "$RUN_DIR/dsh.port" "$RUN_DIR/dsh.url" "$RUN_DIR/proot.pid" "$RUN_DIR/dsh.pid.starttime" 2>/dev/null || true
    set_marker stopped
    return 0
  fi

  # 先 TERM 整个进程组：setsid 之后 proot 是组长，node 继承同一 pgid。
  # 只发单个 PID 会漏掉 tracee（proot 没有 --kill-on-exit，tracee 会存活）。
  for pgid in "$ppid" "$pid"; do
    [[ -n "$pgid" ]] || continue
    kill -TERM -- "-$pgid" 2>/dev/null || true
  done
  [[ -n "$pid" ]] && kill -TERM -- "$pid" 2>/dev/null || true
  [[ -n "$ppid" ]] && kill -TERM -- "$ppid" 2>/dev/null || true

  while (( waited < STOP_TIMEOUT * 10 )); do
    alive_pid=0; alive_proot=0
    pid_alive "$pid" && alive_pid=1
    pid_alive "$ppid" && alive_proot=1
    (( alive_pid == 0 && alive_proot == 0 )) && break
    sleep 0.1
    waited=$(( waited + 1 ))
  done

  if (( alive_pid || alive_proot )); then
    warn "TERM 后仍有进程存活，发送 KILL（pid=${pid:-无} proot=${ppid:-无}）"
    for pgid in "$ppid" "$pid"; do
      [[ -n "$pgid" ]] || continue
      kill -KILL -- "-$pgid" 2>/dev/null || true
    done
    [[ -n "$pid" ]] && kill -KILL -- "$pid" 2>/dev/null || true
    [[ -n "$ppid" ]] && kill -KILL -- "$ppid" 2>/dev/null || true
    sleep 0.5
  fi

  rm -f -- "$RUN_DIR/dsh.pid" "$RUN_DIR/dsh.port" "$RUN_DIR/dsh.url" "$RUN_DIR/proot.pid" "$RUN_DIR/dsh.pid.starttime" 2>/dev/null || true
  set_marker stopped
  clear_last_error

  if pid_alive "$pid" || pid_alive "$ppid"; then
    return 1
  fi
  return 0
}

was_running() {
  local pid=""
  pid=$(read_pid)
  [[ -n "$pid" ]] && pid_alive "$pid" && pid_is_ours "$pid"
}

restart_if_was_running() { # $1 = 1 表示之前在运行
  [[ "${1:-0}" == "1" ]] || return 0
  log "环境之前在运行 → 重新启动"
  local rc=0
  "$(start_sh_path)" --no-json >/dev/null 2>>"$LOG_FILE" || rc=$?
  return $rc
}

log_hint() { # log_hint [起始字节偏移] —— 只看本次启动之后新写入的日志，避免捞到旧错误
  local off="${1:-0}" part=""
  [[ -s "$LOG_FILE" ]] || return 0
  part=$(tail -c "+$(( off + 1 ))" "$LOG_FILE" 2>/dev/null || true)
  [[ -n "$part" ]] || return 0
  local hint=""
  hint=$(printf '%s\n' "$part" | grep -a '错误\]' | tail -1 | sed 's/^\[[^]]*\][[:space:]]*//' || true)
  # 其次找带错误关键词的行（proot/node 的失败信息通常长这样）
  [[ -n "$hint" ]] || hint=$(printf '%s\n' "$part" | grep -aiE 'error|fatal|失败|denied|no such|not found|permission' | tail -1 || true)
  [[ -n "$hint" ]] || hint=$(printf '%s\n' "$part" | grep -av '^\[' | tail -1 || true)
  [[ -n "$hint" ]] || hint=$(printf '%s\n' "$part" | grep -av '^[[:space:]]*$' | tail -1 || true)
  printf '%s' "$hint" | head -c 400
  return 0
}

start_sh_path() {
  local p="$SELF_DIR/start.sh"
  if [[ -f "$p" ]]; then printf '%s' "$p"; return 0; fi
  if [[ -f "$BIN_DIR/start.sh" ]]; then printf '%s' "$BIN_DIR/start.sh"; return 0; fi
  printf '%s' "$p"
  return 0
}

# =============================================================================
# 7. 子命令
# =============================================================================
cmd_status() {
  local refresh=0
  while (( $# )); do
    case "$1" in
      --refresh-sizes) refresh=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) warn "status: 忽略未知参数 $1"; shift ;;
    esac
  done
  require_linux_home
  (( refresh )) && { acquire_lock; measure_sizes; release_lock; }
  build_status
  emit_status
  return 0
}

cmd_provision() {
  local seed_arg=""
  while (( $# )); do
    case "$1" in
      --seed) seed_arg="${2-}"; shift 2 ;;
      --seed=*) seed_arg="${1#--seed=}"; shift ;;
      -h|--help) usage; exit 0 ;;
      *) fail 2 "provision: 未知参数 $1" ;;
    esac
  done
  require_linux_home
  acquire_lock

  local already=0
  if [[ -f "$ETC_DIR/state.json" ]] && [[ "$(json_get_bool "$ETC_DIR/state.json" provisioned)" == "true" ]]; then
    already=1
  fi

  ensure_layout

  local seed="" rootfs_ready=0
  if seed_extract_ok; then rootfs_ready=1; fi

  if (( already )) && (( rootfs_ready )); then
    log "环境已部署（幂等）：$LINUX_HOME"
    ensure_config; ensure_channels; ensure_env_file
    measure_sizes
    build_status
    emit_result provision true "$(json_obj already true linux_home "$(jstr "$LINUX_HOME")" \
      rootfs "$(jstr "$ROOTFS")" seeded false \
      bin_linuxctl "$(jstr "$BIN_DIR/linuxctl")" \
      bin_linuxctl_ok "$(jbool "$([[ -x "$BIN_DIR/linuxctl" ]] && printf true || printf false)")")"
    return 2
  fi

  seed=$(find_seed "$seed_arg")
  if (( ! rootfs_ready )); then
    if [[ -z "$seed" ]]; then
      fail 1 "rootfs 还没有内容，且找不到出厂种子。
  请准备一个 Ubuntu base tarball（ubuntu-base-*.tar.gz 或自己打的 rootfs.tar.gz），然后：
    linuxctl provision --seed <包含该 tarball 的目录或文件>
  也可以用 SUNSETLINUX_SEED_DIR=<目录> 指定搜索位置；默认还会找 \$LINUX_HOME/seeds 与 \$LINUX_HOME/cache。"
    fi
    extract_seed_into_rootfs "$seed"
  else
    log "rootfs 已有内容，跳过种子解包（幂等）"
  fi
  # 解包之后再放一遍入口脚本（种子会覆盖 /opt/sunsetlinux）
  ensure_layout

  ensure_config
  ensure_channels
  ensure_env_file

  # 先落 state.json（provisioned 标记 + seed 路径，供 reset/容量缓存使用），
  # 再做容量测量：measure_sizes 只更新 sizes.*，不会覆盖这里的字段。
  local sf="$ETC_DIR/state.json" tmp="$ETC_DIR/state.json.tmp.$$"
  mkdir -p -- "$ETC_DIR" 2>/dev/null || fail 1 "无法创建 $ETC_DIR"
  cat >"$tmp" <<EOF
{
  "schema": 1,
  "provisioned": true,
  "mode": "proot",
  "seed": $(jnull_or_str "$seed"),
  "created_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "updated_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "fake_root": $(json_bool_or "$ETC_DIR/config.json" fake_root false),
  "dsh_version": $(jnull_or_str "$(read_dsh_version_uncached)"),
  "base_version": $(jnull_or_str "$(read_base_version)"),
  "runtime_version": $(jnull_or_str "$(read_runtime_version)"),
  "sizes": {
    "base_bytes": $(jint "$(size_cached base_bytes)"),
    "runtime_bytes": $(jint "$(size_cached runtime_bytes)"),
    "dsh_bytes": $(jint "$(size_cached dsh_bytes)"),
    "rootfs_bytes": $(jint "$(size_cached rootfs_bytes)"),
    "measured_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  }
}
EOF
  mv -f -- "$tmp" "$sf" 2>/dev/null || { rm -f -- "$tmp"; fail 1 "写入 $sf 失败"; }
  measure_sizes
  clear_last_error

  build_status
  emit_result provision true "$(json_obj already false linux_home "$(jstr "$LINUX_HOME")" \
    rootfs "$(jstr "$ROOTFS")" seed "$(jnull_or_str "$seed")" seeded \
    "$(jbool "$([[ -n "$seed" ]] && printf true || printf false)")" \
    bin_linuxctl "$(jstr "$BIN_DIR/linuxctl")" \
    bin_linuxctl_ok "$(jbool "$([[ -x "$BIN_DIR/linuxctl" ]] && printf true || printf false)")")"
  return 0
}

cmd_start() {
  local fake_root="" arg=""
  while (( $# )); do
    case "$1" in
      --fake-root) fake_root=1; shift ;;
      --no-fake-root) fake_root=0; shift ;;
      -h|--help) usage; exit 0 ;;
      *) fail 2 "start: 未知参数 $1" ;;
    esac
  done
  require_linux_home
  acquire_lock
  ensure_layout
  if [[ ! -f "$ETC_DIR/state.json" ]]; then
    fail 1 "环境尚未 provision（缺少 $ETC_DIR/state.json）。请先执行：linuxctl provision --seed <dir>"
  fi
  if ! seed_extract_ok; then
    fail 1 "rootfs 不完整（找不到 /bin/sh）。请执行 linuxctl reset 或 linuxctl provision --seed <dir> 重新部署。"
  fi

  if was_running; then
    log "环境已在运行（PID $(read_pid)），跳过启动（幂等）"
    build_status
    emit_result start true "$(json_obj started false already_running true pid "$(jint "$(read_pid)")" \
      port "$(jint "$(read_port)")")"
    return 0
  fi

  local sh_path; sh_path=$(start_sh_path)
  [[ -f "$sh_path" ]] || fail 1 "找不到 start.sh（期望在 $SELF_DIR 或 $BIN_DIR）"

  mkdir -p -- "$RUN_DIR" 2>/dev/null || fail 1 "无法创建 $RUN_DIR"
  # 令牌每次启动都会变（§3.3 第 4 条）：先清掉上一轮的，避免 App 在窗口期读到过期登录地址
  rm -f -- "$RUN_DIR/dsh.url" 2>/dev/null || true
  set_marker starting

  local extra=()
  [[ "$fake_root" == "1" ]] && extra+=(--fake-root)
  [[ "$fake_root" == "0" ]] && extra+=(--no-fake-root)

  local log_off=0
  [[ -f "$LOG_FILE" ]] && log_off=$(stat -c %s "$LOG_FILE" 2>/dev/null || printf '0')
  [[ "$log_off" =~ ^[0-9]+$ ]] || log_off=0

  local start_json="" rc=0
  # start.sh 的 stdout 是它自己的 JSON 结果（不是 linuxctl 的 stdout），这里捕获后写日志；
  # 人类可读的进度信息从 start.sh 的 stderr 透传到我们的 stderr，并同时落到 linux.log。
  if start_json=$("$sh_path" --no-json "${extra[@]+"${extra[@]}"}" 2> >(tee -a "$LOG_FILE" >&2)); then
    rc=0
  else
    rc=$?
  fi
  [[ -n "$start_json" ]] && printf '[start.sh] %s\n' "$start_json" >>"$LOG_FILE" 2>/dev/null || true

  if (( rc != 0 )); then
    local hint=""
    hint=$(log_hint "$log_off")
    FORCE_LAST_ERROR="start.sh 失败（退出码 $rc）：${hint:-详见 $(basename -- "$LOG_FILE")}"
    # start.sh 失败 = 环境没起来：把标记写成 error，status 才会给出 error 而不是 starting
    set_marker error
    save_last_error "$FORCE_LAST_ERROR"
    build_status
    emit_result start false "$(json_obj started false exit_code "$(jint "$rc")" \
      log "$(jstr "$LOG_FILE")")"
    return 1
  fi

  clear_last_error
  set_marker running
  build_status
  local ready=false
  [[ "$ST_HEALTHY" == "true" ]] && ready=true
  emit_result start true "$(json_obj started true pid "$(jint "$(read_pid)")" \
    port "$(jint "$(read_port)")" ready "$ready" fake_root \
    "$(jbool "$(json_bool_or "$ETC_DIR/config.json" fake_root false)")")"
  return 0
}

cmd_stop() {
  while (( $# )); do
    case "$1" in
      -h|--help) usage; exit 0 ;;
      *) warn "stop: 忽略未知参数 $1"; shift ;;
    esac
  done
  require_linux_home
  acquire_lock
  local rc=0
  stop_env || rc=1
  build_status
  if (( rc != 0 )); then
    FORCE_STATE=error
    FORCE_LAST_ERROR="stop 超时：仍有进程未退出（可用 doctor 或 ps 排查）"
    build_status
    emit_result stop false "$(json_obj stopped false)"
    return 1
  fi
  emit_result stop true "$(json_obj stopped true)"
  return 0
}

cmd_exec() { # exec -- cmd...
  [[ "${1-}" == "--" ]] && shift
  (( $# )) || fail 2 "exec 需要命令：linuxctl exec -- <cmd> [args...]"
  require_linux_home
  ensure_layout
  local sh_path; sh_path=$(start_sh_path)
  [[ -f "$sh_path" ]] || fail 1 "找不到 start.sh"
  "$sh_path" --inner -- "$@"
  return $?
}

cmd_attach() { # attach [-- cmd...]
  [[ "${1-}" == "--" ]] && shift
  require_linux_home
  ensure_layout
  local sh_path; sh_path=$(start_sh_path)
  [[ -f "$sh_path" ]] || fail 1 "找不到 start.sh"
  if (( $# )); then
    "$sh_path" --inner -- "$@"
  else
    "$sh_path" --inner -- /bin/bash -l
  fi
  return $?
}

cmd_logs() {
  local n=200 as_json=0
  while (( $# )); do
    case "$1" in
      -n|--lines) n="${2:-200}"; shift 2 ;;
      -n*) n="${1#-n}"; shift ;;
      --json) as_json=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) warn "logs: 忽略未知参数 $1"; shift ;;
    esac
  done
  [[ "$n" =~ ^[0-9]+$ ]] || n=200
  require_linux_home
  if (( as_json )); then
    local content="" lines_json="[]"
    if [[ -s "$LOG_FILE" ]]; then
      content=$(tail -n "$n" -- "$LOG_FILE" 2>/dev/null || true)
    fi
    # 逐行编码成 JSON 数组（日志行数有限，纯 bash 足够）
    local out='[' first=1 line
    while IFS= read -r line; do
      (( first )) || out+=','
      out+=$(jstr "$line")
      first=0
    done <<<"$content"
    out+=']'
    lines_json=$out
    build_status
    emit_result logs true "$(json_obj path "$(jstr "$LOG_FILE")" lines "$lines_json" \
      bytes "$(jint "$(wc -c <"$LOG_FILE" 2>/dev/null | tr -d ' ' || printf '')")")"
    return 0
  fi
  # 默认：日志尾部原样输出（App 日志页需要文本；见文件头「例外」说明）
  if [[ -s "$LOG_FILE" ]]; then
    tail -n "$n" -- "$LOG_FILE"
  else
    log "日志为空或不存在：$LOG_FILE"
  fi
  return 0
}

snapshot_file_for() { # 找到某名字的快照文件（.tar.zst 优先）
  local name=$1 f=""
  for f in "$SNAP_DIR/$name.tar.zst" "$SNAP_DIR/$name.tar.gz" "$SNAP_DIR/$name.tar"; do
    [[ -f "$f" ]] && { printf '%s' "$f"; return 0; }
  done
  return 0
}

valid_name() {
  [[ "${1-}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]
}

cmd_snapshot() {
  local name="" force=0
  while (( $# )); do
    case "$1" in
      --force) force=1; shift ;;
      -h|--help) usage; exit 0 ;;
      -*) fail 2 "snapshot: 未知参数 $1" ;;
      *) name=$1; shift ;;
    esac
  done
  [[ -n "$name" ]] || fail 2 "snapshot 需要名字：linuxctl snapshot <name>"
  valid_name "$name" || fail 2 "快照名非法（只允许字母数字与 . _ -，1..64 字符）：$name"
  require_linux_home
  acquire_lock
  exists "$ROOTFS" || fail 1 "rootfs 不存在：$ROOTFS（先 provision）"

  local consistent=true
  if was_running; then
    if (( force )); then
      consistent=false
      warn "环境正在运行，--force 已指定：快照可能不一致（rootfs 里有进程在写）"
    else
      fail 1 "环境正在运行，拒绝做不一致的快照。请先 linuxctl stop，或显式加 --force。"
    fi
  fi
  path_ok "$SNAP_DIR" || fail 1 "快照目录不可用"
  mkdir -p -- "$SNAP_DIR" 2>/dev/null || fail 1 "无法创建 $SNAP_DIR"

  local ext="tar.gz" comp="gzip" zstd=""
  zstd=$(zstd_bin)
  if [[ -n "$zstd" ]] && command -v "$zstd" >/dev/null 2>&1; then ext="tar.zst"; comp="zstd"; fi
  command -v tar >/dev/null 2>&1 || fail 1 "缺少 tar，无法制作快照"
  [[ "$comp" == "gzip" ]] && command -v gzip >/dev/null 2>&1 || true
  if [[ "$comp" == "gzip" ]] && ! command -v gzip >/dev/null 2>&1; then
    fail 1 "既没有 zstd 也没有 gzip，无法压缩快照。请安装 zstd 或 gzip。"
  fi

  local out="$SNAP_DIR/$name.$ext" tmp="$SNAP_DIR/.$name.$ext.tmp.$$"
  log "制作快照：$ROOTFS → $out（压缩：$comp；排除 proc/sys/dev 内容）"
  rm -f -- "$tmp" 2>/dev/null || true
  local rc=0
  if [[ "$ext" == "tar.zst" ]]; then
    tar -C "$ROOTFS" --exclude=./proc/* --exclude=./sys/* --exclude=./dev/* -cf - . 2>/dev/null \
      | "$zstd" -q -T0 -3 -f -o "$tmp" || rc=$?
  else
    tar -C "$ROOTFS" --exclude=./proc/* --exclude=./sys/* --exclude=./dev/* -czf "$tmp" . || rc=$?
  fi
  if (( rc != 0 )) || [[ ! -s "$tmp" ]]; then
    rm -f -- "$tmp" 2>/dev/null || true
    fail 1 "制作快照失败（退出码 $rc）：$out"
  fi
  archive_test "$tmp" || { rm -f -- "$tmp"; fail 1 "快照校验失败：$tmp"; }
  mv -f -- "$tmp" "$out" || { rm -f -- "$tmp"; fail 1 "快照落盘失败：$out"; }

  local bytes=""
  bytes=$(stat -c %s -- "$out" 2>/dev/null || true)
  local meta="$SNAP_DIR/$name.json"
  cat >"$meta" <<EOF
{
  "schema": 1,
  "name": "$name",
  "mode": "proot",
  "file": "$(basename -- "$out")",
  "format": "$ext",
  "bytes": $(jint "$bytes"),
  "rootfs_bytes": $(jint "$(size_cached rootfs_bytes)"),
  "created_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "consistent": $(jbool "$consistent")
}
EOF
  log "快照完成：$out（$bytes 字节）"
  build_status
  emit_result snapshot true "$(json_obj name "$(jstr "$name")" path "$(jstr "$out")" \
    format "$(jstr "$ext")" bytes "$(jint "$bytes")" consistent "$(jbool "$consistent")")"
  return 0
}

cmd_restore() {
  local name="" file=""
  while (( $# )); do
    case "$1" in
      --file) file="${2-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      -*) fail 2 "restore: 未知参数 $1" ;;
      *) name=$1; shift ;;
    esac
  done
  require_linux_home
  acquire_lock
  if [[ -z "$file" ]]; then
    [[ -n "$name" ]] || fail 2 "restore 需要名字：linuxctl restore <name>"
    valid_name "$name" || fail 2 "快照名非法：$name"
    file=$(snapshot_file_for "$name")
  fi
  [[ -n "$file" && -f "$file" ]] || fail 1 "找不到快照：${name:-$file}（查找目录：$SNAP_DIR）"

  if was_running; then
    log "环境正在运行，restore 前先停止"
    stop_env || fail 1 "restore 前无法停止环境"
  fi
  archive_test "$file" || fail 1 "快照文件损坏：$file"

  local staging="$LINUX_HOME/rootfs.restore.$$" old="$LINUX_HOME/rootfs.restore-old.$$"
  log "恢复快照 $file → $ROOTFS"
  rm -rf -- "$staging" 2>/dev/null || true
  if ! extract_archive "$file" "$staging"; then
    rm -rf -- "$staging" 2>/dev/null || true
    fail 1 "解包快照失败：$file"
  fi
  if [[ ! -e "$staging/bin/sh" && ! -e "$staging/bin/bash" && ! -e "$staging/usr/bin/env" ]]; then
    rm -rf -- "$staging" 2>/dev/null || true
    fail 1 "快照内容不像 rootfs（解包后找不到 /bin/sh）：$file"
  fi
  # 原子替换：先把现有 rootfs 挪走，再把 staging 就位；失败则回滚
  if [[ -e "$ROOTFS" ]]; then
    mv -f -- "$ROOTFS" "$old" || { rm -rf -- "$staging"; fail 1 "无法移动旧 rootfs（$ROOTFS）"; }
  fi
  if ! mv -f -- "$staging" "$ROOTFS"; then
    [[ -e "$old" ]] && mv -f -- "$old" "$ROOTFS" 2>/dev/null || true
    rm -rf -- "$staging" 2>/dev/null || true
    fail 1 "rootfs 替换失败，已回滚"
  fi
  rm -rf -- "$old" 2>/dev/null || true
  ensure_layout
  rm -f -- "$RUN_DIR/dsh.pid" "$RUN_DIR/dsh.port" "$RUN_DIR/dsh.url" "$RUN_DIR/proot.pid" "$RUN_DIR/dsh.pid.starttime" 2>/dev/null || true
  set_marker stopped
  clear_last_error
  measure_sizes
  refresh_state_versions
  build_status
  emit_result restore true "$(json_obj name "$(jnull_or_str "$name")" file "$(jstr "$file")")"
  return 0
}

cmd_reset() {
  while (( $# )); do
    case "$1" in
      -h|--help) usage; exit 0 ;;
      *) fail 2 "reset: 未知参数 $1" ;;
    esac
  done
  require_linux_home
  acquire_lock

  local seed=""
  seed=$(json_get_str "$ETC_DIR/state.json" seed)
  if [[ -z "$seed" || ! -f "$seed" ]]; then
    seed=$(find_seed "")
  fi
  if [[ -z "$seed" || ! -f "$seed" ]]; then
    fail 1 "找不到出厂种子，无法 reset（记录于 etc/state.json 的 seed=${seed:-空}）。
  请执行 linuxctl provision --seed <包含 ubuntu-base tarball 的目录> 重新部署，
  或先把种子放回原路径。"
  fi

  if was_running; then
    log "环境正在运行，reset 前先停止"
    stop_env || fail 1 "reset 前无法停止环境"
  fi

  local old="$LINUX_HOME/rootfs.reset-old.$$"
  log "reset：清空 rootfs 并从出厂种子重建（$seed）"
  if [[ -e "$ROOTFS" ]]; then
    mv -f -- "$ROOTFS" "$old" || fail 1 "无法移动旧 rootfs：$ROOTFS"
  fi
  mkdir -p -- "$ROOTFS" 2>/dev/null || { [[ -e "$old" ]] && mv -f -- "$old" "$ROOTFS"; fail 1 "无法创建 $ROOTFS"; }
  if ! extract_seed_into_rootfs "$seed"; then
    rm -rf -- "$ROOTFS" 2>/dev/null || true
    [[ -e "$old" ]] && mv -f -- "$old" "$ROOTFS" 2>/dev/null || true
    fail 1 "reset 失败，已回滚到 reset 前的 rootfs"
  fi
  rm -rf -- "$old" 2>/dev/null || true
  ensure_layout
  rm -f -- "$RUN_DIR/dsh.pid" "$RUN_DIR/dsh.port" "$RUN_DIR/dsh.url" "$RUN_DIR/proot.pid" "$RUN_DIR/dsh.pid.starttime" \
    "$RUN_DIR/last_error" 2>/dev/null || true
  set_marker stopped
  measure_sizes
  refresh_state_versions
  build_status
  emit_result reset true "$(json_obj seed "$(jstr "$seed")" rootfs "$(jstr "$ROOTFS")")"
  return 0
}

cmd_update() { # update <base|runtime|dsh> <file>
  local layer="" file=""
  while (( $# )); do
    case "$1" in
      -h|--help) usage; exit 0 ;;
      -*) fail 2 "update: 未知参数 $1" ;;
      *) if [[ -z "$layer" ]]; then layer=$1; elif [[ -z "$file" ]]; then file=$1; else fail 2 "update: 参数过多"; fi; shift ;;
    esac
  done
  [[ -n "$layer" && -n "$file" ]] || fail 2 "用法：linuxctl update <base|runtime|dsh> <file>"
  case "$layer" in base|runtime|dsh) ;; *) fail 2 "layer 只能是 base / runtime / dsh（收到：$layer）" ;; esac
  [[ -f "$file" ]] || fail 2 "层文件不存在：$file"
  require_linux_home
  acquire_lock
  exists "$ROOTFS" || fail 1 "rootfs 不存在：$ROOTFS（先 provision）"
  archive_test "$file" || fail 1 "层文件损坏：$file"

  local was_run=0
  was_running && was_run=1
  (( was_run )) && { log "环境正在运行，update 前先停止"; stop_env || fail 1 "update 前无法停止环境"; }

  case "$layer" in
    base)   update_base "$file" ;;
    runtime) update_runtime "$file" ;;
    dsh)    update_dsh "$file" ;;
  esac
  ensure_layout
  measure_sizes
  refresh_state_versions
  clear_last_error
  restart_if_was_running "$was_run" || warn "更新完成，但重启失败，请查看日志：$LOG_FILE"
  build_status
  emit_result update true "$(json_obj layer "$(jstr "$layer")" file "$(jstr "$file")" \
    restarted "$(jbool "$was_run")")"
  return 0
}

update_base() { # 整根替换：解包到 staging 后原子换（旧 rootfs 存为 rootfs.prev 供回滚）
  local file=$1 staging="$LINUX_HOME/rootfs.new.$$" prev="$LINUX_HOME/rootfs.prev"
  log "update base：整根替换 rootfs（用户数据 /root 会被替换，这是 base 层的语义）"
  rm -rf -- "$staging" 2>/dev/null || true
  extract_archive "$file" "$staging" || { rm -rf -- "$staging"; fail 1 "解包 base 层失败：$file"; }
  if [[ ! -e "$staging/bin/sh" && ! -e "$staging/bin/bash" && ! -e "$staging/usr/bin/env" ]]; then
    rm -rf -- "$staging" 2>/dev/null || true
    fail 1 "base 层内容不像 rootfs（解包后找不到 /bin/sh）：$file"
  fi
  rm -rf -- "$prev" 2>/dev/null || true
  mv -f -- "$ROOTFS" "$prev" || { rm -rf -- "$staging"; fail 1 "无法移动旧 rootfs"; }
  if ! mv -f -- "$staging" "$ROOTFS"; then
    mv -f -- "$prev" "$ROOTFS" 2>/dev/null || true
    rm -rf -- "$staging" 2>/dev/null || true
    fail 1 "base 层替换失败，已回滚"
  fi
  log "旧 rootfs 保留在 $prev（确认新版本可用后可自行删除）"
  return 0
}

update_runtime() { # 合并式：解包到 staging 后覆盖 rootfs（Node/工具，用户数据不动）
  local file=$1 staging="$LINUX_HOME/.runtime-stage.$$"
  log "update runtime：合并覆盖 rootfs（Node 与基础工具；用户数据保留）"
  rm -rf -- "$staging" 2>/dev/null || true
  extract_archive "$file" "$staging" || { rm -rf -- "$staging"; fail 1 "解包 runtime 层失败：$file"; }
  command -v cp >/dev/null 2>&1 || { rm -rf -- "$staging"; fail 1 "缺少 cp"; }
  # 合并式更新做不到原子，但 runtime 只影响 /usr/local 与少量系统路径，
  # 失败时不会删除已有文件，最坏情况是版本混杂（可用 base 层重装兜底）。
  if ! cp -R --preserve=mode,timestamps,links --no-preserve=ownership "$staging/." "$ROOTFS/" 2>/dev/null; then
    rm -rf -- "$staging" 2>/dev/null || true
    fail 1 "runtime 层合并失败：$file"
  fi
  rm -rf -- "$staging" 2>/dev/null || true
  return 0
}

update_dsh() { # 原子替换 @deepseek-ai/dsh 目录（旧版本保留为 .prev 供回滚）
  local file=$1
  local moddir="$ROOTFS/usr/local/lib/node_modules/@deepseek-ai/dsh"
  local staging="$ROOTFS/usr/local/lib/node_modules/.dsh-stage.$$"
  log "update dsh：原子替换 $moddir"
  mkdir -p -- "$(dirname -- "$moddir")" 2>/dev/null || fail 1 "无法创建 node_modules 目录"
  rm -rf -- "$staging" 2>/dev/null || true
  extract_archive "$file" "$staging" || { rm -rf -- "$staging"; fail 1 "解包 dsh 层失败：$file"; }

  # 允许两种打包方式：直接是包的根，或者多一层（含 package/ 前缀）
  local root="$staging"
  if [[ ! -f "$root/package.json" ]]; then
    local cand=""
    cand=$(find "$staging" -maxdepth 3 -name package.json -type f 2>/dev/null | head -1 || true)
    [[ -n "$cand" ]] && root=$(dirname -- "$cand")
  fi
  [[ -f "$root/package.json" ]] || { rm -rf -- "$staging"; fail 1 "dsh 层里找不到 package.json：$file"; }

  local prev="$moddir.prev"
  rm -rf -- "$prev" 2>/dev/null || true
  if [[ -e "$moddir" ]]; then
    mv -f -- "$moddir" "$prev" || { rm -rf -- "$staging"; fail 1 "无法移动旧 DSH 目录"; }
  fi
  if ! mv -f -- "$root" "$moddir"; then
    [[ -e "$prev" ]] && mv -f -- "$prev" "$moddir" 2>/dev/null || true
    rm -rf -- "$staging" 2>/dev/null || true
    fail 1 "DSH 目录替换失败，已回滚"
  fi
  rm -rf -- "$staging" 2>/dev/null || true
  # 保证 /usr/local/bin/dsh 存在（老层可能没带）
  if [[ ! -e "$ROOTFS/usr/local/bin/dsh" && -f "$moddir/lib/bin.js" ]]; then
    mkdir -p -- "$ROOTFS/usr/local/bin" 2>/dev/null || true
    printf '#!/bin/sh\nexec node %s "$@"\n' "$moddir/lib/bin.js" >"$ROOTFS/usr/local/bin/dsh" 2>/dev/null || true
    chmod 0755 -- "$ROOTFS/usr/local/bin/dsh" 2>/dev/null || true
    log "补写 /usr/local/bin/dsh（指向 $moddir/lib/bin.js）"
  fi
  log "旧 DSH 目录保留在 $prev（确认新版本可用后可自行删除）"
  return 0
}

cmd_doctor() {
  while (( $# )); do
    case "$1" in
      -h|--help) usage; exit 0 ;;
      *) warn "doctor: 忽略未知参数 $1"; shift ;;
    esac
  done
  require_linux_home

  local checks=() has_error=0
  add_check() { # add_check <id> <ok:true|false> <level> <detail>
    checks+=("$(json_obj id "$(jstr "$1")" ok "$(jbool "$2")" level "$(jstr "$3")" detail "$(jstr "$4")")")
    if [[ "$3" == "error" && "$2" == "false" ]]; then has_error=1; fi
  }

  # 1. 运行上下文
  if (( TRACED )); then
    add_check "context" false warn "当前 linuxctl 跑在 proot（ptrace）里：文件系统容量/占用不可信，已自动改用 etc/state.json 缓存值。推荐由 App 原生侧调用。"
  else
    add_check "context" true info "原生上下文（未被 ptrace 跟踪），容量测量可信。"
  fi

  # 2. proot 二进制
  local proot_bin="" proot_ver="" proot_src=""
  for c in "${SUNSETLINUX_PROOT_BIN:-}" "$LINUX_HOME/proot/proot-launch.sh" "$LINUX_HOME/bin/proot-launch.sh" "$SELF_DIR/proot-launch.sh"; do
    [[ -n "$c" && -x "$c" ]] && { proot_bin=$c; break; }
  done
  if [[ -z "$proot_bin" ]]; then
    c=$(command -v proot 2>/dev/null || true)
    [[ -n "$c" && -x "$c" ]] && { proot_bin=$c; proot_src="宿主 PATH"; }
  else
    proot_src="随包 bundle"
  fi
  if [[ -z "$proot_bin" ]]; then
    add_check "proot_binary" false error "找不到可执行的 proot。请用 tools/proot-bundle/mkproot-bundle.sh 生成 dist/proot-bundle-arm64.tar.gz 并解包到 \$LINUX_HOME/proot/（App 应随包携带，不要依赖设备上已装 proot / Termux）。"
  else
    proot_ver=$("$proot_bin" --version 2>&1 | tr '\n' ' ' | sed -n 's/.*\([0-9]\+\.[0-9]\+\.[0-9]\+\).*/\1/p' | head -1 || true)
    if [[ -z "$proot_ver" ]]; then
      add_check "proot_binary" false error "proot 存在但无法执行 --version：$proot_bin（设备上的 /data 目录可能被 noexec 挂载，或缺少动态库；用 bundle 里的 proot-launch.sh）"
    else
      local seccomp="未知"
      "$proot_bin" --version 2>&1 | grep -q 'seccomp_filter = no' && seccomp="否"
      "$proot_bin" --version 2>&1 | grep -q 'seccomp_filter = yes' && seccomp="是"
      add_check "proot_binary" true info "proot $proot_ver（来源：$proot_src，路径：$proot_bin）；seccomp 加速：$seccomp。注意：seccomp 加速关闭时每个系统调用都要 ptrace 往返，小文件/进程创建会明显变慢。"
    fi
  fi

  # 3. /dev/fuse —— proot 模式不必需，但要说明
  if [[ -e /dev/fuse ]]; then
    add_check "dev_fuse" true info "存在 /dev/fuse。注意：proot 模式访问 sdcard 仍然绕不开 FUSE（/storage/emulated/0 本身就是 FUSE 挂载），只有 root 模式的 /mnt/pass_through 才能直挂。"
  else
    add_check "dev_fuse" true info "没有 /dev/fuse —— proot 模式**不需要**它，所以这不是问题。"
  fi

  # 4. sdcard 可写性（真实写测试）
  local sd="${SUNSETLINUX_SDCARD:-/storage/emulated/0}" probe=""
  if [[ -d "$sd" ]]; then
    probe="$sd/.sunsetlinux-write-test.$$"
    if ( : >"$probe" ) 2>/dev/null; then
      rm -f -- "$probe" 2>/dev/null || true
      add_check "sdcard_write" true info "sdcard 可写：$sd"
    else
      add_check "sdcard_write" false warn "sdcard 目录存在但不可写：$sd（App 需要 MANAGE_EXTERNAL_STORAGE 或 READ/WRITE_EXTERNAL_STORAGE 权限；proot 模式只能走 FUSE，这条走不通时环境内 /sdcard 会是只读）"
    fi
  else
    add_check "sdcard_write" false warn "找不到 sdcard 路径：$sd（本机可能没插存储/在 CI 环境里跑）"
  fi

  # 5. 端口占用
  local port="" pid=""
  port=$(read_port)
  pid=$(read_pid)
  if [[ -n "$port" ]]; then
    if pid_alive "$pid" && http_ready "http://127.0.0.1:$port"; then
      add_check "port" true info "端口 $port 已被本环境监听（PID $pid）"
    elif port_open_local "$port"; then
      add_check "port" false error "端口 $port 已被别的进程占用，但 run/dsh.pid 不是活的。请改 etc/config.json 的 port，或找出占用者。"
    else
      add_check "port" true info "端口 $port 空闲"
    fi
  else
    add_check "port" false error "读不到端口：$ETC_DIR/config.json 缺少 \"port\""
  fi

  # 5b. 登录 URL（§3.3）：运行中必须存在带令牌的 run/dsh.url，否则 App 的 WebView 必然 401
  local urlv="" um=""
  urlv=$(read_url)
  if was_running; then
    if [[ -n "$urlv" ]]; then
      um=$(stat -c %a -- "$RUN_DIR/dsh.url" 2>/dev/null || printf '')
      if [[ "$um" == "600" ]]; then
        add_check "dsh_url" true info "登录 URL 已就绪：run/dsh.url（0600，含 ?token=，端口 $(url_port_of "$urlv")）。App 要用 dsh.url（带令牌）打开 WebView，不要用 base_url。"
      else
        add_check "dsh_url" false error "run/dsh.url 权限是 ${um:-未知}，应为 600（里面是登录令牌）：chmod 600 $RUN_DIR/dsh.url"
      fi
    else
      add_check "dsh_url" false error "环境在运行，但 run/dsh.url 不存在或不含 ?token=：App 打开界面必然 401。请检查 $LOG_FILE 里是否有 'dsh web: http://127.0.0.1:<port>/?token=...' 那一行（DSH 版本变化可能改了输出格式）。"
    fi
  elif [[ -n "$urlv" ]]; then
    add_check "dsh_url" false warn "环境没在运行，但 run/dsh.url 还在（过期令牌，App 用了会 401）：执行 linuxctl stop 清理。"
  else
    add_check "dsh_url" true info "未运行且无残留 run/dsh.url（符合 §3.3：令牌只在运行期有效）"
  fi

  # 5c. 契约入口
  if [[ -x "$BIN_DIR/linuxctl" ]]; then
    add_check "bin_linuxctl" true info "契约入口存在且可执行：$BIN_DIR/linuxctl（App 调它）"
  else
    add_check "bin_linuxctl" false error "契约入口缺失：$BIN_DIR/linuxctl。执行一次 linuxctl provision（幂等，会补齐 bin/ 下的脚本）"
  fi

  # 6. rootfs 完整性
  local missing=() f
  for f in bin/sh bin/bash usr/bin/env etc/os-release opt/sunsetlinux/entry.sh \
           usr/local/bin/node usr/local/lib/node_modules/@deepseek-ai/dsh/package.json; do
    [[ -e "$ROOTFS/$f" ]] || missing+=("$f")
  done
  if (( ${#missing[@]} == 0 )); then
    add_check "rootfs_files" true info "rootfs 关键文件齐备（7/7）"
  else
    add_check "rootfs_files" false error "rootfs 缺少关键文件：${missing[*]}。请 linuxctl reset 或 provision --seed 重新部署。"
  fi

  # 7. rootfs 可写
  if [[ -d "$ROOTFS" ]]; then
    probe="$ROOTFS/.sunsetlinux-write-test.$$"
    if ( : >"$probe" ) 2>/dev/null; then
      rm -f -- "$probe" 2>/dev/null || true
      add_check "rootfs_writable" true info "rootfs 可写（proot 模式下 rootfs 本身就是可写层）"
    else
      add_check "rootfs_writable" false error "rootfs 不可写：$ROOTFS（App 私有目录权限异常或磁盘满）"
    fi
  else
    add_check "rootfs_writable" false error "rootfs 不存在：$ROOTFS"
  fi

  # 8. 密钥卫生
  if [[ -f "$ETC_DIR/env" ]]; then
    local mode=""
    mode=$(stat -c %a "$ETC_DIR/env" 2>/dev/null || printf '')
    if [[ "$mode" == "600" ]]; then
      add_check "secrets" true info "etc/env 权限 600，且 start.sh 只通过环境变量传入（密钥不出现在命令行，ps 看不到）"
    else
      add_check "secrets" false error "etc/env 权限是 ${mode:-未知}，应为 600：chmod 600 $ETC_DIR/env"
    fi
  else
    add_check "secrets" true info "未使用 etc/env（密钥可放在环境内 /root/.dsh/.credentials.yaml 或 /root/.dsh/env）"
  fi

  # 9. DNS 文件
  if [[ -s "$ROOTFS/etc/resolv.conf" ]]; then
    local ns=""
    ns=$(grep -c '^nameserver' "$ROOTFS/etc/resolv.conf" 2>/dev/null || printf '0')
    if (( ns > 0 )); then
      add_check "dns" true info "rootfs/etc/resolv.conf 有 $ns 条 nameserver（start 时由 start.sh 按 getprop/ndc//system/etc/resolv.conf 多路回退写入）"
    else
      add_check "dns" false warn "rootfs/etc/resolv.conf 里没有 nameserver；启动时 start.sh 会重写，若设备拿不到 DNS 环境内会无法解析域名"
    fi
  else
    add_check "dns" false warn "rootfs/etc/resolv.conf 为空或不存在（start 时会重写）"
  fi

  # 10. IO 工具链
  local tools_missing=() t
  for t in tar bash; do command -v "$t" >/dev/null 2>&1 || tools_missing+=("$t"); done
  if [[ -z "$(zstd_bin)" ]] || ! command -v "$(zstd_bin)" >/dev/null 2>&1; then
    command -v gzip >/dev/null 2>&1 || tools_missing+=("zstd 或 gzip")
  fi
  command -v du >/dev/null 2>&1 || tools_missing+=("du(仅影响容量显示)")
  if (( ${#tools_missing[@]} == 0 )); then
    add_check "toolchain" true info "tar/bash/压缩工具齐备"
  else
    local lvl=error
    [[ "${tools_missing[*]}" == *"du"* ]] && lvl=warn
    add_check "toolchain" false "$lvl" "缺少：${tools_missing[*]}（快照/恢复/日志功能会受限）"
  fi

  # 11. 生命周期解耦提示
  local ko="否"
  [[ -f "$RUN_DIR/proot.pid" ]] && ko="是（setsid，独立会话）"
  add_check "lifecycle" true info "未使用 proot --kill-on-exit；启动走 setsid+nohup，proot 独立会话：$ko。App 侧仍建议用前台服务保活。"

  # 12. 容量缓存
  if [[ -n "$(size_cached rootfs_bytes)" ]]; then
    add_check "sizes_cache" true info "容量缓存可用（etc/state.json），status 不会每次都 du 整棵 rootfs"
  else
    add_check "sizes_cache" false warn "没有容量缓存：status 里 layers.*.size / storage 会是 null。执行 linuxctl status --refresh-sizes 生成。"
  fi

  local arr='[' first=1 c
  for c in "${checks[@]}"; do
    (( first )) || arr+=','
    arr+="$c"
    first=0
  done
  arr+=']'

  build_status
  emit_result doctor "$([[ $has_error == 1 ]] && printf false || printf true)" \
    "$(json_obj checks "$arr" errors "$(jint "$(printf '%s' "$arr" | grep -o '"level":"error"' | wc -l | tr -d ' ')")")"
  return $has_error
}

port_open_local() { # TCP 连接测试（占用/就绪通用）
  local port="${1-}"
  [[ "$port" =~ ^[0-9]+$ ]] || return 1
  (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null
}

# =============================================================================
# 8. 用法 / 分发
# =============================================================================
usage() {
  cat >&2 <<EOF
SunsetLinux linuxctl（proot / 非 root 降级运行时）v$LINUXCTL_VERSION

用法：linuxctl <子命令> [参数...]
（stdout 只输出 JSON；status 严格遵循 docs/architecture.md §3.1 schema）

  provision [--seed <dir|file>]   首次部署：建目录树、解包出厂种子、写 config
                                  （已部署 → 退出码 2，stdout 仍是合法 JSON）
  start [--fake-root|--no-fake-root]
                                  启动环境（幂等，已运行直接返回 running）
  stop                            停止环境（幂等）
  status [--refresh-sizes]        输出状态 JSON（§3.1）
  attach [-- cmd...]              进入环境；无 cmd 则开交互 shell；退出码=命令退出码
  exec -- cmd...                  非交互执行；退出码=命令退出码
  logs [-n N] [--json]            日志尾部（默认原样文本输出，--json 走 JSON）
  snapshot <name> [--force]       打包 rootfs → snapshots/<name>.tar.zst(或 .tar.gz)
  restore <name> [--file <f>]     从快照原子恢复 rootfs
  reset                           清空 rootfs 并从出厂种子重建
  update <base|runtime|dsh> <f>   原子替换某部分并（必要时）重启环境
  doctor                          自检；有 error 级问题退出码 1

环境变量：
  SUNSETLINUX_APP_FILES   App 私有 files 目录（默认环境根 = \$SUNSETLINUX_APP_FILES/sunsetlinux）
  LINUX_HOME          直接指定环境根（优先级最高）
  SUNSETLINUX_PROOT_BIN   指定 proot 可执行文件（默认优先 \$LINUX_HOME/proot/proot-launch.sh）
  DSHOID_* / SUNSETLINUX_* 见 runtime/proot/README.md
EOF
  return 0
}

main() {
  local cmd="${1-}"
  [[ $# -gt 0 ]] && shift

  case "$cmd" in
    ""|-h|--help|help) usage; exit 0 ;;
  esac

  CURRENT_CMD=$cmd
  resolve_paths || true
  if detect_traced; then
    TRACED=1
    warn "检测到本进程被 ptrace 跟踪（跑在 proot 内）：容量/路径信息按不可信处理，只用缓存值。"
  fi

  case "$cmd" in
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
    update)    cmd_update "$@" ;;
    doctor)    cmd_doctor "$@" ;;
    *) fail 2 "未知子命令：$cmd（用 linuxctl --help 看用法）" ;;
  esac
}

main "$@"
