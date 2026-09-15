#!/usr/bin/env bash
# ============================================================================
# tools/seed/verify-seed.sh —— 校验 dshroid 离线种子包完整性
# ============================================================================
# 做三件事：
#   1) 若包旁有 .sha256，先验整包 sha256（能最早发现下载被截断/损坏）；
#   2) 解包后按 MANIFEST 逐文件 sha256 校验（sha256sum -c）；
#   3) 结构体检：ubuntu-base 必须是 gzip、node 包必须是 xz（魔数检查），
#      防止"名字对但内容被换成别的东西"。
#
# 退出码：0 全部通过；1 任何一项失败。
# 本脚本不需要 root，也不写设备路径之外的东西（只在临时目录里解包）。
#
# 用法：
#   tools/seed/verify-seed.sh dist/dshroid-seed-2026-09-15.tar.zst
#   tools/seed/verify-seed.sh --dir /data/linux/seeds        # 校验已解开的目录
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -t 2 ]; then C_E=$'\033[31m'; C_I=$'\033[36m'; C_K=$'\033[32m'; C_W=$'\033[33m'; C_R=$'\033[0m'
else C_E=''; C_I=''; C_K=''; C_W=''; C_R=''; fi
die()  { printf '%s[错误]%s %s\n' "$C_E" "$C_R" "$*" >&2; exit 1; }
info() { printf '%s[信息]%s %s\n' "$C_I" "$C_R" "$*" >&2; }
ok()   { printf '%s[完成]%s %s\n' "$C_K" "$C_R" "$*" >&2; }
warn() { printf '%s[警告]%s %s\n' "$C_W" "$C_R" "$*" >&2; }

usage() {
  cat <<'EOF'
用法：tools/seed/verify-seed.sh [选项] <种子包.tar.zst>
      tools/seed/verify-seed.sh --dir <已解开的种子目录>

选项：
  --dir <目录>   校验一个已经解开的种子目录（不经过压缩包）
  --keep         保留临时解包目录（排错用；会打印路径）
  --quiet        只输出结论与失败项
  -h, --help     显示本帮助

校验内容：
  1. 整包 sha256（若存在同名 .sha256 旁文件）
  2. MANIFEST 里每个文件的 sha256（sha256sum -c）
  3. 结构魔数：ubuntu-base 必须是 .tar.gz、node 必须是 .tar.xz
EOF
}

KEEP=0
QUIET=0
DIR_IN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dir)   DIR_IN="${2:?--dir 需要取值}"; shift 2 ;;
    --keep)  KEEP=1; shift ;;
    --quiet) QUIET=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*)      die "未知参数：$1（用 --help 查看用法）" ;;
    *)       break ;;
  esac
done

quiet() { [ "$QUIET" -eq 1 ] && return 0; "$@"; }

need() { command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"; }
need tar; need sha256sum; need awk; need find

# ------------------------------------------------------------ 定位输入 ----
ARCHIVE=""
if [ -z "$DIR_IN" ]; then
  [ $# -ge 1 ] || { usage; exit 1; }
  ARCHIVE="$1"
  [ -f "$ARCHIVE" ] || die "种子包不存在：$ARCHIVE"
  case "$ARCHIVE" in
    *.tar.zst|*.tar.zstd|*.zst) ;;
    *) warn "文件名不是 *.tar.zst（$ARCHIVE），仍按 zstd 压缩处理" ;;
  esac
fi

TMP=""
cleanup() { [ -n "$TMP" ] && [ "$KEEP" -eq 0 ] && rm -rf "$TMP"; return 0; }
trap cleanup EXIT

FAILURES=0
fail() { FAILURES=$((FAILURES + 1)); printf '%s  ✗ %s%s\n' "$C_E" "$*" "$C_R" >&2; }

