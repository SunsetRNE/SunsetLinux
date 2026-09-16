#!/usr/bin/env node
/**
 * proroot 的**许可合规闸门**。
 *
 * proroot 是专有许可（`tools/proroot/LICENSE.proroot`）：
 *   · 允许：把**未修改**的二进制作为**完整应用包（APK）**的一部分再分发；
 *   · 禁止：再分发修改版；禁止独立于应用包的分发。
 *
 * 我们仓库是**公开**的，所以下面每一条都真的会被外部看到：
 *
 *   1. `libproroot*.so` **不得**被提交进仓库（dist/ 是 gitignore，别处都不行）；
 *   2. 工作流**不得**把裸 `.so` 传成 artifact / Release 资产（APK 里可以）；
 *   3. APK 里必须**5 个 .so 齐全**、**sha256 与 VENDOR.json 一致**（= 没有被 strip/改动），
 *      并且带上许可原文（许可第 4 条）；关于页要有 attribution（第 5 条，代码里查）；
 *   4. 解包/取出用的工具链不得用于"导出"这些 .so（`tools/proroot/fetch.mjs` 只写盘到
 *      dist/，且 .gitignore 覆盖）。
 *
 * 用法：node tools/proroot/compliance-test.mjs [--apk <path>]...
 *       （不给 --apk 就在 app/app/build/outputs/apk 里找已构建的 APK；一个都没有就跳过 APK 检查）
 * 退出码：0 通过；1 违例。
 */

import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const REPO = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const vendor = JSON.parse(readFileSync(join(REPO, 'tools/proroot/VENDOR.json'), 'utf8'));
const EXPECTED = Object.entries(vendor.files).map(([name, meta]) => ({ name, ...meta }));

let pass = 0;
let fail = 0;
const ok = (m) => { pass++; console.log(`  \x1b[32mok\x1b[0m   ${m}`); };
const bad = (m) => { fail++; console.log(`  \x1b[31mFAIL\x1b[0m ${m}`); };

const SKIP_DIRS = new Set(['.git', 'node_modules', 'build', 'dist', '.gradle', '.kotlin', 'outputs']);

/** 找出仓库里被跟踪/存在的裸 proroot 二进制（dist/build 之外都算违例）。 */
function findCommittedSos(dir = REPO, hits = []) {
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    const rel = relative(REPO, abs);
    if (SKIP_DIRS.has(name)) continue;
    let st;
    try { st = statSync(abs); } catch { continue; }
    if (st.isDirectory()) findCommittedSos(abs, hits);
    else if (/^libproroot.*\.so$/.test(name) || /^proroot-.*\.tar\.(gz|xz|zst)$/.test(name)) hits.push(rel);
  }
  return hits;
}

console.log('\n== proroot 许可合规 ==');
console.log(`   版本账本：proroot ${vendor.version}（${vendor.license.spdx}）`);

// ---- 1) 仓库里不得有裸二进制 ------------------------------------------------
{
  const hits = findCommittedSos();
  if (hits.length === 0) {
    ok('仓库里没有 proroot 二进制（只有 VENDOR.json 与许可原文）');
  } else {
    bad(
      `仓库里出现了 proroot 二进制 —— 许可只允许"随完整 APK"再分发，公开仓库里的文件不算：\n       ${hits.join('\n       ')}`,
    );
  }
}

// ---- 2) 工作流不得把它当独立资产上传 ----------------------------------------
{
  const wfDir = join(REPO, '.github/workflows');
  const offenders = [];
  for (const f of readdirSync(wfDir)) {
    if (!f.endsWith('.yml')) continue;
    const text = readFileSync(join(wfDir, f), 'utf8');
    // 只看"上传/发布"动作的参数里有没有裸 proroot 文件
    for (const [i, line] of text.split('\n').entries()) {
      if (!/libproroot|proroot-.*\.tar/.test(line)) continue;
      if (/upload-artifact|gh release|path:|files:/.test(line) || /path:\s*$/.test(line)) {
        offenders.push(`${f}:${i + 1} ${line.trim()}`);
      }
    }
  }
  if (offenders.length === 0) ok('工作流没有把 proroot 二进制当 artifact / Release 资产');
  else bad(`工作流里有可疑的上传行（裸 .so 不得独立分发）：\n       ${offenders.join('\n       ')}`);
}

