#!/usr/bin/env bash
# =============================================================================
# 生成 dist/ 的总清单（MANIFEST.txt）
#
# 为什么需要：dist/ 里有十来个产物（APK / 模块 / 三层 rootfs / 频道 / 种子 / proot bundle），
# 之前哈希散在 4 个 SHA256SUMS.* 里，没有一个地方能一眼看全"这次到底交付了什么、每个是什么"。
# 本脚本把它们汇总成一份**可复现**的清单（每次发版跑一次即可）。
#
# 用法：bash tools/mkdist-manifest.sh [--dist <目录>]
# 产物：<dist>/MANIFEST.txt
# =============================================================================
set -euo pipefail

SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_DIR="$(cd -- "$SELF_DIR/.." && pwd -P)"
DIST="$REPO_DIR/dist"

while [ $# -gt 0 ]; do
    case "$1" in
        --dist) DIST="${2:-}"; shift 2 ;;
        --dist=*) DIST="${1#*=}"; shift ;;
        -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "未知参数：$1" >&2; exit 2 ;;
    esac
done
[ -d "$DIST" ] || { echo "目录不存在：$DIST" >&2; exit 1; }

OUT="$DIST/MANIFEST.txt"
item() { # item <文件> <说明>
    local f="$1" desc="$2"
    if [ -f "$DIST/$f" ]; then
        printf '%-46s %12s  %s  %s\n' "$f" "$(stat -c %s "$DIST/$f")" "$(sha256sum "$DIST/$f" | cut -d' ' -f1)" "$desc"
    else
        printf '%-46s %12s  %-64s  %s\n' "$f" "-" "（不存在）" "$desc"
    fi
}

{
    echo "# DSHroid 交付产物总清单"
    echo "# 生成时间: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# 生成脚本: tools/mkdist-manifest.sh（可复现；每次发版重跑即可）"
    echo "#"
    echo "# 校验示例：cd dist && sha256sum -c <(awk 'NF>=3 && \$2 ~ /^[0-9]+\$/ {print \$3\"  \"\$1}' MANIFEST.txt)"
    echo
    echo "## 1) App（装它）"
    printf '%-46s %12s  %-64s  %s\n' "文件" "字节" "sha256" "说明"
    item "dshroid-launcher-debug.apk" "Android 启动器（debug 签名）"
    echo
    echo "## 2) KernelSU 模块（开机自启 + 模块 WebUI）"
    printf '%-46s %12s  %-64s  %s\n' "文件" "字节" "sha256" "说明"
    item "dshroid-module-0.1.0.zip" "用 KernelSU 管理器安装；内含 bin/ + lib/ + webroot/"
    echo
    echo "## 3) rootfs 三层（分发产物：zstd 给 App，gzip 给纯 CLI/WebUI）"
    printf '%-46s %12s  %-64s  %s\n' "文件" "字节" "sha256" "说明"
    for l in "base-24.04.3-l1" "runtime-1.0.0" "dsh-0.1.5-rc.2"; do
        item "$l.erofs.zst" "主产物（App 走这条，体积更小）"
        item "$l.erofs.gz"  "回退产物（设备侧无 zstd，纯 CLI/WebUI 走这条）"
    done
    echo
    echo "## 4) 频道（已签名，可直接发布）"
    printf '%-46s %12s  %-64s  %s\n' "文件" "字节" "sha256" "说明"
    item "channel/channel.json"     "频道清单（字段语义见 docs/architecture.md §5.2）"
    item "channel/channel.json.sig" "Ed25519 签名（对 channel.json 原始字节）"
    echo "  channel/ 下的层文件是 dist/ 根目录同名文件的硬链接（不额外占盘）"
    echo
    echo "## 5) 离线种子 / 非 root 模式"
    printf '%-46s %12s  %-64s  %s\n' "文件" "字节" "sha256" "说明"
    item "dshroid-seed-2026-09-15.tar.zst" "离线种子（ubuntu-base + Node 官方包）"
    item "proot-bundle-arm64.tar.gz"       "非 root 模式用的 proot 二进制（GPLv2 合规）"
    item "dshroid-proot-runtime.tar.gz"    "proot 运行时脚本包"
    echo
    echo "## 6) 参考清单"
    printf '%-46s %12s  %-64s  %s\n' "文件" "字节" "sha256" "说明"
    item "SHA256SUMS.layers.txt"  "三层产物的压缩/裸镜像两组哈希与真实字节数"
    item "channel.json.example"   "清单字段样例（真实三层全字段，供解析器测试）"
    item "README.txt"             "产物说明（面向使用者）"
} > "$OUT"

echo "已写入 $OUT（$(wc -l < "$OUT") 行）"
echo
sed -n '1,14p' "$OUT"
