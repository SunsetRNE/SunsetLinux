#!/system/bin/sh
# =============================================================================
# dshroid · module/uninstall.sh
#
# **默认保留 /data/linux 用户数据**，只清理模块自身的运行痕迹，并打印提示。
#
# 为什么默认保留：/data/linux 里有用户的环境（可写层里的会话、settings.yaml、
# 快照、证书、可能的 API key）。卸载模块是"换/去插件"，不是"删用户数据"。
# 想彻底清掉必须**显式**做，见下面的 DSHRDOID_PURGE 开关与提示。
#
# 注意：KernelSU/Magisk 会在卸载时自动删除模块目录本身，
# 这里不要试图 rm -rf "$MODDIR"。
# =============================================================================

LINUX_HOME="${LINUX_HOME:-/data/linux}"

echo "=============================================="
echo " DSHroid 模块已卸载"
echo "=============================================="
echo ""
echo " 用户数据 **已保留**：$LINUX_HOME"
echo "   - 环境本体（只读层、可写层、快照）都在那里，重新安装模块即可继续用"
echo "   - 若确实要彻底删除（不可恢复），请手动执行："
echo ""
echo "       su -c 'rm -rf $LINUX_HOME'"
echo ""
echo "   （想保留可写层但复用只读层，可以先备份："
echo "     su -c '$LINUX_HOME/bin/linuxctl.sh snapshot before-uninstall'）"
echo ""

# 停掉正在运行的环境（如果还活着）——不这么做会留下孤儿 node 与被占用的端口
CTL=""
for c in "$LINUX_HOME/bin/linuxctl" "$LINUX_HOME/bin/linuxctl.sh"; do
  [ -f "$c" ] && { CTL="$c"; break; }
done
if [ -n "$CTL" ] && [ -f "$LINUX_HOME/run/supervisor.pid" ]; then
  echo " 正在停止仍在运行的环境..."
  if LINUX_HOME="$LINUX_HOME" sh "$CTL" stop >> "$LINUX_HOME/run/linux.log" 2>&1; then
    echo " 已停止。"
  else
    echo " 停止命令返回非 0，请检查 $LINUX_HOME/run/linux.log"
    echo " 也可手动：kill \$(cat $LINUX_HOME/run/supervisor.pid)"
  fi
fi

# 只清模块自己留下的"运行态"文件，不动 layers/upper.img/snapshots/seeds
if [ -d "$LINUX_HOME/run" ]; then
  rm -f "$LINUX_HOME/run/ready" \
        "$LINUX_HOME/run/mounted.json" \
        "$LINUX_HOME/run/dsh.pid" \
        "$LINUX_HOME/run/dsh.url" \
        "$LINUX_HOME/run/dsh.port" \
        "$LINUX_HOME/run/supervisor.pid" 2>/dev/null
  : > "$LINUX_HOME/run/mounts" 2>/dev/null
fi

# 若曾被显式要求清洗（用户自己设的环境变量），才删除用户数据
if [ "${DSHROID_PURGE:-0}" = "1" ]; then
  echo " DSHROID_PURGE=1：正在删除 $LINUX_HOME（不可恢复）"
  rm -rf "$LINUX_HOME" 2>/dev/null
  echo " 已删除。"
fi

echo ""
echo " 提示：卸载模块不会删除 App 数据；如需一并清理，请单独卸载 DSHroid App。"
echo ""
exit 0
