#!/usr/bin/env node
/**
 * tools/channel/publish-channel.mjs —— 一条命令把「层产物」变成「可发布的频道」
 *
 * ## 为什么需要它
 *   发布一个频道本来要手工串四步，而且每步都有容易漏的细节：
 *     1. `gen-manifest`  → 生成 channel.json（要挑对版本、算真解压的 sha256_raw）
 *     2. `sign`          → 签 channel.json 的**原始字节**
 *     3. `verify`        → 验签 + 逐层校验
 *     4. `publish-check` → 发布前体检（要求层文件与清单**在同一个目录**里）
 *   第 4 步是最常踩的：层产物在 `dist/`，清单一生成也在 `dist/`，一旦你把清单挪到
 *   别处，`publish-check` 就会因为"找不到层文件"失败 —— 于是有人干脆跳过体检直接上传，
 *   把一个**验签/哈希对不上**的频道发出去。本脚本把"整理发布目录"这件事固化下来：
 *   层文件用**硬链接**放进发布目录（不占额外空间），清单就地生成、就地签、就地体检。
 *
 * ## 用法
 *   node tools/channel/publish-channel.mjs \
 *     --base-url https://cdn.example.com/sunsetlinux \
 *     --key ~/.sunsetlinux-keys/channel.key \
 *     --pub ~/.sunsetlinux-keys/channel.pub \
 *     [--dir dist] [--out dist/channel] [--name "SunsetLinux 官方"] \
 *     [--dsh-dist-tag next] [--offline] [--no-strict] [--dry-run]
 *
 * ## 产物（`--out` 目录里就是**要上传的全部文件**）
 *   channel.json            清单（App 拉这个）
 *   channel.json.sig        对清单原始字节的 Ed25519 签名
 *   <层>-<版本>.erofs.zst   App 走这条（体积最小）
 *   <层>-<版本>.erofs.gz    设备侧纯 CLI 走这条（设备上没有 zstd）
 *
 * ⚠️ 上传后**不要再改** channel.json（一个空格就会让签名失效）。
 * ⚠️ 裸镜像（`.erofs`）不必上传；`sha256_raw`/`size_raw` 由解压复算得出。
 * ⚠️ 私钥**绝不**上传、绝不提交；只放在你自己的机器与 CI Secret 里。
 */

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, linkSync, copyFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  bold, cyan, dim, die, formatBytes, green, info, loadPublicKey, ok,
  publicKeyFingerprint, warn,
} from './common.mjs';

const SELF_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(SELF_DIR, '..', '..');

function usage() {
  // 直接把文件头的文档注释当作 --help 输出（单一事实源，避免帮助与实现漂移）
  const text = execFileSync('sed', ['-n', '2,32p', fileURLToPath(import.meta.url)], { encoding: 'utf8' });
  process.stdout.write(
    text.replace(/^\/\*\*?\s?/, '').replace(/^ \*\/?\s?/gm, '').replace(/^ \*\/$/gm, ''),
  );
}

const args = process.argv.slice(2);
const opt = {
  dir: path.join(REPO, 'dist'),
  out: null,
  key: path.join(process.env.HOME || '.', '.sunsetlinux-keys', 'channel.key'),
  pub: path.join(process.env.HOME || '.', '.sunsetlinux-keys', 'channel.pub'),
  baseUrl: null,
  name: 'SunsetLinux 官方',
  dshDistTag: null,
  offline: false,
  strict: true,
  dryRun: false,
};
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  const next = () => args[++i] ?? die(`${a} 需要一个值`);
  switch (a) {
    case '--dir': opt.dir = path.resolve(next()); break;
    case '--out': opt.out = path.resolve(next()); break;
    case '--key': opt.key = path.resolve(next()); break;
    case '--pub': opt.pub = path.resolve(next()); break;
    case '--base-url': opt.baseUrl = next().replace(/\/+$/, ''); break;
    case '--name': opt.name = next(); break;
    case '--dsh-dist-tag': opt.dshDistTag = next(); break;
    case '--offline': opt.offline = true; break;
    case '--no-strict': opt.strict = false; break;
    case '--dry-run': opt.dryRun = true; break;
    case '-h': case '--help': usage(); process.exit(0); break;
    default: die(`不认识的参数：${a}（用 --help 看用法）`);
  }
}
if (!opt.out) opt.out = path.join(opt.dir, 'channel');
if (!opt.baseUrl) die('必须给 --base-url <层文件的下载前缀>（上传后用户实际访问的前缀，不要带结尾斜杠）');

// ───────────────────────── 1) 前置检查（失败要早、原因要具体）

info(`层产物目录：${opt.dir}`);
info(`发布目录  ：${opt.out}`);
info(`下载前缀  ：${opt.baseUrl}`);
info(`频道名    ：${opt.name}`);

if (!existsSync(opt.dir)) die(`层产物目录不存在：${opt.dir}`);
for (const [f, what] of [[opt.key, '频道私钥'], [opt.pub, '频道公钥']]) {
  if (!existsSync(f)) {
    die(`缺少${what}：${f}\n` +
        `       先生成一次：node tools/channel/keygen.mjs --out-dir ${path.dirname(opt.key)}`);
  }
}

// 私钥权限：必须是 0600（Windows/挂载盘上可能拿不到 mode，故只告警不失败）
const keyMode = statSync(opt.key).mode & 0o777;
if (keyMode !== 0o600) {
  warn(`私钥权限是 ${keyMode.toString(8)}，建议 chmod 600 ${opt.key}（同机其它用户不该读得到）`);
}

