#!/system/bin/sh
# =============================================================================
# sunsetlinux · runtime/root/update.sh
#
# 更新管理（**给 App 与模块 WebUI 共用**）。所有输出都是 JSON（stdout），
# 人类可读信息走 stderr —— 与 linuxctl 的约定一致。
#
# 为什么单独一个脚本而不是塞进 linuxctl.sh：
#   更新涉及"拉清单 + Ed25519 验签 + 下载 + sha256 校验 + 解压 + 落盘"，
#   和 linuxctl 的"生命周期控制"是两件事。分开后：
#     - WebUI 可以只调这一个脚本，不必理解 linuxctl 的全部；
#     - linuxctl.sh 只做一层极薄的转发（cmd_update_check），不与 App 契约纠缠。
#
# 安全模型（architecture.md §5，**不打折**）：
#   频道 = URL + ed25519 公钥。清单 channel.json 旁边必须有 channel.json.sig，
#   对**原始字节**验签。**验签失败 = 拒绝该频道**，绝不静默降级。
#
# 实现在浏览器里做密码学（用户/父 agent 明确要求）—— 所以：
#   验签用 Node 内置 crypto（设备上有 /opt/node），**不在 JS 前端实现 Ed25519**。
#   Node 来源：优先环境内的 /opt/node/bin/node；环境没起来时 loop 挂 runtime 层取。
#
# 子命令：
#   check         拉取所有启用频道 → 验签 → 与 state.json 比对 → 报告可更新项
#   apply <layer> 下载该层 → 校验 sha256 → 解压 → linuxctl update --version
#   versions      列出本机已有的层版本（供 rollback 选择）
#
# 环境变量：LINUX_HOME、UPDATE_DOWNLOAD_DIR（默认 $LH/cache/updates）、MODULE_DIR
#
# 子命令里 module-* 系列是**模块自身**的更新（与"层更新"分开）：
#   module-info    读 module.prop 的 version/versionCode + 本机已有的脚本版本
#   module-check   从已验签的频道清单里取可选的 module 段，比对版本
#   module-apply   下载 zip → 校验 sha256 → 落到 Download/ → **只给安装指引**
#
# ★ 为什么**不**在 WebUI 里静默替换模块自身：模块文件在 /data/adb/modules/ 下，
#   热替换有风险（正在执行的脚本被换掉、管理器缓存 module.prop、装到一半断电），
#   而且 KernelSU/Magisk 的模块安装有自己的事务与 WebUI 注册流程。
#   所以这里只做"检查 + 下载 + 校验 + 指引用户去管理器里安装"。
# =============================================================================
set -uo pipefail

SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" && pwd -P)"
LINUX_HOME="${LINUX_HOME:-/data/sunsetlinux}"
LH="$LINUX_HOME"

LAYERS_DIR="$LH/layers"
ETC_DIR="$LH/etc"
RUN_DIR="$LH/run"
CACHE_DIR="$LH/cache"
DL_DIR="${UPDATE_DOWNLOAD_DIR:-$LH/cache/updates}"
# 模块自身目录（读 module.prop、落新包）。默认 /data/adb/modules/sunsetlinux。
MODULE_DIR="${MODULE_DIR:-/data/adb/modules/sunsetlinux}"
# 模块更新包的落点：优先 Download（用户/管理器最容易拿到），回落 $LH/cache
MODULE_PKG_DIR="${MODULE_PKG_DIR:-/sdcard/Download}"
CHANNELS_JSON="$ETC_DIR/channels.json"
STATE_JSON="$ETC_DIR/state.json"

# 更新路径**只用 gzip 产物**：设备侧实测没有 zstd（/system/bin/zstd 不存在），
# 只有 toybox 的 gzip。App 侧走 .zst（体积小约 35%），WebUI 侧走 .gz。
# 这就是 layer-spec §3 要求"两种都发布"的原因。
TRANSPORT_KIND="${UPDATE_TRANSPORT:-gzip}"

