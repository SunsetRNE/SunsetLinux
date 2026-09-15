#!/usr/bin/env bash
# =============================================================================
# dshroid · runtime/root/stop.sh
#
# 按 **相反顺序** 整洁卸载 architecture.md §4 的挂载树，并停掉 supervisor。
#
# 关键设计：
#   1) 所有挂载动作都是在 `unshare -m` 出来的**私有命名空间**里做的，所以
#      "从宿主卸载"必须用 `nsenter --mount=/proc/<nspid>/ns/mnt` 进那个 ns 再卸，
#      否则宿主看到的是别的挂载表，umount 会报"未挂载"。
#   2) 必须处理"部分挂载残留"：run/mounts 是 start.sh **每挂一个就登记一个**的
#      持久清单（宿主可见）。本脚本优先按它反序卸载，再用固定顺序兜底。
#   3) 卸载失败不中断整体流程：一路尽力卸完并汇总报告（幂等要求"重复 stop 不报错"）。
# =============================================================================
set -uo pipefail

SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
LINUX_HOME="${LINUX_HOME:-/data/linux}"
LH="$LINUX_HOME"

RUN_DIR="$LH/run"
LAYERS_DIR="$LH/layers"
LAYERS_MNT="$LH/layers-mnt"
UPPER_DIR="$LH/upper"
ROOTFS_DIR="$LH/rootfs"

SUPERVISOR_PID_FILE="$RUN_DIR/supervisor.pid"
DSH_PID_FILE="$RUN_DIR/dsh.pid"
READY_FILE="$RUN_DIR/ready"
MOUNTS_FILE="$RUN_DIR/mounts"

UMOUNT=/system/bin/umount
MOUNT=/system/bin/mount
NSENTER=/system/bin/nsenter

# 兜底卸载顺序（**§4 的严格逆序**）：先叶子后根
#   mnt/sdcard → dev/shm → dev/pts → dev → sys → proc → run → tmp
#   → overlay(rootfs) → layers-mnt/* → upper
FALLBACK_ORDER=(
    mnt/sdcard
    dev/shm
    dev/pts
    dev
    sys
    proc
    run
    tmp
)
# 层挂载（相对 $LH，不在 rootfs 内）
LAYER_MNT_REL=( layers-mnt/dsh layers-mnt/runtime layers-mnt/base )

log() {
    local line
    line="[$(date '+%Y-%m-%d %H:%M:%S')] [stop.sh] $*"
    printf '%s\n' "$line" >&2
    [ -d "$RUN_DIR" ] && printf '%s\n' "$line" >> "$RUN_DIR/linux.log" 2>/dev/null || true
}
warn() { log "WARN: $*"; }

have() { command -v "$1" >/dev/null 2>&1; }
RUNNING=0

ns_pid_alive() {
    local pid=""
    [ -f "$SUPERVISOR_PID_FILE" ] || return 1
    pid="$(head -n1 "$SUPERVISOR_PID_FILE" 2>/dev/null | tr -dc '0-9' || true)"
    [ -n "$pid" ] || return 1
    [ -d "/proc/$pid" ] || return 1
    [ -r "/proc/$pid/ns/mnt" ] || return 1
    printf '%s' "$pid"
}

# ---------------------------------------------------------------------------
# do_umount <abs_path> —— 尽力卸载（-l 懒卸载 → -f 强制），返回 0/1
# ---------------------------------------------------------------------------
do_umount() {
    local target="$1"
    [ -e "$target" ] || return 0
    mountpoint -q "$target" 2>/dev/null || return 0

    if [ -n "${NSPID:-}" ] && [ -x "$NSENTER" ]; then
        "$NSENTER" --mount="/proc/$NSPID/ns/mnt" "$UMOUNT" -l "$target" 2>/dev/null && return 0
        "$NSENTER" --mount="/proc/$NSPID/ns/mnt" "$UMOUNT" -f "$target" 2>/dev/null && return 0
    fi
    # 退化：直接卸（对层挂载与 upper 有效，因为它们是在宿主 ns 里挂的）
    "$UMOUNT" -l "$target" 2>/dev/null && return 0
    "$UMOUNT" -f "$target" 2>/dev/null && return 0
    return 1
}

