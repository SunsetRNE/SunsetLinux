#!/system/bin/sh
# =============================================================================
# SunsetLinux 分层规格 —— 唯一共同事实源
# =============================================================================
# `rootfs/build-layers.sh`（宿主/CI 发布构建）与 `rootfs/device-provision.sh`
# （设备侧原生构建）**必须都 source 本文件**，不得各自内联定义层内容、命名、
# 压缩参数或版本格式。两条路径产出的层必须逐字节可比对（同名同内容）。
#
# 本文件只定义常量与纯函数，**不做任何 I/O、不改动系统**，可以安全 source。
# =============================================================================

LAYER_SPEC_VERSION=1

# -----------------------------------------------------------------------------
# 1. 文件系统格式：EROFS
# -----------------------------------------------------------------------------
# 为什么不是 squashfs：本机内核实测 `# CONFIG_SQUASHFS is not set`，
# /proc/filesystems 无 squashfs，/system/lib/modules 下也无 squashfs.ko
# → squashfs 层**根本挂不起来**（详见 docs/findings.md §2）。
#
# 为什么是 EROFS：CONFIG_EROFS_FS=y（且 XATTR/POSIX_ACL/SECURITY 全开），
# 设备上已有 1742 个 erofs 挂载，并且 **overlayfs + erofs lowerdir 在本机有现成实例**
# （/product/overlay 等）。这是 Android 原生的只读压缩文件系统，最贴合本场景。
LAYER_FS=erofs
LAYER_EXT=erofs

# -----------------------------------------------------------------------------
# 2. 镜像内压缩：默认**不开**（none）
# -----------------------------------------------------------------------------
# 本内核 EROFS **只支持 LZ4**（CONFIG_EROFS_FS_ZIP=y，但 LZMA/DEFLATE/ZSTD 均未启用）。
# LZ4 压缩率太弱，实测同一份 node 二进制：
#     erofs -zlz4   69.0 MB
#     erofs 无压缩 116.4 MB → 再 zstd-19 传输压缩 31.1 MB
# 分发体积才是用户能感知的（"更新又拖又慢"），而 flash 有 625 GB 可用，不是瓶颈。
# 所以：**镜像不压缩（读取也更快），分发时整体 zstd 压缩**。
# 如需兼顾 flash，可设 EROFS_COMPRESS=lz4hc,9。
EROFS_COMPRESS="${EROFS_COMPRESS:-none}"
# mkfs.erofs 参数。-b 4096 与设备页大小一致；--all-root 去掉宿主 uid 差异（可重现构建）。
EROFS_BLOCK_SIZE="${EROFS_BLOCK_SIZE:-4096}"

# -----------------------------------------------------------------------------
# 3. 分发（传输）压缩：zstd 优先，gzip 作为兼容回退 —— **两者都发布**
# -----------------------------------------------------------------------------
# 层在频道里以 `<name>-<version>.erofs.zst` 为主分发；App 侧解压后得到裸 erofs 镜像再挂载。
# 压缩算法由 URL 后缀决定，App 据此分派解压器：
#   .zst → zstd（首选，App 用 zstd-jni）
#   .gz  → gzip（零依赖回退，JDK GZIPInputStream 即可）
# 实测（node 二进制）：zstd-19 = 31.1 MB，gzip-9 = 41.8 MB。
#
# ★ 为什么**两种都发布**（实测决定）：
#   设备侧实测 **没有 zstd，也没有 xz**（`/system/bin/zstd` 与 `/system/bin/xz` 都不存在），
#   只有 `gzip`（toybox 软链）。所以：
#     - **App 路径**：下载 `.erofs.zst`（体积小 ~35%），App 内解压 → 写裸 `.erofs` → `linuxctl update`。
#     - **纯 CLI / 无 App 路径**（root 终端手工安装、模块自举）：只能用 `.erofs.gz` 配 toybox gzip。
#   只发布一种就会打断其中一条路径，因此**构建时两种都产出**（额外成本仅一份存储）。
TRANSPORT_COMPRESS_PRIMARY="${TRANSPORT_COMPRESS_PRIMARY:-zstd}"
TRANSPORT_COMPRESS_FALLBACK="${TRANSPORT_COMPRESS_FALLBACK:-gzip}"
TRANSPORT_EXT_zstd=zst
TRANSPORT_EXT_gzip=gz