log()  { printf '[update] %s\n' "$*" >&2; }
die()  { printf '[update][error] %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

emit() { printf '%s\n' "$1"; }
jesc() {
    local s="${1:-}"
    s="${s//\\/\\\\}"; s="${s//\"/\\\"}"
    s="${s//$'\n'/ }"; s="${s//$'\r'/}"; s="${s//$'\t'/ }"
    printf '%s' "$s"
}

# ---------------------------------------------------------------------------
# Node 定位（验签必须用 Node 的 crypto，且**不能在浏览器里做**）
#   1) 环境已起：/opt/node/bin/node 直接可见（runtime 层已挂）
#   2) 环境没起：把 runtime 层 loop 挂到临时目录，用挂载点里的 node
#      —— 这样"没启动环境也能检查更新"，对 WebUI 很重要
# ---------------------------------------------------------------------------
TEMP_MNT=""
cleanup_mnt() {
    [ -n "$TEMP_MNT" ] && [ -d "$TEMP_MNT" ] && umount -l "$TEMP_MNT" 2>/dev/null
    [ -n "$TEMP_MNT" ] && rmdir "$TEMP_MNT" 2>/dev/null
    return 0
}
trap cleanup_mnt EXIT

find_node() {
    local c
    for c in /opt/node/bin/node "$LH/rootfs/opt/node/bin/node"; do
        [ -x "$c" ] && { printf '%s' "$c"; return 0; }
    done
    # 从 runtime 层里取
    local rt=""
    rt="$(ls -1 "$LAYERS_DIR"/runtime-*.erofs "$LAYERS_DIR"/runtime.erofs 2>/dev/null | sort -V | tail -n1)"
    if [ -n "$rt" ]; then
        TEMP_MNT="$(mktemp -d 2>/dev/null || echo /tmp/upd-node)"
        mkdir -p "$TEMP_MNT"
        if mount -t erofs -o loop,ro "$rt" "$TEMP_MNT" 2>/dev/null; then
            [ -x "$TEMP_MNT/opt/node/bin/node" ] && { printf '%s' "$TEMP_MNT/opt/node/bin/node"; return 0; }
        fi
        umount -l "$TEMP_MNT" 2>/dev/null; rmdir "$TEMP_MNT" 2>/dev/null; TEMP_MNT=""
    fi
    have node && { command -v node; return 0; }
    return 1
}

# channel 工具链位置（验签逻辑的唯一实现，绝不在这里重写密码学）
find_channel_tool() {
    local c
    for c in "$LH/bin/sunsetlinux-channel" "$SELF_DIR/sunsetlinux-channel" "$SELF_DIR/channel/sunsetlinux-channel"; do
        [ -f "$c" ] && { printf '%s' "$c"; return 0; }
    done
    return 1
}

# ---------------------------------------------------------------------------
# 底层：用 Node 做"拉清单 + 验签"，把结果 JSON 打出来
#   为什么不直接调 sunsetlinux-channel verify：verify 面向本地文件 + 人类输出；
#   这里要的是"网络拉取 + 验签 + 结构化输出"，所以在 Node 里复用同一套
#   common.mjs 的公钥/指纹实现（保证与 CLI 工具一致），但流程自己控制。
# ---------------------------------------------------------------------------
NODE_VERIFIER="$CACHE_DIR/.update-verify.mjs"

write_node_verifier() {
    mkdir -p "$CACHE_DIR" 2>/dev/null || true
    cat > "$NODE_VERIFIER" <<'MJS'
// 由 runtime/root/update.sh 生成：拉取频道清单 + Ed25519 验签 + 结构化输出。
// 零第三方依赖，只用 Node 内置模块。**验签失败一律拒绝，不静默降级。**
import crypto from 'node:crypto';
import fs from 'node:fs';

const channelsPath = process.argv[2];
const statePath = process.argv[3];
const mode = process.argv[4] || 'layers';   // 'layers' | 'module'
const out = { schema: 1, mode, channels: [], updates: [], errors: [] };

function rawPubToKeyObject(b64) {
  const raw = Buffer.from(String(b64).trim(), 'base64');
  if (raw.length !== 32) throw new Error(`公钥应为 32 字节 raw，实际 ${raw.length}`);
  // Ed25519 raw → SPKI DER 前缀
  const der = Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), raw]);
  return crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });
}
function fingerprint(k) {
  const der = k.export({ format: 'der', type: 'spki' });
  return crypto.createHash('sha256').update(der).digest('hex').slice(0, 16);
}
async function fetchBuf(url) {
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
  return Buffer.from(await res.arrayBuffer());
}
function readInstalled() {
  try {
    const s = JSON.parse(fs.readFileSync(statePath, 'utf8'));
    const o = {};
    for (const [k, v] of Object.entries(s.layers ?? {})) o[k] = v?.version ?? null;
    return o;
  } catch { return {}; }
}
// 模块自身版本：读 module.prop 的 version=（不引额外依赖）
function readModuleVersion() {
  try {
    const t = fs.readFileSync(process.argv[5] || '', 'utf8');
    const m = t.match(/^version=(.*)$/m);
    return m ? m[1].trim() : null;
  } catch { return null; }
}
// 版本比较（与 WebUI 的 PURE.cmpVer 同一套语义，三处必须一致 ——
// 不一致会导致"WebUI 说可更新、linuxctl 说不用"这种自相矛盾）。
//   ① base 层 `<ubuntu>-l<n>`：l<n> 按整数比（字典序会得出 l10<l9 的错误结论）
//   ② semver：有预发布后缀的更小（0.1.5-rc.2 < 0.1.5）—— 不能拿 rc 跟 5 做字典序
//   ③ 主干按数字段比
function cmp(a, b) {
  const norm = (v) => String(v ?? '').trim().replace(/^v(?=\d)/i, '');  // 去掉开头的 v（module.prop 常写 v1.0.0）
  const sa = norm(a), sb = norm(b);
  const ma = sa.match(/^(.*?)-l(\d+)$/), mb = sb.match(/^(.*?)-l(\d+)$/);
  if (ma && mb && ma[1] === mb[1]) {
    const ra = Number(ma[2]), rb = Number(mb[2]);
    return ra === rb ? 0 : (ra < rb ? -1 : 1);
  }
  const split = (v) => { const i = v.indexOf('-'); return i < 0 ? { core: v, pre: null } : { core: v.slice(0, i), pre: v.slice(i + 1) }; };
  function cmpCore(x, y) {
    const px = x.split('.'), py = y.split('.');
    const n = Math.max(px.length, py.length);
    for (let i = 0; i < n; i++) {
      const a1 = px[i] ?? '0', b1 = py[i] ?? '0';
      const na = /^\d+$/.test(a1), nb = /^\d+$/.test(b1);
      if (na && nb) { if (Number(a1) !== Number(b1)) return Number(a1) < Number(b1) ? -1 : 1; }
      else if (na !== nb) return na ? 1 : -1;
      else if (a1 !== b1) return a1 < b1 ? -1 : 1;
    }
    return 0;
  }
  const A = split(sa), B = split(sb);
  const c = cmpCore(A.core, B.core);
  if (c !== 0) return c;
  if (A.pre === null && B.pre === null) return 0;
  if (A.pre === null) return 1;
  if (B.pre === null) return -1;
  const pa = A.pre.split('.'), pb = B.pre.split('.');
  const m = Math.max(pa.length, pb.length);
  for (let j = 0; j < m; j++) {
    const x1 = pa[j], y1 = pb[j];
    if (x1 === undefined) return -1;
    if (y1 === undefined) return 1;
    if (x1 === y1) continue;
    const nx1 = /^\d+$/.test(x1), ny1 = /^\d+$/.test(y1);
    if (nx1 && ny1) return Number(x1) < Number(y1) ? -1 : 1;
    if (nx1 !== ny1) return nx1 ? -1 : 1;
    return x1 < y1 ? -1 : 1;
  }
  return 0;
}

