#!/usr/bin/env bash
# ============================================================================
# 断言 — 这个 APK 到底有没有把「它该内嵌的离线包」装进去
#
# 为什么需要它（这是真实漏过的坑，不是假想）：
#   `EmbedOfflineBundle` 在 `dist/bundles/` 里找不到包时**只是打一行 lifecycle 日志
#   然后静默跳过** —— 理由是"本机开发时没打离线包也能编译"。这个设计对开发方便，
#   对发布是致命的：0.2.6 发出去的那批 APK 就是 12 MB、里面没有 offline-bundle.bin，
#   用户装完照样得联网下载环境。当时 CI 全绿，因为**没有任何一步检查 APK 内部**：
#     · bundles 节点只数了 `dist/bundles/*.bin` 够不够（够的，5 个）
#     · 编译节点只断言了 edition 专属资源（root 的模块包 / proot 的宿主脚本）
#     · 两边都没想到"制品名对不上 ⇒ 一个都没内嵌进 APK"
#   于是「内嵌离线包」这个卖点在 CI 里完全没有回归保护。
#
# 判定完全**由 tools/offline-bundle/variants.json 驱动**（单一事实源）：
#   · `embed` 非空 → APK 里**必须**有 assets/offline-bundle.bin，且大小与本地
#     同名离线包一致（防止"内嵌了另一个组合的包"这种更坏的错）
#   · `embed` 为空（如 root-minimal：root 层走频道）→ APK 里**不该**有
#
# 用法：
#   tools/ci-assert-embed.sh <apk 路径> <组合 id> [--require]
#   --require：本次运行**确实产出了**离线包（流水线路径）→ 缺了就是红。
#              不带则"没产包"只是提示（独立门禁/本机手编时不至于假红）。
# ============================================================================
set -euo pipefail

APK="${1:?用法: ci-assert-embed.sh <apk> <variant> [--require]}"
VARIANT="${2:?用法: ci-assert-embed.sh <apk> <variant> [--require]}"
REQUIRE=0
[ "${3:-}" = "--require" ] && REQUIRE=1

ROOT="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
VJ="$ROOT/tools/offline-bundle/variants.json"
ASSET='assets/offline-bundle.bin'
[ -f "$VJ" ] || { echo "::error::找不到 $VJ（本脚本必须能看到仓库根的 variants.json）"; exit 1; }
[ -f "$APK" ] || { echo "::error::找不到 APK：$APK"; exit 1; }

PARTS="$(python3 - "$VJ" "$VARIANT" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
spec = d['variants'].get(sys.argv[2])
if spec is None:
    sys.exit(f"::error::variants.json 里没有组合 {sys.argv[2]!r}")
print(','.join(spec.get('embed') or []))
PY
)" || exit 1

# 用 zipinfo 而不是 `unzip -l`：只要一行、字段位置稳定，少一个 awk 出错的机会。
SIZE="$(unzip -l "$APK" | awk -v a="$ASSET" '$0 ~ a"$" {print $1; exit}')"

if [ -z "${PARTS}" ]; then
  if [ -n "${SIZE}" ]; then
    echo "::error title=多余的离线包::$(basename "$APK") 里带着 $ASSET，但 variants.json 说 $VARIANT 的 embed 是空的（白占 $((SIZE/1048576)) MiB，且与矩阵声明矛盾）"
    exit 1
  fi
  echo "✓ $(basename "$APK")：$VARIANT 声明不内嵌离线包，确实没有（按设计走频道下载）"
  exit 0
fi

if [ -z "${SIZE}" ]; then
  if [ "$REQUIRE" = 1 ]; then
    echo "::error title=该内嵌的离线包没进 APK::$(basename "$APK") 缺少 $ASSET（组合 $VARIANT 的 embed=[$PARTS]）。"
    echo "这次运行**已经产出了**离线包，所以是制品名/路径对不上，不是「本机没打」。"
    echo "查：build-apk.yml 的 download-artifact（name: offline-bundles → dist/bundles）、"
    echo "    mk-bundle.mjs 的输出名、以及 build.gradle.kts 里 EmbedOfflineBundle 期望的"
    echo "    SunsetLinux-<engineeringVersion>-<variant>.bin / <variant>.bin。"
    ls -l "$ROOT/dist/bundles" 2>/dev/null || echo "（$ROOT/dist/bundles 不存在）"
    exit 1
  fi
  echo "· $(basename "$APK")：没内嵌离线包（本地/门禁没产包，按「未内嵌」构建）—— 发布路径下这会是红"
  exit 0
fi

# 大小必须与本地同名离线包一致：不一致就是内嵌错了一个组合的包（比"没内嵌"更难发现）。
EXPECT=""
for c in "$ROOT/dist/bundles/SunsetLinux-"*"-${VARIANT}.bin" "$ROOT/dist/bundles/${VARIANT}.bin"; do
  [ -f "$c" ] && { EXPECT="$(stat -c%s "$c")"; EXPECT_NAME="$(basename "$c")"; break; }
done
if [ -n "$EXPECT" ] && [ "$SIZE" -ne "$EXPECT" ]; then
  echo "::error title=内嵌了错误的离线包::$APK 里的 $ASSET 是 $SIZE 字节，而 ${EXPECT_NAME} 是 $EXPECT 字节（组合对不上？）"
  exit 1
fi
# ⚠️ 这里**不要**用「小于 1 MiB 就是占位」这种启发式：proot-minimal 的离线包只有
#    ~0.94 MiB（989224 字节，就是 proot 本体），会被误杀成假红（0.3.0 发布时实测）。
#    真正的判据是上面的**与本地同名包严格等大**；拿不到本地包时才退化成"至少不是空壳"
#    （真包最小也有几百 KB，空文件/占位不会超过 4 KiB）。
if [ -z "$EXPECT" ] && [ "$SIZE" -lt 4096 ]; then
  echo "::error title=内嵌的离线包太小::$APK 里的 $ASSET 只有 $SIZE 字节 —— 几乎肯定不是真环境"
  exit 1
fi

# 人读的大小：<1 MiB 用 KiB（proot-minimal 是 989224 字节，一律印 MiB 会显示成 "0 MiB"）
if [ "$SIZE" -ge 1048576 ]; then HUMAN="$((SIZE/1048576)) MiB"; else HUMAN="$((SIZE/1024)) KiB"; fi
echo "✓ $(basename "$APK")：内嵌 $ASSET（$HUMAN${EXPECT:+, 与 ${EXPECT_NAME} 大小一致}）"