# -----------------------------------------------------------------------------
# 4. 层标识、顺序与命名
# -----------------------------------------------------------------------------
# 顺序 = overlay 的 lowerdir 顺序，**左为高优先**（同名文件以左层为准）：
#     lowerdir=<dsh>:<runtime>:<base>
LAYERS_ORDER=(dsh runtime base)

# 层文件名：<id>-<version>.<ext>，例如 base-24.04.3-l1.erofs
# 版本必须是**语义化版本**，不得使用文件名或时间戳占位（§5）。
layer_file_name() { # layer_file_name <id> <version>
  printf '%s-%s.%s' "$1" "$2" "$LAYER_EXT"
}
layer_transport_name() { # layer_transport_name <id> <version> [zstd|gzip]
  local base kind="${3:-$TRANSPORT_COMPRESS_PRIMARY}"
  base="$(layer_file_name "$1" "$2")"
  case "$kind" in
    zstd) printf '%s.%s' "$base" "$TRANSPORT_EXT_zstd" ;;
    gzip) printf '%s.%s' "$base" "$TRANSPORT_EXT_gzip" ;;
    none) printf '%s' "$base" ;;
    *)    printf 'layer-spec: 未知传输压缩：%s\n' "$kind" >&2; return 1 ;;
  esac
}
# 运行时实际使用的层文件路径（本地已解压的镜像，无传输后缀）
layer_local_path() { # layer_local_path <LINUX_HOME> <id>
  printf '%s/layers/%s.%s' "$1" "$2" "$LAYER_EXT"
}

# -----------------------------------------------------------------------------
# 5. 版本约定
# -----------------------------------------------------------------------------
# - base    : <ubuntu-base 版本>-l<n>   例如 24.04.3-l1（l<n> 是本项目对基线的第 n 次修订）
# - runtime : <semver>                  例如 1.0.0（Node/工具/运行时脚本变更时递增）
# - dsh     : 与 npm 上 @deepseek-ai/dsh 的版本**完全一致**，例如 0.1.5-rc.2
# state.json / channel.json 里记录的必须是上面的语义版本，**不是文件名**。
version_regex_base='^[0-9]+\.[0-9]+\.[0-9]+-l[0-9]+$'
version_regex_semver='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'

# -----------------------------------------------------------------------------
# 6. 各层内容定义（合并后 rootfs 内的绝对路径）
# -----------------------------------------------------------------------------
# ---- base 层：Ubuntu 24.04 最小系统 ----------------------------------------
# 来源：ubuntu-base-24.04.3-base-arm64.tar.gz（种子内）+ rootfs/profiles/base.packages
# 含运行 DSH 的**必需**系统库：libc6 / libstdc++6 / libgcc-s1（实测见 findings §6.2）
LAYER_BASE_MUST_NOT_DELETE=(
  /bin /lib /usr /etc /var/lib/dpkg /var/lib/apt
)
# 裁剪（**只允许在 base 层做一次**；层间不做删除，因为只读 lower 无法表达 whiteout）
LAYER_BASE_TRIM=(
  /usr/share/doc /usr/share/man /usr/share/info
  /var/cache/apt /var/lib/apt/lists
  /usr/share/lintian /usr/share/bug
)

# ---- runtime 层：Node、工具、**运行时脚本** ---------------------------------
# 关键决策：entry.sh / supervise.sh 这类"环境入口"必须放在**只读层**，
# 否则 `linuxctl reset`（清空可写上层）会把它们一起删掉，环境再也起不来。
# 放在 runtime 层（而不是 base）是因为它们的变更频率属于"运行时"而不是"OS 基线"。
LAYER_RUNTIME_PATHS=(
  /opt/node            # Node 官方 arm64 静态包 + 全局 pnpm
  /opt/sunsetlinux         # entry.sh / supervise.sh（环境内入口，只读）
)
LAYER_RUNTIME_ENTRY_DIR=/opt/sunsetlinux
LAYER_RUNTIME_ENTRY=/opt/sunsetlinux/entry.sh
LAYER_RUNTIME_SUPERVISE=/opt/sunsetlinux/supervise.sh

