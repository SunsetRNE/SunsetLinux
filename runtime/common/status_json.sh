#!/usr/bin/env bash
# =============================================================================
# dshroid · runtime/common/status_json.sh
#
# 生成 architecture.md §3.1 里**冻结的** status JSON。root 与 proot 两套实现共用。
#
# 硬要求：
#   - **所有键都在**，取不到的值写 null，绝不省略键（App 依赖键的稳定性）。
#   - schema=1，mode ∈ root|proot，state ∈ stopped|starting|running|stopping|error。
#   - dsh.url 是**带令牌**的登录 URL（§3.3），dsh.base_url 是不带令牌的裸地址。
#   - healthy：向 base_url 发 GET /，**收到任何 HTTP 响应即健康**（401 正常）。
#
# JSON 生成策略：
#   1) 检测到 jq 可用 → 走 jq（先构造 TSV 中间格式，再用 jq -n 一次成型，杜绝手写转义错误）。
#   2) 没有 jq → **纯 bash 手写**，所有字符串都过 dsh_json_escape（转义 \ " 与 <0x20 控制字符）。
#
# 使用方法（调用方先设好变量，再调 dsh_status_json）：
#
#   . /path/to/status_json.sh /path/to/http_health.sh
#   dsh_status_var mode root
#   dsh_status_var state running
#   dsh_status_var pid 12345
#   ...
#   dsh_status_json
#
# 变量说明（全部可选，缺省即 null）：
#   DSH_ST_MODE         root|proot
#   DSH_ST_STATE        stopped|starting|running|stopping|error
#   DSH_ST_PID          整数或空
#   DSH_ST_STARTED_AT   进程启动的 Unix 秒（用于算 uptime_sec）；也可用 DSH_ST_UPTIME 直接给
#   DSH_ST_BASE_URL     http://127.0.0.1:3080
#   DSH_ST_URL          带 token 的 URL
#   DSH_ST_PORT         整数或空（缺省时从 base_url 里解析）
#   DSH_ST_VERSION      DSH 版本字符串
#   DSH_ST_LAYER_BASE_VERSION / DSH_ST_LAYER_BASE_SIZE / DSH_ST_LAYER_BASE_MOUNTED
#   DSH_ST_LAYER_RUNTIME_* / DSH_ST_LAYER_DSH_*
#   DSH_ST_UPPER_USED / DSH_ST_UPPER_TOTAL
#   DSH_ST_LAST_ERROR   可读错误原因
#
# 另提供一组探测辅助函数（linuxctl.sh 也直接用）：
#   dsh_layers_json ...            （见下）
#   dsh_layer_version <name>      从 etc/state.json 读版本
#   dsh_size_of <path>            文件大小（字节），失败空
#   dsh_upper_usage <upperdir>    "used total"
#   dsh_proc_start_time <pid>     进程启动 Unix 秒
#   dsh_read_port_file <file>     读 1..65535 的端口
# =============================================================================

[ -n "${DSHROID_STATUS_JSON_SH:-}" ] && return 0 2>/dev/null || true
DSHROID_STATUS_JSON_SH=1

# ---------------------------------------------------------------------------
# JSON 字符串转义（纯 bash）。只处理 JSON 必须转义的字符：
#   \  -> \\      "  -> \"      控制字符(<0x20) -> \u00XX     DEL(0x7f) 保留
# 用 LC_ALL=C 的 printf 取字节值做判断，避免多字节字符被逐字节拆坏 —— 我们只对
# 字节 < 0x20 做替换，UTF-8 的后续字节都 >= 0x80，不会被误伤。
# ---------------------------------------------------------------------------
dsh_json_escape() {
    local s="$1" out="" i ch
    # 先转义反斜杠和引号（顺序不能反：先反斜杠）
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    local n=${#s}
    for (( i = 0; i < n; i++ )); do
        ch="${s:i:1}"
        # 用 printf 把该字符按 C locale 打成字节值
        case "$ch" in
            $'\n') out+='\n' ;;
            $'\r') out+='\r' ;;
            $'\t') out+='\t' ;;
            $'\b') out+='\b' ;;
            $'\f') out+='\f' ;;
            *)
                # 其余 <0x20 的 ASCII 控制符（bash $'..' 里含 0x01..0x1f 的少数）
                local b
                b="$(LC_ALL=C printf '%d' "'$ch" 2>/dev/null || echo 32)"
                if [ "$b" -ge 0 ] 2>/dev/null && [ "$b" -lt 32 ]; then
                    printf -v ch '\\u%04x' "$b"
                fi
                out+="$ch"
                ;;
        esac
    done
    printf '%s' "$out"
}

