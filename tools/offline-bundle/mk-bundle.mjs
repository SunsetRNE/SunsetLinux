#!/usr/bin/env node
/**
 * sunsetlinux 离线包打包 / 校验（内置形态）
 *
 * ## 这是在解决什么
 *
 * 现在的装机路径是"装 App + 装模块 → 从频道下 96 MB 层"（几分钟，但**要联网**）。
 * 用户要的是**内嵌**：APK 里直接带环境，装完就能起 —— 而且要**好几套组合**
 * （只带必要组件 / Ubuntu / Ubuntu+proot / Ubuntu+proot+dsh），见 variants.json。
 *
 * 上游 DSHA 的做法（`assets/offline-rootfs.bin` = base+runtime，`dsh-runtime.bin` = dsh，
 * 两个都内嵌、冷装两份都解、局部更新只读 dsh 包）我们照搬**思路**，但格式自定：
 * 我们本来就是三层 erofs + overlayfs，所以"组合"= 内嵌哪几个部件，
 * 而**不是**把 rootfs 拼成一坨。
 *
 * ## 容器格式（`sunsetlinux-bundle` v1，layout `layers-split-v1`）
 *
 * ```
 * 偏移 0        4 字节    魔数 "SLB1"
 * 偏移 4        4 字节    头长度 N（小端 uint32）
 * 偏移 8        N 字节    头 JSON（UTF-8）—— 含各部件 off/len/sha256/版本
 * 偏移 8+N      载荷       各部件字节**按头里顺序原样拼接**
 * ```
 *
 * · `off` 是**相对载荷起点**的偏移（载荷起点 = 8 + N，读端自己算得出来），
 *   这样头里不用放"头的长度"，避免自引用；
 * · 载荷就是**原始分发产物**（`.erofs.zst/.gz`、proot 的 `.tar.gz`），一字节不改 ——
 *   所以"打包"只是搬运 + 记账，出问题一眼能查；
 * · 读端（App）拿头里的 `sha256` 校验后，层交给既有的解压 + `linuxctl update`，
 *   proot 解到 `$LINUX_HOME/proot`。**不新增解包器**。
 *
 * ## 用法
 *
 *   node tools/offline-bundle/mk-bundle.mjs --variant ubuntu-proot-dsh
 *   node tools/offline-bundle/mk-bundle.mjs --all --dir dist --out dist/bundles
  node tools/offline-bundle/mk-bundle.mjs --available --dir dist --out dist/bundles   # 只打"部件齐"的（CI 用）
 *   node tools/offline-bundle/mk-bundle.mjs --verify dist/bundles/ubuntu.bin
 *   node tools/offline-bundle/mk-bundle.mjs --extract <bundle.bin> --out <目录>   # 读端行为的参照实现
 *
 * 退出码：0 成功 / 1 失败（缺部件、变体不存在、校验不过都会明确说原因）。
 */
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync, openSync, readSync, closeSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..', '..');
const MAGIC = 'SLB1';
const FORMAT = 'sunsetlinux-bundle';
const SCHEMA = 1;

const die = (msg) => { console.error('ERROR: ' + msg); process.exit(1); };
const log = (msg) => console.log('==> ' + msg);

// ─────────────────────────────────────────────────────────── 参数

function parseArgs(argv) {
  const o = { dir: join(REPO, 'dist'), out: null, variant: null, all: false, verify: null, variants: join(HERE, 'variants.json') };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i] ?? die(`${a} 后面缺参数`);
    switch (a) {
      case '--variant': o.variant = next(); break;
      case '--all': o.all = true; break;
      case '--available': o.available = true; break;
      case '--dir': o.dir = next(); break;
      case '--out': o.out = next(); break;
      case '--verify': o.verify = next(); break;
      case '--extract': o.extract = next(); break;
      case '--variants': o.variants = next(); break;
      case '-h': case '--help': o.help = true; break;
      default: die(`未知参数：${a}（-h 看用法）`);
    }
  }
  return o;
}

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  console.log(`用法：
  node tools/offline-bundle/mk-bundle.mjs --variant <id> [--dir dist] [--out dist/bundles]
  node tools/offline-bundle/mk-bundle.mjs --all       [--dir dist] [--out dist/bundles]
  node tools/offline-bundle/mk-bundle.mjs --available [--dir dist] [--out dist/bundles]
  node tools/offline-bundle/mk-bundle.mjs --verify  <bundle.bin>
  node tools/offline-bundle/mk-bundle.mjs --extract <bundle.bin> --out <目录>

部件来源（都从 --dir 里按前缀找，.zst 优先、.gz 回退）：
  base-<版本>.erofs.zst|gz   runtime-<版本>.erofs.zst|gz   dsh-<版本>.erofs.zst|gz
  proot-bundle-*.tar.gz | sunsetlinux-proot-runtime.tar.gz`);
  process.exit(0);
}

