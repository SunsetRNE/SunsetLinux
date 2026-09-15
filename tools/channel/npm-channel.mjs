#!/usr/bin/env node
/**
 * tools/channel/npm-channel.mjs —— 「npm 作为分发渠道」的协议实现（库 + 复用的纯函数）
 *
 * ## 定位：npm 只是**传输与发现**渠道，不是信任来源
 *   频道仍然是「包名 + 公钥」。包里带 `channel.json` 与 `channel.json.sig`，
 *   **必须用用户配置的公钥验签**；验签失败一律拒绝。
 *   即使 npm 账号被盗、包被投毒、镜像被替换，攻击者**签不出**合法清单 ——
 *   因为他没有发布者的私钥。公钥**永远来自用户的 channels.json**，
 *   绝不从包里读（否则投毒包可以自带一把配套公钥，验签就形同虚设）。
 *
 * ## 支持的频道条目（channels.json）
 *   { "id":"dev", "name":"某开发者内测", "type":"npm",
 *     "package":"dshroid-channel-dev", "version":"latest",
 *     "pubkey":"<ed25519 raw base64>", "enabled":true, "priority":50 }
 *   `version` 支持 npm spec：dist-tag（latest/next/...）、确切版本、`^1.2.0`/`~1.2`/`1.x`/
 *   `>=1.0.0 <2.0.0`、`*`。不支持的写法会**明确报错**，不会"猜一个版本"。
 *
 * ## registry 配置（尊重用户/镜像源）
 *   优先级：--registry > NPM_CONFIG_REGISTRY / npm_config_registry >
 *           ./.npmrc > 用户 .npmrc（$NPM_CONFIG_USERCONFIG 或 ~/.npmrc）>
 *           `npm config get registry`（有 npm 时）> https://registry.npmjs.org/
 *   .npmrc 支持 `registry=`、`@scope:registry=`，以及 `//host/:_authToken=`
 *   （私有 registry / 内测源要用）。
 *
 * ## 为什么默认不带层文件（方案 1）
 *   我们的三层两式产物合计约 244 MB（zst 96 MB + gz 148 MB）。npm CLI 本地不限体积
 *   （实测 `npm publish --dry-run` 对 200 MB tarball 不报错），限制在 **registry 服务端**：
 *   公开 registry 与各家镜像会返回 `413 Payload Too Large`（社区多次踩到，例如
 *   "drop Next trace manifests from the npm tarball (413 on publish)"）。
 *   把 150 MB 层塞进 npm 包，等于把"能不能装上"交给各镜像的未公开阈值。
 *   所以：**包内只放 channel.json + .sig（几 KB），层 URL 指向别处**（GitHub Releases /
 *   对象存储 / 自建 HTTP）。小层（例如只含一个插件的层）可用 `--with-layers` 打进包里，
 *   但工具会按体积上限拒绝（`--max-packed-size`，默认 50 MB）。
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import os from 'node:os';
import { execFileSync } from 'node:child_process';
import { loadPublicKey, publicKeyFingerprint } from './common.mjs';

export const DEFAULT_REGISTRY = 'https://registry.npmjs.org/';
/** 打进 npm 包时建议的上限（服务端阈值各家不同，这里取保守值） */
export const DEFAULT_MAX_PACKED_SIZE = 50 * 1024 * 1024;
export const CHANNEL_MANIFEST = 'channel.json';
export const CHANNEL_SIG = 'channel.json.sig';

/* ------------------------------------------------------------- registry --- */

