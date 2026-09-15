#!/system/bin/sh
# =============================================================================
# SunsetLinux — proot 脚本纯函数自测（selftest-funcs.sh）
#
# 为什么有它：`tools/shell-compat-check.mjs` 只能证明「mksh 能解析」，证明不了
#   「mksh 化改写之后语义还对」。本文件把这次改写里**语义最容易被改坏**的纯函数
#   拉出来做回归；测的是**从真实脚本里抽取出来的实现**（与 runtime/root/selftest.sh
#   同一手法），不是复制品 —— 否则测的就不是要发布的代码。
#
#   · is_uint / is_int / jint …… 原来是 `[[ $x =~ ^-?[0-9]+$ ]]`
#   · octet_ok / valid_ipv4 …… 原来是 IPv4 正则 + `(( a <= 255 ))`
#   · valid_name ……………… 原来是 `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`
#   · port_open_local / port_busy  原来是 bash 的 `/dev/tcp`（**mksh 根本没有这个特性**，
#     这类缺陷 `mksh -n` 抓不到，只会表现为"服务起来了却一直判不健康"）
#   · replay_log_delta ………… 原来是进程替换 `2> >(tee …)`
#   · detect_dns ……………… 原来是 `done < <(cmd)`（多行输出靠 heredoc 保住最后一行）
#   · emit_status ……………… 原来是 C 式 `for (( … ))`
#
# 用法（两个解释器都要过；CI 里各跑一遍）：
#   bash runtime/proot/selftest-funcs.sh
#   mksh runtime/proot/selftest-funcs.sh
#
# 退出码：0 全过；1 有失败。
# =============================================================================
set -uo pipefail

SELF_PATH="${BASH_SOURCE[0]:-$0}"      # mksh 下 BASH_SOURCE 未定义 → 退回 $0
SELF_DIR="$(cd -- "$(dirname -- "$SELF_PATH")" && pwd -P)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/sunsetlinux-funcs.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

