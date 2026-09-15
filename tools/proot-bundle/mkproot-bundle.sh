#!/usr/bin/env bash
# =============================================================================
# SunsetLinux — proot 随包 bundle 制作工具（mkproot-bundle.sh）        v1.0.0
#
# 目的：让 App 不依赖 Termux、也不依赖设备上已装的 proot。
#   1. 从本机（或 --proot 指定路径）复制 proot 二进制；
#   2. 用 ldd 找出**全部**动态依赖（libtalloc 等）并一并打包；
#   3. 连 glibc 的解释器（ld-linux-*.so.*）也一起打包，生成 proot-launch.sh：
#      默认用自带 loader 显式加载（`ld.so --library-path lib bin/proot`），
#      因此完全不依赖宿主 loader / glibc 版本；LD_LIBRARY_PATH 只作为兜底路径。
#   4. 随附 GPLv2 许可证全文 + proot 版权与来源说明（GPL 合规，见 bundle 内 README/SOURCE）。
#   5. 解包后自检 `--version` 能跑，并打印 sha256。
#
# 用法：
#   tools/proot-bundle/mkproot-bundle.sh [--proot <path>] [--out <file>]
#                                        [--arch <arm64|x86_64|...>]
#                                        [--license <GPLv2 全文文件>]
#                                        [--keep] [--verify <bundle.tar.gz>]
#
# 产物结构：
#   bin/proot                proot 二进制（原样，未修改）
#   lib/*.so*                ldd 列出的全部依赖 + ELF 解释器
#   proot-launch.sh          POSIX sh 包装器（Android 上用 /system/bin/sh 跑）
#   license/GPL-2.0.txt      GPLv2 许可证全文
#   license/proot-copyright.txt  proot 版权/来源（Debian copyright 或等效说明）
#   SOURCE                   对应源码获取方式 + 原始二进制 sha256
#   README                   许可义务、Android 部署注意事项
#   manifest.txt             文件清单 + 逐个 sha256
# =============================================================================
set -euo pipefail

SELF_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)
REPO_ROOT=$(cd -- "$SELF_DIR/../.." >/dev/null 2>&1 && pwd -P)

PROOT_SRC=""
OUT=""
ARCH=""
LICENSE_SRC=""
KEEP=0
VERIFY_ONLY=""

VERSION_FALLBACK="unknown"

