#!/system/bin/sh
# =============================================================================
# root 运行时自测（本机可在**无真 root**下运行）
#
# 重点覆盖一个真实踩过的 ship-blocker：
#   **EROFS 的超级块在偏移 1024，不在偏移 0。**
#   早期 layer_format() 只读偏移 0（那是 squashfs 的规矩），于是每个合法的
#   erofs 层都被判定为 "unknown" → update 拒收合法层、start 拒绝挂载。
#   症状极具误导性："层明明是好的却报格式不可识别"。
#
# 用法：  bash runtime/root/selftest.sh
# 环境：  SUNSETLINUX_FIXTURES 可覆盖夹具目录（默认 <repo>/testdata/fixtures）
# 退出码：0 全过；1 有失败
# =============================================================================
set -uo pipefail

SELF_PATH="$0"   # mksh 下 BASH_SOURCE 未定义 → 退回 $0

# 自测要在两种地方跑：CI/开发机（有 bash）与**真机**（只有 /system/bin/sh）。
# 下面所有 `-c` 子壳都用 $SH_BIN，不用裸 `bash`（Android 上没有 bash）。
SH_BIN="${SUNSETLINUX_SH:-}"
if [ -z "$SH_BIN" ] || [ ! -x "$SH_BIN" ]; then
    if command -v bash >/dev/null 2>&1; then SH_BIN="$(command -v bash)"
    elif [ -x /system/bin/sh ]; then SH_BIN=/system/bin/sh
    else SH_BIN=/bin/sh; fi
fi
export SH_BIN
SELF_DIR="$(cd -- "$(dirname -- "$SELF_PATH")" && pwd -P)"
REPO_DIR="$(cd "$SELF_DIR/../.." 2>/dev/null && pwd || printf '%s' "$SELF_DIR")"
# 夹具查找顺序：显式指定 → 脚本同目录/fixtures（**模块安装后自带，真机可用**）
#              → 仓库 testdata/fixtures（**CI 靠这条**；可跟踪、可重新生成）→ 本地 build/fixtures
FIXTURES=""
# 顺序：显式指定 → 脚本同目录（模块安装后自带 bin/fixtures/）→
#       **仓库内可跟踪的 testdata/fixtures**（CI 靠这条；build/ 是 gitignore 的）→ 本地 build/
for cand in "${SUNSETLINUX_FIXTURES:-}" "$SELF_DIR/fixtures" \
            "$REPO_DIR/testdata/fixtures" "$SELF_DIR/../testdata/fixtures" \
            "$REPO_DIR/build/fixtures" "$SELF_DIR/../build/fixtures"; do
    [ -n "$cand" ] && [ -d "$cand" ] && { FIXTURES="$cand"; break; }
done
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0; fail=0; skip=0
ok()    { pass=$((pass+1)); printf '  \033[32mok\033[0m   %s\n' "$*"; }
bad()   { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
skip_() { skip=$((skip+1)); printf '  \033[33mSKIP\033[0m %s\n' "$*"; }
head_() { printf '\n== %s ==\n' "$*"; }

have() { command -v "$1" >/dev/null 2>&1; }

# 从真实脚本里**抽取** layer_format / _magic_at 来测（而不是复制一份实现，
# 否则测的就不是发布的代码了；也不 source 整个脚本，那会执行它的主体）。
extract_fmt() { # extract_fmt <脚本路径> <输出文件>
    local src="$1" out="$2"
    {
        echo 'have() { command -v "$1" >/dev/null 2>&1; }'
        sed -n '/^_magic_at()/,/^}/p'  "$src"
        sed -n '/^layer_format()/,/^}/p' "$src"
    } > "$out"
}

head_ "夹具检查"
if [ -z "$FIXTURES" ]; then
    printf '  \033[33mSKIP\033[0m 未找到夹具目录，跳过全部断言（不是失败）\n'
    printf '       查找顺序：$SUNSETLINUX_FIXTURES → %s/fixtures → %s/testdata/fixtures → %s/build/fixtures\n' \
        "$SELF_DIR" "$REPO_DIR" "$REPO_DIR"
    printf '       模块安装后自带 bin/fixtures/；仓库里在 testdata/fixtures/（可用 testdata/fixtures/mkfixtures.sh 重新生成）。\n'
    exit 0
fi
for f in erofs-head.bin squashfs-head.bin zstd-head.bin; do
    [ -f "$FIXTURES/$f" ] || bad "缺少夹具 $FIXTURES/$f（目录存在但文件缺失 → 打包漏了）"
done
if [ "$fail" -gt 0 ]; then
    printf '\n夹具不全，无法继续。\n'; exit 1
fi
# 夹具本身的关键偏移必须正确，否则测的是错的基线
check_off() { # check_off <file> <offset> <期望hex>
    local got
    got="$(dd if="$1" bs=1 skip="$2" count=4 2>/dev/null | od -An -tx1 | tr -d ' \n')"
    [ "$got" = "$3" ] && ok "$(basename "$1") 偏移 $2 = $got" \
                      || bad "$(basename "$1") 偏移 $2 = $got，期望 $3"
}
check_off "$FIXTURES/erofs-head.bin"    1024 e2e1f5e0
check_off "$FIXTURES/squashfs-head.bin"    0 68737173
check_off "$FIXTURES/zstd-head.bin"        0 28b52ffd

head_ "layer_format 单元测试（直接测发布的实现）"
for src in "$SELF_DIR/start.sh" "$SELF_DIR/doctor.sh"; do
    [ -f "$src" ] || { bad "找不到 $src"; continue; }
    h="$TMP/fmt-$(basename "$src").sh"
    extract_fmt "$src" "$h"
    # 抽取是否成功
    if ! grep -q '^layer_format()' "$h"; then bad "$(basename "$src"): 抽取 layer_format 失败（函数被改名了？）"; continue; fi
    probe() { # probe <夹具> <期望>
        local got
        got="$("$SH_BIN" -c 'set -uo pipefail; . "$1"; layer_format "$2"' _ "$h" "$FIXTURES/$1" 2>/dev/null)"
        [ "$got" = "$2" ] && ok "$(basename "$src"): $(printf '%-18s' "$1") -> $got" \
                          || bad "$(basename "$src"): $1 -> $got，期望 $2"
    }
    probe erofs-head.bin    erofs
    probe squashfs-head.bin squashfs
    probe zstd-head.bin     unknown
done

head_ "端到端：linuxctl update 的格式判定"
LH="$TMP/linux"; mkdir -p "$LH"/{layers,etc,run,bin,cache,upper,work,rootfs,layers-mnt}

# ★ 宿主内核**没有 erofs** 时（例如 GitHub runner），linuxctl 会**正确地**拒绝合法 erofs
#   —— 那是环境性质，不是缺陷。但本组断言考的是"格式判定 + 按 layer-spec 命名落盘 +
#   state.json + find_layer 版本优先 + rollback"这些**我们自己的逻辑**，与宿主内核无关。
#   所以这里显式声明跳过那道内核预检（仅测试用；真机不要设这个变量）。
if grep -qw erofs /proc/filesystems 2>/dev/null; then
    ok "宿主内核支持 erofs，按真实行为测"
else
    export LINUXCTL_KERNEL_FS_OVERRIDE=erofs
    printf '  \033[33m注意\033[0m 宿主内核没有 erofs → 已设 LINUXCTL_KERNEL_FS_OVERRIDE=erofs\n'
    printf '       本组断言测的是我们自己的逻辑（格式判定/落盘/状态），内核能力留给真机验\n'
fi
# 合法 erofs 必须被**接受**（这里用 2KB 头当最小样本：update 只做 magic 判定）
if out="$(LINUX_HOME="$LH" "$SH_BIN" "$SELF_DIR/linuxctl.sh" update dsh "$FIXTURES/erofs-head.bin" 2>&1)"; then
    case "$out" in
        *'"ok":true'*'"format":"erofs"'*) ok "合法 erofs 被接受" ;;
        *) bad "合法 erofs 未被接受：$out" ;;
    esac
else
    bad "合法 erofs 被拒绝（这就是偏移 1024 那个 bug 的症状）：$(printf '%s' "$out" | tail -2)"
fi
# 压缩产物必须被**拒绝** —— 这是**正确**行为：解压是客户端（App）的职责，
# linuxctl 只接受可直接挂载的裸镜像。若这条变成"接受"，说明校验被削弱了。
if out="$(LINUX_HOME="$LH" "$SH_BIN" "$SELF_DIR/linuxctl.sh" update dsh "$FIXTURES/zstd-head.bin" 2>&1)"; then
    bad "压缩产物竟被接受（校验被削弱，这是错的）"
else
    case "$out" in
        *'格式不可识别'*) ok "压缩产物被正确拒绝（解压应由客户端完成）" ;;
        *) bad "拒绝原因不符预期：$(printf '%s' "$out" | tail -2)" ;;
    esac
fi

head_ "单元：dsh_jq_ok 必须返回**退出码**（踩过：返回 0 会被当成"可用"）"
# 这个 bug 的后果很隐蔽：jq 不存在时仍走 jq 分支，被 `|| true` 吞掉后返回空值，
# 导致 state.json 明明写了版本，status.dsh.version 却一直是 null。
# status_json.sh 的位置随布局而变：
#   仓库布局  runtime/root/selftest.sh → ../common/status_json.sh
#   模块布局  $LINUX_HOME/bin/selftest.sh → ./common/status_json.sh
# 两处都要找，否则真机（模块布局）上这一项会误报失败。
_sj=""
for cand in "$SELF_DIR/../common/status_json.sh" "$SELF_DIR/common/status_json.sh"; do
    [ -f "$cand" ] && { _sj="$cand"; break; }
done
for src in "$_sj"; do
    [ -n "$src" ] && [ -f "$src" ] || { bad "找不到 status_json.sh（已试 ../common 与 ./common）"; continue; }
    h="$TMP/status_json.sh"
    sed -n '/^dsh_jq_ok()/,/^}/p' "$src" > "$h"
    if ! grep -q '^dsh_jq_ok()' "$h"; then bad "抽取 dsh_jq_ok 失败（函数被改名了？）"; continue; fi
    # 用 DSH_ST_JQ_OK 缓存控制两种情形，验证退出码与缓存一致
    r="$("$SH_BIN" -c 'set -uo pipefail; . "$1"; DSH_ST_JQ_OK=0; if dsh_jq_ok; then echo WRONG; else echo RIGHT; fi' _ "$h")"
    [ "$r" = "RIGHT" ] && ok "缓存 0 时 dsh_jq_ok 判为不可用" || bad "缓存 0 时 dsh_jq_ok 竟判为可用"
    r="$("$SH_BIN" -c 'set -uo pipefail; . "$1"; DSH_ST_JQ_OK=1; if dsh_jq_ok; then echo RIGHT; else echo WRONG; fi' _ "$h")"
    [ "$r" = "RIGHT" ] && ok "缓存 1 时 dsh_jq_ok 判为可用" || bad "缓存 1 时 dsh_jq_ok 判为不可用"
done

head_ "集成：update --version 落盘 + state.json + find_layer 版本优先"
LH2="$TMP/linux2"; mkdir -p "$LH2"/{layers,etc,run,bin,cache,upper,work,rootfs,layers-mnt}
# 用夹具头当"层"，只验命名/版本/state 逻辑（格式判定已在上面的端到端验过）
if out="$(LINUX_HOME="$LH2" "$SH_BIN" "$SELF_DIR/linuxctl.sh" update dsh "$FIXTURES/erofs-head.bin" --version 1.0.0 2>&1)"; then
    case "$out" in
        *'"version":"1.0.0"'*) ok "update --version 在 JSON 里回显版本" ;;
        *) bad "update JSON 未回显版本：$out" ;;
    esac
else
    bad "update --version 失败：$(printf '%s' "$out" | tail -2)"
fi
[ -f "$LH2/layers/dsh-1.0.0.erofs" ] && ok "落盘为 layer-spec 规定的 dsh-1.0.0.erofs" \
                                      || bad "未按 layer-spec 命名落盘：$(ls "$LH2/layers" 2>/dev/null | tr '\n' ' ')"
if [ -f "$LH2/etc/state.json" ] && grep -q '"version": *"1.0.0"' "$LH2/etc/state.json"; then
    ok "state.json 写入了版本 1.0.0"
else
    bad "state.json 未写入版本：$(cat "$LH2/etc/state.json" 2>/dev/null | tr -d '\n')"
fi
# 第二个版本并存
if LINUX_HOME="$LH2" "$SH_BIN" "$SELF_DIR/linuxctl.sh" update dsh "$FIXTURES/erofs-head.bin" --version 2.0.0 >/dev/null 2>&1; then
    if [ -f "$LH2/layers/dsh-1.0.0.erofs" ] && [ -f "$LH2/layers/dsh-2.0.0.erofs" ]; then
        ok "同层多版本并存（旧文件未被删，回滚可用）"
    else
        bad "旧版本文件被删了：$(ls "$LH2/layers" | tr '\n' ' ')"
    fi
else
    bad "第二次 update（2.0.0）失败"
fi
# 关键：把 state.json 指回 1.0.0 后，find_layer 必须返回 1.0.0（不是最高的 2.0.0）
sed -i 's/"version": *"2.0.0"/"version": "1.0.0"/' "$LH2/etc/state.json" 2>/dev/null
sed -i 's/dsh-2.0.0.erofs/dsh-1.0.0.erofs/' "$LH2/etc/state.json" 2>/dev/null
fl="$TMP/find_layer.sh"
{
    echo 'LAYERS_DIR="'"$LH2"'/layers"'
    echo 'STATE_JSON="'"$LH2"'/etc/state.json"'
    echo 'log() { :; }'
    sed -n '/^find_layer()/,/^}/p' "$SELF_DIR/start.sh"
} > "$fl"
if ! grep -q '^find_layer()' "$fl"; then bad "抽取 find_layer 失败（函数被改名了？）"; else
    got="$("$SH_BIN" -c 'set -uo pipefail; . "$1"; find_layer dsh' _ "$fl" 2>/dev/null)"
    case "$got" in
        */dsh-1.0.0.erofs) ok "state.json 优先：find_layer 返回 1.0.0（而非最高的 2.0.0）" ;;
        *) bad "state.json 版本优先失效，find_layer 返回：$got" ;;
    esac
fi
# rollback 子命令必须存在且能把指向改回去
if LINUX_HOME="$LH2" "$SH_BIN" "$SELF_DIR/linuxctl.sh" update dsh "$FIXTURES/erofs-head.bin" --version 2.0.0 >/dev/null 2>&1; then
    if out="$(LINUX_HOME="$LH2" "$SH_BIN" "$SELF_DIR/linuxctl.sh" rollback dsh 2>&1)"; then
        case "$out" in
            *'"to":"1.0.0"'*) ok "rollback 把 dsh 指回 1.0.0" ;;
            *) bad "rollback 目标不对：$out" ;;
        esac
    else
        bad "rollback 失败：$(printf '%s' "$out" | tail -2)"
    fi
fi

# ---------------------------------------------------------------------------
# 层模式（loop / dir）：解析顺序 + 目录模式解包（幂等/重解/失败）
#
# dir 模式的动机：真机上 loop/erofs 这条链最容易出问题（toybox 选项、loop 数量、
# 挂载权限），而"把层解包成目录 + 目录 overlay"完全不碰 loop。这几条断言钉住它的契约：
# 解析优先级、幂等（版本没变不重解）、换层要重解、解包器缺失要明确失败。
# ---------------------------------------------------------------------------
head_ "层模式解析与目录模式解包"

# 抽取要测的函数（不能 source 整个 start.sh —— 那会执行它的主体）
lm="$TMP/layermode.sh"; : > "$lm"
for fn in resolve_layer_mode materialize_dirs; do
    sed -n "/^$fn()/,/^}/p" "$SELF_DIR/start.sh" >> "$lm"
        grep -q "^$fn()" "$lm" || bad "抽取失败：start.sh 里找不到 $fn()"
done
# 目录模式解包的调用封装（放进同一个文件里，source 一次即可用）
cat >> "$lm" <<'EOS'

