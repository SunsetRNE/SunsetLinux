#!/system/bin/sh
# =============================================================================
# SunsetLinux —— 懒人体检 / 一键部署（oneshot-setup.sh）
#
# 目标：**在哪都能跑**，一条命令告诉你"差什么、下一步敲什么"，体检通过后能直接开跑。
#
# 用法（默认只体检、**不改任何东西**，可以随便跑）：
#     sh oneshot-setup.sh                 # 体检
#     sh oneshot-setup.sh --run           # 体检通过后真部署（需要 root）
#     su -c 'sh /data/adb/modules/sunsetlinux/bin/oneshot-setup.sh --run'
#
# 放在哪都行：本脚本不依赖当前目录（`/storage/emulated/0/Download/` 里也能跑），
# 也不依赖仓库 —— 它只跟**设备上的模块**打交道。
#
# 兼容性（这是刻意的，别加 bash 专有语法）：
#   · MT 管理器「系统」模式 = /system/bin/sh = **mksh**；「扩展包」模式给的是 bash/ash。
#     两者都要能解析 → 本脚本只用 POSIX + `case`，不用数组、`[[ =~ ]]`、进程替换、`local x=()`。
#     闸门：tools/shell-compat-check.mjs（改这个文件前先读它）。
#   · /sdcard 是 FAT/exFAT：**没有可执行位**（所以用 `sh 脚本` 而不是 `./脚本`），
#     所以真正的写入一律落在 /data（见下面 LINUX_HOME）。
#   · 若用会把换行改成 CRLF 的编辑器保存过，mksh 会报 `\r` 相关语法错 —— 存成 LF。
#
# 它做什么（体检项，每项都给"怎么修"）：
#   身份与 shell → 模块是否装了 → 环境根与层 → 工具链 → /data 剩余空间 → 网络 → 种子
# --run 会：把缺的种子下下来（ubuntu-base + Node 官方包）→ 跑 device-provision.sh
#           → 试着 linuxctl start → 打印 status。
# =============================================================================
set -u

MODDIR=/data/adb/modules/sunsetlinux
LINUX_HOME=/data/sunsetlinux
SEEDS_DIR="$LINUX_HOME/seeds"
CACHE_DIR="$LINUX_HOME/cache"

RUN=0
ASSUME_YES=0
WANT_SEEDS_DIR=""

# ---- 输出 -------------------------------------------------------------------
c_ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
c_warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
c_bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; }
c_info() { printf '    %s\n' "$*"; }
head_()  { printf '\n== %s ==\n' "$*"; }

BAD=0      # 阻断项（不修就没法继续）
WARN=0     # 非阻断项（提醒）
bad()  { BAD=$((BAD + 1)); c_bad "$*"; }
warn() { WARN=$((WARN + 1)); c_warn "$*"; }

have() { command -v "$1" >/dev/null 2>&1; }

# 找一个能跑的 bash：device-provision.sh 内部要求 bash（Android 自带只有 mksh）。
# 顺序：显式指定 → PATH 里 → Android 自带位置 → Termux（用户常常就站在 Termux 里）。
find_bash() {
  for c in "${SUNSETLINUX_BASH:-}" \
           "$(command -v bash 2>/dev/null || true)" \
           /system/bin/bash /system/xbin/bash /vendor/bin/bash \
           /data/data/com.termux/files/usr/bin/bash \
           /data/local/tmp/bash; do
    [ -n "$c" ] || continue
    [ -x "$c" ] || continue
    printf '%s' "$c"
    return 0
  done
  return 1
}