let doc;
try { doc = JSON.parse(fs.readFileSync(channelsPath, 'utf8')); }
catch (e) { out.errors.push(`读不到或解析失败 ${channelsPath}：${e.message}`); process.stdout.write(JSON.stringify(out)); process.exit(0); }

const installed = readInstalled();
out.installed = installed;
// 模块模式下先把"当前模块版本"读出来，供每个频道比对（不能等到循环之后）
if (mode === 'module') out.module_installed = readModuleVersion();

const enabled = (doc.channels ?? []).filter((c) => c.enabled !== false);
if (!enabled.length) { out.errors.push('channels.json 里没有启用的频道'); process.stdout.write(JSON.stringify(out)); process.exit(0); }

for (const c of enabled) {
  const rec = { id: c.id, name: c.name ?? c.id, url: c.url, ok: false, signature_valid: false, error: null, layers: [], dsh_npm: null };
  try {
    if (!c.url) throw new Error('频道没有 url');
    const u = new URL(c.url);
    let manifestBuf, sigBuf;
    if (u.protocol === 'file:') {
      manifestBuf = fs.readFileSync(u);
      sigBuf = fs.readFileSync(`${u.pathname}.sig`);
    } else {
      manifestBuf = await fetchBuf(c.url);
      sigBuf = await fetchBuf(`${c.url}.sig`);
    }
    const sigText = sigBuf.toString('utf8').trim().replace(/\s+/g, '');
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(sigText)) throw new Error('channel.json.sig 不是 base64（是否返回了 HTML 错误页？）');
    const sig = Buffer.from(sigText, 'base64');
    const pk = rawPubToKeyObject(c.pubkey);
    rec.pubkey_fingerprint = fingerprint(pk);
    if (sig.length !== 64) throw new Error(`签名长度应为 64 字节，实际 ${sig.length}`);
    if (!crypto.verify(null, manifestBuf, pk, sig)) {
      throw new Error('签名无效：清单与公钥不匹配（可能被改动/换过公钥/中间人替换）');
    }
    rec.signature_valid = true;
    const manifest = JSON.parse(manifestBuf.toString('utf8'));
    if (manifest.schema !== 1) throw new Error(`channel.json schema 应为 1，实际 ${manifest.schema}`);
    rec.dsh_npm = manifest.dsh_npm ?? null;

    // ---- 模块自身更新（清单里的可选 module 段）----
    // 结构（可选）：{ "version":"0.2.0", "versionCode":10001, "url":"sunsetlinux-module-0.2.0.zip",
    //                 "sha256":"…", "size":123456, "changelog":"…" }
    // 与层用**同一套安全模型**：签名无效时上面已经 throw，走不到这里。
    if (mode === 'module') {
      const mod = manifest.module;
      if (mod && mod.version) {
        const cur = out.module_installed ?? null;
        rec.module = {
          channel: c.id, channel_name: c.name ?? c.id,
          version: mod.version,
          versionCode: mod.versionCode ?? null,
          url: mod.url ?? null,
          sha256: mod.sha256 ?? null,
          size: mod.size ?? null,
          changelog: mod.changelog ?? null,
          installed: cur,
          update_available: cur === null ? true : cmp(mod.version, cur) !== 0,
          newer: cur === null ? true : cmp(mod.version, cur) > 0,
        };
        out.module = rec.module;
      }
    }
    for (const L of manifest.layers ?? []) {
      // 只挑我们这条路径能用的产物：本机没有 zstd，所以优先 url_gz
      const url = L.url_gz || (/\.(gz)$/i.test(L.url ?? '') ? L.url : null)
                || (/\.(zst|zstd)$/i.test(L.url ?? '') ? L.url : null);
      const sha = L.sha256_gz || (url === L.url ? L.sha256 : null);
      const size = L.size_gz || (url === L.url ? L.size : null);
      const kind = /\.gz$/i.test(url ?? '') ? 'gzip' : /\.(zst|zstd)$/i.test(url ?? '') ? 'zstd' : 'none';
      const cur = installed[L.id] ?? null;
      rec.layers.push({
        id: L.id, version: L.version, url, sha256: sha ?? null, size: size ?? null,
        transport: kind, sha256_raw: L.sha256_raw ?? null, size_raw: L.size_raw ?? null,
        installed: cur,
        update_available: cur === null ? true : cmp(L.version, cur) !== 0,
        newer: cur === null ? true : cmp(L.version, cur) > 0,
      });
    }
    rec.ok = true;
  } catch (e) {
    rec.error = String(e?.message ?? e);
    // 验签失败或网络失败：**拒绝该频道**（不静默继续用它）
  }
  out.channels.push(rec);
}