# ---------------------------------------------------------------------------
# 1) 先让 node/supervisor 优雅退出（**先停进程再卸挂载**：进程还活着写文件时
#    卸挂载会留下半写状态，也容易 umount 失败 EBUSY）
# ---------------------------------------------------------------------------
stop_supervisor() {
    local pid="$1"
    log "向 supervisor(pid=$pid) 发送 TERM"
    kill -TERM "$pid" 2>/dev/null || true
    local i=0
    while [ "$i" -lt 150 ]; do
        kill -0 "$pid" 2>/dev/null || { log "supervisor 已退出"; return 0; }
        sleep 0.1
        i=$(( i + 1 ))
    done
    warn "supervisor 15s 内未退出，发送 KILL"
    kill -KILL "$pid" 2>/dev/null || true
    sleep 0.5
    return 0
}

kill_stray_dsh() {
    # supervisor 被杀但 node 还活着（理论上不应该）→ 按 dsh.pid 精确补刀
    local pid=""
    [ -f "$DSH_PID_FILE" ] || return 0
    pid="$(head -n1 "$DSH_PID_FILE" 2>/dev/null | tr -dc '0-9' || true)"
    [ -n "$pid" ] || return 0
    if [ -d "/proc/$pid" ]; then
        warn "发现残留的 dsh 进程（pid=$pid），发送 TERM"
        kill -TERM "$pid" 2>/dev/null || true
        local i=0
        while [ "$i" -lt 50 ]; do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; i=$(( i + 1 )); done
        kill -KILL "$pid" 2>/dev/null || true
    fi
    return 0
}

