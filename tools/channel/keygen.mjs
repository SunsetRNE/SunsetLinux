#!/usr/bin/env node
/**
 * tools/channel/keygen.mjs —— 生成 Ed25519 频道密钥对
 *
 * 产出：
 *   channel.key   私钥，PKCS#8 PEM，权限 0600（**绝不可外传**）
 *   channel.pub   公钥，raw 32 字节的 base64（写进 channels.json 的 "pubkey"）
 *
 * 设计要点（architecture.md §5.1）：
 *   - 「频道 = 一个 URL + 一个公钥」。任何人自己 keygen 就能成为更新源，
 *     不需要任何中心审核 —— 这是"拿不到第三方内测"的正面解法。
 *   - 私钥用来签 channel.json；公钥分发给别人用来验签。
 *
 * 用法：
 *   node keygen.mjs [--out-dir <目录>] [--force] [--label <备注>]
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {
  parseArgs, wantsHelp, printHelp, die, info, ok, warn,
  keyObjectToRawPub, publicKeyFingerprint, writeFileEnsured, bold, dim, green,
} from './common.mjs';

const HELP = `用法：node keygen.mjs [选项]

生成一对 Ed25519 频道签名密钥。

选项：
  --out-dir <目录>   密钥输出目录（默认：当前目录）
  --force            目标文件已存在时覆盖（默认拒绝，防手滑毁掉旧私钥）
  --label <备注>     写进 channel.pub 旁边的 channel.key.label，仅作人类识别
  --help             显示本帮助

产出：
  channel.key        私钥（PKCS#8 PEM，chmod 0600）—— 只留在你自己的机器上
  channel.pub        公钥（Ed25519 raw 32 字节的 base64）—— 发给你频道用户

安全提示：
  私钥泄露 = 任何人都能冒充你发布层文件。请离线备份，不要提交进 git，
  不要贴进聊天窗口。若已泄露：立刻 keygen 重新出密钥，把新公钥通知用户换频道。`;

const spec = {
  'out-dir': { type: 'string', default: '.' },
  force: { type: 'boolean', default: false },
  label: { type: 'string', default: '' },
};

async function main() {
  const argv = process.argv.slice(2);
  if (wantsHelp(argv)) return printHelp('sunsetlinux 频道密钥生成（keygen）', HELP);

  const { values } = parseArgs(argv, spec);
  const outDir = path.resolve(values['out-dir']);
  const keyFile = path.join(outDir, 'channel.key');
  const pubFile = path.join(outDir, 'channel.pub');

  if (!values.force) {
    for (const f of [keyFile, pubFile]) {
      if (fs.existsSync(f)) {
        die(`目标已存在：${f}\n拒绝覆盖（避免毁掉旧私钥）。确认要换密钥请加 --force。`);
      }
    }
  }

  info('生成 Ed25519 密钥对…');
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ed25519');
  const privPem = privateKey.export({ type: 'pkcs8', format: 'pem' });
  const rawPub = keyObjectToRawPub(publicKey);
  const pubB64 = rawPub.toString('base64');

  // 私钥必须 0600：同机其他用户读不到。
  writeFileEnsured(keyFile, privPem, { mode: 0o600 });
  fs.chmodSync(keyFile, 0o600); // 明确再设一次，避免 umask 干扰
  writeFileEnsured(pubFile, `${pubB64}\n`, { mode: 0o644 });
  if (values.label) {
    writeFileEnsured(path.join(outDir, 'channel.key.label'), `${values.label}\n`, { mode: 0o644 });
  }

  const fp = publicKeyFingerprint(publicKey);
  ok('密钥对已生成。');
  process.stdout.write(
    [
      `${bold('私钥')}  ${keyFile}   ${dim(`(${fs.statSync(keyFile).size} 字节, 权限 0600)`)}`,
      `${bold('公钥')}  ${pubFile}   ${dim('(base64, 32 字节 raw)')}`,
      `${bold('指纹')}  ${green(fp)}   ${dim('← 让用户用这个核对公钥，防止中间人替换')}`,
      '',
      `${bold('下一步')}`,
      `  1. 构建层文件 → node gen-manifest.mjs --dir <层目录> --name "我的频道" --base-url <托管前缀>`,
      `  2. node sign.mjs --in <层目录>/channel.json --key ${path.relative(process.cwd(), keyFile) || 'channel.key'}`,
      `  3. 把 channel.json / channel.json.sig / *.erofs.zst 传到静态托管，`,
      `     把「channel.json 的 URL + ${path.basename(pubFile)} 的内容 + 指纹」发给用户。`,
      '',
      `⚠ 不要外传 ${path.basename(keyFile)}：拿到它的人可以冒充你发布层文件。`,
    ].join('\n') + '\n',
  );
}

main().catch((e) => die(`未预期错误：${e?.stack || e}`));
