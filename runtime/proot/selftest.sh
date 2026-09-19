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

# ---------------------------------------------------------------------------
# 免 root 版的「内部提权」：uid 与 DSH 策略是**两件事**，两个形态必须一致
#
# 用户 2026-09-19 的原话："运行进程 UID ≠ 虚拟环境内部 root 提权"。
#   · 进程 uid：proot 不虚拟内核身份，真实 uid 始终是 App 用户；开 fake_root 才在系统调用层
#     把 getuid() 伪造成 0（**没有真 capabilities**、不能 mount）；
#   · DSH 策略：由 DSH_PERMISSION_MODE 单独决定。root 版有 supervise.sh 兜底，
#     proot 版此前**没有**任何等价处理 ⇒ 免 root 用户会稳定遇到"每条命令都要提权"。
# ---------------------------------------------------------------------------
if [ -f "$ENT" ]; then
    if grep -q 'DSH_PERMISSION_MODE=danger-full-access' "$ENT"; then
        ok "免 root 版兜底 DSH_PERMISSION_MODE=danger-full-access（与 root 版对齐）"
    else
        bad "entry.sh 没有策略兜底" "DSH_PERMISSION_MODE=danger-full-access" "没有（免 root 用户会稳定遇到审批）"
    fi
    # 顺序：必须在加载 /root/.dsh/env **之后**，否则用户写在里面的值会被反覆盖
    ln_env=$(grep -n '\. /root/\.dsh/env' "$ENT" | head -n1 | cut -d: -f1)
    ln_def=$(grep -n 'DSH_PERMISSION_MODE=danger-full-access' "$ENT" | head -n1 | cut -d: -f1)
    if [ -n "$ln_env" ] && [ -n "$ln_def" ] && [ "$ln_def" -gt "$ln_env" ]; then
        ok "策略默认值在加载 /root/.dsh/env 之后（用户值优先）"
    else
        bad "策略默认值的位置在 env 加载之前" "def > env" "env=$ln_env def=$ln_def"
    fi
    # 身份必须说清楚：uid 是伪造的、能力受限 —— 否则用户会按 uid 推断能力
    if grep -q '伪造 root' "$ENT" && grep -q '没有真 capabilities' "$ENT"; then
        ok "启动日志说明「uid=0 是伪造的、没有真 capabilities」"
    else
        bad "entry.sh 没有身份说明" "提到伪造 root 与能力受限" "没有"
    fi
fi

# ---------------------------------------------------------------------------
# update 的参数契约：**必须认 `--version`**（真机 2026-09-19 的事故）
#
# 现场：免 root 版点「铺环境（内嵌离线包 + provision）」，倒在
#   ✗ [linuxctl update] base 失败：[linuxctl 错误] update: 未知参数 --version
#
# 根因是**两个 runtime 的命令行不一致**：App 的 `OfflineApplier` 对两个 edition 用同一份
# 命令构造（`update <layer> <file> --version <ver>`），root 版接受，proot 版把它当未知参数
# 直接失败 ⇒ 同一个按钮在免 root 版上必然失败。
#
# 为什么用**子进程**测而不是抽函数：真正要对齐的是"命令行契约"，而这正是漂移掉的东西；
# 抽函数测会连"脚本到底接不接受这个参数"一起绕过去。这两条走的是真实脚本的真实解析路径，
# 且**不碰任何环境**（参数解析在任何副作用之前）。
#
# 判据刻意不看 `file 不存在` 之外的东西：那说明参数已经被接受、流程往下走了；
# 反过来，如果哪天有人把 `--version` 又删了，这两条会同时红，报的就是真机上那句话。
# ---------------------------------------------------------------------------
head_() { printf '\n-- %s\n' "$1"; }
head_ "update 的参数契约（App 发的 --version 必须被接住）"
LINUXCTL="$SELF_DIR/linuxctl.sh"
[ -f "$LINUXCTL" ] || { printf '找不到 %s\n' "$LINUXCTL" >&2; exit 1; }
if [ -z "${SH_BIN:-}" ] || [ ! -x "${SH_BIN:-/nonexistent}" ]; then
    if [ -x /system/bin/sh ]; then SH_BIN=/system/bin/sh; else SH_BIN=sh; fi
fi
NOPE="$TMP/definitely-missing-layer.erofs"

up_out="$("$SH_BIN" "$LINUXCTL" update base "$NOPE" --version 24.04.3-l1 2>&1 || true)"
case "$up_out" in
    *"未知参数"*) bad "update 接受 '--version <ver>'（App 发的写法）" "参数被接受" "$(printf '%s' "$up_out" | tail -n1)" ;;
    *"层文件不存在"*) ok "update 接受 '--version <ver>'（App 发的写法）" ;;
    *) bad "update 接受 '--version <ver>'（App 发的写法）" "层文件不存在" "$(printf '%s' "$up_out" | tail -n1)" ;;
esac