if (mode === 'module') {
  // 模块模式：只关心 module 段，取"有更新且优先级最高"的那个
  const found = [];
  for (const c of out.channels) if (c.module) found.push({ ...c.module, priority: (doc.channels.find((x) => x.id === c.id)?.priority ?? 0) });
  found.sort((a, b) => b.priority - a.priority);
  // ★ 只把"**更新**"当作可更新项（newer=true）。
  //   版本不同但更旧（例如频道还停在 0.2.0、本机已是 1.0.0）**不能**报"可更新"，
  //   否则会诱导用户降级 —— 这与层更新里的处理必须一致（层用 newer 语义）。
  const best = found.find((m) => m.newer === true) ?? null;
  out.module = best;   // 没有更新时为 null，前端据此显示"已是最新"
  out.module_latest = found.length ? found[0] : null;
  if (!out.channels.some((c) => c.signature_valid)) {
    out.errors.push('所有频道都未通过验签，无法检查模块更新');
  } else if (!found.length) {
    out.errors.push('已启用频道的清单里没有 module 段（发布者尚未提供模块更新）');
  }
  process.stdout.write(JSON.stringify(out));
  process.exit(0);
}

// 汇总：按优先级选可用更新（高 priority 优先；只保留"有更新"的层）
const byId = {};
for (const ch of out.channels) {
  if (!ch.signature_valid) continue;
  const pr = (doc.channels.find((x) => x.id === ch.id)?.priority ?? 0);
  for (const L of ch.layers) {
    if (!L.update_available) continue;
    if (!byId[L.id] || pr > byId[L.id].priority) byId[L.id] = { ...L, channel: ch.id, priority: pr };
  }
}
out.updates = Object.values(byId).sort((a, b) => (a.id < b.id ? -1 : 1));
process.stdout.write(JSON.stringify(out));
MJS
    chmod 0644 "$NODE_VERIFIER" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# check —— 检查更新（只读：不下载层文件）
# ---------------------------------------------------------------------------
cmd_check() {
    local node=""
    node="$(find_node || true)"
    if [ -z "$node" ]; then
        emit '{"ok":false,"error":"找不到 node（runtime 层未安装或未挂载）。检查更新需要 Node 做 Ed25519 验签（不在浏览器里实现密码学）。","hint":"先完成首次部署（linuxctl provision），或确认 runtime 层已在 layers/ 中"}'
        return 1
    fi
    if [ ! -f "$CHANNELS_JSON" ]; then
        emit '{"ok":false,"error":"没有频道配置","hint":"先在 App 的设置页添加频道，或运行：$LINUX_HOME/bin/sunsetlinux-channel channels add ..."}'
        return 1
    fi
    write_node_verifier
    local out=""
    out="$("$node" "$NODE_VERIFIER" "$CHANNELS_JSON" "$STATE_JSON" 2>/dev/null)" || true
    if [ -z "$out" ]; then
        emit '{"ok":false,"error":"检查更新失败（验签脚本没有输出）","hint":"可手动运行：linuxctl exec -- node /data/sunsetlinux/cache/.update-verify.mjs /data/sunsetlinux/etc/channels.json /data/sunsetlinux/etc/state.json"}'
        return 1
    fi
    # 网络/频道层面的错误也如实透出（不掩盖）
    printf '%s' "$out" | sed 's/^/ /' >/dev/null 2>&1 || true
    emit "$out"
    return 0
}

