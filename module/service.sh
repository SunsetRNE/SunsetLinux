#!/system/bin/sh
# =============================================================================
# sunsetlinux · module/service.sh
#
# late_start 阶段做两件事：
#   1. **还没建层就自己建**（零点击部署）—— 装机流程应该是
#        「装模块 → 重启 → 自己建 → 自己起」，不需要先开 App 再点一下"执行部署"。
# --- 自动应用 loop 可写层所需的 SELinux 规则（真机 2026-09-17 验证过写法）-----------
# 为什么需要：loop 的 I/O 在 kernel 域（kworker）里做，本机策略没给 kernel 域
#   system_data_file 的**写**权限 → `mount -t ext4 -o loop,rw upper.img` 报 I/O error
#   （EXT4-fs: I/O error while writing superblock）。规则只有一条、只放开"写"（读本来就允许）。
# 为什么每次开机都要打：ksud sepolicy patch/apply 是**运行时会话级**的，重启即失效。
# 关掉它（例如你不想扩大策略面）：touch /data/sunsetlinux/etc/sepolicy-loop.disabled
_SL_ETC=/data/sunsetlinux/etc
_SL_RUN=/data/sunsetlinux/run
_SL_KS=/data/adb/ksu/bin/ksud
mkdir -p "$_SL_ETC" "$_SL_RUN" 2>/dev/null || true
if [ -x "$_SL_KS" ] && [ ! -f "$_SL_ETC/sepolicy-loop.disabled" ]; then
    printf '%s\n' 'allow kernel system_data_file file write;' > "$_SL_ETC/sepolicy-loop.rule" 2>/dev/null || true
    if "$_SL_KS" sepolicy apply "$_SL_ETC/sepolicy-loop.rule" >>"$_SL_RUN/service.log" 2>&1; then
        printf '[%s] [service.sh] 已应用 loop 写权限规则：allow kernel system_data_file file write;\n' \
            "$(date '+%Y-%m-%d %H:%M:%S')" >>"$_SL_RUN/service.log" 2>/dev/null || true
    else
        printf '[%s] [service.sh] WARN: 应用 loop 写权限规则失败（loop 模式会挂不上可写层，原因见上一行）\n' \
            "$(date '+%Y-%m-%d %H:%M:%S')" >>"$_SL_RUN/service.log" 2>/dev/null || true
    fi
fi

#   2. 层齐了或者建完了 → `linuxctl start` 开机自启（与 App 完全无关，
#      这就是"环境不被 App 杀死"的关键，architecture.md §1 / §6.1）。
#
# 设计要点（每条都是"不许静默 / 不许循环"）：
#   1. 本脚本必须**立刻返回**，绝不阻塞 boot：真正的活儿都用 setsid 脱离出去跑。
#   2. 自动部署**先落标记再启动**（run/.auto-provision-attempted）：
#      构建要十几分钟到半小时，中途断电/失败后不能每次开机重来一遍 —— 那才是灾难。
#      失败不重试，证据留在 cache/provision.log 与 run/service.log，doctor 会报出来。
#   3. 触发条件全部前置检查：种子在、磁盘够、config.json 没关（auto_provision=false）。
#   4. 尊重 etc/config.json 的 autostart 开关（App 设置页可关）。
#   5. 任何失败都不能让 boot 崩：退出码始终 0。
# =============================================================================

MODDIR="${0%/*}"
LINUX_HOME="${LINUX_HOME:-/data/sunsetlinux}"
RUN="$LINUX_HOME/run"
LOG="$RUN/linux.log"
SERVICE_LOG="$RUN/service.log"
PROV_LOG="$LINUX_HOME/cache/provision.log"
SEEDS="$LINUX_HOME/seeds"
MARKER="$RUN/.auto-provision-attempted"
AUTOPROV_MIN_KB=2097152     # 2 GiB：构建期的树（base+node+包）加上三层镜像

mkdir -p "$RUN" 2>/dev/null
: >> "$SERVICE_LOG" 2>/dev/null

log() {
  local line
  line="[$(date '+%Y-%m-%d %H:%M:%S')] [service.sh] $*"
  echo "$line" >> "$SERVICE_LOG" 2>/dev/null
  echo "$line" >> "$LOG" 2>/dev/null
}

