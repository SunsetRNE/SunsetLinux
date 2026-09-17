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

SELF_PATH="$0"      # mksh 下 BASH_SOURCE 未定义 → 退回 $0
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
# root 版这两个函数已抽到 runtime/common/port-probe.sh（与 linuxctl 共用）——
# 抽取目标跟着搬，测的仍是**真正会跑的那份实现**。
COMMON_PORT="$SELF_DIR/../common/port-probe.sh"
extract "$TMP/port-root.sh" "$COMMON_PORT" tcp_listen_local port_busy port_pick_free

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

  # port_pick_free：端口被占时让路（真机现场：免 root 的 DSHA 占着 3080）。
  # 这里用**桩**替换 port_busy，判据与端口可见性无关 ⇒ CI/沙箱/真机结果一致。
  pick() { ( . "$TMP/port-root.sh" 2>/dev/null; port_busy() { case "$1" in 3080|3081|3082) return 0 ;; *) return 1 ;; esac; }; port_pick_free "$1" ); }
  got="$(pick 3080)"
  if [ "$got" = "3083" ]; then ok "port_pick_free：3080~3082 全被占 → 让到 3083"
  else bad "port_pick_free 让路结果不对：得到 $got（期望 3083）"; fi
  got="$(pick 4000)"
  if [ "$got" = "4000" ]; then ok "port_pick_free：端口空闲 → 原样返回"
  else bad "port_pick_free 空闲时改了口：得到 $got（期望 4000）"; fi
  got="$( ( . "$TMP/port-root.sh" 2>/dev/null; port_busy() { return 0; }; port_pick_free 3080 5 ) )"
  if [ "$got" = "3080" ]; then ok "port_pick_free：找不到空闲端口 → 原样返回首选值（不编造）"
  else bad "port_pick_free 兜底不对：得到 $got（期望 3080）"; fi
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
  *',"action":"start","ok":true,'*) ok "两个附加键按顺序、成对追加" ;;
  *) bad "附加键没按预期追加：$out" ;;
esac
# rootless 是"实际用了哪个免 root 运行时"：没启动过时报 null，不能编造
case $out in
  *'"rootless":{"kind":null,"version":null}}') ok "未启动时 rootless 如实报 null" ;;
  *) bad "rootless 字段不对：$out" ;;
esac
out="$(emit_status 2>/dev/null)"
case $out in
  *'}'*) ok "没有附加键时仍以 } 收尾（空数组不炸）" ;;
  *) bad "空附加键输出异常：$out" ;;
esac

# -----------------------------------------------------------------------------
# 5) 非 root 运行时选择：proroot 首选、proot 降级（2026-09-16）
#
# 为什么测它：免 root 方案以前只有 proot；引入 proroot 之后"到底用了谁"必须可解释 ——
# 选错了用户只会看到"环境起不来/很慢"，完全无从下手。
# 这几条断言钉住决策表：auto 优先 proroot、缺件降级并**说明原因**、
# 显式 proot 不被抢走、显式 proroot 缺件**必须失败**（静默降级等于骗人）。
# -----------------------------------------------------------------------------
head_ "rootless 运行时选择（proroot 首选 / proot 降级）"
extract "$TMP/rootless.sh" "$START" json_get_str json_get_bool resolve_rootless resolve_proot build_proot_args

NL_DIR="$TMP/nativelib"; mkdir -p "$NL_DIR"
for so in libproroot.so libproroot-runtime.so libproroot-linker.so libproroot-bridge.so libproroot-stub-loader.so; do
    # 桩启动器：--help 时打印 --link2symlink（模拟 proroot 的能力探测），其余静默成功
    cat > "$NL_DIR/$so" <<'STUB'
#!/bin/sh
[ "$1" = "--help" ] && echo "  --link2symlink  emulate hardlinks"
exit 0
STUB
    chmod +x "$NL_DIR/$so"
done
NL_EMPTY="$TMP/nativelib-empty"; mkdir -p "$NL_EMPTY"
FAKE_PROOT="$TMP/fake-proot"; printf '#!/bin/sh\nexit 0\n' > "$FAKE_PROOT"; chmod +x "$FAKE_PROOT"