usage() {
  cat >&2 <<EOF
SunsetLinux proot bundle 制作工具

用法：
  mkproot-bundle.sh [选项]

选项：
  --proot <path>     proot 二进制路径（默认取 PATH 上的 proot，或 /usr/bin/proot）
  --out <file>       输出 tarball（默认 <repo>/dist/proot-bundle-<arch>.tar.gz）
  --arch <name>      架构名，用于产物文件名（默认由 uname -m 推断 arm64/x86_64/arm/x86）
  --license <file>   GPLv2 全文文件（默认 /usr/share/common-licenses/GPL-2）
  --keep             保留临时目录（排错用）
  --verify <tar>     只做校验：解包已有 bundle、跑 --version、检查许可证文件
  -h, --help         显示本帮助

产物：bin/proot + lib/*.so + proot-launch.sh + license/ + SOURCE + README + manifest.txt
EOF
  return 0
}

log()  { printf '[mkproot-bundle] %s\n' "$*" >&2; }
warn() { printf '[mkproot-bundle 警告] %s\n' "$*" >&2; }
die()  { printf '[mkproot-bundle 错误] %s\n' "$*" >&2; exit 1; }

detect_arch() {
  case "$(uname -m 2>/dev/null || printf unknown)" in
    aarch64|arm64) printf 'arm64' ;;
    armv7l|armv8l|arm) printf 'arm' ;;
    x86_64|amd64) printf 'x86_64' ;;
    i686|i386) printf 'x86' ;;
    *) uname -m 2>/dev/null || printf 'unknown' ;;
  esac
}

parse_args() {
  while (( $# )); do
    case "$1" in
      --proot) PROOT_SRC="${2-}"; shift 2 ;;
      --proot=*) PROOT_SRC="${1#--proot=}"; shift ;;
      --out) OUT="${2-}"; shift 2 ;;
      --out=*) OUT="${1#--out=}"; shift ;;
      --arch) ARCH="${2-}"; shift 2 ;;
      --arch=*) ARCH="${1#--arch=}"; shift ;;
      --license) LICENSE_SRC="${2-}"; shift 2 ;;
      --license=*) LICENSE_SRC="${1#--license=}"; shift ;;
      --keep) KEEP=1; shift ;;
      --verify) VERIFY_ONLY="${2-}"; shift 2 ;;
      --verify=*) VERIFY_ONLY="${1#--verify=}"; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "未知参数：$1（用 --help 看用法）" ;;
    esac
  done
  [[ -n "$ARCH" ]] || ARCH=$(detect_arch)
  [[ -n "$OUT" ]] || OUT="$REPO_ROOT/dist/proot-bundle-$ARCH.tar.gz"
  return 0
}

# -----------------------------------------------------------------------------
# 1. 收集依赖
# -----------------------------------------------------------------------------
# ldd 的输出形态（本机 proot 5.1.0 实测）：
#   linux-vdso.so.1 (0x...)                                  → 虚拟，跳过
#   libtalloc.so.2 => /lib/aarch64-linux-gnu/libtalloc.so.2 (0x...)
#   libc.so.6 => /lib/aarch64-linux-gnu/libc.so.6 (0x...)
#   /lib/ld-linux-aarch64.so.1 (0x...)                       → ELF 解释器，单独处理
collect_libs() { # 输出：每行 "<宿主绝对路径>"
  local bin=$1 line="" path=""
  while IFS= read -r line; do
    case "$line" in
      *"linux-vdso"*|*"linux-gate"*) continue ;;
    esac
    # 带 => 的普通依赖
    if [[ "$line" == *"=>"* ]]; then
      path=$(printf '%s' "$line" | sed -n 's/.*=>[[:space:]]*\([/][^[:space:]]*\).*/\1/p')
      [[ -n "$path" && -e "$path" ]] && { printf '%s\n' "$path"; continue; }
      # "not found" 要显式报错，否则 bundle 一定跑不起来
      if [[ "$line" == *"not found"* ]]; then
        warn "依赖缺失（ldd 报 not found）：$line"
      fi
      continue
    fi
    # 行首就是绝对路径的 = ELF 解释器
    path=$(printf '%s' "$line" | sed -n 's/^[[:space:]]*\([/][^[:space:]]*\).*/\1/p')
    [[ -n "$path" && -e "$path" ]] && printf '%s\n' "$path"
  done < <(ldd "$bin" 2>/dev/null || true)
}

