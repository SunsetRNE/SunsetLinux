#!/system/bin/sh
# =============================================================================
# sunsetlinux · module/uninstall.sh
#
# 由 KernelSU/Magisk 在**卸载模块时**自动调用。两件事：
#   1) 停掉可能还在跑的环境（不停会留下孤儿 node 和被占用的端口）；
#   2) **默认保留用户数据**，只清模块自己的运行态痕迹，并把"还剩什么"逐项打出来。
#
# ## 为什么默认保留
#   /data/sunsetlinux 里是**用户的数据**：可写层里的会话与 settings.yaml、快照、
#   证书、可能的 API key。卸载模块是"拆掉宿主"，不是"删用户的活"。想一起删必须
#   **显式开启**。
#
# ## 为什么用标记文件而不是环境变量
#   本脚本由模块管理器直接 exec，用户**没有任何机会**给它设环境变量；而且
#   KernelSU 卸载时是 `/system/bin/sh uninstall.sh`，父进程环境也不受用户控制。
#   所以环境变量开关是**不可达代码**（写了等于没写，还会误导用户以为能生效）。
#   改用标记文件：`$LINUX_HOME/etc/PURGE_ON_UNINSTALL`，
#   由 App 的「彻底卸载」或 `linuxctl purge --arm` 写入，用户看得见、删得掉。
#
# ## 卸载路径（三条，互不重叠）
#   - 模块目录        KernelSU 自己删，本脚本**不要**碰（碰了会打断管理器流程）
#   - /data/sunsetlinux   内核态环境，App 沙箱外 → 只有这里能清，故用标记文件
#   - App 私有数据    Android 在卸载 App 时由系统清理，模块这边不用管也管不了
# =============================================================================

LINUX_HOME="${LINUX_HOME:-/data/sunsetlinux}"
PURGE_MARKER="$LINUX_HOME/etc/PURGE_ON_UNINSTALL"

# ---- 体积（KB）：干净地报出"还剩什么" --------------------------------------
_sz_kb() { # _sz_kb <路径> → KB（不存在输出 0）
  [ -e "$1" ] || { echo 0; return 0; }
  du -sk "$1" 2>/dev/null | awk '{print $1; exit}' || echo 0
}
_human() { # _human <KB> → 人类可读
  awk -v k="${1:-0}" 'BEGIN{
    if (k >= 1048576) printf "%.2f GB", k/1048576;
    else if (k >= 1024) printf "%.1f MB", k/1024;
    else printf "%d KB", k;
  }'
}
_row() { # _row <标签> <路径> <KB>
  printf '   %-14s %-34s %s\n' "$1" "$2" "$(_human "$3")"
}

echo "=============================================="
echo " SunsetLinux 模块已卸载"
echo "=============================================="
echo ""

# ---- 1) 先停环境（幂等；本就停了也无副作用）---------------------------------
CTL=""
for c in "$LINUX_HOME/bin/linuxctl" "$LINUX_HOME/bin/linuxctl.sh"; do
  [ -f "$c" ] && { CTL="$c"; break; }
done
if [ -n "$CTL" ] && [ -f "$LINUX_HOME/run/supervisor.pid" ]; then
  echo " 正在停止仍在运行的环境…"
  # 用 /system/bin/sh（mksh）发起，或直接执行走 shebang —— **不要裸 `sh`**：
  # 卸载器的 PATH 里 `sh` 可能是 busybox ash，它解析不了设备侧脚本，
  # 结果是"环境没停掉、模块先被删"，留下挂载点（见 linuxctl.sh 的 pick_shell 注释）。
  if [ -x "$CTL" ]; then
    LINUX_HOME="$LINUX_HOME" "$CTL" stop >> "$LINUX_HOME/run/linux.log" 2>&1
  else
    LINUX_HOME="$LINUX_HOME" /system/bin/sh "$CTL" stop >> "$LINUX_HOME/run/linux.log" 2>&1
  fi
  if [ $? -eq 0 ]; then
    echo " 已停止。"
  else
    echo " ⚠️ 停止返回非 0，请看 $LINUX_HOME/run/linux.log"
    echo "    也可手动：kill \$(cat $LINUX_HOME/run/supervisor.pid)"
  fi
  echo ""