/** 极简 npmrc 解析：registry / @scope:registry / //host/:_authToken */
export function parseNpmrc(text) {
  const out = { registry: null, scoped: {}, tokens: {} };
  for (const rawLine of String(text).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#') || line.startsWith(';')) continue;
    const eq = line.indexOf('=');
    if (eq < 0) continue;
    const key = line.slice(0, eq).trim();
    let val = line.slice(eq + 1).trim().replace(/^["']|["']$/g, '');
    // 常见写法：${VAR} 环境变量插值
    val = val.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (_, n) => process.env[n] ?? '');
    if (!val) continue;
    if (key === 'registry') out.registry = val;
    else if (/^@[^:]+:registry$/.test(key)) out.scoped[key.slice(0, key.indexOf(':'))] = val;
    else if (key.startsWith('//') && key.endsWith(':_authToken')) {
      out.tokens[key.slice(2, key.length - ':_authToken'.length)] = val;
    }
  }
  return out;
}

function readNpmrcFile(file) {
  try {
    if (fs.existsSync(file) && fs.statSync(file).isFile()) return parseNpmrc(fs.readFileSync(file, 'utf8'));
  } catch { /* 读不了就当没有 */ }
  return { registry: null, scoped: {}, tokens: {} };
}

/**
 * 解析实际要用的 registry（含该 registry 的 _authToken）。
 * 返回 { registry, token, source }，source 说明来源，便于排错与文档复述。
 */
export function resolveRegistry({ explicit, pkg, cwd = process.cwd(), useNpmCli = true } = {}) {
  const scope = pkg && pkg.startsWith('@') ? pkg.slice(0, pkg.indexOf('/')) : null;

  const envReg = process.env.NPM_CONFIG_REGISTRY || process.env.npm_config_registry;
  const envUser = process.env.NPM_CONFIG_USERCONFIG;
  const project = readNpmrcFile(path.join(cwd, '.npmrc'));
  const user = readNpmrcFile(envUser || path.join(os.homedir(), '.npmrc'));

  const pick = (reg, source) => {
    if (!reg) return null;
    const host = reg.replace(/^[a-z]+:\/\//i, '').replace(/\/.*$/, '');
    const token = project.tokens[host] || user.tokens[host] || null;
    return { registry: reg.endsWith('/') ? reg : `${reg}/`, token, source };
  };

  if (explicit) return pick(explicit, '--registry');
  if (scope && project.scoped[scope]) return pick(project.scoped[scope], `./.npmrc 的 ${scope}:registry`);
  if (scope && user.scoped[scope]) return pick(user.scoped[scope], `~/.npmrc 的 ${scope}:registry`);
  if (envReg) return pick(envReg, 'NPM_CONFIG_REGISTRY 环境变量');
  if (project.registry) return pick(project.registry, './.npmrc 的 registry');
  if (user.registry) return pick(user.registry, '~/.npmrc 的 registry');
  if (useNpmCli) {
    try {
      const r = execFileSync('npm', ['config', 'get', 'registry'], {
        encoding: 'utf8', timeout: 8000, stdio: ['ignore', 'pipe', 'ignore'],
      }).trim();
      if (r && r !== 'undefined') return pick(r, '`npm config get registry`');
    } catch { /* 没装 npm 或超时 → 用默认 */ }
  }
  return pick(DEFAULT_REGISTRY, '默认（registry.npmjs.org）');
}

/* --------------------------------------------------- 版本 spec → 版本号 --- */

const isNum = (s) => /^\d+$/.test(s);

/** 把 '1.2' / '1' / 'v1.2.3' 补全成 [major, minor, patch]（缺的为 null 表示"任意"） */
function parsePartial(v) {
  const m = /^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(String(v).trim());
  if (!m) return null;
  return [Number(m[1]), m[2] === undefined ? null : Number(m[2]), m[3] === undefined ? null : Number(m[3])];
}

/** 只比较主干（忽略预发布），用于 ^ ~ 等范围判断 */
function cmpTriple(a, b) {
  for (let i = 0; i < 3; i++) {
    if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1;
  }
  return 0;
}

/** 单个比较器是否满足；用 common.mjs 的 compareVersions 保证与另两处语义一致 */
function satisfiesOne(version, comparator, cmp) {
  const c = String(comparator).trim();
  if (!c || c === '*' || c === 'x' || c === 'X') return true;
  const m = /^(>=|<=|>|<|=|\^|~)?\s*(.+)$/.exec(c);
  if (!m) return null;
  const op = m[1] || '=';
  const partial = parsePartial(m[2]);
  if (!partial) return null;
  const hasMinor = partial[1] !== null;
  const hasPatch = partial[2] !== null;

  if (op === '=') {
    // 部分版本视为范围：1.2 → >=1.2.0 <1.3.0；1 → >=1.0.0 <2.0.0
    if (!hasMinor) return parsePartial(version)[0] === partial[0];
    if (!hasPatch) {
      const v = parsePartial(version);
      return v[0] === partial[0] && v[1] === partial[1] && v[2] >= 0;
    }
    return cmp(version, m[2].replace(/^v/, '')) === 0;
  }
  if (op === '^' || op === '~') {
    const v = parsePartial(version);
    const low = [partial[0], partial[1] ?? 0, partial[2] ?? 0];
    if (cmpTriple(v, low) < 0) return false;
    let high;
    if (op === '^') {
      high = partial[0] === 0
        ? [0, (partial[1] ?? 0) + 1, 0]
        : [partial[0] + 1, 0, 0];
    } else {
      high = hasMinor ? [partial[0], (partial[1] ?? 0) + 1, 0] : [partial[0] + 1, 0, 0];
    }
    return cmpTriple(v, high) < 0;
  }
  // 比较类：把部分版本按"取该前缀的最小完整版本"处理
  const target = [partial[0], partial[1] ?? 0, partial[2] ?? 0].join('.');
  const d = cmp(version, target);
  switch (op) {
    case '>=': return d >= 0;
    case '>': return d > 0;
    case '<=': return d <= 0;
    case '<': return d < 0;
    default: return null;
  }
}

/**
 * spec 是否满足 version（支持 || 与空格 AND）。返回 true/false，语法不认识时返回 null。
 *
 * 遵循 npm/semver 的**预发布规则**：范围（^ ~ > < * 1.x 等）**默认不匹配预发布版本**，
 * 除非该组比较器里显式写了预发布（例如 `>=1.2.0-rc.1` 才能匹配 1.2.0-rc.2）。
 * 否则 `>=1.1.0` 会意外选到 `2.0.0-rc.1` 这种预发布版 —— 与用户预期不符。
 */
export function satisfiesSpec(version, spec, cmp) {
  const orParts = String(spec).split('||').map((p) => p.trim()).filter(Boolean);
  if (orParts.length === 0) return null;
  const isPre = String(version).includes('-');
  for (const part of orParts) {
    const comps = part.split(/\s+/).filter(Boolean);
    if (isPre) {
      // 该组里必须至少有一个比较器显式带预发布，否则预发布版本对整组不成立
      const mentionsPre = comps.some((c) => /-[0-9A-Za-z]/.test(c.replace(/^[><=~^]*\s*/, '')));
      if (!mentionsPre) continue;
    }
    let all = true;
    for (const c of comps) {
      const r = satisfiesOne(version, c, cmp);
      if (r === null) return null;
      if (!r) { all = false; break; }
    }
    if (all) return true;
  }
  return false;
}

/**
 * 按 npm spec 选版本。返回 { version, source, warnings }。
 * spec 优先级：dist-tag 命中 → 确切版本 → 范围。
 */
export function pickVersion(packument, spec, cmp) {
  const versions = Object.keys(packument.versions || {});
  if (versions.length === 0) throw new Error('该包的 metadata 里没有任何版本');
  const tags = packument['dist-tags'] || {};
  const raw = String(spec ?? 'latest').trim() || 'latest';

  if (tags[raw]) return { version: tags[raw], source: `dist-tag ${raw}` };
  if (versions.includes(raw)) return { version: raw, source: '确切版本' };
  const normalized = raw.replace(/^v/, '');
  if (versions.includes(normalized)) return { version: normalized, source: '确切版本' };

  const matched = versions.filter((v) => satisfiesSpec(v, raw, cmp) === true);
  if (matched.length === 0) {
    // 区分"语法不认识"与"确实没有匹配版本"——前者必须报错，不能猜
    const unknown = satisfiesSpec(versions[0], raw, cmp) === null;
    if (unknown) {
      throw new Error(
        `看不懂的版本 spec：${raw}（支持 dist-tag、确切版本、*、^a.b.c、~a.b.c、>=/<=/>/<、空格 AND、|| OR）`,
      );
    }
    const preOnly = versions.filter((v) => v.includes('-')).length === versions.length;
    throw new Error(
      `没有版本满足 spec "${raw}"。现有版本：${versions.slice(-8).join(', ')}` +
      (preOnly ? '\n  注意：该包只有预发布版本，范围默认不匹配预发布 —— 请用 dist-tag（如 latest/next）或写明预发布版本号' : ''),
    );
  }
  const best = matched.slice().sort(cmp).pop();
  return { version: best, source: `范围 ${raw} 中最大的匹配版本`, warnings: [] };
}

/* ------------------------------------------------------------- 下载 --- */

/** 支持 http(s):// 与 file://（后者用于离线/测试） */
export async function fetchToFile(url, dest, { token, timeoutMs = 60000 } = {}) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  if (url.startsWith('file://')) {
    const src = decodeURIComponent(new URL(url).pathname);
    fs.copyFileSync(src, dest);
    return { bytes: fs.statSync(dest).size, from: src };
  }
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), timeoutMs);
  try {
    const headers = { 'user-agent': 'dshroid-channel/1.0' };
    if (token) headers.authorization = `Bearer ${token}`;
    const r = await fetch(url, { signal: ac.signal, headers, redirect: 'follow' });
    if (!r.ok) {
      throw new Error(
        `下载失败 HTTP ${r.status} ${r.statusText}：${url}` +
        (r.status === 401 || r.status === 403 ? '（私有 registry 需要 _authToken，或该包不存在）' : '') +
        (r.status === 404 ? '（包名/版本写错，或镜像没同步到这个包）' : ''),
      );
    }
    const buf = Buffer.from(await r.arrayBuffer());
    fs.writeFileSync(dest, buf);
    return { bytes: buf.length, from: url };
  } finally {
    clearTimeout(t);
  }
}

