#!/usr/bin/env bash
# =============================================================================
# CI：给一个步骤脚本套上"边跑边留一份日志"
#
# 为什么需要它：GitHub Actions 的**步骤日志要仓库权限**才看得到，而维护者常常是在手机上
# （只能读公开 API）排查 CI 红。`::error::` 注释匿名可读，但注释只能带**我们自己打印**的
# 内容 —— 所以这里把每一步的输出 tee 到 $RUNNER_TEMP/ci-steps.log，
# 再由工作流末尾那个 `if: failure()` 的步骤把尾部贴成注释。
#
# 用法（工作流里）：把 job 的默认 shell 指到它：
#     defaults:
#       run:
#         shell: bash ${{ github.workspace }}/tools/ci-step-tee.sh {0}
# 于是每个 `run:` 步骤都会变成：
#     bash tools/ci-step-tee.sh /tmp/xxxx.sh
#
# ⚠️ 只影响 `run:` 步骤；`uses:` 步骤（action）本来就不经过 shell。
# 退出码原样透传，`set -e` 语义由步骤脚本自己那份 `set -euo pipefail` 保证。
# =============================================================================
set -uo pipefail

script="${1:?用法: ci-step-tee.sh <步骤脚本>}"
log="${RUNNER_TEMP:-/tmp}/ci-steps.log"

printf '\n===== %s =====\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ') step: $(basename "$script")" >>"$log"

set +e
# bash -e：与 GitHub 默认 shell（`bash -e {0}`）保持一致
bash -e "$script" 2>&1 | tee -a "$log"
rc="${PIPESTATUS[0]}"
set -e

printf '===== rc=%s =====\n' "$rc" >>"$log"
exit "$rc"