# =============================================================================
# 自动部署的**决策**（纯函数，便于单测：tools/provision-selftest.mjs 会把它抽出来跑）
#   autoprovision_decision <有base层 0/1> <种子齐 0/1> <已尝试过 0/1> <开关开 0/1> <剩余KB>
#   输出 "yes" 或 "no:<一句话原因>"（原因进日志，用户能看到"为什么这次没自动建"）
# =============================================================================
autoprovision_decision() {
  local have_base="$1" seeds_ok="$2" tried="$3" enabled="$4" free_kb="$5"

  [ "$enabled" = "1" ] || { printf 'no:config.json 里 auto_provision=false'; return 0; }
  [ "$have_base" != "1" ] || { printf 'no:已经有 base 层了，不需要部署'; return 0; }
  [ "$tried" != "1" ] || {
    printf 'no:自动部署已经尝试过一次（要再来一次：删掉 %s 后重启，或手动跑 device-provision.sh）' "$MARKER"
    return 0
  }
  [ "$seeds_ok" = "1" ] || { printf 'no:种子不全（%s 里需要 ubuntu-base-*.tar.gz）' "$SEEDS"; return 0; }

  case "$free_kb" in
    ''|*[!0-9]*) ;;                       # 拿不到空间就先不拦，让构建自己报错（日志里有）
    *) [ "$free_kb" -lt "$AUTOPROV_MIN_KB" ] && {
         printf 'no:/data 剩余空间不足（%s KB < %s KB）' "$free_kb" "$AUTOPROV_MIN_KB"
         return 0
       } ;;
  esac
  printf 'yes'
}

# 播种目录里至少要有 ubuntu-base 的 tarball（node 包缺失时构建期会自己在 chroot 里下）
seeds_ready() {
  local f=""
  for f in "$SEEDS"/ubuntu-base-*.tar.gz; do
    [ -f "$f" ] && return 0
  done
  return 1
}

free_kb_of_data() {
  df -k /data 2>/dev/null | awk 'NR==2{print $4}' | tr -dc '0-9'
}

# 真正的发起：落标记 → 写一个可回放的引导脚本 → setsid 脱离 boot 进程
start_auto_provision() {
  local prov="$MODDIR/bin/device-provision.sh" ctl="$1"
  [ -f "$prov" ] || { log "自动部署：找不到 $prov（模块包不完整？跳过）"; return 0; }

  : > "$MARKER" 2>/dev/null || true       # ★ 先落标记，再启动（见文件头第 2 条）
  mkdir -p "$LINUX_HOME/cache" 2>/dev/null

  # ★ 只用 /system/bin/sh（mksh），**绝不写裸 `sh`**：模块环境里 PATH 前面是
  #   KernelSU 的 busybox，`sh` 会解析成 busybox ash，而 ash 见到设备侧脚本里的
  #   `${BASH_SOURCE[0]:-$0}` 会直接 `syntax error: bad substitution` ——
  #   真机上就是"层文件都在、却永远挂不上"（2026-09-16 事故，见 linuxctl.sh 的 pick_shell）。
  cat > "$RUN/.auto-provision.sh" <<EOF
#!/system/bin/sh
# 由 service.sh 生成：自动部署的可回放引导脚本（失败时直接重放它即可）。
# 日志：$PROV_LOG
LINUX_HOME='$LINUX_HOME'
export LINUX_HOME
/system/bin/sh '$prov' --seeds '$SEEDS' >>'$PROV_LOG' 2>&1
rc=\$?
if [ "\$rc" = 0 ]; then
  echo "[\$(date '+%Y-%m-%d %H:%M:%S')] [service.sh] 自动部署完成，接着启动环境" >>'$PROV_LOG'
  if [ -x '$ctl' ]; then LINUX_HOME='$LINUX_HOME' '$ctl' start >>'$PROV_LOG' 2>&1
  else LINUX_HOME='$LINUX_HOME' /system/bin/sh '$ctl' start >>'$PROV_LOG' 2>&1; fi
fi
exit \$rc
EOF
  chmod 0755 "$RUN/.auto-provision.sh" 2>/dev/null || true

  log "自动部署：开始（十几分钟到半小时，日志 $PROV_LOG）"
  if [ -x /system/bin/setsid ]; then
    setsid /system/bin/sh "$RUN/.auto-provision.sh" >/dev/null 2>&1 &
  else
    ( /system/bin/sh "$RUN/.auto-provision.sh" >/dev/null 2>&1 ) &
  fi
  log "自动部署：已在后台发起（不阻塞 boot）"
}