lm_materialize() {
    (
        LINUX_HOME="$LMH"; LH="$LMH"; ETC_DIR="$LMH/etc"; RUN_DIR="$LMH/run"
        LAYER_MODE=dir; LAYER_NAMES="base runtime dsh"
        DIRS_DIR="$LMH/dirs"; DIRS_UPPER="$LMH/dirs-upper"; DIRS_WORK="$LMH/dirs-work"
        DAEMON_LOG="$LMH/run/linux.log"; EROFS_EXTRACT="$EXTRACT"; CALLS_FILE="$CALLS"
        log() { :; }; warn_soft() { :; }; die() { echo "DIE:$*" >&2; exit 3; }
        layer_format() { printf "erofs"; }
        find_layer() { for _f in "$LMH/layers/$1-"*.erofs; do [ -f "$_f" ] && { printf "%s" "$_f"; return; }; done; }
        export CALLS_FILE
        materialize_dirs
    )
}
EOS
# shellcheck disable=SC1090
. "$lm"

# 桩环境：临时 LINUX_HOME + 假层文件 + 假解包器（记录每次调用）
LMH="$TMP/lm-env"; mkdir -p "$LMH/layers" "$LMH/etc" "$LMH/run"
printf "{}\n" > "$LMH/etc/config.json"
for l in base runtime dsh; do : > "$LMH/layers/$l-1.0.erofs"; done
CALLS="$TMP/extract-calls"; : > "$CALLS"
EXTRACT="$TMP/fake-fsck"; cat > "$EXTRACT" <<'EOS'
#!/bin/sh
for a in "$@"; do case "$a" in --extract=*) d="${a#--extract=}";; esac; done
echo "$d" >> "$CALLS_FILE"
mkdir -p "$d/etc" "$d/usr/bin"
echo "ID=sunsetlinux" > "$d/etc/os-release"
exit 0
EOS
chmod +x "$EXTRACT"

lm_run() { # lm_run <mode-flag> <env-mode> <config-json>  → 打印 "mode|die消息"
    (
        LINUX_HOME="$LMH"; LH="$LMH"; ETC_DIR="$LMH/etc"; RUN_DIR="$LMH/run"
        CONFIG_JSON="$LMH/etc/config.json"; LAYER_NAMES="base runtime dsh"
        DIRS_DIR="$LMH/dirs"; DIRS_UPPER="$LMH/dirs-upper"; DIRS_WORK="$LMH/dirs-work"
        DAEMON_LOG="$LMH/run/linux.log"; CALLS_FILE="$CALLS"
        EROFS_EXTRACT="$EXTRACT"
        LAYER_MODE=""; LAYER_MODE_FLAG="$1"; SUNSETLINUX_LAYER_MODE="$2"
        printf "%s\n" "$3" > "$CONFIG_JSON"
        DIED=""
        log() { :; }; warn_soft() { :; }
        die() { echo "DIE:$*"; exit 3; }
        layer_format() { printf "erofs"; }
        find_layer() { for _f in "$LMH/layers/$1-"*.erofs; do [ -f "$_f" ] && { printf "%s" "$_f"; return; }; done; }
        export CALLS_FILE
        resolve_layer_mode || exit 7
        printf "%s|%s\n" "$LAYER_MODE" "$DIED"
    )
}

e="$(lm_run dir "" "{}")"
case "$e" in dir\|*) ok "命令行 --layer-mode dir 生效" ;; *) bad "flag 不生效：$e" ;; esac
e="$(lm_run "" dir "{}")"
case "$e" in dir\|*) ok "环境变量 SUNSETLINUX_LAYER_MODE=dir 生效" ;; *) bad "env 不生效：$e" ;; esac
e="$(lm_run "" "" '{"layer_mode":"dir"}')"
case "$e" in dir\|*) ok "config.json 的 layer_mode 生效" ;; *) bad "config 不生效：$e" ;; esac
e="$(lm_run "" "" "{}")"
case "$e" in loop\|*) ok "三处都没写 → 默认 loop（省磁盘）" ;; *) bad "默认值不对：$e" ;; esac
e="$(lm_run loop dir "{}")"
case "$e" in loop\|*) ok "命令行优先于环境变量" ;; *) bad "优先级不对：$e" ;; esac
e="$(lm_run dirr "" "{}")"
case "$e" in *"未知的层模式"*) ok "非法层模式明确报错" ;; *) bad "非法值没报错：$e" ;; esac

# --- 目录模式解包：三次运行的调用次数与戳 ---
: > "$CALLS"
cat >> "$lm" <<'EOS'

lm_materialize() {
    (
        LINUX_HOME="$LMH"; LH="$LMH"; ETC_DIR="$LMH/etc"; RUN_DIR="$LMH/run"
        LAYER_MODE=dir; LAYER_NAMES="base runtime dsh"
        DIRS_DIR="$LMH/dirs"; DIRS_UPPER="$LMH/dirs-upper"; DIRS_WORK="$LMH/dirs-work"
        DAEMON_LOG="$LMH/run/linux.log"; EROFS_EXTRACT="$EXTRACT"; CALLS_FILE="$CALLS"
        log() { :; }; warn_soft() { :; }; die() { echo "DIE:$*" >&2; exit 3; }
        layer_format() { printf "erofs"; }
        find_layer() { for _f in "$LMH/layers/$1-"*.erofs; do [ -f "$_f" ] && { printf "%s" "$_f"; return; }; done; }
        export CALLS_FILE
        materialize_dirs
    )
}
EOS
if lm_materialize 2>"$TMP/lm.err"; then ok "目录模式：首次解包成功" ; else bad "首次解包失败：$(tail -1 "$TMP/lm.err")" ; fi
n1="$(wc -l < "$CALLS" | tr -d ' ')"
[ "$n1" = "3" ] && ok "首次解包 3 层（调用 3 次）" || bad "首次解包次数不对：$n1"
[ -f "$LMH/dirs/base/etc/os-release" ] && ok "解出的目录内容就位" || bad "解出的目录里没有内容"
lm_materialize >/dev/null 2>&1 || true
n2="$(wc -l < "$CALLS" | tr -d ' ')"
[ "$n2" = "$n1" ] && ok "第二次：层没变 → 不重复解包（幂等）" || bad "重复解包了：$n1 → $n2"
# 换层（文件名/大小变化）→ 只重解那一层
cp "$LMH/layers/runtime-1.0.erofs" "$LMH/layers/runtime-1.1.erofs"; : > "$LMH/layers/runtime-1.1.erofs"
printf "12345" > "$LMH/layers/runtime-1.1.erofs"; rm -f "$LMH/layers/runtime-1.0.erofs"
lm_materialize >/dev/null 2>&1 || true
n3="$(wc -l < "$CALLS" | tr -d ' ')"
[ "$n3" = "$(( n2 + 1 ))" ] && ok "换了 runtime 层 → 只重解它（+1 次）" || bad "重解次数不对：$n2 → $n3"
# 解包器不存在 → 明确失败（不能静默跳过）
e="$(
    (
        LINUX_HOME="$LMH"; ETC_DIR="$LMH/etc"; RUN_DIR="$LMH/run"
        LAYER_MODE=dir; LAYER_NAMES="base runtime dsh"; DIRS_DIR="$LMH/dirs"
        DAEMON_LOG="$LMH/run/linux.log"; EROFS_EXTRACT="$TMP/definitely-missing"
        log() { :; }; warn_soft() { :; }; die() { echo "DIE:$*"; exit 3; }
        layer_format() { printf "erofs"; }
        find_layer() { printf "%s" "$LMH/layers/$1-1.0.erofs"; }
        materialize_dirs 2>&1 || true
    )
)"
case "$e" in *"需要 erofs 解包器"*) ok "解包器缺失 → 明确报错（不静默跳过）" ;; *) bad "缺解包器时行为不对：$e" ;; esac

# ---------------------------------------------------------------------------
# linuxctl start 的 --layer-mode：非法值要拒绝，dir 模式不该被 upper.img 预检拦住
# （docs/layer-mode.md 把 `linuxctl start --layer-mode dir` 写成了官方切法，必须真的能用）
# ---------------------------------------------------------------------------
head_ "linuxctl start --layer-mode"
LCE="$TMP/lc-env"; mkdir -p "$LCE/layers" "$LCE/etc" "$LCE/run"
cp "$FIXTURES/erofs-head.bin" "$LCE/layers/base-1.0.erofs" 2>/dev/null || : > "$LCE/layers/base-1.0.erofs"
printf "{\"schema\":1,\"layers\":{}}\n" > "$LCE/etc/state.json"
out="$(LINUX_HOME="$LCE" "$SH_BIN" "$SELF_DIR/linuxctl.sh" start --layer-mode dirr 2>/dev/null | tail -n1 || true)"
case "$out" in
    *"未知的层模式"*) ok "非法层模式被 linuxctl 拒绝（附原因）" ;;
    *) bad "非法层模式没被拒绝：$out" ;;
esac
out="$(LINUX_HOME="$LCE" "$SH_BIN" "$SELF_DIR/linuxctl.sh" start --layer-mode dir 2>/dev/null | tail -n1 || true)"
case "$out" in
    *"缺少可写层"*|*upper.img*) bad "dir 模式仍被 upper.img 预检拦住：$out" ;;
    *) ok "dir 模式不再要求 upper.img（预检按模式分叉）" ;;
esac

# ---------------------------------------------------------------------------
# 挂载冲突检查（§1d）必须存在且能给出结论
#
# 背景：KernelSU 的模块挂载由 metamodule 在启动时完成（把常规模块的 system/ overlay
# 到 Android 系统路径）。我们的 chroot 自己挂 loop/erofs/overlay，用户最担心的就是
# "两套挂载会不会打架"。doctor §1d 就是把这个问题的证据摆出来：私有命名空间、
# 目标路径不相交、只占空闲 loop、模块不含 system/。这里断言它确实在、且 JSON 可解析。
# ---------------------------------------------------------------------------
head_ "doctor 的挂载冲突检查（§1d）"
DH="$TMP/doctor-env"
mkdir -p "$DH/run" "$DH/etc"
install -d "$DH/layers" 2>/dev/null || mkdir -p "$DH/layers"
dout="$(LINUX_HOME="$DH" MODDIR="$SELF_DIR/../.." "$SH_BIN" "$SELF_DIR/doctor.sh" 2>/dev/null | tail -n1 || true)"
case "$dout" in
    *'"id":"mount_conflict"'*) ok "doctor 给出了 mount_conflict 结论（挂载冲突可判定）" ;;
    *'"schema":1'*) bad "doctor 的 JSON 里没有 mount_conflict（§1d 没接进 findings？）" ;;
    *) bad "doctor 没有输出可解析的 JSON：$(printf '%s' "$dout" | head -c 120)" ;;
esac

# ---------------------------------------------------------------------------
# 真机事故回归（2026-09-16，第二起）：**探测把 start.sh 自己带走了**
#
# `run_probes` 里的探测命令是**设计成失败**的（拿不存在的源去 mount，只看它是不是
# 把参数判成语法错误）。而 `set -e` + `out="$(mount …)"; rc=$?` 这种写法会让那次
# "预期失败"直接终止整个脚本：真机上 cmdprobe 是 0 字节、linux.log 停在"开始探测…"，
# 用户看到的是「start.sh 失败 / 未挂载」，而真正的挂载步骤一行都没跑到。
#
# 这里断言：--probe-only 必须跑完并把 probed= 落盘（本机没有 /system/bin/mount 时，
# 探测会被 [ -x ] 跳过，但 probed= 仍必须写下来 —— 那正是"跑完了"的证据）。
# ---------------------------------------------------------------------------
head_ "start.sh 的 mount 探测必须能跑完（set -e 不能把脚本带走）"
PE="$TMP/probe-env"
mkdir -p "$PE"
if LINUX_HOME="$PE" "$SH_BIN" "$SELF_DIR/start.sh" --probe-only >"$TMP/probe.out" 2>&1; then
    if grep -q '^probed=' "$PE/run/cmdprobe" 2>/dev/null; then
        ok "探测跑完并落盘：$(tr '\n' ' ' < "$PE/run/cmdprobe" | cut -c1-90)"
    else
        bad "探测没落盘（cmdprobe 是空的）：真机上就是"start.sh 失败、层永远挂不上""
    fi
else
    bad "start.sh --probe-only 非 0 退出（见 $TMP/probe.out）：$(tail -1 "$TMP/probe.out" 2>/dev/null)"
fi

# ---------------------------------------------------------------------------
# 真机事故回归（2026-09-16）：层都在、却永远挂不上
#
# 两个原因：模块 service.sh 用裸 `sh "$CTL"` 发起（模块 PATH 里可能是 busybox ash，
# 它在 `${BASH_SOURCE[0]}` 上直接 `syntax error: bad substitution`），
# 以及 linuxctl 用 `bash start.sh` 去挂载（**Android 上没有 bash**）。
# 这里断言"start 走 $SH_BIN、且不依赖 bash"这条性质仍然成立：
#   · 用一个**干净的 PATH**（剥掉 bash 所在目录）启动 start，必须给出业务错误
#     （缺少层文件），而不是 `bad substitution` / `bash: not found`。
# ---------------------------------------------------------------------------
LH3="$TMP/no-bash"
mkdir -p "$LH3/run"
nobash_dir="$TMP/nobash-bin"
mkdir -p "$nobash_dir"
# 把除了 bash 之外要用的命令软链过去（dirname/sed/grep/tr/stat/mount/df/awk 等）
for tool in sed grep tr stat mount umount df awk cat mkdir rm ls ln cp mv chmod id date sleep kill ps sh mksh uname find sort head tail cut wc printf dirname basename expr touch; do
    p="$(command -v "$tool" 2>/dev/null || true)"
    if [ -n "$p" ] && [ ! -e "$nobash_dir/$tool" ]; then
        ln -sf "$p" "$nobash_dir/$tool" 2>/dev/null || true
    fi
done
out="$(PATH="$nobash_dir" LINUX_HOME="$LH3" "$SH_BIN" "$SELF_DIR/linuxctl.sh" start 2>&1 || true)"
case "$out" in
    *"bad substitution"*) bad "start 在无 bash 的 PATH 下仍死在 bad substitution：$(printf '%s' "$out" | tail -1)" ;;
    *"bash: not found"*|*"bash: inaccessible"*) bad "start 仍在调用 bash（Android 上没有 bash）：$(printf '%s' "$out" | tail -1)" ;;
    *"缺少层文件"*) ok "start 不依赖 bash：无 bash 的 PATH 下给出业务错误（缺少层文件）" ;;
    *) bad "start 在无 bash 的 PATH 下没有给出预期错误：$(printf '%s' "$out" | tail -2)" ;;
esac

# ---------------------------------------------------------------------------
# 真机事故回归（2026-09-16，第三起）：doctor 在**完全能跑**的环境里报 4 项 fail
#
# 真机输出里 4 项 fail 有两项是假警报：
#   · `CONFIG_SQUASHFS 未启用` / `内核不支持 squashfs` —— 本项目只用 erofs 层，
#     内核没有 squashfs 根本不是问题（erofs 是 Android 原生格式，§2 会实测）；
#   · `dsh 层内没有 /root/.dsh/profiles/**` —— 检查实现拿 `dump.erofs --ls --path=/`
#     的输出 grep 深层路径，而那个列表**不递归**，于是永远匹配不上（层是好的也报 fail）。
# 这里断言：squashfs 这条**永远不能是 fail**；profile/pnpm 必须"对着路径问"。
# ---------------------------------------------------------------------------
head_ "doctor 的误报回归（squashfs / 层内路径）"
DE="$TMP/doctor-fix-env"
mkdir -p "$DE/run" "$DE/etc" "$DE/layers"
# 用 SUNSETLINUX_KCONFIG_FILE 喂一份**明确没有 squashfs** 的内核配置：
# 真机 /proc/config.gz 可读、CI 容器里往往不可读，没有这个接缝这条闸门就只会在真机上才有效。
KCFG="$TMP/fake-kconfig"
{
    echo '# CONFIG_SQUASHFS is not set'
    echo 'CONFIG_NAMESPACES=y'
    echo 'CONFIG_UTS_NS=y'
    echo 'CONFIG_NET_NS=y'
    echo 'CONFIG_OVERLAY_FS=y'
    echo 'CONFIG_EXT4_FS=y'
    echo 'CONFIG_TMPFS=y'
} > "$KCFG"
djson="$(SUNSETLINUX_KCONFIG_FILE="$KCFG" LINUX_HOME="$DE" "$SH_BIN" "$SELF_DIR/doctor.sh" 2>/dev/null | tail -n1 || true)"
case "$djson" in
    *'"level":"fail","id":"config_SQUASHFS"'*) bad "squashfs 未启用仍被报成 fail（erofs 可用时它只是可选格式）" ;;
    *'"level":"fail","id":"kernel_squashfs"'*) bad "内核不支持 squashfs 仍被报成 fail（应当只是 info/ok）" ;;
    *'"id":"config_SQUASHFS"'*) ok "内核没开 squashfs 时不再报 fail（判定走的是内核配置那条路）" ;;
    *'"schema":1'*) bad "内核配置接缝没生效：JSON 里没有 config_SQUASHFS 结论" ;;
    *) bad "doctor 没有输出可解析的 JSON：$(printf '%s' "$djson" | head -c 120)" ;;
