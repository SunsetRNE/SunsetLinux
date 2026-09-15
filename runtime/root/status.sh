#!/usr/bin/env bash
# =============================================================================
# dshroid · runtime/root/status.sh
#
# 薄封装：只输出 architecture.md §3.1 的 status JSON（stdout），供需要"只取状态"
# 的调用方（App、脚本、监控）使用，语义与 `linuxctl.sh status` 完全一致。
#
# 为什么是薄封装而不是重复实现：schema 必须在全项目只有**一处**生成逻辑
# （runtime/common/status_json.sh 提供生成器，linuxctl.sh 负责采集），
# 复制一份必然导致两边漂移。
#
# 用法：status.sh [--pretty]
#   --pretty  需要 jq（仅用于人读；App 走 stdout JSON 不需要）
# =============================================================================
set -uo pipefail

SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

LINUX_HOME="${LINUX_HOME:-/data/linux}"
export LINUX_HOME

CTL=""
for c in "$SELF_DIR/linuxctl.sh" "$SELF_DIR/linuxctl" "$SELF_DIR/../linuxctl.sh"; do
    [ -f "$c" ] && { CTL="$c"; break; }
done
if [ -z "$CTL" ]; then
    printf '{"schema":1,"mode":"root","state":"error","pid":null,"uptime_sec":null,'
    printf '"dsh":{"url":null,"base_url":null,"port":null,"version":null,"healthy":false},'
    printf '"layers":{"base":{"version":null,"size":null,"mounted":false},'
    printf '"runtime":{"version":null,"size":null,"mounted":false},'
    printf '"dsh":{"version":null,"size":null,"mounted":false}},'
    printf '"storage":{"upper_used":null,"upper_total":null},'
    printf '"last_error":"status.sh 找不到 linuxctl.sh"}\n'
    exit 1
fi

PRETTY=0
[ "${1:-}" = "--pretty" ] && PRETTY=1

if [ "$PRETTY" = "1" ] && command -v jq >/dev/null 2>&1; then
    bash "$CTL" status 2>/dev/null | jq .
    exit $?
fi
exec bash "$CTL" status