# ---------------------------------------------------------------------------
# apply <layer> <version> <url> [sha256]
#   App/WebUI 从 check 的结果里把参数传进来（这样脚本不必再解析清单）
#   流程：检查空间 → 下载 → 校验 sha256 → 解压 → linuxctl update --version
# ---------------------------------------------------------------------------
cmd_apply() {
    local layer="${1:-}" version="${2:-}" url="${3:-}" want_sha="${4:-}"
    case "$layer" in base|runtime|dsh) ;; *) emit '{"ok":false,"error":"layer 必须是 base/runtime/dsh"}'; return 1 ;; esac
    [ -n "$url" ] || { emit '{"ok":false,"error":"缺少下载 URL"}'; return 1; }
    [ -n "$version" ] || { emit '{"ok":false,"error":"缺少版本号（回滚语义依赖它，必须提供）"}'; return 1; }

    mkdir -p "$DL_DIR" || { emit '{"ok":false,"error":"无法创建下载目录"}'; return 1; }

    local fname base
    base="$(basename "${url%%\?*}")"
    fname="$DL_DIR/$base"
    local raw="$DL_DIR/${layer}-${version}.erofs"

    # --- 落盘前检查可用空间（裸镜像可能 200 MB 级，压缩包另计）---
    local need=0 avail_kb
    if [ -n "$want_sha" ] && [ -f "$CHANNELS_JSON" ]; then
        # 尽量取 raw 大小；取不到就用"压缩包的 4 倍"做保守估计
        need="${UPDATE_RAW_SIZE:-0}"
    fi
    avail_kb="$(df -P "$DL_DIR" 2>/dev/null | awk 'NR==2 {print $4}')"
    case "${avail_kb:-}" in ''|*[!0-9]*) avail_kb=0 ;; esac
    if [ "$avail_kb" -gt 0 ]; then
        local avail_b=$(( avail_kb * 1024 ))
        local need_b="${UPDATE_NEED_BYTES:-0}"
        # 至少要有 500MB 余量，避免把 /data 写满导致系统异常
        local floor_b=$(( 500 * 1024 * 1024 ))
        [ "$need_b" -lt "$floor_b" ] && need_b="$floor_b"
        if [ "$avail_b" -lt "$need_b" ]; then
            emit "{\"ok\":false,\"error\":\"可用空间不足\",\"available_bytes\":$avail_b,\"needed_bytes\":$need_b,\"hint\":\"清理 snapshots/ 或 cache/ 后重试\"}"
            return 1
        fi
    fi

    # --- 下载 ---
    log "下载 $url"
    local dl_ok=0
    if have curl; then
        curl -fL --retry 3 --connect-timeout 10 -o "$fname.part" "$url" >&2 2>/dev/null && dl_ok=1
    elif have wget; then
        wget -q -O "$fname.part" "$url" >&2 2>/dev/null && dl_ok=1
    fi
    if [ "$dl_ok" != "1" ]; then
        rm -f "$fname.part"
        emit "{\"ok\":false,\"error\":\"下载失败\",\"url\":\"$(jesc "$url")\",\"hint\":\"检查网络，或该频道 URL 是否可达\"}"
        return 1
    fi
    mv -f "$fname.part" "$fname" || { emit '{"ok":false,"error":"下载落盘失败"}'; return 1; }

    # --- 校验下载产物 sha256 ---
    if [ -n "$want_sha" ] && have sha256sum; then
        local got; got="$(sha256sum "$fname" 2>/dev/null | awk '{print $1}')"
        if [ "$got" != "$want_sha" ]; then
            rm -f "$fname"
            emit "{\"ok\":false,\"error\":\"sha256 校验失败（下载不完整或被替换）\",\"expected\":\"$(jesc "$want_sha")\",\"actual\":\"$(jesc "$got")\"}"
            return 1
        fi
        log "sha256 校验通过"
    fi

    # --- 解压成裸 .erofs ---
    # ★ linuxctl update 只接受**裸镜像**（会做 magic 检测，偏移 1024 认 erofs）；
    #   压缩产物必须在这里解开。设备只有 toybox gzip，所以默认走 gzip。
    rm -f "$raw"
    case "$fname" in
        *.gz)
            have gzip || { emit '{"ok":false,"error":"缺少 gzip（toybox 应自带）"}'; return 1; }
            # ★ 解压中途失败必须删掉半截裸镜像，否则 update 可能拿到一个"看着像层"
            #   的残文件（magic 检测也许还能过），造成更难查的问题。
            if ! gzip -dc "$fname" > "$raw" 2>/dev/null; then
                rm -f "$raw"    # 不留半截产物
                # 建议要**看本机有没有 zstd**：设备侧没有，就该让人重试或换频道，
                # 而不是建议一条它根本走不通的路。
                local ghint="下载不完整或产物损坏，请重试"
                if have zstd; then
                    ghint="$ghint；本机有 zstd，也可改用 .zst（体积更小）"
                else
                    ghint="$ghint；或换一个频道（本机没有 zstd，走不了 .zst）"
                fi
                emit "{\"ok\":false,\"error\":\"gzip 解压失败\",\"file\":\"$(jesc "$fname")\",\"hint\":\"$(jesc "$ghint")\"}"
                return 1
            fi
            # 二次确认：解出来的必须是**裸 erofs 镜像**（偏移 1024 的 magic），
            # 否则不交给 linuxctl（它会拒，但这里报错信息更具体）
            if [ -f "$SELF_DIR/linuxctl.sh" ]; then
                local fm
                fm="$(bash -c '. "'"$SELF_DIR"'/linuxctl.sh" >/dev/null 2>&1; layer_format "'"$raw"'"' 2>/dev/null || true)"
                if [ -n "$fm" ] && [ "$fm" != "erofs" ] && [ "$fm" != "squashfs" ]; then
                    rm -f "$raw"
                    emit '{"ok":false,"error":"解压结果不是有效的 erofs/squashfs 镜像（产物损坏或格式不符）"}'
                    return 1
                fi
            fi
            ;;
        *.zst|*.zstd)
            if have zstd; then
                zstd -dc "$fname" > "$raw" || { rm -f "$raw"; emit '{"ok":false,"error":"zstd 解压失败"}'; return 1; }
            else
                # ★ 明确拒绝：本机（设备侧）**没有 zstd**。
                #   这里在**解压前**就报错，并把该用哪个产物说清楚，
                #   避免"下载 200MB 之后才发现解不开"。
                emit "{\"ok\":false,\"error\":\"本机没有 zstd，无法解压 .zst 产物\",\"url\":\"$(jesc "$url")\",\"hint\":\"WebUI / 纯 CLI 路径请改用该频道的 .gz 产物（channel.json 的 url_gz/sha256_gz）；App 侧内置 zstd，可用 .zst（体积小约 35%）\",\"suggest\":\"gzip\"}"
                return 1
            fi
            ;;
        *)
            cp -f "$fname" "$raw" || { emit '{"ok":false,"error":"复制裸镜像失败"}'; return 1; }
            ;;
    esac

    # --- 交给 linuxctl 安装（它负责 magic 检测 + state.json + 重启 + 回滚）---
    # linuxctl 位置：优先显式环境变量（WebUI / linuxctl 转发时会传绝对路径），
    # 再退回同目录与 $LH/bin —— 三者都不在就明确报错，不猜。
    local ctl="${LINUXCTL_SH:-}"
    [ -n "$ctl" ] && [ -f "$ctl" ] || ctl=""
    [ -n "$ctl" ] || { [ -f "$SELF_DIR/linuxctl.sh" ] && ctl="$SELF_DIR/linuxctl.sh"; }
    [ -n "$ctl" ] || { [ -f "$LH/bin/linuxctl.sh" ] && ctl="$LH/bin/linuxctl.sh"; }
    if [ ! -f "$ctl" ]; then
        emit '{"ok":false,"error":"找不到 linuxctl.sh，无法完成安装"}'
        return 1
    fi
    log "安装 $layer $version"
    local upd
    upd="$(bash "$ctl" update "$layer" "$raw" --version "$version" 2>/dev/null)" || true
    # 安装完清理裸镜像（层已经落到 layers/ 了，这里不必留 200MB 副本）
    rm -f "$raw" 2>/dev/null || true
    if printf '%s' "$upd" | grep -q '"ok":true'; then
        emit "{\"ok\":true,\"layer\":\"$(jesc "$layer")\",\"version\":\"$(jesc "$version")\",\"download\":\"$(jesc "$fname")\",\"detail\":$upd}"
        return 0
    fi
    emit "{\"ok\":false,\"error\":\"安装失败\",\"detail\":${upd:-null}}"
    return 1
}