pass=0; fail=0
ok()    { pass=$((pass+1)); printf '  \033[32mok\033[0m   %s\n' "$*"; }
bad()   { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
head_() { printf '\n== %s ==\n' "$*"; }
eq() { # eq <说明> <期望> <实际>
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1：期望 '$2'，实际 '$3'"; fi
}

START="$SELF_DIR/start.sh"
LINUXCTL="$SELF_DIR/linuxctl.sh"
ROOT_START="$SELF_DIR/../root/start.sh"

# 从真实脚本里抽取函数：不 source 整个脚本（那会执行它的主体：起 proot、写 rootfs）
extract() { # extract <输出文件> <脚本> <函数名>...
  local out="$1" src="$2" fn="" rc=0
  shift 2
  : > "$out"
  for fn in "$@"; do
    if ! grep -q "^${fn}()" "$src" 2>/dev/null; then
      bad "抽取失败：$(basename "$src") 里找不到 ${fn}()（函数被改名/改写成单行了？）"
      rc=1
      continue
    fi
    sed -n "/^${fn}()/,/^}/p" "$src" >> "$out"
  done
  return $rc
}

# -----------------------------------------------------------------------------
# 1) 整数判定（=~ 正则 → case 字符类）
# -----------------------------------------------------------------------------
head_ "整数判定 is_int / jint（原来是 [[ \$x =~ ^-?[0-9]+\$ ]]）"
extract "$TMP/int.sh" "$START" is_uint is_int jint
# shellcheck disable=SC1090
. "$TMP/int.sh"

jint_eq() { # jint_eq <说明> <输入> <期望>
  eq "$1（jint '$2'）" "$3" "$(jint "${2-}")"
}
jint_eq "正整数"        5    5
jint_eq "零"            0    0
jint_eq "负整数"        -5   -5
jint_eq "前导零原样保留" 007  007
jint_eq "空串"          ""   null
jint_eq "非数字"        abc  null
jint_eq "尾部混字符"    5x   null
jint_eq "只有减号"      -    null
jint_eq "双减号"        --5  null
jint_eq "带正号"        +5   null
jint_eq "带空格"        " 5" null

if is_uint 3080; then ok "is_uint 3080 → 真"; else bad "is_uint 3080 应为真"; fi
if is_uint "";   then bad "is_uint 空串应为假"; else ok "is_uint 空串 → 假"; fi
if is_uint 3.5;  then bad "is_uint 3.5 应为假"; else ok "is_uint 3.5 → 假"; fi

# -----------------------------------------------------------------------------
# 2) IPv4 校验（正则 + (( )) → 切分 + 逐段判定）
# -----------------------------------------------------------------------------
head_ "IPv4 校验 valid_ipv4 / octet_ok（原来是 IPv4 正则 + (( a <= 255 ))）"
extract "$TMP/ip.sh" "$START" octet_ok valid_ipv4
# shellcheck disable=SC1090
. "$TMP/ip.sh"

v4_ok()  { if valid_ipv4 "$1"; then ok "valid_ipv4 '$1' → 通过"; else bad "valid_ipv4 '$1' 应通过，实际被拒"; fi }
v4_bad() { if valid_ipv4 "$1"; then bad "valid_ipv4 '$1' 应被拒，实际通过"; else ok "valid_ipv4 '$1' → 拒绝"; fi }

v4_ok  10.1.2.3
v4_ok  8.8.8.8
v4_ok  192.168.1.255
v4_ok  255.255.255.255
v4_ok  010.1.1.1        # 前导 0：旧实现 (( 08… )) 会踩八进制，这里必须仍然通过
v4_ok  1.1.1.01
v4_bad 0.0.0.0          # 旧实现显式排除（不是有效 DNS）
v4_bad 127.0.0.1        # 回环不算
v4_bad 127.1.2.3
v4_bad 256.1.1.1
v4_bad 300.1.1.1
v4_bad 999.999.999.999
v4_bad 1.2.3            # 只有三段
v4_bad 1.2.3.4.5        # 五段
v4_bad 1.2.3.
v4_bad .1.2.3
v4_bad 1..1.1
v4_bad 1.1.1.a
v4_bad " 1.1.1.1"
v4_bad "1.1.1.1 "
v4_bad ""

# -----------------------------------------------------------------------------
# 3) 快照名校验
# -----------------------------------------------------------------------------
head_ "快照名校验 valid_name（原来是 ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\$）"
extract "$TMP/name.sh" "$LINUXCTL" valid_name
# shellcheck disable=SC1090
. "$TMP/name.sh"

name_ok()  { if valid_name "$1"; then ok "valid_name '$1' → 通过"; else bad "valid_name '$1' 应通过，实际被拒"; fi }
name_bad() { if valid_name "$1"; then bad "valid_name '$1' 应被拒，实际通过"; else ok "valid_name '$1' → 拒绝"; fi }

name_ok  a
name_ok  abc
name_ok  a.b-c_1
name_ok  A9
name_bad ""
name_bad -a
name_bad .a
name_bad _a
name_bad "a/b"
name_bad "a b"
name_bad "a\$b"
name_bad "中文"

long64=""; i=0
while [ "$i" -lt 64 ]; do long64="${long64}a"; i=$((i+1)); done
name_ok  "$long64"          # 正好 64 个字符
name_bad "${long64}b"       # 65 个字符

# -----------------------------------------------------------------------------
# 4) 端口探测（原来是 bash 的 /dev/tcp；mksh 下恒失败）
# -----------------------------------------------------------------------------
head_ "端口探测 port_open_local / port_busy（原来是 bash 的 /dev/tcp）"
extract "$TMP/port.sh" "$LINUXCTL" is_uint tcp_listen_local port_open_local
# shellcheck disable=SC1090
. "$TMP/port.sh"
extract "$TMP/port-root.sh" "$ROOT_START" tcp_listen_local port_busy

# root 版的 port_busy 与这里同名，放到子 shell 里测，避免互相覆盖
root_port_busy() { ( . "$TMP/port-root.sh" 2>/dev/null; port_busy "${1-}" ); }

# 自己起一个只监听、不回话的 TCP server，避免依赖环境里"恰好有服务在跑"
start_listener() { # start_listener <port> → 打印后台 PID（起不来则打印空）
  local port="$1"
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import socket,sys,time
s=socket.socket()
s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
s.bind(("127.0.0.1",int(sys.argv[1])))
s.listen(5)
time.sleep(60)' "$port" >/dev/null 2>&1 &
    printf '%s' "$!"
    return 0
  fi
  if command -v node >/dev/null 2>&1; then
    node -e 'require("net").createServer(function(c){c.destroy()}).listen(Number(process.argv[1]),"127.0.0.1");setTimeout(function(){process.exit(0)},60000)' "$port" >/dev/null 2>&1 &
    printf '%s' "$!"
    return 0
  fi
  return 0
}

PORT=$(( 30000 + ($$ % 20000) ))
LPID="$(start_listener "$PORT")"
if [ -z "$LPID" ]; then
  bad "本机既没有 python3 也没有 node：起不了监听端口，端口探测回归**没跑**（CI 上必然有 python3）"
else
  i=0; up=1
  while [ "$i" -lt 100 ]; do
    if port_open_local "$PORT"; then up=0; break; fi
    sleep 0.1 2>/dev/null || sleep 1
    i=$((i+1))
  done
  if [ "$up" -eq 0 ]; then
    ok "端口 $PORT 正在监听 → port_open_local 判为打开"
  else
    bad "端口 $PORT 起了监听，port_open_local 仍判为关闭（/proc/net/tcp 解析坏了？）"
  fi
  if root_port_busy "$PORT"; then ok "root 版 port_busy 同样判为占用"; else bad "root 版 port_busy 判为空闲"; fi
  # 前导 0：`printf '%04X' 03080` 会按八进制解释（→ 错误端口），所以要归一化后再算
  if port_open_local "0$PORT"; then ok "前导 0 的端口写法同样识别（不当成八进制）"; else bad "带前导 0 的端口没被识别"; fi
  kill "$LPID" 2>/dev/null
  wait "$LPID" 2>/dev/null
  i=0; down=1
  while [ "$i" -lt 50 ]; do
    if ! port_open_local "$PORT"; then down=0; break; fi
    sleep 0.1 2>/dev/null || sleep 1
    i=$((i+1))
  done
  if [ "$down" -eq 0 ]; then ok "监听关闭后 → 判为未监听"; else bad "监听已关闭，port_open_local 仍判为打开"; fi
fi

if port_open_local abc;   then bad "port_open_local 非法入参应被拒"; else ok "非法入参 → 拒绝"; fi
if port_open_local 0;     then bad "端口 0 应被拒"; else ok "端口 0 → 拒绝"; fi
if port_open_local 70000; then bad "端口 70000 应被拒"; else ok "端口 70000 → 拒绝"; fi

# -----------------------------------------------------------------------------
# 5) 日志增量回放（进程替换 → 日志追加 + tail 回放）
# -----------------------------------------------------------------------------
head_ "日志增量回放 replay_log_delta（原来是进程替换 2> >(tee …)）"
extract "$TMP/log.sh" "$LINUXCTL" is_uint replay_log_delta
# shellcheck disable=SC1090
. "$TMP/log.sh"
LOG_FILE="$TMP/linux.log"
printf 'abcdef\n' > "$LOG_FILE"
eq "偏移 0 → 回放整份"        "abcdef" "$(replay_log_delta 0 2>&1)"
eq "偏移 3 → 只回放增量"      "def"    "$(replay_log_delta 3 2>&1)"
eq "非法偏移按 0 处理"        "abcdef" "$(replay_log_delta abc 2>&1)"
eq "偏移超过文件长度 → 空"    ""       "$(replay_log_delta 100 2>&1)"

# -----------------------------------------------------------------------------
# 6) DNS 多路回退（done < <(cmd) → 变量 + heredoc）
# -----------------------------------------------------------------------------
head_ "DNS 多路回退 detect_dns（原来是 done < <(getprop …)）"
extract "$TMP/dns.sh" "$START" octet_ok valid_ipv4 detect_dns
# shellcheck disable=SC1090
. "$TMP/dns.sh"

mkdir -p "$TMP/bin"
cat > "$TMP/bin/getprop" <<'EOF'
#!/bin/sh
# 假 getprop：A = 单个 net.dns1 有值；B = 单键全空、整份 dump 有多行
case "${SUNSETLINUX_FAKE_GETPROP:-A}" in
  A) case "${1-}" in net.dns1) printf '10.1.2.3\n' ;; *) exit 1 ;; esac ;;
  B) case "${1-}" in
       "") printf '[net.dns1]: [10.1.2.3]\n[net.dns2]: [8.8.4.4]\n[net.eth0.dns1]: [9.9.9.9]\n' ;;
       *) exit 1 ;;
     esac ;;
