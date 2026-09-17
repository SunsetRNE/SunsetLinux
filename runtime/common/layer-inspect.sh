#!/system/bin/sh
# =============================================================================
# sunsetlinux · runtime/common/layer-inspect.sh
#
# **共享**的层内容检查：不挂载镜像，直接问"这个层里有没有这个路径"。
#
# 为什么抽成公共库（而不是各脚本自带一份）：
#   同一个判断现在有两个消费方，而且它们必须给出一致的答案 ——
#     · `doctor.sh` §7：报"当前生效的 dsh 层里有没有 /root/.dsh/profiles/**"；
#     · `linuxctl.sh dsh builtin`：决定"要不要把生效层切到内置那份"（层坏没坏就看这个）。
#   两份实现迟早漂移（本项目的老账：同一逻辑抄两遍必出分歧），所以放这里一份。
#
# 调用约定（返回值三态，**不许把"读不到"当成"没有"**）：
#   0 = 层里**有**该路径
#   1 = 层里**没有**该路径
#   2 = 判不了（文件不存在 / 本机没有 dump.erofs / unsquashfs / 格式不认识）
#
# 前置：调用方需已定义 `layer_format()` 与 `have()`（doctor.sh 与 linuxctl.sh 都有）。
# 依赖：toybox/erofs-utils 的 `dump.erofs`，或 `unsquashfs`（squashfs 回退）。
# =============================================================================

# 幂等：被多次 source 也只定义一次
[ -n "${SUNSETLINUX_LAYER_INSPECT_SH:-}" ] && return 0 2>/dev/null || true
SUNSETLINUX_LAYER_INSPECT_SH=1

# layer_has_path <层文件> <层内绝对路径>
#
# ★ 必须**对着目标路径问**（`dump.erofs --path=<p>`），不要拿 `--ls --path=/` 的输出 grep ——
#   那个列表**不递归**，深层路径永远匹配不上，于是"层是好的"也会被报成"没有"
#   （真机 2026-09-16 的假 fail 就是这么来的）。
layer_has_path() {
    local f="$1" p="$2" fmt="" out=""
    [ -f "$f" ] || return 2
    fmt="$(layer_format "$f" 2>/dev/null || echo unknown)"
    case "$fmt" in
        erofs)
            have dump.erofs || return 2
            out="$(dump.erofs --path="$p" "$f" 2>&1 || true)"
            case "$out" in
                *"read inode failed"*|*"No such file"*) return 1 ;;
                "") return 2 ;;
                *) return 0 ;;
            esac ;;
        squashfs)
            have unsquashfs || return 2
            out="$(unsquashfs -l "$f" "$p" 2>/dev/null || true)"
            case "$out" in *"$p"*) return 0 ;; *) return 1 ;; esac ;;
        *) return 2 ;;
    esac
}