// ─────────────────────────────────────────────────────────── 变体表

function loadVariants() {
  const spec = JSON.parse(readFileSync(args.variants, 'utf8'));
  const legend = spec.parts_legend ?? {};
  const variants = spec.variants ?? {};
  const ids = Object.keys(variants);
  if (ids.length === 0) die(`${args.variants} 里没有任何变体`);
  for (const id of ids) {
    const v = variants[id];
    if (!Array.isArray(v.embed)) die(`变体 ${id} 缺 embed 数组`);
    for (const p of v.embed) if (!(p in legend)) die(`变体 ${id} 里的部件 ${p} 不在 parts_legend 里`);
  }
  return { spec, variants };
}

// ─────────────────────────────────────────────────────────── 部件定位

const LAYER_PREFIX = { base: 'base-', runtime: 'runtime-', dsh: 'dsh-' };

/** 在 dir 里找 `<prefix><版本>.erofs.zst`，找不到回退 `.gz`。 */
function findLayer(dir, id) {
  const prefix = LAYER_PREFIX[id] ?? die(`未知层 id：${id}`);
  let names;
  try { names = readdirSync(dir); } catch { die(`目录不存在：${dir}`); }
  const cands = names.filter((n) => n.startsWith(prefix) && (n.endsWith('.erofs.zst') || n.endsWith('.erofs.gz')));
  if (cands.length === 0) return null;
  cands.sort((a, b) => (a.endsWith('.zst') ? -1 : 1) - (b.endsWith('.zst') ? -1 : 1) || b.localeCompare(a));
  const file = cands[0];
  const transport = file.endsWith('.zst') ? 'zstd' : 'gzip';
  const version = file.slice(prefix.length).replace(/\.erofs\.(zst|gz)$/, '');
  return { kind: 'layer', id, version, transport, file };
}

function findProot(dir) {
  let names;
  try { names = readdirSync(dir); } catch { die(`目录不存在：${dir}`); }
  const c = names.filter((n) => n.startsWith('proot-bundle-') && n.endsWith('.tar.gz'))
    .concat(names.filter((n) => n === 'sunsetlinux-proot-runtime.tar.gz'));
  if (c.length === 0) return null;
  c.sort();
  return { kind: 'proot', file: c[0] };
}

function resolvePart(dir, id) {
  if (id === 'proot') return findProot(dir);
  return findLayer(dir, id);
}

// ─────────────────────────────────────────────────────────── 已知的 raw 校验值

/**
 * `dist/SHA256SUMS.layers.txt` 里有"解压后裸镜像"的 sha256/size —— 内嵌包里带上它们，
 * App 解压后就能**立刻核对本机解压结果**（不用等频道清单）。缺了不致命：留 null 并提示。
 */
function loadRawIndex(dir) {
  const map = new Map();
  const p = join(dir, 'SHA256SUMS.layers.txt');
  if (!existsSync(p)) return map;
  for (const line of readFileSync(p, 'utf8').split('\n')) {
    const m = line.match(/^(base|runtime|dsh)-(\S+)\.erofs\s+(\d+)\s+([0-9a-f]{64})/);
    if (m) map.set(`${m[1]}:${m[2]}`, { sha256_raw: m[4], size_raw: Number(m[3]) });
  }
  return map;
}

const sha256File = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');

// ─────────────────────────────────────────────────────────── 打包