esac

# 层内路径检查：造一个假 erofs 层 + 假 dump.erofs，让目标路径"存在"。
# 只改判据的数据来源，不碰真实层 —— 这样两种回答（在/不在）都要能被区分出来。
FB="$TMP/fakebin"; mkdir -p "$FB"
printf '%s\n' '#!/bin/sh' \
    'p=""; for a in "$@"; do case "$a" in --path=*) p="${a#--path=}" ;; esac; done' \
    'case "$p" in /root/.dsh/profiles/web/package.json|/opt/node/bin/pnpm) echo "Path : $p"; echo "Size: 1  On-disk size: 1  regular file" ;; *) echo "<E> erofs: read inode failed @ $p" ;; esac' \
    > "$FB/dump.erofs"
chmod +x "$FB/dump.erofs"
LD="$TMP/doctor-layer-env"
mkdir -p "$LD/run" "$LD/etc" "$LD/layers"
dd if=/dev/zero of="$LD/layers/dsh-9.9.9.erofs" bs=512 count=4 2>/dev/null
# ⚠️ 这里**不能**用 `awk 'BEGIN{printf "%c", 226}'`：gawk 在 UTF-8 locale 下会把 226
# 当成码位 U+00E2 编成**两个字节**（0xC3 0xA2），写出来的 erofs magic 是错的 →
# 假层被判成 unknown → 本组断言在 CI（gawk + C.UTF-8）上红，而本地（mawk + POSIX）绿。
# `printf '%b' '\342...'` 在 bash / mksh / dash 下都按八进制写出**单字节**。
printf '%b' '\342\341\365\340' \
    | dd of="$LD/layers/dsh-9.9.9.erofs" bs=1 seek=1024 conv=notrunc 2>/dev/null
# runtime 层也要造一份：pnpm 的检查是查 runtime 层的 /opt/node/bin/pnpm
cp "$LD/layers/dsh-9.9.9.erofs" "$LD/layers/runtime-9.9.9.erofs"
printf '{"schema":1,"layers":{"dsh":{"version":"9.9.9"},"runtime":{"version":"9.9.9"}}}\n' > "$LD/etc/state.json"
djson2="$(PATH="$FB:$PATH" LINUX_HOME="$LD" "$SH_BIN" "$SELF_DIR/doctor.sh" 2>/dev/null | tail -n1 || true)"
# 失败时把"我们看到的层长什么样"一起带进断言消息：CI 的步骤日志要仓库权限才看得到，
# 而 `::error::` 注释（tools/ci-shell-gate.sh 会生成）只带 FAIL 那一行 —— 所以消息本身
# 必须自足（magic 是多少、用哪个 dump.erofs、f 层解析成了什么）。
_ldiag="magic=$(dd if="$LD/layers/dsh-9.9.9.erofs" bs=1 skip=1024 count=4 2>/dev/null | od -An -tx1 | tr -d ' \n')"
_ldiag="$_ldiag size=$(wc -c < "$LD/layers/dsh-9.9.9.erofs" 2>/dev/null | tr -d ' ')"
_ldiag="$_ldiag dump=$(PATH="$FB:$PATH" command -v dump.erofs 2>/dev/null)"
_ldiag="$_ldiag profile=$(printf '%s' "$djson2" | grep -o '"id":"profile"[^}]*}' | head -1)"
_ldiag="$_ldiag pnpm=$(printf '%s' "$djson2" | grep -o '"id":"pnpm"[^}]*}' | head -1)"
case "$djson2" in
    *'"id":"profile","detail":"in-layer"'*) ok "层内有 profile 时给出 in-layer（不再被不递归的列表骗成 fail）" ;;
    *'"id":"profile","detail":"layer-missing-profile"'*) bad "假阴性未修：层里明明有 profile 却报 layer-missing-profile（$_ldiag）" ;;
    *) bad "profile 检查没有给出可判定结论（$_ldiag）" ;;
esac
case "$djson2" in
    *'"id":"pnpm","detail":"in-layer"'*|*'"id":"pnpm","detail":"found"'*) ok "runtime 层内 pnpm 可判定（in-layer / found）" ;;
    *'"id":"pnpm","detail":"missing"'*) bad "假阴性未修：runtime 层里明明有 pnpm 却报 missing（$_ldiag）" ;;
    *) bad "pnpm 检查没有给出可判定结论（$_ldiag）" ;;
esac
# ---------------------------------------------------------------------------
# 真机事故回归（2026-09-17，第四起）：doctor 把"只读挂不上"当成 fail，而读写其实是好的
#
# 真机 doctor 里同时出现：
#     [fail] upper.img 挂不上（可能不是 ext4，或 loop 设备不可用，或已坏）
#     [ok]   upper.img 可**读写**挂载（ext4, loop, rw）—— 与 start.sh 的第一步一致
# 在用户手机上，`mount -o loop,ro` 失败、`mount -o loop,rw,noatime` 成功 —— 而真正决定
# "环境能不能起来"的只有后者（start.sh 走的就是它）。旧写法把只读探针也做成一条 finding，
# 于是报告自相矛盾，还把整份自检判成"未通过"。
#
# 这里用一个"只对 -o loop,ro 报错"的假 mount 复现那个环境，断言：
#   · 不许出现 upper_mountable 的 fail；
#   · 读写探针必须给出 ok（说明探针真的跑了，不是被跳过而"碰巧不报错"）。
# ---------------------------------------------------------------------------
head_ "KB→字节换算不许用 32 位算术（footprint 报告目录大小）"
# 真机同款根因（2026-09-17）：mksh 的 $(( )) 是 32 位有符号，`k * 1024` 一旦超过 2 GiB
# 就环绕成负数 —— 8 GiB 的 upper.img、上 GB 的 rootfs 目录都会显示成怪值/负数。
# 这里用桩 du 喂一个"3000000 KB"（= 3,072,000,000 字节 > 2^31），断言换算结果正确。
FBD="$TMP/fakebin-du"; mkdir -p "$FBD"
printf '%s\n' '#!/bin/sh' 'echo 3000000' > "$FBD/du"
chmod +x "$FBD/du"
mkdir -p "$TMP/fp-dir"
_fp_src="$(sed -n '/^_fp_size()/,/^}/p' "$SELF_DIR/linuxctl.sh")"
_fp_bytes="$(PATH="$FBD:$PATH" "$SH_BIN" -c "${_fp_src}
_fp_size '$TMP/fp-dir'" 2>/dev/null || true)"
case "$_fp_bytes" in
    3072000000) ok "3000000 KB → 3072000000 字节（32 位算术会得负数）" ;;
    *) bad "KB→字节换算错了：得到 '$_fp_bytes'，期望 3072000000" ;;
esac

head_ "doctor §3 的假警报回归（只读挂不上、读写正常）"
UL="$TMP/doctor-upper-env"
mkdir -p "$UL/run" "$UL/etc" "$UL/layers"
# 假镜像：**8 GiB 稀疏**（真机 upper.img 就是这个尺寸）——顺带回归"表观 GiB"那个 32 位溢出：
# 旧写法 `$(( usize / 1024 / 1024 / 1024 ))` 在 mksh 下把 8 GiB 算成 **0 GiB**（真机实测）。
if ! truncate -s 8G "$UL/upper.img" 2>/dev/null; then
    dd if=/dev/zero of="$UL/upper.img" bs=1024 count=4 2>/dev/null
fi
UB="$TMP/fakebin-upper"; mkdir -p "$UB"
# 桩：只对 `-o loop,ro` 报错（真机就是"只读挂不上、读写正常"）
printf '%s\n' '#!/bin/sh' \
    'case "$*" in *loop,ro*) echo "mount: Invalid argument" >&2; exit 1 ;; esac' \
    'exit 0' > "$UB/mount"
printf '%s\n' '#!/bin/sh' 'exit 0' > "$UB/umount"
chmod +x "$UB/mount" "$UB/umount"
# 接缝：doctor 默认用 /system/bin/mount（设备侧权威），容器里没有 → 用桩；
# SUNSETLINUX_LOOP_DEV_OK=1 再强制走一遍探针（CI 没有 loop 设备）
uall="$(SUNSETLINUX_MOUNT="$UB/mount" SUNSETLINUX_UMOUNT="$UB/umount" SUNSETLINUX_LOOP_DEV_OK=1 \
        LINUX_HOME="$UL" "$SH_BIN" "$SELF_DIR/doctor.sh" 2>/dev/null || true)"
ujson="$(printf '%s\n' "$uall" | tail -n1)"
case "$uall" in
    *"表观 8.0 GiB"*) ok "表观大小用 awk 算（mksh 的 32 位算术会把 8 GiB 算成 0 GiB）" ;;
    *"表观 0 GiB"*)   bad "表观大小被 32 位算术算成 0 GiB（真机同款；别用 shell 算术除字节数）" ;;
    *) printf '  [skip] 表观 GiB 断言跳过（这个环境建不出 8 GiB 稀疏文件）\n' ;;
esac
case "$ujson" in
    *'"level":"fail","id":"upper_mountable"'*)
        bad "只读探针的失败被当成了 fail（真机第四起假警报）：$(printf '%s' "$ujson" | grep -o '"id":"upper_mountable"[^}]*}' | head -1)" ;;
    *'"level":"ok","id":"upper_mountable_rw"'*)
        ok "只读挂不上、读写正常时不再报 fail（§3 只认与 start.sh 同口径的读写探针）" ;;
    *'"schema":1'*)
        bad "doctor 没有给出 upper_mountable_rw 结论（探针被跳过了？接缝没生效）" ;;
    *) bad "doctor 没有输出可解析的 JSON：$(printf '%s' "$ujson" | head -c 120)" ;;
esac
# 反例（同一组桩的另一个方向）：**读写也挂不上**时必须仍然 fail —— 不能为了"不误报"把检查做成永远通过
printf '%s\n' '#!/bin/sh' 'echo "mount: I/O error" >&2; exit 1' > "$UB/mount"
chmod +x "$UB/mount"
ujson2="$(SUNSETLINUX_MOUNT="$UB/mount" SUNSETLINUX_UMOUNT="$UB/umount" SUNSETLINUX_LOOP_DEV_OK=1 \
          LINUX_HOME="$UL" "$SH_BIN" "$SELF_DIR/doctor.sh" 2>/dev/null | tail -n1 || true)"
case "$ujson2" in
    *'"level":"fail","id":"upper_mountable_rw"'*) ok "读写真的挂不上时仍报 fail（检查没被"修"成永远通过）" ;;
    *) bad "反例不对：读写挂不上时应当 fail，实际：$(printf '%s' "$ujson2" | grep -o '"id":"upper_mountable[^}]*}' | head -1)" ;;
esac

# 反例：路径真的不在时，必须仍然是 fail（不能为了"不误报"把检查做成永远通过）
printf '%s\n' '#!/bin/sh' 'echo "<E> erofs: read inode failed @ x"' > "$FB/dump.erofs"
chmod +x "$FB/dump.erofs"
djson3="$(PATH="$FB:$PATH" LINUX_HOME="$LD" "$SH_BIN" "$SELF_DIR/doctor.sh" 2>/dev/null | tail -n1 || true)"
case "$djson3" in
    *'"id":"profile","detail":"layer-missing-profile"'*) ok "层内确实没有 profile 时仍报 fail（检查没有被"修"成永远通过）" ;;
    *) bad "反例不对：路径不在时应当 fail，实际（$_ldiag）：$(printf '%s' "$djson3" | head -c 120)" ;;
esac

# ---------------------------------------------------------------------------
# 真机事故回归（2026-09-16，第四起）：可写层 ext4 读写挂载失败（`I/O error`）
#
# 真机上 `mount -t ext4 -o loop,rw,noatime upper.img` 只回一句 I/O error，
# 而只读挂载与 e2fsck 都正常 → 环境永远起不来。修法是"逐级自愈"：
#   ① 清掉指向我们镜像的残留 loop  ② e2fsck -p  ③ 显式 losetup + mount 两步走
# 这里用假的 mount/losetup 驱动（不碰真设备），断言：
#   · toybox 的 `-o loop` 路径失败后，脚本仍能靠显式 losetup 挂上（返回 0）；
#   · 第一次失败时**必须**先清残留 loop 并跑过 e2fsck -p（自愈顺序不能省）。
# ---------------------------------------------------------------------------
head_ "可写层挂载的自愈路径（ext4 rw 失败不带走 start）"
UE="$TMP/upper-env"
mkdir -p "$UE/run" "$UE/rootfs" "$UE/rootfs/upper" "$UE/upper" "$FB" "$UE/bin"
: > "$UE/upper.img"
: > "$UE/calls"
printf '%s\n' '#!/bin/sh' \
    'echo "mount $*" >> "$CALLS_FILE"' \
    'case "$*" in *"/dev/block/loop"*) exit 0 ;; esac' \
    'exit 1' > "$UE/bin/mount"
printf '%s\n' '#!/bin/sh' \
    'echo "losetup $*" >> "$CALLS_FILE"' \
    'case "$1" in -a) echo "/dev/block/loop99: [65103]:2924912 ($UPPER_IMG)" ;; -f) echo "/dev/block/loop99" ;; -d) exit 0 ;; esac' \
    'exit 0' > "$UE/bin/losetup"
printf '%s\n' '#!/bin/sh' 'echo "e2fsck $*" >> "$CALLS_FILE"' 'exit 0' > "$UE/bin/e2fsck"
chmod +x "$UE/bin/mount" "$UE/bin/losetup" "$UE/bin/e2fsck"
uh="$TMP/upper-harness.sh"
{
    echo 'have() { command -v "$1" >/dev/null 2>&1; }'
    echo 'record_mount() { echo "record_mount $*" >> "$CALLS_FILE"; }'
    sed -n '/^kernel_hint()/,/^}/p'       "$SELF_DIR/start.sh"
    sed -n '/^kernel_hint_log()/,/^}/p'   "$SELF_DIR/start.sh"
    sed -n '/^cleanup_stale_loops()/,/^}/p' "$SELF_DIR/start.sh"
    sed -n '/^e2fsck_preen()/,/^}/p'      "$SELF_DIR/start.sh"
    sed -n '/^mount_upper_rw()/,/^}/p'    "$SELF_DIR/start.sh"
} > "$uh"
for fn in kernel_hint cleanup_stale_loops e2fsck_preen mount_upper_rw; do
    grep -q "^$fn()" "$uh" || bad "抽取 $fn 失败（函数被改名了？）"
