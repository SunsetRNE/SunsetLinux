#!/system/bin/sh
# =============================================================================
# sunsetlinux · module/post-fs-data.sh
#
# 早期启动阶段（post-fs-data）：**只准备目录与权限，不启动任何进程**。
# 为什么不在这一阶段启动：此时 /data 刚挂好，zygote/网络/DNS 都还没就绪，
# 起 node 会拿不到可用的 DNS 与 netd 状态；启动放 service.sh（late_start）。
#
# 本脚本做四件事：
#   1. 建好 /data/sunsetlinux 及子目录（幂等）
#   2. 把模块自带的 bin/ 同步到 /data/sunsetlinux/bin（模块升级后立即生效，
#      且 App 侧 linuxctl 路径固定为 $LINUX_HOME/bin/linuxctl.sh）
#   3. 修正权限（0700/0755；run/ 里的凭证文件由 supervise.sh 自己设 0600）
#   4. 清理上次关机可能残留的"运行态"标记（避免 App 读到过期状态）
#
# 注意：KernelSU 的 post-fs-data 运行在阻塞早期阶段，命令要短、要幂等。
# =============================================================================

MODDIR="${0%/*}"
LINUX_HOME="${LINUX_HOME:-/data/sunsetlinux}"
LOG="$LINUX_HOME/run/module.log"

mkdir -p "$LINUX_HOME/run" 2>/dev/null

log() {
  # 模块脚本日志同时进 dmesg（早期阶段 logcat 可能还没起来）与文件
  echo "[sunsetlinux][post-fs-data] $*" >> "$LOG" 2>/dev/null
  echo "[sunsetlinux][post-fs-data] $*" > /dev/kmsg 2>/dev/null
}

log "开始"

# --- 1) 目录骨架 -------------------------------------------------------------
for d in layers layers-mnt seeds cache etc bin run snapshots upper work rootfs; do
  mkdir -p "$LINUX_HOME/$d" 2>/dev/null
done
chmod 0755 "$LINUX_HOME" 2>/dev/null
chmod 0700 "$LINUX_HOME/run" 2>/dev/null
log "目录骨架就绪"

# --- 2) 同步运行时脚本 -------------------------------------------------------
# ★ **列表由目录派生，新增脚本无需改这里**（历史上手写列表导致同一类 bug 犯了三次：
#   detect-mount.sh 没进包、lib/ 没人同步、update.sh 没进包 → 功能静默消失）。
#
# ★ 来源可以是两处（按优先级）：
#     ① $LINUX_HOME/cache/updates/bin-packages/<ver>/  —— WebUI 下载并暂存的**更新包**
#        （有它就用它，并写 etc/bin-version <ver>、清掉 etc/bin-pending）
#     ② $MODDIR                                        —— 回落到模块目录自带的 bin/
#   这样既能让 WebUI 安全地落地一份更新的运行时脚本，又能在模块升级后自动跟上，
#   **且不需要在脚本里比较版本号**（"优先用暂存包、没有就用模块自带的"即可）。
#
#   版本记录：
#     etc/bin-version = **已生效**的脚本来自哪个版本（模块版本 或 暂存包版本）
#     etc/bin-pending = 有暂存包但还没生效（下次开机生效）
#   回滚方式：`rm -rf $LINUX_HOME/cache/updates/bin-packages/<ver> $LINUX_HOME/etc/bin-pending`
#             下次开机就回落到模块目录里自带的 bin/（见 update.sh module-apply 的提示）。
BIN_SRC="$MODDIR/bin"
BIN_VER_RAW="$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null | head -n1)"
EFFECTIVE_SRC="module"
STAGE_ROOT="$LINUX_HOME/cache/updates/bin-packages"
if [ -d "$STAGE_ROOT" ]; then
  # 取版本号最大的暂存包（sort -V 在 toybox/GNU 都有）
  STAGED_VER="$(ls -1 "$STAGE_ROOT" 2>/dev/null | sort -V | tail -n1)"
  if [ -n "$STAGED_VER" ] && [ -d "$STAGE_ROOT/$STAGED_VER/bin" ]; then
    BIN_SRC="$STAGE_ROOT/$STAGED_VER/bin"
    BIN_VER_RAW="$STAGED_VER"
    EFFECTIVE_SRC="staged"
    log "使用暂存脚本包：$STAGE_ROOT/$STAGED_VER（版本 $STAGED_VER）"
  fi
fi

