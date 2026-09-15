#!/usr/bin/env bash
# =============================================================================
# sunsetlinux · tools/proot-runtime/mkproot-runtime.sh
#
# 打包**非 root（proot）模式的运行时脚本包** → dist/sunsetlinux-proot-runtime.tar.gz
#
# ## 为什么需要这个脚本（它修的是三个真实错位）
#   1. **包内顶层目录名**必须与产品名一致。旧产物是手工打的，顶层还是
#      `dshroid-proot-runtime/`（改名前的名字）—— 用户按文档解包后会看到
#      一个名字对不上的目录，且将来写脚本的人无从判断哪个才对。
#   2. **布局**必须是 `bin/…`：App 的契约候选是
#      `$APP_FILES/sunsetlinux/bin/linuxctl`，其次 `…/bin/linuxctl.sh`
#      （见 app/…/core/LinuxCtl.kt 的 linuxctlCandidates）。旧包把 4 个 .sh
#      直接放在顶层，解包后 App **一个都找不到**，只会显示"环境尚未部署"。
#   3. **可复现**：旧的 tar 里带 uid/gid/mtime，每次重打哈希都变。这里固定
#      `--sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner` 且 gzip 用 -n。
#
# ## 用法
#   bash tools/proot-runtime/mkproot-runtime.sh [--version 1.1.0] [--out-dir dist]
#
# ## ⚠️ 已知未完成（见 docs/STATUS.md「proot 模式」）
#   本包**打出来不等于能用**：`linuxctl.sh` / `start.sh` 目前是 bash 脚本
#   （用了数组、`=~`、进程替换），而设备侧只有 /system/bin/sh（mksh），
#   且 Android 10+ 禁止 execve App 私有目录里的脚本（只能 `sh <脚本>`）。
#   也就是说这个包要在真机上跑起来，还需要「把宿主侧脚本改成 POSIX」或
#   「随包带一个可作为 native lib 执行的 bash」。本脚本只负责**把包打对**。
# =============================================================================
set -euo pipefail

SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_DIR="$(cd -- "$SELF_DIR/../.." && pwd -P)"
SRC_DIR="$REPO_DIR/runtime/proot"

PKG_NAME="sunsetlinux-proot-runtime"
VERSION="1.1.0"
OUT_DIR="$REPO_DIR/dist"

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="${2:?}"; shift 2 ;;
    --out-dir) OUT_DIR="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

die() { echo "[mkproot-runtime][错误] $*" >&2; exit 1; }
log() { echo "[mkproot-runtime] $*"; }

[ -d "$SRC_DIR" ] || die "找不到 proot 运行时源码目录：$SRC_DIR"

# 要进包的脚本：由目录派生，**不手写清单**（手写清单必然漂移）
SCRIPTS=""
for f in "$SRC_DIR"/*.sh; do
  [ -f "$f" ] || continue
  SCRIPTS="$SCRIPTS $(basename "$f")"
done
[ -n "$SCRIPTS" ] || die "$SRC_DIR 里没有 .sh"

# 契约要求的目标文件必须都在，缺了就当场失败（而不是打出一个残包）
for req in linuxctl.sh start.sh entry.sh; do
  case " $SCRIPTS " in *" $req "*) ;; *) die "缺少必需脚本：$req" ;; esac
done
[ -f "$SRC_DIR/README.md" ] || die "缺少 README.md"

mkdir -p "$OUT_DIR"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/$PKG_NAME.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

TOP="$STAGE/$PKG_NAME"
mkdir -p "$TOP/bin"

# --- 脚本 → bin/（保留可执行位）---------------------------------------------
for s in $SCRIPTS; do
  install -m 0755 "$SRC_DIR/$s" "$TOP/bin/$s"
  log "  bin/$s"
done

# --- 契约路径 bin/linuxctl：与模块侧一致，用**相对软链**指向 .sh ---------------
# App 首选 `bin/linuxctl`，找不到才退到 `bin/linuxctl.sh`；两者都给上，
# 这样"契约路径"在 proot 模式下也成立（与 KernelSU 模块的做法一致）。
ln -s linuxctl.sh "$TOP/bin/linuxctl"

# --- 元数据 -----------------------------------------------------------------
# VERSION 的字段是给设备侧 selftest / doctor 读的，改动要同步那两个脚本
cat > "$TOP/VERSION" <<EOF
$PKG_NAME $VERSION
mode=proot
schema=1
token_capture=entry.sh (§3.3)
EOF

install -m 0644 "$SRC_DIR/README.md" "$TOP/README.md"

# 权限显式化：本机的 umask（0077）会产出 0700/0600，解包后 App 虽以同一 uid
# 运行还能读，但**目录 0700 + 文件 0600** 会让 selftest 里的可读性检查失败，
# 也不符合"发行物"的预期。这里统一成 0755 / 0644（脚本本身已是 0755）。
chmod 0755 "$TOP" "$TOP/bin"
chmod 0644 "$TOP/VERSION" "$TOP/README.md"

# --- 打包（可复现：排序 + 固定 mtime/uid/gid + gzip -n）----------------------
OUT="$OUT_DIR/$PKG_NAME.tar.gz"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
    -C "$STAGE" -cf - "$PKG_NAME" | gzip -9n > "$OUT"

# --- 自检：顶层目录名、bin/ 布局、linuxctl 软链、可执行位 ---------------------
tar -tzf "$OUT" >/dev/null 2>&1 || die "产物不是合法 tar.gz：$OUT"
LIST="$(tar -tzf "$OUT")"
case "$LIST" in
  "$PKG_NAME/"*) ;;
  *) die "顶层目录名不是 $PKG_NAME/（是不是又打错名字了？）" ;;
esac
for need in "$PKG_NAME/bin/linuxctl.sh" "$PKG_NAME/bin/linuxctl" \
            "$PKG_NAME/bin/entry.sh" "$PKG_NAME/bin/start.sh" \
            "$PKG_NAME/VERSION" "$PKG_NAME/README.md"; do
  printf '%s\n' "$LIST" | grep -qx "$need" || die "产物里缺少 $need"
done
printf '%s\n' "$LIST" | grep -q "dshroid" && die "产物里还有旧名 dshroid"

# 可执行位核验：只看**普通文件**（软链在 tar -tv 里以 l 开头，不能拿同一把尺子量）
BAD="$(tar -tvzf "$OUT" | grep -E '^-' | grep -E 'bin/.*\.sh$' | grep -v '^-rwxr-xr-x' || true)"
[ -z "$BAD" ] || die "bin/ 下有 .sh 丢了可执行位：
$BAD"

SHA="$(sha256sum "$OUT" | awk '{print $1}')"
printf '%s\n' "$SHA" > "$OUT.sha256"

log "产物：$OUT  $(( $(stat -c '%s' "$OUT") / 1024 )) KB"
log "sha256：$SHA"
log "布局："
tar -tzf "$OUT" | sed 's/^/    /'