# ---------------------------------------------------------------------------
# dsh_jq_ok —— jq 存在且能正常跑（**作为条件用：返回退出码**）
#   ★ 这里踩过一个很隐蔽的坑：早期实现是 `return` 了缓存值本身，
#     而 `DSH_ST_JQ_OK=0` 时 `return 0` 仍然表示"成功" → 所有 `if dsh_jq_ok`
#     都判成真，于是"jq 不存在"的环境里也会去走 jq 分支，被 `|| true` 吞掉却
#     返回空值。结果就是 state.json 明明写了版本，status 却一直读成 null。
#   现在统一为：**jq 可用 → exit 0；不可用 → exit 1**；缓存命中时也返回对应退出码。
# ---------------------------------------------------------------------------
dsh_jq_ok() {
    if [ -n "${DSH_ST_JQ_OK:-}" ]; then
        [ "$DSH_ST_JQ_OK" = "1" ]
        return $?
    fi
    if command -v jq >/dev/null 2>&1 && printf '0' | jq -e . >/dev/null 2>&1; then
        DSH_ST_JQ_OK=1
        return 0
    fi
    DSH_ST_JQ_OK=0
    return 1
}

# ---------------------------------------------------------------------------
# dsh_jnum <value> —— 输出 JSON 数字或 null（只接受十进制整数/小数，其它一律 null）
# ---------------------------------------------------------------------------
dsh_jnum() {
    local v="$1"
    if [ -z "$v" ] || [ "$v" = "null" ]; then
        printf 'null'
    elif printf '%s' "$v" | grep -Eq '^-?[0-9]+(\.[0-9]+)?$'; then
        printf '%s' "$v"
    else
        printf 'null'
    fi
}

# ---------------------------------------------------------------------------
# dsh_jstr <value> —— 输出 JSON 字符串或 null（空串 → null，符合"取不到用 null"）
# ---------------------------------------------------------------------------
dsh_jstr() {
    local v="$1"
    if [ -z "$v" ]; then
        printf 'null'
    else
        printf '"%s"' "$(dsh_json_escape "$v")"
    fi
}

# ---------------------------------------------------------------------------
# dsh_jbool <value> —— true/false；不合法 → false（mounted/healthy 不允许 null，
# 因为"是否挂载"一定有确定答案）
# ---------------------------------------------------------------------------
dsh_jbool() {
    case "${1:-}" in
        true|1|yes) printf 'true' ;;
        *)          printf 'false' ;;
    esac
}

# ---------------------------------------------------------------------------
# 探测辅助
# ---------------------------------------------------------------------------

# dsh_size_of <path> -> 字节数（stat -c%s，GNU 与 toybox 都支持）；失败输出空
dsh_size_of() {
    local p="$1" s=""
    [ -e "$p" ] || { printf ''; return 0; }
    s="$(stat -c '%s' "$p" 2>/dev/null || true)"
    printf '%s' "$s"
}

# dsh_read_port_file <file> -> 端口号（1..65535），否则空
dsh_read_port_file() {
    local f="$1" p=""
    [ -f "$f" ] || { printf ''; return 0; }
    p="$(head -n1 "$f" 2>/dev/null | tr -dc '0-9' || true)"
    if [ -n "$p" ] && [ "$p" -ge 1 ] 2>/dev/null && [ "$p" -le 65535 ] 2>/dev/null; then
        printf '%s' "$p"
    else
        printf ''
    fi
}