# --- 等 /data 完全就绪（late_start 一般已就绪，这里只是保险，最多等 30s）------
i=0
while [ "$i" -lt 30 ]; do
  [ -d "$LINUX_HOME" ] && break
  sleep 1
  i=$((i + 1))
done
if [ ! -d "$LINUX_HOME" ]; then
  log "环境根目录 $LINUX_HOME 不存在，跳过（需先装模块并重启一次）"
  exit 0
fi

# --- config.json 开关（不依赖 jq：抠 "key": true/false）----------------------
AUTOSTART=1
AUTOPROV=1
CFG="$LINUX_HOME/etc/config.json"
if [ -f "$CFG" ]; then
  grep -q '"autostart"[[:space:]]*:[[:space:]]*false' "$CFG" 2>/dev/null && AUTOSTART=0
  grep -q '"auto_provision"[[:space:]]*:[[:space:]]*false' "$CFG" 2>/dev/null && AUTOPROV=0
fi

# --- 选一个可用的 linuxctl ---------------------------------------------------
CTL=""
for c in "$LINUX_HOME/bin/linuxctl" "$LINUX_HOME/bin/linuxctl.sh" "$MODDIR/bin/linuxctl.sh"; do
  [ -f "$c" ] && { CTL="$c"; break; }
done

# --- 层是否已部署 -----------------------------------------------------------
HAVE_LAYER=0
for f in "$LINUX_HOME/layers/base.erofs" "$LINUX_HOME/layers/base.squashfs"; do
  [ -f "$f" ] && HAVE_LAYER=1 && break
done
# 层名带版本（base-24.04.3-l1.erofs）也是常态，一并认
if [ "$HAVE_LAYER" = "0" ]; then
  for f in "$LINUX_HOME"/layers/base-*.erofs "$LINUX_HOME"/layers/base-*.squashfs; do
    [ -f "$f" ] && HAVE_LAYER=1 && break
  done
fi

# --- 内置 DSH 的自愈：安装时没展开成功 → 开机补一次 --------------------------
# 为什么需要（真机 2026-09-17）：`customize.sh` 是在**安装时**一次性展开内置 DSH 层的，
# 而那次失败（当时是空间检查的 32 位算术 bug 报"空间不足"，见 linuxctl dsh builtin 注释）
# 之后**没有任何第二次机会** —— 用户看到的就是"装了 full 模块，重启后照样起不来"。
# 这里只做一次**幂等**的尝试：模块里确实有载荷（full 变体：dsh/manifest.json）就跑。
#   ★ 不能只在"还没有内置记录"时才跑（2026-09-17 真机教训）：现场是"载荷早就落地、
#     state.json 却还指着坏的旧层"，只在缺记录时补就永远治不好这一种。
#     载荷已解压时 `linuxctl dsh builtin` 走 "already" 快路径（不重复解 200 MB），
#     但仍会做**启用判断**（生效层缺 web profile → 切到内置那份）。
# 并且用 setsid 脱离 boot 进程：解压约 200 MB，绝不能拖住开机。结果只进日志
# （`linuxctl dsh info` / doctor §7 都能看到它到底成没成）。
if [ -f "$MODDIR/dsh/manifest.json" ]; then
  _dhctl=""
  for c in "$LINUX_HOME/bin/linuxctl.sh" "$MODDIR/bin/linuxctl.sh"; do
    [ -f "$c" ] && { _dhctl="$c"; break; }
  done
  if [ -n "$_dhctl" ]; then
    log "内置 DSH 自愈检查（载荷存在）→ linuxctl dsh builtin（幂等：缺就展开，指向不对就纠正）"
    if [ -x /system/bin/setsid ]; then
      setsid /system/bin/sh -c "LINUX_HOME='$LINUX_HOME' /system/bin/sh '$_dhctl' dsh builtin --module-dir '$MODDIR' >>'$SERVICE_LOG' 2>&1" >/dev/null 2>&1 &
    else
      ( LINUX_HOME="$LINUX_HOME" /system/bin/sh "$_dhctl" dsh builtin --module-dir "$MODDIR" >>"$SERVICE_LOG" 2>&1 ) &
    fi
    log "内置 DSH 自愈已在后台发起（不阻塞 boot；结果见 $SERVICE_LOG）"
  else
    log "内置 DSH 自愈：找不到 linuxctl（模块包不完整？）"
  fi
