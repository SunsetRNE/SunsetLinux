#!/system/bin/sh
# =============================================================================
# sunsetlinux · runtime/common/host-residue.sh
#
# 清理**泄漏到别的 ns** 的本项目挂载。
#
# ## 为什么会泄漏（2026-09-18 真机实测，用户："已经有挂载了呀，为什么会重建挂载呢？"）
#
# `su`（KernelSU）会给 root shell 建一层自己的 mount ns：
#     /proc/self/ns/mnt = mnt:[4026536053]   ≠   /proc/1/ns/mnt = mnt:[4026532884]
# 而这层 ns 里 `/` 与 `/data` 是 **shared**（`/proc/self/mountinfo` 里带 peer group）。
# 我们的 `unshare -m` 从它继承传播属性 ⇒ 环境里建的挂载会一路传播到**宿主 init 的 ns**。
# 实测证据：`grep -c sunsetlinux /proc/1/mountinfo` = **33 条**。
#
# 本该由 `make-rprivate` 挡住，但在这台机上它**从来没生效过**：
#   · toybox 的 mount 没有 `--make-rprivate`；
#   · 能用的 util-linux 在 base 层里（`detect_util_mount`），可那个调用点在挂载树里
#     （`start.sh:1072`），而 make-rprivate 的尝试在它**之前**（`start.sh:1464`）
#     ⇒ `UTIL_MNT_BIN` 那时必然是空的，兜底那一支一次都没跑过。
#
# ## 泄漏的两个后果（都是用户看得见的）
#
# 1. `stop` 之后宿主里仍留着我们的挂载 ⇒ 看上去像"已经有挂载了"（但那棵树属于**已死的**
#    守护进程的私有 ns，不可复用 ⇒ 下次 start 必须重建，重建是**对**的）；
# 2. `cleanup_stale_loops` 见到 loop 仍被挂载（挂在**别的 ns** 里）就跳过 detach
#    ⇒ loop 残留，日志一直 WARN。
#
# ## 这个文件干的活
#
# 只做一件事：把**本项目自己的**挂载点懒卸载（`umount -l`）——
#   · 当前 ns 里的那些：直接 `umount -l`；
#   · init ns 里的那些：`nsenter --mount=/proc/1/ns/mnt -- umount -l`。
# 调用方**必须**保证"没有活着的守护进程"（否则会拆掉正在用的环境）；两个调用点都满足：
#   · `stop.sh` —— 已经杀完守护进程、正常卸载失败之后；
#   · `start.sh`(外层) —— 在拿到启动锁、且确认没有活着的环境之后。
#
# ## 覆盖点（自测用；与 e2fsck / erofs_extract 同一套约定）
#
#   SUNSETLINUX_HOST_RESIDUE=0   整体关闭（自测里避免真去动宿主）
#   SUNSETLINUX_MOUNTINFO=path   当前 ns 的 mountinfo（默认 /proc/self/mountinfo）
#   SUNSETLINUX_HOST_MOUNTINFO=p init ns 的 mountinfo（默认 /proc/1/mountinfo）
#   SUNSETLINUX_HOST_NS=path     init ns 路径（默认 /proc/1/ns/mnt）
#   SUNSETLINUX_NSENTER=cmd / SUNSETLINUX_HOST_UMOUNT=cmd   用桩替换
# =============================================================================

# 本项目可能占用的宿主路径，**子路径在前**（先卸子、再卸父，否则父会被子挡住）
host_residue_paths() {
    local lh="${LINUX_HOME:-/data/sunsetlinux}"
    printf '%s\n' \
        "$lh/rootfs/dev/shm" \
        "$lh/rootfs/dev/pts" \
        "$lh/rootfs/dev" \
        "$lh/rootfs/proc" \
        "$lh/rootfs/sys" \
        "$lh/rootfs/run" \
        "$lh/rootfs/mnt/sdcard" \
        "$lh/rootfs" \
        "$lh/layers-mnt/dsh" \
        "$lh/layers-mnt/runtime" \
        "$lh/layers-mnt/base" \
        "$lh/upper" \
        "$lh/upper.img"
}

# host_residue_in <mountinfo> <path> → 0 = 该路径在这份 mountinfo 里确实是挂载点
host_residue_in() {
    [ -r "$1" ] || return 1
    awk -v want="$2" '$5 == want { f = 1; exit } END { exit(f ? 0 : 1) }' "$1" 2>/dev/null
}

# host_residue_clean → 尽力清掉；所有动作都写进调用方的日志
host_residue_clean() {
    [ "${SUNSETLINUX_HOST_RESIDUE:-}" = "0" ] && return 0
    local mi_self="${SUNSETLINUX_MOUNTINFO:-/proc/self/mountinfo}"
    local mi_init="${SUNSETLINUX_HOST_MOUNTINFO:-/proc/1/mountinfo}"
    local host_ns="${SUNSETLINUX_HOST_NS:-/proc/1/ns/mnt}"
    local um="${SUNSETLINUX_HOST_UMOUNT:-/system/bin/umount}"
    local nsenter="${SUNSETLINUX_NSENTER:-/system/bin/nsenter}"
    local p

    # ① 当前 ns：直接卸（`umount -l` 是懒卸载：先摘挂载点，不再被占用挡住）
    if [ -r "$mi_self" ]; then
        host_residue_paths | while IFS= read -r p; do
            [ -n "$p" ] || continue
            host_residue_in "$mi_self" "$p" || continue
            if "$um" -l "$p" 2>/dev/null; then
                log "已卸下当前 ns 里的残留挂载：$p"
            else
                log "WARN: 当前 ns 的残留挂载卸不下来（非致命）：$p"
            fi
        done
    fi

    # ② init ns：泄漏真正落地的地方（不进去就永远清不掉，loop 也就永远 detach 不了）
    if [ -r "$mi_init" ] && [ -e "$host_ns" ]; then
        host_residue_paths | while IFS= read -r p; do
            [ -n "$p" ] || continue
            host_residue_in "$mi_init" "$p" || continue
            if "$nsenter" --mount="$host_ns" -- "$um" -l "$p" 2>/dev/null; then
                log "已从宿主 init ns 卸下残留挂载：$p"
            else
                log "WARN: 宿主 init ns 的残留挂载卸不下来：$p（可在 root 终端手动 nsenter --mount=$host_ns -- umount -l $p）"
            fi
        done
    fi
    return 0
}
