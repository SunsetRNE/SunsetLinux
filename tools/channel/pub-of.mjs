#!/usr/bin/env node
/**
 * tools/channel/pub-of.mjs —— 从**私钥**反推公钥与指纹
 *
 * ## 为什么需要它（一个很容易混的点）
 *   频道体系里有**两个东西**，名字都带 "key"，但一个是秘密、一个是公开的：
 *
 *     channel.key   私钥（PKCS#8 PEM, 0600）  →  只放进 GitHub Secret `CHANNEL_SIGNING_KEY`
 *                                              ＋ 你自己的离线备份。**永不提交、永不上传。**
 *     channel.pub   公钥（raw 32 字节 base64）→  **要提交进仓库**（`channel` 分支的
 *                                              `channel/channel.pub`），并随 URL 一起发给用户。
 *
 *   问题在于：一旦你把私钥塞进 CI Secret（网页上是**只写**的，再也读不出来），
 *   或者只备份了 `channel.key` 而丢了 `channel.pub`，就会问："公钥在哪？"
 *   答案：**公钥可以从私钥推导出来**，不需要重新生成密钥对（重新生成等于换了身份，
 *   所有用户手里的旧公钥会立刻失效）。本命令就干这一件事。
 *
 * ## 用法
 *   node pub-of.mjs --key ~/.sunsetlinux-keys/channel.key
 *   node pub-of.mjs --key channel.key --write ~/.sunsetlinux-keys/channel.pub   # 顺便写回文件
 *   node pub-of.mjs --key channel.key --json                                    # 机器可读
 *   node pub-of.mjs --pub channel/channel.pub                                   # 只打印公钥指纹（核对用）
 *
 * 退出码：0 成功；1 私钥/公钥读不了或格式不对。
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {
  bold, cyan, die, dim, green, info, keyObjectToRawPub, loadPrivateKey, loadPublicKey,
  parseArgs, printHelp, publicKeyFingerprint, warn, wantsHelp, writeFileEnsured,
} from './common.mjs';

const HELP = `用法：node pub-of.mjs --key <私钥文件> [--write <公钥文件>] [--json]

从私钥推导公钥（Ed25519），并打印指纹。用途：
  · 私钥已放进 CI Secret、本机没有 channel.pub 时，把公钥补回来；
  · 核对「Secret 里的私钥」与「仓库里的 channel.pub」是不是同一对（指纹必须一致）。

选项：
  --key <文件>      私钥文件（PKCS#8 PEM，keygen.mjs 产出的 channel.key）【必需】
  --write <文件>    把公钥写到该文件（默认只打印）
  --json            以 JSON 输出（fields: pubkey, fingerprint, key_file）
  --help            显示本帮助`;

const spec = {
  key: { type: 'string', default: '' },
  pub: { type: 'string', default: '' },
  write: { type: 'string', default: '' },
  json: { type: 'boolean', default: false },
};

function main() {
  const argv = process.argv.slice(2);
  if (wantsHelp(argv)) return printHelp('sunsetlinux 频道公钥推导（pub-of）', HELP);
  const { values } = parseArgs(argv, spec);

  // ---- 模式二：只核对一个**已有公钥**的指纹（对称能力，用于"CI 私钥 vs 仓库公钥"核对）
  if (values.pub) {
    let fp;
    try {
      fp = publicKeyFingerprint(loadPublicKey(values.pub));
    } catch (e) {
      die(`读公钥失败：${e.message}`);
    }
    if (values.json) {
      process.stdout.write(`${JSON.stringify({ fingerprint: fp, pub_input: values.pub })}\n`);
    } else {
      process.stderr.write(`\n  ${green(fp)}   ${dim('← 与私钥侧 pub-of --key 打印的指纹对比，必须完全一致')}\n\n`);
      process.stdout.write(`${fp}\n`);
    }
    return;
  }

  if (!values.key) die('缺少 --key <私钥文件>（keygen.mjs 产出的 channel.key），或 --pub <公钥> 只查指纹');
  const keyFile = path.resolve(values.key);
  if (!fs.existsSync(keyFile)) die(`私钥文件不存在：${keyFile}`);

  // 权限体检：私钥不该是组/他人可读的（不阻断，但要吼一声）
  const mode = fs.statSync(keyFile).mode & 0o777;
  if (mode & 0o077) warn(`私钥权限过宽（${mode.toString(8)}），建议：chmod 600 ${keyFile}`);

  let pub, fingerprint;
  try {
    const priv = loadPrivateKey(keyFile);
    // ⚠️ 必须先用 createPublicKey 把私钥对象转成**公钥对象**：
    //    keyObjectToRawPub/publicKeyFingerprint 都只接受公钥对象，
    //    直接把私钥对象丢进去会报 "options.type is invalid. Received 'spki'"
    //    （SPKI 是公钥的编码，私钥只能导出 pkcs8）。实测踩过。
    const pubObj = crypto.createPublicKey(priv);
    pub = keyObjectToRawPub(pubObj).toString('base64');
    fingerprint = publicKeyFingerprint(pubObj);
  } catch (e) {
    die(`读私钥失败（不是 keygen.mjs 产出的 PKCS#8 Ed25519 私钥？）：${e.message}`);
  }

  if (values.write) {
    const out = path.resolve(values.write);
    writeFileEnsured(out, `${pub}\n`, { mode: 0o644 });
    info(`已写出公钥：${out}`);
  }

  if (values.json) {
    process.stdout.write(`${JSON.stringify({ pubkey: pub, fingerprint, key_file: keyFile })}\n`);
    return;
  }

  process.stderr.write(`\n${bold('从私钥推导出的公钥（可以公开）')}\n`);
  process.stderr.write(`  ${cyan(pub)}\n`);
  process.stderr.write(`  指纹  ${green(fingerprint)}   ${dim('← 让用户核对这个，防中间人替换')}\n\n`);
  process.stderr.write(`${bold('接下来把它放到该放的地方：')}\n`);
  process.stderr.write(`  1) 提交进仓库（channel 分支，CI 签名时用它做独立验签）：\n`);
  process.stderr.write(`       git checkout channel && node tools/channel/pub-of.mjs --key <私钥> --write channel/channel.pub\n`);
  process.stderr.write(`  2) 随清单 URL 一起发给用户（另走一个渠道，别和清单放同一处）\n`);
  process.stderr.write(`  ${dim('私钥本身永远不进仓库、不进聊天、不进工单。')}\n\n`);
  // stdout 只给公钥（一行），方便脚本取用
  process.stdout.write(`${pub}\n`);
}

main();
