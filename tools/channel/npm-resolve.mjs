#!/usr/bin/env node
/**
 * tools/channel/npm-resolve.mjs —— 解析「npm 形态」的频道 → 验签 → 落地 channel.json
 *
 * 这是**消费侧**命令：给设备侧更新流程（runtime/root/update.sh）或 App 用。
 * 流程（每一步失败都**拒绝该频道**，不降级）：
 *   1. 解析 registry（尊重 --registry / NPM_CONFIG_REGISTRY / .npmrc / npm config）
 *   2. 拉 packument → 按 npm spec（dist-tag / 确切版本 / 范围）选版本
 *   3. 下载 tarball → 校验 dist.integrity（SRI sha512）或 shasum（sha1）
 *   4. 只用 Node 内置 zlib 解出包内 `channel.json` + `channel.json.sig`（剥掉 `package/` 前缀）
 *   5. **用用户配置的公钥验签**（公钥绝不从包里读）
 *   6. 把验过的两份文件写到 --out 目录，并打印 JSON 摘要（stdout）
 *
 * 用法：
 *   node npm-resolve.mjs --package sunsetlinux-channel-dev --pub <base64|文件> [--version latest]
 *   node npm-resolve.mjs --channels /data/sunsetlinux/etc/channels.json --id dev
 *   node npm-resolve.mjs --channels channels.json --all-npm          # 所有启用的 npm 频道
 */

import fs from 'node:fs';
import path from 'node:path';
import {
  parseArgs, wantsHelp, printHelp, die, info, ok, warn,
  readJson, writeFileEnsured, compareVersions, bold, dim, green,
} from './common.mjs';
import {
  resolveNpmChannel, resolveRegistry, DEFAULT_MAX_PACKED_SIZE,
} from './npm-channel.mjs';

const HELP = `用法：node npm-resolve.mjs [选项]

解析一个「npm 形态」的频道（npm 只是传输渠道，**信任根仍是公钥验签**）。

指定频道（二选一）：
  --package <npm 包名>     npm 包名（可带 scope，如 @me/sunsetlinux-channel）
  --version <spec>         npm spec：dist-tag（latest/next）| 确切版本 | 范围（^1.2.0 / ~1.2 / 1.x / >=1.0.0）默认 latest
  --pub <文件|base64>      频道公钥（**必须由用户提供**，绝不从包里读）
  --channels <文件>        或者：从 channels.json 里取条目
  --id <频道 id>           配合 --channels：解析指定频道
  --all-npm                配合 --channels：解析其中所有启用的 npm 频道

其它：
  --registry <URL>         覆盖 registry（默认按 npm 的配置优先级：环境变量 → .npmrc → npm config）
  --out <目录>             channel.json / channel.json.sig 的输出目录（默认 ./npm-channel-<包名>）
  --cache <目录>           tarball 下载目录（默认用 --out 目录下的 .npm-cache）
  --keep-tarball           保留 tarball（排错用）
  --json                   只输出 JSON（进度走 stderr）
  --help                   显示本帮助

退出码：0 全部解析并验签通过；1 有任何一个频道失败（失败即拒绝，不用它的内容）。

安全说明：npm 包被替换/投毒/镜像被改，都**签不出**合法 channel.json；
但公钥若填错或被人换过，验签就失去意义 —— 请用带外渠道核对公钥指纹。`;

const spec = {
  package: { type: 'string', default: '' },
  version: { type: 'string', default: 'latest' },
  pub: { type: 'string', default: '' },
  channels: { type: 'string', default: '' },
  id: { type: 'string', default: '' },
  'all-npm': { type: 'boolean', default: false },
  registry: { type: 'string', default: '' },
  out: { type: 'string', default: '' },
  cache: { type: 'string', default: '' },
  'keep-tarball': { type: 'boolean', default: false },
  json: { type: 'boolean', default: false },
};

function outDirFor(entry) {
  const safe = String(entry.package || entry.id || 'channel').replace(/[@/]/g, '_');
  return path.resolve(`npm-channel-${safe}`);
}