# dsh_port_from_url <url> -> 端口；无端口时按 scheme 给默认值
# 注意 dsh.base_url 形如 http://127.0.0.1:3080（无路径），这里用参数展开而不是
# 正则回溯，避免 bash 正则在各平台差异。
dsh_port_from_url() {
    local url="$1" rest hostport
    [ -z "$url" ] && { printf ''; return 0; }
    rest="${url#*://}"          # 127.0.0.1:3080[/...]
    hostport="${rest%%/*}"      # 127.0.0.1:3080
    case "$hostport" in
        *:*) printf '%s' "${hostport##*:}" ;;
        *)   case "$url" in https://*) printf '443' ;; *) printf '80' ;; esac ;;
    esac
}

# dsh_proc_start_time <pid> -> 进程启动的 Unix 秒；取不到输出空
#   算法：/proc/<pid>/stat 第 22 字段是 starttime（自 boot 起的时钟滴答数），
#   除以 USER_HZ（通常 100）得到"自 boot 起的秒数"，再加上 boot 时间。
#   boot 时间优先用 /proc/stat 的 btime；没有则用 /proc/uptime 反推。
dsh_proc_start_time() {
    local pid="$1" stat_line starttime hz boot now
    [ -r "/proc/$pid/stat" ] || { printf ''; return 0; }
    stat_line="$(cat "/proc/$pid/stat" 2>/dev/null || true)"
    [ -z "$stat_line" ] && { printf ''; return 0; }
    # 进程名可能含空格与括号，取最后一个 ')' 之后的字段，此时 $2 就是 state，
    # starttime 是剩下的第 20 个字段（原第 22 字段）。
    stat_line="${stat_line##*)}"
    # shellcheck disable=SC2086
    set -- $stat_line
    starttime="${20:-}"
    [ -z "$starttime" ] && { printf ''; return 0; }

    hz="$(getconf CLK_TCK 2>/dev/null || echo 100)"
    case "$hz" in ''|*[!0-9]*) hz=100 ;; esac

    boot="$(awk '/^btime /{print $2; exit}' /proc/stat 2>/dev/null || true)"
    if [ -z "$boot" ]; then
        # 回退：now - uptime
        now="$(date +%s)"
        boot="$(awk -v n="$now" '{printf "%d", n - $1}' /proc/uptime 2>/dev/null || echo '')"
    fi
    [ -z "$boot" ] && { printf ''; return 0; }

    awk -v b="$boot" -v s="$starttime" -v h="$hz" 'BEGIN{printf "%d", b + int(s/h)}'
}

# dsh_upper_usage <upper_dir> -> "used total"（字节）；失败输出 "used" 一项或空
#   df -B1 的 -B 在 toybox/busybox 与 coreutils 都有；用 POSIX -P 解析输出更稳。
dsh_upper_usage() {
    local dir="$1" out used total
    [ -d "$dir" ] || { printf ''; return 0; }
    out="$(df -P "$dir" 2>/dev/null | awk 'NR==2 {print $3" "$2}' || true)"
    if [ -n "$out" ]; then
        used="${out%% *}"
        total="${out##* }"
        # df -P 输出的是 1K 块 → 换算成字节
        case "$used$total" in
            *[!0-9]*) printf '' ; return 0 ;;
        esac
        awk -v u="$used" -v t="$total" 'BEGIN{printf "%d %d", u*1024, t*1024}'
        return 0
    fi
    printf ''
}