usage() {
  sed -n '2,30p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --run) RUN=1; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --seeds) WANT_SEEDS_DIR="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1（用 -h 看用法）" >&2; exit 2 ;;
  esac
done
[ -n "$WANT_SEEDS_DIR" ] && SEEDS_DIR="$WANT_SEEDS_DIR"

SELF_PATH="$0"
case "$SELF_PATH" in
  /*) ;;
  *) SELF_PATH="$(pwd)/$SELF_PATH" ;;
esac

printf 'SunsetLinux 懒人体检%s\n' "$([ "$RUN" = 1 ] && printf '（--run：会真的动手）' || printf '（只检测，不改任何东西）')"

# -----------------------------------------------------------------------------
# 1) 身份与 shell
# -----------------------------------------------------------------------------
head_ "身份与 shell"
UID_NOW="$(id -u 2>/dev/null || printf '?')"
SHELL_KIND="unknown"
[ -n "${KSH_VERSION:-}" ] && SHELL_KIND="mksh（Android 系统 shell）"
[ -n "${BASH_VERSION:-}" ] && SHELL_KIND="bash（MT 扩展包 / Termux）"
[ "$SHELL_KIND" = "unknown" ] && SHELL_KIND="ash/busybox（MT 扩展包）"

c_info "uid=$UID_NOW · shell=$SHELL_KIND · 架构=$(uname -m 2>/dev/null || printf '?')"
c_info "当前目录=$(pwd)（本脚本不依赖它）"
SU_OK=0
if [ "$UID_NOW" = "0" ]; then
  c_ok "已经是 root"
  SU_OK=1
elif have su; then
  c_warn "当前不是 root，但设备上有 su（--run 时会自动用 su 重新执行自己）"
  SU_OK=1
else
  bad "既不是 root、也没有 su —— 部署必须 root（MT 里请用「以 root 执行」，或 KernelSU 里给终端授权）"
fi

# -----------------------------------------------------------------------------
# 2) 模块
# -----------------------------------------------------------------------------
head_ "模块"
if [ -d "$MODDIR" ]; then
  MV="$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null | head -n1)"
  c_ok "模块已安装：$MODDIR${MV:+（$MV）}"
else
  bad "没找到模块目录 $MODDIR —— 先在 KernelSU 管理器里装 sunsetlinux-module-<版本>.zip"
fi

BIN_DIR="$LINUX_HOME/bin"
[ -x "$BIN_DIR/linuxctl" ] || BIN_DIR="$MODDIR/bin"
if [ -f "$BIN_DIR/linuxctl" ]; then
  c_ok "linuxctl 在：$BIN_DIR/linuxctl"
else
  bad "找不到 linuxctl（模块装了但没同步脚本？重启一次让 post-fs-data 铺 bin/）"
fi

PROV=""
for cand in "$MODDIR/bin/device-provision.sh" "$BIN_DIR/device-provision.sh"; do
  [ -f "$cand" ] && { PROV="$cand"; break; }
done
if [ -n "$PROV" ]; then
  c_ok "部署脚本在：$PROV"
else
  bad "找不到 device-provision.sh（模块包不完整？重新装一次模块 zip）"
fi

# -----------------------------------------------------------------------------
# 3) 环境根与层
# -----------------------------------------------------------------------------
head_ "环境根与层"
if [ -d "$LINUX_HOME" ]; then
  c_ok "环境根存在：$LINUX_HOME"
else
  warn "环境根还不存在（部署时会建）：$LINUX_HOME"
fi

if [ -f "$LINUX_HOME/upper.img" ]; then
  c_ok "可写层已有：upper.img（$(du -h "$LINUX_HOME/upper.img" 2>/dev/null | awk 'NR==1{print $1}')）"
else
  warn "还没有 upper.img（部署时创建，默认 8 GiB 稀疏文件）"
fi

N_LAYERS=0
MISSING_LAYERS=""
for id in base runtime dsh; do
  found=0
  for f in "$LINUX_HOME/layers/$id.erofs" "$LINUX_HOME/layers/$id"-*.erofs; do
    [ -f "$f" ] && { found=1; break; }
  done
  if [ "$found" = 1 ]; then
    N_LAYERS=$((N_LAYERS + 1))
  else
    MISSING_LAYERS="$MISSING_LAYERS $id"
  fi
done
if [ "$N_LAYERS" = 3 ]; then
  c_ok "三层只读镜像齐备（base / runtime / dsh）"
elif [ "$N_LAYERS" = 0 ]; then
  bad "一层都没有 —— 这就是 start 报「缺少层文件」的原因。跑 --run 部署，或装已发布的层"
else
  bad "缺层：$MISSING_LAYERS"
fi
if [ "$N_LAYERS" -lt 3 ]; then
  c_info "注意：压缩产物（.erofs.zst / .gz）不能直接放这里，必须是**解压后的裸 .erofs**"
fi

# -----------------------------------------------------------------------------
# 4) 工具链
# -----------------------------------------------------------------------------
head_ "工具链"
# 清单来自 device-provision.sh 自己的依赖（它缺什么会 die）：chroot/mount 必需，
# mkfs.erofs（没它才回退 mksquashfs）、mke2fs 建 upper.img、truncate 建稀疏文件、
# tar/gzip 解包与压缩。少一项就别急着 --run。
for t in chroot mount umount awk sed grep find xargs tar gzip truncate; do
  have "$t" && c_ok "$t" || bad "缺 $t（Toybox 里一般都有；ROM 太精简就这样）"
done
have mkfs.erofs && c_ok "mkfs.erofs（层镜像格式）" || bad "缺 mkfs.erofs —— 没有它建不出 EROFS 层（erofs-utils / 设备内核支持）"
have fsck.erofs && c_ok "fsck.erofs（打包后校验）" || warn "缺 fsck.erofs（不阻断，但少一道校验）"
have mke2fs && c_ok "mke2fs（建 upper.img）" || bad "缺 mke2fs —— 建不出可写层"
have sha256sum && c_ok "sha256sum（种子/层校验）" || warn "缺 sha256sum（不阻断，但少一道完整性核对）"

# device-provision.sh 内部**要求 bash**（Android 自带只有 mksh）——这条以前是隐形门槛：
# 实测设备上 `su -c 'sh .../oneshot-setup.sh --run'` 会在这一步直接退出（退出码 1）。
BASH_BIN="$(find_bash || true)"
if [ -n "$BASH_BIN" ]; then
  c_ok "bash：$BASH_BIN（部署脚本要求 bash）"
else
  bad "找不到 bash —— device-provision.sh 在 Android 自带的 mksh 下会直接退出。三条路：
       ① 在 Termux 里 pkg install bash，然后重跑本脚本（它会自动找到并用它）；
       ② 手动跑：/data/data/com.termux/files/usr/bin/bash /data/adb/modules/sunsetlinux/bin/device-provision.sh --seeds /data/sunsetlinux/seeds
       ③ 别在手机上建层：等层发到频道后从 App/终端装（linuxctl update <层> <裸.erofs>）"
fi
if have curl || have wget; then
  c_ok "有 $(have curl && printf curl || printf wget)（下种子用）"
else
  warn "既没有 curl 也没有 wget —— 种子得你自己放（下面会打印 URL）"
fi
have zstd && c_ok "有 zstd" || c_info "没有 zstd：正常（设备上一般都没有，所以分发用 .gz 回退）"

# -----------------------------------------------------------------------------
# 5) 空间与网络
# -----------------------------------------------------------------------------
head_ "空间与网络"
AVAIL_KB="$(df -k /data 2>/dev/null | awk 'NR==2{print $4}')"
case "$AVAIL_KB" in
  ''|*[!0-9]*) warn "/data 剩余空间读不出来（df 输出格式不认识）" ;;
  *)
    AVAIL_GB=$((AVAIL_KB / 1024 / 1024))
    if [ "$AVAIL_KB" -lt 4194304 ]; then
      bad "/data 只剩 ${AVAIL_GB} GiB —— 建议至少 4 GiB（Ubuntu base + Node + npm 装 DSH）"
    else
      c_ok "/data 剩余 ${AVAIL_GB} GiB"
    fi
    ;;
esac

NET_OK=0
NET_MSG=""
if have curl; then
  CODE="$(curl -s -o /dev/null -m 8 -w '%{http_code}' https://nodejs.org/dist/index.json 2>/dev/null || true)"
  case "$CODE" in 2??|3??) NET_OK=1;; *) NET_MSG="curl 得到 $CODE";; esac
elif have wget; then
  wget -q -T 8 -O /dev/null https://nodejs.org/dist/index.json 2>/dev/null && NET_OK=1 || NET_MSG="wget 失败"
else
  NET_MSG="没有下载工具"
fi
if [ "$NET_OK" = 1 ]; then
  c_ok "网络可达（nodejs.org）"
else
  warn "网络不通：$NET_MSG（无所谓：种子可以提前放好，见下）"
fi

# -----------------------------------------------------------------------------
# 6) 种子
# -----------------------------------------------------------------------------
head_ "种子（部署要用的离线包）"
UBUNTU_TB="ubuntu-base-24.04.3-base-arm64.tar.gz"
UBUNTU_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/$UBUNTU_TB"
NODE_VER="v24.21.0"
NODE_TB="node-$NODE_VER-linux-arm64.tar.xz"
NODE_URL="https://nodejs.org/dist/$NODE_VER/$NODE_TB"

# 优先从设备上的 device-provision.sh 里读它自己认定的 URL（避免两边漂移）
if [ -n "$PROV" ]; then
  U="$(sed -n 's/^UBUNTU_BASE_URL=//p' "$PROV" 2>/dev/null | head -n1 | tr -d '"')"
  T="$(sed -n 's/^UBUNTU_BASE_TARBALL=//p' "$PROV" 2>/dev/null | head -n1 | tr -d '"')"
  NV="$(sed -n 's/^NODE_VERSION=//p' "$PROV" 2>/dev/null | head -n1 | tr -d '"')"
  # ⚠️ device-provision.sh 里 UBUNTU_BASE_URL 是 "…/release/${UBUNTU_BASE_TARBALL}" ——
  # 含**未展开**的变量引用；直接拿来下载会得到一个带 ${…} 字面量的坏 URL。含 $ 就丢弃、用兜底。
  case "$U" in *'$'*) U="" ;; esac
  case "$T" in *'$'*) T="" ;; esac
  case "$NV" in *'$'*) NV="" ;; esac
  [ -n "$T" ] && UBUNTU_TB="$T"
  [ -n "$U" ] && UBUNTU_URL="$U"
  [ -n "$NV" ] && { NODE_VER="$NV"; NODE_TB="node-$NODE_VER-linux-arm64.tar.xz"; NODE_URL="https://nodejs.org/dist/$NODE_VER/$NODE_TB"; }
fi

seed_file() { # seed_file <文件名> —— 在 seeds/ 里找（含通配）
  for f in "$SEEDS_DIR/$1" "$SEEDS_DIR"/$2; do
    [ -f "$f" ] && { printf '%s' "$f"; return 0; }
  done
  return 1
}

UB_SEED="$(seed_file "$UBUNTU_TB" 'ubuntu-base-*-base-arm64.tar.gz' || true)"
ND_SEED="$(seed_file "$NODE_TB" 'node-v*-linux-arm64.tar.xz' || true)"
if [ -n "$UB_SEED" ]; then c_ok "Ubuntu base：$UB_SEED"; else warn "缺 Ubuntu base 种子"; fi
if [ -n "$ND_SEED" ]; then c_ok "Node 官方包：$ND_SEED"; else warn "缺 Node 官方包"; fi
if [ -z "$UB_SEED" ] || [ -z "$ND_SEED" ]; then
  c_info "可以自己下（放到 $SEEDS_DIR/ 下，**不要**放在子目录里）："
  c_info "  $UBUNTU_URL"
  c_info "  $NODE_URL"
  c_info "或者直接 --run，让本脚本替你下（各约 30/25 MB）"
fi

# -----------------------------------------------------------------------------
# 6.5) 模块版本 vs 官方发布（尽力而为：没网 / 没 curl 就跳过，不算失败）
#   为什么值得查：模块里带着 linuxctl / device-provision.sh / 本脚本自身，
#   版本落后就意味着"你现在跑的部署逻辑是旧的"（实测第一台设备就是 1.0.0 落后于 1.0.2）。
# -----------------------------------------------------------------------------
head_ "版本"
INST_VER="${MV:-}"
INST_VER="${INST_VER#v}"
LATEST=""
if [ -n "$INST_VER" ] && have curl; then
  IDX="$(curl -s --max-time 10 https://sunsetrne.github.io/SunsetLinux/stable/index.json 2>/dev/null || true)"
  LATEST="$(printf '%s' "$IDX" | sed -n 's/.*"module_version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
fi
if [ -z "$INST_VER" ]; then
  c_info "设备上没有模块版本信息（模块没装或 module.prop 读不到）—— 跳过比较"
elif [ -z "$LATEST" ]; then
  c_info "拿不到官方发布版本（没网 / 没 curl）—— 跳过比较"
elif [ "$LATEST" = "$INST_VER" ]; then
  c_ok "模块版本与官方发布一致（$INST_VER）"
else
  warn "模块版本不一致：设备上 $INST_VER、官方发布 $LATEST —— 建议先在 KernelSU 管理器里装 sunsetlinux-module-$LATEST.zip（新模块同时带来最新的部署脚本）"
fi

# -----------------------------------------------------------------------------
# 结论
# -----------------------------------------------------------------------------
head_ "结论"
if [ "$BAD" -gt 0 ]; then
  c_bad "$BAD 个阻断项、$WARN 个提醒 —— 先按上面的提示处理"
else
  c_ok "没有阻断项（$WARN 个提醒）"
fi

if [ "$RUN" = 0 ]; then
  printf '\n下一步（体检看着没问题就照这个来）：\n'
  printf '  su -c '"'"'sh %s --run'"'"'\n' "$SELF_PATH"
  printf '  （MT 管理器里也可以直接用「以 root 执行」跑本脚本，再加 --run）\n'
  exit 0
fi

# -----------------------------------------------------------------------------
# --run：真的开始部署
# -----------------------------------------------------------------------------
head_ "--run：开始部署"

if [ "$UID_NOW" != "0" ]; then
  if have su; then
    c_warn "当前不是 root：用 su 重新执行自己（会再弹一次授权）"
    exec su -c "sh '$SELF_PATH' --run"
  fi
  c_bad "没有 root，无法部署（要真 chroot 与 mount）"
  exit 1
fi
[ -n "$PROV" ] || { c_bad "找不到 device-provision.sh，无法部署"; exit 1; }

mkdir -p "$SEEDS_DIR" "$CACHE_DIR" 2>/dev/null || true

# 缺种子就下（下到 cache/ 再移进 seeds/，避免半截文件被当真种子）
fetch() { # fetch <url> <目标文件>
  url="$1"; out="$2"
  c_info "下载 $(basename "$out") …"
  if have curl; then
    curl -fL --retry 3 --connect-timeout 10 -o "$out.part" "$url" || return 1
  elif have wget; then
    wget -q -O "$out.part" "$url" || return 1
  else
    return 1
  fi
  mv -f "$out.part" "$out" 2>/dev/null || return 1
  c_ok "已下载 $(basename "$out")（$(du -h "$out" 2>/dev/null | awk 'NR==1{print $1}')）"
  return 0
}

if [ -z "$UB_SEED" ]; then
  fetch "$UBUNTU_URL" "$SEEDS_DIR/$UBUNTU_TB" || { c_bad "Ubuntu base 下载失败：$UBUNTU_URL"; exit 1; }
fi
if [ -z "$ND_SEED" ]; then
  fetch "$NODE_URL" "$SEEDS_DIR/$NODE_TB" || { c_bad "Node 包下载失败：$NODE_URL"; exit 1; }
fi

printf '\n开始 provisioning（真 chroot 里跑 apt + npm，可能要十几分钟到半小时）…\n'
printf '  日志：%s/run/linux.log\n\n' "$LINUX_HOME"
# ⚠️ device-provision.sh **必须用 bash 跑**（它自己会在没有 BASH_VERSION 时直接退出）。
# 实测：用设备自带的 mksh（`sh script.sh`）跑会立刻 "需要 bash" 退出码 1 —— 所以这里显式挑 bash，
# 并把 bash 所在目录放进 PATH（脚本内部还会用 tar/mkfs.erofs 等，Termux 那套更全）。
BASH_BIN="$(find_bash || true)"
if [ -z "$BASH_BIN" ]; then
  c_bad "找不到 bash，device-provision.sh 跑不起来 —— 见上面「工具链」那节的①②③"
  exit 1
fi
printf '用 %s 执行部署脚本\n\n' "$BASH_BIN"
PATH="$(dirname "$BASH_BIN"):$PATH" "$BASH_BIN" "$PROV" --seeds "$SEEDS_DIR"
RC=$?
if [ "$RC" != 0 ]; then
  c_bad "device-provision.sh 退出码 $RC —— 看日志：$LINUX_HOME/run/linux.log"
  exit "$RC"
fi
c_ok "provisioning 完成"

printf '\n启动环境…\n'
"$BIN_DIR/linuxctl" start || c_warn "start 没成功（看上面的输出；层齐了再试一次）"
printf '\n状态：\n'
"$BIN_DIR/linuxctl" status
exit 0
