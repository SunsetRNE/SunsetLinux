#!/system/bin/sh
# =============================================================================
# sunsetlinux · module/lib/detect-mount.sh
#
# 探测「当前 root 实现」与「KernelSU 的挂载实现（metamodule）」，并判定本模块
# 是否需要挂载支持。
#
# 为什么值得单独做一个探测（用户明确要求）：
#   背景（实测）：**KernelSU 在某个版本删掉了自带的模块挂载实现，改为完全交给
#   第三方 metamodule**（具体是哪一个由设备主人安装，各设备不同）；Magisk 仍是
#   原生挂载。社区里大量模块是"自动挂载"型，它们的假设在装了 metamodule 的环境
#   里经常不成立 —— 刷写时就报告出来能省掉大量排查。
#
#   ★ 刻意**不点名**任何第三方模块/作者：只按"声明了 metamodule=1"这一客观条件
#     识别（见下），不写死任何具体 ID，也不在文案里推荐某个实现。
#
#   而 SunsetLinux 本身是**纯脚本模块**：module/ 下没有 system/、system_ext/、vendor/、
#   product/、odm/ 任何会被 overlay 的目录，所以**根本不依赖挂载实现**，
#   也就不受 KernelSU 这次变动影响。这是我们的优势，应当明确讲给用户听
#   （而不是让用户自己猜"这个模块要不要 metamodule"）。
#
# 设计约束：
#   - POSIX sh 兼容（会被 /system/bin/sh 的 mksh 载入），**不依赖 bash**
#   - 只读探测：不修改任何系统状态
#   - **通配匹配**，不写死任何具体实现：换设备/换实现都要对
#   - 既可被 source（提供函数），也可直接执行（打印报告）
#
# 用法：
#   . detect-mount.sh
#   detect_root_impl                     # 打印 magisk|kernelsu|apatch|unknown
#   metamodule_ids                       # 打印所有声明 metamodule=1 的模块 id（每行一个）
#   detect_mount_json                    # 打印一行 JSON（供 App / WebUI 解析）
#   detect_mount_report                  # 打印人类可读报告
#   module_needs_mount                   # 退出码 0=需要挂载 1=不需要
# =============================================================================

# ---------------------------------------------------------------------------
# 0) 上下文守卫（★ 必须最先做）
#
# 为什么需要：实测同一个 /data/adb 在不同上下文里可见性完全不同 ——
#     proot 内   : ls /data/adb → 只有 1 项（且没有 modules/）
#     原生 root  : ls /data/adb → 19 项（ksu / ksud / metamodule / modules / …）
#   而我们的 chroot 挂载树只 bind 了 /dev、/proc、/sys、sdcard，**没有 bind /data/adb**。
#   在这些上下文里跑探测会输出"root 实现未知 / no metamodule"，**看起来像环境坏了，
#   实际是探测根本不适用**。错误的断言比不报更糟，所以必须先判上下文。
#
# 判据（用户给定）：/data/adb 存在 **且** /data/adb/modules 存在 → 可信；
#   否则一律视为"不在 Android 侧 / 不完整"，**不下任何关于 metamodule 的结论**。
# ---------------------------------------------------------------------------
mount_ctx_trusted() {
    [ -d "$ADB_DIR" ] && [ -d "$PROBE_MODULES_DIR" ]
}

# 打印不可信时的说明（供报告与 doctor 复用）
mount_ctx_note() {
    printf '[不适用] 当前不在 Android 侧（%s 不可见或不完整）→ 挂载实现探测不适用\n' "$ADB_DIR"
    printf '         实测差异：proot/环境内看到的 %s 只有少量条目且常缺 modules/；\n' "$ADB_DIR"
    printf '         原生 root 下才有完整的 ksu/ksud/metamodule/modules 等条目。\n'
    printf '         如需此项，请在 **Android 侧的 root 终端**执行：su -c '"'"'linuxctl doctor'"'"'\n'
}

# ---------------------------------------------------------------------------
# 1) root 实现判定
#    依据：各管理器自己的私有目录（比"有没有 magisk 命令"可靠，后者可能是残留）
# ---------------------------------------------------------------------------
# /data/adb 前缀可覆盖：便于在开发机上用真实结构的副本做自测（本机无 root）。
ADB_DIR="${ADB_DIR:-/data/adb}"

