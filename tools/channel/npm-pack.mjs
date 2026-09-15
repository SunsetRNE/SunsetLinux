#!/usr/bin/env node
/**
 * tools/channel/npm-pack.mjs —— 把频道目录打成**符合 npm 规范**的包（发布侧）
 *
 * 默认**只打包，不发布**（`npm pack`）。真要上传必须显式加 `--publish`。
 *
 * ## 包内放什么（方案选择，实测依据见 docs/updates.md）
 *   默认（方案 1，推荐）：只放 `package.json` + `README.md` + `channel.json` + `channel.json.sig`
 *     —— 几 KB。层文件 URL 指向别处（GitHub Releases / 对象存储 / 自建 HTTP）。
 *     理由：我们的三层两式产物合计约 244 MB；npm CLI 本地不限体积，但 **registry 服务端**
 *     会对大包返回 `413 Payload Too Large`（各家镜像阈值还不一样）。把大层塞进 npm 包，
 *     等于把"用户能不能装上"交给镜像的未公开阈值。
 *   `--with-layers`（方案 2）：把小层打进包内，做到"一个包名搞定"。
 *     工具会按 `--max-packed-size`（默认 50 MB）拒绝超限的包，并提示改用方案 1。
 *
 * ## 用法
 *   node npm-pack.mjs --dir dist/layers-20260915 --name sunsetlinux-channel-dev
 *   node npm-pack.mjs --dir dist/layers --name @me/sunsetlinux-channel --version 1.0.0 --publish --tag next
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';
import {
  parseArgs, wantsHelp, printHelp, die, info, ok, warn,
  readJson, writeFileEnsured, loadPublicKey, publicKeyFingerprint, keyObjectToRawPub,
  formatBytes, sha256File, bold, dim, green,
} from './common.mjs';
import { readNpmTarball, DEFAULT_MAX_PACKED_SIZE } from './npm-channel.mjs';

const HELP = `用法：node npm-pack.mjs [选项]

把一个「频道目录」（含 channel.json + channel.json.sig）打成 npm 包。
**默认只 pack，不 publish**；要真发必须显式 --publish。

必需：
  --dir <目录>            频道目录（要有 channel.json 与 channel.json.sig）
  --name <npm 包名>       npm 包名（可带 scope，全小写，如 sunsetlinux-channel-dev / @me/sunsetlinux-channel-dev）

常用：
  --version <ver>         npm 包版本（默认：取清单里 dsh 层的版本；都没有则 1.0.0）
  --pub <文件|base64>     频道公钥（写进包内 README 供人类核对；**不会**写进清单信任链）
  --out-dir <目录>        tgz 输出目录（默认：当前目录）
  --with-layers           把层文件也打进包内（仅适合小层；默认只放清单+签名）
  --max-packed-size <大小> --with-layers 时的上限，如 50MB / 20MiB（默认 50MB）
  --description <文字>    包描述
  --publish               真的执行 npm publish（不给就只 pack 出 tgz）
  --tag <dist-tag>        --publish 时的 npm dist-tag（如 next；默认 latest）
  --registry <URL>        --publish 用的 registry（默认走 npm 自己的配置/镜像）
  --dry-run               --publish 前先跑一次 npm publish --dry-run 看结果
  --json                  结果以 JSON 打到 stdout
  --help                  显示本帮助

产物：
  <out-dir>/<包名>-<版本>.tgz    可直接 \`npm publish\` 的包
  <out-dir>/<包名>-<版本>.tgz.sha256

发布后把这三样发给用户：**包名 + 公钥（base64）+ 公钥指纹**。`;

const spec = {
  dir: { type: 'string', default: '' },
  name: { type: 'string', default: '' },
  version: { type: 'string', default: '' },
  pub: { type: 'string', default: '' },
  'out-dir': { type: 'string', default: '' },
  'with-layers': { type: 'boolean', default: false },
  'max-packed-size': { type: 'string', default: '' },
  description: { type: 'string', default: '' },
  publish: { type: 'boolean', default: false },
  tag: { type: 'string', default: 'latest' },
  registry: { type: 'string', default: '' },
  'dry-run': { type: 'boolean', default: false },
  json: { type: 'boolean', default: false },
};

function parseSize(text, dflt) {
  if (!text) return dflt;
  const m = /^(\d+(?:\.\d+)?)\s*(B|KB|MB|GB|KiB|MiB|GiB)?$/i.exec(String(text).trim());
  if (!m) die(`看不懂的大小：${text}（例：50MB / 20MiB）`);
  const n = Number(m[1]);
  const unit = (m[2] || 'B').toLowerCase();
  const mul = { b: 1, kb: 1000, mb: 1000 ** 2, gb: 1000 ** 3, kib: 1024, mib: 1024 ** 2, gib: 1024 ** 3 }[unit];
  return Math.round(n * mul);
}

function validateNpmName(name) {
  if (!name) die('缺少 --name <npm 包名>');
  if (name !== name.toLowerCase()) die(`npm 包名必须全小写：${name}`);
  if (name.startsWith('.') || name.startsWith('_')) die(`npm 包名不能以 . 或 _ 开头：${name}`);
  if (!/^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/.test(name)) {
    die(`npm 包名不合法：${name}（可用小写字母/数字/-/._，作用域形如 @scope/name）`);
  }
  if (name.length > 214) die('npm 包名过长（>214 字符）');
  return name;
}

function pickVersion(values, manifest) {
  if (values.version) return values.version;
  const dsh = (manifest.layers || []).find((l) => l.id === 'dsh');
  if (dsh?.version) return dsh.version;
  return '1.0.0';
}

async function main() {
  const argv = process.argv.slice(2);
  if (wantsHelp(argv)) return printHelp('sunsetlinux npm 频道打包（npm-pack）', HELP);
  const { values } = parseArgs(argv, spec);

  if (!values.dir) die('缺少 --dir <频道目录>');
  const dir = path.resolve(values.dir);
  if (!fs.existsSync(dir)) die(`目录不存在：${dir}`);
  const manifestPath = path.join(dir, 'channel.json');
  const sigPath = path.join(dir, 'channel.json.sig');
  if (!fs.existsSync(manifestPath)) die(`目录里没有 channel.json：${dir}（先跑 gen-manifest）`);
  if (!fs.existsSync(sigPath)) die(`目录里没有 channel.json.sig：${dir}（先跑 sign）`);

  const name = validateNpmName(values.name);
  const manifest = readJson(manifestPath);
  const version = pickVersion(values, manifest);
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version)) {
    die(`npm 包版本必须是语义版本（形如 1.0.0 / 0.1.5-rc.2），实际：${version}（可用 --version 指定）`);
  }
  const outDir = path.resolve(values['out-dir'] || '.');
  fs.mkdirSync(outDir, { recursive: true });
  const maxPacked = parseSize(values['max-packed-size'], DEFAULT_MAX_PACKED_SIZE);

  // ---- 1. 准备 npm 包目录 -------------------------------------------------
  const stage = fs.mkdtempSync(path.join(os.tmpdir(), 'sunsetlinux-npm-pack.'));
  const pkgDir = path.join(stage, 'package');
  fs.mkdirSync(pkgDir, { recursive: true });
  fs.copyFileSync(manifestPath, path.join(pkgDir, 'channel.json'));
  fs.copyFileSync(sigPath, path.join(pkgDir, 'channel.json.sig'));

  // 公钥只用于"写进 README 供人类核对"，**不进信任链**（信任链永远是用户那边的 channels.json）
  let pubB64 = null;
  let fingerprint = null;
  if (values.pub) {
    const key = loadPublicKey(values.pub, '公钥');
    pubB64 = keyObjectToRawPub(key).toString('base64');
    fingerprint = publicKeyFingerprint(key);
  }

  const included = ['channel.json', 'channel.json.sig'];
  let layersTotal = 0;
  if (values['with-layers']) {
    for (const l of manifest.layers || []) {
      for (const f of [l.url, l.url_gz]) {
        if (!f) continue;
        const base = /^[a-z][a-z0-9+.-]*:\/\//i.test(f) ? path.basename(new URL(f).pathname) : f;
        const src = path.join(dir, decodeURIComponent(base));
        if (!fs.existsSync(src)) {
          warn(`清单里的层文件不在本地，跳过：${base}`);
          continue;
        }
        fs.copyFileSync(src, path.join(pkgDir, base));
        included.push(base);
        layersTotal += fs.statSync(src).size;
      }
    }
    if (layersTotal > maxPacked) {
      die(
        `--with-layers 会把 ${formatBytes(layersTotal)} 的层打进 npm 包，超过上限 ${formatBytes(maxPacked)}。\n` +
        '  npm registry / 各家镜像对大包会返回 413 Payload Too Large，阈值还不公开、各不相同。\n' +
        '  推荐做法（方案 1）：**只发布清单本身**（去掉 --with-layers），把层文件放到\n' +
        '  GitHub Releases / 对象存储 / 自建 HTTP，清单里的 url 用 --base-url 指过去。\n' +
        `  确实要塞小层，可显式提高上限：--max-packed-size ${Math.ceil(layersTotal / 1048576) + 5}MiB`,
      );
    }
  }

  // ---- 2. package.json（含"这是 sunsetlinux 频道包"的标记）-------------------
  const pkgJson = {
    name,
    version,
    description: values.description || `sunsetlinux 频道包：${manifest.name || name}`,
    keywords: ['sunsetlinux', 'sunsetlinux-channel', 'dsh'],
    license: 'MIT',
    files: included,
    sunsetlinux: {
      channel: 1,
      // 便于别人（与人）识别：包里是 sunsetlinux 的 channel.json + 签名
      manifest: 'channel.json',
      signature: 'channel.json.sig',
      layers_inside_package: Boolean(values['with-layers']),
      ...(fingerprint ? { pubkey_fingerprint: fingerprint } : {}),
    },
  };
  writeFileEnsured(path.join(pkgDir, 'package.json'), JSON.stringify(pkgJson, null, 2) + '\n');

  const layerLines = (manifest.layers || [])
    .map((l) => `| \`${l.id}\` | ${l.version} | ${formatBytes(l.size)} | \`${l.url}\` |${values['with-layers'] ? ' 包内 |' : ' 外部托管 |'}`)
    .join('\n');
  writeFileEnsured(
    path.join(pkgDir, 'README.md'),
    `# ${name}

这是一个 **sunsetlinux 频道包**（sunsetlinux channel package）。它只包含频道的**清单与签名**：

- \`channel.json\` —— 频道清单（各层版本 / sha256 / 下载地址）
- \`channel.json.sig\` —— 用发布者私钥做的 Ed25519 签名

## 怎么订阅

在 sunsetlinux 的 \`channels.json\` 里加入（**公钥必须由发布者通过带外渠道给你，并从包里读不到**）：

\`\`\`json
{
  "id": "${name.replace(/^@[^/]+\//, '')}",
  "name": "${manifest.name || name}",
  "type": "npm",
  "package": "${name}",
  "version": "${values.tag || 'latest'}",
  "pubkey": "${pubB64 || '<发布者给你的 Ed25519 公钥 base64>'}",
  "enabled": true,
  "priority": 50
}
\`\`\`

或命令行：

\`\`\`bash
sunsetlinux-channel channels add --id ${name.replace(/^@[^/]+\//, '')} --type npm \\
    --package ${name} --version ${values.tag || 'latest'} --pub '<公钥>'
sunsetlinux-channel channels check --id ${name.replace(/^[^/]+\//, '')}
\`\`\`

${fingerprint ? `**公钥指纹**（请与发布者核对）：\`${fingerprint}\`\n` : ''}
## 层

| 层 | 版本 | 下载大小 | 地址 |
|---|---|---|---|
${layerLines || '| （无） | | | |'}

${values['with-layers'] ? '> 层文件已打进本 npm 包内。' : '> 层文件托管在包外（GitHub Releases / 对象存储 / 自建 HTTP）。'}

## 安全模型（重要）

**npm 不是信任来源。** 包可能被替换、被投毒、镜像可能被改 —— 都无所谓：
\`channel.json.sig\` 必须能用发布者公钥验过，否则 sunsetlinux 会**直接拒绝**这个频道。
换句话说，能通过校验的只有**持有私钥的人**发布的内容。

注意：\`${fingerprint ? '上面这个' : '发布者的'}\`指纹只是**供人核对**的提示；
真正生效的公钥来自订阅者自己填进 \`channels.json\` 的那一份 —— 请通过带外渠道核对。
`,
  );

  // ---- 3. npm pack --------------------------------------------------------
  info(`打 npm 包：${name}@${version}（${included.length} 个文件${values['with-layers'] ? `，含层 ${formatBytes(layersTotal)}` : ''}）`);
  let packOut;
  try {
    packOut = execFileSync('npm', ['pack', '--pack-destination', outDir, '--json'], {
      cwd: pkgDir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 300000,
    });
  } catch (e) {
    die(`npm pack 失败：${e.stderr?.toString?.() || e.message}\n（宿主要装 npm；本命令只打包，不会上传）`);
  }
  const packed = JSON.parse(packOut)[0];
  const tgz = path.join(outDir, packed.filename);
  if (!fs.existsSync(tgz)) die(`npm pack 说生成了 ${packed.filename}，但找不到它`);
  const tgzSize = fs.statSync(tgz).size;

  // 回读校验：确认包内确实有 channel.json + .sig（且是 npm 规范的 package/ 前缀）
  const back = await readNpmTarball(tgz, () => true);
  for (const need of ['channel.json', 'channel.json.sig', 'package.json']) {
    if (!back.has(need)) die(`打出来的包里没有 ${need}（npm 包结构不对）`);
  }

  // 体积闸门：即使 --with-layers 关了，也把实际 tgz 大小报出来
  if (tgzSize > maxPacked) {
    die(
      `打出来的包 ${formatBytes(tgzSize)} 超过上限 ${formatBytes(maxPacked)}：\n` +
      '  多数 registry / 镜像会以 413 拒绝。请去掉 --with-layers（只发布清单），\n' +
      '  或显式放宽 --max-packed-size（风险自负）。',
    );
  }

  const tgzSha = await sha256File(tgz);
  writeFileEnsured(`${tgz}.sha256`, `${tgzSha}  ${path.basename(tgz)}\n`);

  ok(`已生成 ${path.basename(tgz)}（${formatBytes(tgzSize)}，解包后 ${formatBytes(packed.unpackedSize)}）`);
  process.stderr.write(`  ${dim('sha256：')}${tgzSha}\n`);
  for (const f of included) process.stderr.write(`  ${dim('包含：')}${f}\n`);

  // ---- 4. 可选发布 -------------------------------------------------------
  let published = false;
  if (values.publish) {
    const args = ['publish', tgz, '--tag', values.tag];
    if (values.registry) args.push('--registry', values.registry);
    if (values['dry-run']) args.push('--dry-run');
    info(`执行 npm ${args.join(' ')}`);
    try {
      const out = execFileSync('npm', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 600000 });
      if (out.trim()) process.stderr.write(out.replace(/^/gm, '  '));
      published = !values['dry-run'];
      ok(values['dry-run'] ? 'npm publish --dry-run 通过（未真正上传）' : `已发布 ${name}@${version}（tag=${values.tag}）`);
    } catch (e) {
      const msg = `${e.stdout?.toString?.() || ''}${e.stderr?.toString?.() || ''}` || e.message;
      die(`npm publish 失败：\n${msg}\n（检查是否已登录：npm whoami；registry 是否为你要发的那个）`);
    }
  } else {
    info('未加 --publish，只打包不上传。确认无误后执行：');
    process.stderr.write(`  npm publish ${path.relative(process.cwd(), tgz) || tgz} --tag ${values.tag}\n`);
    process.stderr.write(`  或重跑本命令并加 --publish\n`);
  }

  fs.rmSync(stage, { recursive: true, force: true });

  const summary = {
    ok: true,
    package: name,
    version,
    tgz,
    tgz_bytes: tgzSize,
    tgz_sha256: tgzSha,
    unpacked_bytes: packed.unpackedSize,
    with_layers: Boolean(values['with-layers']),
    layers_bytes: layersTotal,
    pubkey: pubB64,
    pubkey_fingerprint: fingerprint,
    max_packed_size: maxPacked,
    published,
    next: `把「包名 ${name} + 公钥 + 指纹」发给用户；用户用 channels add --type npm 订阅`,
  };
  if (values.json) process.stdout.write(JSON.stringify(summary, null, 2) + '\n');
  else process.stdout.write(`${tgz}\n`);
}

main().catch((e) => die(`未预期错误：${e?.stack || e}`));
