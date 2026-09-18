#!/system/bin/sh
# =============================================================================
# SunsetLinux — proot 模式 entry.sh 解析器自测（selftest.sh）        v1.0.0
#
# 为什么单独有这个文件：
#   `dsh web` 的登录令牌只能从启动那一行解析出来（architecture.md §3.3），
#   而这行文本的解析规则（`dsh web: ` 前缀、到第一个空白为止、必须含 `?token=`）
#   是**对上游输出格式的硬依赖**，最容易随 DSH 版本漂移。所以这里把解析规则
#   单独拉出来做回归测试：用合成日志直接调 entry.sh 里的真实函数。
#
# 安全性：entry.sh 会写 /etc/hosts、/etc/localtime、/etc/resolv.conf，
#   在开发机上直接执行很危险；entry.sh 因此提供了 SUNSETLINUX_ENTRY_LIB=1 钩子，
#   只加载函数、不碰系统文件。本脚本用的就是这个钩子，**不会改动任何系统文件**。
#
# 用法：runtime/proot/selftest.sh          # 全部通过退出码 0
# =============================================================================
set -uo pipefail

SELF_DIR=$(cd -- "$(dirname -- "$0")" >/dev/null 2>&1 && pwd -P)
ENTRY="$SELF_DIR/entry.sh"
[ -f "$ENTRY" ] || { printf '找不到 %s\n' "$ENTRY" >&2; exit 1; }

TMP=$(mktemp -d "${TMPDIR:-/tmp}/sunsetlinux-entry-selftest.XXXXXX")
trap 'rm -rf -- "$TMP"' EXIT

RUNDIR="$TMP/run"
mkdir -p "$RUNDIR"
LOG="$RUNDIR/linux.log"
: >"$LOG"

# 只加载 entry.sh 的函数（不执行主流程）
# shellcheck disable=SC1090
SUNSETLINUX_ENTRY_LIB=1 SUNSETLINUX_RUN_DIR="$RUNDIR" . "$ENTRY"

PASS=0; FAIL=0
ok()   { PASS=$(( PASS + 1 )); printf '  [OK]   %s\n' "$1"; }
bad()  { FAIL=$(( FAIL + 1 )); printf '  [FAIL] %s\n         期望: %s\n         实际: %s\n' "$1" "$2" "$3"; }
eq()   { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$2" "$3"; fi; }
neq()  { if [ "$2" != "$3" ]; then ok "$1"; else bad "$1" "≠ $2" "$3"; fi; }

printf '== entry.sh 带令牌 URL 解析器自测（契约 §3.3）==\n'

TOKEN='AbC-123_xyz'
LANTOKEN='LAN-Token-9'

# --- 1. 标准行 -------------------------------------------------------------
cat >"$LOG" <<EOF
[entry] dsh 已后台启动，pid=1234
dsh web: http://127.0.0.1:3080/?token=$TOKEN
EOF
u=$(parse_url_from_log "$LOG" 1)
eq "标准行 → 抓到带令牌 URL" "http://127.0.0.1:3080/?token=$TOKEN" "$u"

# --- 2. 带 (LAN: ...) 尾部 → 只取到第一个空白 ------------------------------
cat >"$LOG" <<EOF
dsh web: http://127.0.0.1:3080/?token=$TOKEN (LAN: http://192.168.1.5:3080/?token=$LANTOKEN)
EOF
u=$(parse_url_from_log "$LOG" 1)
eq "有 (LAN: …) 尾部时不贪婪（只取第一个空白之前）" "http://127.0.0.1:3080/?token=$TOKEN" "$u"
case "$u" in *' '*) bad "结果里不应有空格" "无空格" "$u" ;; *) ok "结果里没有空格" ;; esac

# --- 3. 行首有空白 / 结尾 CR（stderr 或日志前缀扰动） ----------------------
printf '  dsh web: http://127.0.0.1:4000/?token=%s\r\n' "$TOKEN" >"$LOG"
u=$(parse_url_from_log "$LOG" 1)
eq "容忍行首空白与 CR（打到 stderr 也能抓到）" "http://127.0.0.1:4000/?token=$TOKEN" "$u"

# --- 4. 只有 401/其它行 → 空 ------------------------------------------------
cat >"$LOG" <<'EOF'
[entry] dsh 已后台启动，pid=1234
dsh web authentication required; reopen the URL printed by dsh web.
dsh web: opening the default browser...
EOF
u=$(parse_url_from_log "$LOG" 1)
eq "没有令牌行 → 返回空（绝不编造）" "" "$u"