// ---- 3) 关于页必须有 attribution（代码级）-----------------------------------
{
  const shell = readFileSync(join(REPO, 'app/app/src/main/java/io/github/sunsetrne/sunsetlinux/ui/AppShell.kt'), 'utf8');
  const hasAttr = /coderredlab\/proroot/.test(shell) && /proroot-LICENSE\.txt/.test(shell);
  if (hasAttr) ok('关于页有 proroot attribution 与许可原文指引（许可第 5 条）');
  else bad('关于页缺少 proroot attribution / 许可指引（许可第 5 条要求）');
}

// ---- 4) APK：5 个 .so 齐全、hash 一致、带许可 -------------------------------
const apkDirs = [
  join(REPO, 'app/app/build/outputs/apk'),
];
function collectApks() {
  const out = [];
  for (const base of apkDirs) {
    if (!existsSync(base)) continue;
    const walk = (d) => {
      for (const n of readdirSync(d)) {
        const p = join(d, n);
        const st = statSync(p);
        if (st.isDirectory()) walk(p);
        else if (n.endsWith('.apk')) out.push(p);
      }
    };
    walk(base);
  }
  return out;
}
const apks = process.argv.includes('--apk')
  ? process.argv.slice(process.argv.indexOf('--apk') + 1).filter((a) => a.endsWith('.apk'))
  : collectApks();

if (apks.length === 0) {
  console.log('   （没有已构建的 APK，跳过 APK 内部检查；CI 在 Android 构建之后会跑到）');
} else {
  for (const apk of apks) {
    const name = relative(REPO, apk);
    let list;
    try {
      list = execFileSync('python3', ['-c', `
import zipfile,sys
z=zipfile.ZipFile(sys.argv[1])
for n in z.namelist(): print(n)
`, apk], { encoding: 'utf8' }).split('\n').filter(Boolean);
    } catch (e) {
      bad(`读不了 APK 清单：${name}（${e.message}）`);
      continue;
    }
    const missing = EXPECTED.filter((e) => !list.includes(`lib/arm64-v8a/${e.name}`));
    if (missing.length) {
      bad(`${name} 缺少 proroot 的 .so：${missing.map((m) => m.name).join(', ')}`);
      continue;
    }
    // 逐字节核对（用 python 解压并算 sha256，避免把 600KB × N 读进 node）
    const digests = execFileSync('python3', ['-c', `
import zipfile,hashlib,sys,json
z=zipfile.ZipFile(sys.argv[1])
out={}
for n in sys.argv[2:]:
    out[n]=hashlib.sha256(z.read('lib/arm64-v8a/'+n)).hexdigest()
print(json.dumps(out))
`, apk, ...EXPECTED.map((e) => e.name)], { encoding: 'utf8' });
    const got = JSON.parse(digests);
    const mismatched = EXPECTED.filter((e) => got[e.name] !== e.sha256);
    if (mismatched.length) {
      bad(
        `${name} 里的 proroot 与上游不一致（被 strip/改过？许可第 2 条禁止再分发修改版）：` +
        mismatched.map((m) => m.name).join(', '),
      );
    } else {
      ok(`${name}：5 个 .so 齐全且 sha256 与上游一致（未修改）`);
    }
    const lic = list.find((n) => n.startsWith('assets/licenses/proroot-LICENSE.txt'));
    if (lic) ok(`${name}：带 proroot 许可原文（许可第 4 条）`);
    else bad(`${name} 里没有 assets/licenses/proroot-LICENSE.txt（许可第 4 条要求）`);
    if (list.some((n) => /^assets\/.*libproroot.*\.so$/.test(n))) {
      bad(`${name} 里有裸 proroot .so 被当 asset 打包（应在 lib/ 下）`);
    }
  }
}

console.log(`\n== 结果：${pass} 通过 / ${fail} 失败 ==`);
process.exit(fail === 0 ? 0 : 1);