if [ -d "$BIN_SRC" ]; then
  n=0
  for f in "$BIN_SRC"/*; do
    [ -e "$f" ] || continue
    b="$(basename "$f")"
    rm -rf "$LINUX_HOME/bin/$b.new"
    cp -rf "$f" "$LINUX_HOME/bin/$b.new" 2>/dev/null || continue
    rm -rf "$LINUX_HOME/bin/$b"
    mv "$LINUX_HOME/bin/$b.new" "$LINUX_HOME/bin/$b" 2>/dev/null && n=$((n+1))
  done
  chmod 0755 "$LINUX_HOME/bin"/*.sh 2>/dev/null
  chmod 0644 "$LINUX_HOME/bin"/common/*.sh 2>/dev/null
  chmod 0644 "$LINUX_HOME/bin"/fixtures/* 2>/dev/null
  # 契约路径：$LINUX_HOME/bin/linuxctl（无扩展名，App 直接调用）
  if [ -f "$LINUX_HOME/bin/linuxctl.sh" ]; then
    ln -sfn linuxctl.sh "$LINUX_HOME/bin/linuxctl" 2>/dev/null
  fi
  # 记录已生效版本；如果是暂存包，同时清掉 pending 标记
  mkdir -p "$LINUX_HOME/etc" 2>/dev/null
  printf '%s\n' "$BIN_VER_RAW" > "$LINUX_HOME/etc/bin-version" 2>/dev/null
  [ "$EFFECTIVE_SRC" = "staged" ] && rm -f "$LINUX_HOME/etc/bin-pending" 2>/dev/null
  log "同步运行时脚本 $n 项（来源=$EFFECTIVE_SRC，版本=${BIN_VER_RAW:-未知}）"
else
  log "警告：$BIN_SRC 不存在，跳过脚本同步"
fi

# --- 2a) module/lib → $LINUX_HOME/lib（**不是 bin/**）-------------------------
# doctor.sh 会去 $SELF_DIR/../lib/detect-mount.sh 找挂载实现探测；不铺它
# doctor §1c 就只会打 "[skip] 找不到 lib/detect-mount.sh"（不崩，但功能没了）。
LIB_SRC="$MODDIR/lib"
if [ -d "$LIB_SRC" ]; then
  mkdir -p "$LINUX_HOME/lib" 2>/dev/null
  for f in "$LIB_SRC"/*; do
    [ -e "$f" ] || continue
    b="$(basename "$f")"
    rm -f "$LINUX_HOME/lib/$b.new"
    cp -f "$f" "$LINUX_HOME/lib/$b.new" 2>/dev/null || continue
    mv -f "$LINUX_HOME/lib/$b.new" "$LINUX_HOME/lib/$b" 2>/dev/null
  done
  chmod 0644 "$LINUX_HOME/lib"/* 2>/dev/null
  log "同步 lib/ 到 $LINUX_HOME/lib（目录派生）"
fi

# --- 2b) webroot/ 说明（**刻意不同步**）--------------------------------------
# KernelSU 的模块 WebUI 是管理器**直接从模块目录**读 webroot/index.html 的，
# 不经过 /data/sunsetlinux，所以这里不同步它（同步过去也没有任何东西会去读）。
# 若哪天要改 WebUI，改 module/webroot/index.html 后重新刷模块即可。
if [ -f "$MODDIR/webroot/index.html" ]; then
  log "WebUI 就位：$MODDIR/webroot/index.html（管理器直接读模块目录，无需同步）"
else
  log "提示：模块目录里没有 webroot/index.html，KernelSU 的 WebUI 入口不可用"
fi

# --- 3) 清理上次关机残留的运行态标记 -----------------------------------------
# ready/mounted.json/dsh.pid/dsh.url 都是"这次开机有效"的东西；
# 上次关机若没走 stop.sh，留着会让 App 误判为 running。
# env-mode（full / env-only，§3.4）也一并清：它是"本次启动"的属性，跨开机留着只会误导。
rm -f "$LINUX_HOME/run/ready" \
      "$LINUX_HOME/run/mounted.json" \
      "$LINUX_HOME/run/dsh.pid" \
      "$LINUX_HOME/run/dsh.url" \
      "$LINUX_HOME/run/dsh.port" \
      "$LINUX_HOME/run/env-mode" \
      "$LINUX_HOME/run/supervisor.pid" \
      "$LINUX_HOME/run/stopping" 2>/dev/null
: > "$LINUX_HOME/run/mounts" 2>/dev/null
log "已清理上次开机的运行态标记"

log "完成"
exit 0
