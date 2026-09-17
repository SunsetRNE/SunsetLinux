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
head_ "doctor §3 的假警报回归（只读挂不上、读写正常）"
UL="$TMP/doctor-upper-env"
mkdir -p "$UL/run" "$UL/etc" "$UL/layers"
# 假镜像：doctor 只看文件存在与大小，不解析内容（ext4 校验走 e2fsck，CI 上通常没有）
dd if=/dev/zero of="$UL/upper.img" bs=1024 count=4 2>/dev/null
UB="$TMP/fakebin-upper"; mkdir -p "$UB"
# 桩：只对 `-o loop,ro` 报错（真机就是"只读挂不上、读写正常"）
printf '%s\n' '#!/bin/sh' \
    'case "$*" in *loop,ro*) echo "mount: Invalid argument" >&2; exit 1 ;; esac' \
    'exit 0' > "$UB/mount"
printf '%s\n' '#!/bin/sh' 'exit 0' > "$UB/umount"
chmod +x "$UB/mount" "$UB/umount"
# 接缝：doctor 默认用 /system/bin/mount（设备侧权威），容器里没有 → 用桩；
# SUNSETLINUX_LOOP_DEV_OK=1 再强制走一遍探针（CI 没有 loop 设备）
ujson="$(SUNSETLINUX_MOUNT="$UB/mount" SUNSETLINUX_UMOUNT="$UB/umount" SUNSETLINUX_LOOP_DEV_OK=1 \
         LINUX_HOME="$UL" "$SH_BIN" "$SELF_DIR/doctor.sh" 2>/dev/null | tail -n1 || true)"
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
uout="$(
    PATH="$UE/bin:$PATH" \
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

printf '\n=========================================\n'
printf '  通过 %d，失败 %d\n' "$pass" "$fail"
printf '=========================================\n'
[ "$fail" -eq 0 ] || exit 1
