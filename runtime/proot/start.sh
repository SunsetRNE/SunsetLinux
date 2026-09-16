#!/system/bin/sh
# =============================================================================
# SunsetLinux — proot 模式启动器（start.sh）                v1.0.0
#
# 解释器：**设备侧的 /system/bin/sh（mksh）**。本文件必须能被 mksh 解析 ——
#   设备上没有 bash（`/system/bin/bash` 不存在），App 也只能用 `/system/bin/sh <脚本>`
#   执行它（Android 10+ 禁止 execve App 私有目录里的文件）。
#   闸门：tools/shell-compat-check.mjs；改这个文件前先读它，别再引入 bash 专有语法。
#
# 职责：把 rootfs「用 proot 装起来」，然后 exec rootfs 内的 /opt/sunsetlinux/entry.sh。
#
# 相对上游 DSHA 的四个硬改进（都在这一个文件里落实）：
#   1) **不用 -0 伪造 root**。默认保持真实 uid（App 的 uid），需要写 rootfs 的地方
#      靠「rootfs 本来就在 App 私有目录里、属主就是当前用户」解决，不需要 root 身份。
#      确实要 -0（比如装包）时用 --fake-root / SUNSETLINUX_PROOT_FAKE_ROOT=1 显式打开，
#      并会在 stderr + 日志里明确写「当前为伪造 root，能力受限」。
#   2) **不用 --kill-on-exit**。用 setsid + nohup 让 proot 进入独立会话，PID 记到
#      run/proot.pid，生命周期尽量与调用方（App/终端）解耦。
#   3) **密钥不进命令行**。从 $LINUX_HOME/etc/env（0600）读，按环境变量传给 guest；
#      proot 的命令行里永远只有路径和开关，`ps` 看不到任何密钥。
#   4) **DNS 显式处理**。多路回退（getprop / /system/etc/resolv.conf / ndc）把 Android
#      当前 DNS 写进 rootfs 的 /etc/resolv.conf，并把来源与结果记进日志。
#
# 用法：
#   start.sh [--fake-root|--no-fake-root] [--no-json]      # 后台启动，stdout 出 JSON 结果
#   start.sh --inner -- <cmd> [args...]                    # 前台进入环境执行命令（linuxctl 用）
#
# 环境变量：
#   LINUX_HOME / SUNSETLINUX_APP_FILES  环境根（见 linuxctl.sh）
#   SUNSETLINUX_PROOT_BIN               proot 可执行文件（默认优先 $LINUX_HOME/proot/proot-launch.sh）
#   SUNSETLINUX_START_TIMEOUT           等待就绪上限（秒，默认 120）
#   SUNSETLINUX_HOSTNAME                guest 主机名（默认 sunsetlinux）
#   SUNSETLINUX_SDCARD                  sdcard 宿主路径（默认 /storage/emulated/0）
#   SUNSETLINUX_EXTRA_BINDS            额外 bind，空格分隔，形如 "/a:/b /c"
# =============================================================================
set -euo pipefail

SCHEMA_VERSION=1
MODE="proot"
SELF_DIR=$(cd -- "$(dirname -- "$0")" >/dev/null 2>&1 && pwd -P)

START_TIMEOUT=${SUNSETLINUX_START_TIMEOUT:-120}
HOSTNAME_GUEST=${SUNSETLINUX_HOSTNAME:-sunsetlinux}
SDCARD_HOST=${SUNSETLINUX_SDCARD:-/storage/emulated/0}
GUEST_RUNDIR=/run/sunsetlinux          # 宿主 $LINUX_HOME/run 通过 bind 出现在 guest 的这个位置
# 注意：本脚本跑在宿主侧，读 run 文件必须用宿主路径 $RUN_DIR/*；
# GUEST_RUNDIR 只用于拼 bind 参数与传给 entry.sh 的环境变量。

# 必须用 ${VAR:-} 形式，否则会把继承来的环境变量抹掉
LINUX_HOME="${LINUX_HOME:-}"
ROOTFS=""
ETC_DIR=""
RUN_DIR=""
LOG_FILE=""
BIN_DIR=""
EMIT_JSON=1
MODE_INNER=0
INNER_CMD=()
FAKE_ROOT_FLAG=""      # "" | 1 | 0

# -----------------------------------------------------------------------------
# 日志（一律 stderr；后台启动后由 nohup 重定向进 run/linux.log）
# -----------------------------------------------------------------------------
ts() { date -u '+%H:%M:%S' 2>/dev/null || printf '??:??:??'; }
log()  { printf '[start %s] %s\n' "$(ts)" "$*" >&2; }
warn() { printf '[start 警告] %s\n' "$*" >&2; }
err()  { printf '[start 错误] %s\n' "$*" >&2; }