up_out2="$("$SH_BIN" "$LINUXCTL" update base "$NOPE" --version=24.04.3-l1 2>&1 || true)"
case "$up_out2" in
    *"未知参数"*) bad "update 接受 '--version=<ver>'（等号写法）" "参数被接受" "$(printf '%s' "$up_out2" | tail -n1)" ;;
    *"层文件不存在"*) ok "update 接受 '--version=<ver>'（等号写法）" ;;
    *) bad "update 接受 '--version=<ver>'（等号写法）" "层文件不存在" "$(printf '%s' "$up_out2" | tail -n1)" ;;
esac

# ---------------------------------------------------------------------------
# 互斥锁必须在这个 shell 下**真的能锁上**（真机 2026-09-19 的第五处拦路虎）
#
# 现场：`✗ [linuxctl update] base 失败`，日志里只有一行
#   linuxctl[2168]: {LOCK_FD}: inaccessible or not found
#
# 根因：`acquire_lock` 用的是 bash 的**命名 fd** 语法 `exec {LOCK_FD}>"…"`；
# Android 的 /system/bin/sh（mksh）把它当字面命令执行，`flock` 拿到空串必然失败。
# 更要命的是 flock 在 mksh 里**恰好存在**（/system/bin/flock），所以永远走这一支、
# 下面 mkdir 型锁的兜底根本轮不到 —— `update` 连解包都进不去。
#
# 修法：判据从"有没有 flock"改成"**这个 shell 能不能真的锁上**"—— 当场探一次，
# 不成自动落到 shell 无关的 mkdir 型锁。
#
# 为什么断言"第二个进程必须等超时"：这是 bash 命名 fd 写法**做不到**的事（锁根本没建立），
# 也是唯一能证明锁真的生效的判据。30s 太久，这里把超时压到 1s。
# ---------------------------------------------------------------------------
head_ "互斥锁真的锁得上（fd 型不可用时自动降级到 mkdir 型）"
LH="$TMP/lock-probe/sunsetlinux"
mkdir -p "$LH/etc" "$LH/run"
# ★ 用**确定性**布景，不赌时序：手工造一个"别人正持锁"的状态（mkdir 型锁目录 + 一个活着的 pid），
#   然后断言第二个进程**必须等锁超时**。第一版赌"持锁者还在跑"（后台跑一次真命令再探锁文件），
#   实测太快、持锁者已经结束，第二个进程顺利拿锁退出 0 —— 那是测试不可靠，不是锁坏了。
#   fd 型那条路（bash/dash）同理由实现自己探测；真机是 mksh，走的就是这套 mkdir 型锁。
#
# 为什么要断言"第二个进程被挡住"：这是唯一能证明互斥**真的建立**的判据 ——
# bash 命名 fd 那个 bug（真机事故）的表现正是"锁根本没建立"，而它在开发机上完全看不见。
LOCKD="$LH/run/.linuxctl.lockd"
mkdir -p "$LOCKD"
printf '%s\n' "$$" >"$LOCKD/pid"          # 持锁者 = 本测试进程（活着）
lock_rc=0
SUNSETLINUX_LOCK_TIMEOUT=1 LINUX_HOME="$LH" "$SH_BIN" "$LINUXCTL" \
    status --refresh-sizes >/dev/null 2>&1 || lock_rc=$?
if [ "$lock_rc" -eq 3 ]; then
    ok "别人持锁时第二个进程会等锁并超时（退出码 3）—— 互斥真的生效"
else
    bad "别人持锁时第二个进程没有被挡住" "退出码 3" "退出码 $lock_rc（锁形同虚设）"
fi

# 持锁者消失后，陈旧锁必须能被自动认领（否则一次崩溃会把环境永久锁死）
rm -rf -- "$LOCKD"
after_rc=0
SUNSETLINUX_LOCK_TIMEOUT=2 LINUX_HOME="$LH" "$SH_BIN" "$LINUXCTL" \
    status --refresh-sizes >/dev/null 2>&1 || after_rc=$?
if [ "$after_rc" -ne 3 ]; then
    ok "持锁者退出后锁可再次获取（后续调用不再等锁）"
else
    bad "锁没有释放" "退出码 ≠ 3" "退出码 3（陈旧锁挡住了后续调用）"
fi

# 兜底（源码级）：不许再出现 bash 的命名 fd 写法 —— mksh 会把它当**字面命令**执行，
# 现场症状就是那句 `linuxctl[2168]: {LOCK_FD}: inaccessible or not found`。
# ⚠️ 判据必须**跳过注释行**：修法本身就写了注释解释这个坑，直接 grep 会把解释也算违例
#    （第一版就是这么红的）。
if grep -v '^[[:space:]]*#' "$LINUXCTL" 2>/dev/null | grep -q 'exec {[A-Za-z_][A-Za-z_0-9]*}>'; then
    bad "linuxctl.sh 里又出现 bash 命名 fd 写法" "没有 exec {VAR}>" "有（mksh 下会当字面命令执行）"
else
    ok "没有 bash 命名 fd 写法（exec {VAR}>）"
fi

printf '\n== 结果：%d 通过 / %d 失败 ==\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