# run_rootless <nativelib> <want> → "kind|cmd0|reason"（失败时打印 FAIL:<消息>）
run_rootless() {
    (
        LINUX_HOME="$TMP/lh-rootless"
        ROOTFS="$LINUX_HOME/rootfs"; ETC_DIR="$LINUX_HOME/etc"; RUN_DIR="$LINUX_HOME/run"
        mkdir -p "$ETC_DIR" "$RUN_DIR" "$ROOTFS" 2>/dev/null
        printf '{}\n' > "$ETC_DIR/config.json"
        SUNSETLINUX_ROOTLESS="$2"; SUNSETLINUX_NATIVE_LIB_DIR="$1"
        SUNSETLINUX_PROROOT_VERSION="1.2.8"; SUNSETLINUX_PROOT_BIN="$FAKE_PROOT"
        PROOT_CMD=""; ROOTLESS_KIND=""; ROOTLESS_VERSION=""; ROOTLESS_REASON=""
        log() { :; }; warn() { :; }; err() { :; }
        fail_json() { printf 'FAIL:%s\n' "$2"; exit 9; }
        find_sh() { printf '/bin/sh'; }
        script_interp() { printf ''; }
        # shellcheck disable=SC1090
        . "$TMP/rootless.sh" || exit 8
        resolve_rootless || exit 7
        printf '%s|%s|%s\n' "$ROOTLESS_KIND" "${PROOT_CMD[0]:-}" "$ROOTLESS_REASON"
    )
}

got="$(run_rootless "$NL_DIR" auto)"
case "$got" in
    "proroot|$NL_DIR/libproroot.so|"*) ok "auto：nativeLibraryDir 里 .so 齐全 → 选 proroot" ;;
    *) bad "auto 没选 proroot：$got" ;;
esac

got="$(run_rootless "$NL_EMPTY" auto)"
case "$got" in
    proot\|*) case "$got" in *"proroot 不可用"*) ok "auto：缺 proroot → 降级 proot，且写明原因" ;; *) bad "降级了但没说原因：$got" ;; esac ;;
    *) bad "auto 缺件时没降级到 proot：$got" ;;
esac

got="$(run_rootless "$NL_DIR" proot)"
case "$got" in
    proot\|*) ok "显式 proot：即使 proroot 齐全也不用它" ;;
    *) bad "显式 proot 被 proroot 抢走了：$got" ;;
esac

got="$(run_rootless "$NL_EMPTY" proroot)"
case "$got" in
    FAIL:*proroot*) ok "显式 proroot 但缺件 → 明确失败（不静默降级）" ;;
    *) bad "显式 proroot 缺件时没有失败：$got" ;;
esac

# 启动参数：proroot 与 proot 的 CLI 兼容（-r/-w/-b/-0/--link2symlink），前缀必须一致
args_of() { # args_of <nativelib>
    (
        LINUX_HOME="$TMP/lh-rootless"; ROOTFS="$LINUX_HOME/rootfs"
        ETC_DIR="$LINUX_HOME/etc"; RUN_DIR="$LINUX_HOME/run"
        SDCARD_HOST=/sdcard; GUEST_RUNDIR=/run/sunsetlinux
        mkdir -p "$ETC_DIR" "$RUN_DIR" 2>/dev/null
        printf '{"link2symlink":true}\n' > "$ETC_DIR/config.json"
        SUNSETLINUX_ROOTLESS=auto; SUNSETLINUX_NATIVE_LIB_DIR="$1"
        SUNSETLINUX_PROROOT_VERSION="1.2.8"; SUNSETLINUX_PROOT_BIN="$FAKE_PROOT"
        PROOT_CMD=""; ROOTLESS_KIND=""
        log() { :; }; warn() { :; }; err() { :; }; fail_json() { exit 9; }
        find_sh() { printf '/bin/sh'; }; script_interp() { printf ''; }
        add_bind() { PROOT_ARGS+=("B:$1"); }
        # shellcheck disable=SC1090
        . "$TMP/rootless.sh" || exit 8
        resolve_rootless || exit 7
        FAKE_ROOT=1
        build_proot_args || exit 6
        printf '%s\n' "${PROOT_ARGS[*]}"
    )
}

