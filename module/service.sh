#!/system/bin/sh
# =============================================================================
# dshroid · module/service.sh
#
# late_start 阶段调用 `linuxctl start` —— **开机自启，与 App 完全无关**。
# 这正是"环境不被 App 杀死"的关键（architecture.md §1 生命周期 / §6.1）。
#
# 设计要点：
#   1. 本脚本必须**立刻返回**，不能阻塞 boot。start.sh 内部已经用
#      setsid + unshare --fork 把守护进程脱离出去，所以这里直接调用即可；
#      为保险再套一层 `&`。
#   2. 尊重 etc/config.json 的 autostart 开关（App 设置页可关）。
#   3. 失败不能让 boot 崩：只记日志，退出码始终 0。
#   4. 不做循环重试：反复失败时留证据（run/last-error）比静默重试更有用。
# =============================================================================

MODDIR="${0%/*}"
LINUX_HOME="${LINUX_HOME:-/data/linux}"
RUN="$LINUX_HOME/run"
LOG="$RUN/linux.log"
SERVICE_LOG="$RUN/service.log"

mkdir -p "$RUN" 2>/dev/null
: >> "$SERVICE_LOG" 2>/dev/null

log() {
  local line
  line="[$(date '+%Y-%m-%d %H:%M:%S')] [service.sh] $*"
  echo "$line" >> "$SERVICE_LOG" 2>/dev/null
  echo "$line" >> "$LOG" 2>/dev/null
}

# 等 /data 完全就绪（late_start 一般已就绪，这里只是保险，最多等 30s）
i=0
while [ "$i" -lt 30 ]; do
  [ -d "$LINUX_HOME" ] && break
  sleep 1
  i=$((i + 1))
done
if [ ! -d "$LINUX_HOME" ]; then
  log "环境根目录 $LINUX_HOME 不存在，跳过（需先运行 device-provision.sh）"
  exit 0
fi

# --- autostart 开关 ----------------------------------------------------------
# 不依赖 jq：从 config.json 里抠 "autostart": true/false
AUTOSTART=1
CFG="$LINUX_HOME/etc/config.json"
if [ -f "$CFG" ]; then
  if grep -q '"autostart"[[:space:]]*:[[:space:]]*false' "$CFG" 2>/dev/null; then
    AUTOSTART=0
  fi
fi
if [ "$AUTOSTART" = "0" ]; then
  log "config.json 里 autostart=false，跳过开机自启"
  exit 0
fi

# --- 层是否已部署 -----------------------------------------------------------
HAVE_LAYER=0
for f in "$LINUX_HOME/layers/base.erofs" "$LINUX_HOME/layers/base.squashfs"; do
  [ -f "$f" ] && HAVE_LAYER=1 && break
done
if [ "$HAVE_LAYER" = "0" ]; then
  log "尚未 provision（没有 base 层），跳过开机自启"
  log "请在 root 终端执行：sh $MODDIR/bin/device-provision.sh"
  exit 0
fi

# --- 选一个可用的 linuxctl ---------------------------------------------------
CTL=""
for c in "$LINUX_HOME/bin/linuxctl" "$LINUX_HOME/bin/linuxctl.sh" "$MODDIR/bin/linuxctl.sh"; do
  [ -f "$c" ] && { CTL="$c"; break; }
done
if [ -z "$CTL" ]; then
  log "找不到 linuxctl，跳过"
  exit 0
fi

# --- 已运行就不重复启动（幂等；start 本身也幂等，这里是省一次 fork）----------
if [ -f "$RUN/ready" ] && [ -f "$RUN/supervisor.pid" ]; then
  pid="$(cat "$RUN/supervisor.pid" 2>/dev/null | tr -dc '0-9')"
  if [ -n "$pid" ] && [ -d "/proc/$pid" ]; then
    log "环境已在运行（ns_pid=$pid），跳过"
    exit 0
  fi
fi

log "启动环境：LINUX_HOME=$LINUX_HOME $CTL start"
(
  LINUX_HOME="$LINUX_HOME" sh "$CTL" start >> "$SERVICE_LOG" 2>&1
) &
# 不等待：start.sh 自己会 fork 出守护进程，这里只是发起
log "已在后台发起启动（不阻塞 boot）"
exit 0