# ---- dsh 层：DSH 主体 + profile 工作区 --------------------------------------
LAYER_DSH_PATHS=(
  /usr/local/lib/node_modules/@deepseek-ai/dsh
  /usr/local/bin/dsh
  /root/.dsh/profiles
)
# profile 工作区（由 rootfs/profiles/install-web-profile.sh 生成）
LAYER_DSH_PROFILE_DIR=/root/.dsh/profiles/web
# 用户状态**不在层里**（写入可写上层）：sessions/ settings.yaml .credentials.yaml storages/ 等

# -----------------------------------------------------------------------------
# 7. 分层打包时**绝对禁止**出现的路径（许可红线 / 凭据）
# -----------------------------------------------------------------------------
# dsh-app-integration：package.json 里没有任何 license 字段 → 默认保留所有权利，不得分发。
# /root/dsha-*：DSHA 的私有内置插件，非公开。
# 凭据：绝不能进层（层会被分发到别人的设备上）。
LAYER_FORBIDDEN_PATHS=(
  'dsh-app-integration'
  '/root/dsha-'
  '/root/.dsh/.credentials.yaml'
  '/root/.dsh/env'
  '/root/.ssh'
  'channel.key'
)
# 注意：软链必须原样保留（mksquashfs/mkfs.erofs 默认都保留）。
# 尤其 profile 的 node_modules/@deepseek-ai 是**相对软链**，缺失或被展平会让 DSH 起不来
# （见 docs/dsh-profile.md §5.4–5.5）。
LAYER_MUST_PRESERVE_SYMLINKS=1

# -----------------------------------------------------------------------------
# 8. 断言辅助（供两条构建路径共用）
# -----------------------------------------------------------------------------
# layer_spec_assert_no_forbidden <根目录>  —— 命中禁止路径即返回 1 并打印原因
# 实现为**单次 find 遍历**（组合表达式），避免每个条目各扫一遍的 6 倍开销。
# 绝对路径条目按**前缀**匹配（'/root/dsha-' 的意图是匹配 /root/dsha-*）；
# 不以 / 开头的条目按文件名匹配（可在任意深度命中）。
layer_spec_assert_no_forbidden() {
  local root="$1" f
  [[ -n "$root" && -d "$root" ]] || { printf 'layer-spec: 根目录不存在：%s\n' "$root" >&2; return 1; }

  # 直接构造 find 参数：-o 只插在**表达式组之间**，绝不能插在 -name/-path 与其参数之间
  # ⚠️ 不能写 `local args=() i=0`：mksh（Android /system/bin/sh）里
  #    `local x=()` 是**语法错误**，而本文件被 linuxctl 整份 source，
  #    一个语法错就会让 linuxctl 完全不可用。拆成"先声明、再赋值"。
  local args i=0
  args=()
  for f in "${LAYER_FORBIDDEN_PATHS[@]}"; do
    (( i > 0 )) && args+=( -o )
    if [[ "$f" == /* ]]; then
      # -path 走 glob，因此用尾部 * 做前缀匹配（'/root/dsha-' → "$root/root/dsha-*"）
      args+=( -path "$root$f*" )
    else
      args+=( -name "$f" )
    fi
    (( i++ ))
  done

  local hits
  hits="$(find "$root" \( "${args[@]}" \) -print 2>/dev/null | head -20)"
  if [[ -n "$hits" ]]; then
    printf 'layer-spec: 禁止路径命中（不得进层，见许可红线）：\n%s\n' "$hits" >&2
    return 1
  fi
  return 0
}

# layer_spec_assert_profile_symlink <profile 目录>
# 校验 <profile>/node_modules/@deepseek-ai 仍是**可解析的相对软链**。
layer_spec_assert_profile_symlink() {
  local profile="$1" link="$1/node_modules/@deepseek-ai"
  [[ -L "$link" ]] || { printf 'layer-spec: %s 不是符号链接（软链丢失会让 DSH 无法解析插件）\n' "$link" >&2; return 1; }
  local target; target="$(readlink "$link")"
  [[ "$target" != /* ]] || { printf 'layer-spec: %s 是绝对软链（%s），换 prefix/chroot 后会断，必须用相对路径\n' "$link" "$target" >&2; return 1; }
  [[ -e "$link" ]] || { printf 'layer-spec: %s 指向的 %s 不可达\n' "$link" "$target" >&2; return 1; }
  return 0
}