# ---------------------------------------------------------------------------
# module.prop 读取（模块自身版本）
# ---------------------------------------------------------------------------
prop_get() { # prop_get <key>
    local f="$MODULE_DIR/module.prop"
    [ -f "$f" ] || return 0
    sed -n "s/^$1=//p" "$f" 2>/dev/null | head -n1
}

# 运行时脚本包（bin/）的落地目录。与模块包不同：这是**脚本**，不是模块本体，
# 所以可以安全地交给 post-fs-data 在下次开机时同步到 $LH/bin（不会热替换正在跑的东西）。
bin_stage_root() {
    printf '%s/bin-packages' "${BIN_PKG_ROOT:-$DL_DIR}"
}

cmd_module_info() {
    local ver vc id name f
    ver="$(prop_get version)"
    vc="$(prop_get versionCode)"
    id="$(prop_get id)"
    name="$(prop_get name)"
    # 本机已同步（并已生效）的运行时脚本版本。
    #   post-fs-data.sh 每次开机把模块 bin/ 铺到 $LH/bin，并写 $LH/etc/bin-version；
    #   若存在"已落地但尚未生效"的脚本包（$LH/etc/bin-pending），说明重启后会替换。
    local binver="" pendingver=""
    [ -f "$LH/etc/bin-version" ] && binver="$(head -n1 "$LH/etc/bin-version" 2>/dev/null | tr -d ' \r\n')"
    [ -f "$LH/etc/bin-pending" ] && pendingver="$(head -n1 "$LH/etc/bin-pending" 2>/dev/null | tr -d ' \r\n')"
    printf '{"schema":1,"id":"%s","name":"%s","version":"%s","versionCode":"%s","module_dir":"%s","bin_version":"%s","bin_pending":"%s","staging_dir":"%s","installed":%s}\n' \
        "$(jesc "${id:-}")" "$(jesc "${name:-}")" "$(jesc "${ver:-}")" "$(jesc "${vc:-}")" \
        "$(jesc "$MODULE_DIR")" "$(jesc "${binver:-}")" "$(jesc "${pendingver:-}")" \
        "$(jesc "$(bin_stage_root)")" \
        "$( [ -f "$MODULE_DIR/module.prop" ] && echo true || echo false)"
    return 0
}