detect_root_impl() {
    if [ -d "$ADB_DIR/magisk" ] || [ -x "$ADB_DIR/magisk/magisk" ] || \
       { [ -d "$ADB_DIR/modules" ] && [ -f "$ADB_DIR/magisk.db" ]; }; then
        printf 'magisk'; return 0
    fi
    if [ -d "$ADB_DIR/ksu" ] || [ -x "$ADB_DIR/ksud" ] || [ -f "$ADB_DIR/ksud" ]; then
        printf 'kernelsu'; return 0
    fi
    if [ -d "$ADB_DIR/ap" ] || [ -x "$ADB_DIR/apd" ] || [ -f "$ADB_DIR/apd" ]; then
        printf 'apatch'; return 0
    fi
    # 兜底：有 modules 目录但认不出管理器
    [ -d "$ADB_DIR/modules" ] && { printf 'unknown-with-modules'; return 0; }
    printf 'unknown'
}

root_impl_label() {
    case "$1" in
        magisk)  printf 'Magisk' ;;
        kernelsu) printf 'KernelSU' ;;
        apatch)  printf 'APatch' ;;
        unknown-with-modules) printf '未知（但存在 /data/adb/modules）' ;;
        *)       printf '未知' ;;
    esac
}

# ---------------------------------------------------------------------------
# 2) metamodule 探测
#    metamodule 的判定标准（KernelSU 的约定）：模块 module.prop 里有 `metamodule=1`
#    ★ 必须锚定行首 `^metamodule=1`：实测有的模块 description 里也含 "metamodule"
#      字样（自述用途），用 `grep metamodule` 会把它们误判成 metamodule。
# ---------------------------------------------------------------------------
PROBE_MODULES_DIR="${PROBE_MODULES_DIR:-$ADB_DIR/modules}"

