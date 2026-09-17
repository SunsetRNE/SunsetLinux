#!/system/bin/sh
# =============================================================================
# sunsetlinux · module/customize.sh（KernelSU / Magisk 通用安装脚本）
#
# 安装时执行：把运行时脚本装到模块目录（= /data/adb/modules/sunsetlinux/bin），
# 做基本校验并给用户明确提示。
#
# **本脚本刻意不做的事**：
#   - 不在这里 provision（安装阶段没有 /data 完整就绪的保证，且要跑十几分钟到半小时，
#     装着"卡住"体验极差）。首次部署有两条路，都写在安装输出里：
#       ① **装完重启**：模块 service.sh 在 late_start 自己建层（零点击，1.0.9 起）；
#       ② 手动跑 device-provision.sh（想自己盯着进度时用）
#   - 不修改 SELinux 策略（需要时见同目录 sepolicy.rule 的说明）
#   - 不删除 /data/sunsetlinux（那是用户数据，卸载也不删，见 uninstall.sh）
#
# 环境变量（KernelSU/Magisk 安装器提供）：MODPATH、API
# =============================================================================

SKIPUNZIP=0
MODDIR="${MODPATH:-/data/adb/modules/sunsetlinux}"
LINUX_HOME="${LINUX_HOME:-/data/sunsetlinux}"

ui_print() { echo "$1"; }

ui_print "*******************************"
ui_print "  SunsetLinux — 原生 DSH 运行环境"
ui_print "*******************************"

# --- 1) 架构/内核校验：必须是 arm64 且内核支持 overlay + erofs ----------------
ABI="$(getprop ro.product.cpu.abi 2>/dev/null)"
case "$ABI" in
  arm64*|aarch64*) ui_print "- 架构：$ABI ✓" ;;
  *) ui_print "! 警告：当前 ABI=$ABI，本项目只提供 arm64 产物，环境将无法启动" ;;
esac

KREL="$(uname -r 2>/dev/null)"
ui_print "- 内核：$KREL"

# 只做**只读**探测：/proc/filesystems 是内核已注册的文件系统列表
FSLIST="$(cat /proc/filesystems 2>/dev/null | tr '\n' ' ')"
case "$FSLIST" in
  *overlay*) ui_print "- overlayfs：可用 ✓" ;;
  *) ui_print "! overlayfs 不可用 —— root 模式的分层挂载无法工作" ;;
esac
case "$FSLIST" in
  *erofs*) ui_print "- erofs：可用 ✓（本项目只读层首选格式）" ;;
  *) ui_print "! erofs 不可用" ;;
esac
case "$FSLIST" in
  *squashfs*) ui_print "- squashfs：可用" ;;
  *) ui_print "- 提示：内核不支持 squashfs（本机实测 CONFIG_SQUASHFS is not set），"
     ui_print "  因此只读层使用 erofs，请勿下载 squashfs 格式的层" ;;
esac
case "$FSLIST" in
  *ext4*) ui_print "- ext4：可用 ✓（可写层 upper.img 用）" ;;
  *) ui_print "! ext4 不可用 —— 可写层镜像无法挂载" ;;
esac

# --- 2) 安装 bin/ 到模块目录，并准备 /data/sunsetlinux 目录骨架 ---------------------
ui_print "- 模块目录：$MODDIR"
if [ -d "$MODDIR" ]; then
  ui_print "- bin/ 内容："
  ls -1 "$MODDIR/bin" 2>/dev/null | sed 's/^/    /'
fi

ui_print "- 环境根目录：$LINUX_HOME（用户数据，卸载模块时**保留**）"
if [ ! -d "$LINUX_HOME" ]; then
  ui_print "- 首次安装：创建目录骨架"
  mkdir -p "$LINUX_HOME/layers" "$LINUX_HOME/seeds" "$LINUX_HOME/cache" \
           "$LINUX_HOME/etc" "$LINUX_HOME/bin" "$LINUX_HOME/run" \
           "$LINUX_HOME/snapshots" "$LINUX_HOME/upper" "$LINUX_HOME/work" \
           "$LINUX_HOME/rootfs" "$LINUX_HOME/layers-mnt" 2>/dev/null
  chmod 0755 "$LINUX_HOME" 2>/dev/null
else
  ui_print "- 已存在，保留现有内容（幂等）"
fi