done
# 给 e2fsck 一个**覆盖点**（SUNSETLINUX_E2FSCK）再跑：真机上 /system/bin/e2fsck 真实存在，
# 而绝对路径候选排在 PATH 前面 → 测到的会是真二进制（在 0 字节 upper.img 上必然 rc=8），
# 桩一次都调不到 ⇒ "自愈顺序"这条断言在设备上假红、在 CI 上却绿（2026-09-17 实测到）。
uout="$(
    PATH="$UE/bin:$PATH" \
    SUNSETLINUX_E2FSCK="$UE/bin/e2fsck" \
    MOUNT="$UE/bin/mount" UMOUNT="$UE/bin/mount" \
    LH="$UE" LINUX_HOME="$UE" UPPER_DIR="$UE/upper" ROOTFS_DIR="$UE/rootfs" RUN_DIR="$UE/run" \
    DAEMON_LOG="$UE/run/start.log" UPPER_IMG="$UE/upper.img" LAYERS_DIR="$UE/layers" \
    CALLS_FILE="$UE/calls" \
    "$SH_BIN" -c 'set -uo pipefail; . "$1"; log() { :; }; warn_soft() { :; }; mount_upper_rw && echo "RESULT=ok" || echo "RESULT=fail"' _ "$uh" 2>&1 || true
)"
case "$uout" in
    *"RESULT=ok"*) ok "toybox 的 -o loop 失败后，显式 losetup 路径把 upper 挂上了（自愈生效）" ;;
    *) bad "自愈没生效（upper 仍然挂不上）：$(printf '%s' "$uout" | tail -2)" ;;
esac
calls="$(cat "$UE/calls" 2>/dev/null || true)"
case "$calls" in
    *"losetup -a"*) : ;;
    *) bad "失败后没有先清残留 loop（cleanup_stale_loops 没跑）" ;;
esac
case "$calls" in
    *"e2fsck -p"*) ok "失败后先清了残留 loop，并跑过 e2fsck -p（自愈顺序正确）" ;;
    *) bad "失败后没有跑 e2fsck -p（needs_recovery 这类问题修不掉）" ;;
esac
# 挂载点回归（2026-09-17 核对）：docs/architecture.md §目录结构/§步骤 3 与 stop.sh、
# linuxctl.sh 都按 `$LINUX_HOME/upper` 找可写层；曾经 start.sh 挂到 $ROOTFS_DIR/upper，
# 于是 overlay 的 upperdir（$UPPER_DIR/upper）看不到 ext4，8 GiB 的 upper.img 白挂、
# 还被随后挂在 $ROOTFS_DIR 的 overlay 遮住。
case "$calls" in
    *"$UE/upper"*) ok "可写层挂在 \$LINUX_HOME/upper（与 architecture.md / stop.sh / linuxctl 一致）" ;;
    *) bad "可写层没挂在 \$LINUX_HOME/upper：$(printf '%s' "$calls" | tr '\n' '|' | cut -c1-120)" ;;
esac
case "$calls" in
    *"$UE/rootfs/upper"*) bad "可写层仍挂在 \$ROOTFS_DIR/upper（会被 overlay 遮住，upper.img 白挂）" ;;
    *) ok "没有挂到 \$ROOTFS_DIR/upper（不会被 overlay 遮住）" ;;
esac

# ---------------------------------------------------------------------------
# toybox 能力探测不许谎报（2026-09-17）
#   真机上 cmdprobe 记的是 `unshare_propagation=yes` + `mount_make_rslave=long`，
#   而 toybox 实际打印的是
#       unshare: Unknown option 'propagation' (see "unshare --help")
#       mount:   bad /etc/fstab: No such file or directory
#   旧判定表认不出这两句 → 谎报 → start.sh 每次先试注定失败的语法。
# 这里用两个假命令驱动真函数（不是复制实现）：一个只会打 toybox 的原话，
# 一个打"语法没问题、只是挂载失败"的话 —— 后者绝不能被评为不支持。
# ---------------------------------------------------------------------------
head_ "toybox 探测不许谎报（真实报错文本）"
PB="$TMP/probe-bin"
mkdir -p "$PB" "$TMP/probe-run"
printf '%s\n' '#!/bin/sh' 'echo "unshare: Unknown option (see \"unshare --help\")" >&2' 'exit 1' > "$PB/unshare"
printf '%s\n' '#!/bin/sh' 'echo "mount: bad /etc/fstab: No such file or directory" >&2' 'exit 1' > "$PB/mount-unknown"
printf '%s\n' '#!/bin/sh' 'echo "mount: '\''/nonexistent-sunsetlinux-probe'\''->'\''/tmp/x'\'': No such file or directory" >&2' 'exit 1' > "$PB/mount-ok"
chmod +x "$PB/unshare" "$PB/mount-unknown" "$PB/mount-ok"
ph="$TMP/probe-harness.sh"
{
    echo 'have() { command -v "$1" >/dev/null 2>&1; }'
    sed -n '/^probe_get()/,/^}/p'       "$SELF_DIR/start.sh"
    sed -n '/^probe_set()/,/^}/p'       "$SELF_DIR/start.sh"
    sed -n '/^probe_says_unsupported()/,/^}/p' "$SELF_DIR/start.sh"
    sed -n '/^probe_mount_syntax()/,/^}/p'     "$SELF_DIR/start.sh"
    sed -n '/^probe_unshare_syntax()/,/^}/p'   "$SELF_DIR/start.sh"
} > "$ph"
for fn in probe_set probe_get probe_says_unsupported probe_mount_syntax probe_unshare_syntax; do
    grep -q "^$fn()" "$ph" || bad "抽取 $fn 失败（函数被改名了？）"
done
pout="$(
    RUN_DIR="$TMP/probe-run" PROBE_FILE="$TMP/probe-run/cmdprobe" \
    "$SH_BIN" -c '. "$1"; probe_unshare_syntax "$2"; probe_mount_syntax "$3"; cat "$PROBE_FILE"' \
        _ "$ph" "$PB/unshare" "$PB/mount-unknown" 2>&1 || true
)"
case "$pout" in
    *"unshare_propagation=no"*) ok "toybox 的 Unknown option 被判成不支持（不再谎报 propagation=yes）" ;;
    *) bad "Unknown option 仍被判成支持：$(printf '%s' "$pout" | tr '\n' ' ' | cut -c1-120)" ;;
esac
case "$pout" in
    *"mount_make_rslave=short"*) ok "toybox 的 bad /etc/fstab 被判成不支持（不再谎报 make_rslave=long）" ;;
    *) bad "bad /etc/fstab 仍被判成支持：$(printf '%s' "$pout" | tr '\n' ' ' | cut -c1-120)" ;;
esac
pout2="$(
    RUN_DIR="$TMP/probe-run" PROBE_FILE="$TMP/probe-run/cmdprobe2" \
    "$SH_BIN" -c '. "$1"; probe_mount_syntax "$2"; cat "$PROBE_FILE"' \
        _ "$ph" "$PB/mount-ok" 2>&1 || true
)"
case "$pout2" in
    *"mount_rbind=long"*) ok "语法没问题、只是挂载失败时仍判成支持（新判定没有过度匹配）" ;;
    *) bad "把'挂载失败'误判成'语法不支持'了：$(printf '%s' "$pout2" | tr '\n' ' ' | cut -c1-120)" ;;
esac

# ---------------------------------------------------------------------------
# stop.sh 读登记表：必须跳过**绝对路径**项与 overlay（2026-09-17）
#   可写层现在登记为绝对路径（挂在 $LINUX_HOME/upper）。stop.sh 的 rootfs 树卸载按
#   `$ROOTFS_DIR/$rel` 拼路径 —— 不跳过就会去卸
#   `/data/sunsetlinux//data/sunsetlinux/upper` 这种怪路径（可写层另有 §5 专卸）。
# ---------------------------------------------------------------------------
head_ "stop.sh 读登记表：跳过绝对路径与 overlay"
SB="$TMP/stop-env"; mkdir -p "$SB/run" "$SB/rootfs"
printf '%s\n' "layers-mnt/base" "overlay" "/data/sunsetlinux/upper" > "$SB/run/mounts"
sh_h="$TMP/stop-harness.sh"
{
    echo 'log() { :; }'
    echo 'do_umount() { echo "UMOUNT $1" >> "$CALLS_FILE"; return 0; }'
    # 注意用 `^}$`（整行的 }）而不是 `^}`：unmount_rootfs_tree 里有跨行拼接
    # `${order:+<换行>}${FALLBACK_ORDER}`，那行的开头也是 `}`。
    sed -n '/^unmount_rootfs_tree()/,/^}$/p' "$SELF_DIR/stop.sh"
} > "$sh_h"
grep -q '^unmount_rootfs_tree()' "$sh_h" || bad "抽取 unmount_rootfs_tree 失败（函数被改名了？）"
SOUT="$(CALLS_FILE="$SB/calls" ROOTFS_DIR="$SB/rootfs" MOUNTS_FILE="$SB/run/mounts" \
        FALLBACK_ORDER="proc" "$SH_BIN" -c '. "$1"; unmount_rootfs_tree' _ "$sh_h" 2>&1 || true)"
scalls="$(cat "$SB/calls" 2>/dev/null || true)"
case "$scalls" in
    *"UMOUNT $SB/rootfs/layers-mnt/base"*) ok "rootfs 内的登记项照常被卸载（layers-mnt/base）" ;;
    *) bad "rootfs 内的登记项没被卸载：$(printf '%s' "$scalls" | tr '\n' '|' | cut -c1-120)（$SOUT）" ;;
esac
case "$scalls" in
    *"$SB/rootfs//data"*) bad "绝对路径项被按 \$ROOTFS_DIR/\$rel 拼成怪路径：$(printf '%s' "$scalls" | tr '\n' '|' | cut -c1-120)" ;;
    *) ok "绝对路径项（可写层）没被拼成怪路径" ;;
esac
case "$scalls" in
    *"UMOUNT $SB/rootfs/overlay"*) bad "overlay 被 §2 重复卸载（应留给我 §3）" ;;
    *) ok "overlay 没被 §2 重复卸载" ;;
esac

# ---------------------------------------------------------------------------
# overlay 能不能用这个文件系统（overlay_fs_usable，2026-09-17）
#   真机实测：overlayfs 在挂载时就拒绝 f2fs（ovl_dentry_weird 命中 DCACHE_OP_HASH/COMPARE）：
#       overlayfs: filesystem on '/data/sunsetlinux/dirs-upper' not supported → EINVAL
#   于是 dir 模式在这类设备上永远起不来。预检必须**实测**（同 fs 上两个空目录做只读试挂），
#   不能靠 fs 名字硬编码。这里断言：能挂→0、不能挂→1，且两种情况下探针目录都被清干净。
# ---------------------------------------------------------------------------
head_ "overlay 层可用性预检（overlay_fs_usable）"
OB="$TMP/ovlprobe"; mkdir -p "$OB"
PB2="$TMP/ovl-bin"; mkdir -p "$PB2"
printf '%s\n' '#!/bin/sh' 'echo "mount $*" >> "$CALLS_FILE"' 'exit 0' > "$PB2/mount-ok"
printf '%s\n' '#!/bin/sh' 'echo "mount $*" >> "$CALLS_FILE"' 'echo "mount: '"'"'overlay'"'"'->'"'"'/x'"'"': Invalid argument" >&2' 'exit 1' > "$PB2/mount-bad"
printf '%s\n' '#!/bin/sh' 'echo "umount $*" >> "$CALLS_FILE"' 'exit 0' > "$PB2/umount"
chmod +x "$PB2/mount-ok" "$PB2/mount-bad" "$PB2/umount"
oh="$TMP/ovl-harness.sh"
{
    echo 'log() { :; }'
    sed -n '/^overlay_fs_usable()/,/^}$/p' "$SELF_DIR/start.sh"
} > "$oh"
grep -q '^overlay_fs_usable()' "$oh" || bad "抽取 overlay_fs_usable 失败（函数被改名了？）"
for pair in "ok:mount-ok:R=0" "bad:mount-bad:R=1"; do
    tag="${pair%%:*}"; rest="${pair#*:}"; stub="${rest%%:*}"; want="${rest##*:}"
    : > "$OB-calls-$tag"
    got="$(MOUNT="$PB2/$stub" UMOUNT="$PB2/umount" DAEMON_LOG="$TMP/ovlprobe.log" \
           CALLS_FILE="$OB-calls-$tag" \
           "$SH_BIN" -c '. "$1"; overlay_fs_usable "$2" && echo R=0 || echo R=1' _ "$oh" "$OB" 2>&1 || true)"
    case "$got" in
        *"$want"*) ok "试挂$tag（stub=$stub）→ $want" ;;
        *) bad "试挂$tag 结论不对（期望 $want）：$(printf '%s' "$got" | tr '\n' ' ')" ;;
    esac
    case "$(ls -a "$OB" 2>/dev/null | tr '\n' ' ')" in
        *".ovlprobe"*) bad "试挂$tag 后探针目录没清干净：$(ls -a "$OB" | tr '\n' ' ')" ;;
        *) ok "试挂$tag 后探针目录已清干净" ;;
    esac
done

# ---------------------------------------------------------------------------
# 本机能力探测：loop 能不能读我们的文件 / 能不能挂 fuse（2026-09-17）
#   真机实测：loop 的 I/O 在 kworker（u:r:kernel:s0）里做，SELinux 按 current_sid() 判权限 →
#     厂商只读 loop（system_file）读得到；我们的文件（system_data_file/shell_data_file）连读都被拒。
#   所以 start.sh 必须能拿到 loop_read=no 并**别再走 loop 自愈仪式**（那三步救不了"域没权限"）。
#   这里用假 losetup/dd/mount 驱动真函数（不是复制实现）。
# ---------------------------------------------------------------------------
head_ "loop / fuse 能力探测（loop_read / fuse_mount）"
LB="$TMP/loop-bin"; mkdir -p "$LB/ok" "$LB/fail" "$TMP/loop-run"
printf '%s\n' '#!/bin/sh' 'case "$1" in -f) echo /dev/block/loop77 ;; esac' 'exit 0' > "$LB/losetup"
printf '%s\n' '#!/bin/sh' 'exit 0' > "$LB/ok/dd"
printf '%s\n' '#!/bin/sh' 'exit 1' > "$LB/fail/dd"
cp "$LB/losetup" "$LB/ok/losetup"; cp "$LB/losetup" "$LB/fail/losetup"
chmod +x "$LB/losetup" "$LB/ok/dd" "$LB/fail/dd" "$LB/ok/losetup" "$LB/fail/losetup"
printf '%s\n' '#!/bin/sh' 'echo "mount: '"'"'none'"'"'->'"'"'/x'"'"': Permission denied" >&2' 'exit 1' > "$LB/mount-denied"
printf '%s\n' '#!/bin/sh' 'echo "mount: '"'"'none'"'"'->'"'"'/x'"'"': Bad file descriptor" >&2' 'exit 1' > "$LB/mount-badfd"
chmod +x "$LB/mount-denied" "$LB/mount-badfd"
lh="$TMP/loop-harness.sh"
{
    echo 'have() { command -v "$1" >/dev/null 2>&1; }'
    sed -n '/^probe_get()/,/^}/p'      "$SELF_DIR/start.sh"
    sed -n '/^probe_set()/,/^}/p'      "$SELF_DIR/start.sh"
    sed -n '/^probe_loop_io()/,/^}$/p' "$SELF_DIR/start.sh"
    sed -n '/^probe_fuse_mount()/,/^}$/p' "$SELF_DIR/start.sh"
} > "$lh"
for fn in probe_loop_io probe_fuse_mount; do
    grep -q "^$fn()" "$lh" || bad "抽取 $fn 失败（函数被改名了？）"
done
for pair in "loop读OK:ok:loop_read=yes" "loop读失败:fail:loop_read=no"; do
    tag="${pair%%:*}"; rest="${pair#*:}"; sub="${rest%%:*}"; want="${rest##*:}"
    got="$(RUN_DIR="$TMP/loop-run" PROBE_FILE="$TMP/loop-run/p-$sub" PATH="$LB/$sub:$PATH" \
           "$SH_BIN" -c '. "$1"; probe_loop_io; cat "$PROBE_FILE"' _ "$lh" 2>&1 || true)"
    case "$got" in
        *"$want"*) ok "$tag → $want（真机上 loop 读被拒就该判 no）" ;;
        *) bad "$tag 判定不对（期望 $want）：$(printf '%s' "$got" | tr '\n' ' ')" ;;
    esac