function buildOne(variantId, variants, rawIndex) {
  const v = variants[variantId] ?? die(`未知变体：${variantId}（可选：${Object.keys(variants).join(', ')}）`);
  const parts = [];
  const missing = [];
  let off = 0;
  for (const id of v.embed) {
    const found = resolvePart(args.dir, id);
    if (!found) { missing.push(id); continue; }
    const path = join(args.dir, found.file);
    const size = statSync(path).size;
    const part = {
      kind: found.kind,
      ...(found.kind === 'layer' ? { id: found.id, version: found.version, transport: found.transport } : {}),
      file: found.file,
      size,
      sha256: sha256File(path),
      off,
      len: size,
    };
    if (found.kind === 'layer') {
      const raw = rawIndex.get(`${found.id}:${found.version}`);
      part.sha256_raw = raw?.sha256_raw ?? null;
      part.size_raw = raw?.size_raw ?? null;
    }
    parts.push({ ...part, path });
    off += size;
  }
  if (missing.length) {
    die(`变体 ${variantId} 需要这些部件，但 ${args.dir} 里找不到：${missing.join(', ')}
      提示：层产物由 rootfs/build-layers.sh 产出，proot 由 tools/proot-bundle/mkproot-bundle.sh 产出。`);
  }

  const layerOf = (id) => parts.find((p) => p.id === id);
  const header = {
    format: FORMAT,
    schema: SCHEMA,
    layout: 'layers-split-v1',
    variant: variantId,
    label: v.label ?? variantId,
    built_at: new Date().toISOString().replace(/\.\d+Z$/, 'Z'),
    base_version: layerOf('base')?.version ?? null,
    runtime_version: layerOf('runtime')?.version ?? null,
    dsh_version: layerOf('dsh')?.version ?? null,
    parts: parts.map(({ path, ...rest }) => rest),
    note: v.note ?? null,
  };
  const headerBuf = Buffer.from(JSON.stringify(header), 'utf8');

  const outDir = args.out ?? join(args.dir, 'bundles');
  mkdirSync(outDir, { recursive: true });
  const outBin = join(outDir, `${variantId}.bin`);
  const lenBuf = Buffer.alloc(4);
  lenBuf.writeUInt32LE(headerBuf.length, 0);
  const payload = Buffer.concat(parts.map((p) => readFileSync(p.path)));
  writeFileSync(outBin, Buffer.concat([Buffer.from(MAGIC, 'ascii'), lenBuf, headerBuf, payload]));

  const manifest = { ...header, bundle: basename(outBin), bundle_size: statSync(outBin).size, bundle_sha256: sha256File(outBin) };
  writeFileSync(join(outDir, `${variantId}.json`), JSON.stringify(manifest, null, 2) + '\n');

  const human = (n) => `${(n / 1048576).toFixed(1)} MiB`;
  log(`变体 ${variantId}（${v.label ?? ''}）：${human(manifest.bundle_size)} → ${outBin}`);
  for (const p of header.parts) {
    console.log(`      ${p.kind === 'layer' ? `层 ${p.id} ${p.version}` : 'proot 运行时'}  ${p.file}  ${human(p.len)}`);
  }
  const noRaw = header.parts.filter((p) => p.kind === 'layer' && !p.sha256_raw);
  if (noRaw.length) console.log(`      注：${noRaw.map((p) => p.id).join(', ')} 没有 sha256_raw（dist/SHA256SUMS.layers.txt 里没有），App 端跳过"解压后核对"`);
  return outBin;
}

// ─────────────────────────────────────────────────────────── 校验（读端的最小实现）