# 打印所有 metamodule 的 id（每行一个）
metamodule_ids() {
    local d prop
    [ -d "$PROBE_MODULES_DIR" ] || return 0
    for d in "$PROBE_MODULES_DIR"/*; do
        [ -d "$d" ] || continue
        prop="$d/module.prop"
        [ -f "$prop" ] || continue
        if grep -q '^metamodule=1' "$prop" 2>/dev/null; then
            basename "$d"
        fi
    done
}

# metamodule_prop <id> <key> —— 读该模块 module.prop 的某个键
metamodule_prop() {
    local id="$1" key="$2" prop="$PROBE_MODULES_DIR/$id/module.prop"
    [ -f "$prop" ] || return 0
    sed -n "s/^$key=//p" "$prop" 2>/dev/null | head -n1
}

# metamodule_framework_present —— 打印 yes/no：KernelSU 的 metamodule 框架目录是否存在
metamodule_framework_present() {
    if [ -d "$ADB_DIR/metamodule" ] || [ -d "$ADB_DIR/magic_mount" ] || \
       [ -f "$ADB_DIR/metamodule/metamount.sh" ] || [ -d "$ADB_DIR/modules_update/metamodule" ]; then
        printf 'yes'
    else
        printf 'no'
    fi
}

# metamodule_names_csv —— 所有 metamodule 的 "id(version)" 用逗号连接；无则空
metamodule_names_csv() {
    local out="" id ver
    for id in $(metamodule_ids); do
        ver="$(metamodule_prop "$id" version)"
        [ -n "$out" ] && out="$out,"
        if [ -n "$ver" ]; then out="$out$id($ver)"; else out="$out$id"; fi
    done
    printf '%s' "$out"
}

# ---------------------------------------------------------------------------
# 3) 本模块是否需要挂载
#    判定：模块根下是否存在会被 overlay 到系统分区的目录。
#    我们**没有**这些目录 → 纯脚本模块 → 不需要挂载实现。
#    （自定义 MODULE_ROOT 便于测试；默认取本脚本所在模块根的上一级）
# ---------------------------------------------------------------------------
MODULE_ROOT="${MODULE_ROOT:-}"

module_needs_mount() {
    local root="$MODULE_ROOT"
    [ -n "$root" ] || root="$(cd -- "$(dirname -- "$0")/.." 2>/dev/null && pwd)" || root="."
    local d
    for d in system system_ext vendor product odm my_product my_heytap oplus; do
        [ -d "$root/$d" ] && return 0
    done
    # 有 sepolicy.rule 不算挂载需求；有 .replace 目录则算
    [ -d "$root/.replace" ] && return 0
    return 1
}

# ---------------------------------------------------------------------------
# 4) 结构化输出（一行 JSON）
# ---------------------------------------------------------------------------
_json_escape() {
    # POSIX sh 里没有 ${var//}，用 sed；输入都是我们自己的短字符串，安全
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\n\r'
}

detect_mount_json() {
    # ★ 上下文不可信：明确报"不适用"，**绝不断言** unknown / no metamodule
    if ! mount_ctx_trusted; then
        # 注意：这里刻意不用嵌套单引号（那是 POSIX sh 里最容易踩的坑，
        # 会把 printf 的格式串截断）。用双引号包住、内部只放普通文本。
        printf '{"schema":1,"applicable":false,"trusted":false,"reason":"not_android_side",'
        printf '"root_impl":null,"root_label":null,"metamodule_present":null,'
        printf '"metamodule_ids":null,"metamodule_names":null,"module_needs_mount":null,'
        printf '"conclusion":"%s"}\n' \
            "$(_json_escape "当前不在 Android 侧（$ADB_DIR 不可见或不完整），挂载实现探测不适用；请在 Android 侧的 root 终端执行 su -c linuxctl doctor")"
        return 0
    fi

    local impl mm_present mm_ids mm_csv needs
    impl="$(detect_root_impl)"
    mm_present="$(metamodule_framework_present)"
    mm_ids="$(metamodule_ids | tr '\n' ',' | sed 's/,$//')"
    mm_csv="$(metamodule_names_csv)"
    if module_needs_mount; then needs=true; else needs=false; fi

    # 结论（给 App/WebUI 直接显示，避免前端再拼逻辑）
    local concl=""
    if [ "$needs" = "false" ]; then
        concl="本模块不挂载任何系统路径，无需 metamodule 支持；KernelSU 的挂载实现变动不影响它"
    else
        if [ "$impl" = "kernelsu" ] && [ "$mm_present" = "no" ] && [ -z "$mm_ids" ]; then
            concl="本模块需要挂载系统路径，但当前 KernelSU 没有任何 metamodule：请先安装一个 metamodule 实现"
        else
            concl="本模块需要挂载系统路径，当前环境已满足（metamodule: ${mm_csv:-无}）"
        fi
    fi

    printf '{"schema":1,"applicable":true,"trusted":true,"root_impl":"%s","root_label":"%s",' \
        "$(_json_escape "$impl")" "$(_json_escape "$(root_impl_label "$impl")")"
    printf '"metamodule_present":"%s","metamodule_ids":"%s","metamodule_names":"%s",' \
        "$(_json_escape "$mm_present")" "$(_json_escape "$mm_ids")" "$(_json_escape "$mm_csv")"
    printf '"module_needs_mount":%s,"conclusion":"%s"}\n' "$needs" "$(_json_escape "$concl")"
}

# ---------------------------------------------------------------------------
# 5) 人类可读报告
# ---------------------------------------------------------------------------
detect_mount_report() {
    if ! mount_ctx_trusted; then
        mount_ctx_note
        return 0
    fi

    local impl mm_present mm_csv id ver
    impl="$(detect_root_impl)"
    mm_present="$(metamodule_framework_present)"
    mm_csv="$(metamodule_names_csv)"

    printf 'root 实现        : %s (%s)\n' "$(root_impl_label "$impl")" "$impl"
    printf 'metamodule 框架  : %s\n' "$mm_present"
    if [ -n "$mm_csv" ]; then
        printf 'metamodule 实现  : %s\n' "$mm_csv"
        for id in $(metamodule_ids); do
            ver="$(metamodule_prop "$id" version)"
            printf '                   - %s %s\n' "$id" "${ver:+$ver}"
        done
    else
        printf 'metamodule 实现  : （无）\n'
    fi

    if module_needs_mount; then
        printf '本模块是否需要挂载: 是\n'
        if [ "$impl" = "kernelsu" ] && [ -z "$mm_csv" ]; then
            printf '结论            : 需要挂载但当前没有 metamodule。\n'
            printf '                  请先装一个 metamodule 实现，否则本模块的\n'
            printf '                  系统路径覆盖不会生效。\n'
        else
            printf '结论            : 已满足（KernelSU 由 metamodule 提供挂载；Magisk 为原生挂载）。\n'
        fi
    else
        printf '本模块是否需要挂载: 否\n'
        printf '结论            : 本模块**不挂载任何系统路径**（module/ 下没有 system/、vendor/ 等目录），\n'
        printf '                  是纯脚本模块，**无需 metamodule 支持**。KernelSU 删掉自带挂载实现\n'
        printf '                  这件事对本模块没有影响。\n'
        if [ "$impl" = "kernelsu" ] && [ "$mm_present" = "no" ]; then
            printf '提醒            : 当前 KernelSU 环境中没有检测到 metamodule。若你以后要装**需要挂载**的\n'
            printf '                  模块（很多社区模块是自动挂载型），需要先装一个 metamodule；\n'
            printf '                  但这不影响本模块。\n'
        fi
    fi
}

# 直接执行时打印报告
case "${0##*/}" in
    detect-mount.sh)
        detect_mount_report
        ;;
esac
