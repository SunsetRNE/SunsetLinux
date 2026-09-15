#!/system/bin/sh
# =============================================================================
# dshroid · module/customize.sh（KernelSU / Magisk 通用安装脚本）
#
# 安装时执行：把运行时脚本装到模块目录（= /data/adb/modules/dshroid/bin），
# 做基本校验并给用户明确提示。
#
# **本脚本刻意不做的事**：
#   - 不自动 provision（首次部署涉及 mount/chroot，耗时且要联网，必须由用户在
#     root 终端显式执行 device-provision.sh，或由 App 的 ProvisionActivity 触发）
#   - 不修改 SELinux 策略（需要时见同目录 sepolicy.rule 的说明）
#   - 不删除 /data/linux（那是用户数据，卸载也不删，见 uninstall.sh）
#
# 环境变量（KernelSU/Magisk 安装器提供）：MODPATH、API
# =============================================================================

SKIPUNZIP=0
MODDIR="${MODPATH:-/data/adb/modules/dshroid}"
LINUX_HOME="${LINUX_HOME:-/data/linux}"

ui_print() { echo "$1"; }

ui_print "*******************************"
ui_print "  DSHroid — 原生 DSH 运行环境"
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

# --- 2) 安装 bin/ 到模块目录，并准备 /data/linux 目录骨架 ---------------------
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

# --- 3) 首次部署提示 ---------------------------------------------------------
if [ ! -f "$LINUX_HOME/layers/dsh.erofs" ] && [ ! -f "$LINUX_HOME/layers/dsh.squashfs" ]; then
  ui_print ""
  ui_print "****************************************"
  ui_print " 还需要做一次「首次部署」（provision）"
  ui_print "****************************************"
  ui_print " 请在自己的 root 终端执行（Termux+su / adb shell su）："
  ui_print ""
  ui_print "   sh $MODDIR/bin/device-provision.sh"
  ui_print ""
  ui_print " 它会原生完成：Ubuntu base → Node+pnpm → DSH 三层镜像 + 可写层。"
  ui_print " 也可以打开 DSHroid App 用「首次部署向导」触发同一脚本。"
  ui_print ""
  ui_print " 部署完成后："
  ui_print "   sh $LINUX_HOME/bin/linuxctl.sh status    # 看 JSON 状态"
  ui_print "   sh $LINUX_HOME/bin/linuxctl.sh start     # 手动启动（开机也会自动启）"
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
ui_print "- 建议把 DSHroid 加入「墓碑调度/冻结类」模块的豁免名单（若你装了这类模块），"
ui_print "  以及系统电池优化白名单，否则 App 侧状态卡与 WebView 会被冻住。"
ui_print "  环境本体（node 进程）由本模块在 late_start 启动，不依附 App。"

set_perm_recursive "$MODDIR" 0 0 0755 0644 2>/dev/null || true
set_perm_recursive "$MODDIR/bin" 0 0 0755 0755 2>/dev/null || true
set_perm_recursive "$MODDIR/lib" 0 0 0755 0644 2>/dev/null || true
# WebUI：KernelSU 要求 webroot/index.html 可读；浏览器端不需要可执行位
set_perm_recursive "$MODDIR/webroot" 0 0 0755 0644 2>/dev/null || true
[ -f "$MODDIR/webroot/index.html" ] && ui_print "- 模块 WebUI：webroot/index.html 已就位" \
  || ui_print "- 提示：没有 webroot/index.html（KernelSU 管理器的 WebUI 入口会不可用）"