args="$(args_of "$NL_DIR")"
case "$args" in
    "$NL_DIR/libproroot.so -r $TMP/lh-rootless/rootfs -w /root"*)
        case "$args" in
            *--link2symlink*) case "$args" in
                *" -0") ok "proroot 启动参数：-r/-w/-b/--link2symlink/-0 与 proot 同一套" ;;
                *) bad "proroot 参数缺伪造 root（-0）：$args" ;;
            esac ;;
            *) bad "proroot 参数缺 --link2symlink（探测失败？）：$args" ;;
        esac ;;
    *) bad "proroot 启动参数前缀不对：$args" ;;
esac

# -----------------------------------------------------------------------------
# 13) erofs 层：非 root 模式必须能吃掉与 root 模式**同一份** base/runtime/dsh 层
#
# 真机 2026-09-16：App 的「频道 / 离线包」分发的层全是 erofs，而 proot 这套脚本只认 tar
# （archive_test 判 erofs 为"损坏"、extract_archive 拿 tar 去解）→ 免 root 模式下
# "去更新页装三层"是死路，而用户看到的却是"已安装"。修法：
#   · is_erofs：magic 在偏移 1024（不是 0）
#   · archive_test：erofs 按 magic 校验（以前落到 case 默认分支 = 什么都不查就放行）
#   · extract_archive：走 fsck.erofs --extract（可用 SUNSETLINUX_EROFS_EXTRACT 指定）
#   · find_base_layer + provision 回退：已装好的 base 层直接当 rootfs 用
# -----------------------------------------------------------------------------
head_ "erofs 层支持（is_erofs / archive_test / extract_archive / find_base_layer）"
EF="$TMP/erofs-funcs.sh"
extract "$EF" "$LINUXCTL" is_erofs erofs_extract_bin erofs_hint archive_test extract_archive find_base_layer zstd_bin zstd_hint
cat >> "$EF" <<'EOS'
err()  { printf 'ERR: %s\n' "$*" >&2; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
log()  { :; }
fail() { printf 'FAIL(%s): %s\n' "$1" "$2" >&2; exit "${1:-1}"; }
EOS

IMG_OK="$TMP/base-24.04.3-l1.erofs"
IMG_BAD="$TMP/base-broken.erofs"
dd if=/dev/zero of="$IMG_OK" bs=512 count=4 2>/dev/null
# ⚠️ 这里**不能**用 `awk 'BEGIN{printf "%c", 226}'`：gawk 在 UTF-8 locale 下会把 226
# 当成码位 U+00E2 编成**两个字节**（0xC3 0xA2），写出来的 erofs magic 是错的 →
# 假层被判成 unknown → 本组断言在 CI（gawk + C.UTF-8）上红，而本地（mawk + POSIX）绿。
# `printf '%b' '\342...'` 在 bash / mksh / dash 下都按八进制写出**单字节**。
printf '%b' '\342\341\365\340' | dd of="$IMG_OK" bs=1 seek=1024 conv=notrunc 2>/dev/null
dd if=/dev/zero of="$IMG_BAD" bs=512 count=4 2>/dev/null

CALLS="$TMP/erofs-calls"; : > "$CALLS"
FX="$TMP/fake-erofs-extract"
cat > "$FX" <<'EOS'
#!/bin/sh
printf '%s\n' "$*" >> "$SUNSETLINUX_TEST_CALLS"
dest=""
for a in "$@"; do case "$a" in --extract=*) dest="${a#--extract=}" ;; esac; done
[ -n "$dest" ] || exit 1
mkdir -p "$dest/bin" || exit 1
: > "$dest/bin/sh"
exit 0
EOS
chmod +x "$FX"

# 跑被测函数的子壳（函数从真实脚本抽取，环境变量显式给全）
ef_run() { # ef_run <函数> [参数...]
    local fn="$1"; shift
    (
        LINUX_HOME="$TMP/lh-erofs"; LH="$LINUX_HOME"
        BIN_DIR="$LINUX_HOME/bin"; ROOTFS="$LINUX_HOME/rootfs"
        SUNSETLINUX_TEST_CALLS="$CALLS"
        export SUNSETLINUX_TEST_CALLS
        # shellcheck disable=SC1090
        . "$EF" || exit 8
        "$fn" "$@"
    )
}

