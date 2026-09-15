#!/usr/bin/env node
/**
 * tools/channel/channels-edit.mjs —— 增删改 channels.json 里的频道条目
 *
 * channels.json 结构见 architecture.md §5.1：
 *   { schema:1, channels:[{id,name,url,pubkey,enabled,priority}] }
 *
 * 频道 = **URL 或 npm 包名** + 公钥。第三方开发者自己发一个 channel.json（或一个 npm 包）
 * 就能被加进来，本工具就是那个"加频道"的入口（也是 App 设置页频道管理的命令行等价物）。
 *
 * 两种形态（`type` 字段，缺省为 http，向后兼容）：
 *   { "type":"http", "url":"https://.../channel.json", "pubkey":"..." }
 *   { "type":"npm",  "package":"sunsetlinux-channel-dev", "version":"latest", "pubkey":"..." }
 * npm 只是传输/发现渠道，**信任根仍是公钥验签**（npm 包被投毒也签不出合法清单）。
 *
 * 用法示例：
 *   node channels-edit.mjs list
 *   node channels-edit.mjs add --id dev-x --name "某开发者内测" \
 *        --url https://example.org/sunsetlinux/channel.json --pub dev-x.pub --priority 50
 *   node channels-edit.mjs check --id dev-x        # 联网拉清单并验签，体检该频道
 *   node channels-edit.mjs disable --id official
 *   node channels-edit.mjs remove --id dev-x
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {
  parseArgs, wantsHelp, printHelp, die, info, ok, warn,
  tryLoadPublicKey, publicKeyFingerprint, keyObjectToRawPub,
  readJson, writeFileEnsured, bold, dim, green, red, yellow, compareVersions,
} from './common.mjs';
import { resolveNpmChannel } from './npm-channel.mjs';

const HELP = `用法：node channels-edit.mjs <子命令> [选项]

子命令：
  list                          列出所有频道
  add                           新增频道
  remove                        删除频道（--id）
  set                           修改频道字段（--id + 要改的字段）
  enable / disable              启用 / 停用频道（--id）
  check                         体检频道：http 拉 channel.json+.sig；npm 拉 tarball 解包后验签
  init                          创建一个空的 channels.json

通用选项：
  --file <文件>     channels.json 路径（默认：$LINUX_HOME/etc/channels.json，否则 ./channels.json）
  --json            结果以 JSON 打到 stdout
  --help            显示本帮助

add / set 的字段选项：
  --id <id>         频道短标识（小写字母/数字/连字符）
  --name <名字>     显示名
  --type <http|npm>  频道类型（默认 http，向后兼容）
  --url <URL>       type=http 时：channel.json 的完整 URL（不能只写目录）
  --package <包名>  type=npm  时：npm 包名（可带 scope）
  --version <spec>  type=npm  时：npm spec（dist-tag / 确切版本 / 范围），默认 latest
  --pub <文件|b64>  公钥：channel.pub 文件路径、裸 base64 或 PEM
  --priority <N>    优先级，数字越大越优先（官方默认 100，第三方建议 50）
  --disabled        新增时直接停用

安全提醒：
  加频道前请通过**带外渠道**核对公钥指纹（例如发布者在群里贴的），
  只从同一个 URL 上同时取 channel.json 和公钥等于没有校验。`;

const SUBS = ['list', 'add', 'remove', 'set', 'enable', 'disable', 'check', 'init', 'help'];

const spec = {
  file: { type: 'string', default: '' },
  id: { type: 'string', default: '' },
  name: { type: 'string', default: '' },
  url: { type: 'string', default: '' },
  pub: { type: 'string', default: '' },
  pubkey: { type: 'string', default: '' },
  priority: { type: 'string', default: '' },
  enabled: { type: 'string', default: '' },
  disabled: { type: 'boolean', default: false },
  type: { type: 'string', default: '' },
  package: { type: 'string', default: '' },
  version: { type: 'string', default: '' },
  registry: { type: 'string', default: '' },
  'npm-cache': { type: 'string', default: '' },
  json: { type: 'boolean', default: false },
  force: { type: 'boolean', default: false },
};

function defaultFile() {
  const home = process.env.LINUX_HOME;
  if (home && fs.existsSync(home)) return path.join(home, 'etc', 'channels.json');
  return path.resolve('channels.json');
}

function loadChannels(file) {
  if (!fs.existsSync(file)) {
    warn(`${file} 不存在，按空清单处理（用 init 子命令可显式创建）。`);
    return { schema: 1, channels: [] };
  }
  const j = readJson(file);
  if (j.schema !== 1) die(`channels.json 的 schema 应为 1，实际是 ${JSON.stringify(j.schema)}`);
  if (!Array.isArray(j.channels)) die('channels.json 缺少 channels 数组');
  // 校验每条条目，坏数据早报比运行时炸好。
  for (const c of j.channels) {
    const type = c.type || 'http';
    if (type !== 'http' && type !== 'npm') {
      die(`频道 ${JSON.stringify(c.id)} 的 type 不认识：${type}（只支持 http | npm）`);
    }
    const required = type === 'npm' ? ['id', 'name', 'package', 'pubkey'] : ['id', 'name', 'url', 'pubkey'];
    for (const k of required) {
      if (typeof c[k] !== 'string' || !c[k]) {
        die(`频道 ${JSON.stringify(c.id)}（type=${type}）的字段 ${k} 缺失或不是字符串`);
      }
    }
    if (type === 'npm' && c.version !== undefined && typeof c.version !== 'string') {
      die(`频道 ${JSON.stringify(c.id)} 的 version 必须是字符串（npm spec）`);
    }
  }
  return j;
}

function saveChannels(file, doc) {
  doc.channels.sort((a, b) => (b.priority ?? 0) - (a.priority ?? 0) || String(a.id).localeCompare(String(b.id)));
  const body = JSON.stringify(doc, null, 2) + '\n';
  writeFileEnsured(file, body, { mode: 0o644 });
  return file;
}

function findChannel(doc, id) {
  if (!id) die('缺少 --id');
  const c = doc.channels.find((x) => x.id === id);
  if (!c) die(`没有 id 为 "${id}" 的频道。现有：${doc.channels.map((x) => x.id).join(', ') || '(空)'}`);
  return c;
}

function normalizePub(input) {
  const r = tryLoadPublicKey(input, '公钥');
  if (!r.ok) die(r.error);
  const b64 = keyObjectToRawPub(r.key).toString('base64');
  return { b64, fp: publicKeyFingerprint(r.key), obj: r.key };
}

function validateNpmPackage(name) {
  if (!/^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/.test(name)) {
    die(`npm 包名不合法：${name}（全小写；作用域形如 @scope/name）`);
  }
  return name;
}

function validateUrl(url) {
  let u;
  try {
    u = new URL(url);
  } catch {
    die(`--url 不是合法 URL：${url}\n注意必须指向 channel.json 本身，而不是目录。`);
  }
  if (!['http:', 'https:', 'file:'].includes(u.protocol)) {
    die(`--url 的协议不支持：${u.protocol}（只支持 http/https/file）`);
  }
  if (!/\.json$/i.test(u.pathname)) {
    warn(`URL 不以 .json 结尾（${u.pathname}）——请确认它指向的是 channel.json，而不是目录。`);
  }
  return u.toString();
}

function validateId(id) {
  if (!/^[a-z0-9][a-z0-9._-]{0,62}$/i.test(id)) {
    die(`--id 不合法：${id}（只允许字母数字 . _ -，长度 1–63，且不以符号开头）`);
  }
  return id;
}

async function fetchText(url, timeoutMs = 15000) {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), timeoutMs);
  try {
    const r = await fetch(url, { signal: ac.signal, redirect: 'follow' });
    if (!r.ok) throw new Error(`HTTP ${r.status} ${r.statusText}`);
    return Buffer.from(await r.arrayBuffer());
  } finally {
    clearTimeout(t);
  }
}

async function cmdCheck(doc, positionals, values) {
  // 分离出 npm 频道：它们要拉 tarball 并解包验签，走 npm-channel 模块
  return cmdCheckMixed(doc, positionals, values);
}

async function cmdCheckMixed(doc, positionals, values) {
  // --id <id> 与位置参数 <id> 都接受；都不给才体检全部频道。
  const ids = positionals.length ? positionals : (values.id ? [values.id] : []);
  const targets = ids.length ? ids.map((id) => findChannel(doc, id)) : doc.channels;
  if (!targets.length) die('channels.json 里没有频道可体检');
  const results = [];
  let allOk = true;

  for (const c of targets) {
    const type = c.type || 'http';
    const target = type === 'npm' ? `npm:${c.package}@${c.version || 'latest'}` : c.url;
    const r = {
      id: c.id, name: c.name, type, url: type === 'http' ? c.url : null,
      package: type === 'npm' ? c.package : null,
      version: type === 'npm' ? (c.version || 'latest') : null,
      ok: false, signature_valid: false, error: null, manifest_name: null,
    };
    process.stderr.write(`${bold(`—— 频道 ${c.id}（${c.name}）`)}  ${dim(`[${type}]`)}  \n  ${dim(target)}\n`);
    try {
      let manifestBuf;
      let pubObj;

      if (type === 'npm') {
        // npm 形态：解析 registry → 下 tarball → 解出 channel.json+.sig → 验签
        const pk = tryLoadPublicKey(c.pubkey, `频道 ${c.id} 的 pubkey`);
        if (!pk.ok) throw new Error(pk.error);
        pubObj = pk.key;
        const workDir = values['npm-cache'] ? path.resolve(values['npm-cache']) : path.join(os.tmpdir(), 'sunsetlinux-npm-check');
        const res = await resolveNpmChannel({ ...c, pubkey: c.pubkey }, {
          workDir,
          registry: values.registry || undefined,
          cmp: compareVersions,
          log: (m) => process.stderr.write(`  ${dim(m)}\n`),
        });
        manifestBuf = res.manifestRaw;
        r.signature_valid = true;
        r.registry = res.registry;
        r.registry_source = res.registrySource;
        r.resolved_version = res.version;
        r.tarball_bytes = res.tarballBytes;
        r.integrity = res.integrity?.ok === null ? 'metadata 未提供 integrity' : `${res.integrity.algo} 校验通过`;
        r.manifest_name = res.manifest.name ?? null;
        r.layers = (res.manifest.layers || []).map((l) => `${l.id}@${l.version}`);
        r.ok = true;
        process.stderr.write(
          `  ${green('✅ 验签通过')}（npm 包 ${c.package}@${res.version}，tarball ${res.tarballBytes} 字节，${r.integrity}）\n` +
          `  ${dim('registry：' + res.registry + '（' + res.registrySource + '）')}\n` +
          `  ${dim('公钥指纹：' + res.pubkeyFingerprint)}\n` +
          `  ${dim('层：' + (r.layers.join(', ') || '(无)'))}\n`,
        );
        results.push(r);
        continue;
      }

      const url = new URL(c.url);
      let sigBuf;
      if (url.protocol === 'file:') {
        manifestBuf = fs.readFileSync(url);
        sigBuf = fs.readFileSync(`${url.pathname}.sig`);
      } else {
        manifestBuf = await fetchText(c.url);
        sigBuf = await fetchText(`${c.url}.sig`);
      }
      const sigText = sigBuf.toString('utf8').trim();
      if (!/^[A-Za-z0-9+/]+={0,2}$/.test(sigText.replace(/\s+/g, ''))) {
        throw new Error('channel.json.sig 不是 base64（是否返回了 HTML 错误页？）');
      }
      const sig = Buffer.from(sigText.replace(/\s+/g, ''), 'base64');
      const pk = tryLoadPublicKey(c.pubkey, `频道 ${c.id} 的 pubkey`);
      if (!pk.ok) throw new Error(pk.error);
      pubObj = pk.key;
      const valid = sig.length === 64 && crypto.verify(null, manifestBuf, pubObj, sig);
      r.signature_valid = valid;
      if (!valid) {
        throw new Error('签名校验失败 —— 清单不是该公钥持有者发布的，或内容被改过');
      }
      const j = JSON.parse(manifestBuf.toString('utf8'));
      r.manifest_name = j.name ?? null;
      r.layers = (j.layers || []).map((l) => `${l.id}@${l.version}`);
      r.ok = true;
      process.stderr.write(`  ${green('✅ 验签通过')}  清单名：${j.name ?? '(未命名)'}  层：${r.layers.join(', ') || '(无)'}\n`);
      process.stderr.write(`  ${dim('公钥指纹：' + publicKeyFingerprint(pubObj))}\n`);
    } catch (e) {
      allOk = false;
      r.error = String(e.message || e);
      process.stderr.write(`  ${red('❌ ' + r.error)}\n`);
    }
    results.push(r);
  }
  if (values.json) process.stdout.write(JSON.stringify({ schema: 1, results }, null, 2) + '\n');
  process.stderr.write('\n');
  if (allOk) ok('全部频道验签通过。');
  else warn('存在未通过的频道 —— 请不要使用它们发布的内容。');
  process.exit(allOk ? 0 : 1);
}

async function main() {
  const argv = process.argv.slice(2);
  if (wantsHelp(argv) || argv.length === 0) {
    return printHelp('sunsetlinux 频道管理（channels-edit）', HELP);
  }
  // 第一个非选项参数是子命令。
  const subIdx = argv.findIndex((a) => !a.startsWith('-'));
  const sub = subIdx >= 0 ? argv[subIdx] : 'help';
  if (!SUBS.includes(sub)) die(`未知子命令：${sub}（可用：${SUBS.join(' / ')}）`);
  if (sub === 'help') return printHelp('sunsetlinux 频道管理（channels-edit）', HELP);

  const rest = argv.filter((_, i) => i !== subIdx);
  const { values, positionals } = parseArgs(rest, spec);
  const file = values.file ? path.resolve(values.file) : defaultFile();

  if (sub === 'init') {
    if (fs.existsSync(file) && !values.force) die(`已存在：${file}（要重建请加 --force）`);
    saveChannels(file, { schema: 1, channels: [] });
    ok(`已创建空清单：${file}`);
    if (values.json) process.stdout.write(JSON.stringify({ file, channels: [] }) + '\n');
    return;
  }

  const doc = loadChannels(file);

  switch (sub) {
    case 'list': {
      if (!doc.channels.length) {
        warn('当前没有任何频道。');
      } else {
        process.stderr.write(bold(`频道列表（${file}）\n`));
        for (const c of doc.channels.slice().sort((a, b) => (b.priority ?? 0) - (a.priority ?? 0))) {
          const pk = tryLoadPublicKey(c.pubkey, `频道 ${c.id} 的 pubkey`);
          const fp = pk.ok ? publicKeyFingerprint(pk.key) : '(公钥非法)';
          const type = c.type || 'http';
          const target = type === 'npm'
            ? `npm:${c.package}@${c.version || 'latest'}`
            : c.url;
          process.stderr.write(
            `  ${(c.enabled ? green('●启用') : red('○停用'))} ${bold(String(c.id).padEnd(14))} ` +
            `${String(type).padEnd(5)} prio=${String(c.priority ?? 0).padEnd(4)} ${dim(fp)}\n` +
            `         ${c.name}\n         ${dim(target)}\n`,
          );
        }
      }
      if (values.json) process.stdout.write(JSON.stringify(doc, null, 2) + '\n');
      return;
    }

    case 'add': {
      const id = validateId(values.id || die('add 需要 --id'));
      if (doc.channels.some((c) => c.id === id)) {
        die(`id "${id}" 已存在。要改它请用 set 子命令，或先 remove。`);
      }
      const type = (values.type || 'http').toLowerCase();
      if (type !== 'http' && type !== 'npm') die(`--type 不认识：${type}（只支持 http | npm）`);
      const pubInput = values.pub || values.pubkey || die('add 需要 --pub <公钥文件|base64>');
      const { b64, fp } = normalizePub(pubInput);
      const priority = values.priority === '' ? 50 : Number(values.priority);
      if (!Number.isFinite(priority)) die(`--priority 必须是数字，实际：${values.priority}`);
      const entry = {
        id,
        name: values.name || id,
        type,
        pubkey: b64,
        enabled: values.disabled ? false : true,
        priority,
      };
      if (type === 'npm') {
        entry.package = validateNpmPackage(values.package || die('add --type npm 需要 --package <npm 包名>'));
        entry.version = values.version || 'latest';
      } else {
        entry.url = validateUrl(values.url || die('add 需要 --url（channel.json 的完整 URL）'));
      }
      doc.channels.push(entry);
      saveChannels(file, doc);
      ok(`已添加频道 ${id}（${entry.name}，type=${type}），priority=${priority}`);
      process.stderr.write(`  公钥指纹：${green(fp)}\n  ${dim('请通过带外渠道核对这个指纹！')}\n`);
      process.stderr.write(`  ${dim('建议接着跑：node channels-edit.mjs check --id ' + id)}\n`);
      if (values.json) process.stdout.write(JSON.stringify(entry, null, 2) + '\n');
      return;
    }

    case 'remove': {
      const id = values.id || positionals[0] || die('remove 需要 --id');
      const c = findChannel(doc, id);
      const wasEnabled = c.enabled;
      doc.channels = doc.channels.filter((x) => x.id !== id);
      if (wasEnabled && !doc.channels.some((x) => x.enabled)) {
        warn('注意：删掉它之后已经没有任何启用的频道了。');
      }
      saveChannels(file, doc);
      ok(`已删除频道 ${id}`);
      if (values.json) process.stdout.write(JSON.stringify({ removed: id }, null, 2) + '\n');
      return;
    }

    case 'set': {
      const c = findChannel(doc, values.id);
      const changed = [];
      if (values.type) {
        const t = values.type.toLowerCase();
        if (t !== 'http' && t !== 'npm') die(`--type 不认识：${t}`);
        c.type = t;
        changed.push('type');
      }
      if (values.package) {
        c.package = validateNpmPackage(values.package);
        changed.push('package');
      }
      if (values.version) {
        c.version = values.version;
        changed.push('version');
      }
      if (values.name) {
        c.name = values.name;
        changed.push('name');
      }
      if (values.url) {
        c.url = validateUrl(values.url);
        changed.push('url');
      }
      const pubInput = values.pub || values.pubkey;
      if (pubInput) {
        const { b64, fp } = normalizePub(pubInput);
        c.pubkey = b64;
        changed.push('pubkey');
        process.stderr.write(`  新公钥指纹：${green(fp)}\n`);
      }
      if (values.priority !== '') {
        const p = Number(values.priority);
        if (!Number.isFinite(p)) die(`--priority 必须是数字，实际：${values.priority}`);
        c.priority = p;
        changed.push('priority');
      }
      if (values.enabled !== '') {
        c.enabled = !['false', '0', 'no'].includes(values.enabled.toLowerCase());
        changed.push('enabled');
      }
      if (!changed.length) {
        die('set 没有指定任何要修改的字段（--name/--url/--type/--package/--version/--pub/--priority/--enabled）');
      }
      const t = c.type || 'http';
      if (t === 'npm' && !c.package) die(`频道 ${c.id} 是 npm 类型但没有 package 字段`);
      if (t === 'http' && !c.url) die(`频道 ${c.id} 是 http 类型但没有 url 字段`);
      saveChannels(file, doc);
      ok(`已更新频道 ${c.id}：${changed.join(', ')}`);
      if (values.json) process.stdout.write(JSON.stringify(c, null, 2) + '\n');
      return;
    }

    case 'enable':
    case 'disable': {
      const c = findChannel(doc, values.id || positionals[0]);
      c.enabled = sub === 'enable';
      saveChannels(file, doc);
      ok(`频道 ${c.id} 已${c.enabled ? '启用' : '停用'}。`);
      if (values.json) process.stdout.write(JSON.stringify(c, null, 2) + '\n');
      return;
    }

    case 'check':
      return cmdCheck(doc, positionals, values);

    default:
      die(`未实现的子命令：${sub}`);
  }
}

main().catch((e) => die(`未预期错误：${e?.stack || e}`));