esac
EOF
chmod +x "$TMP/bin/getprop"
PATH="$TMP/bin:$PATH"

SUNSETLINUX_FAKE_GETPROP=A
export SUNSETLINUX_FAKE_GETPROP        # 假 getprop 是被 exec 的子进程，必须 export
out="$(detect_dns 2>/dev/null)"
case $out in
  *"getprop net.dns1"*"10.1.2.3"*) ok "A：单个 net.dns1 命中" ;;
  *) bad "A：单个 net.dns1 未命中，输出：$out" ;;
esac

SUNSETLINUX_FAKE_GETPROP=B
out="$(detect_dns 2>/dev/null)"
case $out in
  *"10.1.2.3"*"8.8.4.4"*"9.9.9.9"*)
    ok "B：接口 dns 的多行输出全部保住（尤其最后一行 —— here-string 少个换行就会丢它）" ;;
  *) bad "B：接口 dns 丢行，输出：$out" ;;
esac
case $out in
  *"getprop(接口 dns)"*) ok "B：来源标注正确" ;;
  *) bad "B：来源标注丢失，输出：$out" ;;
esac

# -----------------------------------------------------------------------------
# 7) status JSON 附加键（C 式 for (( )) → while）
# -----------------------------------------------------------------------------
head_ "status 附加键 emit_status（原来是 C 式 for (( … ))）"
extract "$TMP/status.sh" "$LINUXCTL" jstr jbool emit_status
# shellcheck disable=SC1090
. "$TMP/status.sh"

SCHEMA_VERSION=1
ST_STATE='"error"'; ST_PID='null'; ST_UPTIME='null'
ST_URL='null'; ST_BASE_URL='null'; ST_PORT='null'; ST_DSH_VER='null'; ST_HEALTHY='false'
ST_BASE_VER='null'; ST_BASE_SIZE='null'; ST_BASE_MOUNTED='false'
ST_RT_VER='null'; ST_RT_SIZE='null'; ST_RT_MOUNTED='false'
ST_DSH_SIZE='null'; ST_DSH_MOUNTED='false'
ST_UPPER_USED='null'; ST_UPPER_TOTAL='null'; ST_LAST_ERROR='null'

out="$(emit_status action '"start"' ok true 2>/dev/null)"
case $out in
  *',"action":"start","ok":true}'*) ok "两个附加键按顺序、成对追加" ;;
  *) bad "附加键没按预期追加：$out" ;;
esac
out="$(emit_status 2>/dev/null)"
case $out in
  *'}'*) ok "没有附加键时仍以 } 收尾（空数组不炸）" ;;
  *) bad "空附加键输出异常：$out" ;;
esac

# -----------------------------------------------------------------------------
printf '\n== 结果：%d 通过 / %d 失败 ==\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