cmd_module_check() {
    local node=""
    node="$(find_node || true)"
    if [ -z "$node" ]; then
        emit '{"ok":false,"error":"找不到 node（runtime 层未安装）。模块更新检查需要 Node 做 Ed25519 验签。","hint":"先完成首次部署"}'
        return 1
    fi
    [ -f "$CHANNELS_JSON" ] || { emit '{"ok":false,"error":"没有频道配置","hint":"先在 App 设置页添加频道"}'; return 1; }
    write_node_verifier
    local out
    # argv[5] = module.prop 路径：Node 端据此读"当前模块版本"（用于比对）。
    # 注意要在循环**之前**就能拿到，所以由 shell 传进去，不让 Node 自己猜路径。
    out="$("$node" "$NODE_VERIFIER" "$CHANNELS_JSON" "$STATE_JSON" module "$MODULE_DIR/module.prop" 2>/dev/null)" || true
    if [ -z "$out" ]; then
        emit '{"ok":false,"error":"模块更新检查失败（验签脚本没有输出）"}'
        return 1
    fi
    emit "$out"
    return 0
}

# module-apply <version> <url> [sha256] [--bin]
#   --bin：把下载的 zip **当作运行时脚本包**，解到 staging 目录并写 bin-pending。
#          下次开机 post-fs-data.sh 会把它同步到 $LH/bin（见那里的注释与回滚方式）。
#          这不会替换模块本体，也不会热替换正在运行的脚本 —— 所以是安全的。
cmd_module_apply() {
    local version="${1:-}" url="${2:-}" want_sha="${3:-}" as_bin=0
    [ "${4:-}" = "--bin" ] && as_bin=1
    [ "${3:-}" = "--bin" ] && { as_bin=1; want_sha=""; }
    [ -n "$version" ] || { emit '{"ok":false,"error":"缺少版本号"}'; return 1; }
    [ -n "$url" ] || { emit '{"ok":false,"error":"缺少下载 URL"}'; return 1; }

    mkdir -p "$DL_DIR" 2>/dev/null || true
    local base fname
    base="$(basename "${url%%\?*}")"
    case "$base" in *.zip) ;; *) base="sunsetlinux-module-$version.zip" ;; esac
    fname="$DL_DIR/$base"

    # --- 空间检查（模块包只有几十 KB~几百 KB，但要挡住 /data 已满的情况）---
    local avail_kb
    avail_kb="$(df -P "$DL_DIR" 2>/dev/null | awk 'NR==2 {print $4}')"
    case "${avail_kb:-}" in ''|*[!0-9]*) avail_kb=0 ;; esac
    if [ "$avail_kb" -gt 0 ] && [ "$avail_kb" -lt 2048 ]; then
        emit '{"ok":false,"error":"可用空间不足（<2MB）","hint":"清理 cache/ 后重试"}'
        return 1
    fi

    # --- 下载 ---
    log "下载模块包 $url"
    local dl_ok=0
    if have curl; then
        curl -fL --retry 3 --connect-timeout 10 -o "$fname.part" "$url" >&2 2>/dev/null && dl_ok=1
    elif have wget; then
        wget -q -O "$fname.part" "$url" >&2 2>/dev/null && dl_ok=1
    fi
    [ "$dl_ok" = "1" ] || { rm -f "$fname.part"; emit "{\"ok\":false,\"error\":\"下载失败\",\"url\":\"$(jesc "$url")\"}"; return 1; }
    mv -f "$fname.part" "$fname" || { emit '{"ok":false,"error":"下载落盘失败"}'; return 1; }

    # --- sha256（验签已在 check 阶段完成；这里是完整性）---
    if [ -n "$want_sha" ] && have sha256sum; then
        local got; got="$(sha256sum "$fname" 2>/dev/null | awk '{print $1}')"
        if [ "$got" != "$want_sha" ]; then
            rm -f "$fname"
            emit "{\"ok\":false,\"error\":\"sha256 校验失败（下载不完整或被替换）\",\"expected\":\"$(jesc "$want_sha")\",\"actual\":\"$(jesc "$got")\"}"
            return 1
        fi
        log "sha256 校验通过"
    fi

    # --- 脚本包模式：解到 staging，写 bin-pending，等下次开机由 post-fs-data 同步 ---
    if [ "$as_bin" = "1" ]; then
        local root stage
        root="$(bin_stage_root)"
        stage="$root/$version"
        rm -rf "$stage" 2>/dev/null || true
        mkdir -p "$stage" || { emit '{"ok":false,"error":"无法创建脚本暂存目录"}'; return 1; }
        if ! unzip -q -o "$fname" -d "$stage" 2>/dev/null; then
            rm -rf "$stage"
            emit '{"ok":false,"error":"解包脚本包失败（不是有效 zip？）","hint":"需设备有 unzip（toybox 自带）"}'
            return 1
        fi
        # 基本健全性：必须有 bin/ 与 module.prop（否则不是模块包）
        if [ ! -d "$stage/bin" ] || [ ! -f "$stage/module.prop" ]; then
            rm -rf "$stage"
            emit '{"ok":false,"error":"脚本包里没有 bin/ 或 module.prop，拒绝落地"}'
            return 1
        fi
        mkdir -p "$LH/etc" 2>/dev/null || true
        printf '%s\n' "$version" > "$LH/etc/bin-pending" 2>/dev/null || true
        emit "{\"ok\":true,\"mode\":\"bin\",\"version\":\"$(jesc "$version")\",\"staged\":\"$(jesc "$stage")\",\"pending_file\":\"$(jesc "$LH/etc/bin-pending")\",\"effective\":\"next_boot\",\"install_hint\":\"已暂存；下次开机 post-fs-data 会同步到 $LH/bin 并写 etc/bin-version\",\"rollback\":\"若要撤销：rm -rf $stage $LH/etc/bin-pending（下次开机会回落到模块目录里的版本）\"}"
        return 0
    fi

    # --- 落到用户容易拿到的地方（Download 优先，失败回落 $LH/cache）---
    local final="$fname" note=""
    if [ -d "$MODULE_PKG_DIR" ] && [ -w "$MODULE_PKG_DIR" ]; then
        if cp -f "$fname" "$MODULE_PKG_DIR/$base" 2>/dev/null; then
            final="$MODULE_PKG_DIR/$base"
        else
            note="（复制到 $MODULE_PKG_DIR 失败，包留在 $fname）"
        fi
    else
        note="（$MODULE_PKG_DIR 不可写，包留在 $fname）"
    fi

    emit "{\"ok\":true,\"version\":\"$(jesc "$version")\",\"package\":\"$(jesc "$final")\",\"sha256\":$( [ -n "$want_sha" ] && printf '"%s"' "$(jesc "$want_sha")" || printf 'null'),\"install_hint\":\"在 KernelSU / Magisk 管理器里：模块 → 从本地安装 → 选择 $final\",\"note\":\"$(jesc "$note")\",\"did_replace_module\":false}"
    return 0
}

