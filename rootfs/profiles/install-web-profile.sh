#!/usr/bin/env bash
# 把 web-profile 模板安装成 DSH 可用的 profile 工作区。
#
# 用法（在目标 rootfs 的 chroot 内执行）：
#   install-web-profile.sh <模板目录> <目标 profile 目录>
# 例：
#   install-web-profile.sh /opt/sunsetlinux/web-profile /root/.dsh/profiles/web
#
# 背景与原理见 docs/dsh-profile.md（§5 是已实测跑通的配方）。
#
# 关键点（踩过的坑，别改）：
#   1. npm 上 @deepseek-ai/dsh-llm / dsh-base / dsh-web-app 等子包版本严重滞后
#      （只有 0.0.1-rc.1），而插件 peerDependencies 要求 0.1.5-rc.2。
#      → 用 --legacy-peer-deps 装上插件本身，再把 @deepseek-ai/* 软链到
#        DSH 主体自带的 node_modules，才能解析到正确版本。
#   2. 软链用**相对路径**，这样换 prefix 或 chroot 后依然有效。
set -euo pipefail

TEMPLATE_DIR="${1:-}"
PROFILE_DIR="${2:-/root/.dsh/profiles/web}"
DSH_GLOBAL_DIR="${DSH_GLOBAL_DIR:-/usr/local/lib/node_modules/@deepseek-ai/dsh}"
NPM_BIN="${NPM_BIN:-npm}"

log() { printf '[install-web-profile] %s\n' "$*" >&2; }
die() { printf '[install-web-profile] 错误: %s\n' "$*" >&2; exit 1; }

[ -n "$TEMPLATE_DIR" ] || die "缺少模板目录参数"
[ -d "$TEMPLATE_DIR" ] || die "模板目录不存在: $TEMPLATE_DIR"
[ -f "$TEMPLATE_DIR/package.json" ] || die "模板缺少 package.json: $TEMPLATE_DIR"

[ -d "$DSH_GLOBAL_DIR/node_modules/@deepseek-ai" ] \
  || die "找不到 DSH 自带的 @deepseek-ai 依赖树: $DSH_GLOBAL_DIR/node_modules/@deepseek-ai"

log "模板: $TEMPLATE_DIR"
log "目标: $PROFILE_DIR"
mkdir -p "$PROFILE_DIR"

# 1) 铺模板（保留已存在的 node_modules，便于重复执行时走增量）
for f in package.json cordis.yml; do
  if [ -f "$TEMPLATE_DIR/$f" ]; then
    install -m 0644 "$TEMPLATE_DIR/$f" "$PROFILE_DIR/$f"
    log "写入 $f"
  fi
done

# 2) 装插件本体（不让 npm 解析 peer 依赖）
log "npm install（--legacy-peer-deps）..."
( cd "$PROFILE_DIR" && "$NPM_BIN" install --legacy-peer-deps --no-audit --no-fund ) \
  || die "npm install 失败（检查网络或 npm 是否可用）"

# 3) 关键：把 @deepseek-ai/* 软链到 DSH 自带的依赖树（用相对路径）
NM="$PROFILE_DIR/node_modules"
mkdir -p "$NM"
TARGET_ABS="$DSH_GLOBAL_DIR/node_modules/@deepseek-ai"
# 计算从 $NM 到 $TARGET_ABS 的相对路径
REL="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' \
        "$TARGET_ABS" "$NM" 2>/dev/null \
      || realpath --relative-to="$NM" "$TARGET_ABS" 2>/dev/null \
      || true)"
[ -n "$REL" ] || die "无法计算相对路径（需要 python3 或 coreutils 的 realpath）"

if [ -e "$NM/@deepseek-ai" ] && [ ! -L "$NM/@deepseek-ai" ]; then
  log "警告: $NM/@deepseek-ai 已存在且不是软链，将备份为 .bak 后替换"
  mv "$NM/@deepseek-ai" "$NM/@deepseek-ai.bak.$$"
fi
ln -sfn "$REL" "$NM/@deepseek-ai"
log "已软链 @deepseek-ai -> $REL"

# 4) 自检：插件与 peer 依赖是否真的可解析
log "自检中..."
( cd "$PROFILE_DIR" && node -e '
const need = ["dsh-web-mobile", "dsh-task-notifier", "@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"];
let bad = 0;
for (const name of need) {
  try { await import(name); process.stderr.write(`  ok   ${name}\n`); }
  catch (e) { bad++; process.stderr.write(`  FAIL ${name}: ${e.message}\n`); }
}
process.exit(bad === 0 ? 0 : 1);
' --input-type=module ) || die "自检失败：有插件或 peer 依赖无法解析"

log "完成。"
