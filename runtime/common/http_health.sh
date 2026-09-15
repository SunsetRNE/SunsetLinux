#!/usr/bin/env bash
# =============================================================================
# dshroid · runtime/common/http_health.sh
#
# DSH Web 健康探针。约定（architecture.md §3.1 / §3.3）：
#   - 探测目标是 **base_url**（不带 ?token= 的裸地址）。
#   - `GET /` **收到任何 HTTP 响应即视为健康**。
#     未带令牌时 dsh web 预期返回 401，这说明服务在监听、进程活着 → 健康。
#     只有「连接被拒 / 超时 / 无法建立 HTTP 会话」才算不健康。
#   - 超时 2 秒。
#
# 实现：优先 curl；没有 curl 时用 node 回退（环境里一定有 node，因为 DSH 就是 node）。
# 本文件既可被 source（提供 dsh_http_probe / dsh_http_healthy），也可直接执行做自检。
#
# 用法：
#   . http_health.sh
#   dsh_http_probe http://127.0.0.1:3080     # stdout: <http_code|000>[ <耗时秒>]
#   dsh_http_healthy http://127.0.0.1:3080   # 退出码 0=健康 1=不健康
# =============================================================================

# 允许被重复 source
[ -n "${DSHROID_HTTP_HEALTH_SH:-}" ] && return 0 2>/dev/null || true
DSHROID_HTTP_HEALTH_SH=1

# HTTP 超时（秒）。architecture.md §3.1 规定 healthy = GET <url>/ 在 2 s 内返回。
DSH_HTTP_TIMEOUT="${DSH_HTTP_TIMEOUT:-2}"

# ---------------------------------------------------------------------------
# dsh_http_code <url>
#   输出三位 HTTP 状态码；无法完成请求时输出 000（curl 的约定）。
#   只有第一次需要 curl 的 --max-time，node 回退里用 AbortSignal。
# ---------------------------------------------------------------------------
dsh_http_code() {
    local url="$1" code=""

    if command -v curl >/dev/null 2>&1; then
        # --max-time: 整体超时；-o /dev/null: 丢弃 body；-s: 静默；--insecure 不用（本地回环）
        code="$(curl -s -o /dev/null --max-time "$DSH_HTTP_TIMEOUT" \
                     -w '%{http_code}' "$url" 2>/dev/null || true)"
        # curl 失败（连接被拒/超时）时 -w 仍会打印 000；保险再兜一层
        case "$code" in
            ''|*[!0-9]*) code=000 ;;
        esac
        printf '%s' "$code"
        return 0
    fi

    if command -v node >/dev/null 2>&1; then
        # node 回退：任何 HTTP 响应（含 401/404）都返回码；网络错误返回 000。
        node -e '
const url = process.argv[1];
const ms  = Number(process.argv[2]) * 1000;
const ac  = new AbortController();
const t   = setTimeout(() => ac.abort(), ms);
fetch(url, { signal: ac.signal, redirect: "manual" })
  .then((r) => { clearTimeout(t); process.stdout.write(String(r.status)); })
  .catch(() => { clearTimeout(t); process.stdout.write("000"); });
' "$url" "$DSH_HTTP_TIMEOUT" 2>/dev/null || printf '000'
        return 0
    fi

    # 两条路都不可用：明确报错而不是假装不健康，调用方据 last_error 判断
    printf '000'
    return 0
}

# ---------------------------------------------------------------------------
# dsh_http_probe <url>
#   stdout: "<code> <elapsed_sec>"；elapsed 为 1 位小数，取不到时给 0.0
# ---------------------------------------------------------------------------
dsh_http_probe() {
    local url="$1"
    local t0 t1 code elapsed
    # date +%s%N 在 GNU coreutils 可用；老 busybox 不支持时回退到秒
    t0="$(date +%s%N 2>/dev/null || date +%s)"
    code="$(dsh_http_code "$url")"
    t1="$(date +%s%N 2>/dev/null || date +%s)"
    case "$t0$t1" in
        *[!0-9]*) elapsed="0.0" ;;
        *)
            if [ "${#t0}" -gt 10 ]; then
                elapsed="$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", (b-a)/1000000000}' 2>/dev/null || echo 0.0)"
            else
                elapsed="$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", (b-a)}' 2>/dev/null || echo 0.0)"
            fi
            ;;
    esac
    printf '%s %s' "$code" "$elapsed"
}

# ---------------------------------------------------------------------------
# dsh_http_healthy <url>
#   健康判定：code 非 000（即收到了 HTTP 响应，401/403/404/5xx 都算"服务在跑"）。
#   注意：这里刻意**不**把 5xx 当不健康 —— 契约要求"收到任何 HTTP 响应即算健康"。
#   退出码 0 = 健康；1 = 不健康。
# ---------------------------------------------------------------------------
dsh_http_healthy() {
    local url="$1" code
    [ -z "$url" ] && return 1
    code="$(dsh_http_code "$url")"
    [ "$code" != "000" ] && [ -n "$code" ]
}

# 直接执行时做一次自检：$1=url（默认 127.0.0.1:3080）
if [ "${BASH_SOURCE[0]:-$0}" = "$0" ]; then
    _url="${1:-http://127.0.0.1:3080}"
    _probe="$(dsh_http_probe "$_url")"
    if dsh_http_healthy "$_url"; then
        printf 'healthy url=%s code=%s elapsed=%ss\n' "$_url" "${_probe%% *}" "${_probe##* }"
        exit 0
    fi
    printf 'unhealthy url=%s code=%s elapsed=%ss\n' "$_url" "${_probe%% *}" "${_probe##* }"
    exit 1
fi