# dsh_layer_version <state_json> <layer> -> 版本字符串（state.json 的 layers.<l>.version）
#   device-provision.sh 写出的 state.json 是扁平可控格式：
#     {"schema":1,"layers":{"base":{"version":"...","sha256":"...","size":0}}, ...}
#   没有 jq 时用一个稳的字段抓取：找到 "name" 之后的第一个 "version"。
dsh_layer_version() {
    local state="$1" layer="$2"
    [ -f "$state" ] || { printf ''; return 0; }
    if dsh_jq_ok; then
        jq -r --arg l "$layer" '.layers[$l].version // empty' "$state" 2>/dev/null || true
        return 0
    fi
    # 纯文本回退：把 JSON 压平，再在 "<layer>" 之后取第一个 "version":"..."
    tr -d ' \n\t' < "$state" \
        | sed -n "s/.*\"$layer\":{[^}]*\"version\":\"\([^\"]*\)\".*/\1/p" \
        | head -n1
}

# dsh_layer_sha256 <state_json> <layer> -> sha256 或空
dsh_layer_sha256() {
    local state="$1" layer="$2"
    [ -f "$state" ] || { printf ''; return 0; }
    if dsh_jq_ok; then
        jq -r --arg l "$layer" '.layers[$l].sha256 // empty' "$state" 2>/dev/null || true
        return 0
    fi
    tr -d ' \n\t' < "$state" \
        | sed -n "s/.*\"$layer\":{[^}]*\"sha256\":\"\([^\"]*\)\".*/\1/p" \
        | head -n1
}

# ---------------------------------------------------------------------------
# dsh_status_var <name> <value> —— 设置状态字段（提供 setter 便于调用方统一入口）
# ---------------------------------------------------------------------------
dsh_status_var() {
    case "$1" in
        mode)        DSH_ST_MODE="$2" ;;
        state)       DSH_ST_STATE="$2" ;;
        pid)         DSH_ST_PID="$2" ;;
        started_at)  DSH_ST_STARTED_AT="$2" ;;
        uptime)      DSH_ST_UPTIME="$2" ;;
        base_url)    DSH_ST_BASE_URL="$2" ;;
        url)         DSH_ST_URL="$2" ;;
        port)        DSH_ST_PORT="$2" ;;
        version)     DSH_ST_VERSION="$2" ;;
        base_version)    DSH_ST_LAYER_BASE_VERSION="$2" ;;
        base_size)       DSH_ST_LAYER_BASE_SIZE="$2" ;;
        base_mounted)    DSH_ST_LAYER_BASE_MOUNTED="$2" ;;
        runtime_version) DSH_ST_LAYER_RUNTIME_VERSION="$2" ;;
        runtime_size)    DSH_ST_LAYER_RUNTIME_SIZE="$2" ;;
        runtime_mounted) DSH_ST_LAYER_RUNTIME_MOUNTED="$2" ;;
        dsh_version)     DSH_ST_LAYER_DSH_VERSION="$2" ;;
        dsh_size)        DSH_ST_LAYER_DSH_SIZE="$2" ;;
        dsh_mounted)     DSH_ST_LAYER_DSH_MOUNTED="$2" ;;
        upper_used)  DSH_ST_UPPER_USED="$2" ;;
        upper_total) DSH_ST_UPPER_TOTAL="$2" ;;
        last_error)  DSH_ST_LAST_ERROR="$2" ;;
        *)           return 1 ;;
    esac
    return 0
}

# ---------------------------------------------------------------------------
# dsh_status_json_inputs —— 从环境变量补齐派生字段（uptime / port / healthy）
#   调用方只要设置了 base_url / started_at，这里就能算出 port / uptime / healthy。
# ---------------------------------------------------------------------------
dsh_status_json_inputs() {
    # port：显式给了就用，否则从 base_url 解析
    if [ -z "${DSH_ST_PORT:-}" ]; then
        DSH_ST_PORT="$(dsh_port_from_url "${DSH_ST_BASE_URL:-}")"
    fi
    # uptime_sec
    if [ -z "${DSH_ST_UPTIME:-}" ]; then
        if [ -n "${DSH_ST_STARTED_AT:-}" ]; then
            local now
            now="$(date +%s)"
            if [ "$DSH_ST_STARTED_AT" -le "$now" ] 2>/dev/null; then
                DSH_ST_UPTIME=$(( now - DSH_ST_STARTED_AT ))
            fi
        fi
    fi
    # healthy：只有 running 状态才探测（stopped 时端口一定连不上，省一次 2s 超时）
    if [ -z "${DSH_ST_HEALTHY:-}" ]; then
        if [ "${DSH_ST_STATE:-}" = "running" ] && [ -n "${DSH_ST_BASE_URL:-}" ]; then
            if command -v dsh_http_healthy >/dev/null 2>&1; then
                if dsh_http_healthy "$DSH_ST_BASE_URL"; then DSH_ST_HEALTHY=true; else DSH_ST_HEALTHY=false; fi
            else
                DSH_ST_HEALTHY=false
            fi
        else
            DSH_ST_HEALTHY=false
        fi
    fi
    return 0
}