/** 校验 npm 的 dist.integrity（SRI sha512）或 shasum（sha1）—— 防传输损坏/镜像篡改 */
export function verifyNpmIntegrity(file, dist) {
  const actual = (algo) => crypto.createHash(algo).update(fs.readFileSync(file)).digest();
  if (typeof dist?.integrity === 'string' && dist.integrity.includes('-')) {
    const [algo, b64] = dist.integrity.split('-', 2);
    const wantB64 = b64.split('?')[0];
    const got = actual(algo).toString('base64');
    return { ok: got === wantB64, algo, expected: wantB64, actual: got };
  }
  if (typeof dist?.shasum === 'string' && dist.shasum) {
    const got = actual('sha1').toString('hex');
    return { ok: got === dist.shasum, algo: 'sha1', expected: dist.shasum, actual: got };
  }
  return { ok: null, algo: null, expected: null, actual: null };
}

/* -------------------------------------------------- 最小 tar 读取器 --- */
// 只用 Node 内置 zlib 解 gzip，自己走 tar 头 —— 不依赖系统 tar（设备上是 toybox，
// 长选项支持不全），也顺带杜绝了路径穿越：只取白名单里的条目，绝不按 tar 里的路径落盘。

class ChunkReader {
  constructor(stream) {
    this.stream = stream;
    this.chunks = [];
    this.len = 0;
    this.eof = false;
    this.done = new Promise((resolve, reject) => {
      stream.on('data', (c) => { this.chunks.push(c); this.len += c.length; resolve(); });
      stream.on('end', () => { this.eof = true; resolve(); });
      stream.on('error', reject);
    });
    this.waiters = [];
    stream.on('data', () => { const w = this.waiters.shift(); if (w) w(); });
    stream.on('end', () => { while (this.waiters.length) this.waiters.shift()(); });
  }
  async pull() {
    if (this.len > 0 || this.eof) return;
    await new Promise((resolve) => { this.waiters.push(resolve); });
  }
  async read(n) {
    while (this.len < n && !this.eof) await this.pull();
    if (this.len < n) throw new Error(`tar 数据提前结束（还需要 ${n} 字节，只剩 ${this.len}）`);
    return this.take(n);
  }
  take(n) {
    const buf = Buffer.concat(this.chunks, this.len);
    const out = buf.subarray(0, n);
    const rest = buf.subarray(n);
    this.chunks = rest.length ? [rest] : [];
    this.len = rest.length;
    return Buffer.from(out);
  }
  async skip(n) {
    let left = n;
    while (left > 0) {
      if (this.len === 0) {
        if (this.eof) throw new Error('tar 数据提前结束（跳过数据时）');
        await this.pull();
        continue;
      }
      const step = Math.min(left, this.len);
      this.take(step);
      left -= step;
    }
  }
}