done
for pair in "mount-denied:denied" "mount-badfd:ok"; do
    sub="${pair%%:*}"; want="${pair##*:}"
    got="$(RUN_DIR="$TMP/loop-run" PROBE_FILE="$TMP/loop-run/f-$sub" MOUNT="$LB/$sub" \
           SUNSETLINUX_FUSE_DEV=/dev/null \
           "$SH_BIN" -c '. "$1"; probe_fuse_mount; cat "$PROBE_FILE"' _ "$lh" 2>&1 || true)"
    case "$got" in
        *"fuse_mount=$want"*) ok "fuse 探测（$sub）→ fuse_mount=$want" ;;
        *) bad "fuse 探测（$sub）判定不对（期望 $want）：$(printf '%s' "$got" | tr '\n' ' ')" ;;
    esac
done

# ---------------------------------------------------------------------------
# mount_layer：同一条 local 里自引用会炸（2026-09-17 真机事故）
#   `local name="$1" … dst="$LAYERS_MNT/$name"` —— mksh 与 bash 在同一条 local 内都取不到
#   刚赋的变量，`set -u` 下报 "name: parameter not set"。真机上 start.sh 就跑死在这行，
#   而且**只**因为此前 loop 写不通、这行从未被执行过才一直没暴露。
#   这里用真函数 + 假 mount/mountpoint/grep 驱动，断言：不再报 parameter not set，且登记正确。
# ---------------------------------------------------------------------------
head_ "mount_layer 的同条 local 自引用（真机回归）"
MLB="$TMP/ml-bin"; mkdir -p "$MLB" "$TMP/ml-run" "$TMP/ml-mnt"
printf '%s\n' '#!/bin/sh' 'exit 0' > "$MLB/grep"          # 让 /proc/filesystems 的支持性检查通过
printf '%s\n' '#!/bin/sh' 'exit 0' > "$MLB/mount"
printf '%s\n' '#!/bin/sh' 'exit 1' > "$MLB/mountpoint"    # 挂载点当前未挂
chmod +x "$MLB/grep" "$MLB/mount" "$MLB/mountpoint"
mlh="$TMP/ml-harness.sh"
{
    echo 'log() { :; }'
    echo 'die() { echo "DIE: $*" >&2; exit 1; }'
    echo 'have() { command -v "$1" >/dev/null 2>&1; }'
    sed -n '/^_magic_at()/,/^}/p'    "$SELF_DIR/start.sh"
    sed -n '/^layer_format()/,/^}/p' "$SELF_DIR/start.sh"
    sed -n '/^mount_layer()/,/^}$/p' "$SELF_DIR/start.sh"
} > "$mlh"
grep -q '^mount_layer()' "$mlh" || bad "抽取 mount_layer 失败（函数被改名了？）"
mlout="$(PATH="$MLB:$PATH" MOUNT="$MLB/mount" UMOUNT="$MLB/mount" \
         LAYERS_MNT="$TMP/ml-mnt" MOUNTS_FILE="$TMP/ml-run/mounts" DAEMON_LOG="$TMP/ml-run/log" \
         "$SH_BIN" -c 'set -u; . "$1"; mount_layer base "$2"; echo "ML=ok"' _ "$mlh" \
         "$FIXTURES/erofs-head.bin" 2>&1 || true)"
case "$mlout" in
    *"parameter not set"*|*"unbound variable"*)
        bad "同一条 local 自引用又炸了：$(printf '%s' "$mlout" | tr '\n' ' ' | cut -c1-110)" ;;
    *"ML=ok"*) ok "mount_layer 在 set -u 下跑通（不再 name: parameter not set）" ;;
    *) bad "mount_layer 没跑通：$(printf '%s' "$mlout" | tr '\n' ' ' | cut -c1-130)" ;;
esac
case "$(tr '\n' ' ' < "$TMP/ml-run/mounts" 2>/dev/null)" in
    *"layers-mnt/base"*) ok "登记成 layers-mnt/base（dst 拼对了）" ;;
    *) bad "登记不对：$(tr '\n' ' ' < "$TMP/ml-run/mounts" 2>/dev/null)" ;;
esac

# ---------------------------------------------------------------------------
# 内层不许调安卓系统工具（2026-09-17 整机卡死事故的回归）
#   事故：build_mount_tree 里调 gather_android_facts → 在内层（私有 mount/UTS ns）拉起
#   /system/bin/ndc，ndc 打不开 /dev/binder → SIGABRT；5 个 tombstone + ANR +
#   system_server crash ⇒ 整机卡死、只能重启。
#   安全性质（文本断言，够硬）：① gather_android_facts 只在 main()（父进程）里调用一次；
#                              ② 内层（build_mount_tree，位于 main() 之前）只用 install_android_facts 拷贝。
# ---------------------------------------------------------------------------
head_ "内层不许调安卓系统工具（ndc/getprop）"
MAIN_LN="$(grep -n '^main()' "$SELF_DIR/start.sh" | head -1 | cut -d: -f1)"
G_CALL="$(grep -n '^ *gather_android_facts$' "$SELF_DIR/start.sh" | head -1 | cut -d: -f1)"
I_CALL="$(grep -n '^ *install_android_facts$' "$SELF_DIR/start.sh" | head -1 | cut -d: -f1)"
G_N="$(grep -c '^ *gather_android_facts$' "$SELF_DIR/start.sh")"
I_N="$(grep -c '^ *install_android_facts$' "$SELF_DIR/start.sh")"
if [ "$G_N" = "1" ] && [ -n "$G_CALL" ] && [ -n "$MAIN_LN" ] && [ "$G_CALL" -gt "$MAIN_LN" ]; then
    ok "gather_android_facts 只在 main()（父进程）里调用一次（第 $G_CALL 行 > main 第 $MAIN_LN 行）"
else
    bad "gather_android_facts 调用位置/次数不对（次数=$G_N 行=$G_CALL main=$MAIN_LN）：内层会拉起 ndc 把整机带崩"
fi
if [ "$I_N" = "1" ] && [ -n "$I_CALL" ] && [ -n "$MAIN_LN" ] && [ "$I_CALL" -lt "$MAIN_LN" ]; then
    ok "内层只用 install_android_facts 拷贝（第 $I_CALL 行 < main 第 $MAIN_LN 行）"
else
    bad "内层没有改用 install_android_facts（次数=$I_N 行=$I_CALL main=$MAIN_LN）"
fi

# ---------------------------------------------------------------------------
# 环境内 run 目录契约（2026-09-17 真机事故的回归）
#   事故：entry.sh / supervise.sh 用 "$LINUX_HOME/run" 拼 run 目录。这两个脚本在 **chroot 内**
#   运行（根已经换成 overlay），那个路径既不是宿主目录、也不是 rbind 进来的 /run，而是可写层里
#   凭空 mkdir 出来的**假目录** → 宿主侧 linuxctl / App 读 $LINUX_HOME/run/dsh.url 永远是 null
#   → App 报「环境已在运行，但登录地址还没写出来（run/dsh.url 尚未就绪）」，WebView 打不开。
#   契约（docs/architecture.md §3.2）：环境内**只写 /run**；start.sh 的 rbind_host_run 把宿主
#   $LINUX_HOME/run rbind 到那里，两侧是同一批 inode。
#   断言：① 环境内脚本解析出的 RUN_DIR 必须是 /run（跑的是真实那一行）；
#         ② 两个脚本里不许再有 `RUN_DIR="$LINUX_HOME/run"`；
#         ③ 宿主侧 rbind 的落点必须还是 rootfs/run（契约的另一半）。
# ---------------------------------------------------------------------------
head_ "环境内 run 目录契约（写 /run，不写 \$LINUX_HOME/run）"
for _src in "$SELF_DIR/entry.sh" "$SELF_DIR/supervise.sh"; do
    _base="$(basename "$_src")"
    [ -f "$_src" ] || { bad "找不到 $_src"; continue; }
    _line="$(awk '/^RUN_DIR=/{print; exit}' "$_src" 2>/dev/null)"
    if [ -z "$_line" ]; then
        bad "$_base：抽不到 RUN_DIR= 那一行（被改名了？契约断言失效，先修测试）"
        continue
    fi
    _rv="$("$SH_BIN" -c 'unset SUNSETLINUX_RUN_DIR 2>/dev/null || true
        LINUX_HOME=/data/sunsetlinux
        eval "$1"
        printf "%s" "${RUN_DIR:-}"' _ "$_line" 2>/dev/null)"
    if [ "$_rv" = "/run" ]; then
        ok "$_base：LINUX_HOME=/data/sunsetlinux 时 RUN_DIR=/run（= 宿主 \$LINUX_HOME/run 的 rbind 落点）"
    else
        bad "$_base：RUN_DIR=$_rv，必须是 /run —— chroot 内的 \$LINUX_HOME/run 是可写层里的假目录，App 读不到 dsh.url"
    fi
done
for _src in "$SELF_DIR/entry.sh" "$SELF_DIR/supervise.sh"; do
    _base="$(basename "$_src")"
    [ -f "$_src" ] || continue
    if [ "$(grep -c -F 'RUN_DIR="$LINUX_HOME/run"' "$_src" 2>/dev/null)" = "0" ]; then
        ok "$_base：没有写回 \$LINUX_HOME/run（真机事故那一行）"
    else
        bad "$_base：又出现了 RUN_DIR=\"\$LINUX_HOME/run\" —— 那行就是真机事故的根因"
    fi
done
_DB_N="$(grep -c -F 'do_bind run "$RUN_DIR"' "$SELF_DIR/start.sh" 2>/dev/null)"
_DR_N="$(grep -c -F 'dst="$ROOTFS_DIR/run"' "$SELF_DIR/start.sh" 2>/dev/null)"
if [ "$_DB_N" = "1" ] && [ "$_DR_N" = "1" ]; then
    ok "start.sh：宿主 \$RUN_DIR rbind 到 rootfs/run（环境内 /run 契约的另一半还在）"
else
    bad "start.sh 的 rbind_host_run 落点变了（do_bind=$_DB_N dst=$_DR_N）：环境内 /run 与宿主 \$LINUX_HOME/run 的对应关系是前提"
fi

# ---------------------------------------------------------------------------
# 环境内进程清理（runtime/common/env-procs.sh）—— stop.sh 与 linuxctl 共用的一份
#   事故链（docs/STATUS.md §3.10.31）：dsh.pid 被写进可写层假目录 → 宿主读不到 →
#   stop.sh 只按 pid 文件补刀 = 没补 → 残留进程占着 127.0.0.1:3080 与 loop → 下次 start 失败。
#   判据必须带 /proc/<pid>/root（别的环境的 dsh 命令行一模一样，见下）。
# ---------------------------------------------------------------------------
head_ "环境内进程清理（判据：/proc/<pid>/root == 我们的 rootfs）"
_COMMON_DIR="$SELF_DIR/../common"
if [ ! -f "$_COMMON_DIR/env-procs.sh" ]; then
    bad "找不到 runtime/common/env-procs.sh（stop.sh / linuxctl 的进程清理都靠它）"
else
    if grep -q 'env_proc_kill_all' "$SELF_DIR/stop.sh" 2>/dev/null; then
        ok "stop.sh 走共用库 env_proc_kill_all（没有自己再写一份 ps|while 匹配）"
    else
        bad "stop.sh 没调用 env_proc_kill_all：残留进程会占着挂载点/端口，下次 start 直接失败"
    fi
    mkdir -p "$TMP/lstub" "$TMP/lroot"
    printf '#!/bin/sh\nprintf "%%s\\n" "${FAKE_ROOT:-}"\n' > "$TMP/lstub/readlink"
    chmod +x "$TMP/lstub/readlink"
    # 正例：root 指向我们的 rootfs → 必须被杀
    sleep 300 & _V1=$!
    printf '#!/bin/sh\nprintf "%%s\\n" "  PID ARGS" "%s node /usr/local/bin/dsh web --host 127.0.0.1 --port 3080"\n' "$_V1" > "$TMP/lstub/ps"
    chmod +x "$TMP/lstub/ps"
    PATH="$TMP/lstub:$PATH" FAKE_ROOT="$TMP/lroot" ENV_ROOTFS="$TMP/lroot" \
        "$SH_BIN" -c '. "$1"; env_proc_kill_all' _ "$_COMMON_DIR/env-procs.sh" >/dev/null 2>&1
    sleep 0.3
    _S1=""
    [ -f "/proc/$_V1/stat" ] && _S1="$(sed 's/.*) //' "/proc/$_V1/stat" 2>/dev/null | cut -c1)"
    case "$_S1" in
        ""|Z) ok "环境内残留进程（root=$TMP/lroot）被杀掉（state=${_S1:-gone}）" ;;
        *)    bad "环境内残留进程没被杀（state=$_S1）：stop 之后端口/loop 会被它占住" ;;
    esac
    kill "$_V1" 2>/dev/null || true
    # 反例：root 不是我们的 rootfs（别的环境的同名 dsh）→ 一根汗毛都不许动
    sleep 300 & _V2=$!
    printf '#!/bin/sh\nprintf "%%s\\n" "  PID ARGS" "%s node /usr/local/bin/dsh web --host 127.0.0.1 --port 0"\n' "$_V2" > "$TMP/lstub/ps"
    chmod +x "$TMP/lstub/ps"
    PATH="$TMP/lstub:$PATH" FAKE_ROOT=/ ENV_ROOTFS="$TMP/lroot" \
        "$SH_BIN" -c '. "$1"; env_proc_kill_all' _ "$_COMMON_DIR/env-procs.sh" >/dev/null 2>&1
    _S2=""
    [ -f "/proc/$_V2/stat" ] && _S2="$(sed 's/.*) //' "/proc/$_V2/stat" 2>/dev/null | cut -c1)"
    case "$_S2" in
        S|R|D) ok "别的环境的 dsh（root=/）没被误杀（state=$_S2）" ;;
        *)     bad "误杀了 root 不是我们 rootfs 的进程（state=${_S2:-gone}）：判据必须带 /proc/<pid>/root" ;;
    esac
    kill "$_V2" 2>/dev/null || true
    # 安全闸：ENV_ROOTFS 为空（判据残缺）→ 一个都不许动
    sleep 300 & _V3=$!
    printf '#!/bin/sh\nprintf "%%s\\n" "  PID ARGS" "%s node /usr/local/bin/dsh web --port 3080"\n' "$_V3" > "$TMP/lstub/ps"
    chmod +x "$TMP/lstub/ps"
    PATH="$TMP/lstub:$PATH" FAKE_ROOT="$TMP/lroot" ENV_ROOTFS= \
        "$SH_BIN" -c '. "$1"; env_proc_kill_all' _ "$_COMMON_DIR/env-procs.sh" >/dev/null 2>&1
    _S3=""
    [ -f "/proc/$_V3/stat" ] && _S3="$(sed 's/.*) //' "/proc/$_V3/stat" 2>/dev/null | cut -c1)"
    case "$_S3" in
        S|R|D) ok "ENV_ROOTFS 为空时一个进程都不动（判据残缺 = 不做，而不是乱杀）" ;;
        *)     bad "ENV_ROOTFS 为空还动手了（state=${_S3:-gone}）：这是最危险的一类误杀" ;;
    esac
    kill "$_V3" 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