fi

if [ "$HAVE_LAYER" = "0" ]; then
  log "尚未部署（没有 base 层）"
  SEEDS_OK=0; seeds_ready && SEEDS_OK=1
  TRIED=0;    [ -f "$MARKER" ] && TRIED=1
  DECISION="$(autoprovision_decision "$HAVE_LAYER" "$SEEDS_OK" "$TRIED" "$AUTOPROV" "$(free_kb_of_data)")"
  case "$DECISION" in
    yes)
      if [ -z "$CTL" ]; then
        log "自动部署：先把 linuxctl 铺好（$LINUX_HOME/bin/linuxctl 不存在，构建完会自己同步）"
      fi
      start_auto_provision "${CTL:-$MODDIR/bin/linuxctl.sh}"
      exit 0
      ;;
    *)
      log "自动部署：跳过（${DECISION#no:}）"
      log "  手动跑：/system/bin/sh $MODDIR/bin/device-provision.sh --seeds $SEEDS"
      exit 0
      ;;
  esac
fi

# --- 已部署：开机自启 --------------------------------------------------------
if [ "$AUTOSTART" = "0" ]; then
  log "config.json 里 autostart=false，跳过开机自启"
  exit 0
fi

if [ -z "$CTL" ]; then
  log "找不到 linuxctl，跳过"
  exit 0
fi

# 已运行就不重复启动（幂等；start 本身也幂等，这里是省一次 fork）
if [ -f "$RUN/ready" ] && [ -f "$RUN/supervisor.pid" ]; then
  pid="$(cat "$RUN/supervisor.pid" 2>/dev/null | tr -dc '0-9')"
  if [ -n "$pid" ] && [ -d "/proc/$pid" ]; then
    log "环境已在运行（ns_pid=$pid），跳过"
    exit 0
  fi
fi

# --- 内核 v2（sunsetd）：**先起内核，再发起环境启动** -------------------------
# 为什么顺序反过来了：内核是"谁在建树/环境在不在跑"的唯一权威（docs/core-v2-design.md）。
# 它先起来，模块这条 `linuxctl start` 就会被它**认领**成 owner=foreign-observer，
# App/CLI 读到的相位才是真的。内核起不来（缺 dex / app_process 不可用 / SELinux 域不允许）
# 完全不影响 v1 行为 —— 这里只记一行日志，绝不阻塞 boot、也不改变下面的启动路径。
# 脱离 terminal 常驻：setsid + 输出重定向（与 dsh 的 supervisor 同一套路）
if [ -f "$MODDIR/bin/sunsetd.dex" ] && [ -x /system/bin/app_process ] && [ -x /system/bin/setsid ]; then
  (
    LINUX_HOME="$LINUX_HOME" /system/bin/setsid /system/bin/app_process \
      -Djava.class.path="$MODDIR/bin/sunsetd.dex" /system/bin --nice-name=sunsetd \
      io.github.sunsetrne.sunsetd.MainKt >> "$SERVICE_LOG" 2>&1 </dev/null &
  ) &
  log "内核：已在后台发起 sunsetd（日志见 $SERVICE_LOG）"
else
  log "内核：跳过（缺 bin/sunsetd.dex 或 app_process/setsid）—— 按 v1 行为继续"
fi

log "启动环境：LINUX_HOME=$LINUX_HOME $CTL start"
# ★ 优先**直接执行**（内核按 shebang 用 /system/bin/sh 即 mksh 跑）；
#   退路也必须是 /system/bin/sh，不能是裸 `sh`（模块 PATH 里可能是 busybox ash，
#   它连设备侧脚本第一屏都解析不过 —— 真机上表现为"层都在、就是挂不上"）。
(
  if [ -x "$CTL" ]; then
    LINUX_HOME="$LINUX_HOME" "$CTL" start >> "$SERVICE_LOG" 2>&1
  else
    LINUX_HOME="$LINUX_HOME" /system/bin/sh "$CTL" start >> "$SERVICE_LOG" 2>&1
  fi
) &
# 不等待：start.sh 自己会 fork 出守护进程，这里只是发起
log "已在后台发起启动（不阻塞 boot）"
exit 0
