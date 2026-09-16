#!/usr/bin/env bash
# ============================================================================
# 断言 — 这个 APK 的**系统身份**（包名 + 桌面标签）与 variants.json 一致
#
# 为什么需要（两件都真实发生过）：
#   1. **桌面同名**：0.3.0 拆成两个 App 时只改了 `main/res` 的 `app_name`，按 flavor
#      覆盖的 `src/<edition>/res/` 根本不存在 → 真机上 root 版与免 root 版图标都叫
#      "SunsetLinux"，用户点哪个纯靠猜。CI 当时全绿：没有任何一步去看 APK 的标签。
#   2. **包名撞车**：两个 App 靠 `applicationId` 区分（`…​.root` / `…​.proot`）。万一
#      flavor 接线错了让两个包同名，用户装一个就把另一个**覆盖**掉，KernelSU 授权与
#      App 数据全对不上 —— 而这在编译期是"成功"的。
#
# 两者都只能**打开 APK 看**（aapt2 dump badging），所以专门做一道门禁。
#
# 用法：
#   tools/ci-assert-identity.sh <apk 路径> <组合 id>
# 环境：
#   AAPT2 可覆盖 aapt2 可执行文件路径；默认从 $ANDROID_SDK_ROOT/$ANDROID_HOME 的
#   build-tools/*/aapt2 里取版本最高的那个（AGP 构建时会下载 build-tools，所以
#   在 Gradle 之后跑就一定在）。找不到 aapt2 只**告警跳过**，不让发布卡在工具缺失上。
# ============================================================================
set -euo pipefail

APK="${1:?用法: ci-assert-identity.sh <apk> <variant>}"
VARIANT="${2:?用法: ci-assert-identity.sh <apk> <variant>}"

ROOT="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
VJ="$ROOT/tools/offline-bundle/variants.json"
[ -f "$VJ" ] || { echo "::error::找不到 $VJ"; exit 1; }
[ -f "$APK" ] || { echo "::error::找不到 APK：$APK"; exit 1; }

# ── 找 aapt2 ────────────────────────────────────────────────────────────────
if [ -z "${AAPT2:-}" ]; then
  for base in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
    [ -n "$base" ] || continue
    AAPT2="$(ls -d "$base"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n1 || true)"
    [ -n "$AAPT2" ] && break
  done
fi
[ -n "${AAPT2:-}" ] && [ -x "$AAPT2" ] || AAPT2="$(command -v aapt2 2>/dev/null || true)"
# 最后兜底：整个 SDK 目录里找一遍（AGP 自动下载的 build-tools 不在我们猜的版本目录里也能找到）
if [ -z "${AAPT2:-}" ] || [ ! -x "$AAPT2" ]; then
  for base in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
    [ -n "$base" ] && [ -d "$base" ] || continue
    AAPT2="$(find "$base" -maxdepth 4 -type f -name 'aapt2' 2>/dev/null | head -n1 || true)"
    [ -n "$AAPT2" ] && break
  done
fi
if [ -z "${AAPT2:-}" ] || [ ! -x "$AAPT2" ]; then
  echo "::warning title=没有 aapt2，跳过身份断言::找不到 aapt2（build-tools 还没下？）—— 这轮不校验包名/桌面标签"
  exit 0
fi

WANT_ID="$(python3 - "$VJ" "$VARIANT" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
vid = sys.argv[2]
if vid not in d['variants']:
    sys.exit(f"::error::variants.json 里没有组合 {vid!r}")
eid = d['variants'][vid]['edition']
print(d['editions'][eid]['application_id'])
PY
)" || exit 1

WANT_LABEL="$(python3 - "$VJ" "$VARIANT" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
vid = sys.argv[2]
eid = d['variants'][vid]['edition']
print(d['editions'][eid]['label'])
PY
)"

echo "· aapt2 = $AAPT2（这道门禁就是靠它读 APK 的包名/标签）"
BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null || true)"
[ -n "$BADGING" ] || { echo "::error::aapt2 读不出 $APK 的 badging"; exit 1; }

GOT_ID="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n1)"
GOT_LABEL="$(printf '%s\n' "$BADGING" | sed -n "s/^application-label:'\(.*\)'$/\1/p" | head -n1)"

echo "· $(basename "$APK")：包名 $GOT_ID，桌面标签「$GOT_LABEL」"

fail=0
if [ "$GOT_ID" != "$WANT_ID" ]; then
  echo "::error title=包名与矩阵不符::$APK 的包名是 $GOT_ID，variants.json 说 $VARIANT 必须是 $WANT_ID —— 两个 App 靠包名共存，装错就是互相覆盖（KernelSU 授权与 App 数据全丢）"
  fail=1
fi
if [ "$GOT_LABEL" != "$WANT_LABEL" ]; then
  echo "::error title=桌面标签与矩阵不符::$APK 的桌面标签是「$GOT_LABEL」，variants.json 说 $VARIANT 应该是「$WANT_LABEL」—— 两个 App 同名用户分不清点哪个（0.3.0 真机实测过）"
  fail=1
fi
exit $fail