async function resolveOne(entry, values) {
  const outDir = values.out ? path.resolve(values.out) : outDirFor(entry);
  const cacheDir = values.cache ? path.resolve(values.cache) : path.join(outDir, '.npm-cache');
  const r = await resolveNpmChannel(entry, {
    workDir: cacheDir,
    registry: values.registry || undefined,
    cmp: compareVersions,
    keepTarball: values['keep-tarball'],
    log: (m) => { if (!values.json) process.stderr.write(`${dim('  ' + m)}\n`); },
  });

  const manifestPath = path.join(outDir, 'channel.json');
  const sigPath = path.join(outDir, 'channel.json.sig');
  writeFileEnsured(manifestPath, r.manifestRaw);
  writeFileEnsured(sigPath, `${r.signature.toString('base64')}\n`);

  const layerSummary = (r.manifest.layers || []).map((l) => `${l.id}@${l.version}`);
  return {
    id: r.id,
    package: r.package,
    version: r.version,
    version_source: r.versionSource,
    registry: r.registry,
    registry_source: r.registrySource,
    tarball: r.tarball,
    tarball_bytes: r.tarballBytes,
    integrity: r.integrity?.ok === null ? 'metadata 未提供 integrity（已跳过）' : `${r.integrity.algo} 校验通过`,
    pubkey_fingerprint: r.pubkeyFingerprint,
    channel_name: r.manifest.name ?? null,
    dsh_npm: r.manifest.dsh_npm ?? null,
    layers: layerSummary,
    out_dir: outDir,
    manifest: manifestPath,
    signature: sigPath,
    // 层 URL 交给上层流程（update.sh / App）继续处理
    layer_urls: (r.manifest.layers || []).map((l) => ({
      id: l.id, version: l.version, url: l.url, url_gz: l.url_gz ?? null,
      sha256: l.sha256, size: l.size, sha256_raw: l.sha256_raw ?? null, size_raw: l.size_raw ?? null,
    })),
  };
}

async function main() {
  const argv = process.argv.slice(2);
  if (wantsHelp(argv)) return printHelp('sunsetlinux npm 频道解析（npm-resolve）', HELP);
  const { values } = parseArgs(argv, spec);

  let entries = [];
  if (values.channels) {
    const file = path.resolve(values.channels);
    const doc = readJson(file);
    const all = Array.isArray(doc.channels) ? doc.channels : [];
    const npmOnes = all.filter((c) => (c.type || 'http') === 'npm');
    if (values.id) {
      const e = all.find((c) => c.id === values.id);
      if (!e) die(`channels.json 里没有 id=${values.id} 的频道`);
      if ((e.type || 'http') !== 'npm') {
        die(`频道 ${e.id} 的类型是 ${e.type || 'http'}，不是 npm —— 请用 verify.mjs 校验 http 频道`);
      }
      entries = [e];
    } else if (values['all-npm']) {
      entries = npmOnes.filter((c) => c.enabled !== false);
      if (entries.length === 0) die('channels.json 里没有启用的 npm 频道');
    } else {
      die('用 --channels 时必须再给 --id <频道 id> 或 --all-npm');
    }
  } else {
    if (!values.package) die('缺少 --package <npm 包名>（或用 --channels + --id）');
    if (!values.pub) die('缺少 --pub <公钥>（公钥必须由用户提供，不能从包里读）');
    entries = [{ id: values.package, name: values.package, type: 'npm', package: values.package, version: values.version, pubkey: values.pub }];
  }

  if (!values.json) {
    info(`准备解析 ${entries.length} 个 npm 频道`);
    if (!values.registry) {
      const probe = resolveRegistry({ pkg: entries[0].package, cwd: process.cwd() });
      info(`registry：${probe.registry}（来源：${probe.source}）`);
    }
  }

  const results = [];
  let failed = 0;
  for (const e of entries) {
    if (!values.json) process.stderr.write(`\n${bold(`—— 频道 ${e.id}`)}（${e.package}@${e.version || 'latest'}）\n`);
    try {
      const r = await resolveOne(e, values);
      results.push(r);
      if (!values.json) {
        ok(`验签通过：${r.package}@${r.version}（${r.version_source}）`);
        process.stderr.write(
          `  公钥指纹 ${green(r.pubkey_fingerprint)}\n` +
          `  清单名   ${r.channel_name ?? '(未命名)'}\n` +
          `  层       ${r.layers.join(', ') || '(无)'}\n` +
          `  已写出   ${r.manifest}\n           ${r.signature}\n`,
        );
      }
    } catch (err) {
      failed++;
      results.push({ id: e.id, package: e.package, ok: false, error: String(err.message || err) });
      if (!values.json) {
        process.stderr.write(`  ${'\u001b[31m'}❌ ${err.message}${'\u001b[0m'}\n`);
      } else {
        process.stderr.write(`[错误] ${e.id}: ${err.message}\n`);
      }
    }
  }

  const payload = { schema: 1, ok: failed === 0, results };
  if (values.json) process.stdout.write(JSON.stringify(payload, null, 2) + '\n');
  else {
    process.stdout.write(JSON.stringify(payload, null, 2) + '\n');
    process.stderr.write('\n');
    if (failed === 0) ok(`全部 ${entries.length} 个 npm 频道解析并验签通过。`);
    else warn(`${failed}/${entries.length} 个 npm 频道失败 —— 已拒绝，不会使用其内容。`);
  }
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => die(`未预期错误：${e?.stack || e}`));