function octal(buf) {
  const s = buf.toString('binary').replace(/\0.*$/, '').trim();
  if (s === '') return 0;
  if (buf[0] & 0x80) {
    // base-256（大文件）
    let v = 0;
    for (const b of buf) v = v * 256 + (b & (v === 0 ? 0x7f : 0xff));
    return v;
  }
  const v = parseInt(s, 8);
  return Number.isFinite(v) ? v : 0;
}

function tarPath(header) {
  const name = header.subarray(0, 100).toString('utf8').replace(/\0.*$/, '');
  const prefix = header.subarray(345, 500).toString('utf8').replace(/\0.*$/, '');
  return prefix ? `${prefix}/${name}` : name;
}

function safeRel(p) {
  if (p.startsWith('/') || /^[a-zA-Z]:/.test(p)) return null;
  const parts = p.split('/').filter((x) => x && x !== '.');
  if (parts.some((x) => x === '..')) return null;
  return parts.join('/');
}

/**
 * 从 npm tarball（gzip tar，条目带 `package/` 前缀）里取出想要的条目。
 * @param {string} tgz
 * @param {(rel:string, size:number)=>boolean} filter  rel 已剥掉 `package/` 前缀
 * @param {{writeTo?:string, maxBytes?:number}} opts  给了 writeTo 就落盘（也要过路径检查）
 * @returns {Promise<Map<string, Buffer|string>>}  rel → Buffer（writeTo 时为落盘路径字符串）
 */