# --- 2b) 内置 DSH：把模块自带的层落到环境里（full 变体）-----------------------
# 见 docs/module-variants.md §2.3。为什么在**安装时**展开（而不是每次开机）：
#   · 解压约 200 MB，放开机流程里会拖慢启动；装模块时用户本来就在等，失败也当场看得见；
#   · 幂等由 linuxctl 判（同版本已落地直接跳过），所以重复装/覆盖装不会重复解压。
MODULE_VARIANT="$(sed -n 's/^variant=//p' "$MODDIR/module.prop" 2>/dev/null | head -n1)"
[ -n "$MODULE_VARIANT" ] || MODULE_VARIANT=full
if [ -f "$MODDIR/dsh/manifest.json" ]; then
  ui_print ""
  ui_print "- 本包含内置 DSH（变体 $MODULE_VARIANT）：展开到 $LINUX_HOME/layers/"
  _CTL="$MODDIR/bin/linuxctl.sh"
  if [ -f "$_CTL" ]; then
    _OUT="$(LINUX_HOME="$LINUX_HOME" /system/bin/sh "$_CTL" dsh builtin --module-dir "$MODDIR" 2>&1)"
    case "$_OUT" in
      *'"ok":true'*) ui_print "  内置 DSH 已就位（重启后开机自建 base/runtime 时直接用它）" ;;
      *) ui_print "! 内置 DSH 没展开成功（原因见下）——可在 root 终端重试同样这条命令" ;;
    esac
    printf '%s\n' "$_OUT" | while IFS= read -r _line; do
      ui_print "  $_line"
    done
  else
    ui_print "! 模块包里没有 bin/linuxctl.sh，无法展开内置 DSH（模块包不完整？）"
  fi
fi

# --- 3) 首次部署提示（★ 这一段是用户读得最多的地方：先把**推荐路径**说清楚）----
#
# 顺序上刻意"先重启、后手动"：1.0.9 起模块会在开机时自己建层，用户什么都不用做；
# 把半小时的手动命令放前面，只会让人以为必须手动跑（真机反馈）。
# 历史上这里还踩过两个坑，都写进注释防止回退：
#   · ui_print 的双引号串里**不能**再出现 ASCII 双引号 —— 会把句子截断，
#     后半句被当真命令执行（真机上就是"只在没有任何"后面没了）；
#   · 命令一律写 /system/bin/sh：裸 `sh` 在模块环境里可能是 busybox ash。
# 层名带版本（dsh-<版本>.erofs）也是常态：内置 DSH 展开出来的就是这种名字，
# 所以判定必须同时认"无版本名"与"带版本名"两种 —— 老写法只看 dsh.erofs 会把
# 已经装好的内置层当成"没有层"，再念一遍半小时的部署提示。
DSH_PRESENT=0
for _f in "$LINUX_HOME/layers/dsh.erofs" "$LINUX_HOME/layers/dsh.squashfs" \
          "$LINUX_HOME"/layers/dsh-*.erofs "$LINUX_HOME"/layers/dsh-*.squashfs; do
  [ -f "$_f" ] && { DSH_PRESENT=1; break; }
done
if [ "$DSH_PRESENT" = "0" ]; then
  VER="$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null | head -n1)"
  ui_print ""
  ui_print "****************************************"
  ui_print "  首次部署：装完**重启一次**就行"
  ui_print "****************************************"
  ui_print " 重启后模块会在开机时自己把环境建出来（零点击，不需要你敲任何命令）："
  ui_print "   Ubuntu base → Node + pnpm → DSH 三层只读镜像 + 可写层（约十几分钟到半小时，"
  ui_print "   日志：$LINUX_HOME/run/provision.log；期间可以正常用手机）。"
  ui_print ""
  case "$MODULE_VARIANT" in
    full)
      ui_print " 本包含内置 DSH：DSH 这一层不用等、也不用下载（其余两层照上面的流程建）。" ;;
    *)
      ui_print " 本包**不含** DSH：等 base/runtime 建好后，DSH 用一条指令从官方频道装（最快）："
      ui_print "   /system/bin/sh $LINUX_HOME/bin/linuxctl.sh dsh install" ;;
  esac
  ui_print ""
  ui_print " 两条可选的加速/排查路径："
  ui_print "   · 想自己盯着进度，就在 root 终端跑（KernelSU 管理器 / MT 管理器「以 root 执行」）："
  ui_print "       /system/bin/sh $MODDIR/bin/device-provision.sh --seeds $LINUX_HOME/seeds"
  ui_print "   · 想在装完就用：打开 SunsetLinux App →「部署向导」，或直接在「更新」页"
  ui_print "     从频道装预构建的层（最快，不用在手机上编译）。"
  ui_print ""
  ui_print " ★ 为什么写 /system/bin/sh 而不是 sh：Android 上 sh 可能解析到 busybox 的 ash，"
  ui_print "   而设备侧脚本是按 mksh（/system/bin/sh）写的。"
  ui_print " ★ profiles/ 随包携带（1.0.6 起），所以设备侧构建不会退化成空壳。"
  ui_print ""
  ui_print " 部署完成后（也可以只看 App 的状态卡）："
  ui_print "   /system/bin/sh $LINUX_HOME/bin/linuxctl.sh status    # 看 JSON 状态"
  ui_print "   /system/bin/sh $LINUX_HOME/bin/linuxctl.sh start     # 手动启动（开机也会自动启）"
  ui_print ""
  [ -n "$VER" ] && ui_print " 本次安装的模块版本：$VER"
  ui_print ""