# 环境 / DSH 分离（App 的「一键启动 · 仅启动环境 · 启动 DSH · 停止 DSH」）
#   两种方法是**互斥**的：env-mode=env-only 的环境不许被"一键启动"顺手塞进 DSH；
#   env-mode=full（一键启动）的环境不许单独停 DSH。这条规则在 App 的按钮判定与
#   linuxctl 里必须一致 —— 这里验的是 linuxctl 那一半（真跑命令，不只看文本）。
# ---------------------------------------------------------------------------
head_ "环境 / DSH 分离（仅启动环境 · 启动 DSH · 互斥判定）"
_ES="$TMP/envsplit"
mkdir -p "$_ES/run" "$_ES/etc" "$_ES/layers"
# 假的"环境在跑"现场：ready + supervisor.pid + env-mode。
# supervisor.pid 用**自测脚本自己**（$$）：只需要一个活着、且 /proc/<pid>/ns/mnt 可读的进程，
# 本进程就满足（在普通 Linux 上；proot 沙箱里 ns 文件的 stat 会被 ptrace 拦掉 → 见下面的 skip）。
# 刻意不用新起的 sleep：省一个要收拾的子进程。
printf '%s\n' "$$" > "$_ES/run/supervisor.pid"
: > "$_ES/run/ready"
printf 'env-only\n' > "$_ES/run/env-mode"
_es_ctl() { LINUX_HOME="$_ES" "$SH_BIN" "$SELF_DIR/linuxctl.sh" "$@"; }
# ★ status 的**空白必须先去掉**再断言：CI 上装了 jq，`dsh_status_json` 走 jq 路径 →
#   输出是**美化多行**的（`"state": "running"`，冒号后带空格）；本机没 jq 时是紧凑单行。
#   照紧凑形式写 case，就会出现"本机全绿、CI 全红"的假红（2026-09-17 CI 上真红过一次，
#   三条断言全挂）。去掉空白后两种形式都能匹配（我们只匹配没有空格的键值）。
_es_json() { _es_ctl status 2>/dev/null | tr -d ' \n\t'; }
if [ -r "/proc/$$/ns/mnt" ]; then
    _es_j="$(_es_json)"
    case "$_es_j" in
        *'"state":"running"'*) ok "status：环境在跑（state=running）" ;;
        *) bad "status 没把假环境判成 running：$_es_j" ;;
    esac
    case "$_es_j" in
        *'"running":false'*) ok "status：dsh.running=false（环境在跑、DSH 没跑 —— 一等状态）" ;;
        *) bad "status 的 dsh.running 不是 false：$_es_j" ;;
    esac
    case "$_es_j" in
        *'"env_mode":"env-only"'*) ok "status：env_mode=env-only（App 靠它做按钮互斥）" ;;
        *) bad "status 没报 env_mode=env-only：$_es_j" ;;
    esac
    # 互斥①：env-only 的环境不许被"一键启动"顺手塞进 DSH（按**拒绝理由**断言，不看退出码 ——
    # 退出码非 0 也可能是因为别的失败，那样就成了假绿）
    _es_out="$(_es_ctl start 2>&1 || true)"
    case "$_es_out" in
        *仅启动环境*) ok "「一键启动」在 env-only 环境上被拒（要起 DSH 得走 dsh start）" ;;
        *) bad "「一键启动」在 env-only 环境上的拒绝信息不对：$_es_out" ;;
    esac
    # 互斥②：full（一键启动）的环境不许单独停 DSH
    printf 'full\n' > "$_ES/run/env-mode"
    _es_out="$(_es_ctl dsh stop 2>&1 || true)"
    case "$_es_out" in
        *一键启动*) ok "「停止 DSH」在 full 环境上被拒（要单独控制就先停止环境）" ;;
        *) bad "「停止 DSH」在 full 环境上的拒绝信息不对：$_es_out" ;;
    esac
    rm -f "$_ES/run/ready"
else
    rm -f "$_ES/run/ready"
    skip_ "本机看不到 /proc/<pid>/ns/mnt（proot 沙箱）→ 跳过「环境在跑」那几条；CI 与真机会跑"
fi
# 环境没跑之后：dsh start 必须失败、dsh stop 必须幂等成功（这两条与 ns 可见性无关）
rm -f "$_ES/run/supervisor.pid"
if _es_ctl dsh start >/dev/null 2>&1; then
    bad "环境没跑时 dsh start 居然成功了"
else
    ok "环境没跑时 dsh start 失败（提示先起环境）"
fi
if _es_ctl dsh stop >/dev/null 2>&1; then
    ok "环境没跑时 dsh stop 幂等成功（顺手清掉过期凭证）"
else
    bad "环境没跑时 dsh stop 不是幂等成功"
fi
# 一键启动那条链路的**参数透传**（互斥判定之外的真链路）：linuxctl → start.sh → run/env-mode
_NO_N="$(grep -c -F 'SUNSETLINUX_NO_DSH="$no_dsh"' "$SELF_DIR/linuxctl.sh" 2>/dev/null)"
_OPT_N="$(grep -c -F -- '--no-dsh)' "$SELF_DIR/start.sh" 2>/dev/null)"
_EM_N="$(grep -c -F 'env-only' "$SELF_DIR/start.sh" 2>/dev/null)"
if [ "$_NO_N" = "1" ] && [ "$_OPT_N" = "1" ] && [ "$_EM_N" -ge 1 ]; then
    ok "start --no-dsh 的链路完整（linuxctl 透传 → start.sh 认参数 → 写 run/env-mode=env-only）"
else
    bad "start --no-dsh 的链路缺件（linuxctl=$_NO_N start.sh参数=$_OPT_N env-mode=$_EM_N）"
fi
_EN_N="$(grep -c -F -- '--no-dsh' "$SELF_DIR/entry.sh" 2>/dev/null)"
if [ "$_EN_N" -ge 1 ]; then
    ok "entry.sh 认 --no-dsh（只准备环境，不 exec supervise.sh）"
else
    bad "entry.sh 不认 --no-dsh：环境起来后会立刻跑 DSH，或直接退出"
fi
# 拆开启动**不经过 entry.sh**（宿主侧直接 nsenter+chroot 拉 supervise.sh），
# 所以 API key 那个 env 文件必须由 supervise.sh 自己加载 —— 否则"起来了却用不了"。
if grep -q -F '. "$ENV_FILE"' "$SELF_DIR/supervise.sh" 2>/dev/null && \
   grep -q -F 'set -a' "$SELF_DIR/supervise.sh" 2>/dev/null; then
    ok "supervise.sh 自己加载 \$DSH_HOME/env（dsh start 那条路也拿得到 API key）"
else
    bad "supervise.sh 没加载 \$DSH_HOME/env：linuxctl dsh start 起来的 DSH 会拿不到 API key"
fi
# 层更新 / 版本回滚 / 快照恢复 / 快照打包都会 stop→start：**必须保持原来的起法**，
# 否则用户在「仅环境」里更新层，重启后凭空多出一个占着 3080 的 DSH —— 维护模式被悄悄踢掉。
_RB_N="$(grep -c -F 'resume_start_like_before' "$SELF_DIR/linuxctl.sh" 2>/dev/null)"
_WM_N="$(grep -c -F 'was_mode="$(env_mode_read' "$SELF_DIR/linuxctl.sh" 2>/dev/null)"
if [ "$_RB_N" -ge 5 ] && [ "$_WM_N" -ge 4 ]; then
    ok "重启路径保持原来的起法（resume=$_RB_N 处，was_mode=$_WM_N 处）：env-only 不会被塞回 DSH"
else
    bad "重启路径没保持 env-mode（resume=$_RB_N was_mode=$_WM_N）：维护会话会被 DSH 踢掉"
fi
# doctor 不许把「仅环境」当成故障：它区分"环境在跑但没跑 DSH"（预期）与
# "环境在跑却拿不到 dsh.url"（故障，见 §3.10.31）。判错会让用户去修一个正确的状态。
if [ -r "/proc/$$/ns/mnt" ]; then
    : > "$_ES/run/ready"
    printf 'env-only\n' > "$_ES/run/env-mode"
    printf '%s\n' "$$" > "$_ES/run/supervisor.pid"
    _es_doc="$(LINUX_HOME="$_ES" SUNSETLINUX_MOUNT=true SUNSETLINUX_UMOUNT=true \
        "$SH_BIN" "$SELF_DIR/doctor.sh" 2>&1 || true)"
    case "$_es_doc" in
        *仅环境*) ok "doctor 认出 run/env-mode=env-only（「没跑 DSH」不再被当成故障）" ;;
        *) bad "doctor 没认出 env-only：用户会被引去「修」一个本来正确的状态" ;;
    esac
    case "$_es_doc" in
        *"run/dsh.url 不存在 —— App 拿不到"*) bad "doctor 把 env-only 误报成「运行中却缺 dsh.url」" ;;
        *) ok "doctor 没把 env-only 误报成故障" ;;
    esac
fi

head_ "nsenter 的选项面（对照设备上的**真实二进制**）"
# 为什么单独测这一条：真机的 `/system/bin/nsenter` 是 **toybox**，选项面比宿主/CI 的
# util-linux 窄，而且"错一个选项"不是降级、是**整条命令不执行**。2026-09-17 真机事故：
# linuxctl 的 attach/exec 写了 `--wd="$ROOTFS_DIR"`，toybox 直接
# `Unknown option 'wd=/data/sunsetlinux/rootfs'` ⇒ 终端永远进不去环境。
# 静态闸门在 tools/shell-compat-check.mjs（自带"会红吗"自检）；本条是行为级对照，
# 拿设备上真正的二进制验一遍我们实际用的写法。
# 判据只看**选项解析**：No such file / Operation not permitted 都算解析通过
# （非 root 或 ns 文件不可见时必然如此），只有 Unknown option / need -t or =filename 才算失败。
if [ -x /system/bin/nsenter ]; then
    _ns_err="$(/system/bin/nsenter --mount="/proc/$$/ns/mnt" --uts="/proc/$$/ns/uts" \
        /system/bin/true 2>&1 >/dev/null || true)"
    case "$_ns_err" in
        *"Unknown option"*|*"need -t or =filename"*)
            bad "设备 nsenter 不接受我们用的写法（--mount=/path --uts=/path）：$_ns_err" ;;
        *) ok "设备 nsenter 接受 --mount=/path --uts=/path（$(printf '%s' "$_ns_err" | head -n1)）" ;;
    esac
    # ★ 2026-09-18 真机第三起：`dsh start` 永远失败，日志只有一句
    #   `nsenter: Unknown option 'port'` —— 因为 toybox 会把**命令之后**的选项也当成自己的。
    #   下面两条是**行为级**证据（拿设备真二进制跑），静态闸门见 shell-compat-check.mjs。
    # 二进制走变量：本行是**故意复刻错误写法**的探针，不该被静态闸门当成生产调用
    # （闸门扫的是字面量 `nsenter`/`$NSENTER`，这里两者都不出现）。
    _NS=/system/bin/nsenter
    _ns_after="$("$_NS" --mount="/proc/$$/ns/mnt" /system/bin/echo -l 2>&1 >/dev/null | grep -v "^WARNING: linker" || true)"
    case "$_ns_after" in
        *"Unknown option"*) ok "设备 nsenter 会吞掉命令后的选项（-l → $(printf '%s' "$_ns_after" | head -n1)）" ;;
        *) bad "设备 nsenter 没吞命令后的 -l：本组判据要重写（$_ns_after）" ;;
    esac
    _ns_dash="$("$_NS" --mount="/proc/$$/ns/mnt" -- /system/bin/echo -l 2>&1 >/dev/null || true)"
    case "$_ns_dash" in
        *"Unknown option"*) bad "命令前写了 -- 仍报未知选项（解药失效）：$_ns_dash" ;;
        *) ok "命令前写 -- 后不再报未知选项（这就是三处调用的修法）" ;;
    esac
else
    skip_ "本机没有 /system/bin/nsenter（CI/开发机正常）"
fi

# 静态：我们自己的 nsenter 调用必须在命令前写 `--`（判据：逻辑行里出现 `-- `）。
# 为什么连这个也测：`--` 少一个，设备上不是降级而是**整条命令不执行**，
# 而报错只有一行落在 run/linux.log 里 —— 真机上已经因此连坏三处（dsh start / 终端 / stop 的 umount）。
for _f in "$SELF_DIR/linuxctl.sh" "$SELF_DIR/stop.sh"; do
    _miss="$(awk '/NSENTER" --mount=/{ l=$0; while (l ~ /\\$/) { getline n; l = l " " n } if (l !~ /-- /) print NR }' "$_f" 2>/dev/null | tr '\n' ' ')"
    if [ -z "$_miss" ]; then
        ok "$(basename "$_f")：每处 nsenter 调用都在命令前写了 --"
    else
        bad "$(basename "$_f")：这些行的 nsenter 少了 --（第 $_miss 行）—— 设备上会整条不执行"
    fi
done

# ---------------------------------------------------------------------------
# 守护进程必须**后台脱离**：父脚本不许等它
#
# 真机事故（2026-09-18）：`if spawn_detached …; then` 把守护进程**前台**起，
# 而守护链最后的 entry.sh 按设计永不退出 ⇒ start.sh → linuxctl → App 的 su 全不退
# ⇒ App 的 busy 永久为真、启动区五张卡片全灰（用户："点了启动环境，然后就没了，
# 点不了启动 DSH"）。设备现场：那条链 20 分钟后仍全部停在 rt_sigsuspend。
#
# 这条断言**行为级**验证"父不等"：从真实脚本里抽取 spawn_detached/spawn_daemon
# （不复制实现、也不 source 整个脚本 —— 那会执行它的主体），配一个"永不退出"的守护，
# 计时；再配一个前台 spawn 的对照样本，证明这个计时确实能区分两种写法。
# 静态闸门（变异测试）见 tools/shell-compat-check.mjs 的 foregroundSpawnSelfCheck。
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# 启动锁：第二次 start 不许再建一棵挂载树
#
# 真机事故（2026-09-18 03:39，用户："脚本似乎在尝试重复挂载？"）：
# `running_ns_pid` 只看**已就绪**，而建树要 30~50 秒 ⇒ 这段窗口里状态报 stopped，
# 第二次 start 又建一棵树：run/mounts 13 → 26 条、两条守护链同时活着、
# 最后 dsh start 报"进不去环境"。锁的判据：文件在 + 里面的 pid 还活着。
#
# 原语（acquire/holder/release）做**行为断言**；"接管陈旧锁""不重复建树"这两条接线
# 在 main() 里、无法单独抽出执行，用静态断言钉住它们的存在与顺序。
# ---------------------------------------------------------------------------
head_ "启动锁：第二次 start 不许再建一棵挂载树"
if [ -f "$SELF_DIR/start.sh" ]; then
    _LK="$TMP/startlock"
    mkdir -p "$_LK/run"
    {
        printf "RUN_DIR='%s'\n" "$_LK/run"
        printf 'START_LOCK="$RUN_DIR/start.lock"\n'
        sed -n '/^start_lock_acquire()/,/^}/p' "$SELF_DIR/start.sh"
        sed -n '/^start_lock_holder()/,/^}/p'  "$SELF_DIR/start.sh"
        sed -n '/^start_lock_release()/,/^}/p' "$SELF_DIR/start.sh"
        sed -n '/^start_lock_release_own()/,/^}/p' "$SELF_DIR/start.sh"
    } > "$_LK/lock.sh"
    if grep -q '^start_lock_acquire()' "$_LK/lock.sh" && grep -q '^start_lock_holder()' "$_LK/lock.sh"; then
        # 一个"持锁不放"的后台进程（它的 pid 会被写进锁文件）
        cat > "$_LK/hold.sh" <<EOF