# --- 5. 旧令牌（start_line 之前）必须被忽略 --------------------------------
cat >"$LOG" <<EOF
dsh web: http://127.0.0.1:3080/?token=OLD-TOKEN
dsh web: http://127.0.0.1:3080/?token=$TOKEN
EOF
u=$(parse_url_from_log "$LOG" 2)
eq "从 start_line 之后取，忽略上一轮的旧令牌" "http://127.0.0.1:3080/?token=$TOKEN" "$u"

# --- 6. 缺 ?token= 的行不算 ------------------------------------------------
printf 'dsh web: http://127.0.0.1:3080/\n' >"$LOG"
u=$(parse_url_from_log "$LOG" 1)
eq "无 ?token= → 不算登录 URL" "" "$u"

# --- 7. port 提取 ----------------------------------------------------------
eq "url_port_of 取端口" "3080" "$(url_port_of 'http://127.0.0.1:3080/?token=x')"
eq "url_port_of 非 127.0.0.1 也能取" "19099" "$(url_port_of 'http://localhost:19099/?token=x')"
eq "url_port_of 异常输入 → 空" "" "$(url_port_of 'not-a-url')"

# --- 8. persist_url：写 dsh.url(0600) + 用 URL 端口覆盖 dsh.port -----------
rm -f "$URLFILE" "$PORTFILE"
if persist_url "http://127.0.0.1:4321/?token=$TOKEN"; then ok "persist_url 成功"; else bad "persist_url 成功" "0" "$?"; fi
eq "dsh.url 内容正确" "http://127.0.0.1:4321/?token=$TOKEN" "$(cat "$URLFILE" 2>/dev/null)"
if command -v stat >/dev/null 2>&1; then
  eq "dsh.url 权限 0600" "600" "$(stat -c %a "$URLFILE" 2>/dev/null)"
fi
eq "dsh.port 被 URL 里的实际端口覆盖" "4321" "$(cat "$PORTFILE" 2>/dev/null)"

# --- 9. persist_url 拒绝非法输入 ------------------------------------------
rm -f "$URLFILE"
if persist_url "http://127.0.0.1:4321/"; then bad "无 ?token= 必须被拒绝" "非 0 退出" "0"; else ok "无 ?token= 被拒绝"; fi
if persist_url ""; then bad "空串必须被拒绝" "非 0 退出" "0"; else ok "空串被拒绝"; fi
[ -e "$URLFILE" ] && bad "非法输入不应落盘" "文件不存在" "存在" || ok "非法输入未落盘"

# --- 10. cleanup_run_files -------------------------------------------------
persist_url "http://127.0.0.1:4321/?token=$TOKEN"
cleanup_run_files
[ -e "$URLFILE" ] && bad "cleanup_run_files 删除 dsh.url" "不存在" "存在" || ok "cleanup_run_files 删除 dsh.url"

# --- 11. 启动器契约：entry.sh 必须带 --expose-internals -----------------------
# 免 root 模式的启动行在同一条链上的另一个点：DSH 0.1.6 的 HMR 硬要求
# （见 runtime/root/supervise.sh 的注释与 docs/dsh-profile.md §7.3）。
# 这条脚本随 APK 的 assets/proot-runtime/ 分发，改错了只有真机才看得出来 —— 静态钉住。
ENT="$SELF_DIR/entry.sh"
if [ -f "$ENT" ]; then
    if grep -q -- '--expose-internals' "$ENT"; then
        ok "entry.sh 里有 --expose-internals"
    else
        bad "entry.sh 缺少 --expose-internals（DSH 0.1.6+ 启动即失败）" "存在该 flag" "没有"
    fi
    if grep -qE '"\$NODE_BIN"[[:space:]]+--expose-internals[[:space:]]+"\$DSH_BIN"[[:space:]]+web' "$ENT"; then
        ok "entry.sh 的启动行 = node --expose-internals <dsh> web"
    else
        bad "entry.sh 的启动行不是 node --expose-internals <dsh> web" "node --expose-internals <dsh> web" "其它"
    fi
else
    ok "本机看不到 entry.sh（跳过?）"
fi

printf '\n== 结果：%d 通过 / %d 失败 ==\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