else
  ui_print "- 已检测到 DSH 层，开机将由 service.sh 自动启动"
fi

# --- 4) 挂载实现探测（用户明确要求：刷写流程里做自动挂载检测） ----------------
# 背景：KernelSU 某版本起**删掉了自带的模块挂载实现**，完全交给第三方 metamodule
# （具体实现由第三方提供，各设备不同）；Magisk 仍是原生挂载。社区里大量模块是"自动挂载"型，
# 它们的假设在装了 metamodule 的环境里经常不成立。
# 我们即使不需要挂载，也必须**探测 + 报告 + 显式声明不需要**：
#   ① 早报告能省掉大量排查；② 让用户一眼看到这个模块不会受 metamodule 变动影响。
ui_print ""
ui_print "------------------------------"
ui_print "- 挂载实现探测"
ui_print "------------------------------"
DETECT="$MODDIR/lib/detect-mount.sh"
if [ -f "$DETECT" ]; then
  # 逐行打印探测报告（不把整段塞进一次 ui_print，安装器日志更好读）
  # ★ 显式给模块根：detect-mount.sh 不允许再"猜"（真机上猜成 cwd 会误报需要挂载）
  MODULE_ROOT="$MODDIR"
  export MODULE_ROOT
  # shellcheck source=/dev/null
  . "$DETECT" 2>/dev/null || true
  if command -v detect_mount_report >/dev/null 2>&1; then
    detect_mount_report 2>/dev/null | while IFS= read -r line; do ui_print "  $line"; done
  else
    ui_print "  (探测函数载入失败，跳过)"
  fi
  ui_print ""
  if module_needs_mount; then
    ui_print "- 结论：本模块需要挂载系统路径（module/ 下有 system/ 等目录）"
  else
    ui_print "★ 本模块不挂载任何系统路径，无需 metamodule 支持。"
    ui_print "  （module/ 下没有 system/、system_ext/、vendor/、product/、odm/，"
    ui_print "    只有脚本与 webroot/，因此 KernelSU 删除自带挂载实现这件事"
    ui_print "    对本模块没有影响，也不会和任何 metamodule 冲突。）"
  fi
else
  ui_print "  警告：找不到 $DETECT，跳过挂载实现探测"
fi

# --- 5) 冻结/省电豁免提示（重要：findings §3.2） ------------------------------
ui_print ""
ui_print "- 建议把 SunsetLinux 加入「墓碑调度/冻结类」模块的豁免名单（若你装了这类模块），"
ui_print "  以及系统电池优化白名单，否则 App 侧状态卡与 WebView 会被冻住。"
ui_print "  环境本体（node 进程）由本模块在 late_start 启动，不依附 App。"

set_perm_recursive "$MODDIR" 0 0 0755 0644 2>/dev/null || true
set_perm_recursive "$MODDIR/bin" 0 0 0755 0755 2>/dev/null || true
set_perm_recursive "$MODDIR/lib" 0 0 0755 0644 2>/dev/null || true
# WebUI：KernelSU 要求 webroot/index.html 可读；浏览器端不需要可执行位
set_perm_recursive "$MODDIR/webroot" 0 0 0755 0644 2>/dev/null || true
[ -f "$MODDIR/webroot/index.html" ] && ui_print "- 模块 WebUI：webroot/index.html 已就位" \
  || ui_print "- 提示：没有 webroot/index.html（KernelSU 管理器的 WebUI 入口会不可用）"