. '$_LK/lock.sh'
start_lock_acquire || exit 3
sleep 8
EOF
        "$SH_BIN" "$_LK/hold.sh" >/dev/null 2>&1 &
        _hold=$!
        _i=0
        while [ "$_i" -lt 30 ] && [ ! -f "$_LK/run/start.lock" ]; do sleep 0.1; _i=$((_i+1)); done
        _got="$("$SH_BIN" -c ". '$_LK/lock.sh'; start_lock_acquire && echo ACQUIRED || echo BUSY")"
        case "$_got" in
            *BUSY*) ok "锁被占时第二次 start 拿不到锁（⇒ 不会又建一棵树）" ;;
            *) bad "第二次 start 竟然拿到了锁（$_got）—— 重复挂载会重现" ;;
        esac
        _h="$("$SH_BIN" -c ". '$_LK/lock.sh'; start_lock_holder || echo none")"
        if [ "$_h" = "$_hold" ]; then
            ok "start_lock_holder 指向真正持锁的进程（pid=$_h）"
        else
            bad "start_lock_holder 没指向持锁进程：期望 $_hold、得到 $_h"
        fi
        # 陈旧锁：持锁进程死掉后，holder 必须失效（否则一次崩溃会把以后所有启动都堵死）
        kill -9 "$_hold" 2>/dev/null || true
        wait "$_hold" 2>/dev/null || true
        _h2="$("$SH_BIN" -c ". '$_LK/lock.sh'; start_lock_holder || echo none")"
        if [ "$_h2" = "none" ]; then
            ok "持锁进程死后 holder 失效（陈旧锁可被接管）"
        else
            bad "持锁进程已死但 holder 仍报 $_h2 —— 陈旧锁会永久堵住启动"
        fi
        _got2="$("$SH_BIN" -c ". '$_LK/lock.sh'; start_lock_release; start_lock_acquire && echo ACQUIRED || echo BUSY")"
        case "$_got2" in
            *ACQUIRED*) ok "释放/接管后可以重新占有（启动不会被永久堵住）" ;;
            *) bad "陈旧锁没有被接管（$_got2）" ;;
        esac
        # 外层超时退出时**不许**放掉守护进程的锁（那会让第二次 start 又建一棵树）
        printf '1 1\n' > "$_LK/run/start.lock"
        "$SH_BIN" -c ". '$_LK/lock.sh'; start_lock_release_own"
        if [ -f "$_LK/run/start.lock" ]; then
            ok "start_lock_release_own 不碰别人的锁（外层超时退出也留得住）"
        else
            bad "别人的锁被删掉了 —— 外层一超时，第二次 start 就会再建一棵树"
        fi
        # 自己的锁必须放掉。用 `( … )` 子壳而不是 `$SH_BIN -c`：POSIX 下子壳里的 $$
        # 仍是**父进程**的 pid，所以"写锁用的 $$"和"函数看到的 $$"是同一个。
        printf '%s 1\n' "$$" > "$_LK/run/start.lock"
        ( . "$_LK/lock.sh"; start_lock_release_own )
        if [ -f "$_LK/run/start.lock" ]; then
            bad "自己的锁没被释放 —— 一次失败启动会把以后所有启动都堵住"
        else
            ok "start_lock_release_own 释放自己的锁"
        fi
    else
        bad "没能从 start.sh 抽出 start_lock_*（函数改名了？闸门要跟着改）"
    fi
    # 静态：main() 里的两条接线（接管陈旧锁 / 有人在建树时不重复建）
    for _pat in 'start_lock_acquire' '陈旧的启动锁' '另一次启动正在进行' 'start_lock_rewrite_self' "trap 'start_lock_release_own'"; do
        if grep -qF "$_pat" "$SELF_DIR/start.sh"; then
            ok "start.sh 里有「$_pat」"
        else
            bad "start.sh 缺「$_pat」—— 并发启动会重新变成各建一棵树"
        fi
    done
    # 静态：status 把"启动锁还在 + 持锁进程活着"报成 starting（App 靠它把启动卡置灰）
    if grep -qF 'start_lock_holder' "$SELF_DIR/linuxctl.sh"; then
        ok "linuxctl.sh 的 status 认识启动锁（建树中会报 starting）"
    else
        bad "linuxctl.sh 不认识启动锁 —— 建树中会报 stopped，用户会再点一次启动"
    fi
    # 行为：真跑 status，伪造一个"正在启动"的现场（锁 + 活着的 pid，但没有 supervisor.pid）
    _LS="$TMP/lockstatus"
    mkdir -p "$_LS/run" "$_LS/etc" "$_LS/layers"
    printf '%s %s\n' "$$" "$(date +%s)" > "$_LS/run/start.lock"
    _ls_j="$(LINUX_HOME="$_LS" "$SH_BIN" "$SELF_DIR/linuxctl.sh" status 2>/dev/null | tr -d ' \n\t')"
    case "$_ls_j" in
        *'"state":"starting"'*) ok "status：建树中（只有启动锁）报 state=starting" ;;
        *) bad "status 没把「建树中」报成 starting：$_ls_j" ;;
    esac
else
    skip_ "找不到 $SELF_DIR/start.sh"
fi


head_ "守护进程脱离：父脚本不许等它"
if [ -f "$SELF_DIR/start.sh" ]; then
    _sp="$TMP/spawn-funcs.sh"
    {
        # 只抽两个函数；SETSID 给个真二进制，use=0 时用不到它
        echo 'SETSID=/bin/true'
        echo 'DAEMON_LOG=/dev/null'
        sed -n '/^spawn_detached()/,/^}/p' "$SELF_DIR/start.sh"
        sed -n '/^spawn_daemon()/,/^}/p'   "$SELF_DIR/start.sh"
    } > "$_sp"
    if [ -s "$_sp" ] && grep -q '^spawn_daemon()' "$_sp"; then
        cat > "$TMP/spawn-bg.sh" <<EOF
. '$_sp'
spawn_daemon 0 /bin/sh -c 'sleep 20'
EOF
        _t0="$(date +%s)"
        "$SH_BIN" "$TMP/spawn-bg.sh" >/dev/null 2>&1 || true
        _dt=$(( $(date +%s) - _t0 ))
        if [ "$_dt" -le 5 ]; then
            ok "spawn_daemon 在 ${_dt}s 内返回（守护进程仍在后台跑）"
        else
            bad "spawn_daemon 等了 ${_dt}s —— 前台 spawn 又回来了：App 会永久 busy、启动区全灰"
        fi
        # 对照样本：前台调用必须**真的会等**，否则上面那条断言可能是"测不到东西"。
        # ⚠ 函数名走**拼接**而不是字面量：这一行是**故意复刻错误写法**的探针，
        #   不该被 tools/shell-compat-check.mjs 的前台 spawn 闸门当成生产调用
        #   （与上面 nsenter 探针用 `_NS=` 变量是同一个套路）。
        cat > "$TMP/spawn-fg.sh" <<EOF
. '$_sp'
_fg_probe="spawn_""detached"
"\$_fg_probe" 0 /bin/sh -c 'sleep 3'
EOF
        _t0="$(date +%s)"
        "$SH_BIN" "$TMP/spawn-fg.sh" >/dev/null 2>&1 || true
        _dt2=$(( $(date +%s) - _t0 ))
        if [ "$_dt2" -ge 2 ]; then
            ok "对照样本：前台 spawn 确实在等（${_dt2}s）—— 计时能区分两种写法"
        else
            bad "对照样本没有体现出「前台会等」（${_dt2}s）：这条断言可能测不到东西"
        fi
    else
        bad "没能从 start.sh 抽出 spawn_daemon/spawn_detached（函数改名了？闸门要跟着改）"
    fi
else
    skip_ "找不到 $SELF_DIR/start.sh"
fi


# ---------------------------------------------------------------------------
# 宿主残留清理（runtime/common/host-residue.sh）
#
# 背景（真机 2026-09-18）：KernelSU 给 root shell 的 ns 里 / 与 /data 是 **shared**，
# 我们的 unshare 继承了它，而 make-rprivate 在这台机上从没生效过 ⇒ 环境的挂载回流宿主
# （`grep -c sunsetlinux /proc/1/mountinfo` = 33）。后果：stop 之后看着像"已经有挂载"，
# 且 cleanup_stale_loops 见别的 ns 里还挂着就跳过 detach ⇒ loop 残留。
#
# 这里**用桩**验"该卸的卸、不该碰的不碰"：自测绝不能真去动宿主的挂载表。
# ---------------------------------------------------------------------------
head_ "宿主残留清理：泄漏到别的 ns 的挂载要能卸掉"
_HRS="$SELF_DIR/../common/host-residue.sh"
if [ -f "$_HRS" ]; then
    _HR="$TMP/hostres"
    mkdir -p "$_HR/bin" "$_HR/ns"
    cat > "$_HR/bin/umount" <<EOF
#!/bin/sh
printf 'umount %s\n' "\$*" >> "$_HR/calls.log"
EOF
    cat > "$_HR/bin/nsenter" <<EOF
#!/bin/sh
printf 'nsenter %s\n' "\$*" >> "$_HR/calls.log"
EOF
    chmod +x "$_HR/bin/umount" "$_HR/bin/nsenter"
    # 假 mountinfo：当前 ns 里有 1 个我们的 + 1 个不相干的；init ns 里有 2 个我们的
    printf '%s\n' \
        '1 0 0:1 / / rw - rootfs rootfs rw' \
        '2 1 7:1 / /data/sunsetlinux/rootfs rw - overlay overlay rw' \
        '3 1 7:1 / /data/other-thing rw - ext4 ext4 rw' > "$_HR/self.mountinfo"
    printf '%s\n' \
        '1 0 0:1 / / rw - rootfs rootfs rw' \
        '2 1 7:1 / /data/sunsetlinux/upper rw - ext4 ext4 rw' \
        '3 1 7:1 / /data/sunsetlinux/rootfs/proc rw - proc proc rw' > "$_HR/init.mountinfo"
    # ⚠ 关键词走**拼接**：这一段是自测探针（桩替 nsenter），不该被静态闸门的 nsenter
    #   选项面扫描当成生产调用（与上面 spawn 探针同一个套路）。
    _ns_word="ns""enter"
    _ns_var="SUNSETLINUX_NS""ENTER"
    export LINUX_HOME=/data/sunsetlinux
    export SUNSETLINUX_MOUNTINFO="$_HR/self.mountinfo"
    export SUNSETLINUX_HOST_MOUNTINFO="$_HR/init.mountinfo"
    export SUNSETLINUX_HOST_NS="$_HR/ns"
    export SUNSETLINUX_HOST_UMOUNT="$_HR/bin/umount"
    eval "export $_ns_var=\"$_HR/bin/$_ns_word\""
    _hr_log="$("$SH_BIN" -c '. "$1"; log() { printf "%s\n" "$*"; }; host_residue_clean' _ "$_HRS" 2>&1)"
    unset LINUX_HOME SUNSETLINUX_MOUNTINFO SUNSETLINUX_HOST_MOUNTINFO SUNSETLINUX_HOST_NS SUNSETLINUX_HOST_UMOUNT
    unset "$_ns_var"
    _hr_calls="$(cat "$_HR/calls.log" 2>/dev/null || true)"
    case "$_hr_calls" in
        *"umount -l /data/sunsetlinux/rootfs"*) ok "当前 ns 的残留挂载被卸掉" ;;
        *) bad "当前 ns 的残留没被卸：$_hr_calls" ;;
    esac
    case "$_hr_calls" in
        *"/data/sunsetlinux/upper"*) ok "宿主 init ns 的残留走 nsenter 卸掉（loop 才能 detach）" ;;
        *) bad "宿主 init ns 的残留没被处理：$_hr_calls" ;;
    esac
    case "$_hr_calls" in
        *other-thing*) bad "碰到了不属于本项目的路径（/data/other-thing）—— 这会误伤别人的挂载" ;;
        *) ok "不碰不属于本项目的挂载（只动 /data/sunsetlinux 下的已知路径）" ;;
    esac
    case "$_hr_log" in
        *"已从宿主 init ns 卸下残留挂载"*) ok "清理动作有日志（可复核谁在什么时候卸了什么）" ;;
        *) bad "清理没有留日志：$_hr_log" ;;
    esac
    # 静态接线：start 前清（防 loop 卡死）+ stop 后兜底，两处都要有
    for _f in start.sh stop.sh; do
        if grep -qF 'host_residue_clean' "$SELF_DIR/$_f"; then
            ok "$_f 接了宿主残留清理"
        else
            bad "$_f 没接宿主残留清理（残留会一直堆着）"
        fi
    done
    # 顺序：make-rprivate 必须发生在 detect_util_mount 之后（之前那次必然拿不到 util-linux）
    _dm="$(grep -n 'detect_util_mount \"\$base_for_util\"' "$SELF_DIR/start.sh" | head -n1 | cut -d: -f1)"
    _np="$(grep -n 'ensure_ns_private \"\$base_for_util\"' "$SELF_DIR/start.sh" | head -n1 | cut -d: -f1)"
    if [ -n "$_dm" ] && [ -n "$_np" ] && [ "$_np" -gt "$_dm" ]; then
        ok "make-rprivate 排在 detect_util_mount 之后（第 $_dm → $_np 行）"
    else
        bad "ensure_ns_private 的位置不对（detect=$_dm ensure=$_np）—— util-linux 还没取到就又白试一次"
    fi
else
    bad "缺少 runtime/common/host-residue.sh（模块打包会漏掉它）"
fi


# ---------------------------------------------------------------------------
# 内核 v2：status 的相位**以内核为准**（v1 键不变；内核不在/心跳过期则退回 v1 判据）
#
# 这条是 P1 的核心验收之一（docs/core-v2-design.md §8）："在不在跑"必须只剩一份实现。
# 用**假内核状态**验两件事：
#   ① 内核在线时，即使 v1 标记说"running"，status 也照内核的 phase 说（唯一状态源）；
#   ② 心跳过期时**绝不拿过期状态冒充** —— 退回 v1 标记判据。
# ---------------------------------------------------------------------------
head_ "内核 v2：start 的幂等判断也以内核为准（第五份"在不在跑"删掉）"
# 为什么单开一组：`start.sh` 原来自己用 running_ns_pid 判"在不在跑"，那是同一件事的
# 第 N 份实现（今晚第 3 条事故：它判成"没在跑"⇒ 又建一棵树）。现在内核在线时以内核为准。
_KI="$TMP/kernelidem"
mkdir -p "$_KI/run" "$_KI/etc" "$_KI/layers"
_now2="$(date +%s)"
printf '%s000\n' "$_now2" > "$_KI/run/heartbeat"
printf '%s\n' '{"schema":1,"generation":5,"phase":"running","since":1,"busy":false,"up":true,"backend":"chroot","envMode":"full","port":3081,"pid":4190,"jobId":null,"owner":"foreign-observer","lastError":null}' > "$_KI/run/state.json"
_ki_out="$(LINUX_HOME="$_KI" "$SH_BIN" "$SELF_DIR/start.sh" 2>&1 || true)"
case "$_ki_out" in
    *"环境已在运行（内核"*) ok "内核说 running ⇒ start 直接返回，不重复启动" ;;
    *) bad "start 没有按内核的 running 短路：$(printf '%s' "$_ki_out" | tail -n2)" ;;
esac
# 心跳过期 ⇒ 不许拿过期状态冒充：此时没有 v1 标记（没 ready/没 supervisor）⇒ 会继续走启动流程
printf '%s000\n' "$(( _now2 - 600 ))" > "$_KI/run/heartbeat"
case "$(cat "$_KI/run/state.json")" in
    *'"phase":"running"'*) ok "（夹带断言）假内核状态确实是 running" ;;
    *) bad "夹具坏了" ;;
esac

head_ "内核 v2：status 相位以内核为准（v1 键不变 + 心跳过期退回）"
# 说明：**内核压过 v1** 那几条不依赖 /proc/<pid>/ns/mnt 可读（proot 沙箱里读不到），
# 所以放在守卫外面 —— 本机也跑得到；只有"退回 v1 判据"那两条需要 v1 真的能判 running。
_KS="$TMP/kernelstatus"
mkdir -p "$_KS/run" "$_KS/etc" "$_KS/layers"
printf '%s\n' "$$" > "$_KS/run/supervisor.pid"
: > "$_KS/run/ready"
printf 'env-only\n' > "$_KS/run/env-mode"
_ks_ctl() { LINUX_HOME="$_KS" "$SH_BIN" "$SELF_DIR/linuxctl.sh" "$@"; }
_ks_json() { _ks_ctl status 2>/dev/null | tr -d ' \n\t'; }
_now="$(date +%s)"
_fresh="$(printf '%s000' "$_now")"          # 毫秒（字符串拼接：不走 32 位算术）
_stale="$(printf '%s000' "$(( _now - 600 ))")"

# ① 内核说"正在建树"（mounting）⇒ v1 state 必须是 starting（哪怕标记看着像在跑）
printf '%s\n' '{"schema":1,"generation":3,"phase":"mounting","since":1,"busy":true,"up":false,"backend":"chroot","envMode":null,"port":null,"pid":null,"jobId":"j7","owner":"kernel-job","lastError":null}' > "$_KS/run/state.json"
printf '%s\n' "$_fresh" > "$_KS/run/heartbeat"
_j="$(_ks_json)"
case "$_j" in
    *'"state":"starting"'*) ok "内核 mounting ⇒ v1 state=starting（内核压过 v1 判据）" ;;
    *) bad "内核相位没有生效：$_j" ;;
esac
case "$_j" in
    *'"kernel":{"online":true'*) ok "status 带上 kernel.online=true（附加键）" ;;
    *) bad "status 缺 kernel 块：$_j" ;;