fi

# ---- 2) 清模块自己的"运行态"痕迹（不动 layers/upper.img/snapshots/seeds）-----
if [ -d "$LINUX_HOME/run" ]; then
  rm -f "$LINUX_HOME/run/ready" \
        "$LINUX_HOME/run/mounted.json" \
        "$LINUX_HOME/run/dsh.pid" \
        "$LINUX_HOME/run/dsh.url" \
        "$LINUX_HOME/run/dsh.port" \
        "$LINUX_HOME/run/supervisor.pid" 2>/dev/null
  : > "$LINUX_HOME/run/mounts" 2>/dev/null
fi

# ---- 3) 按标记决定：保留还是连用户数据一起删 --------------------------------
if [ -f "$PURGE_MARKER" ]; then
  TOTAL="$(_sz_kb "$LINUX_HOME")"
  echo " 检测到「卸载即清除」标记：$PURGE_MARKER"
  echo " 正在删除全部用户数据（$(_human "$TOTAL")，**不可恢复**）…"
  echo ""
  rm -rf "$LINUX_HOME" 2>/dev/null
  if [ -d "$LINUX_HOME" ]; then
    LEFT="$(_sz_kb "$LINUX_HOME")"
    echo " ⚠️ 没删干净：仍残留 $(_human "$LEFT")"
    echo "    常见原因：还有挂载点占用该目录。请重启后执行一次："
    echo "      su -c 'umount -R $LINUX_HOME 2>/dev/null; rm -rf $LINUX_HOME'"
  else
    echo " ✅ 已全部删除：$LINUX_HOME 不存在了"
  fi
else
  TOTAL="$(_sz_kb "$LINUX_HOME")"
  echo " 用户数据 **已保留**（这是默认行为）。"
  echo ""
  echo " 还留在设备上的东西："
  _row "环境根" "$LINUX_HOME" "$TOTAL"
  _row "  layers/" "$LINUX_HOME/layers" "$(_sz_kb "$LINUX_HOME/layers")"
  _row "  upper.img" "$LINUX_HOME/upper.img" "$(_sz_kb "$LINUX_HOME/upper.img")"
  _row "  snapshots/" "$LINUX_HOME/snapshots" "$(_sz_kb "$LINUX_HOME/snapshots")"
  _row "  seeds/" "$LINUX_HOME/seeds" "$(_sz_kb "$LINUX_HOME/seeds")"
  APP_DATA=/data/data/io.github.sunsetrne.sunsetlinux
  _row "App 私有数据" "$APP_DATA" "$(_sz_kb "$APP_DATA")"
  echo "                  （在 App 沙箱里，卸载 App 时由系统清理）"
  echo ""
  echo " 重新安装模块即可继续用（环境本体和可写层都在原地）。"
  echo ""
  echo " 要**彻底删除**，二选一："
  echo ""
  echo "   A) 直接删（立刻生效，不可恢复）"
  echo "        su -c '$LINUX_HOME/bin/linuxctl purge --yes'"
  echo "      或 su -c 'rm -rf $LINUX_HOME'"
  echo ""
  echo "   B) 先留着、下次卸载模块时自动删"
  echo "        su -c '$LINUX_HOME/bin/linuxctl purge --arm'"
  echo "      （或在 App 里开启「彻底卸载」；撤销用 purge --disarm）"
  echo ""
  echo " 想留一份可写层再删："
  echo "   su -c '$LINUX_HOME/bin/linuxctl snapshot before-uninstall'"
fi

echo ""
echo " 提示：模块目录由 KernelSU 自行删除，本脚本未触碰；App 数据请卸载 App 清理。"
echo ""
exit 0
