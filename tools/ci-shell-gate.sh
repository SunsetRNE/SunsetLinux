#!/usr/bin/env bash
# =============================================================================
# CI：跑一个 shell 自测，并把**失败原因**带成 GitHub 注释
#
# 为什么需要它：GitHub Actions 的步骤日志要**仓库权限**才看得到，而维护者常常是在
# 手机上（只读 API / 网页）排查 CI 红。默认的失败输出只有一句
# "Process completed with exit code 1." —— 完全看不出是哪几条断言红了。
# `::error::` 工作流命令会进 check-run 的 annotations，`GET /check-runs/<id>/annotations`
# 匿名就能读，于是"红了哪几条"在任何地方都能看到。
#
# 用法（CI 里）：
#   bash tools/ci-shell-gate.sh bash runtime/root/selftest.sh
#   bash tools/ci-shell-gate.sh mksh runtime/proot/selftest-funcs.sh
#
# 退出码：原样透传被跑脚本的退出码（0 通过，非 0 失败）。
# =============================================================================
set -uo pipefail

if [ "$#" -lt 2 ]; then
    printf '用法：%s <解释器> <自测脚本> [参数...]\n' "$(basename "$0")" >&2
    exit 2
fi

interp="$1"; shift
script="$1"; shift

out="$(mktemp)"
set +e
# tee 保留完整输出到 stdout（CI 步骤日志里也有一份），同时留一份给下面做摘要
"$interp" "$script" "$@" 2>&1 | tee "$out"
rc="${PIPESTATUS[0]}"
set -e

if [ "$rc" -ne 0 ]; then
    summary="$(grep -a 'FAIL' "$out" 2>/dev/null | head -5 | tr '\n' ' ' | cut -c1-900)"
    printf '::error title=%s（%s）断言失败::%s\n' \
        "$(basename "$script")" "$interp" "${summary:-（输出里没有 FAIL 行，见步骤日志）}"
fi

rm -f "$out"
exit "$rc"