# -----------------------------------------------------------------------------
# JSON 工具（与 linuxctl.sh 同一套约定，保持脚本自包含）
# -----------------------------------------------------------------------------
jstr() {
  local s="${1-}"
  s=${s//\\/\\\\}; s=${s//\"/\\\"}
  s=${s//$'\n'/\\n}; s=${s//$'\r'/\\r}; s=${s//$'\t'/\\t}
  s=$(printf '%s' "$s" | tr -d '\000-\010\013\014\016-\037' 2>/dev/null || printf '%s' "$s")
  printf '"%s"' "$s"
}
jnull_or_str() { if [[ -n "${1-}" ]]; then jstr "$1"; else printf 'null'; fi }
# 整数判定：mksh 没有 `=~`（`[[ x =~ re ]]` 是语法错误），用 case 的字符类做等价的
# "整串都是数字" 判断。见 docs/STATUS.md §3.4 与 tools/shell-compat-check.mjs。
# （刻意写成多行：runtime/proot/selftest-funcs.sh 用 `/^fn()/,/^}/` 从本文件抽取实现来测。）
is_uint() {
  case ${1-} in ''|*[!0-9]*) return 1 ;; esac
  return 0
}
is_int() {
  case ${1-} in
    ''|-) return 1 ;;
    -*)   is_uint "${1#-}" ;;
    *)    is_uint "$1" ;;
  esac
}
jint() {
  if is_int "${1-}"; then printf '%s' "$1"; else printf 'null'; fi
}
jbool() { case "${1-}" in true|1|yes|on) printf 'true';; false|0|no|off) printf 'false';; *) printf 'null';; esac }
json_obj() {
  local out='{' first=1
  while (( $# >= 2 )); do (( first )) || out+=','; out+=$(jstr "$1"):$2; first=0; shift 2; done
  printf '%s}' "$out"
}
json_get_num() { [[ -f "${1-}" ]] || return 0; sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\([0-9]\{1,\}\).*/\1/p" "$1" 2>/dev/null | head -1; }
json_get_bool() { [[ -f "${1-}" ]] || return 0; sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\(true\|false\).*/\1/p" "$1" 2>/dev/null | head -1; }

RESULT_OK=false
RESULT_ERR=""
RESULT_JSON=""

emit_json() {
  (( EMIT_JSON )) || return 0
  printf '%s\n' "$RESULT_JSON"
  return 0
}

fail_json() { # fail_json <exit> <message>
  local code="${1:-1}" msg="${2:-未知错误}"
  err "$msg"
  RESULT_OK=false
  RESULT_ERR=$msg
  RESULT_JSON=$(json_obj schema "$(jint "$SCHEMA_VERSION")" mode "$(jstr "$MODE")" \
    ok false started false last_error "$(jstr "$msg")")
  emit_json
  exit "$code"
}

# -----------------------------------------------------------------------------
# 路径解析
# -----------------------------------------------------------------------------
resolve_paths() {
  local home="${LINUX_HOME:-}" appfiles="${SUNSETLINUX_APP_FILES:-}"
  if [[ -n "$home" ]]; then :
  elif [[ -n "$appfiles" ]]; then home="${appfiles%/}/linux"
  elif [[ -d "$SELF_DIR/../rootfs" ]]; then home=$(cd -- "$SELF_DIR/.." >/dev/null 2>&1 && pwd -P)
  else
    fail_json 2 "未找到环境根：请设置 LINUX_HOME 或 SUNSETLINUX_APP_FILES（默认环境根 = \$SUNSETLINUX_APP_FILES/sunsetlinux）。"
  fi
  LINUX_HOME=$home
  ROOTFS="$LINUX_HOME/rootfs"
  ETC_DIR="$LINUX_HOME/etc"
  RUN_DIR="$LINUX_HOME/run"
  BIN_DIR="$LINUX_HOME/bin"
  LOG_FILE="$RUN_DIR/linux.log"
  return 0
}

# -----------------------------------------------------------------------------
# proot 解析：随包 bundle 优先，宿主 PATH 兜底
# -----------------------------------------------------------------------------
PROOT_CMD=()

script_interp() { # 读脚本的 shebang 解释器路径；非脚本/无 shebang → 空
  local f="${1-}" first=""
  [[ -f "$f" ]] || return 0
  # 只看前 2 字节是不是 "#!"（避免把 ELF 当脚本读）
  first=$(head -c 2 -- "$f" 2>/dev/null || true)
  [[ "$first" == '#!' ]] || return 0
  head -1 -- "$f" 2>/dev/null | sed -n 's|^#![[:space:]]*\([^[:space:]]*\).*|\1|p'
  return 0
}

find_sh() { # 给「shebang 指向 /bin/sh 但 Android 上没有 /bin/sh」的场景找可用 sh
  local c=""
  for c in /bin/sh /system/bin/sh /usr/bin/sh; do
    [[ -x "$c" ]] && { printf '%s' "$c"; return 0; }
  done
  c=$(command -v sh 2>/dev/null || true)
  [[ -n "$c" ]] && { printf '%s' "$c"; return 0; }
  return 1
}

resolve_proot() {
  local cand="" src="" interp="" sh=""

  # 1) 显式命令前缀（最可靠，Android 上常用：/system/bin/sh /path/proot-launch.sh）
  if [[ -n "${SUNSETLINUX_PROOT_CMD:-}" ]]; then
    # shellcheck disable=SC2206
    PROOT_CMD=(${SUNSETLINUX_PROOT_CMD})
    [[ -e "${PROOT_CMD[0]}" || -x "${PROOT_CMD[0]}" ]] || \
      fail_json 1 "SUNSETLINUX_PROOT_CMD 的第一个元素不存在：${PROOT_CMD[0]}"
    log "proot 命令前缀（SUNSETLINUX_PROOT_CMD）：${PROOT_CMD[*]}"
    return 0
  fi

  # 2) 单个可执行文件
  if [[ -n "${SUNSETLINUX_PROOT_BIN:-}" ]]; then
    cand=$SUNSETLINUX_PROOT_BIN; src="SUNSETLINUX_PROOT_BIN"
  else
    # 3) 随包 bundle 优先（proot-launch.sh 会用自己的 lib 加载 proot，不依赖宿主库）
    for cand in "$LINUX_HOME/proot/proot-launch.sh" "$BIN_DIR/proot-launch.sh" \
                "$SELF_DIR/proot-launch.sh" "$LINUX_HOME/proot/bin/proot"; do
      [[ -f "$cand" ]] && { src="随包 bundle"; break; }
      cand=""
    done
  fi

  if [[ -z "$cand" ]]; then
    cand=$(command -v proot 2>/dev/null || true)
    [[ -n "$cand" ]] && src="宿主 PATH（不推荐：设备上通常没有，且宿主 glibc 版本不可控）"
  fi

  [[ -n "$cand" && -f "$cand" ]] || fail_json 1 "找不到可用的 proot。
  请先产出随包 bundle：tools/proot-bundle/mkproot-bundle.sh，
  再把 dist/proot-bundle-arm64.tar.gz 解包到 \$LINUX_HOME/proot/（含 proot-launch.sh + lib/）。
  也可以临时用 SUNSETLINUX_PROOT_BIN=/path/to/proot 或
  SUNSETLINUX_PROOT_CMD='/system/bin/sh /path/to/proot-launch.sh' 指定。"

  # 脚本型包装器：shebang 解释器在 Android 上可能不存在（典型：#!/bin/sh 但只有 /system/bin/sh），
  # 这时显式用找得到的 sh 去跑它，避免 ENOENT。
  interp=$(script_interp "$cand")
  if [[ -n "$interp" ]]; then
    if [[ ! -x "$interp" ]]; then
      sh=$(find_sh) || fail_json 1 "proot 包装器 $cand 需要 $interp，但本机找不到任何 sh 可执行文件"
      warn "包装器 shebang 解释器 $interp 不存在，改用 $sh 执行：$cand"
      PROOT_CMD=("$sh" "$cand")
    else
      PROOT_CMD=("$cand")
    fi
  else
    [[ -x "$cand" ]] || fail_json 1 "proot 二进制不可执行：$cand（/data 目录可能被挂载成 noexec；改用 bundle 里的 proot-launch.sh）"
    PROOT_CMD=("$cand")
  fi
  log "proot：${PROOT_CMD[*]}（来源：$src）"
  return 0
}

# -----------------------------------------------------------------------------
# bind 列表（精简且明确；每条都检查宿主路径是否存在）
# -----------------------------------------------------------------------------
PROOT_ARGS=()
BIND_SKIPPED=()

add_bind() { # add_bind <src>[:<guest>] <required:0|1>
  local spec=$1 required=$2 host_="" guest_=""
  host_=${spec%%:*}
  if [[ "$spec" == *:* ]]; then guest_=${spec##*:}; else guest_=$host_; fi
  if [[ ! -e "$host_" ]]; then
    if (( required )); then
      fail_json 1 "必需的 bind 源不存在：$host_（设备环境异常，proot 无法正常工作）"
    fi
    BIND_SKIPPED+=("$spec")
    warn "跳过不存在的 bind：$spec"
    return 0
  fi
  PROOT_ARGS+=(-b "$spec")
  return 0
}

build_proot_args() {
  # 第一个元素必须是 proot 可执行文件本身（launcher 与 --inner 都依赖这一点）
  (( ${#PROOT_CMD[@]} )) || fail_json 1 "内部错误：PROOT_CMD 为空（resolve_proot 未执行）"
  PROOT_ARGS=("${PROOT_CMD[@]}" -r "$ROOTFS" -w /root)

  # 必须的 7 条（见交付说明）：/dev /proc /sys /system /apex /proc/self/fd→/dev/fd sdcard
  add_bind /dev 1
  add_bind /proc 1
  add_bind /sys 1
  add_bind /system 0
  add_bind /apex 0
  add_bind "/proc/self/fd:/dev/fd" 0
  add_bind "$SDCARD_HOST:/sdcard" 0

  # 控制通道：宿主 $LINUX_HOME/run ↔ guest /run/sunsetlinux。
  # 有了它，entry.sh 才能把 dsh.pid / dsh.port / linux.log 写回宿主可见的位置，
  # 而 proot 模式下这是唯一可靠的「状态交换」方式（不依赖 -0，也不伪造 /proc）。
  PROOT_ARGS+=(-b "$RUN_DIR:$GUEST_RUNDIR")

  # 可选扩展（config.json 的 extra_binds 不支持数组解析成本高，这里用文件/环境变量）
  local binds_file="$ETC_DIR/binds" spec=""
  if [[ -s "$binds_file" ]]; then
    while IFS= read -r spec; do
      spec=${spec%%#*}
      spec=$(printf '%s' "$spec" | tr -d '[:space:]')
      [[ -n "$spec" ]] || continue
      add_bind "$spec" 0
    done <"$binds_file"
  fi
  if [[ -n "${SUNSETLINUX_EXTRA_BINDS:-}" ]]; then
    for spec in $SUNSETLINUX_EXTRA_BINDS; do add_bind "$spec" 0; done
  fi

  # 可选：npm 在 FUSE/不支持硬链接的文件系统上需要 link2symlink 兜底。
  # ⚠️ **只有 proot ≥ 5.3 才有这个开关**（随包 bundle 现为 5.4.0）。喂给老 proot（如系统里的
  #    5.1.0）它会 `unknown option '--link2symlink'` + fatal error，**整个 start 直接起不来**。
  #    所以先问一次 --help：不支持就跳过并提醒，而不是把 start 拖崩。
  local l2s=""
  l2s=$(json_get_bool "$ETC_DIR/config.json" link2symlink)
  if [[ "$l2s" == "true" || "${SUNSETLINUX_LINK2SYMLINK:-0}" == "1" ]]; then
    if "${PROOT_CMD[@]}" --help 2>&1 | grep -q -- '--link2symlink'; then
      PROOT_ARGS+=(--link2symlink)
      log "已启用 --link2symlink（npm/硬链接兜底；会让 hard link 表现为 symlink）"
    else
      warn "请求了 --link2symlink，但当前 proot 不支持它（<5.3）—— 已跳过。"
      warn "  升级随包 bundle（dist/proot-bundle-arm64.tar.gz，含 proot 5.4.0）后即可启用。"
    fi
  fi

  if [[ "$FAKE_ROOT" == "1" ]]; then
    PROOT_ARGS+=(-0)
    warn "当前为伪造 root（proot -0）：id 会显示 uid=0，但**没有真实 capabilities**——"
    warn "  mount / chown / mknod / 修改系统目录 等操作依旧会失败，安全边界与真实 root 完全不同。"
    warn "  仅在你明确需要（如 apt/dpkg 装包）时使用，默认关闭。"
  fi
  return 0
}

# -----------------------------------------------------------------------------
# DNS：多路回退把 Android 当前 DNS 写进 rootfs/etc/resolv.conf
# -----------------------------------------------------------------------------
# 单个 IPv4 段：0-255（允许前导 0）。先剥掉前导 0 再用 `[ -le ]`，
# 避免 `(( 08 <= 255 ))` 这类八进制歧义。mksh 没有 `=~`，故全用 case。
octet_ok() {
  local o="${1-}"
  case $o in ''|*[!0-9]*) return 1 ;; esac
  while :; do case $o in 0?*) o=${o#0} ;; *) break ;; esac; done
  [ ${#o} -le 3 ] || return 1
  [ "$o" -le 255 ] 2>/dev/null || return 1
  return 0
}

valid_ipv4() {
  local ip="${1-}" a="" b="" c="" d=""
  # 原来这里是 `[[ $ip =~ ^([0-9]{1,3})\.…$ ]]`（bash 专有）。mksh 没有 `=~`，
  # 改成「字符类过滤 + 按点切分 + 逐段判定」，语义等价且不需要正则。
  case "$ip" in
    ''|*[!0-9.]*) return 1 ;;
  esac
  IFS=. read -r a b c d <<<"$ip"
  [ -n "$a" ] && [ -n "$b" ] && [ -n "$c" ] && [ -n "$d" ] || return 1
  case "$d" in *.*) return 1 ;; esac          # 5 段以上时最后一段里会留下点
  octet_ok "$a" && octet_ok "$b" && octet_ok "$c" && octet_ok "$d" || return 1
  case "$ip" in 0.0.0.0|127.*) return 1 ;; esac
  return 0
}

detect_dns() { # 输出：来源<TAB>IP（每行一个）；找不到输出空
  local ip="" src="" found=0

  # 1) getprop（Android 的 net.dns1 / 各接口的 dns1）
  if command -v getprop >/dev/null 2>&1; then
    for k in net.dns1 net.dns2 net.dns3; do
      ip=$(getprop "$k" 2>/dev/null | tr -d '[:space:]' || true)
      if valid_ipv4 "$ip"; then printf 'getprop %s\t%s\n' "$k" "$ip"; found=1; fi
    done
    if (( ! found )); then
      # 原来是 `done < <(getprop …)`（进程替换 = bash 专有，mksh 直接语法错）。
      # 改成「先取到变量，再用 here-doc 喂给 while」：here-doc 不是管道，
      # 循环仍在当前 shell 里跑，`found=1` 能带出来（用管道会丢在子 shell 里）。
      # 注意 here-doc 的结束符必须顶格（不能缩进）。
      dns_lines=$(getprop 2>/dev/null | sed -n 's/.*\[[a-zA-Z0-9_.]*dns[0-9]*\]:[[:space:]]*\[\([0-9.]\{7,\}\)\].*/\1/p' | sort -u || true)
      while IFS= read -r ip; do
        valid_ipv4 "$ip" && { printf 'getprop(接口 dns)\t%s\n' "$ip"; found=1; }
      done <<EOF
$dns_lines
EOF
    fi
  fi

  # 2) /system/etc/resolv.conf（Android 上通常存在且是权威的）
  local f=""
  for f in /system/etc/resolv.conf /etc/resolv.conf; do
    [[ -r "$f" ]] || continue
    ns_lines=$(sed -n 's/^[[:space:]]*nameserver[[:space:]]\+\([0-9.]\{7,\}\).*/\1/p' "$f" 2>/dev/null || true)
    while IFS= read -r ip; do
      if valid_ipv4 "$ip"; then printf '%s\t%s\n' "$f" "$ip"; found=1; fi
    done <<EOF
$ns_lines
EOF
    (( found )) && break
  done

  # 3) ndc（老 Android 的 netd 命令行）
  if (( ! found )) && command -v ndc >/dev/null 2>&1; then
    local out=""
    out=$( { ndc resolver getdnsnetid 2>/dev/null; ndc resolver getifacedns wlan0 2>/dev/null; \
             ndc resolver getifacedns rmnet0 2>/dev/null; } | tr ' ' '\n' || true)
    while IFS= read -r ip; do
      if valid_ipv4 "$ip"; then printf 'ndc\t%s\n' "$ip"; found=1; fi
    done <<<"$out"
  fi

  (( found )) && return 0

  # 4) 兜底：公共 DNS（明确标记来源，方便排查）
  printf 'fallback(公共 DNS)\t1.1.1.1\n'
  printf 'fallback(公共 DNS)\t8.8.8.8\n'
  return 0
}

prepare_dns() {
  # ⚠️ `local lines=()` 在 mksh 里是语法错误（`unexpected '('`）：数组变量要
  #    先声明、再单独赋值。见 tools/shell-compat-check.mjs。
  local lines dns_all
  local ip="" src="" first_src=""
  lines=()
  dns_all=$(detect_dns)
  while IFS=$'\t' read -r src ip; do
    [[ -n "$ip" ]] || continue
    lines+=("$ip")
    [[ -z "$first_src" ]] && first_src=$src
  done <<EOF
$dns_all
EOF

  DNS_IPS=("${lines[@]+"${lines[@]}"}")
  DNS_SOURCE=${first_src:-未知}

  mkdir -p -- "$ROOTFS/etc" 2>/dev/null || fail_json 1 "无法创建 $ROOTFS/etc"
  local tmp="$ROOTFS/etc/resolv.conf.sunsetlinux.$$"
  {
    printf '# 由 SunsetLinux start.sh 写入 %s（来源：%s）\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$DNS_SOURCE"
    printf '# 不要手动编辑：每次 start 都会按 Android 当前 DNS 重写。\n'
    local x
    for x in "${DNS_IPS[@]}"; do printf 'nameserver %s\n' "$x"; done
    printf 'options timeout:2 attempts:3\n'
  } >"$tmp" 2>/dev/null || { rm -f -- "$tmp"; fail_json 1 "无法写入 $ROOTFS/etc/resolv.conf"; }
  mv -f -- "$tmp" "$ROOTFS/etc/resolv.conf" 2>/dev/null || { rm -f -- "$tmp"; fail_json 1 "写入 resolv.conf 失败"; }
  log "DNS（来源：$DNS_SOURCE）：${DNS_IPS[*]:-无} → $ROOTFS/etc/resolv.conf"
  return 0
}

# -----------------------------------------------------------------------------
# 时区 / 主机名 / 密钥环境文件
# -----------------------------------------------------------------------------
prepare_tz() {
  local tz=""
  if command -v getprop >/dev/null 2>&1; then
    tz=$(getprop persist.sys.timezone 2>/dev/null | tr -d '[:space:]' || true)
  fi
  [[ -n "$tz" && "$tz" != "null" ]] || tz="UTC"
  TZ_GUEST=$tz
  export TZ="$tz"
  printf '%s\n' "$tz" >"$ROOTFS/etc/sunsetlinux-tz" 2>/dev/null || true
  log "时区：$tz"
  return 0
}

load_env_file() {
  local ef="$ETC_DIR/env"
  [[ -f "$ef" ]] || return 0
  local mode="" names=""
  mode=$(stat -c %a "$ef" 2>/dev/null || printf '')
  if [[ -n "$mode" && "$mode" != "600" ]]; then
    warn "etc/env 权限是 $mode，正在收紧到 600"
    chmod 0600 -- "$ef" 2>/dev/null || warn "收紧 etc/env 权限失败，请手动 chmod 600 $ef"
  fi
  names=$(sed -n 's/^[[:space:]]*\([A-Za-z_][A-Za-z0-9_]*\)[[:space:]]*=.*/\1/p' "$ef" 2>/dev/null | sort -u | tr '\n' ' ' || true)
  set -a
  # shellcheck disable=SC1090
  if ! . "$ef"; then
    warn "etc/env 解析失败（语法错误？）已忽略该文件"
  fi
  set +a
  # 只打印变量名，绝不打印值 —— 避免密钥进日志
  log "已从 etc/env 注入环境变量（值不进日志）：${names:-无}"
  return 0
}

# -----------------------------------------------------------------------------
# 启动
# -----------------------------------------------------------------------------
rotate_log() {
  local max=$((8 * 1024 * 1024)) size=""
  [[ -f "$LOG_FILE" ]] || return 0
  size=$(stat -c %s "$LOG_FILE" 2>/dev/null || printf '0')
  if is_uint "$size" && (( size > max )); then
    mv -f -- "$LOG_FILE" "$LOG_FILE.1" 2>/dev/null || true
    log "日志超过 8 MiB，已轮转为 linux.log.1"
  fi
  return 0
}

already_running() {
  local p=""
  p=$(cat "$RUN_DIR/dsh.pid" 2>/dev/null | tr -dc '0-9' || true)
  [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null
}

# 端口探测：**不能用 bash 的 /dev/tcp**（mksh 没有该特性，真机上恒为"连不上"——
# 表现为"服务起来了但 start 一直等到超时"）。三层回退，见 linuxctl.sh 里的同一段注释。
tcp_listen_local() { # /proc/net/tcp[6] 里有没有该端口的 LISTEN
  local hex=""
  hex=$(printf '%04X' "${1-}" 2>/dev/null || true)
  [ -n "$hex" ] || return 1
  awk -v want=":${hex}" '
    NR > 1 && $4 == "0A" && substr($2, length($2) - length(want) + 1) == want { f = 1; exit }
    END { exit(f ? 0 : 1) }
  ' /proc/net/tcp /proc/net/tcp6 2>/dev/null
}

port_open_local() {
  local port="${1-}"
  is_uint "$port" || return 1
  # 去掉前导 0：`printf '%04X' 03080` 会按**八进制**解释 → 算出错误的端口号
  while :; do case $port in 0?*) port=${port#0} ;; *) break ;; esac; done
  [ "$port" -ge 1 ] && [ "$port" -le 65535 ] || return 1
  tcp_listen_local "$port" && return 0
  if command -v nc >/dev/null 2>&1; then
    nc -z -w 2 127.0.0.1 "$port" >/dev/null 2>&1 && return 0
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk -v want=":${port}" '
      NR > 1 && substr($4, length($4) - length(want) + 1) == want { f = 1; exit }
      END { exit(f ? 0 : 1) }
    ' && return 0
  fi
  return 1
}

launch_background() {
  mkdir -p -- "$RUN_DIR" 2>/dev/null || fail_json 1 "无法创建 $RUN_DIR"
  rm -f -- "$RUN_DIR/dsh.pid" "$RUN_DIR/dsh.port" "$RUN_DIR/proot.pid" "$RUN_DIR/dsh.pid.starttime" 2>/dev/null || true
  printf 'starting\n' >"$RUN_DIR/state"
  date +%s >"$RUN_DIR/started_at" 2>/dev/null || true
  rotate_log

  local launcher
  launcher=()
  if command -v setsid >/dev/null 2>&1; then
    launcher=(setsid)
  else
    warn "缺少 setsid：环境仍会被 nohup 脱离终端，但无法脱离调用方的进程组（App 被整组杀掉时可能一起死）。"
  fi
  command -v nohup >/dev/null 2>&1 || warn "缺少 nohup，将直接后台运行。"

  # 关键：不用 proot --kill-on-exit。setsid 让 proot 成为独立会话/进程组组长，
  # 子 shell 先把自己的 PID 写进 run/proot.pid（exec 后 PID 不变，即 proot 的 PID）。
  # 注意：不能写 `exec "$@"`——$@ 以 proot 的开关（-r/-b/...）开头，会被 exec 当成自己的选项。
  "${launcher[@]+"${launcher[@]}"}" nohup bash -c '
    # 关闭从 linuxctl 继承来的文件描述符 —— 尤其是 flock 的锁 fd。
    # 不关的话后台 guest（proot/node）会一直持有文件锁，
    # 之后每一个 linuxctl 子命令都会「等锁超时」，环境看起来像卡死。
    i=3
    while [ "$i" -le 64 ]; do eval "exec $i>&-" 2>/dev/null || true; i=$((i+1)); done
    printf "%s\n" "$$" >"$1" 2>/dev/null || true
    shift
    prog=$1
    shift
    exec "$prog" "$@"
  ' _ "$RUN_DIR/proot.pid" "${PROOT_ARGS[@]}" /opt/sunsetlinux/entry.sh >>"$LOG_FILE" 2>&1 </dev/null &

  log "已后台启动（独立会话，日志追加到 $LOG_FILE）"
  return 0
}

wait_ready() { # 返回 0=就绪；1=进程活着但还没监听；2=进程已死
  local deadline=$(( $(date +%s) + START_TIMEOUT )) pid="" ppid="" port="" started_at=$SECONDS
  while (( $(date +%s) < deadline )); do
    pid=$(cat "$RUN_DIR/dsh.pid" 2>/dev/null | tr -dc '0-9' || true)
    ppid=$(cat "$RUN_DIR/proot.pid" 2>/dev/null | tr -dc '0-9' || true)
    port=$(cat "$RUN_DIR/dsh.port" 2>/dev/null | tr -dc '0-9' || true)
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      if [[ -n "$port" ]] && port_open_local "$port"; then
        WAIT_SECONDS=$(( SECONDS - started_at ))
        RESULT_PID=$pid; RESULT_PORT=$port
        return 0
      fi
    else
      # 还没写 pid 文件，或者进程已经没了
      if [[ -n "$ppid" ]] && ! kill -0 "$ppid" 2>/dev/null; then
        WAIT_SECONDS=$(( SECONDS - started_at ))
        return 2
      fi
      if [[ -z "$ppid" ]] && (( SECONDS - started_at > 10 )); then
        WAIT_SECONDS=$(( SECONDS - started_at ))
        return 2
      fi
    fi
    sleep 0.3
  done
  WAIT_SECONDS=$(( SECONDS - started_at ))
  pid=$(cat "$RUN_DIR/dsh.pid" 2>/dev/null | tr -dc '0-9' || true)
  port=$(cat "$RUN_DIR/dsh.port" 2>/dev/null | tr -dc '0-9' || true)
  RESULT_PID=${pid:-}; RESULT_PORT=${port:-}
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then return 1; fi
  return 2
}

tail_log() {
  log "—— $LOG_FILE 末尾 25 行 ——"
  tail -n 25 -- "$LOG_FILE" 2>/dev/null >&2 || true
  log "—— 日志结束 ——"
  return 0
}

# -----------------------------------------------------------------------------
# 主流程
# -----------------------------------------------------------------------------
parse_args() {
  while (( $# )); do
    case "$1" in
      --fake-root) FAKE_ROOT_FLAG=1; shift ;;
      --no-fake-root) FAKE_ROOT_FLAG=0; shift ;;
      --no-json) EMIT_JSON=0; shift ;;
      --json) EMIT_JSON=1; shift ;;
      --inner)
        MODE_INNER=1; shift
        [[ "${1-}" == "--" ]] && shift
        INNER_CMD=("$@")
        break ;;
      -h|--help)
        cat >&2 <<'EOF'
start.sh [--fake-root|--no-fake-root] [--no-json]
start.sh --inner -- <cmd> [args...]
EOF
        exit 0 ;;
      *) fail_json 2 "start.sh: 未知参数 $1" ;;
    esac
  done
  return 0
}

main() {
  parse_args "$@"
  resolve_paths

  # 明确拒绝注入式补丁：NODE_OPTIONS --import=... 是 DSHA 那套脆弱做法的根源
  if [[ -n "${NODE_OPTIONS:-}" ]]; then
    warn "检测到继承来的 NODE_OPTIONS，已清除（SunsetLinux 不使用 --import 注入式补丁）：$NODE_OPTIONS"
    unset NODE_OPTIONS
  fi

  [[ -d "$ROOTFS" ]] || fail_json 1 "rootfs 不存在：$ROOTFS（先执行 linuxctl provision --seed <dir>）"
  [[ -e "$ROOTFS/bin/sh" || -e "$ROOTFS/bin/bash" ]] || \
    fail_json 1 "rootfs 不完整（找不到 /bin/sh）：$ROOTFS。请 linuxctl reset 或 provision --seed 重新部署。"
  [[ -f "$ROOTFS/opt/sunsetlinux/entry.sh" ]] || \
    fail_json 1 "rootfs 内缺少 /opt/sunsetlinux/entry.sh。请先执行 linuxctl provision（会把 entry.sh 装进去）。"

  mkdir -p -- "$RUN_DIR" "$ROOTFS/root" "$ROOTFS/run/sunsetlinux" "$ROOTFS/var/run" "$ROOTFS/sdcard" 2>/dev/null || true

  resolve_proot

  # 伪造 root 的决策顺序：命令行 > 环境变量 > config.json（默认 false）
  FAKE_ROOT=""
  if [[ -n "$FAKE_ROOT_FLAG" ]]; then FAKE_ROOT=$FAKE_ROOT_FLAG
  elif [[ -n "${SUNSETLINUX_PROOT_FAKE_ROOT:-}" ]]; then
    # 原来是 `$([[ "$x" =~ ^(1|true|yes|on)$ ]] && printf 1 || printf 0)`——
    # mksh 没有 `=~`；`case` 的 glob 在这里完全等价。
    case "${SUNSETLINUX_PROOT_FAKE_ROOT}" in
      1|true|yes|on) FAKE_ROOT=1 ;;
      *)             FAKE_ROOT=0 ;;
    esac
  else
    FAKE_ROOT=$(json_get_bool "$ETC_DIR/config.json" fake_root)
  fi
  [[ "$FAKE_ROOT" == "true" ]] && FAKE_ROOT=1
  [[ "$FAKE_ROOT" == "false" || -z "$FAKE_ROOT" ]] && FAKE_ROOT=0
  # 命令行显式指定时固化到 config.json，便于 doctor/status 反映真实意图
  if [[ -n "$FAKE_ROOT_FLAG" && -f "$ETC_DIR/config.json" ]]; then
    local tmp="$ETC_DIR/config.json.tmp.$$"
    sed "s/\"fake_root\"[[:space:]]*:[[:space:]]*\(true\|false\)/\"fake_root\": $(jbool "$FAKE_ROOT")/" \
      "$ETC_DIR/config.json" >"$tmp" 2>/dev/null && mv -f -- "$tmp" "$ETC_DIR/config.json" 2>/dev/null || rm -f -- "$tmp" 2>/dev/null || true
  fi

  build_proot_args
  prepare_dns
  prepare_tz
  load_env_file

  local port="" host="127.0.0.1"
  port=${SUNSETLINUX_PORT:-}
  [[ -n "$port" ]] || port=$(json_get_num "$ETC_DIR/config.json" port)
  [[ -n "$port" ]] || port=3080
  host=${SUNSETLINUX_HOST:-}
  [[ -n "$host" ]] || host=$(sed -n 's/.*"host"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$ETC_DIR/config.json" 2>/dev/null | head -1 || true)
  [[ -n "$host" ]] || host=127.0.0.1

  # 传给 guest 的环境变量（这是密钥进环境的唯一通道，命令行里永远没有它们）
  export SUNSETLINUX_PORT="$port"
  export SUNSETLINUX_HOST="$host"
  export SUNSETLINUX_RUN_DIR="$GUEST_RUNDIR"
  export SUNSETLINUX_LINUX_HOME="$LINUX_HOME"
  export SUNSETLINUX_HOSTNAME="$HOSTNAME_GUEST"
  export SUNSETLINUX_FAKE_ROOT="$FAKE_ROOT"
  export SUNSETLINUX_DNS_SOURCE="$DNS_SOURCE"
  export SUNSETLINUX_TZ="$TZ_GUEST"
  export TZ="$TZ_GUEST"
  export SUNSETLINUX_TOP="$LINUX_HOME"

  if (( MODE_INNER )); then
    # 前台进入环境执行命令：stdout/退出码都属于被执行的命令本身
    local cmd
    cmd=()
    if (( ${#INNER_CMD[@]} )); then cmd=("${INNER_CMD[@]}"); else cmd=(/bin/bash -l); fi
    log "进入环境执行：${cmd[*]}"
    exec "${PROOT_ARGS[@]}" "${cmd[@]}"
  fi

  if already_running; then
    local p=""
    p=$(cat "$RUN_DIR/dsh.pid" 2>/dev/null | tr -dc '0-9' || true)
    log "环境已在运行（PID $p），跳过启动（幂等）"
    RESULT_OK=true
    RESULT_JSON=$(json_obj schema "$(jint "$SCHEMA_VERSION")" mode "$(jstr "$MODE")" ok true \
      started false already_running true pid "$(jint "$p")" port "$(jint "$port")" \
      url "$(jstr "http://127.0.0.1:$port")" ready true fake_root "$(jbool "$FAKE_ROOT")" \
      dns_source "$(jstr "$DNS_SOURCE")" log "$(jstr "$LOG_FILE")" last_error null)
    emit_json
    exit 0
  fi

  launch_background

  # 组装 DNS 的 JSON 数组（纯 bash，不依赖 jq）
  local dns_arr='[]'
  if (( ${#DNS_IPS[@]} )); then
    dns_arr='['"$(printf '"%s",' "${DNS_IPS[@]}" | sed 's/,$//')"']'
  fi
  local skipped_arr='[]'
  if (( ${#BIND_SKIPPED[@]} )); then
    skipped_arr='['"$(printf '"%s",' "${BIND_SKIPPED[@]}" | sed 's/,$//')"']'
  fi

  local rc=0
  wait_ready || rc=$?
  local ready=true
  case $rc in
    0) ready=true ;;
    1) ready=false; warn "已启动但 ${WAIT_SECONDS}s 内还没监听 $port：交给 linuxctl status 的宽限期继续观察。" ;;
    2) ready=false ;;
  esac

  if (( rc == 2 )); then
    printf 'error\n' >"$RUN_DIR/state" 2>/dev/null || true
    printf 'start.sh：环境进程在就绪前退出（proot 或 node 启动失败）\n' >"$RUN_DIR/last_error" 2>/dev/null || true
    tail_log
    RESULT_JSON=$(json_obj schema "$(jint "$SCHEMA_VERSION")" mode "$(jstr "$MODE")" ok false \
      started false pid null proot_pid "$(jint "$(cat "$RUN_DIR/proot.pid" 2>/dev/null | tr -dc '0-9' || true)")" \
      port "$(jint "$port")" url "$(jstr "http://127.0.0.1:$port")" ready false \
      waited_sec "$(jint "$WAIT_SECONDS")" fake_root "$(jbool "$FAKE_ROOT")" \
      dns_source "$(jstr "$DNS_SOURCE")" proot "$(jstr "${PROOT_CMD[0]}")" log "$(jstr "$LOG_FILE")" \
      last_error "$(jstr "环境进程在就绪前退出；详见日志尾部")")
    emit_json
    exit 1
  fi

  if [[ "$ready" == "true" ]]; then printf 'running\n' >"$RUN_DIR/state" 2>/dev/null || true; fi

  RESULT_JSON=$(json_obj schema "$(jint "$SCHEMA_VERSION")" mode "$(jstr "$MODE")" ok true \
    started true pid "$(jint "${RESULT_PID:-}")" proot_pid "$(jint "$(cat "$RUN_DIR/proot.pid" 2>/dev/null | tr -dc '0-9' || true)")" \
    port "$(jint "${RESULT_PORT:-$port}")" url "$(jstr "http://127.0.0.1:${RESULT_PORT:-$port}")" \
    ready "$(jbool "$ready")" waited_sec "$(jint "$WAIT_SECONDS")" fake_root "$(jbool "$FAKE_ROOT")" \
    dns_source "$(jstr "$DNS_SOURCE")" dns "$dns_arr" binds_skipped "$skipped_arr" \
    proot "$(jstr "${PROOT_CMD[0]}")" log "$(jstr "$LOG_FILE")" last_error null)
  emit_json
  exit 0
}

DNS_IPS=()
DNS_SOURCE=""
TZ_GUEST="UTC"
FAKE_ROOT="0"
WAIT_SECONDS=0
RESULT_PID=""
RESULT_PORT=""

main "$@"