# ------------------------------------------------- 1. 整包 sha256 校验 ----
if [ -n "$ARCHIVE" ]; then
  printf '%s==>%s 校验整包 sha256\n' "$C_I" "$C_R" >&2
  SIDE="$ARCHIVE.sha256"
  SIZE_H="$(du -h "$ARCHIVE" | awk '{print $1}')"
  if [ -f "$SIDE" ]; then
    ( cd "$(dirname "$ARCHIVE")" && sha256sum -c "$(basename "$SIDE")" ) >/dev/null 2>&1 \
      && ok "整包 sha256 与 $(basename "$SIDE") 一致（$SIZE_H）" \
      || fail "整包 sha256 与 $(basename "$SIDE") 不一致 —— 文件损坏或被替换，不要使用"
  else
    warn "没有 $(basename "$SIDE")，跳过整包校验（仍会校验 MANIFEST 内每个文件）"
    info "整包 sha256：$(sha256sum "$ARCHIVE" | awk '{print $1}')"
  fi

  # --------------------------------------------- 2. 解包 ------------------
  printf '%s==>%s 解包到临时目录\n' "$C_I" "$C_R" >&2
  TMP="$(mktemp -d "${TMPDIR:-/tmp}/dshroid-seed-verify.XXXXXX")"
  UNZSTD=""
  if command -v zstd >/dev/null 2>&1; then
    UNZSTD="zstd -d -q -c"
  elif command -v node >/dev/null 2>&1; then
    UNZSTD="node --no-warnings $SCRIPT_DIR/zstd-filter.mjs -d"
    info "系统没有 zstd 命令，改用 Node 内置 zstd 解压"
  else
    die "既没有 zstd 也没有 node，无法解压。请安装 zstd，或用能解 zstd 的工具先解开再 --dir 校验。"
  fi
  $UNZSTD < "$ARCHIVE" | tar -xf - -C "$TMP" \
    || die "解包失败：压缩包损坏或不是 tar.zst（含 tar 与 zstd 两层错误，已在上面列出）"
  quiet info "解包完成：$TMP"

  # 找到种子根目录（顶层应只有一个 dshroid-seed-* 目录）
  SEED_ROOT=""
  for d in "$TMP"/*/; do
    [ -d "$d" ] || continue
    if [ -f "${d}MANIFEST" ]; then SEED_ROOT="${d%/}"; break; fi
  done
  if [ -z "$SEED_ROOT" ]; then
    # 也接受"解包后平铺"的情况（用户可能 --strip-components 过）
    if [ -f "$TMP/MANIFEST" ]; then SEED_ROOT="$TMP"; else
      die "解包后找不到 MANIFEST（顶层应形如 dshroid-seed-YYYY-MM-DD/MANIFEST）"
    fi
  fi
else
  SEED_ROOT="$(cd "$DIR_IN" && pwd)"
  [ -d "$SEED_ROOT" ] || die "--dir 不是目录：$DIR_IN"
  [ -f "$SEED_ROOT/MANIFEST" ] || die "$SEED_ROOT 下没有 MANIFEST，不确定这是不是种子目录"
  printf '%s==>%s 校验目录 %s\n' "$C_I" "$C_R" "$SEED_ROOT" >&2
fi

# --dir 模式没有解包目录，但下面还要写校验输出文件，这里补一个临时目录
[ -n "$TMP" ] || TMP="$(mktemp -d "${TMPDIR:-/tmp}/dshroid-seed-verify.XXXXXX")"

# ------------------------------------------------- 3. MANIFEST 校验 ----
printf '%s==>%s 按 MANIFEST 校验每个文件\n' "$C_I" "$C_R" >&2
TOTAL_FILES="$(grep -c . "$SEED_ROOT/MANIFEST" || true)"
TOTAL_BYTES="$(cd "$SEED_ROOT" && awk '{print $2}' MANIFEST | while IFS= read -r f; do
  [ -f "$f" ] && stat -c %s "$f"; done | awk '{s+=$1} END{print s+0}')"

CHECK_OUT="$TMP/check.out"
( cd "$SEED_ROOT" && sha256sum -c MANIFEST ) > "$CHECK_OUT" 2>&1 || true
OK_COUNT="$(grep -c ': OK$' "$CHECK_OUT" || true)"
BAD_COUNT="$(grep -c -E ': FAILED$' "$CHECK_OUT" || true)"
MISSING_COUNT="$(grep -c -E 'FAILED open or read' "$CHECK_OUT" || true)"

if [ "$BAD_COUNT" -gt 0 ] || [ "$MISSING_COUNT" -gt 0 ]; then
  while IFS= read -r line; do
    case "$line" in
      *": FAILED"|*"FAILED open or read"*) fail "$line" ;;
    esac
  done < "$CHECK_OUT"
else
  ok "MANIFEST 全面通过：$OK_COUNT/$TOTAL_FILES 个文件 sha256 一致"
fi

# ------------------------------------------------- 4. 结构魔数体检 ----
printf '%s==>%s 结构体检（文件类型魔数）\n' "$C_I" "$C_R" >&2
check_magic() { # check_magic <文件> <期望十六进制前缀> <说明>
  local f="$1" want="$2" what="$3"
  [ -f "$f" ] || { fail "缺少文件：$(basename "$f")"; return 0; }
  local got
  got="$(head -c 4 "$f" | od -An -tx1 | tr -d ' \n' | cut -c1-${#want})"
  if [ "$got" = "$want" ]; then
    quiet ok "$(basename "$f") 是合法 $what（魔数 $got）"
  else
    fail "$(basename "$f") 不是 $what（期望魔数 $want，实际 $got）—— 文件可能被替换"
  fi
}

UB_FILE="$(find "$SEED_ROOT" -maxdepth 1 -name 'ubuntu-base-*-base-arm64.tar.gz' | head -1)"
NODE_FILE="$(find "$SEED_ROOT" -maxdepth 1 -name 'node-*-linux-arm64.tar.xz' | head -1)"
[ -n "$UB_FILE" ] || fail "找不到 ubuntu-base-*.tar.gz（种子包的必备内容）"
[ -n "$NODE_FILE" ] || fail "找不到 node-*-linux-arm64.tar.xz（种子包的必备内容）"
[ -n "$UB_FILE" ] && check_magic "$UB_FILE" "1f8b" "gzip 包"
[ -n "$NODE_FILE" ] && check_magic "$NODE_FILE" "fd377a58" "xz 包"

# ------------------------------------------------- 5. 汇总 ----
printf '\n' >&2
printf '  %-28s %s\n' "种子目录" "$(basename "$SEED_ROOT")" >&2
printf '  %-28s %s\n' "MANIFEST 文件数" "$TOTAL_FILES" >&2
printf '  %-28s %s\n' "内容总大小" "$(printf '%s' "$TOTAL_BYTES" | awk '{printf "%.1f MiB", $1/1048576}')" >&2
if [ -n "$UB_FILE" ]; then printf '  %-28s %s\n' "ubuntu-base" "$(basename "$UB_FILE") $(du -h "$UB_FILE" | awk '{print $1}')" >&2; fi
if [ -n "$NODE_FILE" ]; then
  printf '  %-28s %s\n' "Node" "$(basename "$NODE_FILE") $(du -h "$NODE_FILE" | awk '{print $1}')" >&2
  [ -f "$SEED_ROOT/node-version.txt" ] && printf '  %-28s %s\n' "Node 版本（记录）" "$(cat "$SEED_ROOT/node-version.txt")" >&2
fi
if [ -d "$SEED_ROOT/debs" ]; then
  printf '  %-28s %s\n' "deb 缓存" "$(find "$SEED_ROOT/debs" -name '*.deb' | wc -l) 个" >&2
fi
if [ -n "$TMP" ] && [ "$KEEP" -eq 1 ]; then warn "临时目录保留在：$TMP"; fi

printf '\n' >&2
if [ "$FAILURES" -eq 0 ]; then
  ok "种子包校验通过：内容完整、与 MANIFEST 一致。"
  printf '  使用：mkdir -p /data/linux/seeds && tar --zstd -xf %s -C /data/linux/seeds --strip-components=1\n' \
    "${ARCHIVE:-<包>}" >&2
  exit 0
else
  printf '%s❌ 种子包校验失败：%d 项异常。%s\n' "$C_E" "$FAILURES" "$C_R" >&2
  printf '  不要用这个包部署：rootfs 与 Node 二进制是环境的信任根。\n' >&2
  printf '  请重新制作/重新下载（tools/seed/mkseed.sh），或先用 sha256 向分发者核对。\n' >&2
  exit 1
fi