ef_run is_erofs "$IMG_OK" && ok "is_erofs：合法 erofs（magic 在偏移 1024）认得出来" \
                          || bad "is_erofs 没认出合法 erofs"
ef_run is_erofs "$IMG_BAD" && bad "is_erofs 把零字节文件当成 erofs 了" \
                           || ok "is_erofs：magic 不对就拒绝"

ef_run archive_test "$IMG_OK" && ok "archive_test：erofs 通过 magic 校验" \
                              || bad "archive_test 拒绝了合法 erofs"
if out=$(ef_run archive_test "$IMG_BAD" 2>&1); then
    bad "archive_test 放过了损坏的 erofs（以前就是这样：默认分支什么都不查）"
else
    case "$out" in
        *"超级块不可读"*) ok "archive_test：损坏的 erofs 明确报错（不再静默放行）" ;;
        *) bad "archive_test 的 erofs 报错信息不可读：$out" ;;
    esac
fi

OUT="$TMP/erofs-out"; rm -rf "$OUT"
if SUNSETLINUX_EROFS_EXTRACT="$FX" ef_run extract_archive "$IMG_OK" "$OUT" && [ -f "$OUT/bin/sh" ]; then
    ok "extract_archive：走 fsck.erofs --extract 把层解成目录"
else
    bad "extract_archive 没能解开 erofs（假解包器：$FX）"
fi
case "$(cat "$CALLS" 2>/dev/null || true)" in
    *"--extract=$OUT"*) ok "解包器收到 --extract=<目标目录>（契约与 root 模式 dir 模式一致）" ;;
    *) bad "解包器没有收到 --extract=<目录>：$(head -2 "$CALLS" 2>/dev/null)" ;;
esac

# 解包器失败必须**显式失败**，绝不能悄悄"成功"
FX_FAIL="$TMP/fake-erofs-fail"; printf '#!/bin/sh\nexit 1\n' > "$FX_FAIL"; chmod +x "$FX_FAIL"
if out=$(SUNSETLINUX_EROFS_EXTRACT="$FX_FAIL" ef_run extract_archive "$IMG_OK" "$TMP/erofs-fail-out" 2>&1); then
    bad "解包器返回 1 时 extract_archive 仍报成功"
else
    case "$out" in
        *"解包 erofs 失败"*) ok "解包器失败 → extract_archive 明确失败（带上工具与路径）" ;;
        *) bad "解包失败的信息不对：$out" ;;
    esac
fi

# tar 路径不能被 erofs 分支带坏（回归）
TG="$TMP/seed.tar.gz"
printf 'seed\n' > "$TMP/seed.txt"
( cd "$TMP" && tar -czf "$TG" seed.txt ) 2>/dev/null
TOUT="$TMP/tar-out"; rm -rf "$TOUT"
if ef_run extract_archive "$TG" "$TOUT" && [ -f "$TOUT/seed.txt" ]; then
    ok "extract_archive：tar.gz 路径没被 erofs 分支带坏"
else
    bad "extract_archive 解不开 tar.gz 了（回归）"
fi

# find_base_layer：有层就给出路径，没有就返回空
mkdir -p "$TMP/lh-erofs/layers"
cp "$IMG_OK" "$TMP/lh-erofs/layers/base-24.04.3-l1.erofs"
got=$(ef_run find_base_layer)
[ "$got" = "$TMP/lh-erofs/layers/base-24.04.3-l1.erofs" ] \
    && ok "find_base_layer：找到已就位的 base 层（provision 因此能零手工拿它当 rootfs）" \
    || bad "find_base_layer 没找到 base 层：$got"
rm -f "$TMP/lh-erofs/layers/base-24.04.3-l1.erofs"
got=$(ef_run find_base_layer)
[ -z "$got" ] && ok "find_base_layer：没有层时返回空（不编造路径）" || bad "没层时返回了东西：$got"

# -----------------------------------------------------------------------------
printf '\n== 结果：%d 通过 / %d 失败 ==\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
