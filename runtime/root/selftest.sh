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

SELF_PATH="${BASH_SOURCE[0]:-$0}"   # mksh 下 BASH_SOURCE 未定义 → 退回 $0
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
        got="$(bash -c 'set -uo pipefail; . "$1"; layer_format "$2"' _ "$h" "$FIXTURES/$1" 2>/dev/null)"
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
if out="$(LINUX_HOME="$LH" bash "$SELF_DIR/linuxctl.sh" update dsh "$FIXTURES/erofs-head.bin" 2>&1)"; then
    case "$out" in
        *'"ok":true'*'"format":"erofs"'*) ok "合法 erofs 被接受" ;;
        *) bad "合法 erofs 未被接受：$out" ;;
    esac
else
    bad "合法 erofs 被拒绝（这就是偏移 1024 那个 bug 的症状）：$(printf '%s' "$out" | tail -2)"
fi
# 压缩产物必须被**拒绝** —— 这是**正确**行为：解压是客户端（App）的职责，
# linuxctl 只接受可直接挂载的裸镜像。若这条变成"接受"，说明校验被削弱了。
if out="$(LINUX_HOME="$LH" bash "$SELF_DIR/linuxctl.sh" update dsh "$FIXTURES/zstd-head.bin" 2>&1)"; then
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
    r="$(bash -c 'set -uo pipefail; . "$1"; DSH_ST_JQ_OK=0; if dsh_jq_ok; then echo WRONG; else echo RIGHT; fi' _ "$h")"
    [ "$r" = "RIGHT" ] && ok "缓存 0 时 dsh_jq_ok 判为不可用" || bad "缓存 0 时 dsh_jq_ok 竟判为可用"
    r="$(bash -c 'set -uo pipefail; . "$1"; DSH_ST_JQ_OK=1; if dsh_jq_ok; then echo RIGHT; else echo WRONG; fi' _ "$h")"
    [ "$r" = "RIGHT" ] && ok "缓存 1 时 dsh_jq_ok 判为可用" || bad "缓存 1 时 dsh_jq_ok 判为不可用"
done

head_ "集成：update --version 落盘 + state.json + find_layer 版本优先"
LH2="$TMP/linux2"; mkdir -p "$LH2"/{layers,etc,run,bin,cache,upper,work,rootfs,layers-mnt}
# 用夹具头当"层"，只验命名/版本/state 逻辑（格式判定已在上面的端到端验过）
if out="$(LINUX_HOME="$LH2" bash "$SELF_DIR/linuxctl.sh" update dsh "$FIXTURES/erofs-head.bin" --version 1.0.0 2>&1)"; then
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
if LINUX_HOME="$LH2" bash "$SELF_DIR/linuxctl.sh" update dsh "$FIXTURES/erofs-head.bin" --version 2.0.0 >/dev/null 2>&1; then
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
    got="$(bash -c 'set -uo pipefail; . "$1"; find_layer dsh' _ "$fl" 2>/dev/null)"
    case "$got" in
        */dsh-1.0.0.erofs) ok "state.json 优先：find_layer 返回 1.0.0（而非最高的 2.0.0）" ;;
        *) bad "state.json 版本优先失效，find_layer 返回：$got" ;;
    esac
fi
# rollback 子命令必须存在且能把指向改回去
if LINUX_HOME="$LH2" bash "$SELF_DIR/linuxctl.sh" update dsh "$FIXTURES/erofs-head.bin" --version 2.0.0 >/dev/null 2>&1; then
    if out="$(LINUX_HOME="$LH2" bash "$SELF_DIR/linuxctl.sh" rollback dsh 2>&1)"; then
        case "$out" in
            *'"to":"1.0.0"'*) ok "rollback 把 dsh 指回 1.0.0" ;;
            *) bad "rollback 目标不对：$out" ;;
        esac
    else
        bad "rollback 失败：$(printf '%s' "$out" | tail -2)"
    fi
fi

printf '\n=========================================\n'
printf '  通过 %d，失败 %d\n' "$pass" "$fail"
printf '=========================================\n'
[ "$fail" -eq 0 ] || exit 1