export async function readNpmTarball(tgz, filter, opts = {}) {
  const { writeTo = null, maxBytes = 512 * 1024 * 1024 } = opts;
  const src = fs.createReadStream(tgz);
  const gunzip = zlib.createGunzip();
  src.on('error', (e) => gunzip.destroy(e));
  src.pipe(gunzip);
  const reader = new ChunkReader(gunzip);

  const found = new Map();
  let total = 0;
  let longName = null;
  let paxPath = null;

  for (;;) {
    if (reader.len === 0) {
      if (reader.eof) break;
      await reader.pull();
      if (reader.len === 0 && reader.eof) break;
      continue;
    }
    const header = await reader.read(512);
    if (header.every((b) => b === 0)) break; // 结束块
    const size = octal(header.subarray(124, 136));
    const type = String.fromCharCode(header[156] || 0x30);
    const padded = Math.ceil(size / 512) * 512;

    if (type === 'L') { // GNU long name
      const data = await reader.read(padded);
      longName = data.subarray(0, size).toString('utf8').replace(/\0.*$/, '');
      continue;
    }
    if (type === 'x' || type === 'g') { // pax 扩展头
      const data = await reader.read(padded);
      for (const rec of data.subarray(0, size).toString('utf8').split('\n')) {
        const m = /^\d+ path=(.*)$/.exec(rec);
        if (m) paxPath = m[1];
      }
      continue;
    }

    let raw = paxPath || longName || tarPath(header);
    longName = null;
    paxPath = null;
    if (type === '5') { await reader.skip(padded); continue; } // 目录

    const norm = safeRel(raw);
    // 剥掉 npm tarball 的 `package/` 前缀（协议要求）
    let rel = norm;
    if (norm && norm.startsWith('package/')) rel = norm.slice('package/'.length);
    if (!rel) {
      if (norm && !norm.startsWith('package/')) {
        // 少数工具打出来的包没有前缀，也接受
        rel = norm;
      } else {
        await reader.skip(padded);
        continue;
      }
    }
    if (rel.startsWith('package/')) rel = rel.slice('package/'.length);

    if (!filter(rel, size)) { await reader.skip(padded); continue; }
    if (size > maxBytes) throw new Error(`包内文件过大，拒绝解出：${rel}（${size} 字节）`);
    total += size;
    if (total > maxBytes) throw new Error(`包内待解出内容累计超过 ${maxBytes} 字节，拒绝继续`);
    const data = await reader.read(padded);
    const body = data.subarray(0, size);
    if (writeTo) {
      const out = path.join(writeTo, rel);
      const resolved = path.resolve(out);
      if (!resolved.startsWith(path.resolve(writeTo) + path.sep)) {
        throw new Error(`tar 条目路径越界，拒绝写出：${raw}`);
      }
      fs.mkdirSync(path.dirname(resolved), { recursive: true });
      fs.writeFileSync(resolved, body);
      found.set(rel, resolved);
    } else {
      found.set(rel, Buffer.from(body));
    }
  }
  return found;
}

/* ------------------------------------------------------- 解析 + 验签 --- */

export function packumentUrl(registry, pkg) {
  const base = registry.endsWith('/') ? registry : `${registry}/`;
  // 作用域包：/@scope%2Fname（npm registry 规范）
  return `${base}${pkg.startsWith('@') ? pkg.replace('/', '%2f') : pkg}`;
}

export async function fetchPackument(pkg, { registry, token, timeoutMs = 30000 } = {}) {
  const url = packumentUrl(registry, pkg);
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), timeoutMs);
  try {
    const headers = { accept: 'application/vnd.npm.install-v1+json, application/json' };
    if (token) headers.authorization = `Bearer ${token}`;
    const r = await fetch(url, { signal: ac.signal, headers, redirect: 'follow' });
    if (!r.ok) {
      throw new Error(
        `取包元数据失败 HTTP ${r.status}：${url}` +
        (r.status === 404 ? '（包不存在，或镜像未同步）' : ''),
      );
    }
    return await r.json();
  } finally {
    clearTimeout(t);
  }
}