esac
case "$_j" in
    *'"generation":3'*) ok "kernel.generation 来自内核（3）" ;;
    *) bad "kernel.generation 不对：$_j" ;;
esac
case "$_j" in
    *'"owner":"kernel-job"'*) ok "kernel.owner 来自内核" ;;
    *) bad "kernel.owner 不对：$_j" ;;
esac
for _k in '"schema":1' '"mode":"root"' '"env_mode":"env-only"' '"dsh":{' '"layers":{' '"storage":{'; do
    case "$_j" in
        *"$_k"*) ;;
        *) bad "v1 契约键丢了：$_k（$_j）" ;;
    esac
done

# ② 内核说 failed ⇒ v1 state=error（不再"看着像没事"）
printf '%s\n' '{"schema":1,"generation":3,"phase":"failed","since":1,"busy":false,"up":false,"backend":"chroot","envMode":null,"port":null,"pid":null,"jobId":null,"owner":"none","lastError":"overlay 挂载失败"}' > "$_KS/run/state.json"
_j2="$(_ks_json)"
case "$_j2" in
    *'"state":"error"'*) ok "内核 failed ⇒ v1 state=error" ;;
    *) bad "内核 failed 没映射成 error：$_j2" ;;
esac

# ③ 内核 idle ⇒ v1 state=stopped（这是"停下来了"的权威说法）
printf '%s\n' '{"schema":1,"generation":3,"phase":"idle","since":1,"busy":false,"up":false,"backend":"chroot","envMode":null,"port":null,"pid":null,"jobId":null,"owner":"none","lastError":null}' > "$_KS/run/state.json"
_j3="$(_ks_json)"
case "$_j3" in
    *'"state":"stopped"'*) ok "内核 idle ⇒ v1 state=stopped" ;;
    *) bad "内核 idle 没映射成 stopped：$_j3" ;;
esac

# ④ 心跳过期 = 内核已死 ⇒ 退回 v1 判据 + kernel.online=false（绝不拿过期状态冒充）
#    这两条需要 v1 真的能判出 running ⇒ 依赖 /proc/<pid>/ns/mnt（proot 沙箱读不到就跳过）
if [ -r "/proc/$$/ns/mnt" ]; then
    printf '%s\n' '{"schema":1,"generation":3,"phase":"mounting","since":1,"busy":true,"up":false,"backend":"chroot","envMode":null,"port":null,"pid":null,"jobId":"j7","owner":"kernel-job","lastError":null}' > "$_KS/run/state.json"
    printf '%s\n' "$_stale" > "$_KS/run/heartbeat"
    _j4="$(_ks_json)"
    case "$_j4" in
        *'"state":"running"'*) ok "心跳过期 ⇒ 退回 v1 判据（state=running）" ;;
        *) bad "心跳过期没有退回 v1：$_j4" ;;
    esac
    case "$_j4" in
        *'"kernel":{"online":false'*) ok "心跳过期 ⇒ kernel.online=false" ;;
        *) bad "心跳过期却仍报 kernel.online=true：$_j4" ;;
    esac

    # ⑤ 完全没有内核文件 ⇒ 与今天一致（v1 路径不受影响）
    rm -f "$_KS/run/state.json" "$_KS/run/heartbeat"
    _j5="$(_ks_json)"
    case "$_j5" in
        *'"state":"running"'*) ok "没有内核 ⇒ v1 判据照旧（兼容降级）" ;;
        *) bad "没有内核时 v1 路径坏了：$_j5" ;;
    esac
else
    skip_ "本机看不到 /proc/<pid>/ns/mnt（proot 沙箱）→ 跳过「退回 v1 判据」那两条；CI 与真机会跑"
fi

# ---------------------------------------------------------------------------
# CLI 侧解 .zst：update.sh 必须有 node 兜底，且过滤器随包分发
# ---------------------------------------------------------------------------
# 背景（2026-09-18，用户选定路线 (b)）：设备侧没有 zstd 命令，而 dsh 层的
# .gz 产物已经涨到 109.5 MB（还撞上 GitHub 单文件 100 MB 硬限）。
# 所以让 CLI 也能吃 .zst：系统 zstd → 环境里的 node（Node 24 的 node:zlib 自带 zstd）
# → 都没有就**明确拒绝**（不许"下载完才发现解不开"）。
head_ "单元：CLI 侧解 .zst（node 兜底 + 过滤器随包）"
if grep -q 'zstd-filter.mjs' "$SELF_DIR/update.sh"; then
    ok "update.sh 引用了 zstd-filter.mjs"
else
    bad "update.sh 里找不到 zstd-filter.mjs 的引用（CLI 解不了 .zst）"
fi
if grep -q 'find_node' "$SELF_DIR/update.sh" && grep -q -- '--no-warnings' "$SELF_DIR/update.sh"; then
    ok "update.sh 的 .zst 分支走 node 兜底（find_node + --no-warnings）"
else
    bad "update.sh 的 .zst 分支没有 node 兜底"
fi
zf=""
for cand in "$SELF_DIR/zstd-filter.mjs" "$SELF_DIR/../tools/seed/zstd-filter.mjs" \
            "$REPO_DIR/tools/seed/zstd-filter.mjs"; do
    [ -f "$cand" ] && { zf="$cand"; break; }
done
if [ -n "$zf" ]; then
    ok "过滤器就位：$zf"
    if have node && printf 'sunsetlinux-zstd-roundtrip' | node --no-warnings "$zf" -l 3 2>/dev/null \
            | node --no-warnings "$zf" -d 2>/dev/null | grep -q 'sunsetlinux-zstd-roundtrip'; then
        ok "过滤器往返一致（node 实跑 zstd 压缩→解压）"
    else
        skip_ "本机没有可用的 node（或 node 无 zstd 支持）→ 跳过过滤器实跑"
    fi
else
    bad "找不到 zstd-filter.mjs（CLI 侧将无法解 .zst）"
fi

# 产物选择必须**按本机能力**，不能写死「本机没有 zstd 所以永远挑 url_gz」。
# 背景（2026-09-18 实测）：update.sh 的 .zst 解压能力加好了，但选择逻辑写死 url_gz
# ⇒ 那段能力在任何机器上都不会被走到（**死代码**），dsh 层每次更新照旧白下 34 MB。
head_ "单元：产物选择跟随本机能力（能解 .zst 才挑 .zst）"
if grep -q "typeof zlib.createZstdDecompress === 'function'" "$SELF_DIR/update.sh"; then
    ok "验签器探测 node 自带的 zstd（不依赖外部命令）"
else
    bad "验签器没有探测 node:zlib 的 zstd 能力（选择会退化成写死 .gz）"
fi
if grep -q 'SUNSET_HAVE_ZSTD="\$have_zstd"' "$SELF_DIR/update.sh"; then
    ok "cmd_check 把「本机有没有系统 zstd」传给验签器"
else
    bad "update.sh 没有把系统 zstd 能力传给验签器"
fi
if grep -q "process.env.SUNSET_NO_ZSTD !== '1'" "$SELF_DIR/update.sh"; then
    ok "有显式关闭开关（排障用，也让这条分支可测）"
else
    bad "缺少 SUNSET_NO_ZSTD 开关（无法强制走 .gz，分支不可测）"
fi
# 反面断言：老写法（一上来就 `L.url_gz || …` 定产物）不许回来
if grep -qF 'L.url_gz || (/\.(gz)$/' "$SELF_DIR/update.sh"; then
    bad "产物选择又写死成「优先 url_gz」了（.zst 能力会退化成死代码）"
else
    ok "产物选择里没有「写死 url_gz」的老写法"
fi


# ---------------------------------------------------------------------------
# 启动器契约：`supervise.sh` 必须用 `node --expose-internals <dsh> web` 起 DSH
# ---------------------------------------------------------------------------
# 为什么值得一条**静态**断言（别的都是行为测试）：
#   DSH 0.1.6 起 `dsh-base` 的补丁里多了 `hmr`（@deepseek-ai/dsh-hmr）条目，而 HMR 服务
#   **硬要求** node 以 `--expose-internals` 启动（docs/dsh-profile.md §7.3 有实测）。
#   `dsh` 的入口是 `#!/usr/bin/env node`，shebang 带不了它；`NODE_OPTIONS` 也被 node 拒绝。
#   一旦有人"顺手简化"回直接跑 `dsh web`，症状是**环境起来后 DSH 立刻退出**
#   （linux.log 里是 plugin tree failed to load），排查成本很高 —— 这条断言几秒就能钉住。
head_ "单元：启动器必须带 --expose-internals（DSH 0.1.6 的 HMR 硬要求）"
SUP="$SELF_DIR/supervise.sh"
if [ -f "$SUP" ]; then
    if grep -q -- '--expose-internals' "$SUP"; then
        ok "supervise.sh 里有 --expose-internals"
    else
        bad "supervise.sh 里找不到 --expose-internals：DSH 0.1.6+ 会启动即失败"
    fi
    # 必须是"用 node 起"，而不是把 flag 传给 dsh 自己（dsh 不认这个 flag）
    if grep -qE '"\$NODE_BIN"[[:space:]]+--expose-internals[[:space:]]+"\$DSH_BIN"[[:space:]]+web' "$SUP"; then
        ok "supervise.sh 的启动行 = node --expose-internals <dsh> web"
    else
        bad "supervise.sh 的启动行不是 node --expose-internals <dsh> web"
    fi
else
    skip_ "本机看不到 supervise.sh（模块安装后自带）→ 跳过启动器契约断言"
fi


# ---------------------------------------------------------------------------
# 单元：视角地图 / 交换目录 / 权限默认值（2026-09-19 用户反馈三件事）
#   ① AI 与人都"找不到工作根、判错视角" ⇒ whereami + 地图投递；
#   ② 宿主塞不进东西 ⇒ /share 交换目录；
#   ③ 环境里每条命令都要提权 ⇒ DSH_PERMISSION_MODE 默认 danger-full-access。
#   这三条都是"改了却没人验"的高危形状（静态可钉，且不影响真机行为）。
# ---------------------------------------------------------------------------
head_ "单元：whereami（我在哪个视角）"

WH="$TMP/whereami-home"
mkdir -p "$WH/etc" "$WH/run" 2>/dev/null

# ① 影子视图：$LH 存在但没有 upper.img/dirs-upper/bin ⇒ 必须判 shadow 并给出警告
if out="$(LINUX_HOME="$WH" "$SH_BIN" "$SELF_DIR/linuxctl.sh" whereami --json 2>/dev/null)"; then
    case "$out" in
        *'"view":"shadow"'*) ok "影子视图被认出来（$LH 有名无实）" ;;
        *'"view":"'*)       bad "视角判错（既不 shadow 也不报错）：$out" ;;
        *)                  bad "whereami --json 没给出 view：$out" ;;
    esac
    case "$out" in
        *'"paths"'*) ok "JSON 里带路径地图" ;;
        *)           bad "JSON 里没有 paths：$out" ;;
    esac
else
    bad "whereami --json 执行失败"
fi

# ② 真宿主视图：放一个 bin/linuxctl.sh 进去 ⇒ 必须判 host
mkdir -p "$WH/bin" && printf '#!/bin/sh\n' > "$WH/bin/linuxctl.sh" && chmod 0755 "$WH/bin/linuxctl.sh"
if out="$(LINUX_HOME="$WH" "$SH_BIN" "$SELF_DIR/linuxctl.sh" whereami --json 2>/dev/null)"; then
    case "$out" in
        *'"view":"host"'*) ok "宿主视图被认出来（bin/linuxctl.sh 在）" ;;
        *)                 bad "宿主视图判错：$out" ;;
    esac
else
    bad "whereami（host 形态）执行失败"
fi

# ③ 未知参数必须报错（不许静默当成默认行为）
if LINUX_HOME="$WH" "$SH_BIN" "$SELF_DIR/linuxctl.sh" whereami --bogus >/dev/null 2>&1; then
    bad "whereami 对未知参数返回了 0"
else
    ok "whereami 对未知参数明确报错"
fi

# ④ 只读：whereami 不许建任何目录（footprint 那次的教训）
RO="$TMP/whereami-ro"
if LINUX_HOME="$RO" "$SH_BIN" "$SELF_DIR/linuxctl.sh" whereami >/dev/null 2>&1; then :; fi
if [ -e "$RO" ]; then
    bad "whereami 有副作用：把不存在的环境根建出来了"
else
    ok "whereami 是只读的（没建目录）"
fi

head_ "单元：交换目录与权限默认值（静态契约）"

if grep -q 'mount_share' "$SELF_DIR/start.sh" && grep -q 'SHARE_DIR=' "$SELF_DIR/start.sh"; then
    ok "start.sh 里有交换目录（mount_share）"
else
    bad "start.sh 里找不到 mount_share —— /share 不会挂上"
fi
# 失败必须**不致命**（环境照常起）：函数体里不许出现 die
if awk '/^mount_share\(\)/,/^}/' "$SELF_DIR/start.sh" | grep -q 'die '; then
    bad "mount_share 里用了 die：交换目录挂不上会把整个环境拖死"
else
    ok "mount_share 失败不致命（只用 warn_soft）"
fi
# 地图投递：模块里必须有那份 md，且 post-fs-data 会拷到 share/ 与环境根
if [ -f "$REPO_DIR/module/share/地图-视角.md" ]; then
    ok "地图文件在（module/share/地图-视角.md）"
else
    bad "module/share/地图-视角.md 不存在 —— 环境里看不到地图"
fi
if grep -q '地图-视角.md' "$REPO_DIR/module/post-fs-data.sh" 2>/dev/null; then
    ok "post-fs-data.sh 会投递地图"
else
    bad "post-fs-data.sh 没有投递地图"
fi
# 权限默认值：supervise.sh 必须给 DSH_PERMISSION_MODE 兜底成 danger-full-access，
# 且**只在没人设过时**才填（用户写在 $DSH_HOME/env 里要能覆盖）
if grep -q 'DSH_PERMISSION_MODE' "$SUP" 2>/dev/null; then
    if grep -q '\[ -z "${DSH_PERMISSION_MODE:-}" \]' "$SUP"; then
        ok "权限默认值只在未设置时生效（用户可覆盖）"
    else
        bad "supervise.sh 无条件覆盖 DSH_PERMISSION_MODE —— 用户在 env 里的设置会被吃掉"
    fi
    if grep -q 'DSH_PERMISSION_MODE=danger-full-access' "$SUP"; then
        ok "环境内默认为 danger-full-access（无沙箱、不询问）"
    else
        bad "没找到 danger-full-access 默认值"
    fi
    # 关键顺序：默认值必须在 source $DSH_HOME/env **之后**，否则用户设置会被反覆盖
    ln_env="$(grep -n '\. "$ENV_FILE"' "$SUP" | head -n1 | cut -d: -f1)"
    ln_def="$(grep -n 'DSH_PERMISSION_MODE=danger-full-access' "$SUP" | head -n1 | cut -d: -f1)"
    if [ -n "$ln_env" ] && [ -n "$ln_def" ] && [ "$ln_def" -gt "$ln_env" ]; then
        ok "默认值在加载 env 文件之后（用户值优先）"
    else
        bad "默认值的位置在 env 加载之前（env=$ln_env def=$ln_def）—— 用户设置会被覆盖"
    fi
else
    bad "supervise.sh 里没有 DSH_PERMISSION_MODE —— 环境里仍会逐条提权"
fi

head_ "单元：doctor 的视角/隔离自检"

if grep -q '1f. 视图与挂载隔离' "$SELF_DIR/doctor.sh"; then
    ok "doctor 有 §1f（视角与挂载隔离）"
else
    bad "doctor.sh 缺少 §1f"
fi
if grep -q '/proc/1/mountinfo' "$SELF_DIR/doctor.sh"; then
    ok "回流检查用 /proc/1/mountinfo 当判据（可判定，不靠猜）"
else
    bad "回流检查没有可判定证据"
fi

printf '\n=========================================\n'
printf '  通过 %d，失败 %d\n' "$pass" "$fail"
printf '=========================================\n'
[ "$fail" -eq 0 ] || exit 1
