#!/usr/bin/env node
/**
 * tools/channel/sign.mjs —— 用 Ed25519 私钥对 channel.json 的**原始字节**签名
 *
 * 产出 channel.json.sig：签名（raw 64 字节）的 base64，加一个换行。
 *
 * 关键约定（architecture.md §5.1）：
 *   - 签的是文件字节本身，**不做任何 JSON 规范化/重排序**。
 *     因此任何"格式化一下 channel.json"的操作都会让签名失效 ——
 *     这是刻意的：验签端拿到的字节必须与签的字节完全一致，
 *     不存在"两种不同字节序列被认为是同一份清单"的解释空间。
 *   - 算法 Ed25519（Node 24 原生支持，无第三方库）。
 *
 * 用法：
 *   node sign.mjs --key channel.key [--in channel.json] [--out channel.json.sig]
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {
  parseArgs, wantsHelp, printHelp, die, info, ok, warn,
  loadPrivateKey, keyObjectToRawPub, publicKeyFingerprint, writeFileEnsured,
  sha256File, mustExist, bold, dim, green,
} from './common.mjs';

const HELP = `用法：node sign.mjs [选项]

用 Ed25519 私钥对 channel.json 的原始字节签名，生成 channel.json.sig。

选项：
  --key <文件>      私钥文件（PKCS#8 PEM，keygen.mjs 产出的 channel.key）【必需】
  --in <文件>      要签名的清单（默认：channel.json）
  --out <文件>     签名输出（默认：<输入>.sig，即 channel.json.sig）
  --check          签名后立即用对应公钥自验一次（默认开启，--no-check 关闭）
  --no-check       跳过自验
  --help           显示本帮助

输出：
  stdout 打印签名文件路径（一行）；摘要走 stderr。

注意：
  - 签名对象是 channel.json 的原始字节。签完不要再改动/格式化该文件。
  - channel.json.sig 与 channel.json 必须一起发布，缺一即视为无效频道。`;

const spec = {
  key: { type: 'string', default: '' },
  in: { type: 'string', default: 'channel.json' },
  out: { type: 'string', default: '' },
  check: { type: 'boolean', default: true },
  'no-check': { type: 'boolean', default: false },
};

async function main() {
  const argv = process.argv.slice(2);
  if (wantsHelp(argv)) return printHelp('dshroid 频道签名（sign）', HELP);
  const { values } = parseArgs(argv, spec);

  if (!values.key) die('缺少 --key <私钥文件>（keygen.mjs 产出的 channel.key）');
  const keyFile = path.resolve(values.key);
  const inFile = path.resolve(values.in);
  const outFile = values.out ? path.resolve(values.out) : `${inFile}.sig`;

  mustExist(keyFile, '私钥文件');
  mustExist(inFile, '待签名清单');

  // 权限体检：私钥不该是组/他人可读的（不阻断，但要吼一声）。
  const mode = fs.statSync(keyFile).mode & 0o777;
  if (mode & 0o077) {
    warn(`私钥权限过宽（${mode.toString(8)}），建议：chmod 600 ${values.key}`);
  }

  const privateKey = loadPrivateKey(keyFile);
  const raw = fs.readFileSync(inFile);

  // Ed25519：algorithm 传 null，Node 不接受 'sha256' 之类的摘要名。
  const sig = crypto.sign(null, raw, privateKey);
  if (sig.length !== 64) die(`内部错误：Ed25519 签名应为 64 字节，实际 ${sig.length}`);

  if (values.check && !values['no-check']) {
    const pub = crypto.createPublicKey(privateKey);
    if (!crypto.verify(null, raw, pub, sig)) {
      die('自验失败：刚生成的签名无法通过校验，私钥文件可能损坏。已放弃写出签名。');
    }
    info('自验通过：签名可被对应公钥验证。');
  }

  const body = `${sig.toString('base64')}\n`;
  writeFileEnsured(outFile, body, { mode: 0o644 });

  const pubObj = crypto.createPublicKey(privateKey);
  const pubB64 = keyObjectToRawPub(pubObj).toString('base64');
  const fp = publicKeyFingerprint(pubObj);
  const fileSha = await sha256File(inFile);

  ok(`已签名：${outFile}`);
  process.stderr.write(
    [
      `  ${bold('被签文件')}  ${inFile}`,
      `  ${bold('文件 sha256')} ${green(fileSha)}`,
      `  ${bold('签名长度')}  ${sig.length} 字节（base64 后 ${body.trim().length} 字符）`,
      `  ${bold('对应公钥')}  ${dim(fp)}`,
      '',
      `  发出去的公钥（应原样写进用户 channels.json 的 "pubkey"）：`,
      `    ${pubB64}`,
    ].join('\n') + '\n',
  );
  process.stdout.write(`${outFile}\n`);
}

main().catch((e) => die(`未预期错误：${e?.stack || e}`));
