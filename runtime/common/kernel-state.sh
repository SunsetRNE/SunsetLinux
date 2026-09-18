#!/system/bin/sh
# =============================================================================
# sunsetlinux · runtime/common/kernel-state.sh
#
# 内核 v2（sunsetd）状态的**唯一读取实现** —— 谁要问"环境在不在跑"，都读这里。
#
# 为什么必须是"一份"：v1 里同一件事有四处各自推导（start.sh 的 running_ns_pid、
# linuxctl 的 ns_pid_alive/env_ready、App 的 DshStatus、WebUI），2026-09-18 那晚的
# 第 2、3 条事故（端口没落盘、两棵挂载树）就是它们漂移出来的。现在：
#
#   · 内核在线（run/state.json + run/heartbeat 在 N 秒内）⇒ **相位以内核为准**；
#   · 内核不在或心跳过期 ⇒ 调用方退回原有的 v1 标记判据（**绝不拿过期状态冒充**）；
#   · 心跳写的是**毫秒**，而 mksh 的算术是 32 位（真机踩过：KB 乘字节溢出）⇒
#     取秒用**字符串截断** `${ts%???}`，不做乘除。
#
# 覆盖点（自测用）：SUNSETLINUX_KERNEL_STALE_SEC（心跳容忍秒数，默认 15）
# =============================================================================

KERNEL_HEARTBEAT_STALE_SEC="${SUNSETLINUX_KERNEL_STALE_SEC:-15}"

# ---------------------------------------------------------------------------
# 内核 v2（sunsetd）的权威相位 —— 「在不在跑」从此**只认它**（它不在时才退回 v1 判据）
#
# 为什么做成"覆盖"而不是"再来一份判定"：v1 里同一件事有四处各自推导
# （start.sh / linuxctl / App / WebUI），今晚第 2、3 条事故就是它们漂移的产物。
# 内核在线时 `state` 由内核给；内核不在（模块未起、免 root 未开前台服务、dex 缺失）时，
# 下面那套 v1 标记判据照旧工作 —— 这就是决策 D4 的"v1 契约兼容 + 文件降级"。
#
# 心跳过期 = 内核已死：**绝不拿过期状态冒充**（宁可退回 v1 判据）。
# ---------------------------------------------------------------------------
KERNEL_HEARTBEAT_STALE_SEC="${SUNSETLINUX_KERNEL_STALE_SEC:-15}"

kernel_heartbeat_fresh() {
    local hb="$RUN_DIR/heartbeat" now ts sec
    [ -f "$hb" ] || return 1
    now="$(date +%s)"
    ts="$(tr -dc '0-9' < "$hb" 2>/dev/null | head -c 13)"
    [ -n "$ts" ] || return 1
    # 心跳写的是**毫秒**。mksh 的算术是 32 位（真机踩过），所以用**字符串截断**取秒，
    # 不做乘法/除法 —— 13 位数一进 $(( )) 就溢出。
    sec="${ts%???}"
    case "$sec" in ''|*[!0-9]*) return 1 ;; esac
    [ "$(( now - sec ))" -le "$KERNEL_HEARTBEAT_STALE_SEC" ]
}

kernel_field() {   # kernel_field <键>：从内核的平铺 state.json 里取一个字符串值
    local f="$RUN_DIR/state.json" v=""
    [ -f "$f" ] || return 1
    v="$(sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" "$f" 2>/dev/null | head -n1)"
    [ -n "$v" ] || return 1
    printf '%s' "$v"
}

kernel_phase_v1() {   # 内核相位 → v1 的 state 取值（stopped|starting|running|stopping|error）
    local ph=""
    kernel_heartbeat_fresh || return 1
    ph="$(kernel_field phase)" || return 1
    case "$ph" in
        running|degraded)             printf 'running' ;;
        preparing|mounting|starting)  printf 'starting' ;;
        stopping)                     printf 'stopping' ;;
        failed)                       printf 'error' ;;
        idle)                         printf 'stopped' ;;
        *) return 1 ;;
    esac
}

# 把内核事实填进 status 的附加键（内核不在时 online=false，其余为空 —— 不编造）
kernel_fill_status() {
    if kernel_heartbeat_fresh; then
        DSH_ST_KERNEL_ONLINE=true
        DSH_ST_KERNEL_PHASE="$(kernel_field phase 2>/dev/null || true)"
        DSH_ST_KERNEL_GENERATION="$(sed -n 's/.*"generation":\([0-9][0-9]*\).*/\1/p' "$RUN_DIR/state.json" 2>/dev/null | head -n1)"
        DSH_ST_KERNEL_OWNER="$(kernel_field owner 2>/dev/null || true)"
        DSH_ST_KERNEL_JOB="$(kernel_field jobId 2>/dev/null || true)"
    else
        DSH_ST_KERNEL_ONLINE=false
        DSH_ST_KERNEL_PHASE=""
        DSH_ST_KERNEL_GENERATION=""
        DSH_ST_KERNEL_OWNER=""
        DSH_ST_KERNEL_JOB=""
    fi
}