find_license_text() {
  local c=""
  for c in "${LICENSE_SRC:-}" /usr/share/common-licenses/GPL-2 /usr/share/common-licenses/GPL-2.0 \
           /usr/share/licenses/common/GPL2/license.txt "$SELF_DIR/GPL-2.0.txt"; do
    [[ -n "$c" && -s "$c" ]] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

find_copyright_text() {
  local c=""
  for c in /usr/share/doc/proot/copyright /usr/share/doc/proot/COPYING; do
    [[ -s "$c" ]] && { printf '%s' "$c"; return 0; }
  done
  return 0
}

# -----------------------------------------------------------------------------
# 2. 打包
# -----------------------------------------------------------------------------
S_PROOT_VER=""
S_PROOT_SHA=""
S_INTERP=""
# 临时目录必须是全局变量：EXIT trap 在函数返回后执行，局部变量那时已经不存在了
STAGE_DIR=""

build_bundle() {
  command -v ldd >/dev/null 2>&1 || die "缺少 ldd（binutils），无法找出动态依赖。
  在 Debian/Ubuntu 上：apt-get install -y binutils
  或者改用静态编译的 proot 并用 --proot 指定。"
  command -v tar >/dev/null 2>&1 || die "缺少 tar"

  [[ -n "$PROOT_SRC" ]] || PROOT_SRC=$(command -v proot 2>/dev/null || true)
  [[ -n "$PROOT_SRC" ]] || [[ ! -x /usr/bin/proot ]] || PROOT_SRC=/usr/bin/proot
  [[ -n "$PROOT_SRC" && -f "$PROOT_SRC" ]] || die "找不到 proot 二进制。
  安装：apt-get install -y proot
  或自行编译：git clone https://github.com/proot-me/proot && make -C src
  再用 --proot <path> 指定。"
  [[ -x "$PROOT_SRC" ]] || die "proot 不可执行：$PROOT_SRC"

  S_PROOT_VER=$("$PROOT_SRC" --version 2>&1 | tr '\n' ' ' | sed -n 's/.*\([0-9]\+\.[0-9]\+\.[0-9]\+\).*/\1/p' | head -1 || true)
  [[ -n "$S_PROOT_VER" ]] || S_PROOT_VER=$VERSION_FALLBACK
  S_PROOT_SHA=$(sha256sum -- "$PROOT_SRC" | awk '{print $1}')
  log "proot 源：$PROOT_SRC（版本 $S_PROOT_VER，sha256 $S_PROOT_SHA）"

  local lic="" copyr=""
  lic=$(find_license_text) || die "找不到 GPLv2 许可证全文。
  proot 是 GPL-2.0-or-later，**必须随附许可证全文**（这是硬性合规要求，不能省）。
  请用 --license <GPLv2 全文文件> 指定，或安装 common-licenses 包。"
  copyr=$(find_copyright_text)

  STAGE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/sunsetlinux-proot-bundle.XXXXXX")
  trap '[[ "${KEEP:-0}" == 1 ]] || rm -rf -- "${STAGE_DIR:-}" 2>/dev/null || true' EXIT
  local stage="$STAGE_DIR"
  mkdir -p "$stage/bin" "$stage/lib" "$stage/license"

  # 2.1 二进制
  cp -f -- "$PROOT_SRC" "$stage/bin/proot"
  chmod 0755 "$stage/bin/proot"

  # 2.2 依赖库（按 SONAME 命名；解释器也一并带上）
  local libs=() p base
  mapfile -t libs < <(collect_libs "$PROOT_SRC" | sort -u)
  if (( ${#libs[@]} == 0 )); then
    warn "ldd 没有列出任何依赖 —— proot 可能是静态链接，bundle 里就不需要 lib/。"
  fi
  for p in ${libs[@]+"${libs[@]}"}; do
    base=$(basename -- "$p")
    cp -fL -- "$p" "$stage/lib/$base"
    case "$base" in
      ld-linux*.so.*|ld-*.so.*) S_INTERP="$base" ;;
    esac
  done
  log "已打包依赖 ${#libs[@]} 个：$(printf '%s ' "${libs[@]##*/}")"

  # 2.3 包装器（POSIX sh：Android 上可以 /system/bin/sh proot-launch.sh ...
  #     —— Android 没有 /bin/sh，所以脚本本身与 start.sh 都会做解释器回退）
  cat >"$stage/proot-launch.sh" <<'WRAP'
#!/bin/sh
# SunsetLinux proot 启动包装器
#
# 默认行为：用 bundle 自带的 glibc 解释器显式加载 proot
#     <lib>/ld-linux-*.so.* --library-path <lib> <bin>/proot "$@"
# 这样完全不依赖宿主的 /lib/ld-*.so 与 glibc 版本，也不污染环境变量
# （不给 guest 程序塞 LD_LIBRARY_PATH —— 那会破坏 rootfs 里的程序）。
#
# 兜底：PROOT_BUNDLE_FORCE_LD_LIBRARY_PATH=1 时改用 LD_LIBRARY_PATH 方式。
#
# Android 提示：本脚本 shebang 是 #!/bin/sh，Android 上请用
#     /system/bin/sh /path/to/proot-launch.sh ...
# 或让 start.sh 自动回退（SUNSETLINUX_PROOT_CMD='/system/bin/sh .../proot-launch.sh'）。
set -eu

self=$0
case $self in
  */*) bundle_dir=$(cd -- "$(dirname -- "$self")" && pwd -P) ;;
  *)   bundle_dir=$(cd -- "$(dirname -- "$(command -v -- "$self")")" && pwd -P) ;;
esac
lib="$bundle_dir/lib"
bin="$bundle_dir/bin/proot"

[ -f "$bin" ] || { echo "proot-launch: 找不到 $bin" >&2; exit 127; }
[ -x "$bin" ] || chmod 0755 "$bin" 2>/dev/null || true

loader=""
for c in "$lib"/ld-linux*.so.* "$lib"/ld-*.so.*; do
  if [ -f "$c" ]; then loader=$c; break; fi
done

if [ -n "$loader" ] && [ -z "${PROOT_BUNDLE_FORCE_LD_LIBRARY_PATH:-}" ]; then
  exec "$loader" --library-path "$lib" "$bin" "$@"
fi

LD_LIBRARY_PATH="$lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export LD_LIBRARY_PATH
exec "$bin" "$@"
WRAP
  chmod 0755 "$stage/proot-launch.sh"

  # 2.4 许可证（GPL 合规：全文 + 版权/来源）
  cp -f -- "$lic" "$stage/license/GPL-2.0.txt"
  if [[ -n "$copyr" ]]; then
    cp -f -- "$copyr" "$stage/license/proot-copyright.txt"
  else
    cat >"$stage/license/proot-copyright.txt" <<'EOF'
Upstream-Name: proot
Source: http://proot.me  /  https://github.com/proot-me/proot
Copyright: 2013-2014, STMicroelectronics
License: GPL-2.0-or-later

本文件由 SunsetLinux 的 mkproot-bundle.sh 生成：
打包时本机没有找到 /usr/share/doc/proot/copyright，因此这里给出等效的
来源与版权声明。proot 的完整许可文本见同目录 GPL-2.0.txt。
EOF
  fi

  # 2.5 SOURCE + README
  cat >"$stage/SOURCE" <<EOF
SunsetLinux proot bundle —— 对应源码获取方式（GPLv2 合规要求）
=========================================================

本 bundle 内含的 proot 二进制**未经修改**，按 GPL-2.0-or-later 分发。

上游项目：PRoot
  主页      http://proot.me
  源码仓库  https://github.com/proot-me/proot
版本：$S_PROOT_VER
打包时使用的真实可执行文件：$PROOT_SRC
原始二进制 sha256：$S_PROOT_SHA
许可证：GPL-2.0-or-later（Copyright (C) 2013-2014 STMicroelectronics）

如何取得对应源码：
  1) 官方仓库对应 tag/发布版：
     git clone https://github.com/proot-me/proot
     cd proot && git checkout v$S_PROOT_VER   # 若该 tag 不存在，用最接近的发布标签
  2) 若使用发行版补丁版，取发行版源码包：
     apt-get source proot        # Debian/Ubuntu
  3) 重新编译（交叉编译到 Android/aarch64 时请见上游 README）：

本 bundle 的 proot 二进制与上述源码**逐字节一致（未做任何修改）**，
因此不需要额外发布修改后的源码；若你今后修改了 proot，
必须以 GPL 兼容方式发布修改后的完整源码（GPLv2 第 2/3 条）。
EOF

  cat >"$stage/README" <<EOF
SunsetLinux PRoot 随包运行库（bundle）
=================================

架构：$ARCH
proot 版本：$S_PROOT_VER
打包时间（UTC）：$(date -u '+%Y-%m-%dT%H:%M:%SZ')
生成工具：sunsetlinux/tools/proot-bundle/mkproot-bundle.sh

目录结构
--------
  bin/proot          proot 二进制（原样复制，未修改）
  lib/*.so*          proot 的全部动态依赖（含 libtalloc、libc）与 glibc 解释器
  proot-launch.sh    POSIX sh 包装器：默认用自带 loader 加载自带库
  license/           GPL-2.0 全文 + proot 版权/来源说明
  SOURCE             对应源码获取方式（GPL 合规）
  manifest.txt       文件清单 + sha256

为什么自带 lib（而不是用设备上的）
----------------------------------
Android 上既没有 Termux 也没有 glibc 的 /lib/ld-linux-aarch64.so.1，
proot 是 glibc 动态链接的程序，直接跑会 ENOENT/EACCES。
把 loader + 依赖一起打包，用
  <bundle>/lib/ld-linux-aarch64.so.1 --library-path <bundle>/lib <bundle>/bin/proot ...
显式加载，就能与宿主/设备上有什么库完全无关。
包装器默认就这么做，并且**不设置** LD_LIBRARY_PATH —— 避免把宿主 glibc
塞给 rootfs 里的程序（那会让 guest 程序加载错误的 libc）。

用法
----
  # 1) 解包到环境根下的 proot/ 目录（start.sh 会自动发现它）
  mkdir -p \$LINUX_HOME/proot
  tar -xzf proot-bundle-$ARCH.tar.gz -C \$LINUX_HOME/proot

  # 2) 自检
  \$LINUX_HOME/proot/proot-launch.sh --version

  # 3) 交给 linuxctl 用（start.sh 优先使用 \$LINUX_HOME/proot/proot-launch.sh）
  linuxctl start

Android 上的两个坑
------------------
  1. Android **没有 /bin/sh**，只有 /system/bin/sh。本 bundle 的包装器 shebang
     是 #!/bin/sh，所以：
       - 要么用  SUNSETLINUX_PROOT_CMD='/system/bin/sh <bundle>/proot-launch.sh'
         （start.sh 支持，会整体当作 proot 命令前缀使用）
       - 要么直接跑自带的 loader：
         <bundle>/lib/ld-linux-aarch64.so.1 --library-path <bundle>/lib <bundle>/bin/proot ...
     start.sh 也会自动检测「shebang 解释器不存在」并回退到可用的 sh。
  2. App 私有目录可能被挂载为 noexec（尤其 /data 下的某些子目录）。
     若 exec 报 EACCES，把 bin/proot（或整个 bundle）放到可执行的位置，
     例如 App 的 nativeLibraryDir（jniLibs，命名成 libproot.so）——
     这是 Android 上唯一保证可执行的 App 私有路径。

许可（重要，别删）
------------------
proot 以 **GPL-2.0-or-later** 分发（Copyright (C) 2013-2014 STMicroelectronics）。
随包分发二进制时你必须：
  1. 随附许可证全文 —— 已放在 license/GPL-2.0.txt；
  2. 提供对应源码的获取方式 —— 见 SOURCE（上游仓库 + 版本 + 原始二进制 sha256）；
  3. 若修改了 proot，以 GPL 兼容方式发布修改后的完整源码；
  4. 在 App 内提供「开源许可」入口展示以上信息（GPL 的合理告知义务）。
本 bundle 内的 proot 二进制未做任何修改。
EOF

  # 2.6 manifest
  {
    printf '# SunsetLinux proot bundle manifest\n'
    printf '# arch=%s proot_version=%s generated=%s\n' "$ARCH" "$S_PROOT_VER" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf '# 原始二进制 sha256=%s\n' "$S_PROOT_SHA"
    printf '# 说明：以下 sha256 是 bundle 内文件自身的摘要（含 proot-launch.sh）。\n'
  } >"$stage/manifest.txt"
  local f
  while IFS= read -r f; do
    printf '%s  %s\n' "$(sha256sum -- "$stage/$f" | awk '{print $1}')" "$f" >>"$stage/manifest.txt"
  done < <(cd "$stage" && find . -type f ! -name manifest.txt | sed 's|^\./||' | sort)

  # 2.7 打包：固定 mtime/属主/排序，减少无谓差异。
  # 注意：README/manifest 里含生成时间，因此跨次构建的 sha256 仍会不同
  #（要逐字节可复现就得注入 SOURCE_DATE_EPOCH，这里不做）。
  mkdir -p -- "$(dirname -- "$OUT")"
  log "打包 → $OUT"
  tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
      -czf "$OUT" -C "$stage" . || die "打包失败：$OUT"

  log "自检：解包后运行 --version"
  verify_bundle "$OUT" || die "bundle 自检失败：$OUT"
  rm -rf -- "$stage" 2>/dev/null || true
  STAGE_DIR=""
  trap - EXIT
  report "$OUT"
  return 0
}

# -----------------------------------------------------------------------------
# 3. 校验
# -----------------------------------------------------------------------------
verify_bundle() {
  local tar_file=$1 tmp rc=0 out="" v="" missing=()
  [[ -f "$tar_file" ]] || { err_ "找不到 bundle：$tar_file"; return 1; }
  tar -tzf "$tar_file" >/dev/null 2>&1 || { err_ "不是合法的 tar.gz：$tar_file"; return 1; }
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/sunsetlinux-proot-verify.XXXXXX")
  if ! tar -xzf "$tar_file" -C "$tmp" 2>/dev/null; then
    err_ "解包失败：$tar_file"; rm -rf -- "$tmp"; return 1
  fi
  local d
  for d in bin/proot proot-launch.sh license/GPL-2.0.txt SOURCE README manifest.txt; do
    [[ -e "$tmp/$d" ]] || missing+=("$d")
  done
  if (( ${#missing[@]} )); then
    err_ "bundle 缺少文件：${missing[*]}"
    rm -rf -- "$tmp"; return 1
  fi
  [[ -x "$tmp/proot-launch.sh" ]] || chmod 0755 "$tmp/proot-launch.sh" 2>/dev/null || true
  out=$("$tmp/proot-launch.sh" --version 2>&1) || rc=$?
  v=$(printf '%s' "$out" | tr '\n' ' ' | sed -n 's/.*\([0-9]\+\.[0-9]\+\.[0-9]\+\).*/\1/p' | head -1 || true)
  if (( rc != 0 )) || [[ -z "$v" ]]; then
    err_ "解包后的 proot 无法运行 --version（退出码 $rc）："
    printf '%s\n' "$out" | head -5 >&2
    rm -rf -- "$tmp"; return 1
  fi
  log "自检通过：proot $v（自带 loader 加载成功）"
  printf 'VERIFY_OK version=%s\n' "$v"
  rm -rf -- "$tmp"
  return 0
}

err_() { printf '[mkproot-bundle 错误] %s\n' "$*" >&2; }

report() {
  local f=$1 bytes="" sha=""
  bytes=$(stat -c %s -- "$f" 2>/dev/null || printf '?')
  sha=$(sha256sum -- "$f" | awk '{print $1}')
  printf '\n=== 产物 ===\n' >&2
  printf '路径   : %s\n' "$f" >&2
  printf '大小   : %s 字节（%s MiB）\n' "$bytes" "$(awk -v b="$bytes" 'BEGIN{printf "%.2f", b/1048576}')" >&2
  printf 'sha256 : %s\n' "$sha" >&2
  printf '许可证 : license/GPL-2.0.txt（GPLv2 全文）+ license/proot-copyright.txt + SOURCE\n' >&2

  # 顺带写出 .sha256 文件，方便分发校验
  printf '%s  %s\n' "$sha" "$(basename -- "$f")" >"$f.sha256" 2>/dev/null || true
  return 0
}

main() {
  parse_args "$@"
  if [[ -n "$VERIFY_ONLY" ]]; then
    verify_bundle "$VERIFY_ONLY" || exit 1
    report "$VERIFY_ONLY"
    exit 0
  fi
  build_bundle
  return 0
}

main "$@"