# ---------------------------------------------------------------------------
# dsh_status_emit_flat —— 把状态字段输出为 TSV（jq 路径用）
#   每行 "<key><TAB><json_value>"，json_value 已是合法 JSON 片段。
# ---------------------------------------------------------------------------
dsh_status_emit_flat() {
    printf 'schema\t%s\n'             "$(dsh_jnum 1)"
    printf 'mode\t%s\n'               "$(dsh_jstr "${DSH_ST_MODE:-}")"
    printf 'state\t%s\n'              "$(dsh_jstr "${DSH_ST_STATE:-}")"
    printf 'pid\t%s\n'                "$(dsh_jnum "${DSH_ST_PID:-}")"
    printf 'uptime_sec\t%s\n'         "$(dsh_jnum "${DSH_ST_UPTIME:-}")"
    printf 'dsh.url\t%s\n'            "$(dsh_jstr "${DSH_ST_URL:-}")"
    printf 'dsh.base_url\t%s\n'       "$(dsh_jstr "${DSH_ST_BASE_URL:-}")"
    printf 'dsh.port\t%s\n'           "$(dsh_jnum "${DSH_ST_PORT:-}")"
    printf 'dsh.version\t%s\n'        "$(dsh_jstr "${DSH_ST_VERSION:-}")"
    printf 'dsh.healthy\t%s\n'        "$(dsh_jbool "${DSH_ST_HEALTHY:-false}")"
    printf 'layers.base.version\t%s\n'    "$(dsh_jstr "${DSH_ST_LAYER_BASE_VERSION:-}")"
    printf 'layers.base.size\t%s\n'       "$(dsh_jnum "${DSH_ST_LAYER_BASE_SIZE:-}")"
    printf 'layers.base.mounted\t%s\n'    "$(dsh_jbool "${DSH_ST_LAYER_BASE_MOUNTED:-false}")"
    printf 'layers.runtime.version\t%s\n' "$(dsh_jstr "${DSH_ST_LAYER_RUNTIME_VERSION:-}")"
    printf 'layers.runtime.size\t%s\n'    "$(dsh_jnum "${DSH_ST_LAYER_RUNTIME_SIZE:-}")"
    printf 'layers.runtime.mounted\t%s\n' "$(dsh_jbool "${DSH_ST_LAYER_RUNTIME_MOUNTED:-false}")"
    printf 'layers.dsh.version\t%s\n'     "$(dsh_jstr "${DSH_ST_LAYER_DSH_VERSION:-}")"
    printf 'layers.dsh.size\t%s\n'        "$(dsh_jnum "${DSH_ST_LAYER_DSH_SIZE:-}")"
    printf 'layers.dsh.mounted\t%s\n'     "$(dsh_jbool "${DSH_ST_LAYER_DSH_MOUNTED:-false}")"
    printf 'storage.upper_used\t%s\n'  "$(dsh_jnum "${DSH_ST_UPPER_USED:-}")"
    printf 'storage.upper_total\t%s\n' "$(dsh_jnum "${DSH_ST_UPPER_TOTAL:-}")"
    printf 'last_error\t%s\n'          "$(dsh_jstr "${DSH_ST_LAST_ERROR:-}")"
}

