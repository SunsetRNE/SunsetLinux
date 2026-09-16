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

printf '\n=========================================\n'
printf '  通过 %d，失败 %d\n' "$pass" "$fail"
printf '=========================================\n'
[ "$fail" -eq 0 ] || exit 1
