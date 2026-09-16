#!/usr/bin/env node
/**
 * 取 proroot 二进制（**构建期**，只为了打进 APK 的 jniLibs）。
 *
 * ## 为什么不能把它提交进仓库 / 挂成下载资产
 *
 * proroot 是**专有许可**（见 `LICENSE.proroot` 全文）：
 *   - 允许：把**未修改**的二进制作为**完整应用包（APK/AAB）**的一部分再分发；
 *   - 禁止：再分发修改版；禁止独立于应用包的分发（仓库文件、Release 资产、
 *     CI workflow artifact —— 我们仓库是公开的，都算"独立分发"）。
 *
 * 所以 `tools/proroot/VENDOR.json`（版本账本 + sha256）与许可原文进仓库，
 * **二进制不进**（`dist/` 已被 .gitignore 忽略），每次构建现取现用；
 * 发布产物里只有 APK/zip，永远没有裸 `libproroot*.so`（CI 有断言）。
 *
 * ## 用法
 *
 *   node tools/proroot/fetch.mjs                       # 取到 dist/proroot/（缺什么取什么）
 *   node tools/proroot/fetch.mjs --check               # 只校验（不联网），给 CI/Gradle 用
 *   node tools/proroot/fetch.mjs --out <dir>           # 指定目录
 *   node tools/proroot/fetch.mjs --update-hashes       # 升版本时重新记账（会改 VENDOR.json）
 *   node tools/proroot/fetch.mjs --print-version       # 只打印版本号（构建脚本记账用）
 *
 * 退出码：0 成功；1 失败（下载/校验不符/缺文件）。
 */

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const REPO = join(HERE, '..', '..');
const VENDOR_PATH = join(HERE, 'VENDOR.json');

const argv = process.argv.slice(2);
const has = (flag) => argv.includes(flag);
const opt = (flag, dflt) => {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};

const vendor = JSON.parse(readFileSync(VENDOR_PATH, 'utf8'));
const OUT = opt('--out', join(REPO, 'dist', 'proroot'));
const CHECK_ONLY = has('--check');
const UPDATE = has('--update-hashes');
const PRINT_VERSION = has('--print-version');

if (PRINT_VERSION) {
  console.log(vendor.version);
  process.exit(0);
}

const EXPECTED_FILE_ORDER = Object.keys(vendor.files);
const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

function die(msg) {
  console.error(`✗ proroot: ${msg}`);
  process.exit(1);
}

function verifyFile(name, buf, expected) {
  const got = sha256(buf);
  if (expected && got !== expected) {
    die(
      `${name} 的 sha256 与 VENDOR.json 不符\n` +
      `    期望 ${expected}\n    实际 ${got}\n` +
      `    要么上游重新发了同 tag 的产物（罕见，需人工复核），要么下载被中间人改了 —— 都不要继续构建。`,
    );
  }
  return got;
}

async function main() {
  if (UPDATE) {
    // 只在"升版本"时用：从当前 vendor.release_url 下载并按实际内容重新记账。
    const tag = vendor.version;
    const base = `https://github.com/coderredlab/proroot/releases/download/${tag}`;
    console.error(`· 重新记账：${base}`);
    const files = {};
    for (const name of EXPECTED_FILE_ORDER) {
      const res = await fetch(`${base}/${name}`);
      if (!res.ok) die(`下载 ${name} 失败：HTTP ${res.status}`);
      const buf = Buffer.from(await res.arrayBuffer());
      // 记新哈希前先把"产物的 sha256"打出来，便于人工核对上游是否换过产物
      files[name] = { size: buf.length, sha256: sha256(buf) };
      console.error(`  ${name}  ${buf.length} B  ${files[name].sha256}`);
    }
    vendor.files = files;
    vendor.total_size = Object.values(files).reduce((a, f) => a + f.size, 0);
    writeFileSync(VENDOR_PATH, `${JSON.stringify(vendor, null, 2)}\n`);
    console.error('· VENDOR.json 已更新（请复核许可是否变化，必要时更新 LICENSE.proroot 与 obligations）');
  }

  if (CHECK_ONLY) {
    const bad = [];
    for (const [name, meta] of Object.entries(vendor.files)) {
      const p = join(OUT, name);
      if (!existsSync(p)) {
        bad.push(`缺 ${name}`);
        continue;
      }
      const buf = readFileSync(p);
      const got = sha256(buf);
      if (got !== meta.sha256) bad.push(`${name} sha256 不符`);
    }
    if (bad.length) {
      die(
        `${OUT} 里的 proroot 不完整/不符：\n    - ${bad.join('\n    - ')}\n` +
        `    跑一次：node tools/proroot/fetch.mjs`,
      );
    }
    console.log(`✅ proroot ${vendor.version} 完整（${Object.keys(vendor.files).length} 个 .so，${(vendor.total_size / 1024).toFixed(0)} KB）`);
    return;
  }

  mkdirSync(OUT, { recursive: true });
  let fetched = 0;
  for (const [name, meta] of Object.entries(vendor.files)) {
    const dst = join(OUT, name);
    if (existsSync(dst) && statSync(dst).size === meta.size && sha256(readFileSync(dst)) === meta.sha256) {
      continue;   // 已有且一致 → 不重复下载（离线重建时这点很重要）
    }
    const url = `${vendor.release_url.replace('/tag/', '/download/')}/${name}`;
    const res = await fetch(url);
    if (!res.ok) die(`下载 ${name} 失败：HTTP ${res.status}（${url}）`);
    const buf = Buffer.from(await res.arrayBuffer());
    verifyFile(name, buf, meta.sha256);
    writeFileSync(dst, buf);
    fetched++;
  }
  // 许可原文一并放到输出目录：APK 的 assets 要带它（许可第 4 条）
  const lic = readFileSync(join(HERE, vendor.license.file));
  if (sha256(lic) !== vendor.license.sha256) {
    die(`${vendor.license.file} 与 VENDOR.json 里记的 sha256 不符（被人改过？复核后跑 --update-hashes）`);
  }
  writeFileSync(join(OUT, 'LICENSE.proroot'), lic);

  console.log(
    `✅ proroot ${vendor.version} → ${OUT}（本次下载 ${fetched} 个，共 ${Object.keys(vendor.files).length} 个 .so，` +
    `${(vendor.total_size / 1024).toFixed(0)} KB）`,
  );
  console.log('   许可：专有 —— 只能随 APK 分发，禁止作为独立资产/仓库文件再分发（见 tools/proroot/README.md）');
}

await main();