# ---------------------------------------------------------------------------
# dsh_status_json —— 打印完整 status JSON 到 stdout（一个对象，不带缩进换行噪音）
# ---------------------------------------------------------------------------
dsh_status_json() {
    dsh_status_json_inputs

    if dsh_jq_ok; then
        # 用 TSV → jq 组装，避免手写转义。to_entries 之后按 key 的 '.' 分层 setpath。
        dsh_status_emit_flat | jq -R -s '
            [ split("\n")[] | select(length > 0) | split("\t") | { key: .[0], value: (.[1] | fromjson) } ]
            | reduce .[] as $kv ({}; setpath($kv.key | split("."); $kv.value))
        ' 2>/dev/null && return 0
        # jq 意外失败 → 落到纯 bash 实现（不返回错误，保证 App 至少拿到合法 JSON）
    fi

    local pid uptime
    pid="$(dsh_jnum "${DSH_ST_PID:-}")"
    uptime="$(dsh_jnum "${DSH_ST_UPTIME:-}")"

    printf '{'
    printf '"schema":1,'
    printf '"mode":%s,'            "$(dsh_jstr "${DSH_ST_MODE:-}")"
    printf '"state":%s,'           "$(dsh_jstr "${DSH_ST_STATE:-}")"
    printf '"pid":%s,'             "$pid"
    printf '"uptime_sec":%s,'      "$uptime"
    printf '"dsh":{'
    printf   '"url":%s,'           "$(dsh_jstr "${DSH_ST_URL:-}")"
    printf   '"base_url":%s,'      "$(dsh_jstr "${DSH_ST_BASE_URL:-}")"
    printf   '"port":%s,'          "$(dsh_jnum "${DSH_ST_PORT:-}")"
    printf   '"version":%s,'       "$(dsh_jstr "${DSH_ST_VERSION:-}")"
    printf   '"healthy":%s'        "$(dsh_jbool "${DSH_ST_HEALTHY:-false}")"
    printf '},'
    printf '"layers":{'
    printf   '"base":{"version":%s,"size":%s,"mounted":%s},' \
                "$(dsh_jstr "${DSH_ST_LAYER_BASE_VERSION:-}")" \
                "$(dsh_jnum "${DSH_ST_LAYER_BASE_SIZE:-}")" \
                "$(dsh_jbool "${DSH_ST_LAYER_BASE_MOUNTED:-false}")"
    printf   '"runtime":{"version":%s,"size":%s,"mounted":%s},' \
                "$(dsh_jstr "${DSH_ST_LAYER_RUNTIME_VERSION:-}")" \
                "$(dsh_jnum "${DSH_ST_LAYER_RUNTIME_SIZE:-}")" \
                "$(dsh_jbool "${DSH_ST_LAYER_RUNTIME_MOUNTED:-false}")"
    printf   '"dsh":{"version":%s,"size":%s,"mounted":%s}' \
                "$(dsh_jstr "${DSH_ST_LAYER_DSH_VERSION:-}")" \
                "$(dsh_jnum "${DSH_ST_LAYER_DSH_SIZE:-}")" \
                "$(dsh_jbool "${DSH_ST_LAYER_DSH_MOUNTED:-false}")"
    printf '},'
    printf '"storage":{"upper_used":%s,"upper_total":%s},' \
                "$(dsh_jnum "${DSH_ST_UPPER_USED:-}")" \
                "$(dsh_jnum "${DSH_ST_UPPER_TOTAL:-}")"
    printf '"last_error":%s'       "$(dsh_jstr "${DSH_ST_LAST_ERROR:-}")"
    printf '}\n'
}

# 直接执行：打印一份 demoted 样例（便于离线校验 schema 完整性）
if [ "${BASH_SOURCE[0]:-$0}" = "$0" ]; then
    DSH_ST_MODE="${1:-root}"
    DSH_ST_STATE="stopped"
    dsh_status_json
fi