# ---------------------------------------------------------------------------
# 2) 反序卸载 rootfs 内的挂载点
# ---------------------------------------------------------------------------
unmount_rootfs_tree() {
    local -a order=()
    local rel

    # 优先用 start.sh 写下的**实际**登记表（精确、反映真实顺序）
    if [ -s "$MOUNTS_FILE" ]; then
        log "按 $MOUNTS_FILE 的登记反序卸载"
        local -a recorded=()
        while IFS= read -r rel; do
            [ -n "$rel" ] && [ "$rel" != "overlay" ] && recorded+=( "$rel" )
        done < "$MOUNTS_FILE"
        local i
        for (( i=${#recorded[@]}-1; i>=0; i-- )); do
            order+=( "${recorded[$i]}" )
        done
    fi
    # 兜底顺序补齐（登记表可能缺失/不全）
    for rel in "${FALLBACK_ORDER[@]}"; do
        order+=( "$rel" )
    done

    local seen=" "
    for rel in "${order[@]}"; do
        # 去重
        case "$seen" in *" $rel "*) continue ;; esac
        seen="$seen$rel "
        do_umount "$ROOTFS_DIR/$rel" && log "已卸载 $rel" || true
    done
    return 0
}

# ---------------------------------------------------------------------------
# 3) 卸载 overlay 本体（必须是 rootfs 内挂载全部卸完之后）
# ---------------------------------------------------------------------------
unmount_overlay() {
    do_umount "$ROOTFS_DIR" && log "已卸载 overlay（$ROOTFS_DIR）" || true
    return 0
}

# ---------------------------------------------------------------------------
# 4) 卸载三层只读镜像（loop）
# ---------------------------------------------------------------------------
unmount_layers() {
    local rel
    for rel in "${LAYER_MNT_REL[@]}"; do
        do_umount "$LH/$rel" && log "已卸载 $rel" || true
    done
    # 极端情况：squashfs/erofs 层曾经被直接挂在 layers 目录下
    local f
    for f in "$LAYERS_DIR"/*.erofs "$LAYERS_DIR"/*.squashfs; do
        [ -f "$f" ] || continue
        mountpoint -q "$f" 2>/dev/null && { do_umount "$f" || true; }
    done
    return 0
}

# ---------------------------------------------------------------------------
# 5) 卸载可写层（最后）
# ---------------------------------------------------------------------------
unmount_upper() {
    do_umount "$UPPER_DIR" && log "已卸载可写层（$UPPER_DIR）" || true
    return 0
}

# ---------------------------------------------------------------------------
# 6) 收尾：清理 run 下的运行期文件（保留 linux.log）
# ---------------------------------------------------------------------------
cleanup_run_files() {
    rm -f "$READY_FILE" "$DSH_PID_FILE" "$SUPERVISOR_PID_FILE" \
          "$RUN_DIR/dsh.url" "$RUN_DIR/dsh.port" "$RUN_DIR/mounted.json" \
          "$RUN_DIR/started" 2>/dev/null || true
    : > "$MOUNTS_FILE" 2>/dev/null || true
    # 若 rootfs 里还留着上次的挂载点空目录，保留（overlay upper 里本来就该有）
    return 0
}

# ---------------------------------------------------------------------------
# 残留检查（幂等验证 + 可读报告）
# ---------------------------------------------------------------------------
verify_clean() {
    local left=""
    local rel
    for rel in "${FALLBACK_ORDER[@]}"; do
        mountpoint -q "$ROOTFS_DIR/$rel" 2>/dev/null && left="$left $rel"
    done
    mountpoint -q "$ROOTFS_DIR" 2>/dev/null && left="$left overlay"
    for rel in "${LAYER_MNT_REL[@]}"; do
        mountpoint -q "$LH/$rel" 2>/dev/null && left="$left $rel"
    done
    mountpoint -q "$UPPER_DIR" 2>/dev/null && left="$left upper"
    if [ -n "$left" ]; then
        warn "仍有挂载残留：$left"
        warn "可尝试手动清理：nsenter --mount=/proc/<pid>/ns/mnt umount -l <path>"
        check_loop_leak
        return 1
    fi
    log "确认已全部卸载，无残留"
    check_loop_leak
    return 0
}

# ---------------------------------------------------------------------------
# loop 设备泄漏检查
#   `mount -o loop` 由内核自动分配 loop，正常 umount 会释放；
#   但如果镜像被强杀/懒卸载，autoclear 可能没跑到，留下 ls -l 指向 (deleted) 的 loop。
#   Android 有 /system/bin/losetup（toybox），有就查一下，不删（删块设备超出本脚本职责）。
# ---------------------------------------------------------------------------
check_loop_leak() {
    local ls=""
    for c in /system/bin/losetup /sbin/losetup /usr/sbin/losetup; do
        [ -x "$c" ] && { ls="$c"; break; }
    done
    have losetup && ls="${ls:-losetup}"
    [ -n "$ls" ] || { return 0; }
    local out
    out="$("$ls" -a 2>/dev/null | grep -E "$LAYERS_DIR|$UPPER_IMG|$LH/" || true)"
    if [ -n "$out" ]; then
        warn "检测到与本项目相关的 loop 设备仍被占用："
        printf '%s\n' "$out" | while IFS= read -r l; do warn "    $l"; done
        warn "  通常再执行一次 stop 即可释放；若持续存在，可在 root 终端手动 detach："
        warn "    losetup -d /dev/block/loopN"
        return 1
    fi
    log "无 loop 设备泄漏"
    return 0
}

# ===========================================================================
main() {
    mkdir -p "$RUN_DIR" 2>/dev/null || true

    NSPID=""
    if NSPID="$(ns_pid_alive)"; then
        RUNNING=1
        log "发现运行中的环境（ns_pid=$NSPID），开始停止"
        stop_supervisor "$NSPID"
    else
        log "没有运行中的环境（幂等）；仍会检查并清理任何挂载残留"
    fi
    kill_stray_dsh

    unmount_rootfs_tree
    unmount_overlay
    unmount_layers
    unmount_upper

    # 若守护进程仍活着（卸载后仍持有 ns），再补一刀
    if NSPID="$(ns_pid_alive)"; then
        warn "守护进程 $NSPID 仍存活，补发 TERM"
        kill -TERM "$NSPID" 2>/dev/null || true
    fi

    if verify_clean; then
        cleanup_run_files
        log "停止完成"
        exit 0
    fi
    # 有残留：清掉进程相关文件但保留 mounts 记录，方便下一次 stop 继续清
    rm -f "$READY_FILE" "$DSH_PID_FILE" "$SUPERVISOR_PID_FILE" \
          "$RUN_DIR/dsh.url" "$RUN_DIR/dsh.port" 2>/dev/null || true
    log "停止完成（有挂载残留，见上面的 WARN）"
    exit 0
}

main "$@"