let fingerprint = '';
try {
  fingerprint = publicKeyFingerprint(loadPublicKey(opt.pub));
} catch (e) {
  die(`公钥读不了（${opt.pub}）：${e.message}`);
}

// 层产物：硬链进发布目录的内容 = 两种分发产物（裸镜像不上传）
const layers = readdirSync(opt.dir)
  .filter((n) => /\.erofs(\.zst|\.gz)$/.test(n))
  .sort();
if (layers.length === 0) {
  die(`${opt.dir} 里没有 .erofs.zst / .erofs.gz 层产物。\n` +
      `       先构建层：bash rootfs/build-layers.sh --out-dir ${opt.dir} …（需要 arm64 + 真 chroot）`);
}
// 三层都要有主产物，否则 App 更新会缺层 —— 这里提前拦，别等 user 端报错
const have = (id) => layers.some((n) => n.startsWith(`${id}-`));
const missing = ['base', 'runtime', 'dsh'].filter((id) => !have(id));
if (missing.length) die(`缺少这些层的分发产物：${missing.join(', ')}（在 ${opt.dir} 里没找到）`);

info(`将发布 ${layers.length} 个层文件：`);
for (const n of layers) {
  info(`  ${n.padEnd(34)} ${formatBytes(statSync(path.join(opt.dir, n)).size)}`);
}

if (opt.dryRun) {
  info('--dry-run：到此为止，未生成任何文件。');
  process.exit(0);
}

// ───────────────────────── 2) 整理发布目录（硬链接，不复制 244 MB）

mkdirSync(opt.out, { recursive: true });
// 清单与签名每次重生成：删掉旧的，避免"签的是上一版"这种事故
for (const stale of ['channel.json', 'channel.json.sig']) {
  const p = path.join(opt.out, stale);
  if (existsSync(p)) rmSync(p);
}
for (const n of layers) {
  const dst = path.join(opt.out, n);
  if (existsSync(dst)) continue;
  try {
    linkSync(path.join(opt.dir, n), dst);      // 同一文件系统：硬链接，零额外空间
  } catch {
    copyFileSync(path.join(opt.dir, n), dst);  // 跨文件系统（如 /tmp 与 repo 不同盘）：退回复制
    warn(`${n} 跨文件系统，已改为复制（多占 ${formatBytes(statSync(dst).size)}）`);
  }
}

const run = (script, argv, what) => {
  info(`→ ${what}`);
  try {
    execFileSync(process.execPath, [path.join(SELF_DIR, script), ...argv], { stdio: 'inherit' });
  } catch (e) {
    die(`${what} 失败（退出码 ${e.status ?? '?'}）—— 发布中止，**不要上传**这个目录`);
  }
};

// ───────────────────────── 3) 生成清单 → 签名 → 验签

const manifest = path.join(opt.out, 'channel.json');
const genArgs = ['--dir', opt.out, '--out', manifest, '--name', opt.name, '--base-url', opt.baseUrl];
if (opt.dshDistTag) genArgs.push('--dsh-dist-tag', opt.dshDistTag);
if (opt.offline) genArgs.push('--offline');
if (opt.strict) genArgs.push('--strict');
run('gen-manifest.mjs', genArgs, '生成 channel.json（会真解压复算 sha256_raw）');

run('sign.mjs', ['--in', manifest, '--key', opt.key], '签名 channel.json → channel.json.sig');
run('verify.mjs', ['--strict', '--pub', opt.pub, '--in', manifest, '--dir', opt.out],
    '独立验签 + 逐层校验（含解压复算）');

// ───────────────────────── 4) 打印"怎么发布 / 怎么发给用户"

const files = readdirSync(opt.out).filter((n) => !n.startsWith('.')).sort();
let total = 0;
for (const n of files) total += statSync(path.join(opt.out, n)).size;

const channelUrl = `${opt.baseUrl}/channel.json`;
console.log('');
console.log(bold(green('✅ 频道已就绪，可以发布了')));
console.log('');
console.log(`${bold('① 上传')}（把 ${cyan(opt.out)} 里的**全部**文件传到 ${cyan(opt.baseUrl)}）：`);
for (const n of files) {
  console.log(`     ${n.padEnd(34)} ${formatBytes(statSync(path.join(opt.out, n)).size)}`);
}
console.log(`     ${dim('—'.repeat(34))}`);
console.log(`     ${String(files.length).padEnd(34)} ${formatBytes(total)}`);
console.log(`   ${dim('裸镜像（.erofs）不必上传；channel.json 上传后一个字节都不要再改。')}`);
console.log('');
console.log(`${bold('② 把这两样发给用户')}（走**另一个**渠道，公钥是信任根）：`);
console.log(`     URL    : ${cyan(channelUrl)}`);
console.log(`     公钥   : ${cyan(readFileSync(opt.pub, 'utf8').trim())}`);
console.log(`     指纹   : ${green(fingerprint)}   ${dim('← 让用户核对，防中间人替换')}`);
console.log('');
console.log(`${bold('③ 用户侧/App 侧订阅')}：`);
console.log(`     sunsetlinux-channel channels add --id official --name "${opt.name}" \\`);
console.log(`         --url ${channelUrl} --pub '<上面的公钥>' --priority 10`);
console.log(`     ${dim('App：设置 → 频道管理 → 添加「URL + 公钥」；指纹要与 ② 一致。')}`);
console.log('');
