#!/usr/bin/env bash
# =============================================================================
# testdata/fixtures/mkfixtures.sh —— 生成单测夹具（三种格式的"头部"最小样本）
#
# ## 为什么这些 2 KB 的小文件值得单独一个脚本
#   这 3 个夹具决定 `runtime/root/selftest.sh` 里 **19 项断言**能否运行：
#   其中一条正是当年最贵的一个 bug —— **EROFS 的 magic 不在偏移 0，而在偏移 1024**
#   （旧实现按偏移 0 判，导致 `linuxctl update` 把所有合法 erofs 层全拒了）。
#   而夹具原先只放在 `build/fixtures/`，`build/` 是 gitignore 的
#   → **CI 上找不到夹具，19 项断言直接 SKIP（还打印"通过"）**。
#   所以：夹具放进仓库内可跟踪的路径，并且**可重新生成**（不是来路不明的二进制）。
#
# ## 关键事实（生成依据，不是猜的）
#   - EROFS 超级块位于 **1024 字节**处，magic `0xE0F5E1E2`（小端字节序 = e2 e1 f5 e0）
#     —— 见 docs/findings.md §2.1（真实镜像实测）。
#   - squashfs 的 magic 在偏移 0：`hsqs`（68 73 71 73）。
#   - zstd 帧 magic 在偏移 0：`28 b5 2f fd`（28b52ffd）。
#   除这几个字节外其余为 0：断言只读"魔数在哪个偏移"，不依赖镜像内容。
#
# ## 用法
#   bash testdata/fixtures/mkfixtures.sh            # 就地生成/覆盖三个夹具
#   bash testdata/fixtures/mkfixtures.sh --check     # 只校验现有夹具是否符合预期（CI 用）
# =============================================================================
set -euo pipefail

SELF_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
SIZE=2048

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

# 写出一个夹具：<文件> <偏移> <4 字节>
# 用 printf 的八进制转义（POSIX）而不是 xxd/hexdump —— 设备侧与精简镜像里不一定有后者
write_fixture() {
  local f="$SELF_DIR/$1" off="$2" bytes="$3"
  dd if=/dev/zero of="$f" bs=1 count="$SIZE" status=none 2>/dev/null
  printf "$bytes" | dd of="$f" bs=1 seek="$off" conv=notrunc status=none 2>/dev/null
}

# 读回某个偏移的 4 字节（hex），用于 --check
read_offset() {
  dd if="$SELF_DIR/$1" bs=1 skip="$2" count=4 2>/dev/null | od -An -tx1 | tr -d ' \n'
}

declare -A OFF=( [erofs-head.bin]=1024 [squashfs-head.bin]=0 [zstd-head.bin]=0 )
declare -A HEX=( [erofs-head.bin]=e2e1f5e0 [squashfs-head.bin]=68737173 [zstd-head.bin]=28b52ffd )
declare -A OCT=( [erofs-head.bin]='\342\341\365\340' [squashfs-head.bin]='\150\163\161\163' [zstd-head.bin]='\050\265\057\375' )

if [ "$CHECK" -eq 1 ]; then
  bad=0
  for f in erofs-head.bin squashfs-head.bin zstd-head.bin; do
    [ -f "$SELF_DIR/$f" ] || { echo "  ❌ 缺少 $f（跑一次 bash $0 生成）"; bad=1; continue; }
    got="$(read_offset "$f" "${OFF[$f]}")"
    if [ "$got" = "${HEX[$f]}" ]; then
      echo "  ✅ $f 偏移 ${OFF[$f]} = $got"
    else
      echo "  ❌ $f 偏移 ${OFF[$f]} = $got，期望 ${HEX[$f]}"; bad=1
    fi
  done
  exit "$bad"
fi

for f in erofs-head.bin squashfs-head.bin zstd-head.bin; do
  write_fixture "$f" "${OFF[$f]}" "${OCT[$f]}"
  printf '  生成 %-20s %s 字节，偏移 %-5s = %s\n' "$f" "$SIZE" "${OFF[$f]}" "$(read_offset "$f" "${OFF[$f]}")"
done
echo "完成。夹具目录：$SELF_DIR"