# ---------------------------------------------------------------------------
# versions —— 列出本机已有的层版本（给 rollback 选择用）
# ---------------------------------------------------------------------------
cmd_versions() {
    local layer id f v first
    printf '{"schema":1,"installed":{'
    first=1
    for id in base runtime dsh; do
        [ "$first" = 1 ] || printf ','
        printf '"%s":[' "$id"
        local ffirst=1
        for f in "$LAYERS_DIR/$id"-*.erofs "$LAYERS_DIR/$id"-*.squashfs; do
            [ -f "$f" ] || continue
            v="$(basename "$f")"; v="${v#$id-}"; v="${v%.*}"
            [ "$ffirst" = 1 ] || printf ','
            printf '"%s"' "$(jesc "$v")"
            ffirst=0
        done
        printf ']'
        first=0
    done
    printf '}'
    # 当前生效版本（state.json）
    printf ',"active":{'
    first=1
    for id in base runtime dsh; do
        [ "$first" = 1 ] || printf ','
        v=""
        [ -f "$STATE_JSON" ] && v="$(tr -d ' \n\t' < "$STATE_JSON" | sed -n "s/.*\"$id\":{[^}]*\"version\":\"\([^\"]*\)\".*/\1/p" | head -n1)"
        printf '"%s":' "$id"
        [ -n "$v" ] && [ "$v" != "null" ] && printf '"%s"' "$(jesc "$v")" || printf 'null'
        first=0
    done
    printf '}}\n'
    return 0
}

main() {
    local sub="${1:-}"
    shift || true
    case "$sub" in
        check)    cmd_check "$@" ;;
        apply)    cmd_apply "$@" ;;
        versions) cmd_versions "$@" ;;
        module-info)   cmd_module_info "$@" ;;
        module-check)  cmd_module_check "$@" ;;
        module-apply)  cmd_module_apply "$@" ;;
        *) printf '[update] 用法：update.sh check|apply|versions|module-info|module-check|module-apply\n' >&2; exit 2 ;;
    esac
}

main "$@"