/**
 * 解析一个 npm 形态的频道，并**验签**。返回：
 *   { ok, package, version, registry, tarball, manifest, manifestRaw, signature,
 *     pubkeyFingerprint, integrity }
 * 任何一步失败都抛错（调用方据此拒绝该频道，不得降级）。
 */
export async function resolveNpmChannel(entry, opts = {}) {
  const {
    workDir, registry: explicitRegistry, cmp, timeoutMs = 60000,
    keepTarball = false, log = () => {},
  } = opts;
  const pkg = entry.package;
  if (!pkg) throw new Error(`npm 频道 ${entry.id ?? ''} 缺少 package 字段`);
  const spec = entry.version || 'latest';
  if (!entry.pubkey) throw new Error(`npm 频道 ${entry.id ?? ''} 缺少 pubkey（公钥必须由用户提供，不从包里读）`);

  const reg = resolveRegistry({ explicit: explicitRegistry, pkg, cwd: opts.cwd });
  log(`registry = ${reg.registry}（来自 ${reg.source}）`);

  const packument = await fetchPackument(pkg, { registry: reg.registry, token: reg.token, timeoutMs });
  const chosen = pickVersion(packument, spec, cmp);
  log(`解析 ${pkg}@${spec} → ${chosen.version}（${chosen.source}）`);
  const dist = packument.versions?.[chosen.version]?.dist;
  if (!dist?.tarball) throw new Error(`包 ${pkg}@${chosen.version} 的元数据里没有 dist.tarball`);

  fs.mkdirSync(workDir, { recursive: true });
  const tgz = path.join(workDir, `${pkg.replace(/[@/]/g, '_')}-${chosen.version}.tgz`);
  const dl = await fetchToFile(dist.tarball, tgz, { token: reg.token, timeoutMs });
  log(`下载 tarball ${dl.bytes} 字节`);

  const integrity = verifyNpmIntegrity(tgz, dist);
  if (integrity.ok === false) {
    throw new Error(
      `tarball 完整性校验失败（${integrity.algo}）：期望 ${integrity.expected}，实际 ${integrity.actual}` +
      ' —— 传输损坏或 registry 被改，拒绝使用',
    );
  }

  const files = await readNpmTarball(tgz, (rel) => rel === CHANNEL_MANIFEST || rel === CHANNEL_SIG);
  const manifestRaw = files.get(CHANNEL_MANIFEST);
  const sigRaw = files.get(CHANNEL_SIG);
  if (!manifestRaw) {
    throw new Error(`包 ${pkg}@${chosen.version} 里没有 ${CHANNEL_MANIFEST}（这不是一个 dshroid 频道包？）`);
  }
  if (!sigRaw) {
    throw new Error(
      `包 ${pkg}@${chosen.version} 里没有 ${CHANNEL_SIG} —— 没有签名的频道一律拒绝（npm 不是信任来源）`,
    );
  }

  // ★ 信任根：用**用户的**公钥验签
  const pubObj = loadPublicKey(entry.pubkey, `频道 ${entry.id ?? pkg} 的 pubkey`);
  const sigText = sigRaw.toString('utf8').trim();
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(sigText.replace(/\s+/g, ''))) {
    throw new Error(`${CHANNEL_SIG} 不是合法 base64（包内容被改过？）`);
  }
  const sig = Buffer.from(sigText.replace(/\s+/g, ''), 'base64');
  if (sig.length !== 64) throw new Error(`签名长度应为 64 字节，实际 ${sig.length}`);
  if (!crypto.verify(null, manifestRaw, pubObj, sig)) {
    throw new Error(
      '签名校验失败：包里的 channel.json 不是该公钥持有者发布的（npm 包被替换/投毒，或公钥填错）',
    );
  }

  let manifest;
  try {
    manifest = JSON.parse(manifestRaw.toString('utf8'));
  } catch (e) {
    throw new Error(`${CHANNEL_MANIFEST} 不是合法 JSON：${e.message}`);
  }

  if (!keepTarball) { try { fs.rmSync(tgz, { force: true }); } catch { /* ignore */ } }
  return {
    ok: true,
    id: entry.id ?? null,
    package: pkg,
    version: chosen.version,
    versionSource: chosen.source,
    registry: reg.registry,
    registrySource: reg.source,
    tarball: dist.tarball,
    tarballBytes: dl.bytes,
    integrity,
    pubkeyFingerprint: publicKeyFingerprint(pubObj),
    manifest,
    manifestRaw,
    signature: sig,
    tarballPath: keepTarball ? tgz : null,
  };
}