function verify(binPath) {
  const buf = readFileSync(binPath);
  if (buf.length < 8) die(`${binPath} 太小，不是 bundle`);
  const magic = buf.subarray(0, 4).toString('ascii');
  if (magic !== MAGIC) die(`${binPath} 魔数不对（读到 ${JSON.stringify(magic)}，期望 ${MAGIC}）`);
  const headerLen = buf.readUInt32LE(4);
  if (8 + headerLen > buf.length) die(`${binPath} 头长度 ${headerLen} 超出文件大小`);
  let header;
  try { header = JSON.parse(buf.subarray(8, 8 + headerLen).toString('utf8')); }
  catch (e) { die(`${binPath} 头不是合法 JSON：${e.message}`); }
  if (header.format !== FORMAT) die(`format 字段是 ${header.format}，期望 ${FORMAT}`);

  const payloadStart = 8 + headerLen;
  let ok = 0;
  for (const p of header.parts) {
    const from = payloadStart + p.off;
    const to = from + p.len;
    if (to > buf.length) die(`部件 ${p.file} 越界（${to} > ${buf.length}）`);
    const got = createHash('sha256').update(buf.subarray(from, to)).digest('hex');
    if (got !== p.sha256) die(`部件 ${p.file} sha256 不符：${got} ≠ ${p.sha256}`);
    ok++;
  }
  // 载荷必须"不多不少"地正好覆盖文件尾（多出来的字节说明打包逻辑和头不同步）
  const last = header.parts[header.parts.length - 1];
  if (last && payloadStart + last.off + last.len !== buf.length) {
    die(`载荷与头不同步：头算出来 ${payloadStart + last.off + last.len}，文件实际 ${buf.length}`);
  }
  log(`校验通过：${basename(binPath)} · 变体 ${header.variant} · ${ok} 个部件 · ${(buf.length / 1048576).toFixed(1)} MiB`);
  return header;
}

/**
 * 把 bundle 里的部件**原样**落到目录里（每个部件用头里的 file 名）。
 *
 * 这是"读端"的参照实现：App 侧将来做同样的事（校验 sha256 → 落盘 → 层走既有的
 * 解压 + `linuxctl update`，proot 解到 `$LINUX_HOME/proot`）。
 * 写出来是为了**能被测**：打包→解包→逐字节比对，才算真的没搬坏。
 */
function extract(binPath, outDir) {
  const buf = readFileSync(binPath);
  const header = verify(binPath);
  const headerLen = buf.readUInt32LE(4);
  const payloadStart = 8 + headerLen;
  mkdirSync(outDir, { recursive: true });
  const written = [];
  for (const p of header.parts) {
    const bytes = buf.subarray(payloadStart + p.off, payloadStart + p.off + p.len);
    const to = join(outDir, p.file);
    writeFileSync(to, bytes);
    written.push({ file: p.file, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
  }
  log(`已解出 ${written.length} 个部件 → ${outDir}`);
  for (const w of written) console.log(`      ${w.file}  ${w.size} 字节  ${w.sha256.slice(0, 16)}…`);
  return { header, written };
}

// ─────────────────────────────────────────────────────────── main

if (args.verify) {
  verify(args.verify);
  process.exit(0);
}
if (args.extract) {
  extract(args.extract, args.out ?? die('--extract 需要同时给 --out <目录>'));
  process.exit(0);
}

const { variants } = loadVariants();
const rawIndex = loadRawIndex(args.dir);
if (rawIndex.size === 0) console.log(`注：${join(args.dir, 'SHA256SUMS.layers.txt')} 不存在或没有裸镜像校验值（不影响打包）`);

if (args.all || args.available) {
  // 每个变体都"打完立刻校验一遍"：搬运类代码最怕静默错位，验证是顺手的事
  const skipped = [];
  for (const id of Object.keys(variants)) {
    if (args.available) {
      // 缺哪个部件就**明确跳过**（CI 上 proot 包可能还没发过），绝不静默少打一个变体
      const missing = variants[id].embed.filter((p) => !resolvePart(args.dir, p));
      if (missing.length) { skipped.push(`${id}（缺 ${missing.join('、')}）`); continue; }
    }
    verify(buildOne(id, variants, rawIndex));
  }
  if (skipped.length) {
    console.log(`\n==> 跳过的变体（部件不全，不是错误）：${skipped.join('；')}`);
    if (process.env.GITHUB_STEP_SUMMARY) {
      writeFileSync(process.env.GITHUB_STEP_SUMMARY, `### 跳过的内置组合\n\n${skipped.map((s) => '- ' + s).join('\n')}\n`, { flag: 'a' });
    }
  }
} else if (args.variant) {
  const out = buildOne(args.variant, variants, rawIndex);
  verify(out);
} else {
  die('要么给 --variant <id>，要么给 --all（-h 看用法）');
}
